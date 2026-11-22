package hmgapi.vehicle;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.IBinder;

import hmgapi.engine.CarMap;
import hmgapi.engine.SystemCheck;
import hmgapi.engine.Vehicle;
import hmgapi.hook.Points;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * HMG-API's vehicle service: answers readings (hmgapi.engine.Vehicle) for hmgapid,
 * which has already checked that the asking mod declared them, and commands
 * (Commands, with their guards). Run by hmgapid as a core service while an enabled
 * mod declares readings or commands; listens on the abstract socket @hmgapi-vehicle
 * and serves only root (hmgapid).
 *
 *   request:  "read <name> [<name>...]\n"
 *   answer:   "<name> <value>\n" per name; "-" when the car doesn't answer
 *   request:  "do <mod|-> <command> [<arg>...]\n"
 *   answer:   "ok ...\n" or "refused: <why>\n"
 *
 * Each value is read through the car's own service interface (hook map points),
 * then reused for the reading's freshness window, whoever asks.
 */
public final class Hub {
    private Hub() {}

    static final String SOCKET = "hmgapi-vehicle";
    static final String HCAN_JAR = "/system/framework/com.hkmc.ext.hcan.jar";
    static final String ALLOW = "/data/hmgapi/settings/allow/";

    static ClassLoader hcan;
    static SystemCheck.Facts facts;
    static final Map<String, Object> services = new HashMap<String, Object>();
    static final Map<String, String> values = new HashMap<String, String>();
    static final Map<String, Long> readAt = new HashMap<String, Long>();
    static long lastSleepGap = Long.MIN_VALUE;
    static int lastReady = -1;

    /**
     * Detects QuickBoot STR resume (elapsedRealtime advances while uptimeMillis freezes)
     * so per-start command guards and cached readings never carry stale state across drives.
     */
    static synchronized boolean checkSession() {
        long sleepGap = android.os.SystemClock.elapsedRealtime() - android.os.SystemClock.uptimeMillis();
        if (lastSleepGap == Long.MIN_VALUE) {
            lastSleepGap = sleepGap;
            return false;
        }
        boolean woke = sleepGap - lastSleepGap >= 3000;
        lastSleepGap = sleepGap;
        if (woke) {
            System.out.println("vehicle: system resumed from suspend; refreshing session state");
            values.clear();
            readAt.clear();
            callbacks.onWake();
            callbacks.powerAfterWake();
            if (commands != null) commands.onNewSession();
            lastReady = -1;
            parking.reset();
            shutdownSent = false;
            confirmedOff = false;
            fire("wake");
        }
        return woke;
    }

    // ---- car events (EventRun, Parking): state/events.txt, the owner's rule in settings/park_rule

    static final String STATE = "/data/hmgapi/state/";
    static final String PARK_RULE = "/data/hmgapi/settings/park_rule";
    static final String ACTION = "hmgapi.intent.action.CAR_EVENT";
    static final Parking parking = new Parking();
    static volatile java.util.List<EventRun.Decl> eventDecls = new java.util.ArrayList<EventRun.Decl>();
    /**
     * shutdownSent: "shutdown" ran for this off. confirmedOff: HMG-API confirmed it. The power
     * service never clears a responder's confirmation, so a later off wouldn't wait for HMG-API;
     * but a new registration during a shutdown that goes on would hold it up (unconfirmed) for
     * the car's 10 s. So the responder is registered again only once the car is on again (an off
     * that didn't end in a shutdown) or after a wake.
     */
    static boolean shutdownSent, confirmedOff;
    static final java.util.concurrent.ExecutorService eventQueue = java.util.concurrent.Executors.newSingleThreadExecutor();

    static java.util.List<EventRun.Decl> readEvents() {
        try {
            return EventRun.parse(hmgapi.engine.Engine.read(new java.io.File(STATE + "events.txt")));
        } catch (Exception e) {
            return new java.util.ArrayList<EventRun.Decl>();
        }
    }

    static String parkRule() {
        try {
            String r = Parking.rule(hmgapi.engine.Engine.read(new java.io.File(PARK_RULE)));
            return r == null ? Parking.DOOR : r;
        } catch (Exception e) {
            return Parking.DOOR;   // the owner hasn't chosen
        }
    }

    /** body control's door status (pushed): driver, passenger, rear left, rear right open */
    static void doors(int drv, int pas, int rl, int rr) {
        synchronized (Hub.class) {
            int[] was = parking.doors.clone();
            parking.doors[0] = drv;
            parking.doors[1] = pas;
            parking.doors[2] = rl;
            parking.doors[3] = rr;
            if (!java.util.Arrays.equals(was, parking.doors)) System.out.println("vehicle: doors open " + drv + " " + pas + " " + rl + " " + rr);
            step();
        }
    }

    /** the power status, from the power service (read each tick, or pushed to the responder) */
    static void power(int status) {
        synchronized (Hub.class) {
            if (status != parking.power) System.out.println("vehicle: power " + Parking.powerName(status) + " (" + status + ")");
            parking.power = status;
            step();
        }
    }

    /** Parking's verdict on the newest values (Hub.class held) */
    static void step() {
        StringBuilder log = new StringBuilder();
        String e = parking.update(android.os.SystemClock.elapsedRealtime(), log);
        if (log.length() > 0) System.out.println("vehicle: " + log);
        if (e != null) fire(e);
    }

    /** the car's off: run "shutdown" (at most Vehicle.SHUTDOWN_SECONDS + 1 s), then tell the power service HMG-API is done */
    static void systemOff() {
        synchronized (Hub.class) {
            if (shutdownSent) return;
            shutdownSent = true;
        }
        System.out.println("vehicle: the head unit is shutting down (system off)");
        Thread t = new Thread("vehicle-shutdown") {
            @Override
            public void run() {
                try {
                    runEvent("shutdown", (hmgapi.engine.Vehicle.SHUTDOWN_SECONDS + 1) * 1000L);
                } catch (Throwable x) {
                    System.out.println("vehicle: shutdown event: " + x);
                } finally {
                    System.out.println(callbacks.confirmSystemOff() ? "vehicle: confirmed the system off" : "vehicle: couldn't confirm the system off (the car goes on after its 10 s)");
                    synchronized (Hub.class) {
                        confirmedOff = true;
                    }
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /** parked, unparked, wake: one after another, off the tick */
    static void fire(final String event) {
        if (!EventRun.asked(eventDecls, event)) {
            System.out.println("vehicle: " + event + " (no mod asked for it)");
            return;
        }
        System.out.println("vehicle: " + event);
        eventQueue.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runEvent(event, hmgapi.engine.Vehicle.EVENT_SECONDS * 1000L);
                } catch (Throwable x) {
                    System.out.println("vehicle: " + event + " event: " + x);
                }
            }
        });
    }

    static void runEvent(String event, long limitMs) {
        new EventRun(new Unit()).run(event, eventDecls, limitMs);
    }

    /** the tick's part: what the mods asked for, the power status and gear, the responder */
    static void eventsTick() {
        java.util.List<EventRun.Decl> d = readEvents();
        eventDecls = d;
        boolean again = false;
        synchronized (Hub.class) {
            if (shutdownSent && parking.power == Parking.ACC_ON) {   // the off didn't end in a shutdown: ready for the next one
                again = confirmedOff;
                shutdownSent = confirmedOff = false;
            }
        }
        if (again) callbacks.powerAfterWake();
        boolean shuttingDown;
        synchronized (Hub.class) {
            shuttingDown = shutdownSent;
        }
        if (!shuttingDown) callbacks.ensurePower(EventRun.asked(d, "shutdown"));   // during a shutdown: no new registration
        // the parked state is followed whether or not a mod asked for the events: the "parked" reading
        // is it (home Wi-Fi reads it), and without the power and gear here it never saw the car go off
        if (!Points.ready()) return;
        int p = -1, g = -1;
        try {
            if (Points.has("framework.power_service.power_status")) p = num(call(power(), "framework.power_service.power_status"));
        } catch (Throwable t) {
            services.remove("framework.car_services.power");
        }
        try {
            g = num(call(system(), "framework.system_service.gear_p_status")) == 1 ? 1 : 0;
        } catch (Throwable t) {
            g = -1;
        }
        synchronized (Hub.class) {
            parking.rule = parkRule();
            parking.park = g;
            if (p >= 0) parking.power = p;
            step();
        }
    }

    /** the unit's side of an event: hmgapid's service control, services.status, am broadcast */
    static final class Unit implements EventRun.Unit {
        static int seq;

        public String state(String mod, String service) {
            try {
                for (String l : hmgapi.engine.Engine.read(new java.io.File(STATE + "services.status")).split("\n")) {
                    String[] w = l.trim().split(" ");
                    if (w.length >= 3 && w[0].equals(mod) && w[1].equals(service)) return w[2];
                }
            } catch (Exception e) {
                // no status yet
            }
            return null;
        }

        public void ctl(String verb, String mod, String service) {
            java.io.File dir = new java.io.File(STATE + "ctl");
            dir.mkdirs();
            String name;
            synchronized (Unit.class) {
                name = "vehicle-" + android.os.Process.myPid() + "-" + (++seq);
            }
            java.io.File tmp = new java.io.File(dir, name + ".tmp"), f = new java.io.File(dir, name);
            try {
                java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
                try {
                    o.write((verb + " " + mod + " " + service).getBytes("UTF-8"));
                } finally {
                    o.close();
                }
                if (!tmp.renameTo(f)) System.out.println("vehicle: can't ask hmgapid to " + verb + " " + mod + "/" + service);
            } catch (Exception e) {
                System.out.println("vehicle: can't ask hmgapid to " + verb + " " + mod + "/" + service + ": " + e);
            }
        }

        public EventRun.Waiter broadcast(String event) {
            try {
                // ordered (am waits for every receiver), foreground (receivers get it at once, 10 s at most each)
                final Process p = new ProcessBuilder("/system/bin/am", "broadcast", "-a", ACTION, "--es", "event", event,
                        "-f", String.valueOf(0x10000000)).redirectErrorStream(true).start();
                final java.io.InputStream in = p.getInputStream();
                return new EventRun.Waiter() {
                    boolean exited;

                    public boolean done() {
                        if (exited) return true;
                        try {
                            while (in.available() > 0) in.read();   // am's output, so it never blocks on a full pipe
                            p.exitValue();
                            exited = true;
                        } catch (IllegalThreadStateException e) {
                            // still running
                        } catch (java.io.IOException e) {
                            exited = true;
                        }
                        return exited;
                    }

                    public void cancel() {
                        p.destroy();
                    }
                };
            } catch (Exception e) {
                System.out.println("vehicle: can't send the event to apps: " + e);
                return null;
            }
        }

        public long now() { return android.os.SystemClock.elapsedRealtime(); }

        public void pause(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void log(String line) { System.out.println("vehicle: " + line); }
    }

    public static void main(String[] args) throws Exception {
        facts = new SystemCheck.Facts("", null);
        Points.use(CarMap.load("", facts, null));
        if (!Points.ready()) {
            System.out.println("vehicle: no good hook map for this car; every reading answers -");
        }
        try {
            hcan = new dalvik.system.PathClassLoader(HCAN_JAR, Hub.class.getClassLoader());
        } catch (Throwable t) {
            System.out.println("vehicle: no " + HCAN_JAR + ": " + t);
        }
        commands = new Commands(new CarServices());
        final MicomWatch watch = new MicomWatch();
        checkSession();
        callbacks.ensure();
        eventDecls = readEvents();
        new EventRun(new Unit()).settle(eventDecls);   // event scripts a run before this one left "exited"
        try {
            eventsTick();
        } catch (Throwable t) {
            System.out.println("vehicle: car events: " + t);
        }
        Thread tick = new Thread("vehicle-tick") {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                        checkSession();
                        int rdy = -1;
                        try { if (Points.ready()) rdy = hev("framework.hev_flag.ev_ready"); } catch (Throwable ignored) {}
                        synchronized (Hub.class) {
                            if (lastReady == 1 && rdy == 0 && commands != null) {
                                System.out.println("vehicle: EV_RDY off; resetting per-drive command guards");
                                commands.onNewSession();
                            }
                            if (rdy == 0 || rdy == 1) lastReady = rdy;
                        }
                        commands.tick(declared());
                        watch.sync();
                        callbacks.ensure();
                        eventsTick();
                    } catch (Throwable e) {
                        System.out.println("vehicle: tick: " + e);
                    }
                }
            }
        };
        tick.setDaemon(true);
        tick.start();
        LocalServerSocket server = new LocalServerSocket(SOCKET);
        System.out.println("vehicle: listening on @" + SOCKET);
        while (true) {
            LocalSocket s = null;
            try {
                s = server.accept();
                serve(s);
            } catch (Throwable t) {
                System.out.println("vehicle: " + t);
                if (s == null) try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            } finally {
                close(s);
            }
        }
    }

    /**
     * Closes an accepted socket's fd. On these units (KitKat) LocalServerSocket.accept() never
     * marks the socket's fd as its own, so LocalSocket.close() only drops it: the fd stayed
     * open, hmgapid never saw the end of an answer (every read took its 3 s timeout, #145),
     * and each connection leaked an fd.
     */
    static void close(LocalSocket s) {
        if (s == null) return;
        java.io.FileDescriptor fd = null;
        try { fd = s.getFileDescriptor(); } catch (Throwable ignored) {}
        try { s.shutdownOutput(); } catch (Throwable ignored) {}
        try { s.shutdownInput(); } catch (Throwable ignored) {}
        try {
            if (fd != null && fd.valid()) libcore.io.Libcore.os.close(fd);
        } catch (Throwable t) {
            System.out.println("vehicle: close: " + t);
        }
        try {
            s.close();
        } catch (Exception x) {
            // gone already
        }
    }

    static void serve(LocalSocket s) throws Exception {
        int uid = s.getPeerCredentials().getUid();
        if (uid != 0 && uid != 1000) return;   // hmgapid (it checks what each mod declared); system_server only passes the light status
        if (checkSession()) callbacks.ensure();
        s.setSoTimeout(2000);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"), 1024);
        String line = in.readLine();
        if (line != null && line.startsWith("light ")) {   // hmgapi.hook.LightRelay
            callbacks.lightStatus(line.substring(6).trim());
            return;
        }
        if (uid != 0) return;
        if (line != null && line.startsWith("do ")) {
            String[] w = line.substring(3).trim().split(" +");
            String answer = w.length < 2 ? "refused: do <mod> <command>"
                    : commands.handle(w[0], w[1], java.util.Arrays.copyOfRange(w, 2, w.length));
            OutputStream o = s.getOutputStream();
            o.write((answer + "\n").getBytes("UTF-8"));
            o.flush();
            return;
        }
        if (line == null || !line.startsWith("read ")) return;
        StringBuilder out = new StringBuilder();
        for (String name : line.substring(5).trim().split(" +")) {
            if (!Vehicle.READINGS.containsKey(name)) continue;
            out.append(name).append(' ').append(value(name)).append('\n');
        }
        OutputStream o = s.getOutputStream();
        o.write(out.toString().getBytes("UTF-8"));
        o.flush();
    }

    /** the reading's value, from the car or from the last read within its freshness window */
    static synchronized String value(String name) {
        long now = android.os.SystemClock.elapsedRealtime();
        Long at = readAt.get(name);
        if (at != null && now - at < Vehicle.READINGS.get(name).freshMs) return values.get(name);
        String v;
        try {
            v = read(name);
        } catch (Throwable t) {
            services.clear();   // a service that restarted gets looked up again
            v = null;
        }
        if (v == null) v = "-";
        values.put(name, v);
        readAt.put(name, now);
        return v;
    }

    static String read(String name) throws Exception {
        if (!Points.ready()) return null;
        if ("gear.park".equals(name)) return bool(num(call(system(), "framework.system_service.gear_p_status")) == 1);
        if ("power".equals(name)) {
            if (!Points.has("framework.power_service.power_status")) return null;
            return Parking.powerName(num(call(power(), "framework.power_service.power_status")));
        }
        if ("doors.closed".equals(name)) {
            int c;
            synchronized (Hub.class) {
                c = parking.doorsClosed();
            }
            return c < 0 ? null : String.valueOf(c);
        }
        if ("parked".equals(name)) {
            synchronized (Hub.class) {
                return bool(parking.parked);
            }
        }
        if ("parking_brake".equals(name)) {   // EPB status: 2 engaged, 1 released (EV6, 2026-10-31)
            int st = num(call(system(), "framework.system_service.parking_brake_status"));
            return st == 2 ? "1" : st == 1 ? "0" : null;
        }
        if ("speed".equals(name)) return dec(((Float) call(hscan(), "framework.hscan_service.car_speed", (byte) 0)).floatValue());
        if ("moving".equals(name)) return bool(Boolean.TRUE.equals(call(hscan(), "framework.hscan_service.moving")));
        if ("driving.restricted".equals(name)) return bool(num(regulation()) == 1);
        if ("steering.angle".equals(name)) {
            float f = (Float) call(hscan(), "framework.hscan_service.steering_angle");
            if (Float.isNaN(f) || f < 0 || f > 65535 || Math.abs(f - Math.round(f)) > .001f) return null;
            int raw = Math.round(f);   // signed 16 bits, 0.1 degree (as Steering Arcade found)
            return dec((raw < 0x8000 ? raw : raw - 0x10000) / 10f);
        }
        if ("pedal.accel".equals(name)) return dec((Float) call(hscan(), "framework.hscan_service.accel_pedal"));
        if ("brake.pressure".equals(name)) return dec((Float) call(hscan(), "framework.hscan_service.brake_pressure"));
        if ("battery12v.soc".equals(name)) return valid(num(call(hscan(), "framework.hscan_service.battery_12v_soc")), 0, 100);
        if ("battery12v.sensor_state".equals(name)) return mapped(hscan(), "framework.hscan_service.battery_12v_sensor_state");
        if ("battery12v.sensor_invalid".equals(name)) return mapped(hscan(), "framework.hscan_service.battery_12v_sensor_invalid");
        if ("battery12v.sensor_error".equals(name)) return mapped(hscan(), "framework.hscan_service.battery_12v_sensor_error");
        if ("battery12v.saver".equals(name)) return mapped(hscan(), "framework.hscan_service.battery_12v_saver");
        if ("hv.health".equals(name)) return valid(num(call(hscan(), "framework.hscan_service.hv_health")), 0, 255);
        if ("charging".equals(name)) return String.valueOf(num(call(hscan(), "framework.hscan_service.charging_status")));
        if ("charging.plugged".equals(name)) return readPluggedIn();
        if ("hv.soc".equals(name)) {
            int half = hev("framework.hev_flag.display_soc");
            return half < 0 || half > 200 ? null : dec(half / 2f);
        }
        if ("hv.ready".equals(name)) return hevRaw("framework.hev_flag.ev_ready");
        if ("hv.conditioning".equals(name)) return hevRaw("framework.hev_flag.battery_conditioning");
        if ("hv.conditioning_active".equals(name)) return valid(num(call(bcm(), "framework.bcm_service.batt_cond_indicator")), 0, 1);
        if ("hv.conditioning_failure".equals(name)) return String.valueOf(num(call(hev(), "framework.hev_service.hev_status",
                Points.get(null, "framework.hev_flag.batt_cond_failure", null))));
        if ("hv.battery_temp_min".equals(name)) {
            int t = callbacks.packTempMin(500);
            return t == Integer.MIN_VALUE ? null : String.valueOf(t);
        }
        if ("conditioning.state".equals(name)) return commands == null ? null : commands.conditioningState;
        if (name.startsWith("seat_heat.") || name.startsWith("seat_vent.") || name.startsWith("seat_state.")) {
            String seat = name.substring(name.indexOf('.') + 1);
            String id = seatId(seat);
            int st = num(call(bcm(), "framework.bcm_service." + id + "_seat_heat_state"));
            if (name.startsWith("seat_state.")) return String.valueOf(st);
            if (st == 0 || st == 0xf || st < 0) return null;   // not known yet / invalid
            if (name.startsWith("seat_heat.")) {
                if (st < 6 || st > 8) return "0";
                boolean two = num(call(bcm(), "framework.bcm_service." + id + "_seat_heat_option")) == 1;   // two levels: 1, 2
                return String.valueOf(two ? (st == 6 ? 1 : 2) : st - 5);   // 6-8: heater low-high
            }
            return String.valueOf(st >= 3 && st <= 5 ? st - 2 : 0);   // 3-5: ventilation low-high
        }
        if ("wheel_heat".equals(name)) return callbacks.wheelHeat == Integer.MIN_VALUE ? null : String.valueOf(callbacks.wheelHeat);
        if ("defogger".equals(name)) return callbacks.defogger == Integer.MIN_VALUE ? null : String.valueOf(callbacks.defogger);
        if ("outside_temp".equals(name) || "outside_temp_f".equals(name)) {
            // climate control's status (pushed): Fahrenheit always; Celsius when the car's is valid, else converted
            long now = android.os.SystemClock.elapsedRealtime();
            int f10 = callbacks.outside.f10(now), c10 = callbacks.outside.c10(now);
            if (f10 == Integer.MIN_VALUE) return null;
            if ("outside_temp_f".equals(name)) return dec(f10 / 10f);
            return dec(c10 != Integer.MIN_VALUE ? c10 / 10f : (f10 / 10f - 32f) * 5f / 9f);
        }
        if ("odometer".equals(name)) {
            int tenths = hev("framework.hev_flag.odometer");
            return tenths < 0 ? null : dec(tenths / 10f);
        }
        return null;
    }

    static String readPluggedIn() throws Exception {
        Object hs = hscan();
        if (hs == null) return null;
        int chg = num(call(hs, "framework.hscan_service.charging_status"));
        if (chg > 0 && chg < 0xff) return "1";
        int conn = optHscanInt(hs, "getConnectStatus");
        if (conn > 0 && conn < 0x0f) return "1";
        int pd = optHscanInt(hs, "getCF_OBC_PD_Stat");
        if (pd == 1 || pd == 2) return "1";
        if (optHscanBool(hs, "isConnectExternalEquipment")) return "1";
        return (chg == 0 || conn == 0 || pd == 0) ? "0" : null;
    }

    static int optHscanInt(Object svc, String methodName) {
        try {
            Object v = svc.getClass().getMethod(methodName).invoke(svc);
            return v instanceof Number ? ((Number) v).intValue() : Integer.MIN_VALUE;
        } catch (Throwable ignored) {
            return Integer.MIN_VALUE;
        }
    }

    static boolean optHscanBool(Object svc, String methodName) {
        try {
            Object v = svc.getClass().getMethod(methodName).invoke(svc);
            return Boolean.TRUE.equals(v);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static int hev(String flag) throws Exception {
        int f = (Integer) Points.get(null, flag, null);
        int v = num(call(service("framework.car_services.hev", "framework.hev_service_stub.as_interface", null), "framework.hev_service.hev_status", f));
        return v == 0xffff ? -1 : v;   // the car's "invalid"
    }

    static String hevRaw(String flag) throws Exception {
        int v = hev(flag);
        return v < 0 ? null : String.valueOf(v);
    }

    static Object system() throws Exception {
        return service("framework.car_services.system", "framework.system_service_stub.as_interface", null);
    }

    static Object hscan() throws Exception {
        if (hcan == null) return null;
        return service("framework.hscan_adapter.service_name", "framework.hscan_service_stub.as_interface", hcan);
    }

    static String seatId(String seat) {
        return "driver".equals(seat) ? "drv" : "passenger".equals(seat) ? "ast" : "rear_left".equals(seat) ? "rl" : "rr";
    }

    static Object power() throws Exception {
        return service("framework.car_services.power", "framework.power_service_stub.as_interface", null);
    }

    static Object usm() throws Exception {
        return service("framework.car_services.usm", "framework.usm_service_stub.as_interface", null);
    }

    static Object pgs() throws Exception {
        return service("framework.car_services.pgs", "framework.pgs_service_stub.as_interface", null);
    }

    static Object bcm() throws Exception {
        return service("framework.car_services.bcm", "framework.bcm_service_stub.as_interface", null);
    }

    static Object vr() throws Exception {
        return service("framework.car_services.vr", "framework.vr_service_stub.as_interface", null);
    }

    static Object hev() throws Exception {
        return service("framework.car_services.hev", "framework.hev_service_stub.as_interface", null);
    }

    static Object cluster() throws Exception {
        return service("framework.car_services.cluster", "framework.cluster_service_stub.as_interface", null);
    }

    static Object datc() throws Exception {
        return service("framework.car_services.datc", "framework.datc_service_stub.as_interface", null);
    }

    static Object factory() throws Exception {
        return service("framework.car_services.factory", "framework.factory_service_stub.as_interface", null);
    }

    static Commands commands;
    static final CarCallbacks callbacks = new CarCallbacks();

    /** state/commands.txt: mod -> the commands it declared (enabled mods only) */
    static Map<String, java.util.Set<String>> declared() {
        Map<String, java.util.Set<String>> m = new HashMap<String, java.util.Set<String>>();
        try {
            for (String l : hmgapi.engine.Engine.read(new java.io.File("/data/hmgapi/state/commands.txt")).split("\n")) {
                String[] w = l.trim().split(" ");
                if (w.length != 2) continue;
                java.util.Set<String> c = m.get(w[0]);
                if (c == null) m.put(w[0], c = new java.util.HashSet<String>());
                c.add(w[1]);
            }
        } catch (Exception e) {
            // none
        }
        return m;
    }

    /** the car's side of the commands: its own services, by the hook map's names */
    static final class CarServices implements Commands.Car {
        static Object must(Object service, String what) {
            if (service == null) throw new IllegalStateException("no " + what + " service");
            return service;
        }

        static void run(Object target, String id, Object... args) {
            try {
                Points.call(target.getClass().getClassLoader(), id, target, args);
            } catch (RuntimeException e) {
                services.clear();
                throw e;
            }
        }

        static Object get(String what) {
            try {
                if ("usm".equals(what)) return must(usm(), what);
                if ("pgs".equals(what)) return must(pgs(), what);
                if ("bcm".equals(what)) return must(bcm(), what);
                if ("vr".equals(what)) return must(vr(), what);
                if ("hev".equals(what)) return must(hev(), what);
                if ("factory".equals(what)) return must(factory(), what);
                return must(system(), what);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public boolean park() {
            try {
                return num(call(system(), "framework.system_service.gear_p_status")) == 1;
            } catch (Exception e) {
                return false;
            }
        }

        public void chargeDoor(boolean close) { run(get("bcm"), "framework.bcm_service.charge_door", close); }

        public int seatOption(String seat) {
            try {
                return num(call(get("bcm"), "framework.bcm_service." + seatId(seat) + "_seat_heat_option"));
            } catch (RuntimeException e) {
                return -1;
            }
        }

        public void seatStep(String seat, int step) {
            String id = "driver".equals(seat) ? "seat_heat_driver" : "passenger".equals(seat) ? "seat_heat_passenger"
                    : "rear_left".equals(seat) ? "seat_heat_rear_left" : "seat_heat_rear_right";
            run(get("usm"), "framework.usm_service." + id, step);
        }

        public void wheelHeat(int on) { run(get("vr"), "framework.vr_service.wheel_heat", on); }

        public void conditioningRoute(int km) { run(get("hev"), "framework.hev_service.batt_cond_route_distance", km); }

        public void conditioningTime(int minutes) { run(get("hev"), "framework.hev_service.batt_cond_remain_time", minutes); }

        public void conditioningStation(int a, int b) { run(get("hev"), "framework.hev_service.batt_cond_station", a, b); }

        public int ready() {
            try {
                return hev("framework.hev_flag.ev_ready");
            } catch (Exception e) {
                return -1;
            }
        }

        public int conditioningSetting() {
            try {
                return hev("framework.hev_flag.battery_conditioning");
            } catch (Exception e) {
                return -1;
            }
        }

        public int conditioningIndicator() {
            try {
                int v = num(call(get("bcm"), "framework.bcm_service.batt_cond_indicator"));
                return v == 0 || v == 1 ? v : -1;
            } catch (RuntimeException e) {
                return -1;
            }
        }

        public int packTempMin() { return callbacks.packTempMin(1000); }

        public String model() {
            String m = facts == null ? null : facts.get("model");
            return m == null ? "" : m;
        }

        public void lightBrightness(int level) { run(get("system"), "framework.system_service.sml_configuration", 5, level); }

        public void lightColor(int r, int g, int b) {
            run(get("system"), "framework.system_service.sml_rgb", two(r), two(g), two(b));
        }

        static byte[] two(int v) {
            return new byte[] { (byte) (v >> 8), (byte) v };
        }

        public int[] lightState() {
            byte[] s = callbacks.sml;
            return s == null ? null : light(s);
        }

        /** the car's status bytes as r, g, b (raw), brightness (raw) */
        static int[] light(byte[] s) {
            return new int[] { (s[0] & 255) << 8 | s[1] & 255, (s[2] & 255) << 8 | s[3] & 255, (s[4] & 255) << 8 | s[5] & 255, s[6] & 255 };
        }

        public void lightStatus() { run(get("system"), "framework.system_service.sml_status"); }

        public int guideLines() { return num(call(get("pgs"), "framework.pgs_service.guide_lines")); }

        public void setGuideLines(int state) { run(get("pgs"), "framework.pgs_service.request_guide_lines", state); }

        private final java.util.concurrent.atomic.AtomicInteger noticeGen = new java.util.concurrent.atomic.AtomicInteger();

        public void hideStartupNotice() {
            final int gen = noticeGen.incrementAndGet();
            run(get("factory"), "framework.factory_service.hide_consent_screen");
            Thread t = new Thread("startup-notice-confirm") {
                @Override
                public void run() {
                    long[] gaps = { 150, 250, 400, 700, 1100 };
                    for (long g : gaps) {
                        pause(g);
                        if (noticeGen.get() != gen) return;
                        try {
                            if (!park()) return;
                            CarServices.run(get("factory"), "framework.factory_service.hide_consent_screen");
                        } catch (Throwable ignored) {}
                    }
                }
            };
            t.setDaemon(true);
            t.start();
        }

        public boolean noticeAllowed() { return new java.io.File("/data/hmgapi/settings/allow/startup_notice").isFile(); }   // hmgapid allow

        public boolean lightBlocked() { return new java.io.File("/data/hmgapi/settings/allow/block_ambient_light").isFile(); }

        public boolean askFirst() { return !new java.io.File(ALLOW + "trust_commands").isFile(); }

        public String answer(String mod, String cmd) {   // hmgapid allow cmd.<mod>.<command> / nocmd.<mod>.<command>
            if (new java.io.File(ALLOW + "nocmd." + mod + "." + cmd).isFile()) return "no";
            return new java.io.File(ALLOW + "cmd." + mod + "." + cmd).isFile() ? "yes" : null;
        }

        public void ask(final String mod, final String cmd) {
            // in the background (am takes a moment); what am says goes to the log when the question
            // didn't go on screen (an app without AskActivity, Android not up), so it isn't lost
            new Thread() {
                @Override
                public void run() {
                    try {
                        Process p = new ProcessBuilder("/system/bin/am", "start", "--user", "0", "-n", "hmgapi.app/.AskActivity",
                                "--es", "mod", mod, "--es", "command", cmd).redirectErrorStream(true).start();
                        java.io.InputStream in = p.getInputStream();
                        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[1024];
                        int n;
                        while ((n = in.read(b)) > 0) o.write(b, 0, n);
                        int rc = p.waitFor();
                        String out = o.toString("UTF-8").trim();
                        if (rc != 0 || out.contains("Error")) System.out.println("vehicle: the question for " + mod + " " + cmd + " didn't go on screen: " + out.replace('\n', ' '));
                    } catch (Exception e) {
                        System.out.println("vehicle: can't ask the owner: " + e);
                    }
                }
            }.start();
        }

        public long now() { return android.os.SystemClock.elapsedRealtime(); }

        public void pause(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public void log(String line) { System.out.println("vehicle: " + line); }
    }

    static Object regulation() throws Exception {
        return call(service("framework.car_services.regulation", "framework.regulation_service_stub.as_interface", null),
                "framework.regulation_service.status");
    }

    /**
     * a car service's interface: ServiceManager.getService(its registered name, a
     * constant in the car's own code), then its Stub.asInterface
     */
    static Object service(String namePoint, String asInterface, ClassLoader cl) throws Exception {
        Object s = services.get(namePoint);
        if (s != null) return s;
        String name = (String) Points.get(cl, namePoint, null);
        IBinder b = (IBinder) Points.call(null, "framework.service_manager.get_service", null, name);
        if (b == null) return null;
        s = Points.call(cl, asInterface, null, b);
        if (s != null) services.put(namePoint, s);
        return s;
    }

    static Object call(Object target, String id, Object... args) {
        if (target == null) return null;
        return Points.call(target.getClass().getClassLoader(), id, target, args);
    }

    static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : -1;
    }

    /** a raw number from a point this car's map may not have yet ("-" then, without a failed call) */
    static String mapped(Object target, String id) {
        return Points.has(id) ? raw(call(target, id)) : null;
    }

    /** a number as the car gave it; null (shown as "-") for anything else. A byte is read
     *  unsigned (the 12V battery getters return byte: 0xFF is 255, not -1). */
    static String raw(Object o) {
        if (o instanceof Byte) return String.valueOf(((Byte) o).intValue() & 0xff);
        return o instanceof Number ? String.valueOf(((Number) o).intValue()) : null;
    }

    static String bool(boolean b) {
        return b ? "1" : "0";
    }

    static String valid(int v, int lo, int hi) {
        return v < lo || v > hi ? null : String.valueOf(v);
    }

    static String dec(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) return null;
        float r = Math.round(f * 10) / 10f;
        return r == Math.round(r) ? String.valueOf(Math.round(r)) : String.valueOf(r);
    }
}

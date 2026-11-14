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
        callbacks.ensure();
        Thread tick = new Thread("vehicle-tick") {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(2000);
                        commands.tick(declared());
                        watch.sync();
                        callbacks.ensure();
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
            int f10 = callbacks.outsideF10, c10 = callbacks.outsideC10;
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

        public void hideStartupNotice() { run(get("factory"), "framework.factory_service.hide_consent_screen"); }

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

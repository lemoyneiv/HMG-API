package hmgapi.engine;

import java.lang.reflect.Method;

/**
 * The projection hotspot, through Android's own Wi-Fi service, as the projection apps start it:
 * app_process /system/bin hmgapi.engine.WifiAp restart | stop | start <mode> | client on|off (root;
 * CLASSPATH hmgapi-hook.jar). Home Wi-Fi's keeper turns the hotspot off and the car's own Wi-Fi
 * client on while the car is parked, and back when it's on: the stock path, the one this driver
 * handles well (a second interface next to the hotspot didn't hold up: US EV6, 2026-11-18).
 * The same mode as before: the projection AP (apMode 2) or the plain hotspot.
 * Through IWifiManager by reflection (hidden API on 4.4); no Context under app_process.
 */
public final class WifiAp {
    private WifiAp() {}

    static final int AP_DISABLED = 11, AP_ENABLED = 13, AP_FAILED = 14;
    static final int CLIENT_DISABLED = 1, CLIENT_ENABLED = 3;

    /** The boot keeper may start mode 2 only while both AP and client are idle. */
    static int ensureProjection(Class<?> type, Object wifi, Class<?> config) throws ReflectiveOperationException, InterruptedException {
        Method apState = type.getMethod("getWifiApEnabledState");
        Method clientState = type.getMethod("getWifiEnabledState");
        Method apMode = type.getMethod("getWifiApMode");
        Method start = type.getMethod("setWifiApEnabledForConnectivity", config, boolean.class);
        int client = (Integer) clientState.invoke(wifi);
        if (client == CLIENT_ENABLED || (client == CLIENT_DISABLED && clientSavedOn(type, wifi))) {
            System.out.println("the car's own Wi-Fi client is on or set to restore, left alone");
            return 0;
        }
        int ap = (Integer) apState.invoke(wifi);
        if (ap == AP_ENABLED) {
            System.out.println("already up");
            return 0;
        }
        if ((ap != AP_DISABLED && ap != AP_FAILED) || (Integer) clientState.invoke(wifi) != CLIENT_DISABLED
                || (Integer) apState.invoke(wifi) != ap) {
            System.out.println("Wi-Fi is changing mode");
            return 1;
        }
        start.invoke(wifi, null, true);
        for (int t = 0; t < 60 && (Integer) apState.invoke(wifi) != AP_ENABLED; t++) Thread.sleep(250);
        boolean up = (Integer) apState.invoke(wifi) == AP_ENABLED && (Integer) apMode.invoke(wifi) == 2;
        System.out.println(up ? "up in mode 2" : "didn't come on in mode 2");
        return up ? 0 : 1;
    }

    /**
     * A mod's projection attach (hmgapi.hook.Wifi.projectionAp, in the app's own process, on its
     * WifiManager): the AP in mode 2 now, as the Wireless mod's ensureHotspot() did before HMG-API
     * had the keeper. Unlike the boot keeper it switches a plain hotspot or the car's own client
     * over, because a phone is connecting right now. Only home Wi-Fi's client (parked; home is
     * wifi.json's state, "up" or "searching") keeps the radio: its keeper brings the AP back when
     * the car is on. Returns what happened; "not started: ..." when it left the radio alone.
     */
    public static String attachProjection(Object wm, Class<?> config, String home) throws ReflectiveOperationException {
        if ("up".equals(home) || "searching".equals(home)) {
            return "not started: home Wi-Fi has the radio while parked (it brings the AP back itself)";
        }
        Class<?> k = wm.getClass();
        boolean on = false;
        int mode = -1;
        try {
            on = (Boolean) k.getMethod("isWifiApEnabled").invoke(wm);
        } catch (NoSuchMethodException e) {
            // no such call on this build: treat as off
        }
        try {
            mode = (Integer) k.getMethod("getWifiApMode").invoke(wm);
        } catch (NoSuchMethodException e) {
            // vendor call missing: the mode is unknown
        }
        if (on && mode == 2) return "already up (mode 2)";
        Object conf = k.getMethod("getWifiApConfiguration").invoke(wm);
        Object r = k.getMethod("setWifiApEnabledForConnectivity", config, boolean.class).invoke(wm, conf, true);
        return "started in mode 2 (" + r + "; was on=" + on + " mode=" + mode + ")";
    }

    private static boolean clientSavedOn(Class<?> type, Object wifi) throws ReflectiveOperationException {
        try {
            return Boolean.TRUE.equals(type.getMethod("getPersistedWifiEnabled").invoke(wifi));
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * restart: off and on again, the same mode. stop: off, printing the mode it had ("2" the projection
     * AP, "1" the plain hotspot, "0" it wasn't on). start <mode>: on again in that mode.
     */
    public static void main(String[] args) throws Exception {
        System.exit(runCommand(args));
    }

    public static int runCommand(String[] args) throws Exception {
        String v = args.length > 0 ? args[0] : "";
        boolean client = "client".equals(v) && args.length == 2 && ("on".equals(args[1]) || "off".equals(args[1]));
        boolean ensure = "ensure-projection".equals(v) && args.length == 1;
        if (!(("restart".equals(v) || "stop".equals(v)) && args.length == 1) && !("start".equals(v) && args.length == 2) && !client && !ensure) {
            System.out.println("usage: WifiAp restart | stop | start <1|2> | client on|off | ensure-projection");
            return 2;
        }
        Object b = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "wifi");
        if (b == null) {
            System.out.println("no Wi-Fi service");
            return 1;
        }
        Class<?> i = Class.forName("android.net.wifi.IWifiManager");
        Object wifi = Class.forName("android.net.wifi.IWifiManager$Stub").getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, b);
        Class<?> conf = Class.forName("android.net.wifi.WifiConfiguration");
        if (ensure) return ensureProjection(i, wifi, conf);
        if (client) {   // the stock Wi-Fi client (home Wi-Fi while parked); the hotspot must be off first
            boolean on = "on".equals(args[1]);
            i.getMethod("setWifiEnabled", boolean.class).invoke(wifi, on);
            for (int t = 0; t < 60; t++) {   // WIFI_STATE_ENABLED 3, DISABLED 1
                int st = (Integer) i.getMethod("getWifiEnabledState").invoke(wifi);
                if (st == (on ? 3 : 1)) break;
                Thread.sleep(250);
            }
            int st = (Integer) i.getMethod("getWifiEnabledState").invoke(wifi);
            System.out.println("client " + (st == 3 ? "on" : st == 1 ? "off" : "state " + st));
            return st == (on ? 3 : 1) ? 0 : 1;
        }
        int state = (Integer) i.getMethod("getWifiApEnabledState").invoke(wifi);
        int mode;
        if ("start".equals(v)) {
            mode = "2".equals(args[1]) ? 2 : 1;
        } else {
            try {
                mode = (Integer) i.getMethod("getWifiApMode").invoke(wifi) == 2 ? 2 : 1;
            } catch (NoSuchMethodException e) {
                mode = 1;
            }
        }
        Method set = mode == 2 ? i.getMethod("setWifiApEnabledForConnectivity", conf, boolean.class) : i.getMethod("setWifiApEnabled", conf, boolean.class);
        if ("stop".equals(v) || "restart".equals(v)) {
            if (state != AP_ENABLED && state != 12) {   // 12: enabling
                if ("stop".equals(v)) {
                    System.out.println("0");
                    return 0;
                }
            } else {
                set.invoke(wifi, null, false);
                for (int t = 0; t < 40 && (Integer) i.getMethod("getWifiApEnabledState").invoke(wifi) != 11; t++) Thread.sleep(250);   // 11: disabled
            }
            if ("stop".equals(v)) {
                System.out.println(mode);
                return 0;
            }
        }
        set.invoke(wifi, null, true);
        for (int t = 0; t < 60 && (Integer) i.getMethod("getWifiApEnabledState").invoke(wifi) != AP_ENABLED; t++) Thread.sleep(250);
        boolean up = (Integer) i.getMethod("getWifiApEnabledState").invoke(wifi) == AP_ENABLED;
        System.out.println((mode == 2 ? "projection AP" : "hotspot") + (up ? " on" : " didn't come on"));
        return up ? 0 : 1;
    }
}

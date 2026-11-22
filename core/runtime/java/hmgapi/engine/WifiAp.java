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

    static final int AP_ENABLED = 13;

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
        if (!(("restart".equals(v) || "stop".equals(v)) && args.length == 1) && !("start".equals(v) && args.length == 2) && !client) {
            System.out.println("usage: WifiAp restart | stop | start <1|2> | client on|off");
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

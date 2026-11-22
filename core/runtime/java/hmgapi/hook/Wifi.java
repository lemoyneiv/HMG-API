package hmgapi.hook;

import android.content.Context;
import android.net.wifi.WifiConfiguration;

import java.io.File;
import java.io.FileInputStream;

import hmgapi.engine.WifiAp;

/**
 * HMG-API's Wi-Fi for mods, in the mod's own app process.
 *
 * The projection AP (apMode 2) comes up from boot through HMG-API's Wi-Fi keeper for a mod
 * that asks ("wifi": {"projection": true}). The keeper starts it once each boot (and again after
 * a QuickBoot suspend) and then leaves it to the car. A mod that is attaching a phone right now
 * (CarPlay's Bluetooth bridge, say) calls projectionAp() first, so the AP is there even when the
 * car turned it off since: HMG-API starts it the way the stock projection apps do
 * (WifiManager.setWifiApEnabledForConnectivity), unless home Wi-Fi has the radio while parked.
 */
public final class Wifi {
    private Wifi() {}

    static final File HOME = new File("/data/hmgapi/public/wifi.json");
    static final long HOME_FRESH_MS = 60_000;   // the keeper rewrites it every few seconds

    /**
     * The projection AP up for a phone attaching now. True when it's up or was started, false
     * when HMG-API left the radio alone (home Wi-Fi parked) or the call failed. Logs what it did.
     */
    public static boolean projectionAp(Context c, String who) {
        if (c == null) return false;
        try {
            Object wm = c.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            String r = WifiAp.attachProjection(wm, WifiConfiguration.class, homeState());
            Bridge.log(4, "[" + who + "] projection AP: " + r);
            return !r.startsWith("not started");
        } catch (Throwable t) {
            Bridge.log(6, "[" + who + "] projection AP: " + Bridge.trace(t));
            return false;
        }
    }

    /** Home Wi-Fi's state from its keeper, or null when it's missing or stale. */
    static String homeState() {
        try {
            if (!HOME.isFile() || System.currentTimeMillis() - HOME.lastModified() > HOME_FRESH_MS) return null;
            byte[] b = new byte[(int) Math.min(HOME.length(), 512)];
            int n;
            try (FileInputStream in = new FileInputStream(HOME)) {
                n = in.read(b);
            }
            String s = n > 0 ? new String(b, 0, n, "UTF-8") : "";
            int i = s.indexOf("\"state\"");
            if (i < 0) return null;
            int a = s.indexOf('"', s.indexOf(':', i) + 1), z = a < 0 ? -1 : s.indexOf('"', a + 1);
            return z > a ? s.substring(a + 1, z) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}

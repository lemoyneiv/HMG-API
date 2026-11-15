package hmgapi.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.net.ConnectException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

/**
 * HMG-API's "updates" option (hmgapi.json "updates", default false = blocked):
 * over-the-air software updates on or off.
 *
 * The head unit's OTA client is com.hkmc.system.app.OTAClient
 * (HKMC_DMClient_OTA, a Redbend vDM / OMA-DM client). It downloads and runs
 * infotainment, navigation-map and other control-unit updates over the
 * network, but it is also what Engineering Mode's DSP "Update OTA" (USB) path
 * relies on: DspUpdate copies ota_package.tar to /storage/upgrade and
 * broadcasts OTA_DEBUG_SET_TO_DL_ENDED, which only this app handles before
 * running MAP_PREPARE (the NEMESIS install path and HMG-API's USB installer).
 * So it stays enabled, and when updates are off HMG-API only takes its network
 * away: in its processes, libcore.io.IoBridge.connect (every TCP connect:
 * Socket incl. SSL, SocketChannel, HttpURLConnection) refuses anything but
 * loopback. Its native libraries (libvdm*, libotanativeinterface) open no
 * sockets of their own. The stock "Install Infotainment System Updates
 * Automatically" setting (Settings.System gen_auto_update) is turned off too
 * (it stays off afterwards, hooks on or not). All of it needs HMG-API's hooks: on
 * a car where they never run, nothing is blocked and that setting isn't touched.
 *
 * apply() runs in the launcher process (system uid) at every start; it also
 * re-enables the app if an earlier HMG-API build disabled it.
 */
final class Updates {
    private Updates() {}

    static final String OTA = "com.hkmc.system.app.OTAClient";
    static final String AUTO = "gen_auto_update";
    static final String PREV = "persist.sys.hmgapi.autoupd";    // the owner's gen_auto_update before HMG-API
    static final String OFF = "persist.sys.hmgapi.otaoff";      // 1: an earlier HMG-API build disabled the app

    static boolean wanted() {
        return Mods.hmgapiSettings.optBoolean("updates", false);
    }

    /** Launcher process: the stock setting, and undo the earlier builds' package disable. */
    static void apply(Context ctx) {
        try {
            if ("1".equals(StarKey.prop(OFF, ""))) {
                PackageManager pm = ctx.getPackageManager();
                if (pm.getApplicationEnabledSetting(OTA) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setApplicationEnabledSetting(OTA, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, 0);
                }
                StarKey.setProp(OFF, "");
                HmgApi.i("updates: OTA client enabled again (blocked by a hook now, so USB/DSP updates keep working)");
            }
            int auto = Settings.System.getInt(ctx.getContentResolver(), AUTO, -1);
            if (!wanted()) {
                if (auto > 0) {
                    StarKey.setProp(PREV, String.valueOf(auto));
                    Settings.System.putInt(ctx.getContentResolver(), AUTO, 0);
                }
                HmgApi.i("updates: off (OTA client has no network, automatic install off)");
            } else {
                String prev = StarKey.prop(PREV, "");
                if (prev.length() > 0) {
                    Settings.System.putInt(ctx.getContentResolver(), AUTO, Integer.parseInt(prev));
                    StarKey.setProp(PREV, "");
                }
                HmgApi.i("updates: on");
            }
        } catch (Throwable t) {
            HmgApi.e("updates", t);
        }
    }

    /** OTA client process: no network while updates are off. */
    static void otaProcess(LoadedApp app) {
        if (wanted()) return;
        final Set<String> logged = new HashSet<String>();
        MethodHook block = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                InetAddress a = (InetAddress) p.args[1];
                if (a != null && a.isLoopbackAddress()) return;   // its local http server, etc.
                String where = (a == null ? "?" : a.getHostAddress()) + ":" + p.args[2];
                synchronized (logged) {
                    if (logged.add(where)) HmgApi.i("updates: blocked OTA client connection to " + where);
                }
                p.setThrowable(new ConnectException("HMG-API: over-the-air updates are off"));
            }
        };
        if (!Points.needAll("updates block", "framework.io_bridge.connect", "framework.io_bridge.connect_timeout")) return;
        Points.hook(null, "framework.io_bridge.connect", block);
        Points.hook(null, "framework.io_bridge.connect_timeout", block);
        HmgApi.i("updates: OTA client network blocked in " + app.processName);
    }

    /**
     * With HMG-API's hooks off on a car (no hook map that fits, the boot guard) nothing
     * here runs: the OTA client keeps its network and the car's own automatic-install
     * setting is left as the owner has it. The app says so instead of "off".
     */
    static final String NOT_BLOCKED = "not blocked (hooks off)";

    /** For the HMG-API app. */
    static String state() {
        return wanted() ? "on" : "off";
    }
}

package hmgapi.hook;

import android.bluetooth.BluetoothAdapter;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Method;

/**
 * Coordinates volatile Bluetooth standby / bounce suppression with {@code uid 0} daemons
 * ({@code hmgapi-openlink} Dark Standby {@code /dev/.openlink-bt} and {@code hmgapi-bounce}
 * {@code /dev/.hmgapi-bounce-bt}) without ever setting {@code Settings.Global.BLUETOOTH_ON = 0}.
 *
 * <p>On Gen5W builds, leaving {@code Settings.Global.BLUETOOTH_ON = 1} across volatile
 * {@code IBluetoothManager.disable(false)} keeps cold-boot {@code PPService} initialization
 * lock-free, but Mobis {@code BTCMService} ({@code uid 1002}, {@code handleMessage: 999}) and
 * {@code Iap2Service} poll {@code BluetoothAdapter.isEnabled()} during {@code ADM}
 * ({@code pwrSt == 2}) and call {@link BluetoothAdapter#enable()} every 2.05 s — causing
 * {@code enable() <-> disable(false)} thrashing in Dark Standby or mid-bounce {@code MESSAGE_ENABLE}
 * races ({@code MESSAGE_TIMEOUT_BIND} 60 s later).</p>
 *
 * <p>Both markers reside on RAM {@code tmpfs} ({@code /dev}) and carry the live kernel
 * {@code boot_id}, so they automatically vanish on every cold boot or 12V cut and never block
 * {@code uid 0} callers.</p>
 */
public final class BtStandby {
    private BtStandby() {}

    static final File BOOT_ID_FILE = new File("/proc/sys/kernel/random/boot_id");
    static final File OPENLINK_BT_MARKER = new File("/dev/.openlink-bt");
    static final File BOUNCE_BT_MARKER = new File("/dev/.hmgapi-bounce-bt");

    private static final long LOG_THROTTLE_MS = 30_000L;
    private static volatile long lastLogAtMs = 0L;

    /** Pure policy check (host-testable without Android runtime). */
    public static boolean shouldSuppressEnable(
            int callingUid,
            String curBootId,
            String openlinkMarkerContent,
            String bounceMarkerContent) {
        if (callingUid == 0) return false;
        String boot = curBootId == null ? "" : curBootId.trim();
        if (boot.isEmpty()) return false;
        return matchesBootMarker(boot, openlinkMarkerContent)
                || matchesBootMarker(boot, bounceMarkerContent);
    }

    private static boolean matchesBootMarker(String boot, String markerContent) {
        if (markerContent == null) return false;
        String m = markerContent.trim();
        return !m.isEmpty() && boot.equals(m);
    }

    static void zygote() {
        MethodHook guard = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param param) {
                try {
                    int uid = Process.myUid();
                    if (uid == 0) return;
                    if (!OPENLINK_BT_MARKER.isFile() && !BOUNCE_BT_MARKER.isFile()) {
                        return;
                    }
                    String curBoot = readSmallAscii(BOOT_ID_FILE);
                    String ol = readSmallAscii(OPENLINK_BT_MARKER);
                    String bn = readSmallAscii(BOUNCE_BT_MARKER);
                    if (shouldSuppressEnable(uid, curBoot, ol, bn)) {
                        param.setResult(Boolean.FALSE);
                        long now = System.currentTimeMillis();
                        if (now - lastLogAtMs >= LOG_THROTTLE_MS) {
                            lastLogAtMs = now;
                            HmgApi.i("bt standby: suppressed non-root BluetoothAdapter."
                                    + param.method.getName() + "() (uid=" + uid + ") while tmpfs marker active");
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        };

        try {
            Hooks.findAndHookMethod(BluetoothAdapter.class, "enable", guard);
        } catch (Throwable t) {
            HmgApi.e("bt standby enable hook", t);
        }
        try {
            Method m = BluetoothAdapter.class.getDeclaredMethod("enableNoAutoConnect");
            Bridge.hookMethod(m, guard);
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            HmgApi.e("bt standby enableNoAutoConnect hook", t);
        }
    }

    private static String readSmallAscii(File f) {
        if (f == null || !f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[96];
            int n = in.read(buf);
            if (n <= 0) return "";
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Throwable ignored) {
            return "";
        } finally {
            if (in != null) {
                try { in.close(); } catch (Throwable ignored) {}
            }
        }
    }
}

package hmgapi.hook;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bypasses {@code BTCMService}'s 7.6 s cold-boot {@code msg.what = 1100} fallback timer inside
 * {@code com.android.bluetooth} when {@code wireless-carplay-aa} is enabled.
 *
 * <p>On Gen5W cold boots, {@code BluetoothAdapter.ACTION_STATE_CHANGED(STATE_ON = 12)} arrives in
 * {@code BTCMService.mbtcmReceiver} before {@code handleMessage(100)} ({@code startBTCMService})
 * finishes initializing, so {@code getBluetoothState()} is still {@code false} and
 * {@code updateBondedDeviceList} runs with {@code AdapterOnChanged = false}. {@code BTCMService}
 * then sits idle for ~7.6 s waiting for {@code msg.what = 1100} before dispatching
 * {@code BTCM_REQ_CONNECT_PRIORITY}.</p>
 *
 * <p>Once {@code BTCMService} finishes {@code setBluetoothState(true)} + {@code btcmIsReady()},
 * {@code Mode 2} SoftAP is {@code 13}, and {@code phoneprojection} is registered and idle, we
 * deliver a single {@code ACTION_STATE_CHANGED(STATE_ON)} to {@code mbtcmReceiver} so priority
 * auto-connect starts immediately (~0.4 s instead of 7.6 s).</p>
 */
public final class BtcmFastConnect {
    private BtcmFastConnect() {}

    public enum Action {
        WAIT,
        DISPATCH,
        DONE_ALREADY_ACTIVE
    }

    static final File BOOT_ID_FILE = new File("/proc/sys/kernel/random/boot_id");
    static final File PROJ_FASTSTART_MARKER = new File("/dev/.hmgapi-proj-faststart");
    static final File PUBLIC_WIFI_JSON = new File("/data/hmgapi/public/wifi.json");

    private static final int BT_STATE_ON = 12;
    private static final int BT_STATE_TURNING_ON = 11;
    private static final int AP_STATE_ENABLED = 13;
    private static final int AP_MODE_PROJECTION = 2;
    private static final long POLL_INTERVAL_MS = 350L;
    private static final int MAX_POLLS = 40;

    private static volatile LoadedApp btApp;
    private static volatile Context btContext;
    private static volatile boolean wirelessEnabled;
    private static volatile Object btcmInstance;
    private static boolean hooksInstalled;
    private static BroadcastReceiver stateReceiver;
    private static Handler mainHandler;
    private static int pollCount;
    private static final AtomicBoolean firedThisBoot = new AtomicBoolean(false);

    private static final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            runReadinessCheck();
        }
    };

    /** Pure policy check for host unit tests (no Android runtime dependencies). */
    public static Action evaluate(
            boolean wirelessModEnabled,
            boolean alreadyFiredThisBoot,
            boolean btStandbySuppressed,
            boolean powerOrGraceApproved,
            String homeWifiPublicState,
            int btAdapterState,
            int softApState,
            int softApMode,
            boolean btcmBluetoothStateReady,
            boolean btcmReady,
            boolean btcmAdapterStateAlreadyOn,
            boolean btcmBusyOrConnected,
            boolean phoneProjectionReadyAndIdle,
            boolean phoneProjectionAlreadyConnected) {
        if (!wirelessModEnabled || alreadyFiredThisBoot) {
            return Action.DONE_ALREADY_ACTIVE;
        }
        if (btcmAdapterStateAlreadyOn || btcmBusyOrConnected || phoneProjectionAlreadyConnected) {
            return Action.DONE_ALREADY_ACTIVE;
        }
        if (btStandbySuppressed || !powerOrGraceApproved) {
            return Action.WAIT;
        }
        String hw = homeWifiPublicState == null ? "" : homeWifiPublicState.trim();
        if ("up".equals(hw) || "searching".equals(hw)) {
            return Action.WAIT;
        }
        if (btAdapterState != BT_STATE_ON
                || softApState != AP_STATE_ENABLED
                || softApMode != AP_MODE_PROJECTION
                || !btcmBluetoothStateReady
                || !btcmReady
                || !phoneProjectionReadyAndIdle) {
            return Action.WAIT;
        }
        return Action.DISPATCH;
    }

    @SuppressWarnings("unchecked")
    public static boolean isWirelessProjectionEnabled(Map<String, Object> mods) {
        if (mods == null) return false;
        Object entry = mods.get("wireless-carplay-aa");
        if (entry instanceof Map) {
            return Boolean.TRUE.equals(((Map<String, Object>) entry).get("enabled"));
        }
        return false;
    }

    public static String parseHomeWifiState(String rawJson) {
        if (rawJson == null || rawJson.isEmpty()) return "";
        int idx = rawJson.indexOf("\"state\"");
        if (idx < 0) return "";
        int colon = rawJson.indexOf(':', idx + 7);
        if (colon < 0) return "";
        int q1 = rawJson.indexOf('"', colon + 1);
        if (q1 < 0) return "";
        int q2 = rawJson.indexOf('"', q1 + 1);
        if (q2 < 0) return "";
        return rawJson.substring(q1 + 1, q2).trim();
    }

    public static boolean matchesBootMarker(String curBoot, String markerContent) {
        if (curBoot == null || markerContent == null) return false;
        String b = curBoot.trim();
        String m = markerContent.trim();
        return !b.isEmpty() && b.equals(m);
    }

    static void init(LoadedApp app) {
        btApp = app;
        if (app != null && app.context != null) {
            btContext = app.context;
        }
    }

    static synchronized void sync(Map<String, Object> mods) {
        LoadedApp app = btApp;
        Context ctx = btContext;
        if (app == null || ctx == null) return;

        boolean next = isWirelessProjectionEnabled(mods);
        wirelessEnabled = next;
        if (!next) {
            stopPollingLocked(ctx);
            return;
        }
        if (firedThisBoot.get()) {
            return;
        }
        installHooksOnceLocked(app.classLoader);
        ensureReceiverAndScheduleLocked(ctx, 250L);
    }

    private static void installHooksOnceLocked(ClassLoader cl) {
        if (hooksInstalled || cl == null) return;
        hooksInstalled = true;

        String[] candidates = new String[] {
                "com.broadcom.bt.service.btcmservice.BTCMService",
                "com.broadcom.bt.service.btcmservice.HfDeviceBTCMService"
        };
        for (int i = 0; i < candidates.length; i++) {
            Class<?> cls = findClassQuietly(candidates[i], cl);
            if (cls == null) continue;
            hookZeroArgMethodQuietly(cls, "onCreate", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param param) {
                    if (param.thisObject != null) {
                        btcmInstance = param.thisObject;
                        schedulePollFromAnyThread(250L);
                    }
                }
            });
            hookBooleanMethodQuietly(cls, "setBluetoothState", new MethodHook() {
                @Override
                protected void afterHookedMethod(Param param) {
                    if (param.thisObject != null) {
                        btcmInstance = param.thisObject;
                        if (param.args != null && param.args.length > 0 && Boolean.TRUE.equals(param.args[0])) {
                            schedulePollFromAnyThread(200L);
                        }
                    }
                }
            });
        }
    }

    private static Class<?> findClassQuietly(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void hookZeroArgMethodQuietly(Class<?> cls, String methodName, MethodHook hook) {
        try {
            Method m = cls.getDeclaredMethod(methodName);
            Bridge.hookMethod(m, hook);
        } catch (Throwable ignored) {
        }
    }

    private static void hookBooleanMethodQuietly(Class<?> cls, String methodName, MethodHook hook) {
        for (Class<?> cur = cls; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(methodName, boolean.class);
                Bridge.hookMethod(m, hook);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return;
            }
        }
    }

    private static synchronized void ensureReceiverAndScheduleLocked(Context ctx, long delayMs) {
        if (firedThisBoot.get() || !wirelessEnabled) return;
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        if (stateReceiver == null && ctx != null) {
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    int st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (st == BT_STATE_ON) {
                        schedulePollFromAnyThread(200L);
                    }
                }
            };
            try {
                ctx.registerReceiver(r, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
                stateReceiver = r;
            } catch (Throwable ignored) {
            }
        }
        pollCount = 0;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, delayMs);
    }

    private static synchronized void schedulePollFromAnyThread(long delayMs) {
        if (firedThisBoot.get() || !wirelessEnabled) return;
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        pollCount = 0;
        mainHandler.removeCallbacks(pollRunnable);
        mainHandler.postDelayed(pollRunnable, delayMs);
    }

    private static synchronized void stopPollingLocked(Context ctx) {
        if (mainHandler != null) {
            mainHandler.removeCallbacks(pollRunnable);
        }
        if (stateReceiver != null && ctx != null) {
            try {
                ctx.unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            stateReceiver = null;
        }
    }

    private static void runReadinessCheck() {
        Context ctx = btContext;
        if (ctx == null || !wirelessEnabled || firedThisBoot.get()) {
            synchronized (BtcmFastConnect.class) {
                stopPollingLocked(ctx);
            }
            return;
        }

        Object svc = btcmInstance;
        String curBoot = readSmallAscii(BOOT_ID_FILE);
        boolean btStandby = BtStandby.shouldSuppressEnable(
                1002,
                curBoot,
                readSmallAscii(BtStandby.OPENLINK_BT_MARKER),
                readSmallAscii(BtStandby.BOUNCE_BT_MARKER));
        boolean approved = matchesBootMarker(curBoot, readSmallAscii(PROJ_FASTSTART_MARKER))
                || isDirectMicomPowerOn();
        String hwState = parseHomeWifiState(readSmallAscii(PUBLIC_WIFI_JSON));
        int btState = readBluetoothAdapterState();
        int[] ap = readSoftApStateAndMode();

        boolean btcmBtState = svc != null && invokeBooleanMethod(svc, "getBluetoothState", false);
        boolean btcmReady = svc != null && invokeBooleanMethod(svc, "btcmIsReady", true);
        boolean btcmAdapterAlreadyOn = svc != null && invokeBooleanMethod(svc, "getBluetoothAdapterState", false);
        boolean btcmBusy = svc != null && isBtcmBusyOrConnected(svc);
        boolean[] pp = checkPhoneProjectionState();

        Action action = evaluate(
                wirelessEnabled,
                firedThisBoot.get(),
                btStandby,
                approved,
                hwState,
                btState,
                ap[0],
                ap[1],
                btcmBtState,
                btcmReady,
                btcmAdapterAlreadyOn,
                btcmBusy,
                pp[0],
                pp[1]);

        if (action == Action.DONE_ALREADY_ACTIVE) {
            firedThisBoot.set(true);
            synchronized (BtcmFastConnect.class) {
                stopPollingLocked(ctx);
            }
            return;
        }

        if (action == Action.DISPATCH && svc != null) {
            BroadcastReceiver receiver = findBtcmReceiver(svc);
            if (receiver != null && firedThisBoot.compareAndSet(false, true)) {
                synchronized (BtcmFastConnect.class) {
                    stopPollingLocked(ctx);
                }
                try {
                    Intent stateOn = new Intent(BluetoothAdapter.ACTION_STATE_CHANGED);
                    stateOn.putExtra(BluetoothAdapter.EXTRA_STATE, BT_STATE_ON);
                    stateOn.putExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, BT_STATE_TURNING_ON);
                    receiver.onReceive(ctx, stateOn);
                    HmgApi.i("btcm fast-connect: delivered cold-boot STATE_ON to BTCMService");
                } catch (Throwable t) {
                    HmgApi.e("btcm fast-connect dispatch", t);
                }
                return;
            }
        }

        synchronized (BtcmFastConnect.class) {
            if (firedThisBoot.get() || !wirelessEnabled) {
                stopPollingLocked(ctx);
                return;
            }
            if (++pollCount < MAX_POLLS && mainHandler != null) {
                mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            } else {
                stopPollingLocked(ctx);
            }
        }
    }

    private static BroadcastReceiver findBtcmReceiver(Object svc) {
        for (Class<?> c = svc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("mbtcmReceiver");
                f.setAccessible(true);
                Object v = f.get(svc);
                if (v instanceof BroadcastReceiver) return (BroadcastReceiver) v;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
            }
        }
        BroadcastReceiver fallback = null;
        int count = 0;
        for (Class<?> c = svc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Field[] fields = c.getDeclaredFields();
            for (int i = 0; i < fields.length; i++) {
                Field f = fields[i];
                if (!BroadcastReceiver.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(svc);
                    if (!(v instanceof BroadcastReceiver)) continue;
                    String n = f.getName().toLowerCase();
                    if (n.contains("btcm")) return (BroadcastReceiver) v;
                    fallback = (BroadcastReceiver) v;
                    count++;
                } catch (Throwable ignored) {
                }
            }
        }
        return count == 1 ? fallback : null;
    }

    private static boolean invokeBooleanMethod(Object target, String name, boolean defaultIfMissing) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                Object r = m.invoke(target);
                if (r instanceof Boolean) return ((Boolean) r).booleanValue();
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return false;
            }
        }
        return defaultIfMissing;
    }

    private static boolean isBtcmBusyOrConnected(Object svc) {
        if (invokeBooleanMethod(svc, "btcmIsBusy", false)) return true;
        for (Class<?> c = svc.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod("btcmGetConnectedDevices");
                m.setAccessible(true);
                Object r = m.invoke(svc);
                if (r instanceof Collection && !((Collection<?>) r).isEmpty()) return true;
                if (r instanceof Object[] && ((Object[]) r).length > 0) return true;
                break;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                break;
            }
        }
        return false;
    }

    private static int readBluetoothAdapterState() {
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            return ad != null ? ad.getState() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static int[] readSoftApStateAndMode() {
        int[] out = new int[] { -1, -1 };
        try {
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "wifi");
            if (b == null) return out;
            Object iwm = Class.forName("android.net.wifi.IWifiManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
            if (iwm == null) return out;
            Object st = iwm.getClass().getMethod("getWifiApEnabledState").invoke(iwm);
            if (st instanceof Number) out[0] = ((Number) st).intValue();
            try {
                Object md = iwm.getClass().getMethod("getWifiApMode").invoke(iwm);
                if (md instanceof Number) out[1] = ((Number) md).intValue();
            } catch (NoSuchMethodException e) {
                out[1] = AP_MODE_PROJECTION;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Returns [readyAndIdle, alreadyConnected]. */
    private static boolean[] checkPhoneProjectionState() {
        boolean[] out = new boolean[] { false, false };
        try {
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "phoneprojection");
            if (b == null) return out;
            Object pp = Class.forName("android.phoneprojection.IPPManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
            if (pp == null) {
                out[0] = true;
                return out;
            }
            Class<?> cls = pp.getClass();
            String aa = "";
            String cp = "";
            try {
                Object r = cls.getMethod("getAAConnectedBTAddress").invoke(pp);
                if (r instanceof String) aa = ((String) r).trim();
            } catch (Throwable ignored) {
            }
            try {
                Object r = cls.getMethod("getCPConnectedBTAddress").invoke(pp);
                if (r instanceof String) cp = ((String) r).trim();
            } catch (Throwable ignored) {
            }
            boolean hasAa = aa.length() == 17 && !"00:00:00:00:00:00".equals(aa);
            boolean hasCp = cp.length() == 17 && !"00:00:00:00:00:00".equals(cp);
            if (hasAa || hasCp) {
                out[1] = true;
            } else {
                out[0] = true;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static boolean isDirectMicomPowerOn() {
        try {
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "com.hkmc.micom.power");
            if (b != null) {
                Object pwr = Class.forName("com.hkmc.power.IMicomPowerService$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, b);
                if (pwr != null) {
                    Object st = pwr.getClass().getMethod("getPowerStatus").invoke(pwr);
                    if (st instanceof Number && ((Number) st).intValue() == 0) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String readSmallAscii(File f) {
        if (f == null || !f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[256];
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

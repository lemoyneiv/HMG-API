package hmgapi.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.DhcpInfo;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Real Gen5W Android 4.4 (KitKat) WifiPlatform backed by WifiManager + BroadcastReceiver +
 * IWifiManager SoftAP reflection + @hmgapi-vehicle IPC + HomeWifiRouter + MICOM PwrKeep(1).
 */
public final class AndroidWifiPlatform implements WifiPlatform {
    private static final String WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String EXTRA_WIFI_AP_STATE = "wifi_state";
    private static final String VEHICLE_SOCK = "hmgapi-vehicle";
    private static final long PWRKEEP_REFRESH_MS = 30000L;
    private static final long STANDALONE_PROJ_UNMUTE_MS = 20000L;
    private static final File STANDALONE_BL_MARKER = new File("/dev/.hmgapi-wifi-bl");
    private static final File PROJECTION_RESUME_FILE = new File("/data/hmgapi/public/projection-resume.json");
    private static final File BT_STANDBY_RESTORE_MARKER = new File("/data/hmgapi/public/bt-standby-restore");

    private final Context context;
    private final WifiManager wifiManager;
    private final Object iWifiManager;
    private final Handler handler;
    private final HomeWifiRouter router = new HomeWifiRouter();
    private final Map<String, Runnable> scheduled = new HashMap<String, Runnable>();

    private volatile Events eventListener;
    private volatile HomeWifiFlightLog flightLog;
    private boolean receiverRegistered = false;
    private WifiConfiguration savedApConfig = null;
    private WifiManager.WifiLock wifiLock = null;
    private PowerManager.WakeLock cpuWakeLock = null;
    private boolean wifiLockRecorded = false;
    private boolean wakeLockRecorded = false;
    private boolean pwrKeepHeld = false;
    private boolean parkedDarkEntered = false;
    private boolean standaloneDarkBlanked = false;
    private boolean standaloneMutedAv = false;
    private int standalonePreMode = -1;
    private int standaloneFallbackMode = -1;
    private final Map<String, Integer> savedBacklight = new LinkedHashMap<String, Integer>();
    private long lastPwrKeepAssertMs = 0L;
    private String lastPwrKeepWaitSig = "";
    private volatile int liveMicomIgn = -1;
    private volatile int liveMicomDoorsClosed = -1;
    private volatile int liveMicomDoorsLocked = -1;
    private volatile VehicleSnapshot lastGoodVehicle = null;
    private Binder micomPwrListener = null;
    private Binder micomBcmListener = null;
    private final File peerOpenLinkLeaseFile = new File("/data/hmgapi/public/openlink-keepawake.json");

    private final Runnable vehiclePushWakeRunnable = new Runnable() {
        @Override
        public void run() {
            Events ev = eventListener;
            if (ev != null) ev.onVehicleSignalChanged();
        }
    };

    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            Events ev = eventListener;
            if (ev == null || intent == null) return;
            String action = intent.getAction();
            if (action == null) return;

            if (WIFI_AP_STATE_CHANGED_ACTION.equals(action)) {
                int apState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1);
                if (apState >= 0) ev.onApStateChanged(apState);
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                int st = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                if (st >= 0) ev.onWifiStateChanged(st);
            } else if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(action)) {
                ev.onScanResultsAvailable();
            } else if (WifiManager.SUPPLICANT_STATE_CHANGED_ACTION.equals(action)) {
                SupplicantState st = intent.getParcelableExtra(WifiManager.EXTRA_NEW_STATE);
                int err = intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1);
                ev.onSupplicantStateChanged(mapSupState(st), err == WifiManager.ERROR_AUTHENTICATING);
            } else if (WifiManager.NETWORK_STATE_CHANGED_ACTION.equals(action)) {
                NetworkInfo ni = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (ni != null && ni.isConnected()) {
                    ev.onNetworkConnected();
                }
            }
        }
    };

    public AndroidWifiPlatform(Context sysContext, Handler mainHandler) {
        this.context = sysContext;
        this.handler = mainHandler != null ? mainHandler : new Handler(Looper.getMainLooper());
        this.iWifiManager = bindIWifiManager();
        this.wifiManager = resolveWifiManager(sysContext, this.iWifiManager);
        initLocks(sysContext, this.wifiManager);
        recoverStandaloneBacklightMarkerIfOn();
        registerBroadcasts();
        ensureMicomVehiclePushListeners();
    }

    private void initLocks(Context ctx, WifiManager wm) {
        if (wm != null) {
            try {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hmgapi:homewifi");
                if (wifiLock != null) wifiLock.setReferenceCounted(false);
            } catch (Throwable ignored) {
            }
        }
        if (ctx != null) {
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hmgapi:keepawake");
                    if (cpuWakeLock != null) cpuWakeLock.setReferenceCounted(false);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object bindIWifiManager() {
        try {
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "wifi");
            if (b == null) return null;
            return Class.forName("android.net.wifi.IWifiManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
        } catch (Throwable t) {
            return null;
        }
    }

    private static WifiManager resolveWifiManager(Context ctx, Object iwm) {
        if (ctx != null) {
            try {
                WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) return wm;
            } catch (Throwable ignored) {
            }
        }
        if (iwm != null) {
            try {
                Class<?> iwmClass = Class.forName("android.net.wifi.IWifiManager");
                return WifiManager.class.getDeclaredConstructor(Context.class, iwmClass)
                        .newInstance(ctx, iwm);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void registerBroadcasts() {
        if (context == null || receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WIFI_AP_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            filter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            context.registerReceiver(wifiReceiver, filter, null, handler);
            receiverRegistered = true;
        } catch (Throwable t) {
            log("BroadcastReceiver registration notice: " + t.getMessage());
        }
    }

    public void close() {
        setSessionLocks(false, false);
        router.awaitPendingAdbTransition(1500L);
        if (context != null && receiverRegistered) {
            try {
                context.unregisterReceiver(wifiReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        synchronized (scheduled) {
            for (Runnable r : scheduled.values()) {
                handler.removeCallbacks(r);
            }
            scheduled.clear();
        }
    }

    @Override
    public void setEventListener(Events listener) {
        this.eventListener = listener;
    }

    @Override
    public long elapsedRealtimeMs() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public long uptimeMillis() {
        return SystemClock.uptimeMillis();
    }

    @Override
    public long wallEpochSec() {
        return System.currentTimeMillis() / 1000L;
    }

    @Override
    public String bootId() {
        try {
            return HomeWifiStore.readText(new File("/proc/sys/kernel/random/boot_id")).trim();
        } catch (Exception e) {
            return "boot";
        }
    }

    private void triggerVehiclePushWake() {
        handler.removeCallbacks(vehiclePushWakeRunnable);
        handler.post(vehiclePushWakeRunnable);
    }

    private void ensureMicomVehiclePushListeners() {
        if (micomPwrListener == null) {
            try {
                Object svc = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
                if (svc != null) {
                    final String desc = "com.hkmc.power.IMicomPowerStatusListener";
                    Class<?> ifaceCls = Class.forName(desc);
                    Class<?> stubCls = Class.forName(desc + "$Stub");
                    final int txChanged = readStaticIntField(stubCls, "TRANSACTION_onPowerStatusChanged", -1);
                    final int txPrepOff = readStaticIntField(stubCls, "TRANSACTION_onPrepareSystemOff", -1);
                    final int txSysOff = readStaticIntField(stubCls, "TRANSACTION_onSystemOff", -1);
                    Binder b = new Binder() {
                        { attachInterface(null, desc); }
                        @Override
                        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                            if (code == INTERFACE_TRANSACTION) {
                                if (reply != null) reply.writeString(desc);
                                return true;
                            }
                            if (code == txChanged || code == txPrepOff || code == txSysOff) {
                                triggerVehiclePushWake();
                            }
                            if (reply != null) answerMicomBinderSafe(data, reply);
                            return true;
                        }
                    };
                    Object proxy = stubCls.getMethod("asInterface", IBinder.class).invoke(null, b);
                    svc.getClass().getMethod("registerPowerStatusListener", ifaceCls).invoke(svc, proxy);
                    micomPwrListener = b;
                }
            } catch (Throwable ignored) {
            }
        }
        if (micomBcmListener == null) {
            try {
                Object svc = getMicomBinder("com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService$Stub");
                if (svc != null) {
                    final String desc = "com.hkmc.bcm.IBCMListener";
                    Class<?> ifaceCls = Class.forName(desc);
                    Class<?> stubCls = Class.forName(desc + "$Stub");
                    final int txDoors = readStaticIntField(stubCls, "TRANSACTION_onDoorAllStateChanged", -1);
                    final int txStolen = readStaticIntField(stubCls, "TRANSACTION_onStolen", -1);
                    final int txIgn = readStaticIntField(stubCls, "TRANSACTION_onIgnitionChanged", -1);
                    final int txAcc = readStaticIntField(stubCls, "TRANSACTION_onACCChanged", -1);
                    Binder b = new Binder() {
                        { attachInterface(null, desc); }
                        @Override
                        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                            if (code == INTERFACE_TRANSACTION) {
                                if (reply != null) reply.writeString(desc);
                                return true;
                            }
                            try {
                                if (code == txDoors) {
                                    data.enforceInterface(desc);
                                    if (data.dataAvail() >= 16) {
                                        boolean allLocked = true;
                                        boolean anyUnlocked = false;
                                        for (int i = 0; i < 4; i++) {
                                            int u = data.readInt(); // 0 = locked, 1 = unlocked
                                            if (u != 0) allLocked = false;
                                            if (u == 1) anyUnlocked = true;
                                        }
                                        if (allLocked) liveMicomDoorsLocked = 1;
                                        else if (anyUnlocked) liveMicomDoorsLocked = 0;
                                        if (data.dataAvail() >= 16) {
                                            boolean anyOpen = false;
                                            for (int i = 0; i < 4; i++) {
                                                if (data.readInt() == 1) anyOpen = true;
                                            }
                                            liveMicomDoorsClosed = anyOpen ? 0 : 1;
                                        }
                                    }
                                    triggerVehiclePushWake();
                                } else if (code == txStolen) {
                                    data.enforceInterface(desc);
                                    if (data.dataAvail() >= 4 && data.readInt() == 7) {
                                        // CF_Gway_BCMArmState == 7: disarmed by key-fob / proximity unlock
                                        liveMicomDoorsLocked = 0;
                                        triggerVehiclePushWake();
                                    }
                                } else if (code == txIgn) {
                                    data.enforceInterface(desc);
                                    if (data.dataAvail() >= 4) {
                                        int ign = data.readInt();
                                        if (ign == 0 || ign == 1) liveMicomIgn = ign;
                                    }
                                    triggerVehiclePushWake();
                                } else if (code == txAcc) {
                                    triggerVehiclePushWake();
                                }
                            } catch (Throwable ignored) {
                            }
                            if (reply != null) answerMicomBinderSafe(data, reply);
                            return true;
                        }
                    };
                    Object proxy = stubCls.getMethod("asInterface", IBinder.class).invoke(null, b);
                    svc.getClass().getMethod("registerBCMListener", ifaceCls, IBinder.class).invoke(svc, proxy, b);
                    try {
                        svc.getClass().getMethod("getDoorLockStatus").invoke(svc);
                    } catch (Throwable ignored) {
                    }
                    micomBcmListener = b;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isDirectMicomIgnitionOn() {
        try {
            Object bcm = getMicomBinder("com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService$Stub");
            if (bcm != null) {
                Object ign = bcm.getClass().getMethod("getIGNStatus").invoke(bcm);
                if (ign instanceof Boolean) {
                    boolean on = ((Boolean) ign).booleanValue();
                    liveMicomIgn = on ? 1 : 0;
                    return on;
                }
                if (ign instanceof Number) {
                    int v = ((Number) ign).intValue();
                    if (v == 0 || v == 1) liveMicomIgn = v;
                    return v == 1;
                }
            }
        } catch (Throwable ignored) {
        }
        return liveMicomIgn == 1;
    }

    private static int readStaticIntField(Class<?> cls, String name, int fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void answerMicomBinderSafe(Parcel data, Parcel reply) {
        reply.writeNoException();
        int end = data.dataSize();
        if (end < 4) return;
        data.setDataPosition(end - 4);
        int n = data.readInt();
        if (n >= 0 && n <= 64 * 1024) {
            reply.writeByteArray(new byte[n]);
        }
    }

    @Override
    public VehicleSnapshot readVehicle() {
        ensureMicomVehiclePushListeners();
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(VEHICLE_SOCK, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write("read power parked doors.closed gear.park charging charging.plugged battery12v.soc battery12v.saver\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String power = "";
            boolean parked = false;
            int doorsClosed = -1;
            boolean gearPark = false;
            boolean charging = false;
            boolean pluggedIn = false;
            int bat12v = -1;
            boolean saver12v = false;
            String line;
            while ((line = br.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp <= 0) continue;
                String k = line.substring(0, sp).trim();
                String v = line.substring(sp + 1).trim();
                if ("-".equals(v)) continue;
                if ("power".equals(k)) power = v;
                else if ("parked".equals(k)) parked = "1".equals(v) || "true".equalsIgnoreCase(v);
                else if ("doors.closed".equals(k)) {
                    if ("1".equals(v) || "true".equalsIgnoreCase(v)) doorsClosed = 1;
                    else if ("0".equals(v) || "false".equalsIgnoreCase(v)) doorsClosed = 0;
                } else if ("gear.park".equals(k)) gearPark = "1".equals(v) || "true".equalsIgnoreCase(v);
                else if ("charging".equals(k)) {
                    charging = !"0".equals(v) && !"off".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v);
                } else if ("charging.plugged".equals(k)) {
                    pluggedIn = "1".equals(v) || "true".equalsIgnoreCase(v);
                } else if ("battery12v.soc".equals(k)) {
                    try { bat12v = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                } else if ("battery12v.saver".equals(k)) {
                    saver12v = "1".equals(v);
                }
            }
            if (liveMicomDoorsClosed == 0 || (doorsClosed < 0 && liveMicomDoorsClosed >= 0)) {
                doorsClosed = liveMicomDoorsClosed;
            }
            if (power.isEmpty()) {
                power = readDirectMicomPowerNameFallback();
            }
            // On Gen5W, BCM.onIgnitionChanged(1) arrives ~1.0s before IMicomPowerService transitions
            // power from "adm" (2) to "on" (0). Promoting power="on" immediately on ign==1 prevents
            // misclassifying the car as still parked away-from-home and restores Mode 2 SoftAP right away.
            if (!"on".equalsIgnoreCase(power) && !"logic_on".equalsIgnoreCase(power)
                    && (liveMicomIgn == 1 || "adm".equalsIgnoreCase(power) || "delay_mode".equalsIgnoreCase(power))
                    && isDirectMicomIgnitionOn()) {
                power = "on";
                parked = false;
            }
            VehicleSnapshot snap = new VehicleSnapshot(
                    power, parked, gearPark, pluggedIn || charging, charging, bat12v, saver12v, doorsClosed, liveMicomDoorsLocked);
            lastGoodVehicle = snap;
            if (snap.isPowerOn()) {
                restoreStandaloneDarkIfNeeded();
            }
            return snap;
        } catch (Exception e) {
            VehicleSnapshot prev = lastGoodVehicle;
            String directPower = readDirectMicomPowerNameFallback();
            if (!"on".equalsIgnoreCase(directPower) && isDirectMicomIgnitionOn()) {
                directPower = "on";
            }
            if (!directPower.isEmpty()) {
                boolean isOn = "on".equalsIgnoreCase(directPower);
                if (isOn) {
                    restoreStandaloneDarkIfNeeded();
                    return new VehicleSnapshot("on", false, prev != null && prev.gearPark);
                }
                if (prev != null) {
                    return new VehicleSnapshot(
                            directPower,
                            prev.parked,
                            prev.gearPark,
                            prev.pluggedIn,
                            prev.charging,
                            prev.bat12vSoc,
                            prev.saver12v,
                            liveMicomDoorsClosed >= 0 ? liveMicomDoorsClosed : prev.doorsClosed,
                            liveMicomDoorsLocked >= 0 ? liveMicomDoorsLocked : prev.doorsLocked);
                }
                return new VehicleSnapshot(directPower, false, true, false, false, -1, false,
                        liveMicomDoorsClosed, liveMicomDoorsLocked);
            }
            if (prev != null) {
                return prev;
            }
            return new VehicleSnapshot("on", false, false);
        } finally {
            try { s.shutdownInput(); } catch (Exception ignored) {}
            try { s.shutdownOutput(); } catch (Exception ignored) {}
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static String readDirectMicomPowerNameFallback() {
        try {
            Object pwr = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
            if (pwr == null) return "";
            Object pObj = pwr.getClass().getMethod("getPowerStatus").invoke(pwr);
            int p = pObj instanceof Number ? ((Number) pObj).intValue() : -1;
            switch (p) {
                case 0: return "on";
                case 1: return "logic_on";
                case 2: return "adm";
                case 6: return "delay_mode";
                case 7: return "preparing_off";
                case 255: return "off";
                default: return "";
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ---- SoftAP reflection (KitKat 4.4 / Gen5W Mode 1 & Mode 2 Connectivity AP) ----

    @Override
    public boolean isSoftApActiveOrEnabling() {
        int st = getSoftApState();
        if (st == AP_STATE_ENABLING || st == AP_STATE_ENABLED) return true;
        Object target = wifiManager != null ? wifiManager : iWifiManager;
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod("isWifiApEnabled");
            Object res = m.invoke(target);
            return Boolean.TRUE.equals(res);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public int getSoftApState() {
        if (wifiManager != null) {
            try {
                Object res = wifiManager.getClass().getMethod("getWifiApState").invoke(wifiManager);
                if (res instanceof Integer) return ((Integer) res).intValue();
            } catch (Throwable ignored) {
            }
        }
        if (iWifiManager != null) {
            try {
                Object res = iWifiManager.getClass().getMethod("getWifiApEnabledState").invoke(iWifiManager);
                if (res instanceof Integer) return ((Integer) res).intValue();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    @Override
    public int getSoftApMode() {
        for (Object target : new Object[] { wifiManager, iWifiManager }) {
            if (target == null) continue;
            try {
                Object res = target.getClass().getMethod("getWifiApMode").invoke(target);
                if (res instanceof Integer) {
                    int m = ((Integer) res).intValue();
                    if (m == 1 || m == 2) return m;
                }
            } catch (Throwable ignored) {
            }
        }
        return 2;
    }

    @Override
    public void captureSoftApConfig() {
        for (Object target : new Object[] { wifiManager, iWifiManager }) {
            if (target == null) continue;
            try {
                Object res = target.getClass().getMethod("getWifiApConfiguration").invoke(target);
                if (res instanceof WifiConfiguration) {
                    savedApConfig = (WifiConfiguration) res;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void disconnectWirelessProjectionQuietly() {
        try {
            Object pp = getMicomBinder("phoneprojection", "android.phoneprojection.IPPManager$Stub");
            if (pp == null) return;
            Class<?> cls = pp.getClass();
            String aa = "";
            String cp = "";
            try {
                Object r = cls.getMethod("getAAConnectedBTAddress").invoke(pp);
                if (r instanceof String) aa = ((String) r).trim();
            } catch (Throwable ignored) {}
            try {
                Object r = cls.getMethod("getCPConnectedBTAddress").invoke(pp);
                if (r instanceof String) cp = ((String) r).trim();
            } catch (Throwable ignored) {}
            boolean hasAa = aa.length() == 17 && !"00:00:00:00:00:00".equals(aa);
            boolean hasCp = cp.length() == 17 && !"00:00:00:00:00:00".equals(cp);
            if (hasAa || hasCp) {
                cls.getMethod("disconnectWirelessAll").invoke(pp);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean disableSoftAp(int currentMode) {
        if (savedApConfig == null) {
            captureSoftApConfig();
        }
        if (currentMode == 2) {
            disconnectWirelessProjectionQuietly();
            if (invokeSoftAp("setWifiApEnabledForConnectivity", null, false)) return true;
            if (savedApConfig != null && invokeSoftAp("setWifiApEnabledForConnectivity", savedApConfig, false)) return true;
        }
        if (invokeSoftAp("setWifiApEnabled", null, false)) return true;
        return savedApConfig != null && invokeSoftAp("setWifiApEnabled", savedApConfig, false);
    }

    @Override
    public void stopClientWifiOnly() {
        try {
            if (wifiManager != null) {
                wifiManager.setWifiEnabled(false);
            } else if (iWifiManager != null) {
                iWifiManager.getClass().getMethod("setWifiEnabled", boolean.class).invoke(iWifiManager, false);
            }
        } catch (Throwable ignored) {
        }
    }

    public void setFlightLog(HomeWifiFlightLog log) {
        this.flightLog = log;
    }

    private void recordPlatformFlight(String tag, String detail, boolean doFsync) {
        HomeWifiFlightLog fl = this.flightLog;
        if (fl == null) return;
        fl.recordEvent(wallEpochSec(), elapsedRealtimeMs(), bootId(), tag, detail, detail, doFsync);
    }

    @Override
    public void setSessionLocks(boolean holdWifiLock, boolean holdParkedPower) {
        boolean wifiNow = wifiLockRecorded;
        if (wifiLock != null) {
            try {
                if (holdWifiLock && !wifiLock.isHeld()) {
                    wifiLock.acquire();
                } else if (!holdWifiLock && wifiLock.isHeld()) {
                    wifiLock.release();
                }
                wifiNow = wifiLock.isHeld();
            } catch (Throwable ignored) {
            }
        }
        boolean wakeNow = wakeLockRecorded;
        if (cpuWakeLock != null) {
            try {
                if (holdParkedPower && !cpuWakeLock.isHeld()) {
                    cpuWakeLock.acquire();
                } else if (!holdParkedPower && cpuWakeLock.isHeld()) {
                    cpuWakeLock.release();
                }
                wakeNow = cpuWakeLock.isHeld();
            } catch (Throwable ignored) {
            }
        }
        if (wifiNow != wifiLockRecorded || wakeNow != wakeLockRecorded) {
            wifiLockRecorded = wifiNow;
            wakeLockRecorded = wakeNow;
            recordPlatformFlight("OS_LOCKS", "wifiLock=" + wifiNow + " wakeLock=" + wakeNow, true);
        }
        if (holdParkedPower) {
            long now = elapsedRealtimeMs();
            boolean darkActive = holdWifiLock || parkedDarkEntered; // false = ADM RAP pre-latch while seated; true = parked dark session
            boolean enteringDark = darkActive && !parkedDarkEntered;
            if (!pwrKeepHeld || enteringDark || (now - lastPwrKeepAssertMs) >= PWRKEEP_REFRESH_MS) {
                boolean isRefresh = pwrKeepHeld && !enteringDark;
                if (assertMicomPwrKeepDarkSafe(isRefresh, darkActive)) {
                    if (!pwrKeepHeld) {
                        log("Acquired MICOM PwrKeep(1) (" + (darkActive ? "parked dark" : "ADM pre-latch") + ")");
                    }
                    pwrKeepHeld = true;
                    if (darkActive) parkedDarkEntered = true;
                    lastPwrKeepAssertMs = now;
                }
            } else if (darkActive && !isPeerOpenLinkHoldingPower()) {
                enforceStandaloneDarkBlankingQuietly();
            }
        } else {
            lastPwrKeepWaitSig = "";
            boolean wasDark = parkedDarkEntered;
            parkedDarkEntered = false;
            if (pwrKeepHeld) {
                pwrKeepHeld = false;
                lastPwrKeepAssertMs = 0L;
                if (!isPeerOpenLinkHoldingPower()) {
                    String stopDetail = releaseMicomPwrKeepWhenReady(wasDark, 0);
                    recordPlatformFlight("PWRKEEP_STOP", "peerHold=false " + stopDetail, true);
                    log("Released parked MICOM PwrKeep(1) (" + stopDetail + ")");
                } else {
                    recordPlatformFlight("PWRKEEP_DEFER_PEER", "peerHold=true", true);
                    log("Deferred MICOM PwrKeepStop(1) to active OpenLink lease");
                }
            }
        }
    }

    private boolean isPeerOpenLinkHoldingPower() {
        try {
            if (!peerOpenLinkLeaseFile.isFile()) return false;
            HomeWifiKeepAwake.PeerLease peer = HomeWifiKeepAwake.parsePeerLeaseJson(
                    HomeWifiStore.readText(peerOpenLinkLeaseFile));
            return peer != null && peer.isFreshAndActive(bootId(), elapsedRealtimeMs());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getMicomBinder(String serviceName, String preferredStubClassName) {
        try {
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, serviceName);
            if (b == null) return null;
            try {
                return Class.forName(preferredStubClassName)
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, b);
            } catch (Throwable fallback) {
                String desc = ((IBinder) b).getInterfaceDescriptor();
                if (desc != null && !desc.isEmpty()) {
                    return Class.forName(desc + "$Stub")
                            .getMethod("asInterface", IBinder.class)
                            .invoke(null, b);
                }
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean assertMicomPwrKeepDarkSafe(boolean isRefresh, boolean darkSessionActive) {
        try {
            Object pwr = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
            if (pwr == null) {
                if (!"no_pwr_binder".equals(lastPwrKeepWaitSig)) {
                    lastPwrKeepWaitSig = "no_pwr_binder";
                    recordPlatformFlight("PWRKEEP_WAIT", "reason=no_pwr_binder", true);
                }
                return false;
            }
            Object pwrStObj = pwr.getClass().getMethod("getPowerStatus").invoke(pwr);
            int pwrSt = pwrStObj instanceof Number ? ((Number) pwrStObj).intValue() : -1;
            int admSt = -1;
            try {
                Object admObj = pwr.getClass().getMethod("getADMSetupStatus").invoke(pwr);
                if (admObj instanceof Number) admSt = ((Number) admObj).intValue();
            } catch (Throwable ignored) {
            }
            boolean prepOff = false;
            try {
                prepOff = Boolean.TRUE.equals(pwr.getClass().getMethod("isPreparingSystemOff").invoke(pwr));
            } catch (Throwable ignored) {
            }

            boolean ignOn = false;
            Object bcm = getMicomBinder("com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService$Stub");
            if (bcm != null) {
                try {
                    Object ign = bcm.getClass().getMethod("getIGNStatus").invoke(bcm);
                    ignOn = Boolean.TRUE.equals(ign) || (ign instanceof Number && ((Number) ign).intValue() == 1);
                } catch (Throwable ignored) {
                }
            }

            boolean avOff = false;
            try {
                avOff = Boolean.TRUE.equals(pwr.getClass().getMethod("isAvPowerOff").invoke(pwr));
            } catch (Throwable ignored) {
            }
            Object bsSvc = getMicomBinder("com.hkmc.micom.blackscreen", "com.hkmc.power.IBlackScreenService$Stub");
            boolean bsShown = false;
            if (bsSvc != null) {
                try {
                    bsShown = Boolean.TRUE.equals(bsSvc.getClass().getMethod("isBlackScreenShown").invoke(bsSvc));
                } catch (Throwable ignored) {
                }
            }

            String stateSummary = "pwrSt=" + pwrSt + " admSt=" + admSt + " prepOff=" + prepOff
                    + " ign=" + ignOn + " avOff=" + avOff + " bs=" + bsShown + " dark=" + darkSessionActive;

            if (pwrSt == 0 || ignOn) {
                String sig = "ign_active|" + stateSummary;
                if (!sig.equals(lastPwrKeepWaitSig)) {
                    lastPwrKeepWaitSig = sig;
                    recordPlatformFlight("PWRKEEP_WAIT", "reason=ign_active " + stateSummary, true);
                }
                return false;
            }

            // Latch requestPwrKeepStart(1) immediately in ADM (pwrSt==2) BEFORE MICOM transitions
            // to DELAY_MODE (pwrSt==6, which has an unstoppable 180s hardware cutoff).
            pwr.getClass().getMethod("requestPwrKeepStart", int.class).invoke(pwr, Integer.valueOf(1));

            boolean peerHold = isPeerOpenLinkHoldingPower();
            if (darkSessionActive && !peerHold) {
                enforceStandaloneDarkBlankingQuietly();
            }
            lastPwrKeepWaitSig = "";
            String tag = isRefresh ? "PWRKEEP_REFRESH" : (darkSessionActive ? "PWRKEEP_START" : "PWRKEEP_PRELATCH");
            recordPlatformFlight(tag, stateSummary + " peerHold=" + peerHold, true);
            return true;
        } catch (Throwable t) {
            String errSig = "err:" + t.getClass().getSimpleName();
            if (!errSig.equals(lastPwrKeepWaitSig)) {
                lastPwrKeepWaitSig = errSig;
                recordPlatformFlight("PWRKEEP_ERR", "err=" + t.getClass().getSimpleName(), true);
            }
            return false;
        }
    }

    private static int readDirectMicomMode() {
        try {
            Object modeSvc = getMicomBinder("com.hkmc.micom.mode", "com.hkmc.mode.IModeManager$Stub");
            if (modeSvc != null) {
                Object mObj = modeSvc.getClass().getMethod("getCurrentMode").invoke(modeSvc);
                if (mObj instanceof Number) return ((Number) mObj).intValue();
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void writeStandaloneProjectionResumeHint(int preMode) {
        try {
            File pubDir = PROJECTION_RESUME_FILE.getParentFile();
            if (pubDir != null && !pubDir.exists()) {
                pubDir.mkdirs();
                pubDir.setReadable(true, false);
                pubDir.setExecutable(true, false);
            }
            long now = elapsedRealtimeMs();
            String src = (preMode == 115) ? "cp" : "aa";
            String json = "{\"bootId\":\"" + bootId() + "\",\"preMode\":" + preMode
                    + ",\"source\":\"" + src + "\",\"resumedAtElapsedMs\":" + now
                    + ",\"expiresAtElapsedMs\":" + (now + 45000L) + "}\n";
            File tmp = new File(PROJECTION_RESUME_FILE.getAbsolutePath() + ".tmp");
            writeFileQuietly(tmp, json);
            tmp.setReadable(true, false);
            if (tmp.renameTo(PROJECTION_RESUME_FILE)) {
                PROJECTION_RESUME_FILE.setReadable(true, false);
            } else {
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void enforceStandaloneDarkBlankingQuietly() {
        try {
            Object pwr = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
            if (pwr != null) {
                Object pObj = pwr.getClass().getMethod("getPowerStatus").invoke(pwr);
                int pwrSt = pObj instanceof Number ? ((Number) pObj).intValue() : -1;
                if (pwrSt == 0 || pwrSt == 1) return;
                if (!standaloneDarkBlanked) {
                    standalonePreMode = readDirectMicomMode();
                    boolean avWasOff = false;
                    try {
                        avWasOff = Boolean.TRUE.equals(pwr.getClass().getMethod("isAvPowerOff").invoke(pwr));
                    } catch (Throwable ignored) {}
                    if (!avWasOff) {
                        Object aud = getMicomBinder("com.hkmc.micom.audiosetup", "com.hkmc.media.IMicomAudioService$Stub");
                        if (aud != null) {
                            try {
                                boolean userMuted = Boolean.TRUE.equals(aud.getClass().getMethod("getUserMuteState").invoke(aud));
                                aud.getClass().getMethod("muteAV").invoke(aud);
                                if (!userMuted) standaloneMutedAv = true;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                try { pwr.getClass().getMethod("setScreenOn", boolean.class).invoke(pwr, Boolean.FALSE); } catch (Throwable ignored) {}
                try {
                    pwr.getClass().getMethod("requestLcdControl", boolean.class, boolean.class, boolean.class, boolean.class)
                            .invoke(pwr, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE);
                } catch (Throwable ignored) {}
            }
            Object bsSvc = getMicomBinder("com.hkmc.micom.blackscreen", "com.hkmc.power.IBlackScreenService$Stub");
            if (bsSvc != null) {
                // Omit showBlackScreen() so Mobis never launches the Clock Screen Saver widget
                try { bsSvc.getClass().getMethod("screenOff").invoke(bsSvc); } catch (Throwable ignored) {}
            }
            blankSysfsBacklightStandalone();
            standaloneDarkBlanked = true;
        } catch (Throwable ignored) {
        }
    }

    private void unmuteStandaloneAvNow() {
        try {
            Object aud = getMicomBinder("com.hkmc.micom.audiosetup", "com.hkmc.media.IMicomAudioService$Stub");
            if (aud != null) {
                aud.getClass().getMethod("unMuteAV").invoke(aud);
            }
        } catch (Throwable ignored) {
        }
        PROJECTION_RESUME_FILE.delete();
    }

    private void scheduleStandaloneProjectionUnmutePoll(final long startedMs, final long deadlineMs, final boolean reassertedAtOn) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (standaloneDarkBlanked) return;
                long now = elapsedRealtimeMs();
                int curMode = readDirectMicomMode();
                if (curMode == 115 || curMode == 116 || now >= deadlineMs) {
                    unmuteStandaloneAvNow();
                    return;
                }
                if (now - startedMs >= 2500L) {
                    if (standaloneFallbackMode <= 0 && curMode > 0) {
                        standaloneFallbackMode = curMode;
                    } else if (standaloneFallbackMode > 0 && curMode > 0 && curMode != standaloneFallbackMode) {
                        unmuteStandaloneAvNow();
                        return;
                    }
                }
                boolean didReassert = reassertedAtOn;
                if (!didReassert && "on".equalsIgnoreCase(readDirectMicomPowerNameFallback())) {
                    didReassert = true;
                    try {
                        Object aud = getMicomBinder("com.hkmc.micom.audiosetup", "com.hkmc.media.IMicomAudioService$Stub");
                        if (aud != null) aud.getClass().getMethod("muteAV").invoke(aud);
                    } catch (Throwable ignored) {}
                }
                scheduleStandaloneProjectionUnmutePoll(startedMs, deadlineMs, didReassert);
            }
        }, 350L);
    }

    private void restoreStandaloneDarkIfNeeded() {
        if (!standaloneDarkBlanked && !STANDALONE_BL_MARKER.isFile()) return;
        standaloneDarkBlanked = false;
        restoreSysfsBacklightStandalone();
        try {
            Object pwr = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
            if (pwr != null) {
                // Do NOT call powerOn() or ModeManager.setAVOn(true) — waking before Wireless
                // CarPlay/AA reconnects would cause ModeManager to fall back to FM Radio!
                try { pwr.getClass().getMethod("setScreenOn", boolean.class).invoke(pwr, Boolean.TRUE); } catch (Throwable ignored) {}
                try {
                    pwr.getClass().getMethod("requestLcdControl", boolean.class, boolean.class, boolean.class, boolean.class)
                            .invoke(pwr, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.TRUE);
                } catch (Throwable ignored) {}
            }
            Object bsSvc = getMicomBinder("com.hkmc.micom.blackscreen", "com.hkmc.power.IBlackScreenService$Stub");
            if (bsSvc != null) {
                try { bsSvc.getClass().getMethod("hideBlackScreen").invoke(bsSvc); } catch (Throwable ignored) {}
                try { bsSvc.getClass().getMethod("screenOn").invoke(bsSvc); } catch (Throwable ignored) {}
            }
            int preMode = standalonePreMode;
            standalonePreMode = -1;
            standaloneFallbackMode = -1;
            if (standaloneMutedAv) {
                standaloneMutedAv = false;
                if (preMode == 115 || preMode == 116) {
                    writeStandaloneProjectionResumeHint(preMode);
                    long now = elapsedRealtimeMs();
                    scheduleStandaloneProjectionUnmutePoll(now, now + STANDALONE_PROJ_UNMUTE_MS, false);
                } else {
                    unmuteStandaloneAvNow();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void recoverStandaloneBacklightMarkerIfOn() {
        String p = readDirectMicomPowerNameFallback();
        boolean isOn = "on".equals(p) || "logic_on".equals(p) || p.isEmpty() || isDirectMicomIgnitionOn();
        if (STANDALONE_BL_MARKER.isFile() && isOn) {
            restoreStandaloneDarkIfNeeded();
        }
        if (BT_STANDBY_RESTORE_MARKER.isFile() && isOn && !isPeerOpenLinkHoldingPower()) {
            try {
                String raw = HomeWifiStore.readText(BT_STANDBY_RESTORE_MARKER).trim();
                String curBoot = bootId();
                boolean prevBootMarker = raw.isEmpty() || !raw.contains(curBoot);
                boolean openLinkMissing = !new File("/data/hmgapi/mods/hmgapi-openlink/mod.json").isFile();
                if (prevBootMarker || openLinkMissing) {
                    Object b = Class.forName("android.os.ServiceManager")
                            .getMethod("getService", String.class)
                            .invoke(null, "bluetooth_manager");
                    if (b != null) {
                        Object bt = Class.forName("android.bluetooth.IBluetoothManager$Stub")
                                .getMethod("asInterface", IBinder.class)
                                .invoke(null, b);
                        if (bt != null) {
                            try {
                                bt.getClass().getMethod("enable").invoke(bt);
                            } catch (NoSuchMethodException e) {
                                bt.getClass().getMethod("enable", String.class).invoke(bt, "android");
                            }
                            BT_STANDBY_RESTORE_MARKER.delete();
                            log("Recovered Bluetooth state from bt-standby-restore marker");
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private synchronized void blankSysfsBacklightStandalone() {
        List<File> dirs = findBacklightDirs();
        if (dirs.isEmpty()) return;
        for (File d : dirs) {
            File br = new File(d, "brightness");
            int cur = readSysfsIntQuietly(br, -1);
            if (cur > 0) savedBacklight.put(br.getAbsolutePath(), Integer.valueOf(cur));
        }
        if (!STANDALONE_BL_MARKER.isFile() && !savedBacklight.isEmpty()) {
            StringBuilder sb = new StringBuilder(96);
            for (Map.Entry<String, Integer> e : savedBacklight.entrySet()) {
                if (e.getValue() != null && e.getValue().intValue() > 0) {
                    sb.append(e.getKey()).append('=').append(e.getValue().intValue()).append('\n');
                }
            }
            if (sb.length() > 0) writeFileQuietly(STANDALONE_BL_MARKER, sb.toString());
        }
        for (File d : dirs) {
            writeFileQuietly(new File(d, "brightness"), "0\n");
            File blp = new File(d, "bl_power");
            if (blp.isFile()) writeFileQuietly(blp, "4\n");
        }
    }

    private synchronized void restoreSysfsBacklightStandalone() {
        if (STANDALONE_BL_MARKER.isFile()) {
            try {
                for (String line : HomeWifiStore.readText(STANDALONE_BL_MARKER).split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        String k = line.substring(0, eq).trim();
                        int v = Integer.parseInt(line.substring(eq + 1).trim());
                        if (v > 0 && !savedBacklight.containsKey(k)) {
                            savedBacklight.put(k, Integer.valueOf(v));
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        for (File d : findBacklightDirs()) {
            File blp = new File(d, "bl_power");
            if (blp.isFile()) writeFileQuietly(blp, "0\n");
            File br = new File(d, "brightness");
            Integer sv = savedBacklight.get(br.getAbsolutePath());
            int target = (sv != null && sv.intValue() > 0) ? sv.intValue() : -1;
            if (target <= 0) {
                int maxBr = readSysfsIntQuietly(new File(d, "max_brightness"), 255);
                target = Math.max(1, (maxBr > 0 ? maxBr : 255) * 3 / 4);
            }
            writeFileQuietly(br, target + "\n");
        }
        STANDALONE_BL_MARKER.delete();
    }

    private static List<File> findBacklightDirs() {
        List<File> out = new ArrayList<File>(4);
        File[] bl = new File("/sys/class/backlight").listFiles();
        if (bl != null) {
            for (File d : bl) {
                if (new File(d, "brightness").isFile()) out.add(d);
            }
        }
        File[] leds = new File("/sys/class/leds").listFiles();
        if (leds != null) {
            for (File d : leds) {
                String n = d.getName().toLowerCase(Locale.US);
                if ((n.contains("backlight") || n.contains("lcd"))
                        && !n.contains("key") && !n.contains("button")
                        && !n.contains("kbd") && !n.contains("charging")
                        && new File(d, "brightness").isFile()) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private static int readSysfsIntQuietly(File f, int def) {
        try {
            return Integer.parseInt(HomeWifiStore.readText(f).trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static void writeFileQuietly(File f, String content) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try { out.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private String releaseMicomPwrKeepWhenReady(final boolean advanceAdmIfStillParked, final int attempt) {
        String detail = "stop=ERR";
        try {
            Object pwr = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
            if (pwr != null) {
                Object pObj = pwr.getClass().getMethod("getPowerStatus").invoke(pwr);
                int pwrSt = pObj instanceof Number ? ((Number) pObj).intValue() : -1;
                boolean ignOn = false;
                Object bcm = getMicomBinder("com.hkmc.micom.bcm", "com.hkmc.bcm.IBCMService$Stub");
                if (bcm != null) {
                    try {
                        Object ign = bcm.getClass().getMethod("getIGNStatus").invoke(bcm);
                        ignOn = Boolean.TRUE.equals(ign) || (ign instanceof Number && ((Number) ign).intValue() == 1);
                    } catch (Throwable ignored) {}
                }
                // If IG ON just fired while MICOM is still in ADM (pwrSt==2), wait up to ~2.4s
                // for MICOM to step directly 2 -> 0 instead of dipping 2 -> 6 (DELAY_MODE) -> 0.
                if (ignOn && pwrSt == 2 && attempt < 8) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (pwrKeepHeld || isPeerOpenLinkHoldingPower()) return;
                            releaseMicomPwrKeepWhenReady(false, attempt + 1);
                        }
                    }, 300L);
                    return "deferred-until-pwr0(ps=2)";
                }
                pwr.getClass().getMethod("requestPwrKeepStop", int.class).invoke(pwr, Integer.valueOf(1));
                detail = "stop=OK(ps=" + pwrSt + ")";
                if (advanceAdmIfStillParked && !ignOn && pwrSt == 2) {
                    Object sys = getMicomBinder("com.hkmc.micom.system", "com.hkmc.micom.ISystemService$Stub");
                    if (sys != null) {
                        sys.getClass().getMethod("requestDelayModeOnADM").invoke(sys);
                        detail = "stop=OK delayAdm=OK";
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return detail;
    }

    @Override
    public boolean restoreSoftAp(int mode) {
        if (getWifiState() == WIFI_STATE_DISABLED
                && getSoftApState() == AP_STATE_ENABLED
                && getSoftApMode() == mode) {
            return true;
        }
        if (getWifiState() != WIFI_STATE_DISABLED) {
            try {
                if (wifiManager != null) {
                    wifiManager.setWifiEnabled(false);
                } else if (iWifiManager != null) {
                    iWifiManager.getClass().getMethod("setWifiEnabled", boolean.class).invoke(iWifiManager, false);
                }
                for (int t = 0; t < 24; t++) {
                    if (getWifiState() == WIFI_STATE_DISABLED) break;
                    Thread.sleep(150);
                }
            } catch (Throwable ignored) {
            }
        }
        if (getSoftApState() == AP_STATE_ENABLED && getSoftApMode() == mode) {
            return true;
        }
        if (getSoftApState() == AP_STATE_DISABLING) {
            try {
                for (int t = 0; t < 24; t++) {
                    if (getSoftApState() != AP_STATE_DISABLING) break;
                    Thread.sleep(150);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        boolean ok = false;
        if (mode == 2) {
            ok = invokeSoftApOnTarget(iWifiManager, "setWifiApEnabledForConnectivity", null, true)
                    || invokeSoftAp("setWifiApEnabledForConnectivity", null, true);
            if (ok) {
                try {
                    for (int t = 0; t < 6; t++) {
                        int st = getSoftApState();
                        if (st == AP_STATE_ENABLED) return true;
                        if (st == AP_STATE_ENABLING) break;
                        Thread.sleep(150);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (getSoftApState() != AP_STATE_ENABLED && getSoftApState() != AP_STATE_ENABLING) {
                if (savedApConfig == null) captureSoftApConfig();
                WifiConfiguration conf = savedApConfig;
                ok = invokeSoftAp("setWifiApEnabledForConnectivity", conf, true);
                if (!ok) {
                    ok = invokeSoftAp("setWifiApEnabled", conf, true);
                }
            }
        } else {
            ok = invokeSoftApOnTarget(iWifiManager, "setWifiApEnabled", null, true)
                    || invokeSoftAp("setWifiApEnabled", null, true);
            if (!ok) {
                if (savedApConfig == null) captureSoftApConfig();
                ok = invokeSoftAp("setWifiApEnabled", savedApConfig, true);
            }
        }
        try {
            for (int t = 0; t < 26; t++) {
                if (getSoftApState() == AP_STATE_ENABLED) return true;
                Thread.sleep(150);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok;
    }

    private static boolean invokeSoftApOnTarget(Object target, String methodName, WifiConfiguration conf, boolean enabled) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, WifiConfiguration.class, boolean.class);
            Object res = m.invoke(target, conf, Boolean.valueOf(enabled));
            return !(res instanceof Boolean) || ((Boolean) res).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean invokeSoftAp(String methodName, WifiConfiguration conf, boolean enabled) {
        for (Object target : new Object[] { iWifiManager, wifiManager }) {
            if (invokeSoftApOnTarget(target, methodName, conf, enabled)) {
                return true;
            }
        }
        return false;
    }

    // ---- Station WifiManager ----

    @Override
    public int getWifiState() {
        if (wifiManager != null) {
            try {
                return wifiManager.getWifiState();
            } catch (Throwable ignored) {
            }
        }
        if (iWifiManager != null) {
            try {
                Object res = iWifiManager.getClass().getMethod("getWifiEnabledState").invoke(iWifiManager);
                if (res instanceof Integer) return ((Integer) res).intValue();
            } catch (Throwable ignored) {
            }
        }
        return WIFI_STATE_UNKNOWN;
    }

    @Override
    public boolean setWifiEnabled(boolean enabled) {
        if (wifiManager != null) {
            try {
                return wifiManager.setWifiEnabled(enabled);
            } catch (Throwable ignored) {
            }
        }
        if (iWifiManager != null) {
            try {
                Object res = iWifiManager.getClass().getMethod("setWifiEnabled", boolean.class)
                        .invoke(iWifiManager, Boolean.valueOf(enabled));
                return !(res instanceof Boolean) || ((Boolean) res).booleanValue();
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    public boolean startScan() {
        if (wifiManager == null) return false;
        try {
            return wifiManager.startScan();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<HomeWifiStore.SeenNetwork> getScanResults() {
        if (wifiManager == null) return Collections.emptyList();
        try {
            List<ScanResult> raw = wifiManager.getScanResults();
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            List<HomeWifiStore.SeenNetwork> out = new ArrayList<HomeWifiStore.SeenNetwork>(raw.size());
            for (ScanResult r : raw) {
                if (r == null || r.SSID == null) continue;
                String clean = WifiSecurity.cleanSsid(r.SSID);
                if (clean.isEmpty()) continue;
                out.add(new HomeWifiStore.SeenNetwork(clean, r.BSSID, r.frequency, r.level, r.capabilities));
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    @Override
    public List<ConfiguredNet> getConfiguredNetworks() {
        if (wifiManager == null) return Collections.emptyList();
        try {
            List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
            if (list == null || list.isEmpty()) return Collections.emptyList();
            List<ConfiguredNet> out = new ArrayList<ConfiguredNet>(list.size());
            for (WifiConfiguration c : list) {
                if (c == null || c.SSID == null) continue;
                String clean = WifiSecurity.cleanSsid(c.SSID);
                if (clean.isEmpty()) continue;
                boolean open = c.allowedKeyManagement != null
                        && c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE)
                        && !c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK);
                boolean enabled = c.status != WifiConfiguration.Status.DISABLED;
                out.add(new ConfiguredNet(c.networkId, clean, open, enabled));
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    @Override
    public int upsertWifiNetwork(String ssid, String psk, boolean open, String capabilities, boolean forceUpdateCredentials) {
        if (wifiManager == null) return -1;
        String clean = WifiSecurity.cleanSsid(ssid);
        String quotedSsid = WifiSecurity.quoteSsid(clean);
        int netId = -1;
        try {
            List<WifiConfiguration> list = wifiManager.getConfiguredNetworks();
            if (list != null) {
                for (WifiConfiguration c : list) {
                    if (c != null && c.SSID != null && WifiSecurity.cleanSsid(c.SSID).equalsIgnoreCase(clean)) {
                        netId = c.networkId;
                        break;
                    }
                }
            }
            if (netId == -1 || forceUpdateCredentials) {
                WifiConfiguration conf = new WifiConfiguration();
                conf.SSID = quotedSsid;
                conf.priority = 50;
                conf.status = WifiConfiguration.Status.ENABLED;
                conf.hiddenSSID = true; // Supports both broadcast and hidden SSIDs cleanly on bcmdhd

                int secMode = open ? WifiSecurity.SEC_OPEN : WifiSecurity.classifySecurityMode(capabilities);
                if (secMode == WifiSecurity.SEC_OPEN) {
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else if (secMode == WifiSecurity.SEC_WPA2_CCMP) {
                    conf.preSharedKey = WifiSecurity.formatPreSharedKey(psk);
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                    conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                    conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                } else {
                    conf.preSharedKey = WifiSecurity.formatPreSharedKey(psk);
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    conf.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                    conf.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
                    conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                    conf.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
                    conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                    conf.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
                }

                if (netId != -1) {
                    conf.networkId = netId;
                    int updated = wifiManager.updateNetwork(conf);
                    if (updated != -1) netId = updated;
                } else {
                    netId = wifiManager.addNetwork(conf);
                }
                if (netId != -1) {
                    wifiManager.saveConfiguration();
                }
            }
            return netId;
        } catch (Throwable t) {
            log("upsertWifiNetwork error: " + t.getMessage());
            return -1;
        }
    }

    @Override
    public boolean enableOnlyNetwork(int netId) {
        return enableNetwork(netId, true);
    }

    @Override
    public boolean enableNetwork(int netId, boolean disableOthers) {
        if (wifiManager == null || netId < 0) return false;
        try {
            return wifiManager.enableNetwork(netId, disableOthers);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean disableNetworkInRam(int netId) {
        if (wifiManager == null || netId < 0) return false;
        try {
            return wifiManager.disableNetwork(netId);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean removeWifiNetwork(int netId) {
        if (wifiManager == null || netId < 0) return false;
        try {
            wifiManager.disableNetwork(netId);
            boolean ok = wifiManager.removeNetwork(netId);
            wifiManager.saveConfiguration();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean reconnect() {
        if (wifiManager == null) return false;
        try {
            return wifiManager.reconnect();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean reassociate() {
        if (wifiManager == null) return false;
        try {
            return wifiManager.reassociate();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean disconnect() {
        if (wifiManager == null) return false;
        try {
            return wifiManager.disconnect();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public ConnectionSnapshot getConnectionSnapshot() {
        if (wifiManager == null) {
            return new ConnectionSnapshot(SupState.DISCONNECTED, -1, "", "", 0, 0, 0, 0, 0, 0, 0, 0);
        }
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            DhcpInfo dhcp = wifiManager.getDhcpInfo();
            SupState sup = info != null ? mapSupState(info.getSupplicantState()) : SupState.DISCONNECTED;
            int netId = info != null ? info.getNetworkId() : -1;
            String ssid = (info != null && info.getSSID() != null) ? WifiSecurity.cleanSsid(info.getSSID()) : "";
            String bssid = (info != null && info.getBSSID() != null) ? info.getBSSID() : "";
            int rssi = info != null ? info.getRssi() : 0;
            int freq = resolveWifiInfoFreq(info, bssid, ssid);
            int wIp = info != null ? info.getIpAddress() : 0;
            int dIp = dhcp != null ? dhcp.ipAddress : 0;
            int dGw = dhcp != null ? dhcp.gateway : 0;
            int dMask = dhcp != null ? dhcp.netmask : 0;
            int dDns1 = dhcp != null ? dhcp.dns1 : 0;
            int dDns2 = dhcp != null ? dhcp.dns2 : 0;
            return new ConnectionSnapshot(sup, netId, ssid, bssid, rssi, freq, wIp, dIp, dGw, dMask, dDns1, dDns2);
        } catch (Throwable t) {
            return new ConnectionSnapshot(SupState.DISCONNECTED, -1, "", "", 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private int resolveWifiInfoFreq(WifiInfo info, String bssid, String ssid) {
        if (info != null) {
            try {
                Object res = info.getClass().getMethod("getFrequency").invoke(info);
                if (res instanceof Integer && ((Integer) res).intValue() > 0) {
                    return ((Integer) res).intValue();
                }
            } catch (Throwable ignored) {
            }
        }
        if (wifiManager != null) {
            try {
                List<ScanResult> scans = wifiManager.getScanResults();
                if (scans != null) {
                    if (bssid != null && !bssid.isEmpty() && !"00:00:00:00:00:00".equals(bssid)) {
                        for (ScanResult r : scans) {
                            if (r != null && bssid.equalsIgnoreCase(r.BSSID) && r.frequency > 0) {
                                return r.frequency;
                            }
                        }
                    }
                    if (ssid != null && !ssid.isEmpty()) {
                        for (ScanResult r : scans) {
                            if (r != null && r.SSID != null
                                    && WifiSecurity.cleanSsid(r.SSID).equalsIgnoreCase(ssid)
                                    && r.frequency > 0) {
                                return r.frequency;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    private static SupState mapSupState(SupplicantState s) {
        if (s == null) return SupState.DISCONNECTED;
        try {
            return SupState.valueOf(s.name());
        } catch (IllegalArgumentException e) {
            return SupState.DISCONNECTED;
        }
    }

    @Override
    public boolean routeUp(String ip, String gateway, String dns1, String dns2) {
        return router.routeUp(ip, gateway, dns1, dns2);
    }

    @Override
    public void routeDown() {
        router.routeDown();
    }

    @Override
    public boolean setAdbOverWifi(boolean enable, String wlanIp, boolean deferTeardown) {
        return router.setAdbOverWifi(enable, wlanIp, deferTeardown);
    }

    @Override
    public void scheduleDelayed(final String tag, long delayMs, final Runnable task) {
        synchronized (scheduled) {
            Runnable prev = scheduled.remove(tag);
            if (prev != null) handler.removeCallbacks(prev);
            Runnable wrapped = new Runnable() {
                @Override
                public void run() {
                    synchronized (scheduled) {
                        if (scheduled.get(tag) == this) {
                            scheduled.remove(tag);
                        }
                    }
                    task.run();
                }
            };
            scheduled.put(tag, wrapped);
            handler.postDelayed(wrapped, Math.max(0L, delayMs));
        }
    }

    @Override
    public void cancelScheduled(String tag) {
        synchronized (scheduled) {
            Runnable prev = scheduled.remove(tag);
            if (prev != null) handler.removeCallbacks(prev);
        }
    }

    @Override
    public void log(String line) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
        System.out.println(ts + " " + line);
    }
}

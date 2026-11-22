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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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
    private static final long PWRKEEP_REFRESH_MS = 45000L;

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
    private long lastPwrKeepAssertMs = 0L;
    private String lastPwrKeepWaitSig = "";
    private final File peerOpenLinkLeaseFile = new File("/data/hmgapi/public/openlink-keepawake.json");

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
        registerBroadcasts();
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

    @Override
    public VehicleSnapshot readVehicle() {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress(VEHICLE_SOCK, LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(2000);
            OutputStream out = s.getOutputStream();
            out.write("read power parked gear.park charging charging.plugged battery12v.soc battery12v.saver\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String power = "";
            boolean parked = false;
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
                else if ("gear.park".equals(k)) gearPark = "1".equals(v) || "true".equalsIgnoreCase(v);
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
            return new VehicleSnapshot(power, parked, gearPark, pluggedIn || charging, charging, bat12v, saver12v);
        } catch (Exception e) {
            return new VehicleSnapshot("on", false, false);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
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

    private int getSoftApState() {
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

    @Override
    public boolean disableSoftAp(int currentMode) {
        if (currentMode == 2) {
            if (invokeSoftAp("setWifiApEnabledForConnectivity", null, false)) return true;
        }
        return invokeSoftAp("setWifiApEnabled", null, false);
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
            if (!pwrKeepHeld || (now - lastPwrKeepAssertMs) >= PWRKEEP_REFRESH_MS) {
                boolean isRefresh = pwrKeepHeld;
                if (assertMicomPwrKeepDarkSafe(isRefresh)) {
                    if (!pwrKeepHeld) {
                        log("Acquired parked MICOM PwrKeep(1) + PARTIAL_WAKE_LOCK");
                    }
                    pwrKeepHeld = true;
                    lastPwrKeepAssertMs = now;
                }
            }
        } else {
            lastPwrKeepWaitSig = "";
            if (pwrKeepHeld) {
                pwrKeepHeld = false;
                lastPwrKeepAssertMs = 0L;
                if (!isPeerOpenLinkHoldingPower()) {
                    releaseMicomPwrKeepQuietly();
                    recordPlatformFlight("PWRKEEP_STOP", "peerHold=false", true);
                    log("Released parked MICOM PwrKeep(1)");
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

    private static Object getMicomBinder(String serviceName, String stubClassName) {
        try {
            Object b = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, serviceName);
            if (b == null) return null;
            return Class.forName(stubClassName)
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, b);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean assertMicomPwrKeepDarkSafe(boolean isRefresh) {
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
            Object bsSvc = getMicomBinder("com.hkmc.micom.blackscreen", "com.hkmc.blackscreen.IMicomBlackScreenService$Stub");
            boolean bsShown = false;
            if (bsSvc != null) {
                try {
                    bsShown = Boolean.TRUE.equals(bsSvc.getClass().getMethod("isBlackScreenShown").invoke(bsSvc));
                } catch (Throwable ignored) {
                }
            }

            String stateSummary = "pwrSt=" + pwrSt + " admSt=" + admSt + " prepOff=" + prepOff
                    + " ign=" + ignOn + " avOff=" + avOff + " bs=" + bsShown;

            if (pwrSt == 0 || ignOn) {
                String sig = "ign_active|" + stateSummary;
                if (!sig.equals(lastPwrKeepWaitSig)) {
                    lastPwrKeepWaitSig = sig;
                    recordPlatformFlight("PWRKEEP_WAIT", "reason=ign_active " + stateSummary, true);
                }
                return false;
            }
            if (!avOff && !bsShown && pwrSt == 1) {
                // Still in Retained Accessory Power before driver door opens
                String sig = "rap_wait|" + stateSummary;
                if (!sig.equals(lastPwrKeepWaitSig)) {
                    lastPwrKeepWaitSig = sig;
                    recordPlatformFlight("PWRKEEP_WAIT", "reason=rap_wait " + stateSummary, true);
                }
                return false;
            }

            pwr.getClass().getMethod("requestPwrKeepStart", int.class).invoke(pwr, Integer.valueOf(1));

            boolean peerHold = isPeerOpenLinkHoldingPower();
            // Ensure panel stays dark if OpenLink isn't already managing backlight
            if (!peerHold && bsSvc != null && !bsShown) {
                try { bsSvc.getClass().getMethod("showBlackScreen").invoke(bsSvc); } catch (Throwable ignored) {}
                try { bsSvc.getClass().getMethod("screenOff").invoke(bsSvc); } catch (Throwable ignored) {}
            }
            lastPwrKeepWaitSig = "";
            recordPlatformFlight(isRefresh ? "PWRKEEP_REFRESH" : "PWRKEEP_START",
                    stateSummary + " peerHold=" + peerHold, true);
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

    private static void releaseMicomPwrKeepQuietly() {
        try {
            Object pwr = getMicomBinder("com.hkmc.micom.power", "com.hkmc.power.IMicomPowerService$Stub");
            if (pwr != null) {
                pwr.getClass().getMethod("requestPwrKeepStop", int.class).invoke(pwr, Integer.valueOf(1));
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean restoreSoftAp(int mode) {
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

        WifiConfiguration conf = savedApConfig;
        boolean ok = false;
        if (mode == 2) {
            ok = invokeSoftAp("setWifiApEnabledForConnectivity", conf, true);
            if (!ok) {
                ok = invokeSoftAp("setWifiApEnabled", conf, true);
            }
        } else {
            ok = invokeSoftAp("setWifiApEnabled", conf, true);
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

    private boolean invokeSoftAp(String methodName, WifiConfiguration conf, boolean enabled) {
        for (Object target : new Object[] { wifiManager, iWifiManager }) {
            if (target == null) continue;
            try {
                Method m = target.getClass().getMethod(methodName, WifiConfiguration.class, boolean.class);
                Object res = m.invoke(target, conf, Boolean.valueOf(enabled));
                return !(res instanceof Boolean) || ((Boolean) res).booleanValue();
            } catch (Throwable ignored) {
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

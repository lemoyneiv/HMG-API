package hmgapi.wifi;

import hmgapi.engine.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Event-driven Home Wi-Fi state machine modeled on fitcamx-gen5w's WifiBridge:
 *   - Clean SoftAP (Mode 2 Wireless Projection / Mode 1 Hotspot) <-> Station handoff
 *   - Live 4s scan loop & tap-to-connect even with 0 saved networks
 *   - Per-SSID Use for Home Wi-Fi switch (disables dashcam / excluded SSIDs in WifiManager RAM)
 *   - Broadcom bcmdhd 5.5s association watchdog with 400ms WLC_E_DISASSOC_IND settle delay
 *   - Immediate WPA/WPA2 wrong-password detection (ERROR_AUTHENTICATING & 4-way handshake drops)
 *   - 150ms DHCP lease polling + table 95 split-tunnel policy routing
 */
public final class HomeWifiController implements WifiPlatform.Events {
    public static final long AP_DISABLE_FALLBACK_MS = 3500L;
    public static final long AP_DISABLE_POLL_MS = 1000L;
    public static final int AP_DISABLE_MAX_POLLS = 75;
    public static final long WIFI_ENABLE_POLL_MS = 400L;
    public static final long SCAN_INTERVAL_MS = 4000L;
    public static final long AWAY_PEER_SCAN_INTERVAL_MS = 60000L;
    public static final long FAST_CONNECT_FALLBACK_MS = 6500L;
    public static final long ASSOC_WATCHDOG_MS = 5500L;
    public static final long ASSOC_SETTLE_MS = 400L;
    public static final int MAX_ASSOC_RETRIES = 3;
    public static final long DHCP_POLL_MS = 150L;
    public static final int DHCP_MAX_ATTEMPTS = 70;
    public static final long VEHICLE_POLL_ACTIVE_MS = 1500L;
    public static final long VEHICLE_POLL_IDLE_MS = 4000L;
    public static final long OFF_DELAY_MS = 30000L;

    static final String TAG_VEHICLE = "vehicle_tick";
    static final String TAG_AP_FALLBACK = "ap_disable_fallback";
    static final String TAG_WIFI_ENABLE = "wifi_enable_poll";
    static final String TAG_SCAN = "scan_loop";
    static final String TAG_FAST_FALLBACK = "fast_connect_fallback";
    static final String TAG_ASSOC_WATCHDOG = "assoc_watchdog";
    static final String TAG_ASSOC_SETTLE = "assoc_settle";
    static final String TAG_DHCP_POLL = "dhcp_poll";

    public static final class CmdResult {
        public final int code;
        public final String output;

        public CmdResult(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private final HomeWifiStore store;
    private final WifiPlatform platform;

    // Session & vehicle state
    private long lastSleepGapMs = Long.MIN_VALUE;
    private long powerOffSinceMs = -1L;
    private boolean doorOpenedWhileOff = false;
    private boolean manualActive = false;
    private boolean manualStartedWhilePowerOn = false;
    private long manualDeadlineMs = 0L; // 0 = unlimited while in P
    private boolean sessionActive = false;
    private int savedApMode = 2;
    private boolean bootChecked = false;

    // Connection state machine
    private String phase = "standby";
    private String headline = "Standby";
    private String detail = "Wireless CarPlay / Android Auto is active.";
    private String pinnedSsid = null;
    private String targetSsid = null;
    private String connectedSsid = "";
    private int targetNetId = -1;
    private boolean isConnectingToTarget = false;
    private boolean isFastConnecting = false;
    private boolean isPollingDhcp = false;
    private int dhcpAttempt = 0;
    private long connectStartMs = 0L;
    private int assocRetryCount = 0;
    private int authErrorCount = 0;
    private boolean handshakeSeen = false;
    private int handshakeDropCount = 0;
    private int scanCount = 0;
    private int enablePollCount = 0;
    private int apDisablePollCount = 0;

    private String connectedIp = "";
    private String connectedGateway = "";
    private int connectedRssi = 0;
    private int connectedFreq = 0;
    private boolean adbTcpActive = false;
    private String activeAdbIp = "";

    private String lastErrorSsid = "";
    private String lastErrorReason = "";
    private final Set<String> authFailedLower = new HashSet<String>();
    private final Set<String> pausedAutoLower = new HashSet<String>();
    private List<HomeWifiStore.SeenNetwork> liveScans = new ArrayList<HomeWifiStore.SeenNetwork>();
    private WifiPlatform.VehicleSnapshot lastVehicle = new WifiPlatform.VehicleSnapshot("on", false, false);
    private final HomeWifiKeepAwake keepAwake = new HomeWifiKeepAwake();
    private HomeWifiKeepAwake.Verdict lastKeepAwakeVerdict =
            new HomeWifiKeepAwake.Verdict(false, false, false, false, false, "off", 0, 0L);
    private boolean pendingApRestoreOnPowerOn = false;
    private String lastLoggedVehicleSig = "";
    private String lastLoggedVerdictSig = "";
    private String lastLoggedScanSig = "";
    private long lastHeartbeatMs = 0L;

    public HomeWifiController(HomeWifiStore store, WifiPlatform platform) {
        this.store = store;
        this.platform = platform;
        this.platform.setEventListener(this);
        this.liveScans = new ArrayList<HomeWifiStore.SeenNetwork>(store.loadSeenNetworks());
    }

    private String redactSsid(String ssid) {
        return HomeWifiFlightLog.redactSsid(ssid, store.listProfiles());
    }

    private void recordFlight(String tag, String ownerDetail, String publicDetail, boolean doFsync) {
        long wallSec = platform.wallEpochSec();
        long nowEl = platform.elapsedRealtimeMs();
        String curBoot = platform.bootId();
        store.flightLog().recordEvent(wallSec, nowEl, curBoot, tag, ownerDetail, publicDetail, doFsync);
        if (sessionActive && !lastVehicle.isPowerOn()) {
            store.flightLog().markSessionActive(curBoot, wallSec, nowEl, phase, lastVehicle.power,
                    tag + "(" + publicDetail + ")");
        }
    }

    private void syncAdbOverWifiState(String reason, boolean deferTeardown) {
        boolean want = sessionActive
                && "connected".equals(phase)
                && store.isAdbOverWifi()
                && HomeWifiRouter.validIpv4(connectedIp);
        if (want) {
            boolean changed = !adbTcpActive || !connectedIp.equals(activeAdbIp);
            if (platform.setAdbOverWifi(true, connectedIp, false)) {
                adbTcpActive = true;
                activeAdbIp = connectedIp;
                if (changed) {
                    String ownMsg = "endpoint=" + connectedIp + ":" + HomeWifiRouter.ADB_PORT + " reason=" + reason;
                    String pubMsg = "endpoint=" + HomeWifiFlightLog.redactIp(connectedIp) + ":" + HomeWifiRouter.ADB_PORT
                            + " reason=" + reason;
                    recordFlight("ADB_TCP_UP", ownMsg, pubMsg, true);
                    platform.log("ADB over TCP active on " + connectedIp + ":" + HomeWifiRouter.ADB_PORT);
                }
            }
        } else if (adbTcpActive) {
            adbTcpActive = false;
            activeAdbIp = "";
            platform.setAdbOverWifi(false, "", deferTeardown);
            recordFlight("ADB_TCP_DOWN", "reason=" + reason, "reason=" + reason, true);
            platform.log("Disabled ADB over TCP on Home Wi-Fi (" + reason + ")");
        }
    }

    private void tearDownRoutesAndAdb(String reason) {
        if (adbTcpActive) {
            adbTcpActive = false;
            activeAdbIp = "";
            platform.setAdbOverWifi(false, "", false);
            recordFlight("ADB_TCP_DOWN", "reason=" + reason, "reason=" + reason, true);
            platform.log("Disabled ADB over TCP on Home Wi-Fi (" + reason + ")");
        } else {
            platform.setAdbOverWifi(false, "", false);
        }
        platform.routeDown();
        store.clearLease();
    }

    public synchronized void start() {
        checkBootCleanupAndTick();
    }

    public synchronized void shutdown(String reason) {
        cancelAllTimers();
        platform.cancelScheduled(TAG_VEHICLE);
        if (sessionActive) {
            exitClientSession(reason, true);
        } else {
            store.flightLog().clearSessionActive();
            tearDownRoutesAndAdb("shutdown");
            if (pendingApRestoreOnPowerOn) {
                pendingApRestoreOnPowerOn = false;
                platform.restoreSoftAp(savedApMode);
                store.clearActiveBootId();
            }
        }
        platform.setSessionLocks(false, false);
        store.clearKeepAwakeLease();
        phase = "off";
        headline = "Home Wi-Fi is off";
        detail = "Disabled.";
        store.writePublicStatus("off", 0, 0);
    }

    private void scheduleVehicleTick() {
        long delay = sessionActive ? VEHICLE_POLL_ACTIVE_MS : VEHICLE_POLL_IDLE_MS;
        platform.scheduleDelayed(TAG_VEHICLE, delay, new Runnable() {
            @Override
            public void run() {
                synchronized (HomeWifiController.this) {
                    tickVehicleAndHealth();
                }
            }
        });
    }

    private void checkBootCleanupAndTick() {
        if (!bootChecked) {
            bootChecked = true;
            String prevBoot = store.loadActiveBootId();
            String curBoot = platform.bootId();
            long nowEl = platform.elapsedRealtimeMs();
            long sleepGap = nowEl - platform.uptimeMillis();
            store.flightLog().recordDaemonStart(curBoot, platform.wallEpochSec(), nowEl, sleepGap);
            platform.setAdbOverWifi(false, "", false);
            store.clearLease();
            store.clearKeepAwakeLease();
            if (!prevBoot.isEmpty() && !prevBoot.equals(curBoot)) {
                platform.log("Cleaning up Home Wi-Fi client state left across reboot");
                savedApMode = store.loadApMode();
                platform.routeDown();
                platform.restoreSoftAp(savedApMode);
                store.clearActiveBootId();
                String cleanMsg = "restoredSoftApMode=" + savedApMode;
                recordFlight("REBOOT_AP_CLEANUP", cleanMsg, cleanMsg, true);
            }
        }
        tickVehicleAndHealth();
    }

    private boolean isHomeNetworkSeenOrAssociating() {
        if (!sessionActive) return false;
        if (isPollingDhcp || (!isFastConnecting && isConnectingToTarget)) {
            return true;
        }
        for (HomeWifiStore.SeenNetwork sr : liveScans) {
            if (sr == null || !isAllowedForHomeWifi(sr.ssid)) continue;
            String low = sr.ssid.toLowerCase(Locale.US);
            if (authFailedLower.contains(low) || pausedAutoLower.contains(low)) continue;
            HomeWifiStore.Profile p = store.findProfile(sr.ssid);
            if (p != null && p.useForHomeWifi) {
                return true;
            }
        }
        return false;
    }

    public synchronized void tickVehicleAndHealth() {
        WifiPlatform.VehicleSnapshot veh = platform.readVehicle();
        lastVehicle = veh;

        long nowElapsed = platform.elapsedRealtimeMs();
        String curBoot = platform.bootId();
        long sleepGap = nowElapsed - platform.uptimeMillis();
        if (lastSleepGapMs != Long.MIN_VALUE && (sleepGap - lastSleepGapMs) >= 3000L) {
            long deltaMs = sleepGap - lastSleepGapMs;
            platform.log("System resumed from QuickBoot suspend; refreshing vehicle & Wi-Fi state");
            String strDetail = "gapDeltaMs=" + deltaMs + " totalGapMs=" + sleepGap
                    + " power=" + veh.power + " sessionWasActive=" + sessionActive;
            recordFlight("STR_RESUME", strDetail, strDetail, true);
            if (veh.isPowerOn()) {
                powerOffSinceMs = -1L;
                if (!sessionActive && !pendingApRestoreOnPowerOn
                        && store.isProjectionApRequested()
                        && !platform.isSoftApActiveOrEnabling()
                        && platform.getWifiState() == WifiPlatform.WIFI_STATE_DISABLED) {
                    platform.restoreSoftAp(2);
                    recordFlight("STR_PROJECTION_AP", "mode=2", "mode=2", false);
                }
            }
            if (manualActive) {
                manualActive = false;
                manualDeadlineMs = 0L;
            }
        }
        lastSleepGapMs = sleepGap;

        String vehSig = veh.power + "|" + veh.parked + "|" + veh.pluggedIn + "|" + veh.charging
                + "|" + veh.saver12v + "|" + veh.doorsClosed + "|" + veh.doorsLocked;
        if (!veh.isPowerOn() || sessionActive) {
            if (!vehSig.equals(lastLoggedVehicleSig)) {
                lastLoggedVehicleSig = vehSig;
                String vMsg = "power=" + (veh.power.isEmpty() ? "on" : veh.power)
                        + " parked=" + veh.parked
                        + " gearPark=" + veh.gearPark
                        + " plugged=" + veh.pluggedIn
                        + " charging=" + veh.charging
                        + " doorsClosed=" + veh.doorsClosed
                        + " doorsLocked=" + veh.doorsLocked
                        + " soc12v=" + veh.bat12vSoc
                        + " saver12v=" + veh.saver12v;
                recordFlight("VEHICLE_STATE", vMsg, vMsg, true);
            }
        } else if (!lastLoggedVehicleSig.isEmpty()) {
            lastLoggedVehicleSig = "";
            lastLoggedVerdictSig = "";
            String vMsg = "power=on plugged=" + veh.pluggedIn + " soc12v=" + veh.bat12vSoc;
            recordFlight("VEHICLE_IG_ON", vMsg, vMsg, true);
        }

        if (veh.isPowerOn()) {
            keepAwake.reset();
            lastKeepAwakeVerdict = new HomeWifiKeepAwake.Verdict(false, false, false, false, false, "off", 0, 0L);
            store.clearKeepAwakeLease();
            if (store.flightLog().hasActiveMarker()) {
                store.flightLog().clearSessionActive();
            }
            if (pendingApRestoreOnPowerOn && !sessionActive && !manualActive) {
                pendingApRestoreOnPowerOn = false;
                platform.restoreSoftAp(savedApMode);
                store.clearActiveBootId();
                platform.log("Restored SoftAP mode " + savedApMode + " on power-on after parked sleep");
                recordFlight("SOFTAP_RESTORED", "mode=" + savedApMode, "mode=" + savedApMode, true);
            }
        }

        // Import any newly created system profiles (e.g. fitcamx dashcam SSIDs) if Wi-Fi is enabled
        if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
            store.mergeSystemNetworks(platform.getConfiguredNetworks());
        }

        // Check manual session expiration, IG_ON/IG_OFF power transitions, or gear shift out of Park
        if (manualActive) {
            if (veh.isPowerOn() && !manualStartedWhilePowerOn) {
                manualActive = false;
                manualStartedWhilePowerOn = false;
                manualDeadlineMs = 0L;
                pinnedSsid = null;
                if (sessionActive) {
                    exitClientSession("Car switched on", true);
                    scheduleVehicleTick();
                    return;
                }
            } else if (!veh.isPowerOn() && manualStartedWhilePowerOn) {
                // Hand off cleanly to normal parked keep-awake / 2m away-from-home rules
                manualActive = false;
                manualStartedWhilePowerOn = false;
                manualDeadlineMs = 0L;
                pinnedSsid = null;
            } else if (veh.isPowerOn() && !veh.gearPark) {
                manualActive = false;
                manualStartedWhilePowerOn = false;
                manualDeadlineMs = 0L;
                pinnedSsid = null;
                if (sessionActive) {
                    exitClientSession("Shifted out of Park", true);
                }
            } else if (manualDeadlineMs > 0 && nowElapsed >= manualDeadlineMs) {
                manualActive = false;
                manualStartedWhilePowerOn = false;
                manualDeadlineMs = 0L;
                pinnedSsid = null;
                if (sessionActive && veh.isPowerOn()) {
                    exitClientSession("Manual session timer ended", true);
                }
            }
        }

        boolean offLong = evaluateOffLong(veh, nowElapsed);
        boolean hasHomeProfile = store.hasUsableHomeProfile();
        HomeWifiKeepAwake.PeerLease peerLease = null;

        if (!veh.isPowerOn() && offLong) {
            String ownUnplugged = hasHomeProfile ? store.getKeepAwakeUnplugged() : "off";
            String ownPlugged = hasHomeProfile ? store.getKeepAwakePlugged() : "off";
            boolean wifiConnected = sessionActive && "connected".equals(phase);
            boolean homeSeenOrAssoc = isHomeNetworkSeenOrAssociating();
            boolean waitingForApStop = sessionActive && "disabling_ap".equals(phase);
            peerLease = store.readPeerOpenLinkLease();
            lastKeepAwakeVerdict = keepAwake.evaluate(
                    veh,
                    nowElapsed,
                    curBoot,
                    ownUnplugged,
                    ownPlugged,
                    peerLease,
                    wifiConnected,
                    homeSeenOrAssoc,
                    waitingForApStop);
            if (manualActive && !manualStartedWhilePowerOn && !lastKeepAwakeVerdict.shouldStayInClientMode) {
                // RAP UI scan handed off to parked sleep (e.g. away from home after 2m)
                manualActive = false;
                manualDeadlineMs = 0L;
            }
            store.writeKeepAwakeLease(curBoot, lastKeepAwakeVerdict, veh, nowElapsed);
            String verdictSig = lastKeepAwakeVerdict.shouldStayInClientMode + "|"
                    + lastKeepAwakeVerdict.holdPower + "|"
                    + lastKeepAwakeVerdict.unlimited + "|"
                    + lastKeepAwakeVerdict.extendedByPeer + "|"
                    + lastKeepAwakeVerdict.awayFromHome + "|"
                    + lastKeepAwakeVerdict.cutBy12v + "|"
                    + lastKeepAwakeVerdict.activeCode + "|"
                    + keepAwake.isPlugLatchedWhileLocked();
            if (!verdictSig.equals(lastLoggedVerdictSig)) {
                lastLoggedVerdictSig = verdictSig;
                String kaMsg = "stayClient=" + lastKeepAwakeVerdict.shouldStayInClientMode
                        + " holdPower=" + lastKeepAwakeVerdict.holdPower
                        + " code=" + lastKeepAwakeVerdict.activeCode
                        + " plugLatched=" + keepAwake.isPlugLatchedWhileLocked()
                        + " peer=" + lastKeepAwakeVerdict.extendedByPeer
                        + " away=" + lastKeepAwakeVerdict.awayFromHome
                        + " cut12v=" + lastKeepAwakeVerdict.cutBy12v
                        + " remSec=" + lastKeepAwakeVerdict.remainingSec
                        + " wifiUp=" + wifiConnected
                        + " homeSeen=" + homeSeenOrAssoc
                        + " soc12v=" + veh.bat12vSoc;
                recordFlight("KEEPAWAKE_VERDICT", kaMsg, kaMsg, true);
            }
        } else if (!veh.isPowerOn()) {
            lastKeepAwakeVerdict = new HomeWifiKeepAwake.Verdict(false, true, false, false, false, "off", 0, 0L);
        }

        if (sessionActive) {
            if (veh.isPowerOn() && !manualActive) {
                pinnedSsid = null;
                exitClientSession("Car switched on", true);
                scheduleVehicleTick();
                return;
            }
            if (!veh.isPowerOn() && !manualActive && !lastKeepAwakeVerdict.shouldStayInClientMode) {
                pinnedSsid = null;
                String endWhy = lastKeepAwakeVerdict.cutBy12v
                        ? "12V battery protection floor reached"
                        : (lastKeepAwakeVerdict.awayFromHome
                                ? "No saved Home Wi-Fi connected (away from home)"
                                : "Parked keep-awake timer ended");
                exitClientSession(endWhy, false);
                scheduleVehicleTick();
                return;
            }
            platform.setSessionLocks(true, !veh.isPowerOn() && lastKeepAwakeVerdict.holdPower);
            maintainActiveSession();
            if (!veh.isPowerOn() && (nowElapsed - lastHeartbeatMs) >= HomeWifiFlightLog.HEARTBEAT_INTERVAL_MS) {
                lastHeartbeatMs = nowElapsed;
                String commonTail = " rssi=" + connectedRssi
                        + " adb=" + adbTcpActive
                        + " power=" + (veh.power.isEmpty() ? "off" : veh.power)
                        + " plugged=" + veh.pluggedIn
                        + " plugLatched=" + keepAwake.isPlugLatchedWhileLocked()
                        + " charging=" + veh.charging
                        + " soc12v=" + veh.bat12vSoc
                        + " holdPower=" + lastKeepAwakeVerdict.holdPower
                        + " peer=" + lastKeepAwakeVerdict.extendedByPeer
                        + " remSec=" + lastKeepAwakeVerdict.remainingSec;
                String ownHb = "phase=" + phase
                        + " ssid=" + (connectedSsid.isEmpty() ? "-" : ("\"" + connectedSsid + "\""))
                        + commonTail;
                String pubHb = "phase=" + phase
                        + " net=" + redactSsid(connectedSsid)
                        + commonTail;
                recordFlight("HEARTBEAT", ownHb, pubHb, true);
            }
            scheduleVehicleTick();
            return;
        }

        // Not currently in a Home Wi-Fi session
        if (!manualActive && platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED && !pendingApRestoreOnPowerOn) {
            // Stock Wi-Fi Settings or Tilepad turned Wi-Fi on independently: leave it alone
            platform.setSessionLocks(false, false);
            phase = "stock";
            headline = "Managed by system Wi-Fi settings";
            detail = "The head unit's built-in Wi-Fi settings currently control Wi-Fi.";
            store.writePublicStatus("stock", 0, 0);
            scheduleVehicleTick();
            return;
        }

        if (manualActive && veh.gearPark) {
            enterClientSession("Manual session in Park");
            platform.setSessionLocks(true, false);
            scheduleVehicleTick();
            return;
        }

        if (offLong) {
            if (hasHomeProfile) {
                boolean shuttingDownNow = "preparing_off".equalsIgnoreCase(veh.power)
                        || "off".equalsIgnoreCase(veh.power);
                boolean onlyPeerTeardownFlush = !lastKeepAwakeVerdict.ownHoldPower
                        && lastKeepAwakeVerdict.extendedByPeer
                        && peerLease != null
                        && peerLease.flushingFinalSync;
                if (lastKeepAwakeVerdict.shouldStayInClientMode && !shuttingDownNow && !onlyPeerTeardownFlush) {
                    enterClientSession("Car parked / powered off");
                    platform.setSessionLocks(true, lastKeepAwakeVerdict.holdPower);
                } else {
                    boolean holdPeerOrOwnPower = lastKeepAwakeVerdict.holdPower && !shuttingDownNow && !onlyPeerTeardownFlush;
                    platform.setSessionLocks(false, holdPeerOrOwnPower);
                    phase = "sleep";
                    if (lastKeepAwakeVerdict.cutBy12v) {
                        headline = "Paused (12V battery protection)";
                        detail = "Home Wi-Fi disconnected early to protect the 12V battery.";
                    } else if (lastKeepAwakeVerdict.awayFromHome) {
                        headline = "Standby (Away from Home Wi-Fi)";
                        detail = "No saved Home Wi-Fi network connected within 2 minutes of parking; allowing the head unit to power off normally.";
                    } else {
                        headline = "Parked keep-awake ended";
                        detail = "Home Wi-Fi completed its parked keep-awake window and is ready to sleep.";
                    }
                    store.writePublicStatus("waiting", 0, 0);
                }
            } else {
                platform.setSessionLocks(false, false);
                phase = "no_networks";
                headline = "No Home Wi-Fi networks enabled";
                detail = "Add a Wi-Fi network (or enable Use for Home Wi-Fi on a saved network) so the car can connect when parked.";
                store.writePublicStatus("nonetworks", 0, 0);
            }
            scheduleVehicleTick();
            return;
        }

        boolean admPreLatch = !veh.isPowerOn() && shouldPreLatchAdmPower(veh, curBoot, nowElapsed, hasHomeProfile);
        platform.setSessionLocks(false, admPreLatch);
        phase = "standby";
        headline = veh.isPowerOn() ? "Standby (Car is on)" : "Standby (Accessory / RAP)";
        if (hasHomeProfile) {
            detail = "Wireless CarPlay / Android Auto is active. Home Wi-Fi connects automatically when parked, or tap \"Scan & Connect Now\" in Park.";
        } else {
            detail = "No Home Wi-Fi network set up yet. Shift to Park and tap \"Scan & Connect Now\" to choose your network.";
        }
        store.writePublicStatus("waiting", 0, 0);
        scheduleVehicleTick();
    }

    private boolean shouldPreLatchAdmPower(WifiPlatform.VehicleSnapshot veh,
                                           String curBoot,
                                           long nowElapsed,
                                           boolean hasHomeProfile) {
        if (veh == null || veh.isPowerOn() || veh.saver12v || keepAwake.isCutBy12v()) return false;
        if ("preparing_off".equalsIgnoreCase(veh.power) || "off".equalsIgnoreCase(veh.power)) return false;
        int floor = veh.pluggedIn ? HomeWifiKeepAwake.MIN_12V_PLUGGED_IDLE : HomeWifiKeepAwake.MIN_12V_UNPLUGGED;
        if (!veh.charging && veh.bat12vSoc >= 0 && veh.bat12vSoc < floor) return false;
        HomeWifiKeepAwake.PeerLease peer = store.readPeerOpenLinkLease();
        if (peer != null && peer.isFreshAndActive(curBoot, nowElapsed) && !peer.flushingFinalSync) {
            return true;
        }
        if (keepAwake.isTimerExpiredThisPark() || keepAwake.isAwayTimedOut()) return false;
        if (!hasHomeProfile) return false;
        boolean effPlugged = veh.pluggedIn || keepAwake.isPlugLatchedWhileLocked();
        String code = effPlugged
                ? HomeWifiKeepAwake.normalizePluggedCode(store.getKeepAwakePlugged())
                : HomeWifiKeepAwake.normalizeUnpluggedCode(store.getKeepAwakeUnplugged());
        return !"off".equals(code);
    }

    private boolean evaluateOffLong(WifiPlatform.VehicleSnapshot veh, long nowElapsed) {
        if (veh.isPowerOn()) {
            powerOffSinceMs = -1L;
            doorOpenedWhileOff = false;
            return false;
        }
        if (veh.doorsClosed == 0) {
            doorOpenedWhileOff = true;
        }
        if (veh.parked || (doorOpenedWhileOff && veh.gearPark)) {
            return true;
        }
        if (powerOffSinceMs < 0L) {
            powerOffSinceMs = nowElapsed;
            return false;
        }
        return (nowElapsed - powerOffSinceMs) >= OFF_DELAY_MS;
    }

    private void enterClientSession(String reason) {
        sessionActive = true;
        isConnectingToTarget = false;
        isFastConnecting = false;
        isPollingDhcp = false;
        targetSsid = null;
        targetNetId = -1;
        connectedSsid = "";
        connectedIp = "";
        connectedGateway = "";
        connectedRssi = 0;
        connectedFreq = 0;
        assocRetryCount = 0;
        authErrorCount = 0;
        handshakeSeen = false;
        handshakeDropCount = 0;
        scanCount = 0;
        enablePollCount = 0;
        lastLoggedScanSig = "";
        lastHeartbeatMs = platform.elapsedRealtimeMs();
        liveScans.clear();
        pausedAutoLower.clear();
        cancelAllTimers();

        if (!pendingApRestoreOnPowerOn) {
            boolean apOn = platform.isSoftApActiveOrEnabling();
            boolean apDisabling = platform.getSoftApState() == WifiPlatform.AP_STATE_DISABLING;
            int mode = platform.getSoftApMode();
            if (mode != 1 && mode != 2) mode = 2;
            savedApMode = (apOn || apDisabling) ? mode : 2;
            platform.captureSoftApConfig();
            store.saveApMode(savedApMode);
            store.saveActiveBootId(platform.bootId());
            String priorLabel = apOn ? ("mode " + savedApMode) : (apDisabling ? ("disabling mode " + savedApMode) : "off");
            platform.log("Starting Home Wi-Fi (" + reason + "; prior SoftAP=" + priorLabel + ")");
            String startMsg = "reason=\"" + reason + "\" priorSoftAp="
                    + ((apOn || apDisabling) ? ("mode" + savedApMode) : "off")
                    + " power=" + (lastVehicle.power.isEmpty() ? "on" : lastVehicle.power)
                    + " plugged=" + lastVehicle.pluggedIn;
            recordFlight("SESSION_START", startMsg, startMsg, true);
            if (apOn || apDisabling) {
                phase = "disabling_ap";
                headline = "Preparing Wi-Fi…";
                detail = "Pausing wireless CarPlay / Android Auto hotspot…";
                store.writePublicStatus(store.hasUsableHomeProfile() ? "searching" : "nonetworks", 0, 0);
                if (apOn) {
                    platform.disableSoftAp(savedApMode);
                }
                apDisablePollCount = 0;
                scheduleApDisablePoll();
                return;
            }
        } else {
            pendingApRestoreOnPowerOn = false;
            store.saveActiveBootId(platform.bootId());
            platform.log("Resuming Home Wi-Fi (" + reason + ")");
            String resumeMsg = "reason=\"" + reason + "\" resumedFromDeferredAp=true"
                    + " power=" + (lastVehicle.power.isEmpty() ? "on" : lastVehicle.power);
            recordFlight("SESSION_START", resumeMsg, resumeMsg, true);
        }
        enableStationHardware();
    }

    private boolean isSoftApBusy() {
        int st = platform.getSoftApState();
        return st == WifiPlatform.AP_STATE_DISABLING
                || st == WifiPlatform.AP_STATE_ENABLING
                || st == WifiPlatform.AP_STATE_ENABLED
                || platform.isSoftApActiveOrEnabling();
    }

    /**
     * Polls SoftAP state while in {@code disabling_ap} so {@code setWifiEnabled(true)} is never
     * called while Android 4.4 {@code WifiStateMachine} is still inside {@code SoftApStoppingState}
     * ({@code AP_STATE_DISABLING == 10}, e.g. waiting for {@code GalReceiver} / {@code hostapd}
     * socket teardown).
     */
    private void scheduleApDisablePoll() {
        platform.scheduleDelayed(TAG_AP_FALLBACK, AP_DISABLE_POLL_MS, new Runnable() {
            @Override
            public void run() {
                synchronized (HomeWifiController.this) {
                    if (!sessionActive || !"disabling_ap".equals(phase)) return;
                    if (!isSoftApBusy()) {
                        enableStationHardware();
                        return;
                    }
                    apDisablePollCount++;
                    int st = platform.getSoftApState();
                    if ((st == WifiPlatform.AP_STATE_ENABLED || st == WifiPlatform.AP_STATE_ENABLING)
                            && apDisablePollCount == 6) {
                        platform.disableSoftAp(savedApMode);
                    }
                    if (apDisablePollCount < AP_DISABLE_MAX_POLLS) {
                        scheduleApDisablePoll();
                    } else {
                        String timeoutMsg = "polls=" + apDisablePollCount + " apState=" + st;
                        recordFlight("AP_DISABLE_TIMEOUT", timeoutMsg, timeoutMsg, true);
                        enableStationHardware();
                    }
                }
            }
        });
    }

    private void exitClientSession(String reason) {
        exitClientSession(reason, true);
    }

    private void exitClientSession(String reason, boolean restoreApNow) {
        cancelAllTimers();
        sessionActive = false;
        isConnectingToTarget = false;
        isFastConnecting = false;
        isPollingDhcp = false;
        targetSsid = null;
        targetNetId = -1;
        String prevConnected = connectedSsid;
        connectedSsid = "";
        connectedIp = "";
        connectedGateway = "";
        connectedRssi = 0;
        connectedFreq = 0;

        store.flightLog().clearSessionActive();
        tearDownRoutesAndAdb("session_end");
        store.writePublicStatus("waiting", 0, 0);
        boolean keepParkedPower = !restoreApNow && !lastVehicle.isPowerOn() && lastKeepAwakeVerdict.holdPower;
        platform.setSessionLocks(false, keepParkedPower);

        String ownEnd = "reason=\"" + reason + "\" restoreApNow=" + restoreApNow
                + " wasConnected=" + (prevConnected.isEmpty() ? "none" : ("\"" + prevConnected + "\""));
        String pubEnd = "reason=\"" + reason + "\" restoreApNow=" + restoreApNow
                + " wasConnected=" + redactSsid(prevConnected);
        recordFlight("SESSION_END", ownEnd, pubEnd, true);

        if (restoreApNow) {
            pendingApRestoreOnPowerOn = false;
            platform.restoreSoftAp(savedApMode);
            store.clearActiveBootId();
            platform.log("Stopped Home Wi-Fi (" + reason
                    + (prevConnected.isEmpty() ? "" : "; was connected") + "); restored SoftAP mode " + savedApMode);
            phase = "standby";
            headline = "Standby (Car is on)";
            detail = "Wireless CarPlay / Android Auto has been restored.";
        } else {
            // Car is still OFF at end of keep-awake: turn off client Wi-Fi cleanly without
            // spinning up 5 GHz SoftAP right before head-unit sleep; restore SoftAP on next IG ON!
            pendingApRestoreOnPowerOn = true;
            platform.stopClientWifiOnly();
            platform.log("Stopped Home Wi-Fi for parked sleep (" + reason
                    + (prevConnected.isEmpty() ? "" : "; was connected") + "); SoftAP restore deferred to power-on");
            phase = "sleep";
            if (lastKeepAwakeVerdict.cutBy12v) {
                headline = "Paused (12V battery protection)";
                detail = "Home Wi-Fi disconnected early to protect the 12V battery.";
            } else if (lastKeepAwakeVerdict.awayFromHome) {
                headline = "Standby (Away from Home Wi-Fi)";
                detail = "No saved Home Wi-Fi network connected within 2 minutes of parking; allowing the head unit to power off normally.";
            } else {
                headline = "Parked keep-awake ended";
                detail = "Home Wi-Fi completed its parked keep-awake window and is ready to sleep.";
            }
        }
    }

    private void enableStationHardware() {
        if (!sessionActive) return;
        int st = platform.getWifiState();
        if (st == WifiPlatform.WIFI_STATE_ENABLED) {
            onStationHardwareReady();
            return;
        }
        phase = "enabling_wifi";
        headline = "Enabling Wi-Fi…";
        detail = "Starting head unit Wi-Fi client hardware…";
        store.writePublicStatus(store.hasUsableHomeProfile() ? "searching" : "nonetworks", 0, 0);
        if (st != WifiPlatform.WIFI_STATE_ENABLING) {
            platform.setWifiEnabled(true);
        }
        enablePollCount = 0;
        scheduleWifiEnablePoll();
    }

    private void scheduleWifiEnablePoll() {
        platform.scheduleDelayed(TAG_WIFI_ENABLE, WIFI_ENABLE_POLL_MS, new Runnable() {
            @Override
            public void run() {
                synchronized (HomeWifiController.this) {
                    if (!sessionActive || !"enabling_wifi".equals(phase)) return;
                    if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                        onStationHardwareReady();
                    } else if (++enablePollCount < 25) {
                        scheduleWifiEnablePoll();
                    } else {
                        platform.setWifiEnabled(true);
                        enablePollCount = 0;
                        scheduleWifiEnablePoll();
                    }
                }
            }
        });
    }

    private void onStationHardwareReady() {
        platform.cancelScheduled(TAG_AP_FALLBACK);
        platform.cancelScheduled(TAG_WIFI_ENABLE);
        if (!sessionActive) return;

        keepAwake.notifyStationHardwareReady(platform.elapsedRealtimeMs());
        syncProfilesAndEnforceExclusions();
        String readyMsg = "savedProfiles=" + store.listProfiles().size();
        recordFlight("WIFI_READY", readyMsg, readyMsg, false);

        // Check if already associated to an allowed Home Wi-Fi network
        WifiPlatform.ConnectionSnapshot snap = platform.getConnectionSnapshot();
        if (!WifiSecurity.isPlaceholderSsid(snap.ssid) && isAllowedForHomeWifi(snap.ssid)) {
            if (snap.supState == WifiPlatform.SupState.COMPLETED) {
                targetSsid = snap.ssid;
                startDhcpLeasePoll(snap.ssid);
                return;
            }
        }

        // Kick off an immediate scan so Nearby Networks populate right away
        platform.startScan();
        startScanLoop();

        // Fast-connect to pinned or most-recently-connected allowed profile while scan runs
        String fastCandidate = chooseFastConnectCandidate();
        if (fastCandidate != null) {
            connectToProfile(fastCandidate, true);
        } else {
            updateScanningStatus();
        }
    }

    /**
     * Syncs enabled Home Wi-Fi profiles into WifiManager and disables all excluded/dashcam
     * profiles in RAM (without removing them from flash, preserving fitcamx-gen5w profiles).
     */
    private void syncProfilesAndEnforceExclusions() {
        List<WifiPlatform.ConfiguredNet> sysNets = platform.getConfiguredNetworks();
        store.mergeSystemNetworks(sysNets);

        for (HomeWifiStore.Profile p : store.listProfiles()) {
            if (p.useForHomeWifi && p.hasExplicitCredentials()) {
                platform.upsertWifiNetwork(p.ssid, p.psk, p.open, p.capabilities, false);
            }
        }

        sysNets = platform.getConfiguredNetworks();
        if (sysNets != null) {
            for (WifiPlatform.ConfiguredNet net : sysNets) {
                if (net == null) continue;
                boolean allowed = isAllowedForHomeWifi(net.ssid)
                        && !pausedAutoLower.contains(net.ssid.toLowerCase(Locale.US));
                if (!allowed) {
                    platform.disableNetworkInRam(net.netId);
                }
            }
        }

        WifiPlatform.ConnectionSnapshot cur = platform.getConnectionSnapshot();
        if (!WifiSecurity.isPlaceholderSsid(cur.ssid) && !isAllowedForHomeWifi(cur.ssid)) {
            platform.disconnect();
        }
    }

    private boolean isAllowedForHomeWifi(String ssid) {
        String clean = WifiSecurity.cleanSsid(ssid);
        if (clean.isEmpty()) return false;
        HomeWifiStore.Profile p = store.findProfile(clean);
        if (p != null) return p.useForHomeWifi;
        return !WifiSecurity.isLikelyDashcamSsid(clean);
    }

    private String chooseFastConnectCandidate() {
        List<WifiPlatform.ConfiguredNet> sysNets = platform.getConfiguredNetworks();
        Set<String> inWifiMgr = new HashSet<String>();
        if (sysNets != null) {
            for (WifiPlatform.ConfiguredNet n : sysNets) {
                if (n != null && !n.ssid.isEmpty()) inWifiMgr.add(n.ssid.toLowerCase(Locale.US));
            }
        }
        if (pinnedSsid != null && isAllowedForHomeWifi(pinnedSsid)
                && !pausedAutoLower.contains(pinnedSsid.toLowerCase(Locale.US))
                && inWifiMgr.contains(pinnedSsid.toLowerCase(Locale.US))) {
            return pinnedSsid;
        }
        HomeWifiStore.Profile best = null;
        for (HomeWifiStore.Profile p : store.listProfiles()) {
            if (!p.useForHomeWifi) continue;
            String low = p.ssid.toLowerCase(Locale.US);
            if (pausedAutoLower.contains(low) || !inWifiMgr.contains(low)) continue;
            if (best == null || p.lastConnectedSec >= best.lastConnectedSec) {
                best = p;
            }
        }
        return best == null ? null : best.ssid;
    }

    private String chooseBestScannedCandidate() {
        if (pinnedSsid != null && isAllowedForHomeWifi(pinnedSsid)
                && !pausedAutoLower.contains(pinnedSsid.toLowerCase(Locale.US))) {
            HomeWifiStore.SeenNetwork sr = findScan(pinnedSsid);
            if (sr != null) return sr.ssid;
        }
        for (HomeWifiStore.SeenNetwork sr : liveScans) {
            if (!isAllowedForHomeWifi(sr.ssid)) continue;
            if (pausedAutoLower.contains(sr.ssid.toLowerCase(Locale.US))) continue;
            HomeWifiStore.Profile p = store.findProfile(sr.ssid);
            if (p != null && p.useForHomeWifi) {
                return p.ssid;
            }
        }
        return null;
    }

    private HomeWifiStore.SeenNetwork findScan(String ssid) {
        String clean = WifiSecurity.cleanSsid(ssid);
        for (HomeWifiStore.SeenNetwork s : liveScans) {
            if (s.ssid.equalsIgnoreCase(clean)) return s;
        }
        return null;
    }

    private WifiPlatform.ConfiguredNet findConfiguredNet(String ssid) {
        String clean = WifiSecurity.cleanSsid(ssid);
        List<WifiPlatform.ConfiguredNet> list = platform.getConfiguredNetworks();
        if (list == null) return null;
        for (WifiPlatform.ConfiguredNet n : list) {
            if (n != null && n.ssid.equalsIgnoreCase(clean)) return n;
        }
        return null;
    }

    private void connectToProfile(String ssid, boolean fastPath) {
        connectToProfile(ssid, fastPath, false);
    }

    private void connectToProfile(String ssid, boolean fastPath, boolean forceCredUpdate) {
        String clean = WifiSecurity.cleanSsid(ssid);
        HomeWifiStore.Profile p = store.findProfile(clean);
        HomeWifiStore.SeenNetwork sr = findScan(clean);
        String caps = (sr != null && !sr.capabilities.isEmpty()) ? sr.capabilities
                : (p != null ? p.capabilities : null);

        int netId = -1;
        if (p != null && p.hasExplicitCredentials()) {
            netId = platform.upsertWifiNetwork(clean, p.psk, p.open, caps, forceCredUpdate);
        } else {
            WifiPlatform.ConfiguredNet existing = findConfiguredNet(clean);
            if (existing != null) netId = existing.netId;
        }

        if (netId == -1) {
            isConnectingToTarget = false;
            isFastConnecting = false;
            updateScanningStatus();
            return;
        }

        platform.cancelScheduled(TAG_FAST_FALLBACK);
        platform.cancelScheduled(TAG_ASSOC_SETTLE);
        isPollingDhcp = false;
        platform.cancelScheduled(TAG_DHCP_POLL);

        targetSsid = clean;
        targetNetId = netId;
        isConnectingToTarget = true;
        isFastConnecting = fastPath;
        connectStartMs = platform.elapsedRealtimeMs();
        assocRetryCount = 0;
        authErrorCount = 0;
        handshakeSeen = false;
        handshakeDropCount = 0;

        WifiPlatform.ConnectionSnapshot cur = platform.getConnectionSnapshot();
        boolean alreadyAssociating = clean.equalsIgnoreCase(cur.ssid) && isHandshakeInFlight(cur.supState);
        if (!alreadyAssociating) {
            platform.enableOnlyNetwork(netId);
            if (cur.supState == WifiPlatform.SupState.DISCONNECTED
                    || cur.supState == WifiPlatform.SupState.INACTIVE
                    || cur.supState == WifiPlatform.SupState.DORMANT
                    || cur.supState == WifiPlatform.SupState.SCANNING) {
                platform.reconnect();
            }
        }

        if (!fastPath) {
            stopScanLoop();
        }
        phase = "connecting";
        headline = "Connecting to " + clean + "…";
        detail = sr != null
                ? "Associating with access point (" + sr.rssiDbm + " dBm, " + WifiSecurity.bandLabel(sr.freqMhz) + ")…"
                : "Looking for access point and associating…";
        store.writePublicStatus("searching", 0, 0);
        String scanPart = sr != null ? (" rssi=" + sr.rssiDbm + " freq=" + sr.freqMhz) : "";
        recordFlight("CONNECT_START",
                "ssid=\"" + clean + "\" fast=" + fastPath + scanPart,
                "target=" + redactSsid(clean) + " fast=" + fastPath + scanPart,
                true);

        scheduleAssocWatchdog(ASSOC_WATCHDOG_MS);
        if (fastPath) {
            platform.scheduleDelayed(TAG_FAST_FALLBACK, FAST_CONNECT_FALLBACK_MS, new Runnable() {
                @Override
                public void run() {
                    synchronized (HomeWifiController.this) {
                        if (!sessionActive || !isConnectingToTarget || !isFastConnecting || isPollingDhcp) return;
                        WifiPlatform.ConnectionSnapshot s = platform.getConnectionSnapshot();
                        if (isHandshakeInFlight(s.supState) && s.supState != WifiPlatform.SupState.ASSOCIATING) {
                            return;
                        }
                        isFastConnecting = false;
                        isConnectingToTarget = false;
                        platform.cancelScheduled(TAG_ASSOC_WATCHDOG);
                        startScanLoop();
                        String alt = chooseBestScannedCandidate();
                        if (alt != null && !alt.equalsIgnoreCase(targetSsid)) {
                            connectToProfile(alt, false);
                        } else {
                            updateScanningStatus();
                        }
                    }
                }
            });
        }
    }

    private static boolean isHandshakeInFlight(WifiPlatform.SupState st) {
        return st == WifiPlatform.SupState.ASSOCIATING
                || st == WifiPlatform.SupState.ASSOCIATED
                || st == WifiPlatform.SupState.AUTHENTICATING
                || st == WifiPlatform.SupState.FOUR_WAY_HANDSHAKE
                || st == WifiPlatform.SupState.GROUP_HANDSHAKE
                || st == WifiPlatform.SupState.COMPLETED;
    }

    private void scheduleAssocWatchdog(long delayMs) {
        platform.scheduleDelayed(TAG_ASSOC_WATCHDOG, delayMs, new Runnable() {
            @Override
            public void run() {
                synchronized (HomeWifiController.this) {
                    onAssocWatchdogFired();
                }
            }
        });
    }

    private void onAssocWatchdogFired() {
        if (!sessionActive || !isConnectingToTarget || isPollingDhcp) return;
        WifiPlatform.ConnectionSnapshot cur = platform.getConnectionSnapshot();
        if (cur.supState == WifiPlatform.SupState.COMPLETED && isAllowedForHomeWifi(cur.ssid)) {
            startDhcpLeasePoll(cur.ssid);
            return;
        }
        if (cur.supState == WifiPlatform.SupState.FOUR_WAY_HANDSHAKE
                || cur.supState == WifiPlatform.SupState.GROUP_HANDSHAKE) {
            scheduleAssocWatchdog(3500L);
            return;
        }

        if (assocRetryCount >= MAX_ASSOC_RETRIES) {
            String failed = targetSsid == null ? "" : targetSsid;
            isConnectingToTarget = false;
            isFastConnecting = false;
            if (!failed.isEmpty()) {
                pausedAutoLower.add(failed.toLowerCase(Locale.US));
                if (targetNetId != -1) platform.disableNetworkInRam(targetNetId);
            }
            lastErrorSsid = failed;
            lastErrorReason = "Timed out trying to associate with \"" + failed + "\" (router out of range or unresponsive).";
            recordFlight("ASSOC_TIMEOUT",
                    "ssid=\"" + failed + "\" supState=" + cur.supState,
                    "target=" + redactSsid(failed) + " supState=" + cur.supState,
                    true);
            startScanLoop();
            String alt = chooseBestScannedCandidate();
            if (alt != null) {
                connectToProfile(alt, false);
            } else {
                updateScanningStatus();
            }
            return;
        }

        assocRetryCount++;
        platform.log("Nudging stalled association (" + assocRetryCount + "/" + MAX_ASSOC_RETRIES + ", state=" + cur.supState + ")");
        recordFlight("ASSOC_RETRY",
                "attempt=" + assocRetryCount + "/" + MAX_ASSOC_RETRIES + " supState=" + cur.supState,
                "attempt=" + assocRetryCount + "/" + MAX_ASSOC_RETRIES + " supState=" + cur.supState,
                false);
        detail = "Retrying Wi-Fi association (" + assocRetryCount + "/" + MAX_ASSOC_RETRIES + ")…";
        if (cur.supState == WifiPlatform.SupState.ASSOCIATING
                || cur.supState == WifiPlatform.SupState.ASSOCIATED
                || cur.supState == WifiPlatform.SupState.AUTHENTICATING) {
            platform.disconnect();
            platform.scheduleDelayed(TAG_ASSOC_SETTLE, ASSOC_SETTLE_MS, new Runnable() {
                @Override
                public void run() {
                    synchronized (HomeWifiController.this) {
                        if (!sessionActive || !isConnectingToTarget || isPollingDhcp) return;
                        if (targetNetId != -1) {
                            connectStartMs = platform.elapsedRealtimeMs();
                            platform.enableOnlyNetwork(targetNetId);
                            platform.reconnect();
                            scheduleAssocWatchdog(ASSOC_WATCHDOG_MS);
                        }
                    }
                }
            });
        } else if (targetNetId != -1) {
            connectStartMs = platform.elapsedRealtimeMs();
            platform.enableOnlyNetwork(targetNetId);
            platform.reconnect();
            scheduleAssocWatchdog(ASSOC_WATCHDOG_MS);
        }
    }

    private void startScanLoop() {
        long intervalMs = (!lastVehicle.isPowerOn() && lastKeepAwakeVerdict.awayFromHome)
                ? AWAY_PEER_SCAN_INTERVAL_MS
                : SCAN_INTERVAL_MS;
        platform.scheduleDelayed(TAG_SCAN, intervalMs, new Runnable() {
            @Override
            public void run() {
                synchronized (HomeWifiController.this) {
                    if (!sessionActive || isPollingDhcp || "connected".equals(phase)) return;
                    if (!isConnectingToTarget || isFastConnecting) {
                        if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                            scanCount++;
                            platform.startScan();
                        }
                        startScanLoop();
                    }
                }
            }
        });
    }

    private void stopScanLoop() {
        platform.cancelScheduled(TAG_SCAN);
    }

    private void updateScanningStatus() {
        if (!sessionActive) return;
        if (!lastErrorSsid.isEmpty() && authFailedLower.contains(lastErrorSsid.toLowerCase(Locale.US))) {
            phase = "auth_failed";
            headline = "Incorrect password for " + lastErrorSsid;
            detail = lastErrorReason;
            store.writePublicStatus("searching", 0, 0);
            return;
        }
        int count = liveScans.size();
        if (!store.hasUsableHomeProfile()) {
            phase = "scanning";
            headline = "Scanning for Wi-Fi networks…";
            detail = count > 0
                    ? "Found " + count + " nearby network" + (count == 1 ? "" : "s") + " — tap Connect on your network below."
                    : "Searching for nearby Wi-Fi networks…";
            store.writePublicStatus("nonetworks", 0, 0);
        } else {
            phase = "scanning";
            headline = "Searching for saved networks…";
            if (!lastErrorReason.isEmpty()) {
                detail = lastErrorReason;
            } else {
                detail = count > 0
                        ? "Scanned " + count + " nearby network" + (count == 1 ? "" : "s") + " (saved Home Wi-Fi networks are out of range)."
                        : "Scanning for your saved Home Wi-Fi networks…";
            }
            store.writePublicStatus("searching", 0, 0);
        }
    }

    @Override
    public synchronized void onVehicleSignalChanged() {
        tickVehicleAndHealth();
    }

    @Override
    public synchronized void onApStateChanged(int apState) {
        if (!sessionActive) return;
        if (apState == WifiPlatform.AP_STATE_DISABLED && "disabling_ap".equals(phase)) {
            enableStationHardware();
            return;
        }
        if ((apState == WifiPlatform.AP_STATE_ENABLING || apState == WifiPlatform.AP_STATE_ENABLED)
                && !"disabling_ap".equals(phase)) {
            reclaimFromSoftApHijack("ap_state:" + apState);
        }
    }

    @Override
    public synchronized void onWifiStateChanged(int wifiState) {
        if (!sessionActive) return;
        if (wifiState == WifiPlatform.WIFI_STATE_ENABLED && "enabling_wifi".equals(phase)) {
            onStationHardwareReady();
            return;
        }
        if ((wifiState == WifiPlatform.WIFI_STATE_DISABLED || wifiState == WifiPlatform.WIFI_STATE_DISABLING)
                && !"disabling_ap".equals(phase) && !"enabling_wifi".equals(phase)) {
            reclaimFromSoftApHijack("wifi_state:" + wifiState);
        }
    }

    private void reclaimFromSoftApHijack(String trigger) {
        if (!sessionActive) return;
        cancelAllTimers();
        isConnectingToTarget = false;
        isFastConnecting = false;
        isPollingDhcp = false;
        targetSsid = null;
        targetNetId = -1;
        connectedSsid = "";
        connectedIp = "";
        connectedGateway = "";
        connectedRssi = 0;
        connectedFreq = 0;

        tearDownRoutesAndAdb("softap_reclaim");
        store.writePublicStatus(store.hasUsableHomeProfile() ? "searching" : "nonetworks", 0, 0);

        String msg = "trigger=" + trigger + " apOn=" + platform.isSoftApActiveOrEnabling();
        recordFlight("SOFTAP_RECLAIM", msg, msg, true);
        platform.log("Reclaiming wlan0 for Home Wi-Fi (" + msg + ")");

        if (isSoftApBusy()) {
            phase = "disabling_ap";
            headline = "Reclaiming Wi-Fi…";
            detail = "Pausing wireless projection hotspot to restore Home Wi-Fi…";
            if (platform.isSoftApActiveOrEnabling()) {
                platform.disableSoftAp(savedApMode);
            }
            apDisablePollCount = 0;
            scheduleApDisablePoll();
        } else {
            enableStationHardware();
        }
    }

    @Override
    public synchronized void onScanResultsAvailable() {
        List<HomeWifiStore.SeenNetwork> fresh = HomeWifiStore.deduplicateAndSortScans(platform.getScanResults());
        liveScans = new ArrayList<HomeWifiStore.SeenNetwork>(fresh);
        if (!liveScans.isEmpty()) {
            store.saveSeenNetworks(liveScans);
        }
        if (!sessionActive || isPollingDhcp || "connected".equals(phase)) return;

        // Re-enforce dashcam/excluded network disablement in case system Wi-Fi discovered anything
        syncProfilesAndEnforceExclusions();

        int allowedSeen = 0;
        for (HomeWifiStore.SeenNetwork s : liveScans) {
            HomeWifiStore.Profile p = store.findProfile(s.ssid);
            if (p != null && p.useForHomeWifi) allowedSeen++;
        }
        String scanSig = liveScans.size() + "|" + allowedSeen;
        if (!scanSig.equals(lastLoggedScanSig)) {
            lastLoggedScanSig = scanSig;
            String scanMsg = "nearby=" + liveScans.size() + " homeInRange=" + allowedSeen;
            recordFlight("SCAN_RESULTS", scanMsg, scanMsg, false);
        }

        if (isConnectingToTarget) {
            if (isFastConnecting && targetSsid != null) {
                HomeWifiStore.SeenNetwork targetScan = findScan(targetSsid);
                if (targetScan != null) {
                    platform.cancelScheduled(TAG_FAST_FALLBACK);
                    isFastConnecting = false;
                    stopScanLoop();
                } else {
                    String alt = chooseBestScannedCandidate();
                    if (alt != null && !alt.equalsIgnoreCase(targetSsid)) {
                        connectToProfile(alt, false);
                    }
                }
            }
            return;
        }

        String candidate = chooseBestScannedCandidate();
        if (candidate != null) {
            connectToProfile(candidate, false);
        } else {
            updateScanningStatus();
        }
    }

    @Override
    public synchronized void onSupplicantStateChanged(WifiPlatform.SupState state, boolean authError) {
        if (!sessionActive || state == null) return;

        if (authError) {
            authErrorCount++;
            if (WifiSecurity.shouldTriggerAuthFallback(authErrorCount, handshakeDropCount, 0, MAX_ASSOC_RETRIES)) {
                handleAuthenticationRejected();
            }
            return;
        }

        switch (state) {
            case ASSOCIATING:
                if (isConnectingToTarget && targetSsid != null) {
                    phase = "connecting";
                    headline = "Connecting to " + targetSsid + "…";
                    detail = "Associating with access point…";
                }
                break;
            case ASSOCIATED:
            case AUTHENTICATING:
                if (isConnectingToTarget && targetSsid != null) {
                    phase = "authenticating";
                    headline = "Connecting to " + targetSsid + "…";
                    detail = "Authenticating with access point…";
                    scheduleAssocWatchdog(4000L);
                }
                break;
            case FOUR_WAY_HANDSHAKE:
            case GROUP_HANDSHAKE:
                handshakeSeen = true;
                if (isConnectingToTarget && targetSsid != null) {
                    phase = "authenticating";
                    headline = "Connecting to " + targetSsid + "…";
                    detail = "Verifying WPA2 Wi-Fi password…";
                    scheduleAssocWatchdog(4000L);
                }
                break;
            case COMPLETED: {
                WifiPlatform.ConnectionSnapshot snap = platform.getConnectionSnapshot();
                String ssid = !WifiSecurity.isPlaceholderSsid(snap.ssid) ? snap.ssid
                        : (targetSsid == null ? "" : targetSsid);
                if (!ssid.isEmpty() && !isAllowedForHomeWifi(ssid)) {
                    if (snap.netId != -1) platform.disableNetworkInRam(snap.netId);
                    platform.disconnect();
                    return;
                }
                authErrorCount = 0;
                assocRetryCount = 0;
                handshakeSeen = false;
                handshakeDropCount = 0;
                isFastConnecting = false;
                platform.cancelScheduled(TAG_ASSOC_WATCHDOG);
                platform.cancelScheduled(TAG_FAST_FALLBACK);
                if (!ssid.isEmpty()) {
                    authFailedLower.remove(ssid.toLowerCase(Locale.US));
                    if (ssid.equalsIgnoreCase(lastErrorSsid)) {
                        lastErrorSsid = "";
                        lastErrorReason = "";
                    }
                    startDhcpLeasePoll(ssid);
                }
                break;
            }
            case DISCONNECTED:
                if ("connected".equals(phase)) {
                    String lostSsid = connectedSsid;
                    platform.log("Home Wi-Fi link lost; tearing down routes and searching");
                    recordFlight("LINK_LOST",
                            "ssid=\"" + lostSsid + "\" via=supplicant",
                            "target=" + redactSsid(lostSsid) + " via=supplicant",
                            true);
                    tearDownRoutesAndAdb("link_lost");
                    connectedSsid = "";
                    connectedIp = "";
                    connectedGateway = "";
                    store.writePublicStatus("searching", 0, 0);
                    startScanLoop();
                    platform.startScan();
                    updateScanningStatus();
                    return;
                }
                if (isConnectingToTarget && handshakeSeen) {
                    handshakeSeen = false;
                    handshakeDropCount++;
                    if (WifiSecurity.shouldTriggerAuthFallback(authErrorCount, handshakeDropCount, 0, MAX_ASSOC_RETRIES)) {
                        handleAuthenticationRejected();
                        return;
                    }
                }
                if (isConnectingToTarget && authErrorCount == 0) {
                    long elapsed = platform.elapsedRealtimeMs() - connectStartMs;
                    if (elapsed >= 1500L && assocRetryCount < MAX_ASSOC_RETRIES) {
                        scheduleAssocWatchdog(650L);
                    }
                }
                break;
            default:
                break;
        }
    }

    private void handleAuthenticationRejected() {
        String failed = targetSsid == null ? "" : targetSsid;
        platform.cancelScheduled(TAG_ASSOC_WATCHDOG);
        platform.cancelScheduled(TAG_FAST_FALLBACK);
        platform.cancelScheduled(TAG_ASSOC_SETTLE);
        isConnectingToTarget = false;
        isFastConnecting = false;
        handshakeSeen = false;

        if (!failed.isEmpty()) {
            String low = failed.toLowerCase(Locale.US);
            authFailedLower.add(low);
            pausedAutoLower.add(low);
            if (pinnedSsid != null && pinnedSsid.equalsIgnoreCase(failed)) {
                pinnedSsid = null;
            }
        }
        if (targetNetId != -1) {
            platform.disableNetworkInRam(targetNetId);
            platform.disconnect();
        }
        lastErrorSsid = failed;
        lastErrorReason = "The Wi-Fi password for \"" + failed + "\" was rejected. Tap \"Password\" on that network below to update it.";
        platform.log("WPA authentication rejected for Home Wi-Fi profile");
        recordFlight("AUTH_REJECTED",
                "ssid=\"" + failed + "\" authErrs=" + authErrorCount + " hsDrops=" + handshakeDropCount,
                "target=" + redactSsid(failed) + " authErrs=" + authErrorCount + " hsDrops=" + handshakeDropCount,
                true);

        startScanLoop();
        platform.startScan();

        String fallback = chooseBestScannedCandidate();
        if (fallback != null && !fallback.equalsIgnoreCase(failed)) {
            connectToProfile(fallback, false);
        } else {
            updateScanningStatus();
        }
    }

    @Override
    public synchronized void onNetworkConnected() {
        if (!sessionActive || "connected".equals(phase)) return;
        WifiPlatform.ConnectionSnapshot snap = platform.getConnectionSnapshot();
        if (!WifiSecurity.isPlaceholderSsid(snap.ssid) && isAllowedForHomeWifi(snap.ssid)) {
            startDhcpLeasePoll(snap.ssid);
        }
    }

    private void startDhcpLeasePoll(final String ssid) {
        if (!sessionActive) return;
        stopScanLoop();
        platform.cancelScheduled(TAG_ASSOC_WATCHDOG);
        platform.cancelScheduled(TAG_FAST_FALLBACK);
        isConnectingToTarget = false;
        isFastConnecting = false;
        if (isPollingDhcp && ssid.equalsIgnoreCase(targetSsid)) return;
        targetSsid = ssid;
        isPollingDhcp = true;
        dhcpAttempt = 0;
        phase = "dhcp";
        headline = "Connecting to " + ssid + "…";
        detail = "Obtaining IP address via DHCP…";
        store.writePublicStatus("searching", 0, 0);
        runDhcpPollStep(ssid);
    }

    private void runDhcpPollStep(final String ssid) {
        if (!sessionActive || !isPollingDhcp) return;
        WifiPlatform.ConnectionSnapshot snap = platform.getConnectionSnapshot();
        boolean supDone = snap.supState == WifiPlatform.SupState.COMPLETED;
        boolean rightNet = WifiSecurity.isPlaceholderSsid(snap.ssid)
                ? supDone
                : (supDone && snap.ssid.equalsIgnoreCase(ssid));

        // Guard against stale KitKat DhcpInfo from an earlier session during the first ~3s
        boolean freshIp = (snap.dhcpIp != 0)
                && ((snap.wifiInfoIp != 0 && snap.wifiInfoIp == snap.dhcpIp)
                    || (dhcpAttempt >= 20 && snap.wifiInfoIp != 0)
                    || dhcpAttempt >= 25);

        if (rightNet && freshIp) {
            int chosenIpInt = snap.dhcpIp != 0 ? snap.dhcpIp : snap.wifiInfoIp;
            String myIp = WifiSecurity.intToIp(chosenIpInt);
            String gw = snap.dhcpGateway != 0 ? WifiSecurity.intToIp(snap.dhcpGateway) : "";
            String d1 = snap.dhcpDns1 != 0 ? WifiSecurity.intToIp(snap.dhcpDns1) : "";
            String d2 = snap.dhcpDns2 != 0 ? WifiSecurity.intToIp(snap.dhcpDns2) : "";

            if (platform.routeUp(myIp, gw, d1, d2)) {
                isPollingDhcp = false;
                connectedSsid = ssid;
                connectedIp = myIp;
                connectedGateway = gw;
                connectedRssi = snap.rssiDbm;
                connectedFreq = resolveFrequency(ssid, snap.freqMhz);
                authFailedLower.remove(ssid.toLowerCase(Locale.US));
                pausedAutoLower.remove(ssid.toLowerCase(Locale.US));
                if (ssid.equalsIgnoreCase(lastErrorSsid)) {
                    lastErrorSsid = "";
                    lastErrorReason = "";
                }
                store.recordConnectedNow(ssid, platform.wallEpochSec());
                store.saveLease(gw, d1, d2, myIp);
                phase = "connected";
                if (!lastVehicle.isPowerOn() && evaluateOffLong(lastVehicle, platform.elapsedRealtimeMs())) {
                    long nowEl = platform.elapsedRealtimeMs();
                    String curBoot = platform.bootId();
                    lastKeepAwakeVerdict = keepAwake.evaluate(
                            lastVehicle,
                            nowEl,
                            curBoot,
                            store.getKeepAwakeUnplugged(),
                            store.getKeepAwakePlugged(),
                            store.readPeerOpenLinkLease(),
                            true,
                            true);
                    store.writeKeepAwakeLease(curBoot, lastKeepAwakeVerdict, lastVehicle, nowEl);
                    platform.setSessionLocks(true, lastKeepAwakeVerdict.holdPower);
                }
                syncAdbOverWifiState("wifi_connected", false);
                headline = "Connected to " + ssid;
                detail = buildConnectedDetail();
                store.writePublicStatus("up", connectedRssi, connectedFreq);
                String upTail = " rssi=" + connectedRssi + " freq=" + connectedFreq
                        + " adb=" + adbTcpActive
                        + " holdPower=" + lastKeepAwakeVerdict.holdPower
                        + " remSec=" + lastKeepAwakeVerdict.remainingSec;
                recordFlight("DHCP_UP",
                        "ssid=\"" + ssid + "\" ip=" + myIp + " gw=" + gw + upTail,
                        "target=" + redactSsid(ssid) + " ip=" + HomeWifiFlightLog.redactIp(myIp)
                                + " gw=" + HomeWifiFlightLog.redactIp(gw) + upTail,
                        true);
                platform.log("Home Wi-Fi connected (" + myIp + ", " + connectedRssi + " dBm, "
                        + connectedFreq + " MHz); split routing active on table 95");
                return;
            }
        }

        dhcpAttempt++;
        if (dhcpAttempt >= DHCP_MAX_ATTEMPTS) {
            isPollingDhcp = false;
            phase = "dhcp_timeout";
            headline = "IP address timed out on " + ssid;
            detail = "Connected to Wi-Fi, but the router did not assign a DHCP lease. Reconnecting…";
            lastErrorSsid = ssid;
            lastErrorReason = "DHCP timed out on \"" + ssid + "\". Retrying…";
            platform.log("DHCP timeout on Home Wi-Fi; triggering reassociate");
            recordFlight("DHCP_TIMEOUT",
                    "ssid=\"" + ssid + "\"",
                    "target=" + redactSsid(ssid),
                    true);
            platform.disconnect();
            platform.reassociate();
            startScanLoop();
            platform.startScan();
            return;
        }

        platform.scheduleDelayed(TAG_DHCP_POLL, DHCP_POLL_MS, new Runnable() {
            @Override
            public void run() {
                synchronized (HomeWifiController.this) {
                    runDhcpPollStep(ssid);
                }
            }
        });
    }

    private int resolveFrequency(String ssid, int snapFreq) {
        if (snapFreq > 0) return snapFreq;
        HomeWifiStore.SeenNetwork sr = findScan(ssid);
        return sr == null ? 0 : sr.freqMhz;
    }

    private void maintainActiveSession() {
        if (!"disabling_ap".equals(phase) && !"enabling_wifi".equals(phase)) {
            if (platform.isSoftApActiveOrEnabling()) {
                reclaimFromSoftApHijack("health_ap_on");
                return;
            }
            if (platform.getWifiState() == WifiPlatform.WIFI_STATE_DISABLED) {
                reclaimFromSoftApHijack("health_wifi_off");
                return;
            }
        }
        if ("connected".equals(phase)) {
            WifiPlatform.ConnectionSnapshot snap = platform.getConnectionSnapshot();
            if (snap.supState != WifiPlatform.SupState.COMPLETED
                    || (!WifiSecurity.isPlaceholderSsid(snap.ssid) && !snap.ssid.equalsIgnoreCase(connectedSsid))) {
                String lostSsid = connectedSsid;
                platform.log("Home Wi-Fi connection dropped; returning to scan");
                recordFlight("LINK_LOST",
                        "ssid=\"" + lostSsid + "\" via=health sup=" + snap.supState,
                        "target=" + redactSsid(lostSsid) + " via=health sup=" + snap.supState,
                        true);
                tearDownRoutesAndAdb("link_lost");
                connectedSsid = "";
                connectedIp = "";
                connectedGateway = "";
                phase = "scanning";
                store.writePublicStatus("searching", 0, 0);
                startScanLoop();
                platform.startScan();
                updateScanningStatus();
                return;
            }
            int chosenIpInt = snap.dhcpIp != 0 ? snap.dhcpIp : snap.wifiInfoIp;
            if (chosenIpInt != 0) {
                connectedIp = WifiSecurity.intToIp(chosenIpInt);
            }
            String gw = snap.dhcpGateway != 0 ? WifiSecurity.intToIp(snap.dhcpGateway) : connectedGateway;
            String d1 = snap.dhcpDns1 != 0 ? WifiSecurity.intToIp(snap.dhcpDns1) : "";
            String d2 = snap.dhcpDns2 != 0 ? WifiSecurity.intToIp(snap.dhcpDns2) : "";
            // Re-assert split routing so ConnectivityService / DHCP renewals never keep default route in main
            platform.routeUp(connectedIp, gw, d1, d2);
            store.saveLease(gw, d1, d2, connectedIp);
            syncAdbOverWifiState("health", false);
            connectedRssi = snap.rssiDbm;
            connectedFreq = resolveFrequency(connectedSsid, snap.freqMhz);
            detail = buildConnectedDetail();
            store.writePublicStatus("up", connectedRssi, connectedFreq);
        }
    }

    private String buildConnectedDetail() {
        StringBuilder sb = new StringBuilder();
        if (!connectedIp.isEmpty() && !"0.0.0.0".equals(connectedIp)) {
            sb.append(connectedIp);
        }
        if (connectedRssi != 0) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(connectedRssi).append(" dBm");
        }
        String band = WifiSecurity.bandLabel(connectedFreq);
        if (!band.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append(band);
        }
        if (adbTcpActive && !connectedIp.isEmpty()) {
            if (sb.length() > 0) sb.append(" • ");
            sb.append("ADB: ").append(connectedIp).append(':').append(HomeWifiRouter.ADB_PORT);
        }
        if (manualActive) {
            if (sb.length() > 0) sb.append(" • ");
            int remSec = manualRemainingSeconds();
            if (remSec < 0) {
                sb.append("Active until shifted out of Park");
            } else {
                int mins = Math.max(1, (remSec + 59) / 60);
                sb.append("Active for ").append(mins).append(" min (or until out of Park)");
            }
        } else if (!lastVehicle.isPowerOn() && lastKeepAwakeVerdict.holdPower) {
            if (sb.length() > 0) sb.append(" • ");
            if (lastKeepAwakeVerdict.unlimited) {
                sb.append("Keeping awake while plugged in");
            } else {
                int mins = Math.max(1, (lastKeepAwakeVerdict.remainingSec + 59) / 60);
                sb.append("Keeping awake: ").append(mins).append(" min left");
            }
        }
        return sb.toString();
    }

    private int manualRemainingSeconds() {
        if (!manualActive) return 0;
        if (manualDeadlineMs <= 0L) return -1;
        long remMs = manualDeadlineMs - platform.elapsedRealtimeMs();
        return (int) Math.max(0L, remMs / 1000L);
    }

    private void cancelAllTimers() {
        platform.cancelScheduled(TAG_AP_FALLBACK);
        platform.cancelScheduled(TAG_WIFI_ENABLE);
        platform.cancelScheduled(TAG_SCAN);
        platform.cancelScheduled(TAG_FAST_FALLBACK);
        platform.cancelScheduled(TAG_ASSOC_WATCHDOG);
        platform.cancelScheduled(TAG_ASSOC_SETTLE);
        platform.cancelScheduled(TAG_DHCP_POLL);
    }

    // ---- Command handling (@hmgapi-wifi & CLI) ----

    public synchronized CmdResult handleCommand(List<String> args) {
        if (args == null || args.isEmpty()) {
            return new CmdResult(2, "usage: wifi status | scan [minutes] | saved | seen | join <ssid> [key] | connect <ssid> [key] | use <ssid> on|off | adb on|off | keepawake <unplugged> <plugged> | forget <ssid> | now [minutes] | back | log | log-usb | log-clear\n");
        }
        String verb = args.get(0);
        if ("status".equals(verb)) {
            return new CmdResult(0, statusJson() + "\n");
        }
        if ("log".equals(verb)) {
            String tail = store.flightLog().readOwnerTail(48 * 1024);
            if (tail.trim().isEmpty()) {
                return new CmdResult(0, "(No parked Home Wi-Fi session events recorded yet.)\n");
            }
            return new CmdResult(0, tail.endsWith("\n") ? tail : (tail + "\n"));
        }
        if ("log-usb".equals(verb)) {
            try {
                List<String> saved = store.flightLog().savePublicToUsb(platform.wallEpochSec());
                if (saved.isEmpty()) {
                    return new CmdResult(1, "No USB stick detected. Insert a USB drive and try again.\n");
                }
                return new CmdResult(0, "Saved scrubbed session log to " + saved.get(0) + "\n");
            } catch (IOException e) {
                return new CmdResult(1, "Could not save session log to USB: " + e.getMessage() + "\n");
            }
        }
        if ("log-clear".equals(verb)) {
            store.flightLog().clear();
            if (sessionActive && !lastVehicle.isPowerOn()) {
                store.flightLog().markSessionActive(platform.bootId(), platform.wallEpochSec(),
                        platform.elapsedRealtimeMs(), phase, lastVehicle.power, "LOG_CLEARED");
            }
            return new CmdResult(0, "Cleared Home Wi-Fi session log.\n");
        }
        if ("saved".equals(verb)) {
            StringBuilder sb = new StringBuilder();
            for (HomeWifiStore.Profile p : store.listProfiles()) {
                sb.append(p.ssid).append('\n');
            }
            return new CmdResult(0, sb.toString());
        }
        if ("seen".equals(verb)) {
            List<HomeWifiStore.SeenNetwork> list = !liveScans.isEmpty() ? liveScans : store.loadSeenNetworks();
            StringBuilder sb = new StringBuilder();
            for (HomeWifiStore.SeenNetwork s : list) {
                sb.append(s.freqMhz).append('\t').append(s.rssiDbm).append('\t')
                  .append(s.capabilities).append('\t').append(s.ssid).append('\n');
            }
            return new CmdResult(0, sb.toString());
        }
        if ("now".equals(verb) || "scan".equals(verb)) {
            String minArg = args.size() >= 2 ? args.get(1) : "0";
            return cmdActivateOrScan(minArg, "scan".equals(verb));
        }
        if ("back".equals(verb)) {
            manualActive = false;
            manualStartedWhilePowerOn = false;
            manualDeadlineMs = 0L;
            pinnedSsid = null;
            WifiPlatform.VehicleSnapshot veh = platform.readVehicle();
            lastVehicle = veh;
            if (sessionActive && veh.isPowerOn()) {
                exitClientSession("Manual disconnect", true);
            }
            return new CmdResult(0, "Disconnected Home Wi-Fi and restored wireless CarPlay / Android Auto.\n");
        }
        if ("join".equals(verb) && args.size() >= 2) {
            String ssid = args.get(1);
            boolean hasPsk = args.size() >= 3;
            String psk = hasPsk ? args.get(2) : "";
            return cmdJoinOrConnect(ssid, psk, hasPsk, false);
        }
        if ("connect".equals(verb) && args.size() >= 2) {
            String ssid = args.get(1);
            boolean hasPsk = args.size() >= 3;
            String psk = hasPsk ? args.get(2) : null;
            return cmdJoinOrConnect(ssid, psk, hasPsk, true);
        }
        if ("use".equals(verb) && args.size() == 3) {
            boolean use = "on".equals(args.get(2));
            return cmdSetUseForHomeWifi(args.get(1), use);
        }
        if ("adb".equals(verb) && args.size() == 2) {
            if (!"on".equals(args.get(1)) && !"off".equals(args.get(1))) {
                return new CmdResult(2, "usage: wifi adb on|off\n");
            }
            return cmdSetAdbOverWifi("on".equals(args.get(1)));
        }
        if ("keepawake".equals(verb) && args.size() == 3) {
            return cmdSetKeepAwake(args.get(1), args.get(2));
        }
        if ("forget".equals(verb) && args.size() == 2) {
            return cmdForget(args.get(1));
        }
        if ("down".equals(verb)) {
            shutdown("Home Wi-Fi disabled");
            return new CmdResult(0, "off\n");
        }
        return new CmdResult(2, "Unknown Home Wi-Fi command: " + verb + "\n");
    }

    private CmdResult cmdSetAdbOverWifi(boolean enable) {
        try {
            store.setAdbOverWifi(enable);
            syncAdbOverWifiState(enable ? "toggle_on" : "toggle_off", !enable);
            if (!enable && !adbTcpActive) {
                platform.setAdbOverWifi(false, "", true);
            }
            if (sessionActive && "connected".equals(phase)) {
                detail = buildConnectedDetail();
            }
            if (enable) {
                if (adbTcpActive && !connectedIp.isEmpty()) {
                    return new CmdResult(0, "ADB over TCP active on " + connectedIp + ":" + HomeWifiRouter.ADB_PORT
                            + " (closes automatically when Home Wi-Fi disconnects).\n");
                }
                return new CmdResult(0, "ADB over TCP will turn on automatically (port " + HomeWifiRouter.ADB_PORT
                        + ") whenever Home Wi-Fi connects.\n");
            }
            return new CmdResult(0, "Disabled ADB over TCP on Home Wi-Fi.\n");
        } catch (IOException e) {
            return new CmdResult(1, "Could not save ADB over Wi-Fi setting: " + e.getMessage() + "\n");
        }
    }

    private CmdResult cmdSetKeepAwake(String unpluggedArg, String pluggedArg) {
        if (!HomeWifiKeepAwake.isValidUnpluggedCode(unpluggedArg)
                || !HomeWifiKeepAwake.isValidPluggedCode(pluggedArg)) {
            return new CmdResult(2, "Invalid keep-awake duration.\n");
        }
        try {
            store.setKeepAwakePolicy(unpluggedArg, pluggedArg);
            tickVehicleAndHealth();
            return new CmdResult(0, "Updated Home Wi-Fi keep-awake ("
                    + HomeWifiKeepAwake.labelForCode(store.getKeepAwakeUnplugged()) + " unplugged / "
                    + HomeWifiKeepAwake.labelForCode(store.getKeepAwakePlugged()) + " plugged in).\n");
        } catch (IOException e) {
            return new CmdResult(1, "Could not save keep-awake settings: " + e.getMessage() + "\n");
        }
    }

    private CmdResult cmdActivateOrScan(String minArg, boolean explicitScan) {
        int mins = parseMinutes(minArg);
        if (mins < 0 || mins > 240) {
            return new CmdResult(2, "Enter 1 to 240 minutes, or 0 for no time limit while in Park.\n");
        }
        if (sessionActive && explicitScan) {
            if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                scanCount++;
                platform.startScan();
            }
            if (!"connected".equals(phase)) {
                startScanLoop();
            }
            return new CmdResult(0, "Scanning for nearby Wi-Fi networks…\n");
        }

        WifiPlatform.VehicleSnapshot veh = platform.readVehicle();
        lastVehicle = veh;
        if (veh.isPowerOn() && !veh.gearPark) {
            return new CmdResult(1, "Shift to Park (P) first. Home Wi-Fi pauses wireless CarPlay and Android Auto while scanning or connected.\n");
        }

        manualActive = true;
        manualStartedWhilePowerOn = veh.isPowerOn();
        manualDeadlineMs = mins == 0 ? 0L : (platform.elapsedRealtimeMs() + mins * 60000L);
        pausedAutoLower.clear();

        if (!sessionActive) {
            enterClientSession(explicitScan ? "Live Wi-Fi scan in Park" : "Connect now in Park");
        } else if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
            platform.startScan();
            if (!"connected".equals(phase) && !isConnectingToTarget) {
                String cand = chooseBestScannedCandidate();
                if (cand != null) connectToProfile(cand, false);
                else startScanLoop();
            }
        }
        scheduleVehicleTick();

        if (mins == 0) {
            return new CmdResult(0, "Home Wi-Fi active until you shift out of Park (wireless CarPlay and Android Auto are paused meanwhile).\n");
        }
        return new CmdResult(0, "Home Wi-Fi active for up to " + mins + " minutes (or until you shift out of Park).\n");
    }

    private CmdResult cmdJoinOrConnect(String rawSsid, String psk, boolean hasExplicitPsk, boolean connectImmediately) {
        String clean = WifiSecurity.cleanSsid(rawSsid);
        HomeWifiStore.SeenNetwork sr = findScan(clean);
        String caps = sr != null ? sr.capabilities : null;
        try {
            HomeWifiStore.Profile p = store.upsertOwnerProfile(clean, psk, hasExplicitPsk, caps, true);
            String low = clean.toLowerCase(Locale.US);
            authFailedLower.remove(low);
            pausedAutoLower.remove(low);
            if (clean.equalsIgnoreCase(lastErrorSsid)) {
                lastErrorSsid = "";
                lastErrorReason = "";
            }

            if (!connectImmediately) {
                if (sessionActive && platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                    if (p.hasExplicitCredentials()) {
                        platform.upsertWifiNetwork(p.ssid, p.psk, p.open, p.capabilities, hasExplicitPsk);
                    }
                    if (!"connected".equals(phase) && !isConnectingToTarget) {
                        connectToProfile(clean, false, hasExplicitPsk);
                    }
                }
                return new CmdResult(0, "Saved \"" + clean + "\" for Home Wi-Fi.\n");
            }

            // Explicit Connect request!
            pinnedSsid = clean;
            if (sessionActive) {
                if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                    if ("connected".equals(phase) && !clean.equalsIgnoreCase(connectedSsid)) {
                        tearDownRoutesAndAdb("switch_ssid");
                        connectedSsid = "";
                        connectedIp = "";
                    }
                    connectToProfile(clean, false, hasExplicitPsk);
                }
                return new CmdResult(0, "Connecting to \"" + clean + "\"…\n");
            }

            WifiPlatform.VehicleSnapshot veh = platform.readVehicle();
            lastVehicle = veh;
            if (!veh.isPowerOn() || veh.gearPark) {
                if (veh.isPowerOn()) {
                    manualActive = true;
                    manualDeadlineMs = 0L;
                }
                enterClientSession("Connect to selected network");
                scheduleVehicleTick();
                return new CmdResult(0, "Connecting to \"" + clean + "\"…\n");
            }
            return new CmdResult(0, "Saved \"" + clean + "\". Shift to Park to connect right now, or it will connect automatically after you turn off the car.\n");
        } catch (IllegalArgumentException e) {
            return new CmdResult(2, e.getMessage() + "\n");
        } catch (IOException e) {
            return new CmdResult(1, "Could not save Wi-Fi network: " + e.getMessage() + "\n");
        }
    }

    private CmdResult cmdSetUseForHomeWifi(String rawSsid, boolean use) {
        String clean = WifiSecurity.cleanSsid(rawSsid);
        try {
            if (!store.setUseForHomeWifi(clean, use)) {
                return new CmdResult(2, "Invalid network name.\n");
            }
            if (!use) {
                if (pinnedSsid != null && pinnedSsid.equalsIgnoreCase(clean)) pinnedSsid = null;
                if (sessionActive && platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                    WifiPlatform.ConfiguredNet net = findConfiguredNet(clean);
                    if (net != null) platform.disableNetworkInRam(net.netId);
                    boolean wasActiveTarget = clean.equalsIgnoreCase(connectedSsid) || clean.equalsIgnoreCase(targetSsid);
                    if (wasActiveTarget) {
                        tearDownRoutesAndAdb("ssid_excluded");
                        platform.disconnect();
                        connectedSsid = "";
                        connectedIp = "";
                        isConnectingToTarget = false;
                        isPollingDhcp = false;
                        cancelAllTimers();
                        startScanLoop();
                        platform.startScan();
                        String next = chooseBestScannedCandidate();
                        if (next != null) connectToProfile(next, false);
                        else updateScanningStatus();
                    }
                }
                return new CmdResult(0, "\"" + clean + "\" will be ignored by Home Wi-Fi (kept saved for dashcam / other apps).\n");
            } else {
                pausedAutoLower.remove(clean.toLowerCase(Locale.US));
                if (sessionActive && platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                    syncProfilesAndEnforceExclusions();
                    if (!"connected".equals(phase) && !isConnectingToTarget) {
                        String next = chooseBestScannedCandidate();
                        if (next != null) connectToProfile(next, false);
                    }
                }
                return new CmdResult(0, "\"" + clean + "\" is now enabled for Home Wi-Fi.\n");
            }
        } catch (IOException e) {
            return new CmdResult(1, "Could not update network setting: " + e.getMessage() + "\n");
        }
    }

    private CmdResult cmdForget(String rawSsid) {
        String clean = WifiSecurity.cleanSsid(rawSsid);
        try {
            store.removeProfile(clean);
            String low = clean.toLowerCase(Locale.US);
            authFailedLower.remove(low);
            pausedAutoLower.remove(low);
            if (pinnedSsid != null && pinnedSsid.equalsIgnoreCase(clean)) pinnedSsid = null;
            if (clean.equalsIgnoreCase(lastErrorSsid)) {
                lastErrorSsid = "";
                lastErrorReason = "";
            }
            if (platform.getWifiState() == WifiPlatform.WIFI_STATE_ENABLED) {
                WifiPlatform.ConfiguredNet net = findConfiguredNet(clean);
                if (net != null) {
                    platform.removeWifiNetwork(net.netId);
                }
            }
            if (sessionActive && (clean.equalsIgnoreCase(connectedSsid) || clean.equalsIgnoreCase(targetSsid))) {
                tearDownRoutesAndAdb("ssid_forgot");
                platform.disconnect();
                connectedSsid = "";
                connectedIp = "";
                isConnectingToTarget = false;
                isPollingDhcp = false;
                cancelAllTimers();
                startScanLoop();
                platform.startScan();
                String next = chooseBestScannedCandidate();
                if (next != null) connectToProfile(next, false);
                else updateScanningStatus();
            }
            return new CmdResult(0, "Removed \"" + clean + "\".\n");
        } catch (IOException e) {
            return new CmdResult(1, "Could not remove network: " + e.getMessage() + "\n");
        }
    }

    private static int parseMinutes(String raw) {
        if (raw == null || raw.trim().isEmpty()) return 0;
        String s = raw.trim();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        try {
            return Integer.parseInt(s, 10);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public synchronized String statusJson() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("phase", phase);
        root.put("headline", headline);
        root.put("detail", detail);
        root.put("active", Boolean.valueOf(sessionActive));
        root.put("manual", Boolean.valueOf(manualActive));
        root.put("manualRemainingSec", Integer.valueOf(manualRemainingSeconds()));
        root.put("inPark", Boolean.valueOf(lastVehicle.gearPark));
        root.put("powerOn", Boolean.valueOf(lastVehicle.isPowerOn()));
        root.put("pluggedIn", Boolean.valueOf(lastVehicle.pluggedIn));
        root.put("charging", Boolean.valueOf(lastVehicle.charging));
        root.put("bat12vSoc", Integer.valueOf(lastVehicle.bat12vSoc));
        root.put("keepAwakeUnplugged", store.getKeepAwakeUnplugged());
        root.put("keepAwakePlugged", store.getKeepAwakePlugged());
        root.put("keepAwakeActive", Boolean.valueOf(lastKeepAwakeVerdict.holdPower));
        root.put("keepAwakeUnlimited", Boolean.valueOf(lastKeepAwakeVerdict.unlimited));
        root.put("keepAwakeRemainingSec", Integer.valueOf(lastKeepAwakeVerdict.remainingSec));
        root.put("keepAwakeExtendedByOpenLink", Boolean.valueOf(lastKeepAwakeVerdict.extendedByPeer));
        root.put("keepAwakeAwayFromHome", Boolean.valueOf(lastKeepAwakeVerdict.awayFromHome));
        root.put("keepAwakeCutBy12v", Boolean.valueOf(lastKeepAwakeVerdict.cutBy12v));
        root.put("adbOverWifi", Boolean.valueOf(store.isAdbOverWifi()));
        root.put("adbActive", Boolean.valueOf(adbTcpActive));
        root.put("adbEndpoint", adbTcpActive && !connectedIp.isEmpty()
                ? (connectedIp + ":" + HomeWifiRouter.ADB_PORT) : "");
        root.put("connectedSsid", connectedSsid);
        root.put("targetSsid", targetSsid == null ? "" : targetSsid);
        root.put("ip", connectedIp);
        root.put("gateway", connectedGateway);
        root.put("rssi", Integer.valueOf(connectedRssi));
        root.put("freq", Integer.valueOf(connectedFreq));
        root.put("scanCount", Integer.valueOf(scanCount));
        root.put("errorSsid", lastErrorSsid);
        root.put("errorReason", lastErrorReason);

        List<HomeWifiStore.SeenNetwork> scans = !liveScans.isEmpty() ? liveScans : store.loadSeenNetworks();
        Map<String, HomeWifiStore.SeenNetwork> scanByLower = new HashMap<String, HomeWifiStore.SeenNetwork>();
        for (HomeWifiStore.SeenNetwork s : scans) {
            scanByLower.put(s.ssid.toLowerCase(Locale.US), s);
        }

        List<Object> savedArr = new ArrayList<Object>();
        Map<String, HomeWifiStore.Profile> profByLower = new HashMap<String, HomeWifiStore.Profile>();
        for (HomeWifiStore.Profile p : store.listProfiles()) {
            String low = p.ssid.toLowerCase(Locale.US);
            profByLower.put(low, p);
            HomeWifiStore.SeenNetwork sr = scanByLower.get(low);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ssid", p.ssid);
            m.put("open", Boolean.valueOf(p.open));
            m.put("useForHomeWifi", Boolean.valueOf(p.useForHomeWifi));
            m.put("dashcam", Boolean.valueOf(p.dashcamDetected));
            m.put("hasExplicitKey", Boolean.valueOf(p.hasExplicitCredentials()));
            boolean isConn = sessionActive && "connected".equals(phase) && p.ssid.equalsIgnoreCase(connectedSsid);
            boolean isTrying = sessionActive && !isConn
                    && (isConnectingToTarget || isPollingDhcp)
                    && targetSsid != null && p.ssid.equalsIgnoreCase(targetSsid);
            m.put("connected", Boolean.valueOf(isConn));
            m.put("connecting", Boolean.valueOf(isTrying));
            m.put("authFailed", Boolean.valueOf(authFailedLower.contains(low)));
            m.put("inRange", Boolean.valueOf(sr != null));
            m.put("rssi", Integer.valueOf(isConn ? connectedRssi : (sr != null ? sr.rssiDbm : 0)));
            m.put("freq", Integer.valueOf(isConn ? connectedFreq : (sr != null ? sr.freqMhz : 0)));
            m.put("security", WifiSecurity.securityLabel(sr != null ? sr.capabilities : p.capabilities));
            savedArr.add(m);
        }
        root.put("saved", savedArr);

        List<Object> nearbyArr = new ArrayList<Object>();
        for (HomeWifiStore.SeenNetwork s : scans) {
            String low = s.ssid.toLowerCase(Locale.US);
            HomeWifiStore.Profile p = profByLower.get(low);
            int secMode = WifiSecurity.classifySecurityMode(s.capabilities);
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ssid", s.ssid);
            m.put("rssi", Integer.valueOf(s.rssiDbm));
            m.put("freq", Integer.valueOf(s.freqMhz));
            m.put("band", WifiSecurity.bandLabel(s.freqMhz));
            m.put("security", WifiSecurity.securityLabel(s.capabilities));
            m.put("open", Boolean.valueOf(secMode == WifiSecurity.SEC_OPEN));
            m.put("supported", Boolean.valueOf(secMode != WifiSecurity.SEC_UNSUPPORTED));
            m.put("saved", Boolean.valueOf(p != null));
            m.put("useForHomeWifi", Boolean.valueOf(p == null ? !WifiSecurity.isLikelyDashcamSsid(s.ssid) : p.useForHomeWifi));
            m.put("connected", Boolean.valueOf(sessionActive && "connected".equals(phase) && s.ssid.equalsIgnoreCase(connectedSsid)));
            m.put("dashcam", Boolean.valueOf(WifiSecurity.isLikelyDashcamSsid(s.ssid)));
            nearbyArr.add(m);
        }
        root.put("nearby", nearbyArr);

        return Json.write(root);
    }
}

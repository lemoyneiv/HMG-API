package hmgapi.wifi;

import hmgapi.engine.Json;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic off-car unit and state-machine test suite for HomeWifiController,
 * HomeWifiStore, and WifiSecurity (runs in --fast host mode without Android SDK).
 */
public final class HomeWifiControllerTest {
    private static int checks = 0;
    private static int fails = 0;

    private static void check(String label, Object want, Object got) {
        checks++;
        boolean eq = (want == null) ? (got == null) : want.equals(got);
        if (eq) {
            System.out.println("ok    " + label);
        } else {
            System.out.println("FAIL  " + label + ": wanted [" + want + "], got [" + got + "]");
            fails++;
        }
    }

    private static void checkTrue(String label, boolean cond) {
        check(label, Boolean.TRUE, Boolean.valueOf(cond));
    }

    public static void main(String[] args) throws Exception {
        testSecurityAndDashcamClassification();
        testLegacyWpaSupplicantImport();
        testZeroSavedNetworksInteractiveScanAndLiveConnect();
        testDashcamAutoExcludeAndManualUseToggle();
        testBcmdhdAssociationWatchdogRecovery();
        testWrongPasswordDetectionAndLiveFix();
        testAutoParked30sTimerShiftOutAndRebootRestore();
        testParkedKeepAwakeUnpluggedAndPluggedIn();
        testKeepAwake12vGuardianAndPeerOpenLink();
        testAwayFromHomeAutoSleepAndOpenLinkPrecedence();
        testFlightLogPowerCutAndScrubbing();
        testAdmPreLatchDoorOpenAndSoftApHijackReclaim();
        testAdbOverWifiLifecycleAndScrubbing();
        testLockedCabinIccuPlugLatchAndNoZombieReentry();
        testSlowSoftApDisablingAndRapManualScanHandoff();
        testSilentDelayModeBackgroundWakeFastRelease();
        testDoorWakeSquashedProjectionSoftApRestoredOnIgOn();
        testDriverEntryGraceProjectionPreStartAndBootSoftApSelfHeal();
        testRouterWithFakeToolsIfPresent();

        if (fails > 0) {
            System.err.println("FAIL  " + fails + " of " + checks + " HomeWifiController checks failed");
            System.exit(1);
        }
        System.out.println("all " + checks + " HomeWifiController checks passed");
    }

    private static void testSecurityAndDashcamClassification() {
        check("WPA2-CCMP classified cleanly",
                Integer.valueOf(WifiSecurity.SEC_WPA2_CCMP),
                Integer.valueOf(WifiSecurity.classifySecurityMode("[WPA2-PSK-CCMP][ESS]")));
        check("WPA+WPA2 TKIP mixed classified cleanly",
                Integer.valueOf(WifiSecurity.SEC_WPA_MIXED),
                Integer.valueOf(WifiSecurity.classifySecurityMode("[WPA-PSK-CCMP+TKIP][WPA2-PSK-CCMP+TKIP][ESS]")));
        check("Open network classified cleanly",
                Integer.valueOf(WifiSecurity.SEC_OPEN),
                Integer.valueOf(WifiSecurity.classifySecurityMode("[ESS]")));
        check("802.1X Enterprise marked unsupported",
                Integer.valueOf(WifiSecurity.SEC_UNSUPPORTED),
                Integer.valueOf(WifiSecurity.classifySecurityMode("[WPA2-EAP-CCMP][ESS]")));

        checkTrue("FITCAMX SSID detected as dashcam", WifiSecurity.isLikelyDashcamSsid("JsKiaEV6FITCAMX"));
        checkTrue("CAR_CAM SSID detected as dashcam", WifiSecurity.isLikelyDashcamSsid("CAR_CAM_84A1"));
        checkTrue("VIOFO SSID detected as dashcam", WifiSecurity.isLikelyDashcamSsid("VIOFO_A229_5G"));
        checkTrue("Normal home router not flagged as dashcam", !WifiSecurity.isLikelyDashcamSsid("MyHome5G"));

        check("Quotes and backslashes escaped in PSK",
                "\"my\\\"pass\\\\key\"",
                WifiSecurity.formatPreSharedKey("my\"pass\\key"));
        String hex64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        check("64-hex PSK left unquoted", hex64, WifiSecurity.formatPreSharedKey(hex64));
        checkTrue("Short 7-char password rejected", !WifiSecurity.isValidPassword("1234567", WifiSecurity.SEC_WPA2_CCMP));
    }

    private static void testLegacyWpaSupplicantImport() throws Exception {
        File root = Files.createTempDirectory("hw-legacy").toFile();
        try {
            File wpaDir = new File(root, "data/misc/wifi");
            wpaDir.mkdirs();
            File wpaConf = new File(wpaDir, "wpa_supplicant.conf");
            String legacy = "ctrl_interface=/data/misc/wifi/sockets\nupdate_config=1\n\n"
                    + "network={\n\tssid=\"Willows\"\n\tpsk=\"willows-secret\"\n\tkey_mgmt=WPA-PSK\n}\n"
                    + "network={\n\tssid=\"CAR_CAM_9911\"\n\tpsk=\"12345678\"\n\tkey_mgmt=WPA-PSK\n}\n";
            Files.write(wpaConf.toPath(), legacy.getBytes("UTF-8"));

            HomeWifiStore store = new HomeWifiStore(root);
            store.importLegacyWpaSupplicantIfNeeded(wpaConf);

            HomeWifiStore.Profile home = store.findProfile("Willows");
            HomeWifiStore.Profile cam = store.findProfile("CAR_CAM_9911");
            checkTrue("Legacy home network imported as usable", home != null && home.useForHomeWifi);
            check("Legacy home PSK preserved", "willows-secret", home.psk);
            checkTrue("Legacy dashcam network imported with useForHomeWifi=false", cam != null && !cam.useForHomeWifi);
        } finally {
            deleteRec(root);
        }
    }

    @SuppressWarnings("unchecked")
    private static void testZeroSavedNetworksInteractiveScanAndLiveConnect() throws Exception {
        File root = Files.createTempDirectory("hw-live").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, false);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();

            // Out of Park -> refused cleanly
            HomeWifiController.CmdResult rOutP = ctl.handleCommand(Arrays.asList("scan", "30"));
            check("Scan out of Park refused", Integer.valueOf(1), Integer.valueOf(rOutP.code));
            checkTrue("Clear Park message", rOutP.output.contains("Shift to Park"));

            // Shift to Park with ZERO saved networks -> scan succeeds (no Catch-22!)
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true);
            HomeWifiController.CmdResult rScan = ctl.handleCommand(Arrays.asList("scan", "30"));
            check("Scan in Park with 0 saved networks allowed", Integer.valueOf(0), Integer.valueOf(rScan.code));
            checkTrue("SoftAP disabled on scan start", !plat.softApActive);

            // Simulate WIFI_AP_STATE_DISABLED -> WIFI_STATE_ENABLED -> SCAN_RESULTS_AVAILABLE
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            checkTrue("Station hardware requested", plat.wifiState == WifiPlatform.WIFI_STATE_ENABLED);

            plat.scanResults = Arrays.asList(
                    new HomeWifiStore.SeenNetwork("CAR_CAM_001", "aa:11", 2437, -42, "[WPA2-PSK-CCMP][ESS]"),
                    new HomeWifiStore.SeenNetwork("Home5G", "bb:22", 5180, -56, "[WPA2-PSK-CCMP][ESS]"),
                    new HomeWifiStore.SeenNetwork("Home5G", "bb:23", 2412, -68, "[WPA2-PSK-CCMP][ESS]"),
                    new HomeWifiStore.SeenNetwork("GuestOpen", "cc:33", 2462, -64, "[ESS]")
            );
            ctl.onScanResultsAvailable();

            Map<String, Object> st = Json.object(ctl.statusJson());
            check("Phase while scanning with 0 saved profiles", "scanning", st.get("phase"));
            List<Map<String, Object>> nearby = (List<Map<String, Object>>) st.get("nearby");
            check("Deduplicated 4 BSSIDs into 3 SSIDs", Integer.valueOf(3), Integer.valueOf(nearby.size()));
            check("Non-dashcam strongest network sorted first", "Home5G", nearby.get(0).get("ssid"));
            check("Strongest BSSID RSSI kept for Home5G", Long.valueOf(-56), nearby.get(0).get("rssi"));
            check("Dashcam sorted last despite stronger RSSI", "CAR_CAM_001", nearby.get(2).get("ssid"));

            // Tap Connect on Home5G WHILE Home Wi-Fi is active and scanning!
            HomeWifiController.CmdResult rConn = ctl.handleCommand(Arrays.asList("connect", "Home5G", "correct-horse-battery"));
            check("Live connect while active succeeds", Integer.valueOf(0), Integer.valueOf(rConn.code));
            check("Phase moves to connecting", "connecting", Json.object(ctl.statusJson()).get("phase"));
            check("Exact WPA2-CCMP cipher mode configured", "[WPA2-PSK-CCMP][ESS]", plat.lastUpsertCaps);

            // Complete WPA2 handshake + fresh DHCP lease
            int ipInt = ipToInt(192, 168, 1, 42);
            int gwInt = ipToInt(192, 168, 1, 1);
            int dnsInt = ipToInt(1, 1, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, plat.lastEnabledNetId, "Home5G", "bb:22",
                    -55, 5180, ipInt, ipInt, gwInt, ipToInt(255, 255, 255, 0), dnsInt, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            Map<String, Object> connSt = Json.object(ctl.statusJson());
            check("Connected phase reached", "connected", connSt.get("phase"));
            check("Connected SSID reported to owner UI", "Home5G", connSt.get("connectedSsid"));
            check("Connected IP reported to owner UI", "192.168.1.42", connSt.get("ip"));
            checkTrue("Split-tunnel table 95 route installed", plat.routeUpCalls > 0);

            // Public wifi.json contract: state=up, rssi=-55, freq=5180, ZERO SSID/PSK!
            String pubJson = HomeWifiStore.readText(new File(root, "data/hmgapi/public/wifi.json")).trim();
            check("Public wifi.json contract", "{\"state\": \"up\", \"rssi\": -55, \"freq\": 5180}", pubJson);
            checkTrue("Public wifi.json never exposes SSID", !pubJson.contains("Home5G"));

            // Forget while active disconnects cleanly without blocking
            HomeWifiController.CmdResult rForget = ctl.handleCommand(Arrays.asList("forget", "Home5G"));
            check("Forget while active succeeds", Integer.valueOf(0), Integer.valueOf(rForget.code));
            checkTrue("Route torn down on forget", plat.routeDownCalls > 0);
        } finally {
            deleteRec(root);
        }
    }

    private static void testDashcamAutoExcludeAndManualUseToggle() throws Exception {
        File root = Files.createTempDirectory("hw-dashcam").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            // Pre-populate Android WifiManager with a saved FITCAMX dashcam profile (id=7)
            plat.configured.add(new FakeWifiPlatform.NetEntry(7, "CAR_CAM_FITCAMX", false, true));
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true); // Parked!

            HomeWifiController ctl = new HomeWifiController(store, plat);
            // Save a normal home network too
            ctl.handleCommand(Arrays.asList("join", "GarageWiFi", "garage-pass-123"));
            ctl.start();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            checkTrue("Dashcam profile disabled in WifiManager RAM", plat.disabledInRamIds.contains(Integer.valueOf(7)));
            checkTrue("Dashcam profile NOT deleted from Android WifiManager", !plat.removedIds.contains(Integer.valueOf(7)));
            checkTrue("Dashcam auto-marked useForHomeWifi=false", !store.findProfile("CAR_CAM_FITCAMX").useForHomeWifi);

            // Suppose wpa_supplicant briefly associated with CAR_CAM_FITCAMX anyway -> controller disconnects immediately!
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 7, "CAR_CAM_FITCAMX", "aa:bb", -40, 2437, 0, 0, 0, 0, 0, 0);
            int discBefore = plat.disconnectCalls;
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            checkTrue("Excluded dashcam connection immediately disconnected", plat.disconnectCalls > discBefore);
            checkTrue("Excluded dashcam never triggers connected phase", !"connected".equals(Json.object(ctl.statusJson()).get("phase")));

            // Connect to GarageWiFi, then toggle "Use for Home Wi-Fi" OFF on GarageWiFi live
            int ipInt = ipToInt(10, 0, 0, 25);
            int gwInt = ipToInt(10, 0, 0, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, plat.lastEnabledNetId, "GarageWiFi", "cc:dd",
                    -58, 2412, ipInt, ipInt, gwInt, ipToInt(255, 255, 255, 0), gwInt, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            check("GarageWiFi connected", "connected", Json.object(ctl.statusJson()).get("phase"));

            HomeWifiController.CmdResult rOff = ctl.handleCommand(Arrays.asList("use", "GarageWiFi", "off"));
            check("Toggling Use for Home Wi-Fi off succeeds", Integer.valueOf(0), Integer.valueOf(rOff.code));
            checkTrue("GarageWiFi immediately disconnected when excluded", !"connected".equals(Json.object(ctl.statusJson()).get("phase")));
            checkTrue("GarageWiFi profile still retained in store", store.findProfile("GarageWiFi") != null);
        } finally {
            deleteRec(root);
        }
    }

    private static void testBcmdhdAssociationWatchdogRecovery() throws Exception {
        File root = Files.createTempDirectory("hw-watchdog").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.handleCommand(Arrays.asList("join", " PatioMesh", "patio-mesh-password".trim()));
            // Clean exact SSID
            ctl.handleCommand(Arrays.asList("forget", " PatioMesh"));
            ctl.handleCommand(Arrays.asList("join", "PatioMesh", "patio-mesh-password"));

            ctl.start();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            // Simulate Broadcom bcmdhd stuck in ASSOCIATING
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.ASSOCIATING, plat.lastEnabledNetId, "PatioMesh", "11:22",
                    -65, 2412, 0, 0, 0, 0, 0, 0);
            int reconnectBefore = plat.reconnectCalls;
            plat.fireTimer(HomeWifiController.TAG_ASSOC_WATCHDOG);
            checkTrue("Watchdog called disconnect() first", plat.disconnectCalls > 0);
            plat.fireTimer(HomeWifiController.TAG_ASSOC_SETTLE);
            checkTrue("Watchdog re-enabled and reconnected after 400ms settle", plat.reconnectCalls > reconnectBefore);
        } finally {
            deleteRec(root);
        }
    }

    private static void testWrongPasswordDetectionAndLiveFix() throws Exception {
        File root = Files.createTempDirectory("hw-auth").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();

            ctl.handleCommand(Arrays.asList("connect", "HomeFiber", "wrong-password-1"));
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            // Simulate 2x WPA 4-way handshake drops (rejected PSK)
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.FOUR_WAY_HANDSHAKE, false);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.DISCONNECTED, false);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.FOUR_WAY_HANDSHAKE, false);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.DISCONNECTED, false);

            Map<String, Object> st = Json.object(ctl.statusJson());
            check("Wrong password sets auth_failed phase", "auth_failed", st.get("phase"));
            check("Failed SSID surfaced", "HomeFiber", st.get("errorSsid"));

            // User taps Password and enters the right password while still active
            ctl.handleCommand(Arrays.asList("connect", "HomeFiber", "right-password-2"));
            int ipInt = ipToInt(192, 168, 50, 10);
            int gwInt = ipToInt(192, 168, 50, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, plat.lastEnabledNetId, "HomeFiber", "dd:ee",
                    -51, 5180, ipInt, ipInt, gwInt, ipToInt(255, 255, 255, 0), gwInt, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            Map<String, Object> fixed = Json.object(ctl.statusJson());
            check("Updating password live connects immediately", "connected", fixed.get("phase"));
            check("Auth error cleared on connect", "", fixed.get("errorSsid"));
        } finally {
            deleteRec(root);
        }
    }

    private static void testAutoParked30sTimerShiftOutAndRebootRestore() throws Exception {
        File root = Files.createTempDirectory("hw-parked").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.softApMode = 2;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.handleCommand(Arrays.asList("join", "DrivewayAP", "driveway-secret"));
            ctl.start();

            // Car turns off (power=adm, parked=false): <30s keeps Projection SoftAP untouched
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", false, true);
            plat.nowMs = 10000L;
            ctl.tickVehicleAndHealth();
            checkTrue("At 0s off, Projection SoftAP stays up", plat.softApActive);

            plat.nowMs = 35000L; // 25s off
            ctl.tickVehicleAndHealth();
            checkTrue("At 25s off, Projection SoftAP still up", plat.softApActive);

            plat.nowMs = 41000L; // 31s off -> switches!
            ctl.tickVehicleAndHealth();
            checkTrue("After 30s off, Projection SoftAP disabled and Home Wi-Fi starts", !plat.softApActive);
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            // Car turns back on -> Projection SoftAP Mode 2 restored immediately
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true);
            ctl.tickVehicleAndHealth();
            checkTrue("Car back on restores SoftAP", plat.softApActive);
            check("Restored exact Mode 2 Projection AP", Integer.valueOf(2), Integer.valueOf(plat.lastRestoredApMode));

            // Manual Connect Now in Park -> shifting out of Park restores Mode 2 Projection AP immediately
            ctl.handleCommand(Arrays.asList("now", "30"));
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            checkTrue("Manual session active in Park", !plat.softApActive);
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, false); // Shifted to Drive/Reverse!
            ctl.tickVehicleAndHealth();
            checkTrue("Shifting out of Park immediately restores Projection AP", plat.softApActive);

            // Simulate reboot while Home Wi-Fi was active (leftover boot-1 mode file)
            store.saveApMode(2);
            store.saveActiveBootId("boot-old");
            plat.bootId = "boot-new";
            plat.softApActive = false;
            plat.wifiState = WifiPlatform.WIFI_STATE_ENABLED;
            HomeWifiController afterReboot = new HomeWifiController(store, plat);
            afterReboot.start();
            checkTrue("Reboot cleanup restores Projection AP", plat.softApActive);
            check("Mode boot marker cleared after cleanup", "", store.loadActiveBootId());
        } finally {
            deleteRec(root);
        }
    }

    private static void testParkedKeepAwakeUnpluggedAndPluggedIn() throws Exception {
        File root = Files.createTempDirectory("hw-keepawake").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("Garage5G", "garage-password", true, "[WPA2-PSK-CCMP][ESS]", true);

            // Verify defaults: 30m unplugged, plugged (unlimited) when plugged in
            check("Default keepAwakeUnplugged", "30m", store.getKeepAwakeUnplugged());
            check("Default keepAwakePlugged", "plugged", store.getKeepAwakePlugged());

            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.nowMs = 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 88, false);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();
            checkTrue("No power lock while car is ON", !plat.holdParkedPower && !plat.holdWifiLock);

            // 1. Park unplugged (default 30m)
            plat.nowMs = 20000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 88, false);
            ctl.tickVehicleAndHealth();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            checkTrue("Unplugged parked session holds WifiLock + ParkedPower", plat.holdWifiLock && plat.holdParkedPower);

            // Simulate DHCP completion and verify /data/hmgapi/wifi/lease is written BEFORE public/wifi.json
            int ip = ipToInt(192, 168, 50, 42);
            int gw = ipToInt(192, 168, 50, 1);
            int dns1 = ipToInt(1, 1, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "Garage5G", "aa:bb:cc:dd:ee:01",
                    -52, 5180, ip, ip, gw, ipToInt(255, 255, 255, 0), dns1, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            File leaseFile = new File(root, "data/hmgapi/wifi/lease");
            checkTrue("Root /data/hmgapi/wifi/lease written on connect", leaseFile.isFile());
            String leaseTxt = HomeWifiStore.readText(leaseFile).trim();
            check("Lease contains gw, dns1, ip", "192.168.50.1 1.1.1.1 192.168.50.42", leaseTxt);

            File kaFile = new File(root, "data/hmgapi/public/keepawake.json");
            checkTrue("Public keepawake.json written while parked", kaFile.isFile());
            Map<String, Object> kaObj = Json.object(HomeWifiStore.readText(kaFile));
            check("keepawake.json active=true", Boolean.TRUE, kaObj.get("active"));

            // At +20 minutes: still awake and connected!
            plat.nowMs = 20000L + 20L * 60000L;
            ctl.tickVehicleAndHealth();
            Map<String, Object> st20 = Json.object(ctl.statusJson());
            check("Still connected at +20m unplugged", "connected", st20.get("phase"));
            checkTrue("Still holding parked power at +20m", plat.holdParkedPower && plat.holdWifiLock);

            // At +30m + 1s: keep-awake expires -> client Wi-Fi stopped WITHOUT starting SoftAP in empty garage!
            plat.nowMs = 20000L + 30L * 60000L + 1000L;
            ctl.tickVehicleAndHealth();
            Map<String, Object> st31 = Json.object(ctl.statusJson());
            check("Phase transitions to sleep at +30m", "sleep", st31.get("phase"));
            checkTrue("Locks released at +30m", !plat.holdParkedPower && !plat.holdWifiLock);
            checkTrue("Client Wi-Fi disabled for sleep", plat.wifiState == WifiPlatform.WIFI_STATE_DISABLED);
            checkTrue("SoftAP NOT turned on while car is still off", !plat.softApActive);
            checkTrue("Lease file cleared on sleep", !leaseFile.isFile());

            // Now turn car ON -> SoftAP restored immediately!
            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 90, false);
            ctl.tickVehicleAndHealth();
            checkTrue("SoftAP restored immediately when car turns ON", plat.softApActive);
            checkTrue("keepawake.json cleared when car turns ON", !kaFile.isFile());

            // 2. Park PLUGGED IN (scheduled TOU charge: pluggedIn=true, charging=false!)
            plat.nowMs = 3000000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, true, false, 85, false);
            ctl.tickVehicleAndHealth();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            checkTrue("Plugged-in idle session holds power", plat.holdParkedPower);

            // Advance +4 hours while plugged in -> STILL awake ("plugged" = unlimited while plugged in)!
            plat.nowMs = 3000000L + 4L * 3600L * 1000L;
            ctl.tickVehicleAndHealth();
            Map<String, Object> stPlug4h = Json.object(ctl.statusJson());
            check("Unlimited while plugged in after 4 hours", Boolean.TRUE, stPlug4h.get("keepAwakeUnlimited"));
            checkTrue("Power still held at +4h while plugged in", plat.holdParkedPower);

            // Unplug cable at +4 hours -> 60s grace window for final sync, then sleeps!
            plat.nowMs += 1000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 84, false);
            ctl.tickVehicleAndHealth();
            checkTrue("60s unplug sync grace keeps Wi-Fi/power briefly", plat.holdParkedPower);

            plat.nowMs += 61000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Sleeps cleanly after 60s unplug grace", !plat.holdParkedPower && "sleep".equals(Json.object(ctl.statusJson()).get("phase")));

            // Configure custom keepawake via IPC verb
            HomeWifiController.CmdResult rKa = ctl.handleCommand(Arrays.asList("keepawake", "15m", "2h"));
            check("keepawake IPC succeeds", Integer.valueOf(0), Integer.valueOf(rKa.code));
            check("Updated keepAwakeUnplugged", "15m", store.getKeepAwakeUnplugged());
            check("Updated keepAwakePlugged", "2h", store.getKeepAwakePlugged());
        } finally {
            deleteRec(root);
        }
    }

    private static void testKeepAwake12vGuardianAndPeerOpenLink() throws Exception {
        File root = Files.createTempDirectory("hw-peer-12v").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("HomeNet", "home-password", true, "[WPA2-PSK-CCMP][ESS]", true);
            store.setKeepAwakePolicy("5m", "plugged");

            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.nowMs = 50000L;
            // Peer OpenLink lease wants 30m!
            File peerFile = new File(root, "data/hmgapi/public/openlink-keepawake.json");
            peerFile.getParentFile().mkdirs();
            long peerExp = 50000L + 30L * 60000L;
            Files.write(peerFile.toPath(),
                    ("{\"bootId\":\"boot-1\",\"active\":true,\"unlimited\":false,\"cutBy12vFloor\":false,"
                            + "\"expiresAtElapsedMs\":" + peerExp + ",\"remainingSec\":1800,\"updatedAtElapsedMs\":50000}\n")
                            .getBytes("UTF-8"));

            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 82, false);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            int ip = ipToInt(192, 168, 1, 55);
            int gw = ipToInt(192, 168, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "HomeNet", "aa:bb:cc:dd:ee:11",
                    -56, 5180, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            // At +10 minutes AT HOME (past Home Wi-Fi's own 5m, inside OpenLink's 30m): stays connected via OpenLink coordination!
            plat.nowMs = 50000L + 10L * 60000L;
            Files.write(peerFile.toPath(),
                    ("{\"bootId\":\"boot-1\",\"active\":true,\"unlimited\":false,\"cutBy12vFloor\":false,"
                            + "\"expiresAtElapsedMs\":" + peerExp + ",\"remainingSec\":1200,\"updatedAtElapsedMs\":" + plat.nowMs + "}\n")
                            .getBytes("UTF-8"));
            ctl.tickVehicleAndHealth();
            Map<String, Object> stExt = Json.object(ctl.statusJson());
            check("Extended by OpenLink peer lease", Boolean.TRUE, stExt.get("keepAwakeExtendedByOpenLink"));
            checkTrue("Stays in client mode at home while OpenLink lease active", (Boolean) stExt.get("active"));

            // Single 12V dip to 62% -> 1st sample debounced (does NOT cut yet!)
            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 62, false);
            ctl.tickVehicleAndHealth();
            checkTrue("Single 12V dip debounced", plat.holdParkedPower);

            // Second consecutive 12V sample at 62% (< 65% floor) -> cuts off immediately!
            plat.nowMs += 5000L;
            ctl.tickVehicleAndHealth();
            Map<String, Object> stCut = Json.object(ctl.statusJson());
            check("12V cutoff flagged", Boolean.TRUE, stCut.get("keepAwakeCutBy12v"));
            checkTrue("Power & Wi-Fi released on 12V cutoff", !plat.holdParkedPower && !(Boolean) stCut.get("active"));
        } finally {
            deleteRec(root);
        }
    }

    private static void testAwayFromHomeAutoSleepAndOpenLinkPrecedence() throws Exception {
        File root = Files.createTempDirectory("hw-away-home").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("MyHomeWiFi", "home-secret-123", true, "[WPA2-PSK-CCMP][ESS]", true);
            // User configured 2h unplugged & "While plugged in" (default) when plugged in — NO OpenLink installed!
            store.setKeepAwakePolicy("2h", "plugged");

            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.nowMs = 10000L;
            // Nearby networks at grocery store / public charger do NOT include MyHomeWiFi
            plat.scanResults = Arrays.asList(
                    new HomeWifiStore.SeenNetwork("CoffeeShop_Guest", "11:22:33:44:55:01", 2412, -62, "[ESS]"),
                    new HomeWifiStore.SeenNetwork("Store_POS", "11:22:33:44:55:02", 5180, -70, "[WPA2-PSK-CCMP][ESS]"));

            // 1. Park UNPLUGGED away from home (no OpenLink)
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 88, false);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            ctl.onScanResultsAvailable();
            checkTrue("Initially searches and holds power during 2m home-check window",
                    plat.holdParkedPower && plat.holdWifiLock);

            // At +7s: fast-connect fallback switches into normal 4s scanning
            plat.nowMs = 10000L + 7000L;
            plat.fireTimer(HomeWifiController.TAG_FAST_FALLBACK);

            // At +90s: still inside 2-minute home-detection window
            plat.nowMs = 10000L + 90000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Still searching at +90s", plat.holdParkedPower && "scanning".equals(Json.object(ctl.statusJson()).get("phase")));

            // At +2m + 1s (well before the 2h home keep-awake!): no saved Home Wi-Fi connected -> powers off!
            plat.nowMs = 10000L + 121000L;
            ctl.tickVehicleAndHealth();
            Map<String, Object> stAway = Json.object(ctl.statusJson());
            check("Away from home transitions to sleep at 2m", "sleep", stAway.get("phase"));
            check("keepAwakeAwayFromHome flagged", Boolean.TRUE, stAway.get("keepAwakeAwayFromHome"));
            checkTrue("Releases parked power & WifiLock away from home", !plat.holdParkedPower && !plat.holdWifiLock);
            checkTrue("Client Wi-Fi stopped without turning SoftAP back on",
                    plat.wifiState == WifiPlatform.WIFI_STATE_DISABLED && !plat.softApActive);

            // 2. Plug into a PUBLIC EV CHARGER away from home (keepAwakePlugged="plugged", no OpenLink):
            //    gets one 2-minute check, sees no Home Wi-Fi, and goes right back to sleep instead of staying awake all night!
            plat.nowMs += 10000L;
            long plugStart = plat.nowMs;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, true, true, 90, false);
            ctl.tickVehicleAndHealth();
            checkTrue("Plug-in triggers fresh 2m home check", plat.holdParkedPower);

            plat.nowMs = plugStart + 121000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Public EV charger away from home sleeps after 2m instead of staying awake forever",
                    !plat.holdParkedPower && "sleep".equals(Json.object(ctl.statusJson()).get("phase")));

            // 3. OpenLink precedence: drive & park away from home WITH an active OpenLink 30m lease ->
            //    stays awake for OpenLink's full 30m even after Home Wi-Fi's 2m home-check times out!
            plat.nowMs += 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 90, false);
            ctl.tickVehicleAndHealth(); // IG ON resets keep-awake

            plat.nowMs += 10000L;
            long olStart = plat.nowMs;
            long olExp = olStart + 30L * 60000L;
            File peerFile = new File(root, "data/hmgapi/public/openlink-keepawake.json");
            peerFile.getParentFile().mkdirs();
            Files.write(peerFile.toPath(),
                    ("{\"bootId\":\"boot-1\",\"active\":true,\"unlimited\":false,\"cutBy12vFloor\":false,"
                            + "\"expiresAtElapsedMs\":" + olExp + ",\"remainingSec\":1800,\"updatedAtElapsedMs\":" + olStart + "}\n")
                            .getBytes("UTF-8"));

            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 87, false);
            ctl.tickVehicleAndHealth();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            // At +10 minutes away from home: Home Wi-Fi's own demand is false and client Wi-Fi stops scanning,
            // while OpenLink's active lease still keeps parked power held!
            plat.nowMs = olStart + 10L * 60000L;
            Files.write(peerFile.toPath(),
                    ("{\"bootId\":\"boot-1\",\"active\":true,\"unlimited\":false,\"cutBy12vFloor\":false,"
                            + "\"expiresAtElapsedMs\":" + olExp + ",\"remainingSec\":1200,\"updatedAtElapsedMs\":" + plat.nowMs + "}\n")
                            .getBytes("UTF-8"));
            ctl.tickVehicleAndHealth();
            Map<String, Object> ownKa = Json.object(HomeWifiStore.readText(new File(root, "data/hmgapi/public/keepawake.json")));
            check("Home Wi-Fi's own published lease is inactive away from home", Boolean.FALSE, ownKa.get("active"));
            checkTrue("OpenLink lease takes precedence and holds power", plat.holdParkedPower);
            checkTrue("Client Wi-Fi stops scanning in store parking lot once awayTimedOut trips",
                    !plat.holdWifiLock && plat.wifiState == WifiPlatform.WIFI_STATE_DISABLED && !plat.softApActive);
            check("wifi.json reports waiting (not searching) once awayTimedOut idles wlan0",
                    "waiting", Json.object(HomeWifiStore.readText(new File(root, "data/hmgapi/public/wifi.json"))).get("state"));

            // Store Stop #1 IG ON: peer lease deleted & car switches ON -> SoftAP Mode 2 restored immediately!
            peerFile.delete();
            plat.nowMs += 30000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 90, false);
            ctl.tickVehicleAndHealth();
            checkTrue("IG ON after away-from-home store stop immediately restores Mode 2 SoftAP",
                    plat.softApActive && plat.softApMode == 2 && !plat.holdParkedPower);

            // Stale persisted seen_networks.json from home must NOT delay awayTimedOut past 2m on next store stop:
            store.saveSeenNetworks(Arrays.asList(
                    new HomeWifiStore.SeenNetwork("MyHomeWiFi", "aa:bb:cc:dd:ee:99", 5500, -61, "[WPA2-PSK-CCMP][ESS]")));
            HomeWifiController ctl2 = new HomeWifiController(store, plat);
            plat.nowMs += 10000L;
            long store2Start = plat.nowMs;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 88, false);
            ctl2.tickVehicleAndHealth();
            ctl2.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            plat.nowMs = store2Start + 121000L; // 2m 01s (< 3m!)
            ctl2.tickVehicleAndHealth();
            checkTrue("Stale persisted home scan cleared at session start so awayTimedOut trips at 2m (not 3m)",
                    !plat.holdParkedPower && "sleep".equals(Json.object(ctl2.statusJson()).get("phase")));

            // 4. Mid-session router power loss at home: connects at home, then drops for > 3m without OpenLink -> sleeps!
            plat.nowMs += 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 90, false);
            ctl.tickVehicleAndHealth();

            plat.nowMs += 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, true, false, 88, false);
            ctl.tickVehicleAndHealth();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            int ip = ipToInt(192, 168, 1, 77);
            int gw = ipToInt(192, 168, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "MyHomeWiFi", "aa:bb:cc:dd:ee:99",
                    -55, 5180, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            check("Connected at home while plugged in", "connected", Json.object(ctl.statusJson()).get("phase"));

            // 1 hour later, home router loses power -> Wi-Fi drops
            plat.nowMs += 3600L * 1000L;
            long dropMs = plat.nowMs;
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.DISCONNECTED, -1, "", "", 0, 0, 0, 0, 0, 0, 0, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.DISCONNECTED, false);
            ctl.tickVehicleAndHealth();
            checkTrue("Keeps power during 3m reconnect grace after drop", plat.holdParkedPower);

            // Still down after 3m + 1s -> releases power so car can sleep!
            plat.nowMs = dropMs + 181000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Powers off after 3m continuous Home Wi-Fi loss",
                    !plat.holdParkedPower && "sleep".equals(Json.object(ctl.statusJson()).get("phase")));
        } finally {
            deleteRec(root);
        }
    }

    private static void testFlightLogPowerCutAndScrubbing() throws Exception {
        File root = Files.createTempDirectory("hmg-wifi-flightlog").toFile();
        try {
            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.bootId = "44e9b2a1-1122-3344-5566-778899aabbcc";
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("SecretGarageNet", "SuperSecretKey99!", true, "[WPA2-PSK-CCMP][ESS]", true);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();

            // Park car & connect to SecretGarageNet
            plat.nowMs = 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 88, false);
            ctl.tickVehicleAndHealth();
            plat.listener.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            plat.scanResults = Arrays.asList(
                    new HomeWifiStore.SeenNetwork("SecretGarageNet", "aa:bb:cc:dd:ee:ff", 5220, -52, "[WPA2-PSK-CCMP][ESS]")
            );
            plat.listener.onScanResultsAvailable();
            int ip = ipToInt(192, 168, 50, 77);
            int gw = ipToInt(192, 168, 50, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "SecretGarageNet", "aa:bb:cc:dd:ee:ff",
                    -52, 5220, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            plat.listener.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            // Advance +16s while parked so a 15s HEARTBEAT is persisted
            plat.nowMs = 26500L;
            ctl.tickVehicleAndHealth();

            checkTrue("Active session marker exists during parked session", store.flightLog().hasActiveMarker());

            HomeWifiController.CmdResult logRes = ctl.handleCommand(Arrays.asList("log"));
            check("wifi log command succeeds", Integer.valueOf(0), Integer.valueOf(logRes.code));
            checkTrue("Owner log contains SSID for in-car UI", logRes.output.contains("SecretGarageNet"));
            checkTrue("Owner log never contains Wi-Fi PSK", !logRes.output.contains("SuperSecretKey99!"));
            checkTrue("Owner log records SESSION_START, DHCP_UP, and HEARTBEAT",
                    logRes.output.contains("SESSION_START")
                            && logRes.output.contains("DHCP_UP")
                            && logRes.output.contains("HEARTBEAT"));

            String pubLog = store.flightLog().readPublicTail(65536);
            checkTrue("Public log never contains SSID", !pubLog.contains("SecretGarageNet"));
            checkTrue("Public log never contains PSK", !pubLog.contains("SuperSecretKey99!"));
            checkTrue("Public log never contains raw boot UUID", !pubLog.contains("44e9b2a1-1122"));
            checkTrue("Public log never contains full client IP", !pubLog.contains("192.168.50.77"));
            checkTrue("Public log redacts SSID to saved#1 and IP to .x",
                    pubLog.contains("saved#1") && pubLog.contains("192.168.50.x"));

            // USB export: returns error when no USB stick exists, succeeds when USB stick is mounted
            HomeWifiController.CmdResult noUsb = ctl.handleCommand(Arrays.asList("log-usb"));
            check("wifi log-usb returns 1 when no USB stick is mounted", Integer.valueOf(1), Integer.valueOf(noUsb.code));

            File usb0 = new File(root, "storage/usb0");
            usb0.mkdirs();
            HomeWifiController.CmdResult okUsb = ctl.handleCommand(Arrays.asList("log-usb"));
            check("wifi log-usb succeeds with USB stick present", Integer.valueOf(0), Integer.valueOf(okUsb.code));
            File[] exported = new File(usb0, "HMG-API reports").listFiles();
            checkTrue("USB export file written", exported != null && exported.length == 1);
            String usbContent = HomeWifiStore.readText(exported[0]);
            checkTrue("USB exported log is scrubbed of SSID and PSK",
                    !usbContent.contains("SecretGarageNet")
                            && !usbContent.contains("SuperSecretKey99!")
                            && usbContent.contains("saved#1"));

            // Simulate abrupt MICOM 12V power cut at +2m (no clean exitClientSession / shutdown!)
            FakeWifiPlatform nextBootPlat = new FakeWifiPlatform();
            nextBootPlat.bootId = "99f0c3d2-8877-6655-4433-221100ffeedd";
            nextBootPlat.nowMs = 4500L;
            nextBootPlat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true);
            HomeWifiStore nextStore = new HomeWifiStore(root);
            HomeWifiController nextCtl = new HomeWifiController(nextStore, nextBootPlat);
            nextCtl.start();

            checkTrue("Active marker cleared after cold-boot recovery", !nextStore.flightLog().hasActiveMarker());
            String postCutPub = nextStore.flightLog().readPublicTail(65536);
            checkTrue("Cold-boot power cut detected and logged to public journal",
                    postCutPub.contains("UNEXPECTED_POWER_CUT") && postCutPub.contains("outcome=COLD_BOOT_CUT"));

            // Simulate QuickBoot STR suspend gap
            nextBootPlat.nowMs = 150000L;
            nextBootPlat.suspendOffsetMs = 120000L;
            nextCtl.tickVehicleAndHealth();
            String postStrPub = nextStore.flightLog().readPublicTail(65536);
            checkTrue("QuickBoot STR suspend gap logged",
                    postStrPub.contains("STR_RESUME") && postStrPub.contains("gapDeltaMs=120000"));

            // Clear command wipes logs cleanly
            HomeWifiController.CmdResult clr = nextCtl.handleCommand(Arrays.asList("log-clear"));
            check("wifi log-clear succeeds", Integer.valueOf(0), Integer.valueOf(clr.code));
            check("Public log empty after clear", "", nextStore.flightLog().readPublicTail(65536));
        } finally {
            deleteRec(root);
        }
    }

    private static void testAdmPreLatchDoorOpenAndSoftApHijackReclaim() throws Exception {
        File root = Files.createTempDirectory("hw-adm-hijack").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("Garage5G", "garage-password", true, "[WPA2-PSK-CCMP][ESS]", true);

            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.nowMs = 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 95, false, 1);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();

            // 1. Driver presses Power OFF (power="adm", parked=false, doorsClosed=1 while still seated):
            //    PwrKeep(1) MUST pre-latch immediately so MICOM stays in ADM (2) instead of DELAY_MODE (6),
            //    while keeping Wireless Projection SoftAP & RAP untouched!
            plat.nowMs = 12000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", false, true, false, false, 95, false, 1);
            ctl.onVehicleSignalChanged();
            checkTrue("ADM pre-latches holdParkedPower immediately before door open", plat.holdParkedPower);
            checkTrue("ADM keeps holdWifiLock false while driver seated", !plat.holdWifiLock);
            checkTrue("ADM keeps Projection SoftAP untouched while seated", plat.softApActive);

            // 2. Driver opens door 5s later (doorsClosed=0, parked still false before door closes):
            //    Session starts immediately without waiting 15s for door close or 30s timeout!
            plat.nowMs = 17000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", false, true, false, false, 95, false, 0);
            ctl.onVehicleSignalChanged();
            checkTrue("Opening door in Park immediately disables Projection SoftAP", !plat.softApActive);
            checkTrue("Opening door holds both WifiLock and ParkedPower", plat.holdWifiLock && plat.holdParkedPower);
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            int ip = ipToInt(192, 168, 1, 44);
            int gw = ipToInt(192, 168, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "Garage5G", "aa:bb:cc:dd:ee:22",
                    -62, 5500, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            File wifiPub = new File(root, "data/hmgapi/public/wifi.json");
            File leaseFile = new File(root, "data/hmgapi/wifi/lease");
            check("Home Wi-Fi public status is up", "up", Json.object(HomeWifiStore.readText(wifiPub)).get("state"));
            checkTrue("Lease file present while up", leaseFile.isFile());

            // 3. Simulate respawned background projection enabling SoftAP (192.168.0.1) mid-park:
            //    Controller must immediately drop "up" & lease, disable SoftAP, and reclaim client Wi-Fi!
            plat.softApActive = true;
            plat.wifiState = WifiPlatform.WIFI_STATE_DISABLED;
            ctl.onApStateChanged(WifiPlatform.AP_STATE_ENABLED);
            check("Public wifi.json immediately leaves 'up' on SoftAP hijack",
                    "searching", Json.object(HomeWifiStore.readText(wifiPub)).get("state"));
            checkTrue("Stale lease cleared on SoftAP hijack", !leaseFile.isFile());
            checkTrue("Hijacking SoftAP immediately disabled", !plat.softApActive);

            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            check("Home Wi-Fi automatically reconnects after reclaiming wlan0",
                    "up", Json.object(HomeWifiStore.readText(wifiPub)).get("state"));
            checkTrue("Flight log recorded SOFTAP_RECLAIM",
                    store.flightLog().readPublicTail(65536).contains("SOFTAP_RECLAIM"));

            // 4. Resume IG ON: wifi.json must flip to 'waiting' BEFORE restoreSoftAp() sleeps/completes
            //    so OpenLink drops ip rule 905 at t=0ms instead of 5s later!
            plat.wifiPubFile = wifiPub;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true);
            ctl.tickVehicleAndHealth();
            check("Public wifi.json cleared to waiting BEFORE restoreSoftAp completes",
                    "waiting", plat.wifiStateDuringRestoreAp);

            // 5. Untimed manual scan in Park ("scan" / "0") has no arbitrary 30m cutoff (manualRemainingSec == -1)
            ctl.handleCommand(Arrays.asList("scan", "0"));
            check("Untimed Park scan sets manualRemainingSec=-1",
                    Long.valueOf(-1), Json.object(ctl.statusJson()).get("manualRemainingSec"));
        } finally {
            deleteRec(root);
        }
    }

    private static void testAdbOverWifiLifecycleAndScrubbing() throws Exception {
        File root = Files.createTempDirectory("hw-adb-tcp").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("GarageLab", "garage-lab-secret", true, "[WPA2-PSK-CCMP][ESS]", true);
            check("ADB over Wi-Fi defaults to false", Boolean.FALSE, Boolean.valueOf(store.isAdbOverWifi()));

            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.nowMs = 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 92, false);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            int ip = ipToInt(192, 168, 77, 19);
            int gw = ipToInt(192, 168, 77, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "GarageLab", "aa:bb:cc:dd:ee:44",
                    -53, 5180, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            Map<String, Object> stDefault = Json.object(ctl.statusJson());
            check("ADB inactive when setting is off", Boolean.FALSE, stDefault.get("adbActive"));
            check("adbEndpoint empty when setting is off", "", stDefault.get("adbEndpoint"));
            checkTrue("Platform ADB stayed disabled", !plat.adbEnabled);

            // 1. Live toggle ON while already connected!
            HomeWifiController.CmdResult onRes = ctl.handleCommand(Arrays.asList("adb", "on"));
            check("wifi adb on succeeds", Integer.valueOf(0), Integer.valueOf(onRes.code));
            checkTrue("Persisted adbOverWifi=true", new HomeWifiStore(root).isAdbOverWifi());
            checkTrue("Platform ADB enabled immediately on live IP", plat.adbEnabled);
            check("Platform ADB bound to wlan0 IP", "192.168.77.19", plat.adbIp);

            Map<String, Object> stOn = Json.object(ctl.statusJson());
            check("statusJson reports adbOverWifi=true", Boolean.TRUE, stOn.get("adbOverWifi"));
            check("statusJson reports adbActive=true", Boolean.TRUE, stOn.get("adbActive"));
            check("statusJson reports adbEndpoint", "192.168.77.19:5555", stOn.get("adbEndpoint"));
            checkTrue("Status detail includes ADB endpoint",
                    String.valueOf(stOn.get("detail")).contains("ADB: 192.168.77.19:5555"));

            // Verify flight log scrubbing: owner log has full IP, public log redacts last octet!
            String ownLog = store.flightLog().readOwnerTail(65536);
            String pubLog = store.flightLog().readPublicTail(65536);
            checkTrue("Owner log records ADB_TCP_UP with full endpoint",
                    ownLog.contains("ADB_TCP_UP") && ownLog.contains("192.168.77.19:5555"));
            checkTrue("Public log redacts ADB endpoint IP",
                    pubLog.contains("ADB_TCP_UP")
                            && pubLog.contains("192.168.77.x:5555")
                            && !pubLog.contains("192.168.77.19"));

            // 2. Live toggle OFF while connected: passes deferTeardown=true for ADB CLI callers!
            HomeWifiController.CmdResult offRes = ctl.handleCommand(Arrays.asList("adb", "off"));
            check("wifi adb off succeeds", Integer.valueOf(0), Integer.valueOf(offRes.code));
            checkTrue("Platform ADB disabled", !plat.adbEnabled);
            checkTrue("Live wifi adb off defers teardown briefly for CLI response", plat.lastAdbDeferTeardown);
            Map<String, Object> stOff = Json.object(ctl.statusJson());
            check("statusJson clears adbActive", Boolean.FALSE, stOff.get("adbActive"));
            check("statusJson clears adbEndpoint", "", stOff.get("adbEndpoint"));
            checkTrue("Detail removes ADB endpoint",
                    !String.valueOf(stOff.get("detail")).contains("ADB:"));
            checkTrue("Public log records ADB_TCP_DOWN",
                    store.flightLog().readPublicTail(65536).contains("ADB_TCP_DOWN"));

            // 3. Turn ON in Standby, connect automatically when parked, then IG ON tears down immediately!
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 92, false);
            ctl.tickVehicleAndHealth();
            ctl.handleCommand(Arrays.asList("adb", "on"));
            checkTrue("ADB stays inactive while in Standby", !plat.adbEnabled);

            plat.nowMs += 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 92, false);
            ctl.tickVehicleAndHealth();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            checkTrue("ADB activates automatically when Home Wi-Fi connects", plat.adbEnabled);

            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 92, false);
            ctl.tickVehicleAndHealth();
            checkTrue("ADB disabled immediately on IG ON", !plat.adbEnabled);
            checkTrue("Session teardown does NOT defer firewall close", !plat.lastAdbDeferTeardown);
        } finally {
            deleteRec(root);
        }
    }

    private static void testLockedCabinIccuPlugLatchAndNoZombieReentry() throws Exception {
        File root = Files.createTempDirectory("hw-iccu-latch").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("GarageEV", "garage-ev-secret", true, "[WPA2-PSK-CCMP][ESS]", true);
            store.setKeepAwakePolicy("30m", "plugged");

            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.bootId = "2b7445cc-overnight";
            plat.nowMs = 10000L;
            // Car parked, EVSE plugged in, HV charging already completed (charging=false), doors closed & locked
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, true, false, 89, false, 1, 1);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);

            int ip = ipToInt(192, 168, 1, 44);
            int gw = ipToInt(192, 168, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, 10, "GarageEV", "aa:bb:cc:dd:ee:99",
                    -58, 5180, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);

            checkTrue("Plugged & locked session holds parked power", plat.holdParkedPower);
            check("Status reports unlimited plugged remainingSec=-1",
                    Long.valueOf(-1), Json.object(ctl.statusJson()).get("keepAwakeRemainingSec"));

            // At +87 minutes (03:19 AM), E-GMP ICCU 12V Aux. Battery Saver+ starts at 80% -> 92%
            // and VCMS HS-CAN sleeps, dropping raw pluggedIn=false while doors stay closed (1) & locked (1)!
            long t87m = 10000L + 87L * 60000L;
            plat.nowMs = t87m;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 80, false, 1, 1);
            ctl.tickVehicleAndHealth();
            checkTrue("ICCU Aux Saver+ conn=0 drop while locked keeps holdParkedPower=true", plat.holdParkedPower);

            // Advance +3 hours past CAN sleep while still locked & closed -> must NOT expire after 60s!
            long t4h = t87m + 3L * 3600L * 1000L;
            plat.nowMs = t4h;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 91, false, 1, 1);
            ctl.tickVehicleAndHealth();
            checkTrue("Remains awake 3+ hours after VCMS CAN sleep while cabin stays locked", plat.holdParkedPower);
            check("Still unlimited while latched in locked cabin",
                    Long.valueOf(-1), Json.object(ctl.statusJson()).get("keepAwakeRemainingSec"));

            // Verify stricter 65% MIN_12V_UNPLUGGED floor applies whenever raw pluggedIn==false && !charging:
            HomeWifiKeepAwake kaCheck = new HomeWifiKeepAwake();
            kaCheck.evaluate(new WifiPlatform.VehicleSnapshot("adm", true, true, true, false, 85, false, 1, 1),
                    1000L, "b1", "30m", "plugged", null, true, true);
            HomeWifiKeepAwake.Verdict v64a = kaCheck.evaluate(
                    new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 64, false, 1, 1),
                    2000L, "b1", "30m", "plugged", null, true, true);
            checkTrue("1st sample at 64% (below 65% raw-unplugged floor) debounced", !v64a.cutBy12v && kaCheck.isPlugLatchedWhileLocked());
            HomeWifiKeepAwake.Verdict v64b = kaCheck.evaluate(
                    new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 64, false, 1, 1),
                    3000L, "b1", "30m", "plugged", null, true, true);
            checkTrue("2nd sample at 64% while raw pluggedIn==false trips 65% 12V guardian", v64b.cutBy12v && !v64b.holdPower);

            // Morning (07:47 AM): owner presses Unlock on key fob (doorsLocked: 1 -> 0) while raw pluggedIn==false
            long tUnlock = t4h + 60000L;
            plat.nowMs = tUnlock;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 90, false, 1, 0);
            ctl.tickVehicleAndHealth();
            checkTrue("Key-fob unlock immediately starts 60s unplug grace without dropping at t+0s", plat.holdParkedPower);

            plat.nowMs = tUnlock + 30000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Still holding power at +30s of post-unlock unplug grace", plat.holdParkedPower);

            plat.nowMs = tUnlock + 61000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Releases parked power once 60s post-unlock grace completes", !plat.holdParkedPower);
            check("Enters sleep phase cleanly after expiry", "sleep", Json.object(ctl.statusJson()).get("phase"));

            // Post-expiry zombie prevention:
            //  1) OpenLink writing a 4s flushingFinalSync=true teardown lease must NOT spin up a new Wi-Fi session
            File peerLeaseFile = new File(root, "data/hmgapi/public/openlink-keepawake.json");
            peerLeaseFile.getParentFile().mkdirs();
            Files.write(peerLeaseFile.toPath(),
                    ("{\"bootId\":\"" + plat.bootId + "\",\"active\":true,\"unlimited\":false,"
                            + "\"flushingFinalSync\":true,\"expiresAtElapsedMs\":" + (plat.nowMs + 4000L)
                            + ",\"remainingSec\":4,\"updatedAtElapsedMs\":" + plat.nowMs + "}\n").getBytes("UTF-8"));
            plat.nowMs += 1000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", true, true, false, false, 90, false, 1, 0);
            ctl.tickVehicleAndHealth();
            checkTrue("Does NOT re-enter zombie session for peer flushingFinalSync teardown lease",
                    !plat.holdWifiLock && !plat.holdParkedPower && "sleep".equals(Json.object(ctl.statusJson()).get("phase")));

            //  2) Even if power steps to preparing_off, never start a new session
            peerLeaseFile.delete();
            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("preparing_off", true, true, true, false, 90, false, 1, 1);
            ctl.tickVehicleAndHealth();
            checkTrue("Never starts a new parked session during preparing_off",
                    !plat.holdWifiLock && !plat.holdParkedPower);
        } finally {
            deleteRec(root);
        }
    }

    private static void testRouterWithFakeToolsIfPresent() throws Exception {
        String fw = System.getenv("FW");
        if (fw == null || fw.isEmpty()) return;
        HomeWifiRouter router = new HomeWifiRouter();
        boolean up = router.routeUp("10.4.0.22", "10.4.0.1", "10.10.10.100", "");
        checkTrue("HomeWifiRouter.routeUp succeeds", up);

        String routes = HomeWifiStore.readText(new File(fw, "ip4.route"));
        checkTrue("Main-table default route on wlan0 removed", !routes.contains("default via 10.4.0.1 dev wlan0 table main"));
        checkTrue("Table 95 default route installed", routes.contains("default via 10.4.0.1 dev wlan0 table 95"));
        checkTrue("Table 95 subnet route installed", routes.contains("10.4.0.0/16 dev wlan0 table 95"));
        checkTrue("Off-subnet DNS host route installed", routes.contains("10.10.10.100/32 via 10.4.0.1 dev wlan0 metric 1000"));

        String ndcLog = HomeWifiStore.readText(new File(fw, "ndc.log"));
        checkTrue("Car default DNS kept on rmnet0", ndcLog.contains("resolver setdefaultif rmnet0"));
        checkTrue("wlan0 DNS set for mods", ndcLog.contains("resolver setifdns wlan0  10.10.10.100"));

        String homedns = HomeWifiStore.readText(new File(fw, "4/nat/HMGAPI_HOMEDNS"));
        checkTrue("Mod service UDP DNS DNAT installed", homedns.contains("-m owner --gid-owner 2951 -p udp --dport 53 -j DNAT --to-destination 10.10.10.100"));
        checkTrue("Mod service TCP DNS DNAT installed", homedns.contains("-m owner --gid-owner 2951 -p tcp --dport 53 -j DNAT --to-destination 10.10.10.100"));

        // Verify HomeWifiRouter ADB over TCP firewall, source-IP policy rule 906, and volatile property lifecycle
        boolean adbUp = router.setAdbOverWifi(true, "10.4.0.22", false);
        checkTrue("HomeWifiRouter.setAdbOverWifi(true) succeeds", adbUp);
        String homeadb = HomeWifiStore.readText(new File(fw, "4/filter/HMGAPI_HOMEADB")).trim();
        check("HMGAPI_HOMEADB accepts TCP 5555 on wlan0", "-i wlan0 -p tcp --dport 5555 -j ACCEPT", homeadb);
        String inputChain = HomeWifiStore.readText(new File(fw, "4/filter/INPUT"));
        checkTrue("INPUT jumps to HMGAPI_HOMEADB", inputChain.contains("-j HMGAPI_HOMEADB"));
        String ipRules = HomeWifiStore.readText(new File(fw, "ip4.rule"));
        checkTrue("Priority 906 source-IP rule installed for adbd return traffic",
                ipRules.contains("906:\tfrom 10.4.0.22/32 table 95"));
        check("Volatile service.adb.tcp.port set to 5555",
                "5555", HomeWifiStore.readText(new File(fw, "props/service.adb.tcp.port")));
        checkTrue("NEVER touches persist.service.adb.tcp.enable (protects CarPlay/AA eth0)",
                !new File(fw, "props/persist.service.adb.tcp.enable").exists());
        checkTrue("NEVER touches persist.adb.tcp.port",
                !new File(fw, "props/persist.adb.tcp.port").exists());
        check("adbd stopped and restarted onto TCP 5555",
                "adbd", HomeWifiStore.readText(new File(fw, "props/ctl.start")));

        // Verify routeDown() tears down HMGAPI_HOMEADB, ip rule 906, and resets volatile service.adb.tcp.port=-1
        router.routeDown();
        String routesAfter = HomeWifiStore.readText(new File(fw, "ip4.route"));
        checkTrue("Table 95 flushed on routeDown", !routesAfter.contains("table 95"));
        checkTrue("HMGAPI_HOMEADB chain removed on routeDown", !new File(fw, "4/filter/HMGAPI_HOMEADB").exists());
        checkTrue("ip rule 906 removed on routeDown",
                !HomeWifiStore.readText(new File(fw, "ip4.rule")).contains("906:"));
        check("Volatile service.adb.tcp.port reset to -1 (USB-only)",
                "-1", HomeWifiStore.readText(new File(fw, "props/service.adb.tcp.port")));

        // Verify coexistence when owner ALSO has Open-HMG/hmgapi-adb Wi-Fi toggle enabled (persist.adb.tcp.port=5555)
        new File(fw, "props").mkdirs();
        Files.write(new File(fw, "props/persist.adb.tcp.port").toPath(), "5555".getBytes("UTF-8"));
        Files.write(new File(fw, "props/service.adb.tcp.port").toPath(), "".getBytes("UTF-8"));
        new File(fw, "props/ctl.stop").delete();
        new File(fw, "props/ctl.start").delete();

        router.setAdbOverWifi(true, "10.4.0.22", false);
        checkTrue("With persist.adb.tcp.port=5555 already set, adbd is NOT bounced unnecessarily",
                !new File(fw, "props/ctl.stop").exists());
        checkTrue("HMGAPI_HOMEADB still installed so home LAN subnet can reach :5555",
                new File(fw, "4/filter/HMGAPI_HOMEADB").isFile());

        router.setAdbOverWifi(false, "", false);
        check("Teardown preserves persist.adb.tcp.port=5555",
                "5555", HomeWifiStore.readText(new File(fw, "props/persist.adb.tcp.port")));
        check("Teardown leaves service.adb.tcp.port empty so permanent hotspot ADB stays up",
                "", HomeWifiStore.readText(new File(fw, "props/service.adb.tcp.port")));
        checkTrue("adbd NOT stopped when persist.adb.tcp.port=5555 is active",
                !new File(fw, "props/ctl.stop").exists());
    }

    private static void testSlowSoftApDisablingAndRapManualScanHandoff() throws Exception {
        File root = Files.createTempDirectory("hw-ap-disabling").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            plat.holdDisablingOnStop = true; // disableSoftAp enters AP_STATE_DISABLING (10) instead of instant 11
            plat.nowMs = 10000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", true, true, false, false, 85, false);

            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.handleCommand(Arrays.asList("join", "GarageNet", "garage-pass-99"));
            ctl.start();

            check("Enters disabling_ap on park while SoftAP is stopping",
                    "disabling_ap", Json.object(ctl.statusJson()).get("phase"));
            check("Zero premature setWifiEnabled(true) calls initially",
                    Integer.valueOf(0), Integer.valueOf(plat.setWifiEnabledCalls));

            // Simulate 62 seconds of GalReceiver / hostapd socket teardown while apState == AP_STATE_DISABLING (10)
            for (int sec = 1; sec <= 62; sec++) {
                plat.nowMs += 1000L;
                plat.fireTimer(HomeWifiController.TAG_AP_FALLBACK);
                ctl.tickVehicleAndHealth();
            }
            check("Stays in disabling_ap across 62s AP_STATE_DISABLING without wedging WifiStateMachine",
                    "disabling_ap", Json.object(ctl.statusJson()).get("phase"));
            check("setWifiEnabled(true) NEVER called while SoftAP is still AP_STATE_DISABLING",
                    Integer.valueOf(0), Integer.valueOf(plat.setWifiEnabledCalls));

            // At +63s SoftAP finally reaches AP_STATE_DISABLED (11)
            plat.softApStateOverride = WifiPlatform.AP_STATE_DISABLED;
            plat.nowMs += 1000L;
            plat.fireTimer(HomeWifiController.TAG_AP_FALLBACK);
            check("setWifiEnabled(true) called cleanly once SoftAP reaches AP_STATE_DISABLED",
                    Integer.valueOf(1), Integer.valueOf(plat.setWifiEnabledCalls));

            // Verify slow 63s SoftAP teardown did NOT eat into wpa_supplicant's 120s scan window:
            // advancing +100s (total 163s since park, but only 100s since WIFI_READY) must still be scanning!
            plat.nowMs += 100000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Full 2-minute scan window preserved after slow SoftAP stop",
                    plat.holdWifiLock && !"sleep".equals(Json.object(ctl.statusJson()).get("phase")));

            // Connect to GarageNet cleanly
            int ip = ipToInt(192, 168, 1, 88);
            int gw = ipToInt(192, 168, 1, 1);
            plat.conn = new WifiPlatform.ConnectionSnapshot(
                    WifiPlatform.SupState.COMPLETED, plat.lastEnabledNetId, "GarageNet", "aa:bb:cc:dd:ee:88",
                    -58, 5180, ip, ip, gw, ipToInt(255, 255, 255, 0), gw, 0);
            ctl.onSupplicantStateChanged(WifiPlatform.SupState.COMPLETED, false);
            check("Connected after slow SoftAP stop", "connected", Json.object(ctl.statusJson()).get("phase"));

            // Now test RAP manual scan ("Live Wi-Fi scan in Park" while power=adm) auto-exiting on IG_ON:
            plat.holdDisablingOnStop = false;
            plat.softApStateOverride = -1;
            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 85, false);
            ctl.tickVehicleAndHealth();
            checkTrue("Driving restores SoftAP", plat.softApActive);

            // Enter RAP (power=adm, parked=false, doors closed) and open Home Wi-Fi UI scan
            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("adm", false, true, false, false, 85, false, 1, 1);
            ctl.tickVehicleAndHealth();
            ctl.handleCommand(Arrays.asList("now", "0")); // manual scan started while power=adm (!isPowerOn)
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            checkTrue("Manual scan active in RAP", !plat.softApActive && plat.wifiState == WifiPlatform.WIFI_STATE_ENABLED);

            // Driver switches car back ON (IG_ON) while still in Park (gearPark=true):
            // Must immediately exit client mode and restore Mode 2 SoftAP for Wireless AA/CarPlay!
            plat.nowMs += 5000L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 85, false, 1, 1);
            ctl.tickVehicleAndHealth();
            checkTrue("RAP manual scan automatically restores Mode 2 SoftAP on IG_ON in Park",
                    plat.softApActive && plat.wifiState == WifiPlatform.WIFI_STATE_DISABLED);
            check("Phase returns to standby on IG_ON after RAP scan",
                    "standby", Json.object(ctl.statusJson()).get("phase"));
        } finally {
            deleteRec(root);
        }
    }

    private static void testSilentDelayModeBackgroundWakeFastRelease() throws Exception {
        File root = Files.createTempDirectory("hw-silent-wake").toFile();
        try {
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("MyHome", "homepassword", true, "[WPA2-PSK-CCMP][ESS]", true);

            FakeWifiPlatform plat = new FakeWifiPlatform();
            // Simulate silent Bluelink/BCM cold boot directly into delay_mode (pwrSt=6, never power=on)
            plat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, false, false, false, 82, false, 1, 1);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();

            // After 5s (SILENT_WAKE_OFF_DELAY_MS), client Wi-Fi starts scanning immediately without waiting 30s
            plat.nowMs += 5500L;
            ctl.tickVehicleAndHealth();
            ctl.onApStateChanged(WifiPlatform.AP_STATE_DISABLED);
            checkTrue("Silent delay_mode wake starts scanning after 5s",
                    plat.wifiState == WifiPlatform.WIFI_STATE_ENABLED);

            // At +20s away from home: still scanning
            plat.nowMs += 20000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Silent delay_mode wake still scanning at +20s",
                    plat.wifiState == WifiPlatform.WIFI_STATE_ENABLED);

            // At +36s (>= SILENT_WAKE_ACQUIRE_TIMEOUT_MS=35s) with 0 saved SSIDs seen: releases Wi-Fi & power lock!
            plat.nowMs += 16000L;
            ctl.tickVehicleAndHealth();
            checkTrue("Silent delay_mode wake away from home shuts down Wi-Fi & power lock after 35s",
                    plat.wifiState == WifiPlatform.WIFI_STATE_DISABLED && !plat.holdParkedPower);
            check("Silent delay_mode wake enters sleep phase",
                    "sleep", Json.object(ctl.statusJson()).get("phase"));
        } finally {
            deleteRec(root);
        }
    }

    private static void testDoorWakeSquashedProjectionSoftApRestoredOnIgOn() throws Exception {
        File root = Files.createTempDirectory("hw-doorwake-ap").toFile();
        try {
            File stateDir = new File(root, "data/hmgapi/state");
            stateDir.mkdirs();
            Files.write(new File(stateDir, "wifi.txt").toPath(),
                    "projection wireless-carplay-aa\nhome hmgapi\n".getBytes("UTF-8"));
            HomeWifiStore store = new HomeWifiStore(root);
            FakeWifiPlatform plat = new FakeWifiPlatform();
            // Cold boot away from home in delay_mode (driver opened door at +17s); stock Mode 2 SoftAP is up initially
            plat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, false, false, false, 82, false, 0, 0);
            HomeWifiController ctl = new HomeWifiController(store, plat);
            ctl.start();
            check("Session stays inactive during initial delay_mode window",
                    "standby", Json.object(ctl.statusJson()).get("phase"));

            // At +18.6s OpenLink's Dark Guard squashes SoftAP before HomeWifiController enters client mode,
            // leaving SoftAP in AP_STATE_DISABLING (10) / AP_STATE_DISABLED (11)
            plat.softApActive = false;
            plat.softApStateOverride = WifiPlatform.AP_STATE_DISABLING;
            plat.lastRestoredApMode = -1;

            // At +19.9s driver presses START (delay_mode -> on)
            plat.nowMs += 2500L;
            plat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 82, false, 1, 0);
            ctl.tickVehicleAndHealth();

            check("Mode 2 projection SoftAP restored on delay_mode -> on transition",
                    Integer.valueOf(2), Integer.valueOf(plat.lastRestoredApMode));
            checkTrue("SoftAP active again for Wireless CarPlay / Android Auto", plat.softApActive);
        } finally {
            deleteRec(root);
        }
    }

    private static void testDriverEntryGraceProjectionPreStartAndBootSoftApSelfHeal() throws Exception {
        File root = Files.createTempDirectory("hw-entry-grace").toFile();
        try {
            File stateDir = new File(root, "data/hmgapi/state");
            stateDir.mkdirs();
            Files.write(new File(stateDir, "wifi.txt").toPath(),
                    "projection wireless-carplay-aa\nhome\n".getBytes("UTF-8"));
            HomeWifiStore store = new HomeWifiStore(root);
            store.upsertOwnerProfile("Garage5G", "garage-password", true, "[WPA2-PSK-CCMP][ESS]", true);

            // 1. Locked-cabin silent delay_mode wake: must NEVER enter Driver Entry Grace or fast-start projection!
            FakeWifiPlatform silentPlat = new FakeWifiPlatform();
            silentPlat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, true, false, false, 85, false, 1, 1);
            HomeWifiController silentCtl = new HomeWifiController(store, silentPlat);
            silentCtl.start();
            check("Silent locked delay_mode wake never calls fastStartProjectionStack",
                    Integer.valueOf(0), Integer.valueOf(silentPlat.fastStartCalls));
            checkTrue("Silent locked delay_mode wake never sets fast-start approval marker",
                    !silentPlat.fastStartMarkerPresent);

            // 2. Cold delay_mode boot with door open & unlocked in Park (doorsClosed=0, doorsLocked=0, gearPark=true):
            //    Enters 45s Driver Entry Grace, self-heals squashed Mode 2 SoftAP, and fast-starts projection!
            FakeWifiPlatform gracePlat = new FakeWifiPlatform();
            gracePlat.softApActive = false; // squashed at boot
            gracePlat.bootCompleted = false; // sys.boot_completed still 0 initially
            gracePlat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, true, false, false, 85, false, 0, 0);
            HomeWifiController graceCtl = new HomeWifiController(store, gracePlat);
            graceCtl.start();

            check("Driver Entry Grace keeps controller in standby even with doorsClosed=0 + gearPark=true",
                    "standby", Json.object(graceCtl.statusJson()).get("phase"));
            checkTrue("Driver Entry Grace does NOT pre-latch parked power", !gracePlat.holdParkedPower);
            check("Squashed Mode 2 SoftAP self-healed during Driver Entry Grace",
                    Integer.valueOf(2), Integer.valueOf(gracePlat.lastRestoredApMode));
            check("Projection fast-start waits while sys.boot_completed=0",
                    Integer.valueOf(0), Integer.valueOf(gracePlat.fastStartCalls));

            // 400ms later sys.boot_completed flips to 1 -> TAG_PROJ_FASTSTART fires without waiting 4s!
            gracePlat.bootCompleted = true;
            gracePlat.nowMs += 400L;
            gracePlat.fireTimer(HomeWifiController.TAG_PROJ_FASTSTART);
            check("Projection fast-start fires on 400ms poll as soon as boot_completed=1",
                    Integer.valueOf(1), Integer.valueOf(gracePlat.fastStartCalls));
            check("Fast-start reason is driver_entry_grace",
                    "driver_entry_grace", gracePlat.lastFastStartReason);
            checkTrue("Fast-start approval marker active during Driver Entry Grace",
                    gracePlat.fastStartMarkerPresent);

            // Still in grace at +25s while driver sits unlocked (doorsClosed=1, doorsLocked=0)
            gracePlat.nowMs += 25000L;
            gracePlat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, true, false, false, 85, false, 1, 0);
            graceCtl.tickVehicleAndHealth();
            check("Still in standby at +25s while driver is seated & unlocked",
                    "standby", Json.object(graceCtl.statusJson()).get("phase"));
            check("Projection fast-start remains strictly one-shot per boot",
                    Integer.valueOf(1), Integer.valueOf(gracePlat.fastStartCalls));

            // Driver exits, closes door, and locks car (doorsClosed=1, doorsLocked=1) -> immediate grace exit!
            gracePlat.nowMs += 2000L;
            gracePlat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, true, false, false, 85, false, 1, 1);
            graceCtl.tickVehicleAndHealth();
            checkTrue("Re-locking car immediately clears fast-start approval marker",
                    !gracePlat.fastStartMarkerPresent);
            checkTrue("Re-locking car immediately starts parked Home Wi-Fi session",
                    !gracePlat.softApActive);

            // 3. 45s timeout when car stays unlocked in delay_mode without IG ON
            FakeWifiPlatform timeoutPlat = new FakeWifiPlatform();
            timeoutPlat.vehicle = new WifiPlatform.VehicleSnapshot("delay_mode", false, true, false, false, 85, false, 1, 0);
            HomeWifiController timeoutCtl = new HomeWifiController(store, timeoutPlat);
            timeoutCtl.start();
            checkTrue("Unlocked cold delay_mode starts in Driver Entry Grace", timeoutPlat.softApActive);
            timeoutPlat.nowMs += 45500L;
            timeoutCtl.tickVehicleAndHealth();
            checkTrue("45s timeout clears fast-start marker and hands off to parked Home Wi-Fi",
                    !timeoutPlat.fastStartMarkerPresent && !timeoutPlat.softApActive);

            // 4. Normal power=on cold boot with squashed Mode 2 SoftAP (no false->true power transition!)
            FakeWifiPlatform onPlat = new FakeWifiPlatform();
            onPlat.softApActive = false;
            onPlat.vehicle = new WifiPlatform.VehicleSnapshot("on", false, true, false, false, 90, false, 1, 0);
            HomeWifiController onCtl = new HomeWifiController(store, onPlat);
            onCtl.start();
            check("Cold power=on boot self-heals squashed Mode 2 SoftAP immediately",
                    Integer.valueOf(2), Integer.valueOf(onPlat.lastRestoredApMode));
            check("Cold power=on boot triggers fastStartProjectionStack",
                    Integer.valueOf(1), Integer.valueOf(onPlat.fastStartCalls));
            check("Cold power=on fast-start reason is power_on", "power_on", onPlat.lastFastStartReason);
        } finally {
            deleteRec(root);
        }
    }

    private static int ipToInt(int a, int b, int c, int d) {
        return (a & 0xFF) | ((b & 0xFF) << 8) | ((c & 0xFF) << 16) | ((d & 0xFF) << 24);
    }

    private static void deleteRec(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRec(k);
        }
        f.delete();
    }

    // ---- FakeWifiPlatform for deterministic testing ----

    private static final class FakeWifiPlatform implements WifiPlatform {
        static final class NetEntry {
            final int id;
            final String ssid;
            boolean open;
            boolean enabled;
            NetEntry(int id, String ssid, boolean open, boolean enabled) {
                this.id = id;
                this.ssid = ssid;
                this.open = open;
                this.enabled = enabled;
            }
        }

        Events listener;
        long nowMs = 1000L;
        long suspendOffsetMs = 0L;
        String bootId = "boot-1";
        VehicleSnapshot vehicle = new VehicleSnapshot("on", false, true);
        boolean softApActive = true;
        int softApStateOverride = -1; // -1 = derive from softApActive (13 vs 11)
        boolean holdDisablingOnStop = false;
        int softApMode = 2;
        int lastRestoredApMode = -1;
        int wifiState = WIFI_STATE_DISABLED;
        int setWifiEnabledCalls = 0;
        boolean holdWifiLock = false;
        boolean holdParkedPower = false;
        int nextNetId = 10;
        int lastEnabledNetId = -1;
        String lastUpsertCaps = null;
        int disconnectCalls = 0;
        int reconnectCalls = 0;
        int routeUpCalls = 0;
        int routeDownCalls = 0;
        boolean adbEnabled = false;
        String adbIp = "";
        boolean lastAdbDeferTeardown = false;
        boolean bootCompleted = true;
        int fastStartCalls = 0;
        String lastFastStartReason = null;
        boolean fastStartMarkerPresent = false;

        final List<NetEntry> configured = new ArrayList<NetEntry>();
        final Set<Integer> disabledInRamIds = new LinkedHashSet<Integer>();
        final Set<Integer> removedIds = new LinkedHashSet<Integer>();
        List<HomeWifiStore.SeenNetwork> scanResults = new ArrayList<HomeWifiStore.SeenNetwork>();
        ConnectionSnapshot conn = new ConnectionSnapshot(SupState.DISCONNECTED, -1, "", "", 0, 0, 0, 0, 0, 0, 0, 0);
        final Map<String, Runnable> timers = new LinkedHashMap<String, Runnable>();

        void fireTimer(String tag) {
            Runnable r = timers.remove(tag);
            if (r != null) r.run();
        }

        File wifiPubFile = null;
        String wifiStateDuringRestoreAp = "";

        @Override public void setEventListener(Events l) { this.listener = l; }
        @Override public long elapsedRealtimeMs() { return nowMs; }
        @Override public long uptimeMillis() { return nowMs - suspendOffsetMs; }
        @Override public long wallEpochSec() { return 1700000000L + (nowMs / 1000L); }
        @Override public String bootId() { return bootId; }
        @Override public VehicleSnapshot readVehicle() { return vehicle; }
        @Override public boolean isSoftApActiveOrEnabling() { return softApActive; }
        @Override
        public int getSoftApState() {
            if (softApStateOverride >= 0) return softApStateOverride;
            return softApActive ? AP_STATE_ENABLED : AP_STATE_DISABLED;
        }
        @Override public int getSoftApMode() { return softApMode; }
        @Override public void captureSoftApConfig() {}

        @Override
        public boolean disableSoftAp(int currentMode) {
            softApActive = false;
            if (holdDisablingOnStop) {
                softApStateOverride = AP_STATE_DISABLING;
            } else if (softApStateOverride == AP_STATE_DISABLING) {
                softApStateOverride = AP_STATE_DISABLED;
            }
            return true;
        }

        @Override
        public boolean restoreSoftAp(int mode) {
            if (wifiPubFile != null && wifiPubFile.isFile()) {
                try {
                    wifiStateDuringRestoreAp = String.valueOf(Json.object(HomeWifiStore.readText(wifiPubFile)).get("state"));
                } catch (Exception ignored) {
                }
            }
            wifiState = WIFI_STATE_DISABLED;
            softApActive = true;
            softApStateOverride = -1;
            softApMode = mode;
            lastRestoredApMode = mode;
            return true;
        }

        @Override
        public void stopClientWifiOnly() {
            wifiState = WIFI_STATE_DISABLED;
        }

        @Override
        public void setSessionLocks(boolean holdWifi, boolean holdPower) {
            this.holdWifiLock = holdWifi;
            this.holdParkedPower = holdPower;
        }

        @Override public int getWifiState() { return wifiState; }

        @Override
        public boolean setWifiEnabled(boolean enabled) {
            if (enabled) setWifiEnabledCalls++;
            wifiState = enabled ? WIFI_STATE_ENABLED : WIFI_STATE_DISABLED;
            if (enabled && listener != null) {
                listener.onWifiStateChanged(WIFI_STATE_ENABLED);
            }
            return true;
        }

        @Override public boolean startScan() { return wifiState == WIFI_STATE_ENABLED; }
        @Override public List<HomeWifiStore.SeenNetwork> getScanResults() { return scanResults; }

        @Override
        public List<ConfiguredNet> getConfiguredNetworks() {
            List<ConfiguredNet> out = new ArrayList<ConfiguredNet>();
            for (NetEntry e : configured) {
                out.add(new ConfiguredNet(e.id, e.ssid, e.open, e.enabled));
            }
            return out;
        }

        @Override
        public int upsertWifiNetwork(String ssid, String psk, boolean open, String capabilities, boolean forceUpdateCredentials) {
            lastUpsertCaps = capabilities;
            for (NetEntry e : configured) {
                if (e.ssid.equalsIgnoreCase(ssid)) {
                    e.open = open;
                    e.enabled = true;
                    return e.id;
                }
            }
            int id = nextNetId++;
            configured.add(new NetEntry(id, ssid, open, true));
            return id;
        }

        @Override
        public boolean enableOnlyNetwork(int netId) {
            return enableNetwork(netId, true);
        }

        @Override
        public boolean enableNetwork(int netId, boolean disableOthers) {
            lastEnabledNetId = netId;
            for (NetEntry e : configured) {
                if (e.id == netId) e.enabled = true;
                else if (disableOthers) e.enabled = false;
            }
            return true;
        }

        @Override
        public boolean disableNetworkInRam(int netId) {
            disabledInRamIds.add(Integer.valueOf(netId));
            for (NetEntry e : configured) {
                if (e.id == netId) e.enabled = false;
            }
            return true;
        }

        @Override
        public boolean removeWifiNetwork(int netId) {
            removedIds.add(Integer.valueOf(netId));
            for (Iterator<NetEntry> it = configured.iterator(); it.hasNext(); ) {
                if (it.next().id == netId) it.remove();
            }
            return true;
        }

        @Override public boolean reconnect() { reconnectCalls++; return true; }
        @Override public boolean reassociate() { reconnectCalls++; return true; }
        @Override public boolean disconnect() { disconnectCalls++; return true; }
        @Override public ConnectionSnapshot getConnectionSnapshot() { return conn; }
        @Override public boolean routeUp(String ip, String gateway, String dns1, String dns2) { routeUpCalls++; return true; }
        @Override public void routeDown() { routeDownCalls++; adbEnabled = false; adbIp = ""; }
        @Override
        public boolean setAdbOverWifi(boolean enable, String wlanIp, boolean deferTeardown) {
            this.lastAdbDeferTeardown = deferTeardown;
            if (enable && HomeWifiRouter.validIpv4(wlanIp)) {
                this.adbEnabled = true;
                this.adbIp = wlanIp;
                return true;
            }
            this.adbEnabled = false;
            this.adbIp = "";
            return false;
        }
        @Override
        public boolean fastStartProjectionStack(String reason) {
            if (!bootCompleted) return false;
            fastStartCalls++;
            lastFastStartReason = reason;
            fastStartMarkerPresent = true;
            return true;
        }
        @Override
        public void clearProjectionFastStart() {
            fastStartMarkerPresent = false;
        }
        @Override public void scheduleDelayed(String tag, long delayMs, Runnable task) { timers.put(tag, task); }
        @Override public void cancelScheduled(String tag) { timers.remove(tag); }
        @Override public void log(String line) {}
    }
}

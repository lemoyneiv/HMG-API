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

        router.routeDown();
        String routesAfter = HomeWifiStore.readText(new File(fw, "ip4.route"));
        checkTrue("Table 95 flushed on routeDown", !routesAfter.contains("table 95"));
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
        int softApMode = 2;
        int lastRestoredApMode = -1;
        int wifiState = WIFI_STATE_DISABLED;
        int nextNetId = 10;
        int lastEnabledNetId = -1;
        String lastUpsertCaps = null;
        int disconnectCalls = 0;
        int reconnectCalls = 0;
        int routeUpCalls = 0;
        int routeDownCalls = 0;

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

        @Override public void setEventListener(Events l) { this.listener = l; }
        @Override public long elapsedRealtimeMs() { return nowMs; }
        @Override public long uptimeMillis() { return nowMs - suspendOffsetMs; }
        @Override public long wallEpochSec() { return 1700000000L + (nowMs / 1000L); }
        @Override public String bootId() { return bootId; }
        @Override public VehicleSnapshot readVehicle() { return vehicle; }
        @Override public boolean isSoftApActiveOrEnabling() { return softApActive; }
        @Override public int getSoftApMode() { return softApMode; }
        @Override public void captureSoftApConfig() {}

        @Override
        public boolean disableSoftAp(int currentMode) {
            softApActive = false;
            return true;
        }

        @Override
        public boolean restoreSoftAp(int mode) {
            wifiState = WIFI_STATE_DISABLED;
            softApActive = true;
            softApMode = mode;
            lastRestoredApMode = mode;
            return true;
        }

        @Override public int getWifiState() { return wifiState; }

        @Override
        public boolean setWifiEnabled(boolean enabled) {
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
        @Override public void routeDown() { routeDownCalls++; }
        @Override public void scheduleDelayed(String tag, long delayMs, Runnable task) { timers.put(tag, task); }
        @Override public void cancelScheduled(String tag) { timers.remove(tag); }
        @Override public void log(String line) {}
    }
}

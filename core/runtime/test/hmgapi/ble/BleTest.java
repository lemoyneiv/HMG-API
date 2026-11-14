package hmgapi.ble;

import hmgapi.client.Ble;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Off-car end-to-end test suite for {@link BleAuth}, {@link BleSession}, {@link BleCentral},
 * {@link BleGatt}, {@link BleTransport}, and {@link Ble}.
 */
public final class BleTest {
    private static int fail = 0;

    private static void check(String name, Object want, Object got) {
        if (want == null ? got == null : want.equals(got)) {
            System.out.println("ok    " + name);
        } else {
            System.out.println("FAIL  " + name + " (want: " + want + ", got: " + got + ")");
            fail = 1;
        }
    }

    private static void checkTrue(String name, boolean cond) {
        check(name, Boolean.TRUE, Boolean.valueOf(cond));
    }

    public static void main(String[] args) throws Exception {
        testBleAuth();
        testAdvRecord();
        testPipeFraming();
        testCentralAndClient();
        testBackpressureAndStalledClient();
        testAdapterDownAndRecovery();
        testSharedAclBuffersRefuseConnect();
        System.exit(fail);
    }

    // -------------------------------------------------------------------------
    // 1. BleAuth: unforgeable public/services.txt session IDs + UID package map
    // -------------------------------------------------------------------------
    private static void testBleAuth() {
        Map<String, Object> mods = new LinkedHashMap<String, Object>();

        Map<String, Object> batt = new LinkedHashMap<String, Object>();
        batt.put("enabled", Boolean.TRUE);
        batt.put("ble", Boolean.TRUE);
        batt.put("apps", Collections.singletonList("dev.example.batt"));
        mods.put("batt", batt);

        Map<String, Object> cam = new LinkedHashMap<String, Object>();
        cam.put("enabled", Boolean.TRUE);
        cam.put("ble", Boolean.FALSE);
        cam.put("apps", Collections.singletonList("dev.example.cam"));
        mods.put("cam", cam);

        Map<String, Object> offMod = new LinkedHashMap<String, Object>();
        offMod.put("enabled", Boolean.FALSE);
        offMod.put("ble", Boolean.TRUE);
        offMod.put("apps", Collections.singletonList("dev.example.off"));
        mods.put("offmod", offMod);

        BleAuth.State st = BleAuth.State.fromModsMap(mods);

        final Map<Integer, String> fakeStat = new HashMap<Integer, String>();
        // Child process 4205 spawned inside service session 4200 ("batt"), with tricky comm name
        fakeStat.put(Integer.valueOf(4205), "4205 (sh (sub)) S 4200 4200 4200 0 -1 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0");
        // Child process 5102 spawned inside root service session 5100 ("cam", no BLE declared!)
        fakeStat.put(Integer.valueOf(5102), "5102 (helper) S 5100 5100 5100 0 -1 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0");
        // Unprivileged attacker process 9001 in its own non-hmgapid session 9000
        fakeStat.put(Integer.valueOf(9001), "9001 (batt.fake) S 9000 9000 9000 0 -1 0 0 0 0 0 0 0 0 20 0 1 0 100 0 0");

        final String servicesTxt =
                "4200 batt\n" +
                "5100 cam\n" +
                "6100 offmod\n";

        BleAuth.EnvReader env = new BleAuth.EnvReader() {
            @Override
            public String readProcStat(int pid) {
                return fakeStat.get(Integer.valueOf(pid));
            }

            @Override
            public String readServices() {
                return servicesTxt;
            }
        };

        check("auth: enabled BLE mod app allowed", "batt",
                BleAuth.authorize(10042, 3001, new String[] { "dev.example.batt" }, st, env));
        check("auth: non-BLE mod app refused", null,
                BleAuth.authorize(10043, 3002, new String[] { "dev.example.cam" }, st, env));
        check("auth: disabled BLE mod app refused", null,
                BleAuth.authorize(10044, 3003, new String[] { "dev.example.off" }, st, env));
        check("auth: third-party app attempting process-name spoof refused", null,
                BleAuth.authorize(10099, 9001, new String[] { "com.evil.spoofer" }, st, env));
        check("auth: running service pid in public/services.txt allowed", "batt",
                BleAuth.authorize(1000, 4200, null, st, env));
        check("auth: child process of BLE service matched via session id", "batt",
                BleAuth.authorize(10042, 4205, null, st, env));
        check("auth: root service of mod without BLE refused", null,
                BleAuth.authorize(0, 5100, null, st, env));
        check("auth: child of root service without BLE refused", null,
                BleAuth.authorize(0, 5102, null, st, env));
        check("auth: service of disabled mod refused", null,
                BleAuth.authorize(0, 6100, null, st, env));
        check("auth: standalone root allowed", "root",
                BleAuth.authorize(0, 999, null, st, env));
        check("auth: adb shell allowed", "root",
                BleAuth.authorize(2000, 998, null, st, env));
    }

    // -------------------------------------------------------------------------
    // 2. Ble.AdvRecord: AD + Scan Response parsing
    // -------------------------------------------------------------------------
    private static void testAdvRecord() {
        byte[] ad = Ble.hexToBytes(
                "020106" +
                "0303f0ff" +
                "04160f185a" +
                "05ff4c000102" +
                "020af8" +
                "060942564d3032");
        Ble.AdvRecord rec = new Ble.AdvRecord("AA:BB:CC:11:22:33", 0, -61, ad);
        check("adv: flags", Integer.valueOf(0x06), Integer.valueOf(rec.flags));
        check("adv: localName", "BVM02", rec.localName);
        check("adv: txPower", Integer.valueOf(-8), rec.txPowerLevel);
        checkTrue("adv: 16-bit service UUID matched", rec.hasServiceUuid("fff0"));
        check("adv: service data (0x180f)", "5a", Ble.bytesToHex(rec.getServiceData("180f")));
        check("adv: manufacturer data (0x004c)", "0102", Ble.bytesToHex(rec.getManufacturerData(0x004C)));
    }

    // -------------------------------------------------------------------------
    // 3. Pipe framing round-trip
    // -------------------------------------------------------------------------
    private static void testPipeFraming() throws Exception {
        PipedOutputStream out = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(out, 4096);
        byte[] sample = BleTransport.Framing.buildAclPayload(0x0041, 0x01, new byte[] { 1, 2, 3, 4, 5 });
        BleTransport.Framing.writePacket(out, BleTransport.PKT_ACL, sample);
        BleTransport.Packet pkt = BleTransport.Framing.readPacket(in);
        check("framing: packet type", Integer.valueOf(BleTransport.PKT_ACL), Integer.valueOf(pkt.type));
        check("framing: payload bytes", Ble.bytesToHex(sample), Ble.bytesToHex(pkt.payload));
        in.close();
        out.close();
    }

    // -------------------------------------------------------------------------
    // 4. End-to-end BleCentral + Ble client + scoped LE_Host_Support restore
    // -------------------------------------------------------------------------
    private static void testCentralAndClient() throws Exception {
        final FakeController ctl = new FakeController();
        final BleCentral central = new BleCentral(ctl);

        // 4a. Unauthorized caller gets immediate IOException on Ble.attach
        {
            StreamPair sp = new StreamPair(16384);
            startServerWorker(central, sp, null);
            String err = null;
            try {
                Ble.attach(sp.clientIn, sp.clientOut, sp.clientClose, new Ble.SimpleListener());
            } catch (IOException e) {
                err = e.getMessage();
            }
            check("central: unauthorized caller refused", "ble refused: NOT_ALLOWED", err);
            sp.closeAll();
        }
        check("idle: controller untouched before first authorized session",
                Boolean.FALSE, Boolean.valueOf(ctl.leHostSupportEnabled.get()));

        // 4b. Open two authorized mod sessions ("batt" and "tpms")
        final CountDownLatch battAdvLatch = new CountDownLatch(1);
        final CountDownLatch tpmsAdvLatch = new CountDownLatch(1);
        final AtomicReference<Ble.AdvRecord> battAdv = new AtomicReference<Ble.AdvRecord>();
        final CountDownLatch battReadyLatch = new CountDownLatch(1);
        final CountDownLatch tpmsReadyLatch = new CountDownLatch(1);
        final CountDownLatch battMtuLatch = new CountDownLatch(1);
        final AtomicInteger battMtu = new AtomicInteger(0);
        final CountDownLatch battWriteLatch = new CountDownLatch(1);
        final CountDownLatch battLongReadLatch = new CountDownLatch(1);
        final AtomicReference<byte[]> battLongRead = new AtomicReference<byte[]>();
        final CountDownLatch battNotifyStateLatch = new CountDownLatch(1);
        final CountDownLatch battNotifyValLatch = new CountDownLatch(1);
        final AtomicReference<byte[]> battNotifyVal = new AtomicReference<byte[]>();
        final CountDownLatch battErrLatch = new CountDownLatch(1);
        final AtomicReference<String> battErr = new AtomicReference<String>();
        final CountDownLatch battClosedLatch = new CountDownLatch(1);
        final AtomicReference<String> battClosedReason = new AtomicReference<String>();
        final CountDownLatch tpmsPostRevokeReadLatch = new CountDownLatch(1);
        final AtomicReference<byte[]> tpmsPostRevokeRead = new AtomicReference<byte[]>();

        StreamPair spBatt = new StreamPair(16384);
        startServerWorker(central, spBatt, "batt");
        Ble bleBatt = Ble.attach(spBatt.clientIn, spBatt.clientOut, spBatt.clientClose, new Ble.SimpleListener() {
            @Override
            public void onAdvertisement(Ble.AdvRecord record) {
                if ("AA:BB:CC:11:22:33".equals(record.mac) && record.localName != null) {
                    battAdv.set(record);
                    battAdvLatch.countDown();
                }
            }

            @Override
            public void onGattReady(String mac, int mtu) {
                battReadyLatch.countDown();
            }

            @Override
            public void onMtuChanged(String mac, int mtu) {
                battMtu.set(mtu);
                battMtuLatch.countDown();
            }

            @Override
            public void onCharacteristicWrite(String mac, String charUuid) {
                battWriteLatch.countDown();
            }

            @Override
            public void onCharacteristicLongRead(String mac, String charUuid, byte[] fullValue) {
                battLongRead.set(fullValue);
                battLongReadLatch.countDown();
            }

            @Override
            public void onNotifyStateChanged(String mac, String charUuid, boolean enabled) {
                if (enabled) battNotifyStateLatch.countDown();
            }

            @Override
            public void onCharacteristicChanged(String mac, String charUuid, byte[] value) {
                battNotifyVal.set(value);
                battNotifyValLatch.countDown();
            }

            @Override
            public void onError(String mac, String error) {
                battErr.set(error);
                battErrLatch.countDown();
            }

            @Override
            public void onSessionClosed(String reason) {
                battClosedReason.set(reason);
                battClosedLatch.countDown();
            }
        });

        check("init: HCI_Write_LE_Host_Support(1, 1) enabled on active session",
                Boolean.TRUE, Boolean.valueOf(ctl.leHostSupportEnabled.get()));
        check("init: HCI_Set_Event_Mask preserves Classic bits + enables bit 61",
                "ffffffff07f8bf3d", ctl.lastEventMaskHex);

        StreamPair spTpms = new StreamPair(16384);
        startServerWorker(central, spTpms, "tpms");
        Ble bleTpms = Ble.attach(spTpms.clientIn, spTpms.clientOut, spTpms.clientClose, new Ble.SimpleListener() {
            @Override
            public void onAdvertisement(Ble.AdvRecord record) {
                if ("AA:BB:CC:11:22:33".equals(record.mac) && record.localName != null) {
                    tpmsAdvLatch.countDown();
                }
            }

            @Override
            public void onGattReady(String mac, int mtu) {
                tpmsReadyLatch.countDown();
            }

            @Override
            public void onCharacteristicRead(String mac, String charUuid, byte[] value) {
                tpmsPostRevokeRead.set(value);
                tpmsPostRevokeReadLatch.countDown();
            }
        });

        check("init: controller LE stack initialized once across two sessions",
                Integer.valueOf(1), Integer.valueOf(ctl.initCount.get()));

        bleBatt.startScan();
        bleTpms.startScan();
        waitUntilScanState(ctl, true);
        check("scan: shared hardware scan started once", Integer.valueOf(1), Integer.valueOf(ctl.scanEnableCount.get()));

        ctl.emitAdvReport(0x00, 0x00, "AA:BB:CC:11:22:33", -58, Ble.hexToBytes("0201060303f0ff"));
        ctl.emitAdvReport(0x04, 0x00, "AA:BB:CC:11:22:33", -57, Ble.hexToBytes("0b09426174746572794d6f6e"));

        checkTrue("scan: batt received merged ADV+SCAN_RSP", battAdvLatch.await(2, TimeUnit.SECONDS));
        checkTrue("scan: tpms also received shared scan report", tpmsAdvLatch.await(2, TimeUnit.SECONDS));
        check("scan: merged ADV keeps UUID from ADV_IND", Boolean.TRUE, Boolean.valueOf(battAdv.get().hasServiceUuid("fff0")));
        check("scan: merged ADV keeps name from SCAN_RSP", "BatteryMon", battAdv.get().localName);

        bleBatt.stopScan();
        Thread.sleep(60);
        checkTrue("scan: hardware scan stays on while tpms still scans", ctl.scanCurrentlyEnabled.get());
        bleTpms.stopScan();
        waitUntilScanState(ctl, false);
        checkTrue("scan: hardware scan stops when last mod stops scanning", !ctl.scanCurrentlyEnabled.get());

        // Connect both mods to two distinct peripherals simultaneously
        bleBatt.connectDevice("AA:BB:CC:11:22:33", 0, "fff0");
        bleTpms.connectDevice("DD:EE:FF:44:55:66", 1, null);
        checkTrue("gatt: batt peripheral discovered (16-bit UUIDs)", battReadyLatch.await(3, TimeUnit.SECONDS));
        checkTrue("gatt: tpms peripheral discovered (128-bit UUIDs)", tpmsReadyLatch.await(3, TimeUnit.SECONDS));
        check("gatt: 2 LE handles tracked simultaneously", Integer.valueOf(2), Integer.valueOf(ctl.trackedHandles.size()));

        // Negotiate MTU 185 on batt peripheral
        bleBatt.requestMtu("AA:BB:CC:11:22:33", 185);
        checkTrue("gatt: MTU exchange completed", battMtuLatch.await(2, TimeUnit.SECONDS));
        check("gatt: negotiated MTU 185", Integer.valueOf(185), Integer.valueOf(battMtu.get()));

        // Write 60-byte payload (verifies 27-byte outbound LE ACL fragmentation + 0x13 credit replenishment)
        byte[] writePayload = new byte[60];
        for (int i = 0; i < writePayload.length; i++) writePayload[i] = (byte) (0xA0 + i);
        ctl.aclFragmentsSeenForHandle41.set(0);
        bleBatt.writeCharacteristic("AA:BB:CC:11:22:33", "fff2", writePayload, true);
        checkTrue("gatt: 60B write succeeded over 27B LE ACL fragments", battWriteLatch.await(2, TimeUnit.SECONDS));
        check("acl: 67B L2CAP frame split into 3 x <=27B LE ACL fragments",
                Integer.valueOf(3), Integer.valueOf(ctl.aclFragmentsSeenForHandle41.get()));
        check("gatt: peripheral received exact 60B write value",
                Ble.bytesToHex(writePayload), Ble.bytesToHex(ctl.lastWrittenValue41));

        // Long characteristic read (220B across Read + Read Blob)
        bleBatt.readLongCharacteristic("AA:BB:CC:11:22:33", "fff1");
        checkTrue("gatt: READ_LONG_CHAR completed across Read + Read Blob", battLongReadLatch.await(2, TimeUnit.SECONDS));
        check("gatt: READ_LONG_CHAR reassembled all 220 bytes",
                Ble.bytesToHex(ctl.longReadData), Ble.bytesToHex(battLongRead.get()));

        // Enable notifications & receive 90-byte notification fragmented across 4 inbound 27B ACL packets
        bleBatt.enableNotifications("AA:BB:CC:11:22:33", "fff1");
        checkTrue("gatt: CCCD notification enabled", battNotifyStateLatch.await(2, TimeUnit.SECONDS));
        byte[] ntfPayload = new byte[90];
        for (int i = 0; i < ntfPayload.length; i++) ntfPayload[i] = (byte) (i + 1);
        ctl.emitFragmentedNotification(0x0041, 0x0012, ntfPayload, 27);
        checkTrue("acl: 90B inbound notification reassembled from 27B ACL fragments",
                battNotifyValLatch.await(2, TimeUnit.SECONDS));
        check("acl: reassembled notification matches", Ble.bytesToHex(ntfPayload), Ble.bytesToHex(battNotifyVal.get()));

        // L2CAP 0x0005 Connection Parameter Update Request from peripheral -> 0x2013 HCI_LE_Connection_Update
        ctl.emitConnParamUpdateReq(0x0041);
        checkTrue("l2cap: 0x0005 conn param request triggers HCI_LE_Connection_Update",
                ctl.connUpdateLatch.await(2, TimeUnit.SECONDS));

        // PROTECT_CHAR rejects write to read-only protected characteristic
        bleBatt.protectCharacteristic("AA:BB:CC:11:22:33", "fff1");
        Thread.sleep(40);
        bleBatt.writeCharacteristic("AA:BB:CC:11:22:33", "fff1", new byte[] { 0x01 }, true);
        checkTrue("gatt: write to protected char rejected", battErrLatch.await(2, TimeUnit.SECONDS));
        checkTrue("gatt: error is FORBIDDEN_READ_ONLY_CHAR",
                battErr.get() != null && battErr.get().startsWith("FORBIDDEN_READ_ONLY_CHAR"));

        // Live per-mod revocation: disable "batt" while keeping "tpms" enabled
        central.retainEnabledMods(Collections.singleton("tpms"));
        checkTrue("live: disabled mod's Ble client closed with MOD_DISABLED",
                battClosedLatch.await(2, TimeUnit.SECONDS));
        check("live: close reason is MOD_DISABLED", "MOD_DISABLED", battClosedReason.get());
        checkTrue("live: disabled mod's peripheral received HCI_Disconnect",
                ctl.disconnectHandle41Latch.await(2, TimeUnit.SECONDS));
        check("live: LE_Host_Support stays (1, 1) while tpms is still open",
                Boolean.TRUE, Boolean.valueOf(ctl.leHostSupportEnabled.get()));

        // Meanwhile, "tpms" stays connected and reads its 128-bit characteristic without interruption
        bleTpms.readCharacteristic("DD:EE:FF:44:55:66", FakeController.TPMS_CHAR_UUID);
        checkTrue("live: other enabled mod (tpms) keeps working uninterrupted",
                tpmsPostRevokeReadLatch.await(2, TimeUnit.SECONDS));
        check("live: tpms read value intact", "2402", Ble.bytesToHex(tpmsPostRevokeRead.get()));

        // Close last session -> listening goes back to false AND LE_Host_Support restored to (0, 0)!
        bleTpms.close();
        for (int i = 0; i < 60 && (ctl.listening.get() || ctl.leHostSupportEnabled.get()); i++) {
            Thread.sleep(25);
        }
        check("idle: native filter returns to pass-through when last session closes",
                Boolean.FALSE, Boolean.valueOf(ctl.listening.get()));
        check("idle: HCI_Write_LE_Host_Support(0, 0) sent when last session closes",
                Boolean.FALSE, Boolean.valueOf(ctl.leHostSupportEnabled.get()));
        check("idle: LE event mask cleared to zeros when idle",
                Boolean.TRUE, Boolean.valueOf(ctl.leEventMaskCleared.get()));
        check("idle: HCI_Set_Event_Mask clears bit 61 while preserving Classic bits",
                "ffffffff07f8bf1d", ctl.lastEventMaskHex);

        spBatt.closeAll();
        spTpms.closeAll();
    }

    // -------------------------------------------------------------------------
    // 5. Non-blocking RX & stalled client backpressure isolation
    // -------------------------------------------------------------------------
    private static void testBackpressureAndStalledClient() throws Exception {
        final FakeController ctl = new FakeController();
        final BleCentral central = new BleCentral(ctl);

        // Connect a raw "wedged" client whose s2c buffer is tiny (128 bytes) and stops reading
        StreamPair wedged = new StreamPair(128);
        startServerWorker(central, wedged, "wedged");
        BufferedWriter wedgedOut = new BufferedWriter(new OutputStreamWriter(wedged.clientOut, "UTF-8"));
        BufferedReader wedgedIn = new BufferedReader(new InputStreamReader(wedged.clientIn, "UTF-8"));
        wedgedOut.write("STREAM_SESSION\n");
        wedgedOut.flush();
        check("backpressure: wedged handshake", "OK STREAM_READY", wedgedIn.readLine());
        check("backpressure: wedged idle", "STATE IDLE", wedgedIn.readLine());
        wedgedOut.write("SCAN_START\n");
        wedgedOut.write("CONNECT AA:BB:CC:11:22:33 0 fff0\n");
        wedgedOut.flush();
        waitUntilScanState(ctl, true);
        // Wait for wedged client's GATT discovery + enable notifications, draining just enough to reach READY
        String l;
        while ((l = wedgedIn.readLine()) != null) {
            if (l.startsWith("STATE READY")) break;
        }
        wedgedOut.write("ENABLE_NOTIFY fff1\n");
        wedgedOut.flush();
        while ((l = wedgedIn.readLine()) != null) {
            if (l.startsWith("NOTIFY_STATE")) break;
        }
        // Now STOP reading wedgedIn completely so wedged's socket buffer fills and stalls its writer!

        // Connect a second healthy client ("healthy") scanning & reading GATT concurrently
        final CountDownLatch healthyReady = new CountDownLatch(1);
        final CountDownLatch healthyRead = new CountDownLatch(1);
        final AtomicInteger healthyAdvs = new AtomicInteger(0);
        StreamPair healthySp = new StreamPair(16384);
        startServerWorker(central, healthySp, "healthy");
        Ble healthy = Ble.attach(healthySp.clientIn, healthySp.clientOut, healthySp.clientClose, new Ble.SimpleListener() {
            @Override
            public void onAdvertisement(Ble.AdvRecord record) {
                healthyAdvs.incrementAndGet();
            }

            @Override
            public void onGattReady(String mac, int mtu) {
                healthyReady.countDown();
            }

            @Override
            public void onCharacteristicRead(String mac, String charUuid, byte[] value) {
                healthyRead.countDown();
            }
        });
        healthy.startScan();
        healthy.connectDevice("DD:EE:FF:44:55:66", 1, null);
        checkTrue("backpressure: healthy client GATT ready", healthyReady.await(3, TimeUnit.SECONDS));

        // Blast 1,000 ADV reports synchronously into central.onPacket (simulating Bluedroid RX pump)
        byte[] ad = Ble.hexToBytes("020106060942564d3032");
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            ctl.emitAdvReport(0x00, 0x00, "11:22:33:44:55:66", -60, ad);
        }
        long elapsedMs = System.currentTimeMillis() - t0;
        checkTrue("backpressure: 1,000 ADV reports dispatched without stalling RX (<1000 ms, took " + elapsedMs + " ms)",
                elapsedMs < 1000);

        // Healthy client still reads GATT immediately while wedged client's writer is blocked
        healthy.readCharacteristic("DD:EE:FF:44:55:66", FakeController.TPMS_CHAR_UUID);
        checkTrue("backpressure: healthy client GATT read succeeds while peer is wedged",
                healthyRead.await(2, TimeUnit.SECONDS));
        checkTrue("backpressure: healthy client received advertisements", healthyAdvs.get() > 0);
        checkTrue("backpressure: wedged client dropped excess ADVs cleanly",
                central.buildStatusSummary().contains("active_sessions=2"));

        // Now blast 300 non-ADV GATT notifications to wedged (> QUEUE_CAP=256) -> wedged must overflow & disconnect!
        byte[] ntf = new byte[] { 0x11, 0x22, 0x33, 0x44 };
        for (int i = 0; i < 300; i++) {
            ctl.emitFragmentedNotification(0x0041, 0x0012, ntf, 27);
        }
        checkTrue("backpressure: wedged session disconnected on non-ADV queue overflow",
                ctl.disconnectHandle41Latch.await(3, TimeUnit.SECONDS));

        healthy.close();
        for (int i = 0; i < 60 && ctl.listening.get(); i++) Thread.sleep(25);
        wedged.closeAll();
        healthySp.closeAll();
    }

    // -------------------------------------------------------------------------
    // 6. Bluetooth adapter off/on recovery (onAdapterDown + clean re-init)
    // -------------------------------------------------------------------------
    private static void testAdapterDownAndRecovery() throws Exception {
        final FakeController ctl = new FakeController();
        final BleCentral central = new BleCentral(ctl);

        final CountDownLatch ready1 = new CountDownLatch(1);
        final CountDownLatch disconnected1 = new CountDownLatch(1);
        final CountDownLatch closed1 = new CountDownLatch(1);
        final AtomicReference<String> closeReason1 = new AtomicReference<String>();

        StreamPair sp1 = new StreamPair(16384);
        startServerWorker(central, sp1, "batt");
        Ble ble1 = Ble.attach(sp1.clientIn, sp1.clientOut, sp1.clientClose, new Ble.SimpleListener() {
            @Override
            public void onGattReady(String mac, int mtu) {
                ready1.countDown();
            }

            @Override
            public void onConnectionStateChanged(String mac, boolean connected, int handleOrReason) {
                if (!connected) disconnected1.countDown();
            }

            @Override
            public void onSessionClosed(String reason) {
                closeReason1.set(reason);
                closed1.countDown();
            }
        });
        ble1.connectDevice("AA:BB:CC:11:22:33", 0, "fff0");
        checkTrue("bt-off: initial GATT connection ready", ready1.await(3, TimeUnit.SECONDS));

        // Simulate Bluetooth turning off
        central.onAdapterDown();
        checkTrue("bt-off: open link notified disconnected", disconnected1.await(2, TimeUnit.SECONDS));
        checkTrue("bt-off: open session closed on adapter down", closed1.await(2, TimeUnit.SECONDS));
        check("bt-off: close reason is BT_OFF", "BT_OFF", closeReason1.get());
        check("bt-off: transport.shutdown() called", Integer.valueOf(1), Integer.valueOf(ctl.shutdownCount.get()));
        check("bt-off: transport marked not ready", Boolean.FALSE, Boolean.valueOf(ctl.isReady()));
        sp1.closeAll();

        // Simulate Bluetooth turned back on: next session must re-attach & re-init controller LE stack
        final CountDownLatch ready2 = new CountDownLatch(1);
        StreamPair sp2 = new StreamPair(16384);
        startServerWorker(central, sp2, "batt");
        Ble ble2 = Ble.attach(sp2.clientIn, sp2.clientOut, sp2.clientClose, new Ble.SimpleListener() {
            @Override
            public void onGattReady(String mac, int mtu) {
                ready2.countDown();
            }
        });
        ble2.connectDevice("AA:BB:CC:11:22:33", 0, "fff0");
        checkTrue("bt-on: GATT connection succeeds after adapter restart", ready2.await(3, TimeUnit.SECONDS));
        check("bt-on: controller LE stack re-initialized after restart",
                Integer.valueOf(2), Integer.valueOf(ctl.initCount.get()));
        check("bt-on: HCI_Write_LE_Host_Support(1, 1) re-enabled",
                Boolean.TRUE, Boolean.valueOf(ctl.leHostSupportEnabled.get()));

        ble2.close();
        for (int i = 0; i < 60 && ctl.listening.get(); i++) Thread.sleep(25);
        sp2.closeAll();
    }

    // -------------------------------------------------------------------------
    // 7. Shared LE/Classic ACL buffers (LE_Read_Buffer_Size == 0): scan OK, connect refused
    // -------------------------------------------------------------------------
    private static void testSharedAclBuffersRefuseConnect() throws Exception {
        final FakeController ctl = new FakeController();
        ctl.sharedAclBuffers = true;
        ctl.bt40Only = true;
        final BleCentral central = new BleCentral(ctl);

        final CountDownLatch advLatch = new CountDownLatch(1);
        final CountDownLatch errLatch = new CountDownLatch(1);
        final AtomicReference<String> connectErr = new AtomicReference<String>();

        StreamPair sp = new StreamPair(16384);
        startServerWorker(central, sp, "batt");
        Ble ble = Ble.attach(sp.clientIn, sp.clientOut, sp.clientClose, new Ble.SimpleListener() {
            @Override
            public void onAdvertisement(Ble.AdvRecord record) {
                advLatch.countDown();
            }

            @Override
            public void onError(String mac, String error) {
                connectErr.set(error);
                errLatch.countDown();
            }
        });

        check("bt40: falls back to BT 4.0 LE event mask when 4.2 mask rejected",
                "1f00000000000000", ctl.lastLeEventMaskHex);

        ble.startScan();
        waitUntilScanState(ctl, true);
        ctl.emitAdvReport(0x00, 0x00, "AA:BB:CC:11:22:33", -55, Ble.hexToBytes("020106"));
        checkTrue("shared-buf: scanning still works without dedicated LE ACL buffers",
                advLatch.await(2, TimeUnit.SECONDS));

        ble.connectDevice("AA:BB:CC:11:22:33", 0, "fff0");
        checkTrue("shared-buf: CONNECT refused immediately", errLatch.await(2, TimeUnit.SECONDS));
        check("shared-buf: error is SHARED_ACL_BUFFERS_UNSUPPORTED",
                "SHARED_ACL_BUFFERS_UNSUPPORTED", connectErr.get());
        check("shared-buf: HCI_LE_Create_Connection never sent",
                Integer.valueOf(0), Integer.valueOf(ctl.createConnCount.get()));

        ble.close();
        for (int i = 0; i < 60 && ctl.listening.get(); i++) Thread.sleep(25);
        sp.closeAll();
    }

    private static void waitUntilScanState(FakeController ctl, boolean want) throws InterruptedException {
        for (int i = 0; i < 40 && ctl.scanCurrentlyEnabled.get() != want; i++) {
            Thread.sleep(25);
        }
    }

    private static void startServerWorker(final BleCentral central, final StreamPair sp, final String owner) {
        Thread t = new Thread("test-ble-server") {
            @Override
            public void run() {
                try {
                    central.serve(sp.serverIn, sp.serverOut, sp.serverClose, owner);
                } catch (IOException ignored) {
                } finally {
                    try { sp.serverClose.close(); } catch (IOException ignored) {}
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    private static final class QueuePipe {
        private final byte[] buf;
        private int head = 0, tail = 0, count = 0;
        private boolean closed = false;

        QueuePipe(int capacity) {
            this.buf = new byte[capacity];
        }

        final java.io.InputStream in = new java.io.InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : (one[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                synchronized (QueuePipe.this) {
                    while (count == 0 && !closed) {
                        try {
                            QueuePipe.this.wait();
                        } catch (InterruptedException e) {
                            throw new IOException("interrupted");
                        }
                    }
                    if (count == 0) return -1;
                    int n = Math.min(len, count);
                    for (int i = 0; i < n; i++) {
                        b[off + i] = buf[head];
                        head = (head + 1) % buf.length;
                    }
                    count -= n;
                    QueuePipe.this.notifyAll();
                    return n;
                }
            }

            @Override
            public void close() {
                synchronized (QueuePipe.this) {
                    closed = true;
                    QueuePipe.this.notifyAll();
                }
            }
        };

        final java.io.OutputStream out = new java.io.OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] { (byte) b }, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                int pos = 0;
                synchronized (QueuePipe.this) {
                    while (pos < len) {
                        if (closed) throw new IOException("pipe closed");
                        while (count == buf.length && !closed) {
                            try {
                                QueuePipe.this.wait();
                            } catch (InterruptedException e) {
                                throw new IOException("interrupted");
                            }
                        }
                        if (closed) throw new IOException("pipe closed");
                        int space = buf.length - count;
                        int n = Math.min(len - pos, space);
                        for (int i = 0; i < n; i++) {
                            buf[tail] = b[off + pos + i];
                            tail = (tail + 1) % buf.length;
                        }
                        count += n;
                        pos += n;
                        QueuePipe.this.notifyAll();
                    }
                }
            }

            @Override
            public void close() {
                synchronized (QueuePipe.this) {
                    closed = true;
                    QueuePipe.this.notifyAll();
                }
            }
        };
    }

    private static final class StreamPair {
        final java.io.InputStream clientIn;
        final java.io.OutputStream clientOut;
        final java.io.InputStream serverIn;
        final java.io.OutputStream serverOut;
        final Closeable clientClose;
        final Closeable serverClose;

        StreamPair(int s2cCapacity) {
            final QueuePipe c2s = new QueuePipe(16384);
            final QueuePipe s2c = new QueuePipe(s2cCapacity);
            clientOut = c2s.out;
            serverIn = c2s.in;
            serverOut = s2c.out;
            clientIn = s2c.in;
            clientClose = new Closeable() {
                @Override
                public void close() throws IOException {
                    clientOut.close();
                    clientIn.close();
                }
            };
            serverClose = new Closeable() {
                @Override
                public void close() throws IOException {
                    serverOut.close();
                    serverIn.close();
                }
            };
        }

        void closeAll() {
            try { clientClose.close(); } catch (IOException ignored) {}
            try { serverClose.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Simulated Broadcom HCI/ACL Controller + Two GATT Peripherals
    // -------------------------------------------------------------------------
    private static final class FakeController implements BleTransport {
        static final String TPMS_SVC_UUID  = "12345678-1234-5678-1234-56789abcdef0";
        static final String TPMS_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef1";

        volatile Receiver receiver;
        volatile boolean sharedAclBuffers = false;
        volatile boolean bt40Only = false;
        volatile String lastEventMaskHex = "";
        volatile String lastLeEventMaskHex = "";
        final AtomicBoolean ready = new AtomicBoolean(false);
        final AtomicBoolean listening = new AtomicBoolean(false);
        final AtomicBoolean leHostSupportEnabled = new AtomicBoolean(false);
        final AtomicBoolean leEventMaskCleared = new AtomicBoolean(false);
        final AtomicInteger initCount = new AtomicInteger(0);
        final AtomicInteger shutdownCount = new AtomicInteger(0);
        final AtomicInteger createConnCount = new AtomicInteger(0);
        final AtomicInteger scanEnableCount = new AtomicInteger(0);
        final AtomicBoolean scanCurrentlyEnabled = new AtomicBoolean(false);
        final Set<Integer> trackedHandles = Collections.synchronizedSet(new HashSet<Integer>());
        final AtomicInteger aclFragmentsSeenForHandle41 = new AtomicInteger(0);
        final CountDownLatch connUpdateLatch = new CountDownLatch(1);
        final CountDownLatch disconnectHandle41Latch = new CountDownLatch(1);

        final byte[] longReadData = new byte[220];
        volatile byte[] lastWrittenValue41;
        private final Map<Integer, ByteArrayOutputStream> txReassembly = new HashMap<Integer, ByteArrayOutputStream>();
        private final Map<Integer, Integer> txExpectedLen = new HashMap<Integer, Integer>();
        private final Map<Integer, Integer> txCid = new HashMap<Integer, Integer>();

        FakeController() {
            for (int i = 0; i < longReadData.length; i++) longReadData[i] = (byte) ((i * 7) & 0xFF);
        }

        @Override
        public boolean ensureReady(Receiver r) {
            this.receiver = r;
            this.ready.set(true);
            return true;
        }

        @Override
        public boolean isReady() {
            return ready.get();
        }

        @Override
        public void shutdown() {
            ready.set(false);
            listening.set(false);
            trackedHandles.clear();
            shutdownCount.incrementAndGet();
        }

        private final java.util.concurrent.ExecutorService ctlWire =
                java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "fake-hci-wire");
                        t.setDaemon(true);
                        return t;
                    }
                });

        @Override
        public void sendCommand(final int opcode, final byte[] params) {
            final byte[] copy = params == null ? new byte[0] : Arrays.copyOf(params, params.length);
            ctlWire.execute(new Runnable() {
                @Override
                public void run() {
                    handleCommand(opcode, copy);
                }
            });
        }

        @Override
        public void sendAcl(final int connHandle, final int pbFlag, final byte[] chunk) {
            if (connHandle == 0x0041) aclFragmentsSeenForHandle41.incrementAndGet();
            final byte[] copy = chunk == null ? new byte[0] : Arrays.copyOf(chunk, chunk.length);
            ctlWire.execute(new Runnable() {
                @Override
                public void run() {
                    emitEvent(new byte[] {
                        0x13, 0x05, 0x01,
                        (byte) (connHandle & 0xFF), (byte) ((connHandle >> 8) & 0xFF),
                        0x01, 0x00
                    });
                    handleOutboundAclFragment(connHandle, pbFlag, copy);
                }
            });
        }

        @Override
        public void setListening(boolean enable) {
            listening.set(enable);
        }

        @Override
        public void trackHandle(int connHandle) {
            trackedHandles.add(Integer.valueOf(connHandle));
        }

        @Override
        public void untrackHandle(int connHandle) {
            trackedHandles.remove(Integer.valueOf(connHandle));
        }

        @Override
        public void clearHandles() {
            trackedHandles.clear();
        }

        @Override
        public int stat(int which) {
            if (which == STAT_HOOK_INSTALLED || which == STAT_TX_THREAD) return ready.get() ? 1 : 0;
            if (which == STAT_LISTENING) return listening.get() ? 1 : 0;
            if (which == STAT_LE_HANDLES) return trackedHandles.size();
            return 0;
        }

        @Override
        public void log(int priority, String message) {}

        void emitEvent(byte[] evt) {
            Receiver r = receiver;
            if (r != null) r.onPacket(PKT_EVT, evt);
        }

        void emitCommandComplete(int opcode, byte[] returnParams) {
            byte[] evt = new byte[5 + returnParams.length];
            evt[0] = 0x0E;
            evt[1] = (byte) (3 + returnParams.length);
            evt[2] = 0x01;
            evt[3] = (byte) (opcode & 0xFF);
            evt[4] = (byte) ((opcode >> 8) & 0xFF);
            System.arraycopy(returnParams, 0, evt, 5, returnParams.length);
            emitEvent(evt);
        }

        void emitCommandStatus(int opcode, int status) {
            emitEvent(new byte[] {
                0x0F, 0x04, (byte) status, 0x01,
                (byte) (opcode & 0xFF), (byte) ((opcode >> 8) & 0xFF)
            });
        }

        void emitAdvReport(int evtType, int addrType, String mac, int rssi, byte[] ad) {
            byte[] macLe = macLe(mac);
            byte[] evt = new byte[4 + 9 + ad.length + 1];
            evt[0] = 0x3E;
            evt[1] = (byte) (evt.length - 2);
            evt[2] = 0x02;
            evt[3] = 0x01;
            evt[4] = (byte) evtType;
            evt[5] = (byte) addrType;
            System.arraycopy(macLe, 0, evt, 6, 6);
            evt[12] = (byte) ad.length;
            System.arraycopy(ad, 0, evt, 13, ad.length);
            evt[13 + ad.length] = (byte) rssi;
            emitEvent(evt);
        }

        void emitFragmentedNotification(int connHandle, int valHandle, byte[] value, int maxAclLen) {
            byte[] att = new byte[3 + value.length];
            att[0] = 0x1B;
            att[1] = (byte) (valHandle & 0xFF);
            att[2] = (byte) ((valHandle >> 8) & 0xFF);
            System.arraycopy(value, 0, att, 3, value.length);
            emitL2capToCentral(connHandle, 0x0004, att, maxAclLen);
        }

        void emitConnParamUpdateReq(int connHandle) {
            byte[] sig = new byte[] {
                0x12, 0x07, 0x08, 0x00,
                0x18, 0x00, 0x28, 0x00, 0x00, 0x00, (byte) 0x90, 0x01
            };
            emitL2capToCentral(connHandle, 0x0005, sig, 27);
        }

        private void emitL2capToCentral(int connHandle, int cid, byte[] pdu, int maxAclLen) {
            byte[] l2cap = new byte[4 + pdu.length];
            l2cap[0] = (byte) (pdu.length & 0xFF);
            l2cap[1] = (byte) ((pdu.length >> 8) & 0xFF);
            l2cap[2] = (byte) (cid & 0xFF);
            l2cap[3] = (byte) ((cid >> 8) & 0xFF);
            System.arraycopy(pdu, 0, l2cap, 4, pdu.length);

            int off = 0;
            boolean first = true;
            while (off < l2cap.length) {
                int n = Math.min(maxAclLen, l2cap.length - off);
                int pb = first ? 0x02 : 0x01;
                byte[] acl = BleTransport.Framing.buildAclPayload(connHandle, pb,
                        Arrays.copyOfRange(l2cap, off, off + n));
                Receiver r = receiver;
                if (r != null) r.onPacket(PKT_ACL, acl);
                first = false;
                off += n;
            }
        }

        private void handleCommand(int opcode, byte[] params) {
            if (opcode == BleCentral.HCI_SET_EVENT_MASK && params.length >= 8) {
                lastEventMaskHex = Ble.bytesToHex(params);
                emitCommandComplete(opcode, new byte[] { 0x00 });
                return;
            }
            if (opcode == BleCentral.HCI_WRITE_LE_HOST_SUPPORT && params.length >= 2) {
                leHostSupportEnabled.set(params[0] != 0);
                emitCommandComplete(opcode, new byte[] { 0x00 });
                return;
            }
            if (opcode == BleCentral.HCI_LE_SET_EVENT_MASK && params.length >= 8) {
                if (bt40Only && (params[1] != 0 || (params[0] & 0xE0) != 0)) {
                    emitCommandComplete(opcode, new byte[] { 0x12 }); // Invalid HCI Command Parameters
                    return;
                }
                boolean allZero = true;
                for (int i = 0; i < 8; i++) {
                    if (params[i] != 0) allZero = false;
                }
                leEventMaskCleared.set(allZero);
                if (!allZero) lastLeEventMaskHex = Ble.bytesToHex(params);
                emitCommandComplete(opcode, new byte[] { 0x00 });
                return;
            }
            if (opcode == BleCentral.HCI_LE_READ_BUFFER_SIZE) {
                initCount.incrementAndGet();
                if (sharedAclBuffers) {
                    emitCommandComplete(opcode, new byte[] { 0x00, 0x00, 0x00, 0x00 });
                } else {
                    emitCommandComplete(opcode, new byte[] { 0x00, 0x1B, 0x00, 0x04 });
                }
                return;
            }
            if (opcode == BleCentral.HCI_LE_SET_SCAN_ENABLE && params.length >= 1) {
                boolean on = params[0] != 0;
                if (on) scanEnableCount.incrementAndGet();
                scanCurrentlyEnabled.set(on);
                emitCommandComplete(opcode, new byte[] { 0x00 });
                return;
            }
            if (opcode == BleCentral.HCI_LE_CREATE_CONN && params.length >= 12) {
                createConnCount.incrementAndGet();
                emitCommandStatus(opcode, 0x00);
                int addrType = params[5] & 0xFF;
                byte[] peerLe = Arrays.copyOfRange(params, 6, 12);
                int handle = (peerLe[5] == (byte) 0xAA) ? 0x0041 : 0x0042;
                byte[] connEvt = new byte[21];
                connEvt[0] = 0x3E;
                connEvt[1] = 19;
                connEvt[2] = 0x01;
                connEvt[3] = 0x00;
                connEvt[4] = (byte) (handle & 0xFF);
                connEvt[5] = (byte) ((handle >> 8) & 0xFF);
                connEvt[6] = 0x00;
                connEvt[7] = (byte) addrType;
                System.arraycopy(peerLe, 0, connEvt, 8, 6);
                connEvt[14] = 0x10;
                connEvt[18] = (byte) 0x90;
                connEvt[19] = 0x01;
                emitEvent(connEvt);
                return;
            }
            if (opcode == BleCentral.HCI_LE_CONN_UPDATE) {
                emitCommandStatus(opcode, 0x00);
                connUpdateLatch.countDown();
                return;
            }
            if (opcode == BleCentral.HCI_DISCONNECT && params.length >= 3) {
                int h = (params[0] & 0xFF) | ((params[1] & 0xFF) << 8);
                emitCommandStatus(opcode, 0x00);
                if (h == 0x0041) disconnectHandle41Latch.countDown();
                emitEvent(new byte[] {
                    0x05, 0x04, 0x00,
                    (byte) (h & 0xFF), (byte) ((h >> 8) & 0xFF),
                    params[2]
                });
                return;
            }
            emitCommandComplete(opcode, new byte[] { 0x00 });
        }

        private synchronized void handleOutboundAclFragment(int connHandle, int pbFlag, byte[] chunk) {
            if (pbFlag == 0x00) {
                if (chunk.length < 4) return;
                int len = (chunk[0] & 0xFF) | ((chunk[1] & 0xFF) << 8);
                int cid = (chunk[2] & 0xFF) | ((chunk[3] & 0xFF) << 8);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bos.write(chunk, 4, chunk.length - 4);
                if (bos.size() >= len) {
                    handlePeripheralL2cap(connHandle, cid, Arrays.copyOf(bos.toByteArray(), len));
                } else {
                    txReassembly.put(connHandle, bos);
                    txExpectedLen.put(connHandle, len);
                    txCid.put(connHandle, cid);
                }
            } else if (pbFlag == 0x01) {
                ByteArrayOutputStream bos = txReassembly.get(connHandle);
                if (bos == null) return;
                bos.write(chunk, 0, chunk.length);
                int want = txExpectedLen.get(connHandle).intValue();
                if (bos.size() >= want) {
                    int cid = txCid.get(connHandle).intValue();
                    txReassembly.remove(connHandle);
                    txExpectedLen.remove(connHandle);
                    txCid.remove(connHandle);
                    handlePeripheralL2cap(connHandle, cid, Arrays.copyOf(bos.toByteArray(), want));
                }
            }
        }

        private void handlePeripheralL2cap(int connHandle, int cid, byte[] pdu) {
            if (cid != 0x0004 || pdu.length < 1) return;
            int op = pdu[0] & 0xFF;

            if (op == 0x02) {
                emitL2capToCentral(connHandle, 0x0004, new byte[] { 0x03, (byte) 185, 0x00 }, 27);
                return;
            }

            if (connHandle == 0x0041) {
                handlePeripheral41Att(op, pdu);
            } else if (connHandle == 0x0042) {
                handlePeripheral42Att(op, pdu);
            }
        }

        private void handlePeripheral41Att(int op, byte[] pdu) {
            int start = pdu.length >= 3 ? ((pdu[1] & 0xFF) | ((pdu[2] & 0xFF) << 8)) : 0;
            if (op == 0x10) {
                if (start <= 0x0010) {
                    emitL2capToCentral(0x0041, 0x0004, new byte[] {
                        0x11, 0x06,
                        0x10, 0x00, 0x15, 0x00, (byte) 0xF0, (byte) 0xFF
                    }, 27);
                } else {
                    emitAttError(0x0041, op, start, 0x0A);
                }
            } else if (op == 0x08) {
                if (start <= 0x0011) {
                    emitL2capToCentral(0x0041, 0x0004, new byte[] {
                        0x09, 0x07,
                        0x11, 0x00, 0x12, 0x12, 0x00, (byte) 0xF1, (byte) 0xFF,
                        0x14, 0x00, 0x0C, 0x15, 0x00, (byte) 0xF2, (byte) 0xFF
                    }, 27);
                } else {
                    emitAttError(0x0041, op, start, 0x0A);
                }
            } else if (op == 0x04) {
                if (start <= 0x0013) {
                    emitL2capToCentral(0x0041, 0x0004, new byte[] {
                        0x05, 0x01, 0x13, 0x00, 0x02, 0x29
                    }, 27);
                } else {
                    emitAttError(0x0041, op, start, 0x0A);
                }
            } else if (op == 0x0A) {
                int len = Math.min(184, longReadData.length);
                byte[] rsp = new byte[1 + len];
                rsp[0] = 0x0B;
                System.arraycopy(longReadData, 0, rsp, 1, len);
                emitL2capToCentral(0x0041, 0x0004, rsp, 27);
            } else if (op == 0x0C && pdu.length >= 5) {
                int off = (pdu[3] & 0xFF) | ((pdu[4] & 0xFF) << 8);
                int rem = Math.max(0, Math.min(184, longReadData.length - off));
                byte[] rsp = new byte[1 + rem];
                rsp[0] = 0x0D;
                if (rem > 0) System.arraycopy(longReadData, off, rsp, 1, rem);
                emitL2capToCentral(0x0041, 0x0004, rsp, 27);
            } else if (op == 0x12 && pdu.length >= 3) {
                if (start == 0x0015) {
                    lastWrittenValue41 = Arrays.copyOfRange(pdu, 3, pdu.length);
                }
                emitL2capToCentral(0x0041, 0x0004, new byte[] { 0x13 }, 27);
            }
        }

        private void handlePeripheral42Att(int op, byte[] pdu) {
            int start = pdu.length >= 3 ? ((pdu[1] & 0xFF) | ((pdu[2] & 0xFF) << 8)) : 0;
            if (op == 0x10) {
                if (start <= 0x0020) {
                    byte[] rsp = new byte[22];
                    rsp[0] = 0x11; rsp[1] = 20;
                    rsp[2] = 0x20; rsp[3] = 0x00; rsp[4] = 0x22; rsp[5] = 0x00;
                    System.arraycopy(uuid128Le(TPMS_SVC_UUID), 0, rsp, 6, 16);
                    emitL2capToCentral(0x0042, 0x0004, rsp, 27);
                } else {
                    emitAttError(0x0042, op, start, 0x0A);
                }
            } else if (op == 0x08) {
                if (start <= 0x0021) {
                    byte[] rsp = new byte[23];
                    rsp[0] = 0x09; rsp[1] = 21;
                    rsp[2] = 0x21; rsp[3] = 0x00; rsp[4] = 0x02; rsp[5] = 0x22; rsp[6] = 0x00;
                    System.arraycopy(uuid128Le(TPMS_CHAR_UUID), 0, rsp, 7, 16);
                    emitL2capToCentral(0x0042, 0x0004, rsp, 27);
                } else {
                    emitAttError(0x0042, op, start, 0x0A);
                }
            } else if (op == 0x0A) {
                emitL2capToCentral(0x0042, 0x0004, new byte[] { 0x0B, 0x24, 0x02 }, 27);
            }
        }

        private void emitAttError(int connHandle, int reqOp, int handle, int errCode) {
            emitL2capToCentral(connHandle, 0x0004, new byte[] {
                0x01, (byte) reqOp, (byte) (handle & 0xFF), (byte) ((handle >> 8) & 0xFF), (byte) errCode
            }, 27);
        }

        private static byte[] macLe(String mac) {
            String[] p = mac.split(":");
            byte[] b = new byte[6];
            for (int i = 0; i < 6; i++) b[5 - i] = (byte) Integer.parseInt(p[i], 16);
            return b;
        }

        private static byte[] uuid128Le(String uuid) {
            byte[] be = Ble.hexToBytes(uuid.replace("-", ""));
            byte[] le = new byte[16];
            for (int i = 0; i < 16; i++) le[i] = be[15 - i];
            return le;
        }
    }
}

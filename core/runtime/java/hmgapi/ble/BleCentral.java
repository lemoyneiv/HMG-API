package hmgapi.ble;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BLE scanner and GATT central coordinator inside {@code com.android.bluetooth}.
 *
 * Pure Java (no {@code android.*} dependencies): speaks HCI/ACL through {@link BleTransport}
 * and line-oriented text protocol over client streams.
 *
 * Coexistence invariants:
 *   - {@link #onPacket} never blocks on client sockets, HCI commands, ATT requests, or
 *     session locks;
 *   - {@code HCI_Write_LE_Host_Support(1, 1)} and the LE event mask are enabled only while
 *     at least one BLE session or peripheral link is active, and restored to {@code (0, 0)}
 *     as soon as the last session and link close;
 *   - if {@code HCI_LE_Read_Buffer_Size} reports {@code 0} (shared LE/Classic ACL buffers),
 *     scanning stays available while {@code CONNECT} is refused with
 *     {@code ERR SHARED_ACL_BUFFERS_UNSUPPORTED};
 *   - {@link #onAdapterDown} resets controller state, wakes pending latches, notifies open
 *     sessions with {@code STATE BT_OFF}, and detaches the native transport so a subsequent
 *     Bluetooth power-on re-attaches cleanly.
 */
public final class BleCentral implements BleTransport.Receiver, BleGatt.HciCommandSender, BleSession.OverflowHandler {
    public static final String SOCKET = "hmgapi-ble";

    public static final int HCI_DISCONNECT            = 0x0406;
    public static final int HCI_SET_EVENT_MASK        = 0x0C01;
    public static final int HCI_WRITE_LE_HOST_SUPPORT = 0x0C6D;
    public static final int HCI_LE_SET_EVENT_MASK     = 0x2001;
    public static final int HCI_LE_READ_BUFFER_SIZE   = 0x2002;
    public static final int HCI_LE_SET_SCAN_PARAMS    = 0x200B;
    public static final int HCI_LE_SET_SCAN_ENABLE    = 0x200C;
    public static final int HCI_LE_CREATE_CONN        = 0x200D;
    public static final int HCI_LE_CREATE_CONN_CANCEL = 0x200E;
    public static final int HCI_LE_CONN_UPDATE        = 0x2013;

    private static final int MAX_CONCURRENT_CONNECTIONS = 4;
    private static final long ADV_MERGE_TTL_MS = 2500L;

    private final BleTransport transport;
    private final BleGatt gatt;

    private final Object stateLock = new Object();
    private final Object cmdSyncLock = new Object();
    private final Object connectInitLock = new Object();
    private final ThreadLocal<Boolean> inPacketCallback = new ThreadLocal<Boolean>();

    private final ExecutorService idleExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "hmgapi-ble-idle");
            t.setDaemon(true);
            return t;
        }
    });

    private volatile boolean leStackInitialized = false;

    private volatile int waitingCmdOpcode = -1;
    private volatile byte[] waitingCmdResult = null;
    private volatile CountDownLatch waitingCmdLatch = null;

    private final Set<BleSession> scanSubscribers = new CopyOnWriteArraySet<BleSession>();
    private volatile boolean hardwareScanEnabled = false;
    private volatile boolean pausedScanForConnect = false;
    private final Map<String, Integer> seenAddrTypes = new ConcurrentHashMap<String, Integer>();
    private final Map<String, AdvCacheEntry> advCache = new ConcurrentHashMap<String, AdvCacheEntry>();

    private volatile String pendingConnectMac = null;
    private volatile CountDownLatch pendingConnectLatch = null;
    private volatile int pendingConnectResultHandle = -1;
    private volatile int pendingConnectStatus = -1;

    private final Map<String, BleGatt.Link> linksByMac = new ConcurrentHashMap<String, BleGatt.Link>();
    private final Map<Integer, BleGatt.Link> linksByHandle = new ConcurrentHashMap<Integer, BleGatt.Link>();
    private final CopyOnWriteArrayList<BleSession> activeSessions = new CopyOnWriteArrayList<BleSession>();
    private final AtomicInteger sessionIdSeq = new AtomicInteger(1);

    private static final class AdvCacheEntry {
        byte[] connectableAd;
        long updatedMs;
    }

    public BleCentral(BleTransport transport) {
        this.transport = transport;
        this.gatt = new BleGatt(transport, this);
    }

    private void info(String msg) {
        transport.log(4, "ble: " + msg);
    }

    private void warn(String msg) {
        transport.log(5, "ble: " + msg);
    }

    /**
     * Tears down active sessions belonging to mods that are no longer enabled with
     * {@code "ble": {"central": true}}, while leaving other enabled mods' sessions and
     * standalone {@code "root"} sessions intact.
     */
    public void retainEnabledMods(Set<String> enabledBleMods) {
        Set<String> allowed = enabledBleMods == null ? Collections.<String>emptySet() : enabledBleMods;
        for (BleSession s : activeSessions) {
            if ("root".equals(s.owner) || allowed.contains(s.owner)) continue;
            info("revoking BLE session #" + s.id + " for disabled mod " + s.owner);
            s.sendLine("STATE MOD_DISABLED");
            s.closeAfterDrain(150);
            cleanupSession(s);
        }
    }

    /**
     * Invoked when the Bluetooth adapter is turning off or has turned off.
     * Wakes pending commands/connections, notifies open sessions with {@code STATE BT_OFF},
     * resets controller tracking state, and detaches the native transport.
     */
    public void onAdapterDown() {
        info("Bluetooth adapter down; resetting BLE central state");
        wakePendingLatches();

        List<BleGatt.Link> links;
        synchronized (stateLock) {
            scanSubscribers.clear();
            hardwareScanEnabled = false;
            pausedScanForConnect = false;
            leStackInitialized = false;
            gatt.configureLeBuffers(0, 0);
            links = new ArrayList<BleGatt.Link>(linksByHandle.values());
            linksByHandle.clear();
            linksByMac.clear();
        }

        for (BleGatt.Link link : links) {
            link.connected = false;
            link.resetReassembly();
            gatt.recoverLinkCredits(link);
            CountDownLatch attLatch = link.waitingAttLatch;
            if (attLatch != null) attLatch.countDown();
            List<BleSession> owners;
            synchronized (link.owners) {
                owners = new ArrayList<BleSession>(link.owners);
                link.owners.clear();
            }
            for (BleSession s : owners) {
                s.ownedMacs.remove(link.mac);
                s.sendDeviceEvent(link.mac, "CONN_STATE DISCONNECTED " + link.mac + " 0x16");
            }
        }

        List<BleSession> sessions = new ArrayList<BleSession>(activeSessions);
        activeSessions.clear();
        for (BleSession s : sessions) {
            s.sendLine("STATE BT_OFF");
        }
        for (BleSession s : sessions) {
            s.closeAfterDrain(150);
        }

        transport.shutdown();
    }

    /** Tears down every active BLE session and restores controller settings. */
    public void shutdownAll() {
        List<BleSession> sessions = new ArrayList<BleSession>(activeSessions);
        activeSessions.clear();
        scanSubscribers.clear();
        for (BleSession s : sessions) {
            s.sendLine("STATE SHUTDOWN");
        }
        for (BleSession s : sessions) {
            s.closeAfterDrain(150);
        }
        for (BleGatt.Link link : new ArrayList<BleGatt.Link>(linksByHandle.values())) {
            disconnectLinkInternal(link, "shutdown");
        }
        linksByHandle.clear();
        linksByMac.clear();
        reconcileIdleController();
    }

    private void wakePendingLatches() {
        CountDownLatch cmdLatch = waitingCmdLatch;
        if (cmdLatch != null) {
            waitingCmdResult = null;
            cmdLatch.countDown();
        }
        CountDownLatch connLatch = pendingConnectLatch;
        if (connLatch != null) {
            pendingConnectStatus = 0xFF;
            pendingConnectResultHandle = -1;
            connLatch.countDown();
        }
    }

    @Override
    public void onSessionOverflow(BleSession session) {
        warn("session #" + session.id + " (" + session.owner + ") outbound queue overflowed; disconnecting");
        cleanupSession(session);
    }

    /**
     * Serves one incoming connection on {@code @hmgapi-ble}.
     * {@code owner} is the mod id from {@link BleAuth#authorize}, {@code "root"}, or
     * {@code null} when the caller is not authorized (only {@code PING} is answered then).
     */
    public void serve(InputStream rawIn, OutputStream rawOut, Closeable closeable, String owner) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, "UTF-8"), 1024);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(rawOut, "UTF-8"), 1024);

        String first = reader.readLine();
        if (first == null) return;
        String trimmed = first.trim();
        String upper = trimmed.toUpperCase(Locale.US);

        if ("PING".equals(upper)) {
            writer.write("PONG\n");
            writer.flush();
            return;
        }
        if (owner == null) {
            writer.write("ERR NOT_ALLOWED\n");
            writer.flush();
            return;
        }
        if ("STATUS".equals(upper)) {
            writer.write(buildStatusSummary());
            writer.flush();
            return;
        }
        if ("SESSIONS".equals(upper)) {
            writer.write(buildSessionsSummary());
            writer.flush();
            return;
        }
        if (upper.startsWith("STREAM_SESSION")) {
            runStreamingSession(owner, closeable, reader, writer);
            return;
        }
        writer.write("ERR UNKNOWN_CMD " + BleGatt.sanitizeToken(trimmed) + "\n");
        writer.flush();
    }

    public String buildStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ble_ready=").append(transport.isReady()).append('\n');
        sb.append("le_stack_init=").append(leStackInitialized).append('\n');
        sb.append("dedicated_le_buffers=").append(gatt.hasDedicatedLeBuffers()).append('\n');
        sb.append("active_sessions=").append(activeSessions.size()).append('\n');
        sb.append("active_links=").append(linksByHandle.size()).append('\n');
        sb.append("scanning=").append(hardwareScanEnabled).append('\n');
        sb.append("scan_subscribers=").append(scanSubscribers.size()).append('\n');
        sb.append("le_acl_mtu=").append(gatt.leAclMtu()).append('\n');
        sb.append("le_acl_credits_avail=").append(gatt.leAvailableCredits())
          .append('/').append(gatt.leTotalCredits()).append('\n');
        sb.append("hook_installed=").append(transport.stat(BleTransport.STAT_HOOK_INSTALLED)).append('\n');
        sb.append("tx_thread=").append(transport.stat(BleTransport.STAT_TX_THREAD)).append('\n');
        sb.append("pkt_rx=").append(transport.stat(BleTransport.STAT_PKT_RX)).append('\n');
        sb.append("pkt_tx=").append(transport.stat(BleTransport.STAT_PKT_TX)).append('\n');
        sb.append("pkt_drop=").append(transport.stat(BleTransport.STAT_PKT_DROP)).append('\n');
        sb.append("listening=").append(transport.stat(BleTransport.STAT_LISTENING)).append('\n');
        sb.append("tracked_le_handles=").append(transport.stat(BleTransport.STAT_LE_HANDLES)).append('\n');
        return sb.toString();
    }

    private String buildSessionsSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("sessions=").append(activeSessions.size()).append(" links=").append(linksByHandle.size()).append('\n');
        for (BleSession s : activeSessions) {
            sb.append("session#").append(s.id)
              .append(" owner=").append(s.owner)
              .append(" multiconn=").append(s.multiConnMode)
              .append(" dropped_adv=").append(s.droppedAdvCount())
              .append(" links=").append(s.ownedMacs).append('\n');
        }
        for (BleGatt.Link l : linksByHandle.values()) {
            sb.append("link mac=").append(l.mac)
              .append(" handle=0x").append(Integer.toHexString(l.connHandle))
              .append(" mtu=").append(l.negotiatedMtu)
              .append(" ready=").append(l.gattReady)
              .append(" chars=").append(l.charsByUuid.size()).append('\n');
        }
        return sb.toString();
    }

    private void runStreamingSession(String owner, Closeable closeable, BufferedReader reader, BufferedWriter writer) {
        final BleSession session = new BleSession(sessionIdSeq.getAndIncrement(), owner, closeable, writer, this);
        session.startWriter();
        activeSessions.add(session);
        info("session #" + session.id + " (" + owner + ") opened (active=" + activeSessions.size() + ")");

        try {
            if (!ensureNativeAndLeReady(session)) {
                session.sendLine("ERR NATIVE_BRIDGE_UNAVAILABLE");
                return;
            }
            session.sendLine("OK STREAM_READY");
            session.sendLine("STATE IDLE");

            String rawLine;
            while (!session.closed.get() && (rawLine = reader.readLine()) != null) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;
                if (!handleSessionCommand(session, line)) break;
            }
        } catch (IOException e) {
            if (!session.closed.get()) {
                info("session #" + session.id + " stream ended: " + e.getMessage());
            }
        } catch (Throwable t) {
            warn("session #" + session.id + " error: " + t);
            session.sendLine("ERR INTERNAL " + BleGatt.sanitizeToken(t.getMessage()));
        } finally {
            cleanupSession(session);
            session.closeAfterDrain(150);
            info("session #" + session.id + " (" + owner + ") closed (active=" + activeSessions.size() + ")");
        }
    }

    private void cleanupSession(BleSession session) {
        activeSessions.remove(session);
        removeScanSubscriber(session);

        List<String> macsSnapshot;
        synchronized (session.ownedMacs) {
            macsSnapshot = new ArrayList<String>(session.ownedMacs);
            session.ownedMacs.clear();
        }
        for (String mac : macsSnapshot) {
            BleGatt.Link link = linksByMac.get(mac);
            if (link != null) detachSessionFromLink(session, link);
        }
        reconcileListeningState();
    }

    private void reconcileListeningState() {
        if (Boolean.TRUE.equals(inPacketCallback.get())) {
            idleExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    reconcileIdleController();
                }
            });
        } else {
            reconcileIdleController();
        }
    }

    private void reconcileIdleController() {
        synchronized (stateLock) {
            if (!activeSessions.isEmpty() || !linksByHandle.isEmpty() || !scanSubscribers.isEmpty()) {
                return;
            }
            if (hardwareScanEnabled) {
                try { setHardwareScan(false); } catch (Throwable ignored) {}
            }
            if (leStackInitialized) {
                deinitControllerLeStack();
            }
            transport.clearHandles();
            transport.setListening(false);
        }
    }

    private boolean ensureNativeAndLeReady(BleSession session) throws IOException {
        synchronized (stateLock) {
            if (!transport.isReady()) {
                leStackInitialized = false;
                hardwareScanEnabled = false;
                pausedScanForConnect = false;
            }
            if (!transport.ensureReady(this)) {
                return false;
            }
            transport.setListening(true);
            if (!leStackInitialized) {
                initControllerLeStack(session);
                leStackInitialized = true;
            }
            return true;
        }
    }

    private void initControllerLeStack(BleSession session) throws IOException {
        gatt.configureLeBuffers(0, 0);

        // 1. HCI_Set_Event_Mask: enable all defined Classic BR/EDR, PIN, SCO/eSCO, EIR, and SSP
        //    events (AOSP HCI_DEFAULT_EVENT_MASK 0x1DBFF807_FFFFFFFF) + bit 61 (0x3E LE Meta Event).
        byte[] evtMask = new byte[] {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
            (byte) 0x07, (byte) 0xF8, (byte) 0xBF, (byte) 0x3D
        };
        sendHciCommandSync(HCI_SET_EVENT_MASK, evtMask, 2000);

        // 2. HCI_Write_LE_Host_Support(LE_Supported_Host=1, Simultaneous_LE_Host=1)
        sendHciCommandSync(HCI_WRITE_LE_HOST_SUPPORT, new byte[] { 0x01, 0x01 }, 2000);

        // 3. HCI_LE_Set_Event_Mask: try BT 4.2 mask (subevents 0x01..0x07 + 0x0A) first,
        //    falling back to BT 4.0 baseline (subevents 0x01..0x05) if rejected by controller.
        byte[] leMask42 = new byte[] { 0x7F, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
        byte[] maskRsp = sendHciCommandSync(HCI_LE_SET_EVENT_MASK, leMask42, 2000);
        if (maskRsp == null || maskRsp.length == 0 || maskRsp[0] != 0x00) {
            byte[] leMask40 = new byte[] { 0x1F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
            sendHciCommandSync(HCI_LE_SET_EVENT_MASK, leMask40, 2000);
        }

        // 4. HCI_LE_Read_Buffer_Size (0x2002): require dedicated LE ACL buffers for GATT connections
        byte[] bufRsp = sendHciCommandSync(HCI_LE_READ_BUFFER_SIZE, new byte[0], 2000);
        if (bufRsp != null && bufRsp.length >= 4 && bufRsp[0] == 0x00) {
            int pktLen = (bufRsp[1] & 0xFF) | ((bufRsp[2] & 0xFF) << 8);
            int totalPkts = bufRsp[3] & 0xFF;
            if (pktLen >= 27 && totalPkts >= 1) {
                gatt.configureLeBuffers(pktLen, totalPkts);
                info("controller LE buffer: aclMtu=" + pktLen + " credits=" + totalPkts);
            } else {
                warn("controller reports shared LE/Classic ACL buffers (pktLen=" + pktLen
                        + " credits=" + totalPkts + "); scan-only mode");
            }
        } else {
            warn("HCI_LE_Read_Buffer_Size unavailable; scan-only mode");
        }
        if (session != null) {
            info("LE central stack initialized for session #" + session.id);
        }
    }

    private void deinitControllerLeStack() {
        leStackInitialized = false;
        gatt.configureLeBuffers(0, 0);
        if (!transport.isReady()) return;
        try {
            sendHciCommandSync(HCI_LE_SET_EVENT_MASK, new byte[8], 1500);
            sendHciCommandSync(HCI_WRITE_LE_HOST_SUPPORT, new byte[] { 0x00, 0x00 }, 1500);
            byte[] classicEvtMask = new byte[] {
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0x07, (byte) 0xF8, (byte) 0xBF, (byte) 0x1D
            };
            sendHciCommandSync(HCI_SET_EVENT_MASK, classicEvtMask, 1500);
            info("LE host support restored to idle (0, 0)");
        } catch (IOException e) {
            warn("failed restoring LE host support on idle: " + e.getMessage());
        }
    }

    private boolean handleSessionCommand(final BleSession session, String rawLine) {
        String targetMac = null;
        String cmdLine = rawLine;
        if (cmdLine.startsWith("@")) {
            int sp = cmdLine.indexOf(' ');
            if (sp <= 1) {
                session.sendLine("ERR INVALID_TARGET_PREFIX");
                return true;
            }
            targetMac = BleGatt.normalizeMac(cmdLine.substring(1, sp));
            if (targetMac == null) {
                session.sendLine("ERR INVALID_MAC");
                return true;
            }
            session.multiConnMode = true;
            cmdLine = cmdLine.substring(sp + 1).trim();
        }

        String[] parts = cmdLine.split("\\s+");
        if (parts.length == 0) return true;
        String cmd = parts[0].toUpperCase(Locale.US);

        if ("PING".equals(cmd)) {
            session.sendLine("PONG");
            return true;
        }
        if ("CLOSE".equals(cmd) || "QUIT".equals(cmd)) {
            session.sendLine("OK BYE");
            return false;
        }
        if ("MULTICONN".equals(cmd)) {
            boolean enable = parts.length < 2 || !"0".equals(parts[1]);
            session.multiConnMode = enable;
            session.sendLine("OK MULTICONN " + (enable ? "1" : "0"));
            return true;
        }
        if ("SET_MAX_MTU".equals(cmd)) {
            if (parts.length < 2) {
                session.sendLine("ERR USAGE_SET_MAX_MTU");
                return true;
            }
            int m = BleGatt.parseIntSafe(parts[1], BleGatt.ATT_MAX_SUPPORTED_MTU);
            m = Math.max(BleGatt.ATT_DEFAULT_MTU, Math.min(BleGatt.ATT_MAX_SUPPORTED_MTU, m));
            session.preferredMaxServerMtu = m;
            if (targetMac != null) {
                BleGatt.Link l = linksByMac.get(targetMac);
                if (l != null) l.maxServerMtu = m;
            }
            session.sendLine("OK MAX_MTU " + m);
            return true;
        }
        if ("SCAN_START".equals(cmd)) {
            addScanSubscriber(session);
            return true;
        }
        if ("SCAN_STOP".equals(cmd)) {
            removeScanSubscriber(session);
            session.sendLine("STATE SCAN_STOPPED");
            return true;
        }
        if ("CONNECT".equals(cmd)) {
            final String mac = targetMac != null ? targetMac : (parts.length >= 2 ? BleGatt.normalizeMac(parts[1]) : null);
            if (mac == null) {
                session.sendLine("ERR INVALID_MAC");
                return true;
            }
            int addrType = -1;
            int argIdx = (targetMac != null) ? 1 : 2;
            if (parts.length > argIdx) {
                if (!"AUTO".equalsIgnoreCase(parts[argIdx]) && !"-".equals(parts[argIdx])) {
                    addrType = BleGatt.parseIntSafe(parts[argIdx], -1);
                }
            }
            String svcFilter = null;
            if (parts.length > argIdx + 1 && !"-".equals(parts[argIdx + 1]) && !"*".equals(parts[argIdx + 1])) {
                svcFilter = parts[argIdx + 1].toLowerCase(Locale.US);
            }
            final int finalAddrType = addrType;
            final String finalSvcFilter = svcFilter;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    performConnectAndDiscover(session, mac, finalAddrType, finalSvcFilter);
                }
            }, "hmgapi-ble-conn-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }
        if ("DISCONNECT_ALL".equals(cmd)) {
            List<String> macs;
            synchronized (session.ownedMacs) {
                macs = new ArrayList<String>(session.ownedMacs);
                session.ownedMacs.clear();
            }
            for (String m : macs) {
                BleGatt.Link l = linksByMac.get(m);
                if (l != null) detachSessionFromLink(session, l);
            }
            session.sendLine("STATE DISCONNECTED");
            return true;
        }
        if ("DISCONNECT".equals(cmd)) {
            String mac = targetMac != null ? targetMac : (parts.length >= 2 ? BleGatt.normalizeMac(parts[1]) : resolveDefaultMac(session));
            if (mac == null) {
                session.sendLine("STATE DISCONNECTED");
                return true;
            }
            session.ownedMacs.remove(mac);
            BleGatt.Link link = linksByMac.get(mac);
            if (link != null) detachSessionFromLink(session, link);
            session.sendDeviceEvent(mac, "CONN_STATE DISCONNECTED " + mac + " 0x16");
            if (!session.multiConnMode) {
                session.sendLine("STATE DISCONNECTED");
            }
            return true;
        }

        final BleGatt.Link link = resolveTargetLink(session, targetMac);
        if (link == null || !link.connected) {
            session.sendDeviceEvent(targetMac, "ERR NOT_CONNECTED");
            return true;
        }

        if ("REQUEST_MTU".equals(cmd)) {
            final int reqMtu = parts.length >= 2 ? BleGatt.parseIntSafe(parts[1], 185) : 185;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    gatt.performMtuExchange(session, link, reqMtu);
                }
            }, "hmgapi-ble-mtu-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }
        if ("PROTECT_CHAR".equals(cmd)) {
            if (parts.length < 2) {
                session.sendDeviceEvent(link.mac, "ERR USAGE_PROTECT_CHAR");
                return true;
            }
            BleGatt.GattCharDef ch = BleGatt.findCharacteristic(link, parts[1]);
            String key = ch != null ? ch.uuid : BleGatt.normalizeUuid(parts[1]);
            link.readOnlyProtectedUuids.add(key);
            session.sendDeviceEvent(link.mac, "OK PROTECTED " + key);
            return true;
        }
        if ("ENABLE_NOTIFY".equals(cmd) || "DISABLE_NOTIFY".equals(cmd)) {
            if (parts.length < 2) {
                session.sendDeviceEvent(link.mac, "ERR USAGE_NOTIFY_UUID");
                return true;
            }
            final String uuidToken = parts[1];
            final boolean enable = "ENABLE_NOTIFY".equals(cmd);
            final boolean indicate = parts.length >= 3 && "INDICATE".equalsIgnoreCase(parts[2]);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    gatt.performSetNotify(session, link, uuidToken, enable, indicate);
                }
            }, "hmgapi-ble-ntf-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }
        if ("READ_CHAR".equals(cmd)) {
            if (parts.length < 2) {
                session.sendDeviceEvent(link.mac, "ERR USAGE_READ_CHAR");
                return true;
            }
            final String uuidToken = parts[1];
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    gatt.performReadChar(session, link, uuidToken);
                }
            }, "hmgapi-ble-rd-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }
        if ("READ_BLOB".equals(cmd)) {
            if (parts.length < 3) {
                session.sendDeviceEvent(link.mac, "ERR USAGE_READ_BLOB");
                return true;
            }
            final String uuidToken = parts[1];
            final int offset = BleGatt.parseIntSafe(parts[2], 0);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    gatt.performReadBlob(session, link, uuidToken, offset);
                }
            }, "hmgapi-ble-rblob-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }
        if ("READ_LONG_CHAR".equals(cmd)) {
            if (parts.length < 2) {
                session.sendDeviceEvent(link.mac, "ERR USAGE_READ_LONG_CHAR");
                return true;
            }
            final String uuidToken = parts[1];
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    gatt.performReadLongChar(session, link, uuidToken);
                }
            }, "hmgapi-ble-rlong-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }
        if ("WRITE_CHAR".equals(cmd) || "WRITE_CHAR_NR".equals(cmd)) {
            if (parts.length < 3) {
                session.sendDeviceEvent(link.mac, "ERR USAGE_WRITE_CHAR");
                return true;
            }
            final String uuidToken = parts[1];
            final byte[] payload = BleGatt.hexToBytes(parts[2]);
            if (payload == null || payload.length == 0) {
                session.sendDeviceEvent(link.mac, "ERR INVALID_HEX_PAYLOAD");
                return true;
            }
            final boolean withRsp = "WRITE_CHAR".equals(cmd);
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    gatt.performWriteChar(session, link, uuidToken, payload, withRsp);
                }
            }, "hmgapi-ble-wr-" + session.id);
            worker.setDaemon(true);
            worker.start();
            return true;
        }

        session.sendDeviceEvent(targetMac, "ERR UNKNOWN_CMD " + BleGatt.sanitizeToken(cmd));
        return true;
    }

    private String resolveDefaultMac(BleSession session) {
        synchronized (session.ownedMacs) {
            if (session.ownedMacs.size() == 1) {
                return session.ownedMacs.iterator().next();
            }
        }
        return null;
    }

    private BleGatt.Link resolveTargetLink(BleSession session, String explicitMac) {
        if (explicitMac != null) {
            return linksByMac.get(explicitMac);
        }
        String singleMac = resolveDefaultMac(session);
        return singleMac != null ? linksByMac.get(singleMac) : null;
    }

    private void addScanSubscriber(BleSession session) {
        synchronized (stateLock) {
            scanSubscribers.add(session);
            if (!hardwareScanEnabled && !pausedScanForConnect) {
                try {
                    setHardwareScan(true);
                } catch (IOException e) {
                    scanSubscribers.remove(session);
                    session.sendLine("ERR SCAN_START_FAILED " + BleGatt.sanitizeToken(e.getMessage()));
                    return;
                }
            }
        }
        session.sendLine("STATE SCANNING");
    }

    private void removeScanSubscriber(BleSession session) {
        synchronized (stateLock) {
            if (!scanSubscribers.remove(session)) return;
            if (scanSubscribers.isEmpty() && hardwareScanEnabled) {
                try {
                    setHardwareScan(false);
                } catch (IOException e) {
                    warn("failed stopping hardware scan: " + e.getMessage());
                }
            }
        }
    }

    private void setHardwareScan(boolean enable) throws IOException {
        if (enable) {
            // Active scan (0x01), interval 0x0060 (60 ms), window 0x0020 (20 ms = 33% duty cycle for A2DP coexistence)
            byte[] scanParams = new byte[] {
                0x01,
                0x60, 0x00,
                0x20, 0x00,
                0x00,
                0x00
            };
            sendHciCommandSync(HCI_LE_SET_SCAN_PARAMS, scanParams, 1500);
            sendHciCommandSync(HCI_LE_SET_SCAN_ENABLE, new byte[] { 0x01, 0x00 }, 1500);
            hardwareScanEnabled = true;
        } else {
            sendHciCommandSync(HCI_LE_SET_SCAN_ENABLE, new byte[] { 0x00, 0x00 }, 1500);
            hardwareScanEnabled = false;
        }
    }

    private void performConnectAndDiscover(BleSession session, String mac, int requestedAddrType, String svcFilter) {
        if (!gatt.hasDedicatedLeBuffers()) {
            session.sendDeviceEvent(mac, "ERR SHARED_ACL_BUFFERS_UNSUPPORTED");
            if (!session.multiConnMode) {
                session.sendLine("STATE DISCONNECTED");
            }
            return;
        }

        BleGatt.Link existing = linksByMac.get(mac);
        if (existing != null && existing.connected) {
            attachSessionToExistingLink(session, existing);
            return;
        }

        if (linksByHandle.size() >= MAX_CONCURRENT_CONNECTIONS) {
            session.sendDeviceEvent(mac, "ERR MAX_CONNECTIONS_REACHED " + MAX_CONCURRENT_CONNECTIONS);
            return;
        }

        BleGatt.Link createdLink = null;
        synchronized (connectInitLock) {
            existing = linksByMac.get(mac);
            if (existing != null && existing.connected) {
                attachSessionToExistingLink(session, existing);
                return;
            }
            if (!gatt.hasDedicatedLeBuffers()) {
                session.sendDeviceEvent(mac, "ERR SHARED_ACL_BUFFERS_UNSUPPORTED");
                return;
            }

            synchronized (stateLock) {
                if (hardwareScanEnabled) {
                    try {
                        setHardwareScan(false);
                        pausedScanForConnect = true;
                    } catch (IOException ignored) {}
                }
            }

            try {
                boolean hadCachedType = seenAddrTypes.containsKey(mac);
                int firstType = requestedAddrType >= 0
                        ? requestedAddrType
                        : (hadCachedType ? seenAddrTypes.get(mac).intValue() : 0x00);

                session.sendDeviceEvent(mac, "CONN_STATE CONNECTING " + mac);
                if (!session.multiConnMode) {
                    session.sendLine("STATE CONNECTING " + mac);
                }

                createdLink = attemptHciLeConnectOnce(mac, firstType, 10000);
                if (createdLink == null && requestedAddrType < 0 && !hadCachedType && !session.closed.get() && leStackInitialized) {
                    int fallbackType = (firstType == 0x00) ? 0x01 : 0x00;
                    info("retrying LE connect to " + mac + " with addrType=" + fallbackType);
                    createdLink = attemptHciLeConnectOnce(mac, fallbackType, 8000);
                }
            } catch (Exception e) {
                session.sendDeviceEvent(mac, "ERR CONNECT_EXCEPTION " + BleGatt.sanitizeToken(e.getMessage()));
            } finally {
                synchronized (stateLock) {
                    if (pausedScanForConnect) {
                        pausedScanForConnect = false;
                        if (!scanSubscribers.isEmpty() && !hardwareScanEnabled && leStackInitialized) {
                            try { setHardwareScan(true); } catch (IOException ignored) {}
                        }
                    }
                }
            }
        }

        if (createdLink == null) {
            if (!session.closed.get()) {
                session.sendDeviceEvent(mac, "ERR CONNECT_TIMEOUT " + mac);
                if (!session.multiConnMode) {
                    session.sendLine("STATE DISCONNECTED");
                }
            }
            return;
        }

        createdLink.maxServerMtu = session.preferredMaxServerMtu;
        createdLink.owners.add(session);
        session.ownedMacs.add(mac);

        session.sendDeviceEvent(mac, String.format(Locale.US, "CONN_STATE CONNECTED %s 0x%04x", mac, createdLink.connHandle));
        if (!session.multiConnMode) {
            session.sendLine(String.format(Locale.US, "STATE CONNECTED %s 0x%04x", mac, createdLink.connHandle));
        }

        try {
            gatt.discoverGattHierarchy(createdLink, svcFilter);
            if (!createdLink.connected) return;
            createdLink.gattReady = true;
            gatt.emitGattCatalogToSession(session, createdLink);
        } catch (Exception e) {
            warn("GATT discovery failed on " + mac + ": " + e);
            session.sendDeviceEvent(mac, "ERR GATT_DISCOVERY_FAILED " + BleGatt.sanitizeToken(e.getMessage()));
        }
    }

    private void attachSessionToExistingLink(BleSession session, BleGatt.Link link) {
        link.owners.add(session);
        session.ownedMacs.add(link.mac);
        session.sendDeviceEvent(link.mac, String.format(Locale.US, "CONN_STATE CONNECTED %s 0x%04x", link.mac, link.connHandle));
        if (!session.multiConnMode) {
            session.sendLine(String.format(Locale.US, "STATE CONNECTED %s 0x%04x", link.mac, link.connHandle));
        }
        if (link.gattReady) {
            gatt.emitGattCatalogToSession(session, link);
        }
    }

    private BleGatt.Link attemptHciLeConnectOnce(String mac, int addrType, long timeoutMs) throws IOException {
        byte[] macLe = BleGatt.macToLittleEndianBytes(mac);
        if (macLe == null) throw new IOException("bad MAC " + mac);

        CountDownLatch latch = new CountDownLatch(1);
        pendingConnectMac = mac;
        pendingConnectResultHandle = -1;
        pendingConnectStatus = -1;
        pendingConnectLatch = latch;

        // Interval=0x0060 (60 ms), Window=0x0030 (30 ms), Conn_Interval=15..30 ms (0x000C..0x0018), Timeout=4.0 s (0x0190)
        byte[] params = new byte[25];
        params[0] = 0x60; params[1] = 0x00;
        params[2] = 0x30; params[3] = 0x00;
        params[4] = 0x00;
        params[5] = (byte) (addrType & 0xFF);
        System.arraycopy(macLe, 0, params, 6, 6);
        params[12] = 0x00;
        params[13] = 0x0C; params[14] = 0x00;
        params[15] = 0x18; params[16] = 0x00;
        params[17] = 0x00; params[18] = 0x00;
        params[19] = (byte) 0x90; params[20] = 0x01;
        params[21] = 0x00; params[22] = 0x00;
        params[23] = 0x00; params[24] = 0x00;

        byte[] cmdStatus = sendHciCommandSync(HCI_LE_CREATE_CONN, params, 2000);
        if (cmdStatus != null && cmdStatus.length >= 1 && cmdStatus[0] != 0x00) {
            int err = cmdStatus[0] & 0xFF;
            warn(String.format(Locale.US, "HCI_LE_Create_Connection rejected status=0x%02x", err));
            pendingConnectLatch = null;
            return null;
        }

        boolean completed = false;
        try {
            completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {}
        pendingConnectLatch = null;

        if (!completed || pendingConnectStatus != 0x00 || pendingConnectResultHandle <= 0) {
            if (!completed && leStackInitialized && transport.isReady()) {
                sendHciCommandSync(HCI_LE_CREATE_CONN_CANCEL, new byte[0], 1500);
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
            }
            return null;
        }

        BleGatt.Link link = linksByHandle.get(pendingConnectResultHandle);
        if (link == null) {
            link = registerConnectedLink(mac, addrType, pendingConnectResultHandle);
        }
        return link;
    }

    private BleGatt.Link registerConnectedLink(String mac, int addrType, int connHandle) {
        transport.trackHandle(connHandle);
        BleGatt.Link link = new BleGatt.Link(mac, addrType, connHandle);
        linksByHandle.put(connHandle, link);
        linksByMac.put(mac, link);
        return link;
    }

    private void detachSessionFromLink(BleSession session, BleGatt.Link link) {
        link.owners.remove(session);
        for (Map.Entry<String, Set<BleSession>> e : link.notifySubscribers.entrySet()) {
            Set<BleSession> subs = e.getValue();
            subs.remove(session);
            if (subs.isEmpty() && link.connected && !link.owners.isEmpty()) {
                BleGatt.GattCharDef ch = link.charsByUuid.get(e.getKey());
                if (ch != null && ch.cccdHandle > 0) {
                    try {
                        gatt.writeAttributeSync(link, ch.cccdHandle, new byte[] { 0x00, 0x00 }, 1500);
                    } catch (Throwable ignored) {}
                }
            }
        }
        if (link.owners.isEmpty()) {
            disconnectLinkInternal(link, "last session closed");
        }
    }

    private void disconnectLinkInternal(BleGatt.Link link, String reason) {
        if (!link.connected) return;
        link.connected = false;
        info(String.format(Locale.US, "disconnecting %s (handle=0x%04x, %s)", link.mac, link.connHandle, reason));
        byte[] params = new byte[] {
            (byte) (link.connHandle & 0xFF),
            (byte) ((link.connHandle >> 8) & 0xFF),
            0x13
        };
        byte[] status = null;
        try {
            status = sendHciCommandSync(HCI_DISCONNECT, params, 1500);
        } catch (IOException ignored) {}
        if (status == null || status.length < 1 || status[0] != 0x00) {
            handlePeripheralDisconnected(link.connHandle, 0x16);
        }
    }

    @Override
    public byte[] sendHciCommandSync(int opcode, byte[] params, long timeoutMs) throws IOException {
        synchronized (cmdSyncLock) {
            CountDownLatch latch = new CountDownLatch(1);
            waitingCmdOpcode = opcode;
            waitingCmdResult = null;
            waitingCmdLatch = latch;

            try {
                transport.sendCommand(opcode, params);
            } catch (IOException e) {
                waitingCmdLatch = null;
                waitingCmdOpcode = -1;
                throw e;
            }
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            byte[] res = waitingCmdResult;
            waitingCmdLatch = null;
            waitingCmdOpcode = -1;
            return res;
        }
    }

    // -------------------------------------------------------------------------
    // Inbound HCI Event & ACL Callbacks (never block on client I/O or locks)
    // -------------------------------------------------------------------------

    @Override
    public void onPacket(int pktType, byte[] payload) {
        if (payload == null || payload.length == 0) return;
        inPacketCallback.set(Boolean.TRUE);
        try {
            if (pktType == BleTransport.PKT_EVT) {
                handleHciEvent(payload);
            } else if (pktType == BleTransport.PKT_ACL) {
                handleAclRxPacket(payload);
            }
        } catch (Throwable t) {
            warn("packet dispatch error: " + t);
        } finally {
            inPacketCallback.remove();
        }
    }

    private void handleHciEvent(byte[] evt) {
        if (evt.length < 2) return;
        int evtCode = evt[0] & 0xFF;

        if (evtCode == 0x0E && evt.length >= 6) {
            int opcode = (evt[3] & 0xFF) | ((evt[4] & 0xFF) << 8);
            CountDownLatch latch = waitingCmdLatch;
            if (opcode == waitingCmdOpcode && latch != null) {
                waitingCmdResult = Arrays.copyOfRange(evt, 5, evt.length);
                latch.countDown();
            }
            return;
        }

        if (evtCode == 0x0F && evt.length >= 6) {
            int status = evt[2] & 0xFF;
            int opcode = (evt[4] & 0xFF) | ((evt[5] & 0xFF) << 8);
            CountDownLatch latch = waitingCmdLatch;
            if (opcode == waitingCmdOpcode && latch != null) {
                waitingCmdResult = new byte[] { (byte) status };
                latch.countDown();
            }
            return;
        }

        if (evtCode == 0x13 && evt.length >= 3) {
            int numHandles = evt[2] & 0xFF;
            for (int i = 0; i < numHandles; i++) {
                int base = 3 + (i * 4);
                if (base + 4 > evt.length) break;
                int h = ((evt[base + 1] & 0xFF) << 8 | (evt[base] & 0xFF)) & 0x0FFF;
                int completed = ((evt[base + 3] & 0xFF) << 8) | (evt[base + 2] & 0xFF);
                BleGatt.Link link = linksByHandle.get(h);
                if (link != null && completed > 0) {
                    gatt.onCompletedPackets(link, completed);
                }
            }
            return;
        }

        if (evtCode == 0x05 && evt.length >= 6) {
            int status = evt[2] & 0xFF;
            int h = ((evt[4] & 0xFF) << 8 | (evt[3] & 0xFF)) & 0x0FFF;
            int reason = evt[5] & 0xFF;
            if (status == 0x00) {
                handlePeripheralDisconnected(h, reason);
            }
            return;
        }

        if (evtCode == 0x3E && evt.length >= 3) {
            int subEvt = evt[2] & 0xFF;
            if (subEvt == 0x01 && evt.length >= 19) {
                int status = evt[3] & 0xFF;
                int connH = ((evt[5] & 0xFF) << 8 | (evt[4] & 0xFF)) & 0x0FFF;
                int peerAddrType = evt[7] & 0xFF;
                String peerMac = BleGatt.littleEndianBytesToMac(evt, 8);
                handleLeConnectionComplete(status, connH, peerAddrType, peerMac);
            } else if (subEvt == 0x0A && evt.length >= 31) {
                int status = evt[3] & 0xFF;
                int connH = ((evt[5] & 0xFF) << 8 | (evt[4] & 0xFF)) & 0x0FFF;
                int peerAddrType = evt[7] & 0xFF;
                String peerMac = BleGatt.littleEndianBytesToMac(evt, 8);
                handleLeConnectionComplete(status, connH, peerAddrType, peerMac);
            } else if (subEvt == 0x02 && evt.length >= 4) {
                parseAndBroadcastAdvReports(evt);
            }
        }
    }

    private void handleLeConnectionComplete(int status, int connHandle, int peerAddrType, String peerMac) {
        if (status == 0x00 && connHandle > 0) {
            String effectiveMac = (pendingConnectMac != null && peerMac.equalsIgnoreCase(pendingConnectMac))
                    ? pendingConnectMac : peerMac;
            registerConnectedLink(effectiveMac, peerAddrType, connHandle);
        }

        CountDownLatch latch = pendingConnectLatch;
        if (latch != null) {
            pendingConnectStatus = status;
            pendingConnectResultHandle = (status == 0x00) ? connHandle : -1;
            latch.countDown();
        }
    }

    private void handlePeripheralDisconnected(int connHandle, int reason) {
        transport.untrackHandle(connHandle);
        BleGatt.Link link = linksByHandle.remove(connHandle);
        if (link == null) return;

        link.connected = false;
        linksByMac.remove(link.mac);
        link.resetReassembly();
        gatt.recoverLinkCredits(link);

        CountDownLatch attLatch = link.waitingAttLatch;
        if (attLatch != null) attLatch.countDown();

        List<BleSession> ownersSnapshot;
        synchronized (link.owners) {
            ownersSnapshot = new ArrayList<BleSession>(link.owners);
            link.owners.clear();
        }
        for (BleSession s : ownersSnapshot) {
            s.ownedMacs.remove(link.mac);
            s.sendDeviceEvent(link.mac, String.format(Locale.US, "CONN_STATE DISCONNECTED %s 0x%02x", link.mac, reason));
            if (!s.multiConnMode) {
                s.sendLine(String.format(Locale.US, "STATE DISCONNECTED 0x%02x", reason));
            }
        }
        reconcileListeningState();
    }

    private void parseAndBroadcastAdvReports(byte[] evt) {
        int numReports = evt[3] & 0xFF;
        int pos = 4;
        long now = System.currentTimeMillis();

        for (int i = 0; i < numReports; i++) {
            if (pos + 9 > evt.length) break;
            int evtType = evt[pos] & 0xFF;
            int addrType = evt[pos + 1] & 0xFF;
            String mac = BleGatt.littleEndianBytesToMac(evt, pos + 2);
            int dataLen = evt[pos + 8] & 0xFF;
            pos += 9;
            if (pos + dataLen + 1 > evt.length) break;

            byte[] adData = Arrays.copyOfRange(evt, pos, pos + dataLen);
            pos += dataLen;
            int rssi = evt[pos];
            pos += 1;

            seenAddrTypes.put(mac, addrType);

            byte[] mergedAd = adData;
            if (evtType == 0x00 || evtType == 0x02 || evtType == 0x03) {
                AdvCacheEntry entry = new AdvCacheEntry();
                entry.connectableAd = adData;
                entry.updatedMs = now;
                advCache.put(mac, entry);
            } else if (evtType == 0x04) {
                AdvCacheEntry prev = advCache.get(mac);
                if (prev != null && prev.connectableAd != null && (now - prev.updatedMs) <= ADV_MERGE_TTL_MS) {
                    mergedAd = new byte[prev.connectableAd.length + adData.length];
                    System.arraycopy(prev.connectableAd, 0, mergedAd, 0, prev.connectableAd.length);
                    System.arraycopy(adData, 0, mergedAd, prev.connectableAd.length, adData.length);
                }
            }

            if (advCache.size() > 128) {
                Iterator<Map.Entry<String, AdvCacheEntry>> it = advCache.entrySet().iterator();
                while (it.hasNext()) {
                    if (now - it.next().getValue().updatedMs > ADV_MERGE_TTL_MS) it.remove();
                }
            }

            if (scanSubscribers.isEmpty()) continue;
            String line = "ADV " + mac + " " + addrType + " " + rssi + " " + BleGatt.bytesToHex(mergedAd);
            for (BleSession s : scanSubscribers) {
                s.sendLine(line);
            }
        }
    }

    private void handleAclRxPacket(byte[] acl) {
        if (acl.length < 4) return;
        int handleAndFlags = (acl[0] & 0xFF) | ((acl[1] & 0xFF) << 8);
        int connHandle = handleAndFlags & 0x0FFF;
        int pbFlag = (handleAndFlags >> 12) & 0x03;
        int aclLen = (acl[2] & 0xFF) | ((acl[3] & 0xFF) << 8);
        if (4 + aclLen > acl.length) aclLen = acl.length - 4;
        if (aclLen <= 0) return;

        BleGatt.Link link = linksByHandle.get(connHandle);
        if (link == null) return;

        gatt.handleAclPacket(link, acl, aclLen, pbFlag);
    }

    public static String normalizeUuid(String raw) {
        return BleGatt.normalizeUuid(raw);
    }

    public static String normalizeMac(String raw) {
        return BleGatt.normalizeMac(raw);
    }

    public static String bytesToHex(byte[] bytes) {
        return BleGatt.bytesToHex(bytes);
    }

    public static byte[] hexToBytes(String hex) {
        return BleGatt.hexToBytes(hex);
    }
}

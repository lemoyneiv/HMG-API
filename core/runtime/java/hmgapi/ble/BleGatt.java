package hmgapi.ble;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-link L2CAP fragmentation/reassembly, dedicated LE ACL credit flow control,
 * L2CAP {@code 0x0005} connection-parameter responder, and ATT/GATT client protocol.
 */
final class BleGatt {
    static final int L2CAP_CID_ATT          = 0x0004;
    static final int L2CAP_CID_LE_SIGNALING = 0x0005;

    static final int ATT_OP_ERROR_RSP         = 0x01;
    static final int ATT_OP_MTU_REQ           = 0x02;
    static final int ATT_OP_MTU_RSP           = 0x03;
    static final int ATT_OP_FIND_INFO_REQ     = 0x04;
    static final int ATT_OP_FIND_INFO_RSP     = 0x05;
    static final int ATT_OP_READ_BY_TYPE_REQ  = 0x08;
    static final int ATT_OP_READ_BY_TYPE_RSP  = 0x09;
    static final int ATT_OP_READ_REQ          = 0x0A;
    static final int ATT_OP_READ_RSP          = 0x0B;
    static final int ATT_OP_READ_BLOB_REQ     = 0x0C;
    static final int ATT_OP_READ_BLOB_RSP     = 0x0D;
    static final int ATT_OP_READ_BY_GROUP_REQ = 0x10;
    static final int ATT_OP_READ_BY_GROUP_RSP = 0x11;
    static final int ATT_OP_WRITE_REQ         = 0x12;
    static final int ATT_OP_WRITE_RSP         = 0x13;
    static final int ATT_OP_HANDLE_VALUE_NTF  = 0x1B;
    static final int ATT_OP_HANDLE_VALUE_IND  = 0x1D;
    static final int ATT_OP_HANDLE_VALUE_CFM  = 0x1E;
    static final int ATT_OP_WRITE_CMD         = 0x52;

    static final int ATT_DEFAULT_MTU       = 23;
    static final int ATT_MAX_SUPPORTED_MTU = 512;
    static final int MAX_LONG_READ_BYTES   = 4096;

    static final String BT_BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    interface HciCommandSender {
        byte[] sendHciCommandSync(int opcode, byte[] params, long timeoutMs) throws IOException;
    }

    public static final class GattServiceDef {
        public final int startHandle;
        public final int endHandle;
        public final String uuid;

        GattServiceDef(int startHandle, int endHandle, String uuid) {
            this.startHandle = startHandle;
            this.endHandle = endHandle;
            this.uuid = uuid;
        }
    }

    public static final class GattCharDef {
        public final String serviceUuid;
        public final int declHandle;
        public final int valueHandle;
        public final int endHandle;
        public final int properties;
        public final String uuid;
        public volatile int cccdHandle = -1;

        GattCharDef(String serviceUuid, int declHandle, int valueHandle, int endHandle, int properties, String uuid) {
            this.serviceUuid = serviceUuid;
            this.declHandle = declHandle;
            this.valueHandle = valueHandle;
            this.endHandle = endHandle;
            this.properties = properties;
            this.uuid = uuid;
        }
    }

    static final class Link {
        final String mac;
        final int addrType;
        final int connHandle;
        final Object attReqLock = new Object();
        final Object rxReassemblyLock = new Object();

        volatile boolean connected = true;
        volatile boolean gattReady = false;
        volatile int negotiatedMtu = ATT_DEFAULT_MTU;
        volatile int maxServerMtu = ATT_MAX_SUPPORTED_MTU;

        byte[] reassemblyBuf = null;
        int reassemblyExpectedLen = 0;
        int reassemblyOffset = 0;
        int reassemblyCid = -1;

        volatile int waitingAttReqOpcode = -1;
        volatile byte[] waitingAttResponse = null;
        volatile CountDownLatch waitingAttLatch = null;

        final AtomicInteger inFlightAclFragments = new AtomicInteger(0);
        final List<GattServiceDef> services = new ArrayList<GattServiceDef>();
        final Map<String, GattCharDef> charsByUuid = new LinkedHashMap<String, GattCharDef>();
        final Map<Integer, GattCharDef> charsByValueHandle = new HashMap<Integer, GattCharDef>();
        final Set<String> readOnlyProtectedUuids = Collections.synchronizedSet(new HashSet<String>());
        final Set<BleSession> owners = Collections.synchronizedSet(new HashSet<BleSession>());
        final Map<String, Set<BleSession>> notifySubscribers = new ConcurrentHashMap<String, Set<BleSession>>();

        Link(String mac, int addrType, int connHandle) {
            this.mac = mac;
            this.addrType = addrType;
            this.connHandle = connHandle;
        }

        void resetReassembly() {
            synchronized (rxReassemblyLock) {
                reassemblyBuf = null;
                reassemblyExpectedLen = 0;
                reassemblyOffset = 0;
                reassemblyCid = -1;
            }
        }
    }

    private final BleTransport transport;
    private final HciCommandSender hci;
    private final Object aclTxLock = new Object();

    private volatile boolean dedicatedLeBuffers = false;
    private volatile int leAclDataPktLen = 0;
    private volatile int leTotalNumDataPkts = 0;
    private volatile Semaphore leAclCredits = new Semaphore(0);

    BleGatt(BleTransport transport, HciCommandSender hci) {
        this.transport = transport;
        this.hci = hci;
    }

    void configureLeBuffers(int pktLen, int totalPkts) {
        if (pktLen >= 27 && totalPkts >= 1) {
            dedicatedLeBuffers = true;
            leAclDataPktLen = pktLen;
            leTotalNumDataPkts = totalPkts;
            leAclCredits = new Semaphore(totalPkts);
        } else {
            dedicatedLeBuffers = false;
            leAclDataPktLen = 0;
            leTotalNumDataPkts = 0;
            leAclCredits = new Semaphore(0);
        }
    }

    boolean hasDedicatedLeBuffers() {
        return dedicatedLeBuffers;
    }

    int leAclMtu() {
        return leAclDataPktLen;
    }

    int leTotalCredits() {
        return leTotalNumDataPkts;
    }

    int leAvailableCredits() {
        return leAclCredits.availablePermits();
    }

    void onCompletedPackets(Link link, int completed) {
        if (link == null || completed <= 0) return;
        int remaining = link.inFlightAclFragments.addAndGet(-completed);
        if (remaining < 0) link.inFlightAclFragments.set(0);
        if (!dedicatedLeBuffers || leTotalNumDataPkts <= 0) return;
        Semaphore credits = leAclCredits;
        int cap = leTotalNumDataPkts - credits.availablePermits();
        int toRelease = Math.min(completed, Math.max(0, cap));
        if (toRelease > 0) credits.release(toRelease);
    }

    void recoverLinkCredits(Link link) {
        int stuck = link.inFlightAclFragments.getAndSet(0);
        if (stuck > 0 && dedicatedLeBuffers && leTotalNumDataPkts > 0) {
            Semaphore credits = leAclCredits;
            int cap = leTotalNumDataPkts - credits.availablePermits();
            int recover = Math.min(stuck, Math.max(0, cap));
            if (recover > 0) credits.release(recover);
        }
    }

    // -------------------------------------------------------------------------
    // GATT Discovery & ATT Operations
    // -------------------------------------------------------------------------

    void discoverGattHierarchy(Link link, String serviceFilter) throws IOException {
        List<GattServiceDef> discoveredServices = new ArrayList<GattServiceDef>();
        int startHandle = 0x0001;

        while (startHandle <= 0xFFFF && link.connected) {
            byte[] req = new byte[] {
                (byte) ATT_OP_READ_BY_GROUP_REQ,
                (byte) (startHandle & 0xFF), (byte) ((startHandle >> 8) & 0xFF),
                (byte) 0xFF, (byte) 0xFF,
                0x00, 0x28
            };
            byte[] rsp = sendAttRequestSync(link, req, 3000);
            if (rsp == null) throw new IOException("Read By Group Type timeout");
            if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) break;
            if ((rsp[0] & 0xFF) != ATT_OP_READ_BY_GROUP_RSP || rsp.length < 6) break;

            int entryLen = rsp[1] & 0xFF;
            if (entryLen != 6 && entryLen != 20) break;

            int lastEnd = startHandle;
            for (int pos = 2; pos + entryLen <= rsp.length; pos += entryLen) {
                int sHandle = (rsp[pos] & 0xFF) | ((rsp[pos + 1] & 0xFF) << 8);
                int eHandle = (rsp[pos + 2] & 0xFF) | ((rsp[pos + 3] & 0xFF) << 8);
                String uuid = parseAttUuidBytes(rsp, pos + 4, entryLen - 4);
                if (matchesServiceFilter(uuid, serviceFilter)) {
                    discoveredServices.add(new GattServiceDef(sHandle, eHandle, uuid));
                }
                lastEnd = eHandle;
            }
            if (lastEnd >= 0xFFFF || lastEnd < startHandle) break;
            startHandle = lastEnd + 1;
        }

        link.services.clear();
        link.services.addAll(discoveredServices);
        link.charsByUuid.clear();
        link.charsByValueHandle.clear();

        for (GattServiceDef svc : discoveredServices) {
            List<GattCharDef> svcChars = discoverServiceCharacteristics(link, svc);
            for (int i = 0; i < svcChars.size(); i++) {
                GattCharDef ch = svcChars.get(i);
                int charEnd = (i + 1 < svcChars.size())
                        ? (svcChars.get(i + 1).declHandle - 1)
                        : svc.endHandle;
                GattCharDef bounded = new GattCharDef(svc.uuid, ch.declHandle, ch.valueHandle, charEnd, ch.properties, ch.uuid);
                if ((bounded.properties & 0x30) != 0 && bounded.valueHandle < bounded.endHandle) {
                    bounded.cccdHandle = discoverCccdHandle(link, bounded.valueHandle + 1, bounded.endHandle);
                }
                link.charsByUuid.put(bounded.uuid, bounded);
                link.charsByValueHandle.put(bounded.valueHandle, bounded);
            }
        }

        transport.log(4, String.format(Locale.US, "ble: GATT discovery complete on %s: %d services, %d chars",
                link.mac, link.services.size(), link.charsByUuid.size()));
    }

    private List<GattCharDef> discoverServiceCharacteristics(Link link, GattServiceDef svc) throws IOException {
        List<GattCharDef> list = new ArrayList<GattCharDef>();
        int curStart = svc.startHandle;
        while (curStart <= svc.endHandle && link.connected) {
            byte[] req = new byte[] {
                (byte) ATT_OP_READ_BY_TYPE_REQ,
                (byte) (curStart & 0xFF), (byte) ((curStart >> 8) & 0xFF),
                (byte) (svc.endHandle & 0xFF), (byte) ((svc.endHandle >> 8) & 0xFF),
                0x03, 0x28
            };
            byte[] rsp = sendAttRequestSync(link, req, 3000);
            if (rsp == null) throw new IOException("Read By Type timeout");
            if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) break;
            if ((rsp[0] & 0xFF) != ATT_OP_READ_BY_TYPE_RSP || rsp.length < 7) break;

            int itemLen = rsp[1] & 0xFF;
            if (itemLen != 7 && itemLen != 21) break;

            int lastDecl = curStart;
            for (int pos = 2; pos + itemLen <= rsp.length; pos += itemLen) {
                int declHandle = (rsp[pos] & 0xFF) | ((rsp[pos + 1] & 0xFF) << 8);
                int props = rsp[pos + 2] & 0xFF;
                int valHandle = (rsp[pos + 3] & 0xFF) | ((rsp[pos + 4] & 0xFF) << 8);
                String charUuid = parseAttUuidBytes(rsp, pos + 5, itemLen - 5);
                list.add(new GattCharDef(svc.uuid, declHandle, valHandle, svc.endHandle, props, charUuid));
                lastDecl = declHandle;
            }
            if (lastDecl >= svc.endHandle || lastDecl < curStart) break;
            curStart = lastDecl + 1;
        }
        return list;
    }

    private int discoverCccdHandle(Link link, int startHandle, int endHandle) throws IOException {
        int cur = startHandle;
        while (cur <= endHandle && link.connected) {
            byte[] req = new byte[] {
                (byte) ATT_OP_FIND_INFO_REQ,
                (byte) (cur & 0xFF), (byte) ((cur >> 8) & 0xFF),
                (byte) (endHandle & 0xFF), (byte) ((endHandle >> 8) & 0xFF)
            };
            byte[] rsp = sendAttRequestSync(link, req, 2500);
            if (rsp == null || (rsp[0] & 0xFF) != ATT_OP_FIND_INFO_RSP || rsp.length < 6) break;

            int format = rsp[1] & 0xFF;
            int step = (format == 0x01) ? 4 : (format == 0x02 ? 18 : 0);
            if (step == 0) break;

            int lastH = cur;
            for (int pos = 2; pos + step <= rsp.length; pos += step) {
                int h = (rsp[pos] & 0xFF) | ((rsp[pos + 1] & 0xFF) << 8);
                String uuid = parseAttUuidBytes(rsp, pos + 2, step - 2);
                if (isShortUuidMatch(uuid, "2902")) {
                    return h;
                }
                lastH = h;
            }
            if (lastH >= endHandle || lastH < cur) break;
            cur = lastH + 1;
        }
        return -1;
    }

    void emitGattCatalogToSession(BleSession session, Link link) {
        for (GattServiceDef svc : link.services) {
            session.sendDeviceEvent(link.mac, String.format(Locale.US,
                    "GATT_SERVICE %s 0x%04x 0x%04x", svc.uuid, svc.startHandle, svc.endHandle));
        }
        for (GattCharDef ch : link.charsByUuid.values()) {
            session.sendDeviceEvent(link.mac, String.format(Locale.US,
                    "GATT_CHAR %s %s 0x%04x 0x%02x 0x%04x",
                    ch.serviceUuid, ch.uuid, ch.valueHandle, ch.properties,
                    ch.cccdHandle > 0 ? ch.cccdHandle : 0));
        }
        session.sendDeviceEvent(link.mac, "GATT_READY " + link.mac + " mtu=" + link.negotiatedMtu);
        if (!session.multiConnMode) {
            session.sendLine("STATE READY " + link.mac);
        }
    }

    void performMtuExchange(BleSession session, Link link, int requestedMtu) {
        int clamped = Math.max(ATT_DEFAULT_MTU, Math.min(ATT_MAX_SUPPORTED_MTU, requestedMtu));
        byte[] req = new byte[] {
            (byte) ATT_OP_MTU_REQ,
            (byte) (clamped & 0xFF),
            (byte) ((clamped >> 8) & 0xFF)
        };
        try {
            byte[] rsp = sendAttRequestSync(link, req, 3000);
            if (rsp == null) {
                session.sendDeviceEvent(link.mac, "ERR MTU_TIMEOUT");
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) {
                session.sendDeviceEvent(link.mac, "MTU_CHANGED " + link.negotiatedMtu);
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_MTU_RSP && rsp.length >= 3) {
                int serverRxMtu = (rsp[1] & 0xFF) | ((rsp[2] & 0xFF) << 8);
                int effective = Math.max(ATT_DEFAULT_MTU, Math.min(clamped, serverRxMtu));
                link.negotiatedMtu = effective;
                session.sendDeviceEvent(link.mac, "MTU_CHANGED " + effective);
            }
        } catch (IOException e) {
            session.sendDeviceEvent(link.mac, "ERR MTU_IO " + sanitizeToken(e.getMessage()));
        }
    }

    void performSetNotify(BleSession session, Link link, String uuidToken, boolean enable, boolean indicate) {
        GattCharDef ch = findCharacteristic(link, uuidToken);
        if (ch == null) {
            session.sendDeviceEvent(link.mac, "ERR CHAR_NOT_FOUND " + sanitizeToken(uuidToken));
            return;
        }
        if (ch.cccdHandle <= 0) {
            session.sendDeviceEvent(link.mac, "ERR CCCD_NOT_FOUND " + ch.uuid);
            return;
        }

        Set<BleSession> subs = link.notifySubscribers.get(ch.uuid);
        if (subs == null) {
            Set<BleSession> created = Collections.synchronizedSet(new HashSet<BleSession>());
            link.notifySubscribers.put(ch.uuid, created);
            subs = link.notifySubscribers.get(ch.uuid);
        }

        try {
            if (enable) {
                subs.add(session);
                byte[] cccdVal = indicate ? new byte[] { 0x02, 0x00 } : new byte[] { 0x01, 0x00 };
                int attErr = writeAttributeSync(link, ch.cccdHandle, cccdVal, 3000);
                if (attErr != 0) {
                    subs.remove(session);
                    session.sendDeviceEvent(link.mac, String.format(Locale.US, "ERR CCCD_WRITE_FAILED 0x%02x", attErr));
                    return;
                }
                session.sendDeviceEvent(link.mac, "NOTIFY_STATE " + ch.uuid + " " + (indicate ? "INDICATE" : "ENABLED"));
            } else {
                subs.remove(session);
                if (subs.isEmpty()) {
                    writeAttributeSync(link, ch.cccdHandle, new byte[] { 0x00, 0x00 }, 3000);
                }
                session.sendDeviceEvent(link.mac, "NOTIFY_STATE " + ch.uuid + " DISABLED");
            }
        } catch (IOException e) {
            session.sendDeviceEvent(link.mac, "ERR CCCD_IO " + sanitizeToken(e.getMessage()));
        }
    }

    void performReadChar(BleSession session, Link link, String uuidToken) {
        GattCharDef ch = findCharacteristic(link, uuidToken);
        if (ch == null) {
            session.sendDeviceEvent(link.mac, "ERR CHAR_NOT_FOUND " + sanitizeToken(uuidToken));
            return;
        }
        byte[] req = new byte[] {
            (byte) ATT_OP_READ_REQ,
            (byte) (ch.valueHandle & 0xFF),
            (byte) ((ch.valueHandle >> 8) & 0xFF)
        };
        try {
            byte[] rsp = sendAttRequestSync(link, req, 3000);
            if (rsp == null) {
                session.sendDeviceEvent(link.mac, "ERR READ_TIMEOUT " + ch.uuid);
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) {
                int errCode = rsp.length >= 5 ? (rsp[4] & 0xFF) : 0xFF;
                session.sendDeviceEvent(link.mac, String.format(Locale.US, "ERR ATT_READ_ERROR %s 0x%02x", ch.uuid, errCode));
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_READ_RSP) {
                byte[] val = Arrays.copyOfRange(rsp, 1, rsp.length);
                session.sendDeviceEvent(link.mac, "CHAR_VALUE " + ch.uuid + " " + bytesToHex(val));
            }
        } catch (IOException e) {
            session.sendDeviceEvent(link.mac, "ERR READ_IO " + sanitizeToken(e.getMessage()));
        }
    }

    void performReadBlob(BleSession session, Link link, String uuidToken, int offset) {
        GattCharDef ch = findCharacteristic(link, uuidToken);
        if (ch == null) {
            session.sendDeviceEvent(link.mac, "ERR CHAR_NOT_FOUND " + sanitizeToken(uuidToken));
            return;
        }
        byte[] req = new byte[] {
            (byte) ATT_OP_READ_BLOB_REQ,
            (byte) (ch.valueHandle & 0xFF), (byte) ((ch.valueHandle >> 8) & 0xFF),
            (byte) (offset & 0xFF), (byte) ((offset >> 8) & 0xFF)
        };
        try {
            byte[] rsp = sendAttRequestSync(link, req, 3000);
            if (rsp == null) {
                session.sendDeviceEvent(link.mac, "ERR READ_BLOB_TIMEOUT " + ch.uuid);
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) {
                int errCode = rsp.length >= 5 ? (rsp[4] & 0xFF) : 0xFF;
                session.sendDeviceEvent(link.mac, String.format(Locale.US, "ERR ATT_READ_BLOB_ERROR %s 0x%02x", ch.uuid, errCode));
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_READ_BLOB_RSP) {
                byte[] val = Arrays.copyOfRange(rsp, 1, rsp.length);
                session.sendDeviceEvent(link.mac, "CHAR_BLOB " + ch.uuid + " " + offset + " " + bytesToHex(val));
            }
        } catch (IOException e) {
            session.sendDeviceEvent(link.mac, "ERR READ_BLOB_IO " + sanitizeToken(e.getMessage()));
        }
    }

    void performReadLongChar(BleSession session, Link link, String uuidToken) {
        GattCharDef ch = findCharacteristic(link, uuidToken);
        if (ch == null) {
            session.sendDeviceEvent(link.mac, "ERR CHAR_NOT_FOUND " + sanitizeToken(uuidToken));
            return;
        }
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        try {
            int mtu = link.negotiatedMtu;
            byte[] firstReq = new byte[] {
                (byte) ATT_OP_READ_REQ,
                (byte) (ch.valueHandle & 0xFF),
                (byte) ((ch.valueHandle >> 8) & 0xFF)
            };
            byte[] rsp = sendAttRequestSync(link, firstReq, 3000);
            if (rsp == null) {
                session.sendDeviceEvent(link.mac, "ERR READ_TIMEOUT " + ch.uuid);
                return;
            }
            if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) {
                int errCode = rsp.length >= 5 ? (rsp[4] & 0xFF) : 0xFF;
                session.sendDeviceEvent(link.mac, String.format(Locale.US, "ERR ATT_READ_ERROR %s 0x%02x", ch.uuid, errCode));
                return;
            }
            if ((rsp[0] & 0xFF) != ATT_OP_READ_RSP) return;
            int chunkLen = rsp.length - 1;
            full.write(rsp, 1, chunkLen);

            while (chunkLen == (mtu - 1) && full.size() < MAX_LONG_READ_BYTES && link.connected) {
                int offset = full.size();
                byte[] blobReq = new byte[] {
                    (byte) ATT_OP_READ_BLOB_REQ,
                    (byte) (ch.valueHandle & 0xFF), (byte) ((ch.valueHandle >> 8) & 0xFF),
                    (byte) (offset & 0xFF), (byte) ((offset >> 8) & 0xFF)
                };
                byte[] blobRsp = sendAttRequestSync(link, blobReq, 3000);
                if (blobRsp == null) break;
                if ((blobRsp[0] & 0xFF) == ATT_OP_ERROR_RSP) {
                    int errCode = blobRsp.length >= 5 ? (blobRsp[4] & 0xFF) : 0xFF;
                    if (errCode == 0x0B || errCode == 0x07) break;
                    session.sendDeviceEvent(link.mac, String.format(Locale.US, "ERR ATT_READ_LONG_ERROR %s 0x%02x", ch.uuid, errCode));
                    return;
                }
                if ((blobRsp[0] & 0xFF) != ATT_OP_READ_BLOB_RSP) break;
                chunkLen = blobRsp.length - 1;
                if (chunkLen <= 0) break;
                full.write(blobRsp, 1, chunkLen);
            }
            session.sendDeviceEvent(link.mac, "CHAR_LONG_VALUE " + ch.uuid + " " + bytesToHex(full.toByteArray()));
        } catch (IOException e) {
            session.sendDeviceEvent(link.mac, "ERR READ_LONG_IO " + sanitizeToken(e.getMessage()));
        }
    }

    void performWriteChar(BleSession session, Link link, String uuidToken, byte[] payload, boolean withResponse) {
        GattCharDef ch = findCharacteristic(link, uuidToken);
        if (ch == null) {
            session.sendDeviceEvent(link.mac, "ERR CHAR_NOT_FOUND " + sanitizeToken(uuidToken));
            return;
        }
        if (link.readOnlyProtectedUuids.contains(ch.uuid)) {
            session.sendDeviceEvent(link.mac, "ERR FORBIDDEN_READ_ONLY_CHAR " + ch.uuid);
            return;
        }
        int maxPayload = link.negotiatedMtu - 3;
        if (payload.length > maxPayload) {
            session.sendDeviceEvent(link.mac, "ERR PAYLOAD_EXCEEDS_MTU max=" + maxPayload);
            return;
        }

        try {
            if (withResponse) {
                int attErr = writeAttributeSync(link, ch.valueHandle, payload, 3000);
                if (attErr != 0) {
                    session.sendDeviceEvent(link.mac, String.format(Locale.US, "ERR ATT_WRITE_ERROR %s 0x%02x", ch.uuid, attErr));
                    return;
                }
                session.sendDeviceEvent(link.mac, "WRITE_OK " + ch.uuid);
            } else {
                byte[] pdu = new byte[3 + payload.length];
                pdu[0] = (byte) ATT_OP_WRITE_CMD;
                pdu[1] = (byte) (ch.valueHandle & 0xFF);
                pdu[2] = (byte) ((ch.valueHandle >> 8) & 0xFF);
                System.arraycopy(payload, 0, pdu, 3, payload.length);
                sendL2capPduFragmented(link, L2CAP_CID_ATT, pdu);
                session.sendDeviceEvent(link.mac, "WRITE_OK " + ch.uuid);
            }
        } catch (IOException e) {
            session.sendDeviceEvent(link.mac, "ERR WRITE_IO " + sanitizeToken(e.getMessage()));
        }
    }

    int writeAttributeSync(Link link, int attrHandle, byte[] value, long timeoutMs) throws IOException {
        byte[] req = new byte[3 + value.length];
        req[0] = (byte) ATT_OP_WRITE_REQ;
        req[1] = (byte) (attrHandle & 0xFF);
        req[2] = (byte) ((attrHandle >> 8) & 0xFF);
        System.arraycopy(value, 0, req, 3, value.length);

        byte[] rsp = sendAttRequestSync(link, req, timeoutMs);
        if (rsp == null) throw new IOException("ATT Write timeout");
        if ((rsp[0] & 0xFF) == ATT_OP_ERROR_RSP) {
            return rsp.length >= 5 ? (rsp[4] & 0xFF) : 0xFF;
        }
        return ((rsp[0] & 0xFF) == ATT_OP_WRITE_RSP) ? 0 : 0xFF;
    }

    byte[] sendAttRequestSync(Link link, byte[] attReq, long timeoutMs) throws IOException {
        synchronized (link.attReqLock) {
            if (!link.connected) throw new IOException("Link disconnected");
            CountDownLatch latch = new CountDownLatch(1);
            link.waitingAttReqOpcode = attReq[0] & 0xFF;
            link.waitingAttResponse = null;
            link.waitingAttLatch = latch;

            sendL2capPduFragmented(link, L2CAP_CID_ATT, attReq);
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}

            byte[] rsp = link.waitingAttResponse;
            link.waitingAttLatch = null;
            link.waitingAttReqOpcode = -1;
            return rsp;
        }
    }

    // -------------------------------------------------------------------------
    // Outbound L2CAP over Fragmented LE ACL + Dedicated Credit Flow Control
    // -------------------------------------------------------------------------

    void sendL2capPduFragmented(Link link, int cid, byte[] payload) throws IOException {
        if (!dedicatedLeBuffers || leTotalNumDataPkts <= 0) {
            throw new IOException("shared ACL buffers unsupported");
        }
        byte[] l2capFrame = new byte[4 + payload.length];
        l2capFrame[0] = (byte) (payload.length & 0xFF);
        l2capFrame[1] = (byte) ((payload.length >> 8) & 0xFF);
        l2capFrame[2] = (byte) (cid & 0xFF);
        l2capFrame[3] = (byte) ((cid >> 8) & 0xFF);
        System.arraycopy(payload, 0, l2capFrame, 4, payload.length);

        int maxChunk = Math.max(27, leAclDataPktLen);
        Semaphore credits = leAclCredits;
        synchronized (aclTxLock) {
            int offset = 0;
            boolean first = true;
            while (offset < l2capFrame.length) {
                if (!link.connected) throw new IOException("BLE link closed during ACL TX");
                int chunkLen = Math.min(maxChunk, l2capFrame.length - offset);
                byte[] chunk = Arrays.copyOfRange(l2capFrame, offset, offset + chunkLen);
                int pbFlag = first ? 0x00 : 0x01;

                boolean acquired = false;
                try {
                    acquired = credits.tryAcquire(2500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {}
                if (!acquired) {
                    throw new IOException("LE ACL credit timeout");
                }

                link.inFlightAclFragments.incrementAndGet();
                try {
                    transport.sendAcl(link.connHandle, pbFlag, chunk);
                } catch (IOException e) {
                    link.inFlightAclFragments.decrementAndGet();
                    credits.release(1);
                    throw e;
                }
                first = false;
                offset += chunkLen;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inbound ACL Reassembly & ATT / L2CAP Signaling Dispatch
    // -------------------------------------------------------------------------

    void handleAclPacket(Link link, byte[] acl, int aclLen, int pbFlag) {
        byte[] completePdu = null;
        int completeCid = -1;

        synchronized (link.rxReassemblyLock) {
            if (pbFlag == 0x02 || pbFlag == 0x00) {
                if (aclLen < 4) return;
                int l2capLen = (acl[4] & 0xFF) | ((acl[5] & 0xFF) << 8);
                int cid = (acl[6] & 0xFF) | ((acl[7] & 0xFF) << 8);
                if (l2capLen < 0 || l2capLen > ATT_MAX_SUPPORTED_MTU + 8) {
                    link.resetReassembly();
                    return;
                }
                int fragPayload = aclLen - 4;
                if (fragPayload >= l2capLen) {
                    completePdu = Arrays.copyOfRange(acl, 8, 8 + l2capLen);
                    completeCid = cid;
                    link.resetReassembly();
                } else {
                    link.reassemblyBuf = new byte[l2capLen];
                    System.arraycopy(acl, 8, link.reassemblyBuf, 0, fragPayload);
                    link.reassemblyExpectedLen = l2capLen;
                    link.reassemblyOffset = fragPayload;
                    link.reassemblyCid = cid;
                }
            } else if (pbFlag == 0x01) {
                if (link.reassemblyBuf == null) return;
                int copyLen = Math.min(aclLen, link.reassemblyExpectedLen - link.reassemblyOffset);
                System.arraycopy(acl, 4, link.reassemblyBuf, link.reassemblyOffset, copyLen);
                link.reassemblyOffset += copyLen;
                if (link.reassemblyOffset >= link.reassemblyExpectedLen) {
                    completePdu = link.reassemblyBuf;
                    completeCid = link.reassemblyCid;
                    link.resetReassembly();
                }
            }
        }

        if (completePdu != null) {
            if (completeCid == L2CAP_CID_ATT) {
                handleAttPdu(link, completePdu);
            } else if (completeCid == L2CAP_CID_LE_SIGNALING) {
                handleLeSignalingPdu(link, completePdu);
            }
        }
    }

    private void handleLeSignalingPdu(final Link link, byte[] sig) {
        if (sig.length < 4) return;
        int code = sig[0] & 0xFF;
        final int identifier = sig[1] & 0xFF;
        // 0x12 = Connection Parameter Update Request
        if (code == 0x12 && sig.length >= 12) {
            final int minInt = (sig[4] & 0xFF) | ((sig[5] & 0xFF) << 8);
            final int maxInt = (sig[6] & 0xFF) | ((sig[7] & 0xFF) << 8);
            final int latency = (sig[8] & 0xFF) | ((sig[9] & 0xFF) << 8);
            final int timeout = (sig[10] & 0xFF) | ((sig[11] & 0xFF) << 8);

            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] acceptRsp = new byte[] {
                            0x13, (byte) identifier, 0x02, 0x00, 0x00, 0x00
                        };
                        sendL2capPduFragmented(link, L2CAP_CID_LE_SIGNALING, acceptRsp);

                        byte[] upd = new byte[] {
                            (byte) (link.connHandle & 0xFF), (byte) ((link.connHandle >> 8) & 0xFF),
                            (byte) (minInt & 0xFF), (byte) ((minInt >> 8) & 0xFF),
                            (byte) (maxInt & 0xFF), (byte) ((maxInt >> 8) & 0xFF),
                            (byte) (latency & 0xFF), (byte) ((latency >> 8) & 0xFF),
                            (byte) (timeout & 0xFF), (byte) ((timeout >> 8) & 0xFF),
                            0x00, 0x00, 0x00, 0x00
                        };
                        hci.sendHciCommandSync(BleCentral.HCI_LE_CONN_UPDATE, upd, 1500);
                    } catch (IOException ignored) {}
                }
            }, "hmgapi-ble-connparam");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handleAttPdu(final Link link, byte[] att) {
        if (att.length < 1) return;
        int opcode = att[0] & 0xFF;

        if (opcode == ATT_OP_MTU_REQ && att.length >= 3) {
            final int clientRxMtu = (att[1] & 0xFF) | ((att[2] & 0xFF) << 8);
            final int ourMax = link.maxServerMtu;
            final int newMtu = Math.max(ATT_DEFAULT_MTU, Math.min(ourMax, clientRxMtu));
            link.negotiatedMtu = newMtu;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] rsp = new byte[] {
                            (byte) ATT_OP_MTU_RSP,
                            (byte) (ourMax & 0xFF),
                            (byte) ((ourMax >> 8) & 0xFF)
                        };
                        sendL2capPduFragmented(link, L2CAP_CID_ATT, rsp);
                        List<BleSession> targets;
                        synchronized (link.owners) {
                            targets = new ArrayList<BleSession>(link.owners);
                        }
                        for (BleSession s : targets) {
                            s.sendDeviceEvent(link.mac, "MTU_CHANGED " + newMtu);
                        }
                    } catch (IOException ignored) {}
                }
            }, "hmgapi-ble-mtursp");
            worker.setDaemon(true);
            worker.start();
            return;
        }

        if ((opcode == ATT_OP_HANDLE_VALUE_NTF || opcode == ATT_OP_HANDLE_VALUE_IND) && att.length >= 3) {
            int valHandle = (att[1] & 0xFF) | ((att[2] & 0xFF) << 8);
            byte[] value = Arrays.copyOfRange(att, 3, att.length);
            if (opcode == ATT_OP_HANDLE_VALUE_IND) {
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            sendL2capPduFragmented(link, L2CAP_CID_ATT, new byte[] { (byte) ATT_OP_HANDLE_VALUE_CFM });
                        } catch (IOException ignored) {}
                    }
                }, "hmgapi-ble-indcfm");
                worker.setDaemon(true);
                worker.start();
            }

            GattCharDef ch = link.charsByValueHandle.get(valHandle);
            String uuidStr = ch != null ? ch.uuid : String.format(Locale.US, "handle:0x%04x", valHandle);
            String hex = bytesToHex(value);

            Set<BleSession> subs = ch != null ? link.notifySubscribers.get(ch.uuid) : null;
            if (subs != null) {
                List<BleSession> targets;
                synchronized (subs) {
                    targets = new ArrayList<BleSession>(subs);
                }
                for (BleSession s : targets) {
                    s.sendDeviceEvent(link.mac, "CHAR_NOTIFY " + uuidStr + " " + hex);
                }
            } else if (ch == null) {
                List<BleSession> targets;
                synchronized (link.owners) {
                    targets = new ArrayList<BleSession>(link.owners);
                }
                for (BleSession s : targets) {
                    s.sendDeviceEvent(link.mac, "CHAR_NOTIFY " + uuidStr + " " + hex);
                }
            }
            return;
        }

        CountDownLatch latch = link.waitingAttLatch;
        if (latch != null) {
            if (opcode == ATT_OP_ERROR_RSP && att.length >= 2) {
                int reqOp = att[1] & 0xFF;
                if (reqOp == link.waitingAttReqOpcode) {
                    link.waitingAttResponse = att;
                    latch.countDown();
                }
            } else if (opcode == link.waitingAttReqOpcode + 1) {
                link.waitingAttResponse = att;
                latch.countDown();
            }
        }
    }

    // -------------------------------------------------------------------------
    // UUID, MAC & Hex Utilities
    // -------------------------------------------------------------------------

    static GattCharDef findCharacteristic(Link link, String query) {
        if (query == null) return null;
        String norm = normalizeUuid(query);
        GattCharDef exact = link.charsByUuid.get(norm);
        if (exact != null) return exact;

        String clean = query.trim().toLowerCase(Locale.US);
        if (clean.startsWith("0x")) clean = clean.substring(2);
        for (GattCharDef ch : link.charsByUuid.values()) {
            if (isShortUuidMatch(ch.uuid, clean)) return ch;
        }
        return null;
    }

    static boolean matchesServiceFilter(String fullUuid, String filter) {
        if (filter == null || filter.isEmpty()) return true;
        String clean = filter.trim().toLowerCase(Locale.US);
        if (clean.startsWith("0x")) clean = clean.substring(2);
        return fullUuid.equalsIgnoreCase(normalizeUuid(clean)) || isShortUuidMatch(fullUuid, clean);
    }

    static boolean isShortUuidMatch(String fullUuid, String shortHex4) {
        if (fullUuid == null || shortHex4 == null) return false;
        if (shortHex4.length() == 4 && fullUuid.endsWith(BT_BASE_UUID_SUFFIX)) {
            return fullUuid.substring(4, 8).equalsIgnoreCase(shortHex4);
        }
        return fullUuid.equalsIgnoreCase(shortHex4);
    }

    static String normalizeUuid(String raw) {
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.length() == 4) {
            return "0000" + s + BT_BASE_UUID_SUFFIX;
        }
        if (s.length() == 8) {
            return s + BT_BASE_UUID_SUFFIX;
        }
        return s;
    }

    static String parseAttUuidBytes(byte[] buf, int offset, int len) {
        if (len == 2) {
            int u16 = (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
            return String.format(Locale.US, "0000%04x%s", u16, BT_BASE_UUID_SUFFIX);
        }
        if (len == 16) {
            byte[] rev = new byte[16];
            for (int i = 0; i < 16; i++) rev[i] = buf[offset + 15 - i];
            String hex = bytesToHex(rev);
            return hex.substring(0, 8) + "-"
                 + hex.substring(8, 12) + "-"
                 + hex.substring(12, 16) + "-"
                 + hex.substring(16, 20) + "-"
                 + hex.substring(20, 32);
        }
        return bytesToHex(Arrays.copyOfRange(buf, offset, offset + len));
    }

    static String normalizeMac(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase(Locale.US);
        String[] parts = s.split(":");
        if (parts.length != 6) return null;
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (parts[i].length() != 2) return null;
            try {
                Integer.parseInt(parts[i], 16);
            } catch (NumberFormatException e) {
                return null;
            }
            if (i > 0) sb.append(':');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    static byte[] macToLittleEndianBytes(String mac) {
        String norm = normalizeMac(mac);
        if (norm == null) return null;
        String[] p = norm.split(":");
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[5 - i] = (byte) Integer.parseInt(p[i], 16);
        }
        return out;
    }

    static String littleEndianBytesToMac(byte[] buf, int offset) {
        return String.format(Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                buf[offset + 5] & 0xFF, buf[offset + 4] & 0xFF,
                buf[offset + 3] & 0xFF, buf[offset + 2] & 0xFF,
                buf[offset + 1] & 0xFF, buf[offset] & 0xFF);
    }

    static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = hexChars[v >>> 4];
            out[i * 2 + 1] = hexChars[v & 0x0F];
        }
        return new String(out);
    }

    static byte[] hexToBytes(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if ((s.length() & 1) != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    static int parseIntSafe(String s, int defVal) {
        if (s == null) return defVal;
        try {
            String t = s.trim();
            if (t.startsWith("0x") || t.startsWith("0X")) {
                return Integer.parseInt(t.substring(2), 16);
            }
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    static String sanitizeToken(String s) {
        if (s == null) return "unknown";
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}

package hmgapi.client;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BLE scanner and GATT central client for a mod's own app and services
 * ({@code hmgapi-client.jar}, Android 4.4; requires {@code "ble": {"central": true}}
 * in {@code mod.json}).
 *
 * Connects to HMG-API's BLE service ({@code @hmgapi-ble} inside {@code com.android.bluetooth}),
 * which checks that the calling app or service belongs to an enabled mod that declared BLE:
 *
 * <pre>
 *   Ble ble = Ble.connect(new Ble.SimpleListener() {
 *       public void onAdvertisement(Ble.AdvRecord adv) { ... }
 *       public void onGattReady(String mac, int mtu) {
 *           ble.enableNotifications(mac, "fff1");
 *       }
 *       public void onCharacteristicChanged(String mac, String uuid, byte[] value) { ... }
 *   });
 *   ble.startScan();
 * </pre>
 *
 * {@link #connect} blocks briefly during session setup: keep it off the UI thread.
 * Listener callbacks run on the client's background reader thread.
 */
public final class Ble implements Closeable {
    public static final String SOCKET = "hmgapi-ble";
    private static final String BT_BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    /** Asynchronous BLE scanner, connection, and GATT callbacks. */
    public interface Listener {
        void onSessionReady();
        void onScanStateChanged(boolean scanning);
        void onAdvertisement(AdvRecord record);
        void onConnectionStateChanged(String mac, boolean connected, int handleOrReason);
        void onMtuChanged(String mac, int mtu);
        void onServiceDiscovered(String mac, String serviceUuid, int startHandle, int endHandle);
        void onCharacteristicDiscovered(String mac, String serviceUuid, String charUuid,
                                        int valueHandle, int properties, int cccdHandle);
        void onGattReady(String mac, int mtu);
        void onCharacteristicRead(String mac, String charUuid, byte[] value);
        void onCharacteristicBlobRead(String mac, String charUuid, int offset, byte[] chunk);
        void onCharacteristicLongRead(String mac, String charUuid, byte[] fullValue);
        void onCharacteristicWrite(String mac, String charUuid);
        void onNotifyStateChanged(String mac, String charUuid, boolean enabled);
        void onCharacteristicChanged(String mac, String charUuid, byte[] value);
        void onError(String mac, String error);
        void onSessionClosed(String reason);
    }

    /** No-op adapter for {@link Listener} so callers only override the callbacks they need. */
    public static class SimpleListener implements Listener {
        @Override public void onSessionReady() {}
        @Override public void onScanStateChanged(boolean scanning) {}
        @Override public void onAdvertisement(AdvRecord record) {}
        @Override public void onConnectionStateChanged(String mac, boolean connected, int handleOrReason) {}
        @Override public void onMtuChanged(String mac, int mtu) {}
        @Override public void onServiceDiscovered(String mac, String serviceUuid, int startHandle, int endHandle) {}
        @Override public void onCharacteristicDiscovered(String mac, String serviceUuid, String charUuid,
                                                         int valueHandle, int properties, int cccdHandle) {}
        @Override public void onGattReady(String mac, int mtu) {}
        @Override public void onCharacteristicRead(String mac, String charUuid, byte[] value) {}
        @Override public void onCharacteristicBlobRead(String mac, String charUuid, int offset, byte[] chunk) {}
        @Override public void onCharacteristicLongRead(String mac, String charUuid, byte[] fullValue) {}
        @Override public void onCharacteristicWrite(String mac, String charUuid) {}
        @Override public void onNotifyStateChanged(String mac, String charUuid, boolean enabled) {}
        @Override public void onCharacteristicChanged(String mac, String charUuid, byte[] value) {}
        @Override public void onError(String mac, String error) {}
        @Override public void onSessionClosed(String reason) {}
    }

    /** Parsed BLE advertisement + scan response record. */
    public static final class AdvRecord {
        public final String mac;
        public final int addrType;
        public final int rssi;
        public final byte[] rawAd;
        public final int flags;
        public final String localName;
        public final Integer txPowerLevel;
        public final List<String> serviceUuids;
        public final Map<String, byte[]> serviceData;
        public final Map<Integer, byte[]> manufacturerData;

        public AdvRecord(String mac, int addrType, int rssi, byte[] rawAd) {
            this.mac = mac;
            this.addrType = addrType;
            this.rssi = rssi;
            this.rawAd = rawAd != null ? rawAd.clone() : new byte[0];

            int parsedFlags = -1;
            String parsedName = null;
            Integer parsedTx = null;
            List<String> uuids = new ArrayList<String>();
            Map<String, byte[]> svcData = new LinkedHashMap<String, byte[]>();
            Map<Integer, byte[]> mfgData = new LinkedHashMap<Integer, byte[]>();

            int pos = 0;
            while (pos < this.rawAd.length) {
                int len = this.rawAd[pos] & 0xFF;
                if (len == 0 || pos + 1 + len > this.rawAd.length) break;
                int adType = this.rawAd[pos + 1] & 0xFF;
                int dataStart = pos + 2;
                int dataLen = len - 1;

                switch (adType) {
                    case 0x01:
                        if (dataLen >= 1) parsedFlags = this.rawAd[dataStart] & 0xFF;
                        break;
                    case 0x02:
                    case 0x03:
                        for (int off = 0; off + 2 <= dataLen; off += 2) {
                            int u16 = (this.rawAd[dataStart + off] & 0xFF)
                                    | ((this.rawAd[dataStart + off + 1] & 0xFF) << 8);
                            String u = String.format(Locale.US, "0000%04x%s", u16, BT_BASE_UUID_SUFFIX);
                            if (!uuids.contains(u)) uuids.add(u);
                        }
                        break;
                    case 0x06:
                    case 0x07:
                        for (int off = 0; off + 16 <= dataLen; off += 16) {
                            String u = parseUuid128LittleEndian(this.rawAd, dataStart + off);
                            if (!uuids.contains(u)) uuids.add(u);
                        }
                        break;
                    case 0x08:
                    case 0x09:
                        if (dataLen > 0) {
                            String candidate = decodeUtf8Trimmed(this.rawAd, dataStart, dataLen);
                            if (parsedName == null || adType == 0x09 || candidate.length() > parsedName.length()) {
                                parsedName = candidate;
                            }
                        }
                        break;
                    case 0x0A:
                        if (dataLen >= 1) parsedTx = Integer.valueOf(this.rawAd[dataStart]);
                        break;
                    case 0x16:
                        if (dataLen >= 2) {
                            int u16 = (this.rawAd[dataStart] & 0xFF)
                                    | ((this.rawAd[dataStart + 1] & 0xFF) << 8);
                            String u = String.format(Locale.US, "0000%04x%s", u16, BT_BASE_UUID_SUFFIX);
                            svcData.put(u, Arrays.copyOfRange(this.rawAd, dataStart + 2, dataStart + dataLen));
                        }
                        break;
                    case 0x21:
                        if (dataLen >= 16) {
                            String u = parseUuid128LittleEndian(this.rawAd, dataStart);
                            svcData.put(u, Arrays.copyOfRange(this.rawAd, dataStart + 16, dataStart + dataLen));
                        }
                        break;
                    case 0xFF:
                        if (dataLen >= 2) {
                            int companyId = (this.rawAd[dataStart] & 0xFF)
                                          | ((this.rawAd[dataStart + 1] & 0xFF) << 8);
                            mfgData.put(companyId, Arrays.copyOfRange(this.rawAd, dataStart + 2, dataStart + dataLen));
                        }
                        break;
                    default:
                        break;
                }
                pos += 1 + len;
            }

            this.flags = parsedFlags;
            this.localName = parsedName;
            this.txPowerLevel = parsedTx;
            this.serviceUuids = Collections.unmodifiableList(uuids);
            this.serviceData = Collections.unmodifiableMap(svcData);
            this.manufacturerData = Collections.unmodifiableMap(mfgData);
        }

        public boolean hasServiceUuid(String uuidQuery) {
            if (uuidQuery == null) return false;
            String norm = normalizeUuid(uuidQuery);
            for (String u : serviceUuids) {
                if (u.equalsIgnoreCase(norm)) return true;
            }
            return false;
        }

        public byte[] getServiceData(String uuidQuery) {
            if (uuidQuery == null) return null;
            byte[] data = serviceData.get(normalizeUuid(uuidQuery));
            return data != null ? data.clone() : null;
        }

        public byte[] getManufacturerData(int companyId) {
            byte[] data = manufacturerData.get(companyId & 0xFFFF);
            return data != null ? data.clone() : null;
        }
    }

    private final Listener listener;
    private final Closeable underlying;
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private Ble(Closeable underlying, BufferedReader reader, BufferedWriter writer, Listener listener) {
        this.underlying = underlying;
        this.reader = reader;
        this.writer = writer;
        this.listener = listener;
    }

    /** True if HMG-API's BLE service inside {@code com.android.bluetooth} answers. */
    public static boolean available() {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(2000);
            OutputStream out = sock.getOutputStream();
            out.write("PING\n".getBytes("UTF-8"));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), "UTF-8"), 128);
            String rsp = in.readLine();
            return rsp != null && "PONG".equals(rsp.trim());
        } catch (IOException e) {
            return false;
        } finally {
            try { sock.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Opens a BLE central session on {@code @hmgapi-ble}.
     * Throws {@link IOException} if {@code com.android.bluetooth} is unavailable or if
     * this caller's mod is off or didn't declare {@code "ble": {"central": true}}.
     */
    public static Ble connect(Listener listener) throws IOException {
        LocalSocket sock = new LocalSocket();
        boolean ok = false;
        try {
            sock.connect(new LocalSocketAddress(SOCKET, LocalSocketAddress.Namespace.ABSTRACT));
            sock.setSoTimeout(5000);
            Ble ble = attach(sock.getInputStream(), sock.getOutputStream(), sock, listener);
            sock.setSoTimeout(0);
            ok = true;
            return ble;
        } finally {
            if (!ok) {
                try { sock.close(); } catch (IOException ignored) {}
            }
        }
    }

    /** Attaches a {@link Ble} session to an arbitrary stream pair (used by off-car tests). */
    public static Ble attach(InputStream in, OutputStream out, Closeable closeable, Listener listener) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"), 2048);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"), 1024);

        writer.write("STREAM_SESSION\n");
        writer.flush();

        String line = reader.readLine();
        if (line == null) {
            throw new IOException("ble connection closed before handshake");
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("ERR ")) {
            throw new IOException("ble refused: " + trimmed.substring(4).trim());
        }
        if (!trimmed.startsWith("OK STREAM_READY")) {
            throw new IOException("ble unexpected handshake: " + trimmed);
        }

        writer.write("MULTICONN 1\n");
        writer.flush();

        final Ble ble = new Ble(closeable, reader, writer, listener);
        if (listener != null) {
            try { listener.onSessionReady(); } catch (Throwable ignored) {}
        }
        ble.startReaderThread();
        return ble;
    }

    private void startReaderThread() {
        Thread rx = new Thread("hmgapi-ble-client-rx") {
            @Override
            public void run() {
                String closeReason = "EOF";
                try {
                    String line;
                    while (running.get() && (line = reader.readLine()) != null) {
                        String t = line.trim();
                        if (t.isEmpty()) continue;
                        if ("STATE MOD_DISABLED".equals(t)) {
                            closeReason = "MOD_DISABLED";
                            break;
                        }
                        if ("STATE BT_OFF".equals(t)) {
                            closeReason = "BT_OFF";
                            break;
                        }
                        if ("STATE SHUTDOWN".equals(t)) {
                            closeReason = "SHUTDOWN";
                            break;
                        }
                        dispatchLine(t);
                    }
                } catch (IOException e) {
                    if (running.get()) closeReason = e.getMessage() != null ? e.getMessage() : "IO_ERROR";
                } finally {
                    boolean wasRunning = running.getAndSet(false);
                    closeStreamsQuietly();
                    if (wasRunning && listener != null) {
                        try { listener.onSessionClosed(closeReason); } catch (Throwable ignored) {}
                    }
                }
            }
        };
        rx.setDaemon(true);
        rx.start();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false)) return;
        sendRawCommand("CLOSE");
        closeStreamsQuietly();
    }

    public boolean isOpen() {
        return running.get();
    }

    private void closeStreamsQuietly() {
        try { writer.close(); } catch (Throwable ignored) {}
        try { reader.close(); } catch (Throwable ignored) {}
        if (underlying != null) {
            try { underlying.close(); } catch (Throwable ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // BLE Scanner & GATT Central Operations
    // -------------------------------------------------------------------------

    public boolean startScan() {
        return sendRawCommand("SCAN_START");
    }

    public boolean stopScan() {
        return sendRawCommand("SCAN_STOP");
    }

    public boolean connectDevice(String mac) {
        return connectDevice(mac, -1, null);
    }

    /**
     * Connects to {@code mac} and discovers its GATT attributes.
     *
     * @param addrType {@code 0} (public), {@code 1} (random), or {@code -1} (auto)
     * @param serviceUuidFilter optional 4-hex or 128-bit service UUID to filter discovery, or {@code null} for all
     */
    public boolean connectDevice(String mac, int addrType, String serviceUuidFilter) {
        String addrToken = addrType >= 0 ? Integer.toString(addrType) : "AUTO";
        StringBuilder sb = new StringBuilder();
        sb.append("@").append(mac.trim()).append(" CONNECT ").append(addrToken);
        if (serviceUuidFilter != null && !serviceUuidFilter.trim().isEmpty()) {
            sb.append(' ').append(serviceUuidFilter.trim());
        }
        return sendRawCommand(sb.toString());
    }

    public boolean disconnectDevice(String mac) {
        return sendRawCommand("@" + mac.trim() + " DISCONNECT");
    }

    public boolean disconnectAll() {
        return sendRawCommand("DISCONNECT_ALL");
    }

    public boolean requestMtu(String mac, int mtu) {
        return sendRawCommand("@" + mac.trim() + " REQUEST_MTU " + mtu);
    }

    public boolean setMaxServerMtu(int maxMtu) {
        return sendRawCommand("SET_MAX_MTU " + maxMtu);
    }

    /** Marks a characteristic read-only on this link so subsequent write attempts are rejected. */
    public boolean protectCharacteristic(String mac, String charUuid) {
        return sendRawCommand("@" + mac.trim() + " PROTECT_CHAR " + charUuid.trim());
    }

    public boolean enableNotifications(String mac, String charUuid) {
        return sendRawCommand("@" + mac.trim() + " ENABLE_NOTIFY " + charUuid.trim());
    }

    public boolean enableIndications(String mac, String charUuid) {
        return sendRawCommand("@" + mac.trim() + " ENABLE_NOTIFY " + charUuid.trim() + " INDICATE");
    }

    public boolean disableNotifications(String mac, String charUuid) {
        return sendRawCommand("@" + mac.trim() + " DISABLE_NOTIFY " + charUuid.trim());
    }

    public boolean readCharacteristic(String mac, String charUuid) {
        return sendRawCommand("@" + mac.trim() + " READ_CHAR " + charUuid.trim());
    }

    public boolean readCharacteristicBlob(String mac, String charUuid, int offset) {
        return sendRawCommand("@" + mac.trim() + " READ_BLOB " + charUuid.trim() + " " + offset);
    }

    public boolean readLongCharacteristic(String mac, String charUuid) {
        return sendRawCommand("@" + mac.trim() + " READ_LONG_CHAR " + charUuid.trim());
    }

    public boolean writeCharacteristic(String mac, String charUuid, byte[] payload, boolean withResponse) {
        if (payload == null || payload.length == 0) return false;
        String verb = withResponse ? "WRITE_CHAR" : "WRITE_CHAR_NR";
        return sendRawCommand("@" + mac.trim() + " " + verb + " " + charUuid.trim() + " " + bytesToHex(payload));
    }

    public boolean ping() {
        return sendRawCommand("PING");
    }

    private boolean sendRawCommand(String cmdLine) {
        if (!running.get()) return false;
        synchronized (writeLock) {
            try {
                writer.write(cmdLine);
                writer.write('\n');
                writer.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inbound Event Parsing
    // -------------------------------------------------------------------------

    private void dispatchLine(String rawLine) {
        if (listener == null) return;
        String mac = null;
        String line = rawLine;
        if (line.startsWith("@")) {
            int sp = line.indexOf(' ');
            if (sp <= 1) return;
            mac = line.substring(1, sp).trim();
            line = line.substring(sp + 1).trim();
        }

        String[] p = line.split("\\s+");
        if (p.length == 0) return;
        String ev = p[0];

        if ("OK".equals(ev) || "PONG".equals(ev)) {
            return;
        }
        if ("STATE".equals(ev) && p.length >= 2) {
            if ("SCANNING".equals(p[1])) {
                listener.onScanStateChanged(true);
            } else if ("SCAN_STOPPED".equals(p[1])) {
                listener.onScanStateChanged(false);
            }
            return;
        }
        if ("ADV".equals(ev) && p.length >= 4) {
            String advMac = p[1];
            int addrType = parseIntSafe(p[2], 0);
            int rssi = parseIntSafe(p[3], -100);
            byte[] ad = p.length >= 5 ? hexToBytes(p[4]) : new byte[0];
            listener.onAdvertisement(new AdvRecord(advMac, addrType, rssi, ad != null ? ad : new byte[0]));
            return;
        }
        if ("CONN_STATE".equals(ev) && p.length >= 3) {
            String state = p[1];
            String devMac = p[2];
            int code = p.length >= 4 ? parseIntSafe(p[3], 0) : 0;
            if ("CONNECTED".equals(state)) {
                listener.onConnectionStateChanged(devMac, true, code);
            } else if ("DISCONNECTED".equals(state)) {
                listener.onConnectionStateChanged(devMac, false, code);
            }
            return;
        }
        if ("MTU_CHANGED".equals(ev) && p.length >= 2) {
            listener.onMtuChanged(mac, parseIntSafe(p[1], 23));
            return;
        }
        if ("GATT_SERVICE".equals(ev) && p.length >= 4) {
            listener.onServiceDiscovered(mac, p[1], parseIntSafe(p[2], 0), parseIntSafe(p[3], 0));
            return;
        }
        if ("GATT_CHAR".equals(ev) && p.length >= 6) {
            listener.onCharacteristicDiscovered(mac, p[1], p[2],
                    parseIntSafe(p[3], 0), parseIntSafe(p[4], 0), parseIntSafe(p[5], 0));
            return;
        }
        if ("GATT_READY".equals(ev)) {
            String devMac = p.length >= 2 ? p[1] : mac;
            int mtu = 23;
            for (int i = 2; i < p.length; i++) {
                if (p[i].startsWith("mtu=")) mtu = parseIntSafe(p[i].substring(4), 23);
            }
            listener.onGattReady(devMac, mtu);
            return;
        }
        if ("CHAR_VALUE".equals(ev) && p.length >= 2) {
            byte[] val = p.length >= 3 ? hexToBytes(p[2]) : new byte[0];
            listener.onCharacteristicRead(mac, p[1], val != null ? val : new byte[0]);
            return;
        }
        if ("CHAR_BLOB".equals(ev) && p.length >= 3) {
            int offset = parseIntSafe(p[2], 0);
            byte[] val = p.length >= 4 ? hexToBytes(p[3]) : new byte[0];
            listener.onCharacteristicBlobRead(mac, p[1], offset, val != null ? val : new byte[0]);
            return;
        }
        if ("CHAR_LONG_VALUE".equals(ev) && p.length >= 2) {
            byte[] val = p.length >= 3 ? hexToBytes(p[2]) : new byte[0];
            listener.onCharacteristicLongRead(mac, p[1], val != null ? val : new byte[0]);
            return;
        }
        if ("WRITE_OK".equals(ev) && p.length >= 2) {
            listener.onCharacteristicWrite(mac, p[1]);
            return;
        }
        if ("NOTIFY_STATE".equals(ev) && p.length >= 3) {
            boolean enabled = "ENABLED".equals(p[2]) || "INDICATE".equals(p[2]);
            listener.onNotifyStateChanged(mac, p[1], enabled);
            return;
        }
        if ("CHAR_NOTIFY".equals(ev) && p.length >= 2) {
            byte[] val = p.length >= 3 ? hexToBytes(p[2]) : new byte[0];
            listener.onCharacteristicChanged(mac, p[1], val != null ? val : new byte[0]);
            return;
        }
        if ("ERR".equals(ev)) {
            listener.onError(mac, line.length() > 4 ? line.substring(4) : "UNKNOWN");
        }
    }

    public static String normalizeUuid(String raw) {
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("0x")) s = s.substring(2);
        if (s.length() == 4) return "0000" + s + BT_BASE_UUID_SUFFIX;
        if (s.length() == 8) return s + BT_BASE_UUID_SUFFIX;
        return s;
    }

    private static String parseUuid128LittleEndian(byte[] buf, int offset) {
        char[] hexChars = "0123456789abcdef".toCharArray();
        char[] hex = new char[32];
        for (int i = 0; i < 16; i++) {
            int v = buf[offset + 15 - i] & 0xFF;
            hex[i * 2] = hexChars[v >>> 4];
            hex[i * 2 + 1] = hexChars[v & 0x0F];
        }
        String s = new String(hex);
        return s.substring(0, 8) + "-"
             + s.substring(8, 12) + "-"
             + s.substring(12, 16) + "-"
             + s.substring(16, 20) + "-"
             + s.substring(20, 32);
    }

    private static String decodeUtf8Trimmed(byte[] buf, int offset, int len) {
        int actualLen = len;
        while (actualLen > 0 && buf[offset + actualLen - 1] == 0) actualLen--;
        try {
            return new String(buf, offset, actualLen, "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static String bytesToHex(byte[] bytes) {
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

    public static byte[] hexToBytes(String hex) {
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

    private static int parseIntSafe(String s, int defVal) {
        if (s == null) return defVal;
        try {
            String t = s.trim();
            if (t.startsWith("0x") || t.startsWith("0X")) return Integer.parseInt(t.substring(2), 16);
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return defVal;
        }
    }
}

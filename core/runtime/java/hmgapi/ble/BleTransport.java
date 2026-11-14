package hmgapi.ble;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Transport seam between {@code BleCentral} and the native Bluedroid HCI/ACL filter
 * in {@code libhmgapi_hook.so} (or a simulated controller in off-car tests).
 *
 * Pure Java (no {@code android.*} classes), so {@code hmgapi.ble} compiles and runs
 * directly on a host JDK in {@code core/runtime/ble-test.sh}.
 */
public interface BleTransport {
    int PKT_CMD = 0x01;
    int PKT_ACL = 0x02;
    int PKT_EVT = 0x04;

    int STAT_HOOK_INSTALLED = 0;
    int STAT_TX_THREAD      = 1;
    int STAT_PKT_RX         = 2;
    int STAT_PKT_TX         = 3;
    int STAT_PKT_DROP       = 4;
    int STAT_LISTENING      = 5;
    int STAT_LE_HANDLES     = 6;

    final class Packet {
        public final int type;
        public final byte[] payload;

        public Packet(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }

    interface Receiver {
        void onPacket(int pktType, byte[] payload);
    }

    /** Attaches (or re-attaches after an adapter restart) to the HCI transport. */
    boolean ensureReady(Receiver receiver) throws IOException;

    boolean isReady();

    /** Detaches the native filter and closes transport pipes (e.g. when Bluetooth turns off). */
    void shutdown();

    void sendCommand(int opcode, byte[] params) throws IOException;

    void sendAcl(int connHandle, int pbFlag, byte[] l2capPayload) throws IOException;

    void setListening(boolean listening);

    void trackHandle(int connHandle);

    void untrackHandle(int connHandle);

    void clearHandles();

    int stat(int which);

    void log(int priority, String message);

    /** Pipe framing helper shared by {@code BleHooks} and host tests: {@code [u2 len BE][u1 type][payload]}. */
    final class Framing {
        private Framing() {}

        public static void writePacket(OutputStream out, int pktType, byte[] payload) throws IOException {
            int payloadLen = payload == null ? 0 : payload.length;
            int totalLen = 1 + payloadLen;
            byte[] frame = new byte[2 + totalLen];
            frame[0] = (byte) ((totalLen >> 8) & 0xFF);
            frame[1] = (byte) (totalLen & 0xFF);
            frame[2] = (byte) (pktType & 0xFF);
            if (payloadLen > 0) {
                System.arraycopy(payload, 0, frame, 3, payloadLen);
            }
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        }

        public static Packet readPacket(InputStream in) throws IOException {
            byte[] hdr = new byte[2];
            readExact(in, hdr, 0, 2);
            int pktLen = ((hdr[0] & 0xFF) << 8) | (hdr[1] & 0xFF);
            if (pktLen < 1 || pktLen > 1032) {
                throw new IOException("invalid BLE pipe frame length: " + pktLen);
            }
            byte[] body = new byte[pktLen];
            readExact(in, body, 0, pktLen);
            int pktType = body[0] & 0xFF;
            byte[] payload = new byte[pktLen - 1];
            if (payload.length > 0) {
                System.arraycopy(body, 1, payload, 0, payload.length);
            }
            return new Packet(pktType, payload);
        }

        public static byte[] buildCmdPayload(int opcode, byte[] params) {
            int pLen = params == null ? 0 : params.length;
            byte[] cmd = new byte[3 + pLen];
            cmd[0] = (byte) (opcode & 0xFF);
            cmd[1] = (byte) ((opcode >> 8) & 0xFF);
            cmd[2] = (byte) (pLen & 0xFF);
            if (pLen > 0) {
                System.arraycopy(params, 0, cmd, 3, pLen);
            }
            return cmd;
        }

        public static byte[] buildAclPayload(int connHandle, int pbFlag, byte[] l2capPayload) {
            int dLen = l2capPayload == null ? 0 : l2capPayload.length;
            int hdrWord = (connHandle & 0x0FFF) | ((pbFlag & 0x03) << 12);
            byte[] acl = new byte[4 + dLen];
            acl[0] = (byte) (hdrWord & 0xFF);
            acl[1] = (byte) ((hdrWord >> 8) & 0xFF);
            acl[2] = (byte) (dLen & 0xFF);
            acl[3] = (byte) ((dLen >> 8) & 0xFF);
            if (dLen > 0) {
                System.arraycopy(l2capPayload, 0, acl, 4, dLen);
            }
            return acl;
        }

        private static void readExact(InputStream in, byte[] buf, int off, int len) throws IOException {
            int got = 0;
            while (got < len) {
                int r = in.read(buf, off + got, len - got);
                if (r < 0) throw new EOFException();
                got += r;
            }
        }
    }
}

package hmgapi.app;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Client for hmgapid (abstract socket @hmgapid). Request: "LFD1", u32 argc,
 * then argc x (u32 len, bytes); response frames 'O' u32 len bytes (output) and
 * 'X' u32 exit code; all little-endian. hmgapid decides what this app's uid may do.
 */
final class Hmgapid {
    private Hmgapid() {}

    static final class Result {
        int code = -1;
        String output = "";
    }

    /**
     * What to show when hmgapid can't be reached. With /system/bin/hmgapid gone and
     * /data/hmgapi still there, a firmware update put /system back to stock: HMG-API's
     * own files went with it, and installing HMG-API again brings them back.
     */
    static String unreachable(Exception e) {
        if (!new java.io.File("/system/bin/hmgapid").exists()) {
            return "HMG-API's system files are gone, most likely removed by a firmware update. Install HMG-API again"
                    + " (NEMESIS or the USB stick): it starts again from the new firmware's files. To remove it instead,"
                    + " uninstall it in NEMESIS Manager.";
        }
        return "Can't reach hmgapid: " + e.getMessage();
    }

    /** Runs one op; blocking (call it off the UI thread). */
    static Result call(String... args) throws IOException {
        return call(60000, args);
    }

    /** timeoutMs: how long to wait for output (the engine prints only when it's done). */
    static Result call(int timeoutMs, String... args) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress("hmgapid", LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(timeoutMs);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[] { 'L', 'F', 'D', '1' });
            u32(req, args.length);
            for (String a : args) {
                byte[] b = a.getBytes("UTF-8");
                u32(req, b.length);
                req.write(b);
            }
            OutputStream out = s.getOutputStream();
            out.write(req.toByteArray());
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            Result r = new Result();
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                int type = in.read();
                if (type < 0) break;
                byte[] n = new byte[4];
                in.readFully(n);
                int len = (n[0] & 0xff) | (n[1] & 0xff) << 8 | (n[2] & 0xff) << 16 | (n[3] & 0xff) << 24;
                byte[] data = new byte[len];
                in.readFully(data);
                if (type == 'O') text.write(data);
                else if (type == 'X' && len == 4) {
                    r.code = (data[0] & 0xff) | (data[1] & 0xff) << 8 | (data[2] & 0xff) << 16 | (data[3] & 0xff) << 24;
                    break;
                }
            }
            r.output = text.toString("UTF-8");
            return r;
        } finally {
            s.close();
        }
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
        o.write(v >> 16 & 0xff);
        o.write(v >> 24 & 0xff);
    }
}

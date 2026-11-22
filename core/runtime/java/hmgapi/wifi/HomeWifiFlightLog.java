package hmgapi.wifi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Persistent, fsync-backed Parked Session & Keep-Awake Flight Recorder for HMG-API Home Wi-Fi.
 *
 * Maintains two bounded ring journals (<= 128 KB each, ~5 parked sessions):
 *   1. Owner log (/data/hmgapi/wifi/session.log + .prev, 0600 root-only): includes SSIDs for the
 *      in-car Settings -> Home Wi-Fi "Session Log" viewer.
 *   2. Scrubbed public log (/data/hmgapi/public/wifi-session.log + .prev, 0644): zero SSIDs, PSKs,
 *      BSSIDs/MACs, full IPs, or PII so ProblemLog, USB exports, and OpenLink can bundle it safely.
 *
 * Also maintains /data/hmgapi/wifi/session.active while a parked session is live so if MICOM cuts
 * 12V power mid-session (wiping volatile RAM logcat), the next daemon start immediately records
 * UNEXPECTED_POWER_CUT with the exact last fsync'd phase, power state, and wall-clock gap.
 */
public final class HomeWifiFlightLog {
    public static final int MAX_FILE_BYTES = 64 * 1024;
    public static final long HEARTBEAT_INTERVAL_MS = 15000L;

    private static final String[] USB_CANDIDATES = {
            "/storage/usb0",
            "/storage/usb1",
            "/storage/usb2",
            "/storage/usb3",
            "/mnt/media_rw/usb0",
            "/mnt/media_rw/usb1",
            "/udisk"
    };

    private static final Pattern PAT_MAC =
            Pattern.compile("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b");
    private static final Pattern PAT_UUID =
            Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern PAT_VIN =
            Pattern.compile("\\b[A-HJ-NPR-Z0-9]{17}\\b");
    private static final Pattern PAT_LONG_DIGITS =
            Pattern.compile("\\b\\d{14,20}\\b");
    private static final Pattern PAT_IPV4 =
            Pattern.compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\.\\d{1,3}\\b");

    private final File rootDir;
    private final File wifiDir;
    private final File publicDir;
    private final File ownerLog;
    private final File ownerPrev;
    private final File publicLog;
    private final File publicPrev;
    private final File activeMarker;

    public HomeWifiFlightLog(File rootDir) {
        this.rootDir = rootDir == null ? new File("/") : rootDir;
        File hmgDir = new File(this.rootDir, "data/hmgapi");
        this.wifiDir = new File(hmgDir, "wifi");
        this.publicDir = new File(hmgDir, "public");
        this.ownerLog = new File(wifiDir, "session.log");
        this.ownerPrev = new File(wifiDir, "session.log.prev");
        this.publicLog = new File(publicDir, "wifi-session.log");
        this.publicPrev = new File(publicDir, "wifi-session.log.prev");
        this.activeMarker = new File(wifiDir, "session.active");
    }

    public File publicLogFile() { return publicLog; }
    public File publicPrevFile() { return publicPrev; }

    public static String hashBootTag(String bootId) {
        if (bootId == null || bootId.trim().isEmpty()) return "boot0000";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(("hmgapi-boot:" + bootId.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.US, "%02x", Integer.valueOf(dig[i] & 0xFF)));
            }
            return sb.toString();
        } catch (Exception e) {
            return "boot0000";
        }
    }

    public static String redactIp(String ip) {
        if (ip == null || ip.trim().isEmpty() || "0.0.0.0".equals(ip.trim())) return "-";
        return PAT_IPV4.matcher(ip.trim()).replaceAll("$1.x");
    }

    public static String redactSsid(String ssid, List<HomeWifiStore.Profile> savedProfiles) {
        String clean = WifiSecurity.cleanSsid(ssid);
        if (clean.isEmpty()) return "none";
        if (savedProfiles != null) {
            int idx = 1;
            for (HomeWifiStore.Profile p : savedProfiles) {
                if (p != null && clean.equalsIgnoreCase(p.ssid)) {
                    return p.useForHomeWifi ? ("saved#" + idx) : ("excluded#" + idx);
                }
                idx++;
            }
        }
        if (WifiSecurity.isLikelyDashcamSsid(clean)) return "dashcam";
        return "net";
    }

    public static String scrubPublic(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = PAT_UUID.matcher(raw).replaceAll("[id]");
        s = PAT_MAC.matcher(s).replaceAll("[mac]");
        s = PAT_VIN.matcher(s).replaceAll("[vin]");
        s = PAT_LONG_DIGITS.matcher(s).replaceAll("[num]");
        s = PAT_IPV4.matcher(s).replaceAll("$1.x");
        return s;
    }

    /**
     * Records daemon startup and inspects session.active to detect if the prior parked session
     * was terminated abruptly by a MICOM 12V power cut (cold boot) or process kill.
     */
    public synchronized void recordDaemonStart(String bootId,
                                               long wallEpochSec,
                                               long elapsedMs,
                                               long suspendGapMs) {
        String curBootTag = hashBootTag(bootId);
        if (activeMarker.isFile()) {
            try {
                String raw = readUtf8(activeMarker).trim();
                String[] f = raw.split("\t", 6);
                String prevBootTag = f.length > 0 ? sanitizeToken(f[0]) : "unknown";
                long prevWallSec = f.length > 1 ? parseLongSafe(f[1], 0L) : 0L;
                long prevElapsedMs = f.length > 2 ? parseLongSafe(f[2], 0L) : 0L;
                String prevPhase = f.length > 3 ? sanitizeToken(f[3]) : "unknown";
                String prevPower = f.length > 4 ? sanitizeToken(f[4]) : "unknown";
                String prevEvent = f.length > 5 ? sanitizeInline(f[5]) : "unknown";

                String cutKind = !prevBootTag.equals(curBootTag)
                        ? "COLD_BOOT_CUT"
                        : "PROC_RESTART_SAME_BOOT";
                long wallDeltaSec = (wallEpochSec > 1000000000L && prevWallSec > 1000000000L && wallEpochSec >= prevWallSec)
                        ? (wallEpochSec - prevWallSec)
                        : -1L;
                String detail = "outcome=" + cutKind
                        + " prevBoot=" + prevBootTag
                        + " prevElapsedMs=" + prevElapsedMs
                        + " prevPhase=" + prevPhase
                        + " prevPower=" + prevPower
                        + (wallDeltaSec >= 0 ? (" wallGapSec=" + wallDeltaSec) : "")
                        + " last=" + prevEvent;
                recordEvent(wallEpochSec, elapsedMs, curBootTag, "UNEXPECTED_POWER_CUT", detail, scrubPublic(detail), true);
            } catch (Exception ignored) {
            } finally {
                activeMarker.delete();
            }
        }
        String bootDetail = "boot=" + curBootTag + " elapsedMs=" + elapsedMs + " suspendGapMs=" + suspendGapMs;
        recordEvent(wallEpochSec, elapsedMs, curBootTag, "BOOT_START", bootDetail, bootDetail, true);
    }

    public synchronized void markSessionActive(String bootId,
                                               long wallEpochSec,
                                               long elapsedMs,
                                               String phase,
                                               String power,
                                               String lastEventSummary) {
        try {
            ensureOwnerDir(wifiDir);
            String line = hashBootTag(bootId) + "\t"
                    + wallEpochSec + "\t"
                    + elapsedMs + "\t"
                    + sanitizeToken(phase) + "\t"
                    + sanitizeToken(power) + "\t"
                    + sanitizeInline(scrubPublic(lastEventSummary)) + "\n";
            File tmp = new File(wifiDir, ".session.active.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { out.getFD().sync(); } catch (IOException ignored) {}
            } finally {
                out.close();
            }
            setPerms(tmp, false);
            tmp.renameTo(activeMarker);
        } catch (IOException ignored) {
        }
    }

    public synchronized void clearSessionActive() {
        activeMarker.delete();
    }

    public synchronized boolean hasActiveMarker() {
        return activeMarker.isFile();
    }

    public synchronized void recordEvent(long wallEpochSec,
                                         long elapsedMs,
                                         String bootIdOrTag,
                                         String tag,
                                         String ownerDetail,
                                         String publicDetail,
                                         boolean doFsync) {
        String bootTag = (bootIdOrTag != null && bootIdOrTag.length() == 8 && isHex(bootIdOrTag))
                ? bootIdOrTag
                : hashBootTag(bootIdOrTag);
        String prefix = formatTimestamp(wallEpochSec)
                + " [+" + formatElapsedSec(elapsedMs) + " b:" + bootTag + "] "
                + sanitizeToken(tag);
        String ownBody = sanitizeInline(ownerDetail);
        String pubBody = scrubPublic(sanitizeInline(publicDetail));

        String ownLine = ownBody.isEmpty() ? (prefix + "\n") : (prefix + " " + ownBody + "\n");
        String pubLine = pubBody.isEmpty() ? (prefix + "\n") : (prefix + " " + pubBody + "\n");

        try {
            ensureOwnerDir(wifiDir);
            appendLine(ownerLog, ownerPrev, ownLine, false, doFsync);
        } catch (IOException ignored) {
        }
        try {
            if (!publicDir.isDirectory()) {
                publicDir.mkdirs();
                publicDir.setReadable(true, false);
                publicDir.setExecutable(true, false);
            }
            appendLine(publicLog, publicPrev, pubLine, true, doFsync);
        } catch (IOException ignored) {
        }
    }

    public synchronized String readOwnerTail(int maxBytes) {
        return readCombinedTail(ownerPrev, ownerLog, maxBytes);
    }

    public synchronized String readPublicTail(int maxBytes) {
        return readCombinedTail(publicPrev, publicLog, maxBytes);
    }

    public synchronized void clear() {
        ownerLog.delete();
        ownerPrev.delete();
        publicLog.delete();
        publicPrev.delete();
        activeMarker.delete();
    }

    /**
     * Exports the scrubbed public session log to all mounted USB sticks under "HMG-API reports/".
     * Returns the list of saved paths, or empty list if no USB drive is mounted.
     */
    public synchronized List<String> savePublicToUsb(long wallEpochSec) throws IOException {
        String content = readPublicTail(MAX_FILE_BYTES * 2);
        if (content.trim().isEmpty()) {
            content = "(No parked Home Wi-Fi session events recorded yet.)\n";
        }
        String ts = formatFileStamp(wallEpochSec);
        String fileName = "homewifi-session-" + ts + ".txt";
        String body = "# HMG-API Home Wi-Fi Parked Session Log (scrubbed)\n"
                + "# Exported: " + formatTimestamp(wallEpochSec) + "\n\n"
                + scrubPublic(content);

        String mounts = readMountsIfPresent();
        List<String> saved = new ArrayList<String>();
        boolean realRoot = "/".equals(rootDir.getPath());

        for (String cand : USB_CANDIDATES) {
            File usbRoot = realRoot ? new File(cand) : new File(rootDir, cand.substring(1));
            if (!usbRoot.isDirectory() || usbRoot.list() == null) continue;
            if (mounts != null && !isMountPresent(mounts, cand)) continue;
            File outDir = new File(usbRoot, "HMG-API reports");
            if (!outDir.isDirectory() && !outDir.mkdirs()) continue;
            File target = new File(outDir, fileName);
            FileOutputStream out = new FileOutputStream(target);
            try {
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
                try { out.getFD().sync(); } catch (IOException ignored) {}
            } finally {
                out.close();
            }
            saved.add(cand + "/HMG-API reports/" + fileName);
        }
        return saved;
    }

    private String readMountsIfPresent() {
        File pm = new File(rootDir, "proc/mounts");
        if (!pm.isFile()) return null;
        try {
            return readUtf8(pm);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isMountPresent(String mounts, String mountPoint) {
        for (String line : mounts.split("\n")) {
            String[] parts = line.split(" ");
            if (parts.length >= 2 && mountPoint.equals(parts[1])) {
                return true;
            }
        }
        return false;
    }

    private static void appendLine(File mainFile,
                                   File prevFile,
                                   String line,
                                   boolean worldReadable,
                                   boolean doFsync) throws IOException {
        if (mainFile.isFile() && mainFile.length() >= MAX_FILE_BYTES) {
            prevFile.delete();
            mainFile.renameTo(prevFile);
        }
        boolean created = !mainFile.exists();
        FileOutputStream out = new FileOutputStream(mainFile, true);
        try {
            out.write(line.getBytes(StandardCharsets.UTF_8));
            out.flush();
            if (doFsync) {
                try { out.getFD().sync(); } catch (IOException ignored) {}
            }
        } finally {
            out.close();
        }
        if (created || worldReadable) {
            setPerms(mainFile, worldReadable);
        }
    }

    private static String readCombinedTail(File prevFile, File mainFile, int maxBytes) {
        int cap = maxBytes <= 0 ? (MAX_FILE_BYTES * 2) : maxBytes;
        byte[] a = readFileBytes(prevFile);
        byte[] b = readFileBytes(mainFile);
        int total = a.length + b.length;
        if (total == 0) return "";
        byte[] merged = new byte[total];
        if (a.length > 0) System.arraycopy(a, 0, merged, 0, a.length);
        if (b.length > 0) System.arraycopy(b, 0, merged, a.length, b.length);
        if (merged.length <= cap) {
            return new String(merged, StandardCharsets.UTF_8);
        }
        int start = merged.length - cap;
        while (start < merged.length && merged[start] != '\n') {
            start++;
        }
        if (start < merged.length && merged[start] == '\n') start++;
        return new String(merged, start, merged.length - start, StandardCharsets.UTF_8);
    }

    private static byte[] readFileBytes(File f) {
        if (f == null || !f.isFile()) return new byte[0];
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            if (len <= 0) return new byte[0];
            int n = (int) Math.min(len, MAX_FILE_BYTES * 2L);
            if (len > n) raf.seek(len - n);
            byte[] buf = new byte[n];
            raf.readFully(buf);
            return buf;
        } catch (IOException e) {
            return new byte[0];
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static String readUtf8(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private static void ensureOwnerDir(File d) {
        if (!d.isDirectory()) d.mkdirs();
        d.setReadable(false, false);
        d.setWritable(false, false);
        d.setExecutable(false, false);
        d.setReadable(true, true);
        d.setWritable(true, true);
        d.setExecutable(true, true);
    }

    private static void setPerms(File f, boolean worldReadable) {
        f.setReadable(false, false);
        f.setWritable(false, false);
        f.setExecutable(false, false);
        if (worldReadable) {
            f.setReadable(true, false);
            f.setWritable(true, true);
        } else {
            f.setReadable(true, true);
            f.setWritable(true, true);
        }
    }

    private static String formatTimestamp(long wallEpochSec) {
        long sec = wallEpochSec > 0 ? wallEpochSec : (System.currentTimeMillis() / 1000L);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date(sec * 1000L));
    }

    private static String formatFileStamp(long wallEpochSec) {
        long sec = wallEpochSec > 0 ? wallEpochSec : (System.currentTimeMillis() / 1000L);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        fmt.setTimeZone(TimeZone.getDefault());
        return fmt.format(new Date(sec * 1000L));
    }

    private static String formatElapsedSec(long elapsedMs) {
        if (elapsedMs < 0) elapsedMs = 0;
        long sec = elapsedMs / 1000L;
        long tenth = (elapsedMs % 1000L) / 100L;
        return sec + "." + tenth + "s";
    }

    private static String sanitizeToken(String s) {
        if (s == null || s.trim().isEmpty()) return "-";
        return s.trim().replace('\t', '_').replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }

    private static String sanitizeInline(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
    }

    private static long parseLongSafe(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) return false;
        }
        return true;
    }
}

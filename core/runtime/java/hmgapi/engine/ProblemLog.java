package hmgapi.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * engine problem record [--no-log] [--crash-dumps] [--crash-reports] [--kernel] [--usb] [--seconds N]
 *   | stop: the problem log, for an
 * owner with an issue (a crash, a feature that stops) to send to whoever is helping them.
 * Separate from the Car Report: the owner starts it, makes the problem happen, stops it.
 *
 * What it keeps, and nothing else (the app's "Record a problem" page says the same):
 *  - the head unit (model, region, firmware, platform) and HMG-API's version;
 *  - logcat lines (main and system buffers, never radio or events) from the tags in
 *    TAGS/PREFIXES only: crashes, CarPlay, Android Auto, audio, Bluetooth connections, Wi-Fi hotspot, HMG-API
 *    and its mods. Everything else is read and dropped, only counted. Every kept line is
 *    scrubbed (Report.scrub + PHONE_IDS);
 *  - each installed mod (id, version, on, whether it supports this car) and the md5 of
 *    the system libraries its native patches are for (stock files, model-level);
 *  - HMG-API's recent errors (as in the Car Report), and its state: the hook switches and the
 *    boot guard (/data/hmgapi/hook), and HMG-API's own lines in the kernel log ("hmgapi-hook":
 *    each zygote start, hooks on or off and why);
 *  - each mod's added system files and whether they're on the unit (md5 or absent);
 *  - with --crash-dumps: the newest crash dumps in /data/tombstones (an exception to
 *    "nothing from /data", which the owner turns on knowingly): per dump only the
 *    crashed process, signal, registers, backtraces and the library map near the fault.
 *    The raw memory (stack, "memory near", "code around") and the process's own log
 *    lines in the dump are dropped.
 *  - with --crash-reports: Android's own crash reports in /data/system/dropbox (also kept
 *    across restarts: a system_server crash at boot is there): app and system crashes, freezes
 *    (ANR) and native crashes, newest MAX_REPORTS, each cut to its header and stack trace
 *    (MAX_REPORT_LINES; native ones trimmed like the dumps), scrubbed. The owner's choice too.
 *  - with --kernel: the kernel log (dmesg), scrubbed, at most MAX_KERNEL bytes. The owner's choice.
 *  - --no-log: no logcat recording, the rest is collected at once (a problem that already happened).
 *
 * Stops on "engine problem stop" (state/problem.stop), after --seconds (default and most
 * MAX_SECONDS), or at MAX_BYTES of kept lines. Writes public/reports/problem-*.txt (and
 * "HMG-API reports" on USB sticks with --usb); prints "problem: <name>", "kept: ...",
 * "usb: ..." lines.
 */
final class ProblemLog {
    private ProblemLog() {}

    static final int MAX_SECONDS = 1800;   // 30 min: a session can drop seconds or minutes in
    static final int MAX_BYTES = 16 << 20;
    static final int MAX_DUMPS = 5;
    static final int MAX_DUMP_LINES = 600;
    static final int MAX_REPORTS = 10;
    static final int MAX_REPORT_LINES = 150;
    static final int MAX_KERNEL = 256 << 10;
    /** dropbox entries kept with --crash-reports: crashes, freezes, watchdog, native crashes */
    static final Pattern DROPBOX = Pattern.compile("^((system_server|system_app|data_app)_(crash|anr|wtf|native_crash)|system_server_watchdog|SYSTEM_TOMBSTONE)@(\\d+)\\.txt(\\.gz)?$");

    /** whole tags kept as they are (after scrubbing) */
    static final String[] TAGS = {
        "AndroidRuntime", "DEBUG", "libc", "dalvikvm", "HMG-API", "LeafiaAAC",
        "TinyALSA", "AudioFlinger", "AudioTrack", "AudioPolicyManagerBase", "audio_hw_primary", "AudioMixer",
        "MicomAudioService", "MicomAudioManager", "MediaFocusControl",
        "CODEC_IF", "CODEC_AACDEC", "ACodec", "OMXCodec", "OMXNodeInstance",
        "PhoneProjection", "GalReceiver", "hostapd", "dhcpcd", "WifiStateMachine", "SoftapController",
    };
    /** tags starting with these */
    static final String[] PREFIXES = {
        "CarPlay", "TccCarPlay", "DiPO", "iAP2", "Iap2", "AAProjection", "Gpm", "Gal", "TCC_OMX", "TCCCDK",
        "AudioService", "AudioManager", "BTCM",   // BTCMService, BTCMStateMachine: Bluetooth connects and drops
    };
    /** ActivityManager logs intents (with their data) too: only its lines about processes dying */
    static final Pattern AM = Pattern.compile("(?i)(has died|crash|anr in|not responding|force finishing|killing|start proc|low memory|died)");
    static final Pattern LINE = Pattern.compile("^\\S+ \\S+\\s+\\d+\\s+\\d+ ([VDIWEF]) (.*?)\\s*: ");

    /** what Report.scrub doesn't catch that phones send: device ids, UUIDs, GPS positions */
    static final Pattern[] PHONE_IDS = {
        Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b"),   // UUIDs
        Pattern.compile("(?i)\\b0000[0-9a-f]{4}-[0-9a-f]{16}\\b"),                                // newer iPhone UDIDs
        Pattern.compile("(?i)\\b[0-9a-f]{24,}\\b"),                                                // older UDIDs, tokens
        Pattern.compile("-?\\b\\d{1,3}\\.\\d{4,}\\s*[,/ ]\\s*-?\\d{1,3}\\.\\d{4,}\\b"),           // latitude, longitude
        Pattern.compile("(?i)(?<=serial ?(number)?[:=] ?)\\S+"),                                 // USB device serials (kernel log)
    };

    static String scrub(String s) {
        for (Pattern p : PHONE_IDS) s = p.matcher(s).replaceAll("[removed]");   // first: Report's number patterns would split ids
        return Report.scrub(s);
    }

    /** a logcat line with its message scrubbed (the date, pids and tag are logcat's own, and look like numbers) */
    static String scrubLine(String l, Matcher m) {
        return l.substring(0, m.end()) + scrub(l.substring(m.end()));
    }

    /** a dump's own format: registers, backtrace frames, map ranges (hex numbers only, nothing to scrub) */
    static final Pattern DUMP_FORMAT = Pattern.compile("^\\s+((r\\d+|ip|sp|lr|pc|cpsr|fp|sl|d\\d+|scr)\\s+[0-9a-f]{8}\\b.*|#\\d+\\s+pc\\s+[0-9a-f]{8}\\s.*|[0-9a-f]{8}-[0-9a-f]{8}\\s.*|\\(no map.*)$");

    /** the tag a line is kept under, or null */
    static boolean keep(String tag, String line) {
        for (String t : TAGS) if (t.equals(tag)) return true;
        for (String p : PREFIXES) if (tag.startsWith(p)) return true;
        return "ActivityManager".equals(tag) && AM.matcher(line).find();
    }

    static int run(Engine e, String[] args) throws Exception {
        String sub = args.length > 1 ? args[1] : "";
        File stop = new File(e.stateDir, "problem.stop");
        if ("stop".equals(sub)) {
            Engine.write(stop, "stop\n");
            e.out.add("stopping");
            return 0;
        }
        if (!"record".equals(sub)) {
            e.out.add("usage: engine problem record [--no-log] [--crash-dumps] [--crash-reports] [--kernel] [--usb] [--seconds N] | stop");
            return 2;
        }
        boolean dumps = false, reports = false, kernel = false, record = true, usb = false;
        int seconds = MAX_SECONDS;
        for (int i = 2; i < args.length; i++) {
            if ("--crash-dumps".equals(args[i])) dumps = true;
            else if ("--crash-reports".equals(args[i])) reports = true;
            else if ("--kernel".equals(args[i])) kernel = true;
            else if ("--no-log".equals(args[i])) record = false;
            else if ("--usb".equals(args[i])) usb = true;
            else if ("--seconds".equals(args[i]) && i + 1 < args.length) seconds = Math.max(1, Math.min(MAX_SECONDS, Integer.parseInt(args[++i])));
        }
        stop.delete();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());

        // logcat: read everything, keep only the allowlisted tags (what's dropped is never stored)
        final StringBuilder kept = new StringBuilder();
        final Map<String, Integer> perTag = new TreeMap<String, Integer>();
        final long[] counts = new long[2];   // seen, kept
        String logcat = e.root + "/system/bin/logcat";
        final Process p = record ? new ProcessBuilder(logcat, "-v", "threadtime", "-b", "main", "-b", "system").redirectErrorStream(true).start() : null;
        Thread reader = new Thread() {
            @Override
            public void run() {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                    String l;
                    while ((l = in.readLine()) != null) {
                        Matcher m = LINE.matcher(l);
                        synchronized (kept) {
                            counts[0]++;
                            if (!m.find() || !keep(m.group(2), l)) continue;
                            if (kept.length() >= MAX_BYTES) continue;
                            counts[1]++;
                            Integer n = perTag.get(m.group(2));
                            perTag.put(m.group(2), n == null ? 1 : n + 1);
                            kept.append(scrubLine(l, m)).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // logcat ended
                }
            }
        };
        reader.setDaemon(true);
        if (record) reader.start();
        long start = System.currentTimeMillis(), end = record ? start + seconds * 1000L : start;
        String why = record ? "time limit (" + seconds + " s)" : "collected only, no recording";
        while (System.currentTimeMillis() < end) {
            if (stop.exists()) { why = "stopped"; break; }
            synchronized (kept) {
                if (kept.length() >= MAX_BYTES) { why = "size limit"; break; }
            }
            if (!reader.isAlive()) { why = "logcat ended"; break; }
            Thread.sleep(250);
        }
        if (p != null) p.destroy();
        if (record) reader.join(2000);
        stop.delete();
        long took = (System.currentTimeMillis() - start) / 1000;

        StringBuilder s = new StringBuilder();
        s.append("HMG-API problem log ").append(stamp).append("\n");
        s.append("HMG-API ").append(Engine.HMGAPI_VERSION);
        String build = Report.installedBuild(e);
        if (build != null) s.append(" (build ").append(build).append(")");
        s.append("\nunit: ");
        for (String k : new String[] {"product", "model", "region", "firmware", "platform", "android"}) s.append(k).append('=').append(e.facts.get(k)).append(' ');
        s.append("\nrecorded: ").append(record ? took + " s, ended by " + why : "no (" + why + ")").append("\n");
        s.append("crash dumps: ").append(dumps ? "included (trimmed: no memory contents)" : "not included").append("\n");
        s.append("crash reports: ").append(reports ? "included (header and stack trace)" : "not included").append("\n");
        s.append("kernel log: ").append(kernel ? "included" : "not included (HMG-API's own lines only)").append("\n");
        s.append("\nThis file holds only what's listed above and below: ").append(record ? "logcat lines from crash, CarPlay, Android Auto,\n"
                + "audio, Bluetooth, Wi-Fi hotspot and HMG-API tags, " : "").append("installed mods and their files, HMG-API's state and errors")
                .append(dumps ? ", trimmed crash dumps" : "").append(reports ? ", Android's crash reports" : "").append(kernel ? ", the kernel log" : "")
                .append(".\nEverything is scrubbed of addresses, numbers and device ids.\n");

        s.append("\n== Mods\n");
        mods(e, s);
        String dmesg = dmesg(e);   // HMG-API's own lines always; the whole log only with --kernel
        s.append("\n== HMG-API state\n");
        hmgapiState(e, s, dmesg);
        s.append("\n== HMG-API errors\n");
        for (Object o : Report.errors(e)) s.append(o).append('\n');
        if (dumps) {
            s.append("\n== Crash dumps\n");
            crashDumps(e, s);
        }
        if (reports) {
            s.append("\n== Crash reports\n");
            crashReports(e, s);
        }
        if (kernel) {
            s.append("\n== Kernel log\n");
            for (String l : dmesg.split("\n")) s.append(scrub(l)).append('\n');
        }
        if (record) {
            s.append("\n== Log (").append(counts[1]).append(" of ").append(counts[0]).append(" lines kept)\n");
            for (Map.Entry<String, Integer> t : perTag.entrySet()) s.append("  ").append(t.getKey()).append(": ").append(t.getValue()).append('\n');
            s.append('\n');
            synchronized (kept) {
                s.append(kept);
            }
        }

        String name = "problem-" + Report.safe(e.facts.get("model")) + "-" + Report.safe(e.facts.get("region")) + "-"
                + Report.safe(e.facts.get("firmware")) + "-" + stamp;
        File dir = new File(e.data, "public/reports");
        dir.mkdirs();
        dir.setReadable(true, false);
        dir.setExecutable(true, false);
        File f = new File(dir, name + ".txt");
        Engine.write(f, s.toString());
        f.setReadable(true, false);
        e.out.add("problem: " + name);
        e.out.add(record ? "kept: " + counts[1] + " of " + counts[0] + " log lines, " + took + " s, ended by " + why : "kept: no log lines (collected only)");
        e.out.add("saved: " + f.getPath());
        if (usb) {
            boolean any = false;
            for (String u : Report.USB) {
                File root = new File(e.root + u);
                if (!root.isDirectory() || root.list() == null) continue;
                File d = new File(root, "HMG-API reports");
                if (!d.isDirectory() && !d.mkdirs()) continue;
                Engine.write(new File(d, name + ".txt"), s.toString());
                e.out.add("usb: " + u + "/HMG-API reports/" + name + ".txt");
                any = true;
            }
            if (!any) e.out.add("usb: no USB stick found (adb pull works too)");
        }
        return 0;
    }

    /** installed mods, and the md5 of each system library their native patches are for */
    @SuppressWarnings("unchecked")
    static void mods(Engine e, StringBuilder s) {
        File[] ds = new File(e.data, "mods").listFiles();
        if (ds == null) { s.append("(none)\n"); return; }
        Arrays.sort(ds);
        for (File d : ds) {
            File mf = new File(d, "mod.json");
            if (!mf.isFile()) continue;
            try {
                Map<String, Object> raw = Json.object(Engine.read(mf));
                String sys = raw.containsKey("system") ? SystemCheck.check(raw.get("system"), e.facts, SystemCheck.asksHotspot(raw)) : null;
                s.append(raw.get("id")).append(' ').append(raw.get("version")).append(new File(d, ".disabled").exists() ? " (off)" : " (on)")
                        .append(sys == null ? "" : ", not for this car (installed anyway)").append('\n');
                Object hooks = raw.get("hooks");
                Object nat = hooks instanceof Map ? ((Map<String, Object>) hooks).get("native") : null;
                if (nat instanceof Map) {
                    for (String lib : new TreeMap<String, Object>((Map<String, Object>) nat).keySet()) {
                        File l = new File(e.root + "/system/lib/" + new File(lib).getName());
                        s.append("  ").append(lib).append(' ').append(l.isFile() ? CarMap.md5(l) : "absent").append('\n');
                    }
                }
                // the system files it adds: absent means its changes never applied here
                File pj = new File(d, "patches.json");
                Object changes = pj.isFile() ? Json.object(Engine.read(pj)).get("changes") : null;
                if (changes instanceof List) {
                    for (Object o : (List<Object>) changes) {
                        if (!(o instanceof Map) || !"add".equals(((Map<String, Object>) o).get("type"))) continue;
                        String path = String.valueOf(((Map<String, Object>) o).get("path"));
                        File t = new File(e.root + path);
                        s.append("  adds ").append(path).append(' ').append(t.isFile() ? CarMap.md5(t) : "absent").append('\n');
                    }
                }
            } catch (Exception x) {
                s.append(d.getName()).append(": ").append(scrub(String.valueOf(x))).append('\n');
            }
        }
    }

    /** the kernel log (dmesg; as root, the whole ring), cut at MAX_KERNEL */
    static String dmesg(Engine e) {
        try {
            String bin = new File(e.root + "/system/bin/dmesg").isFile() ? e.root + "/system/bin/dmesg" : "dmesg";
            String t = Engine.exec(bin).text;
            return t.length() > MAX_KERNEL ? "[cut to the last " + (MAX_KERNEL >> 10) + " KB]\n" + t.substring(t.length() - MAX_KERNEL) : t;
        } catch (Exception x) {
            return "[dmesg: " + x.getMessage() + "]";
        }
    }

    /** the hook switches, the boot guard, and HMG-API's own kernel lines (its zygote wrapper's, one per start) */
    static void hmgapiState(Engine e, StringBuilder s, String dmesg) {
        File hook = new File(e.data, "hook");
        String starts = "absent";
        try {
            File f = new File(hook, "starts");
            if (f.isFile()) starts = Engine.read(f).trim();
        } catch (Exception ignored) {
            // unreadable: left as absent
        }
        s.append("hook/enable: ").append(new File(hook, "enable").exists() ? "yes" : "no")
                .append(", hook/disable: ").append(new File(hook, "disable").exists() ? "yes (hooks switched off)" : "no")
                .append(", hook/starts: ").append(starts).append('\n');
        try {
            if (Integer.parseInt(starts) >= 3) s.append("boot guard: hooks off (the zygote failed to start 3 times in a row with them; a reinstall of HMG-API resets it)\n");
        } catch (NumberFormatException ignored) {
            // no number: nothing to say
        }
        s.append("hook library: ").append(new File(e.root + "/system/lib/libhmgapi_hook.so").isFile() ? "there" : "absent").append('\n');
        int n = 0;
        for (String l : dmesg.split("\n")) {
            if (!l.contains("hmgapi")) continue;
            if (n++ == 0) s.append("kernel log, HMG-API's lines:\n");
            s.append("  ").append(scrub(l.trim())).append('\n');
        }
        if (n == 0) s.append("kernel log: no HMG-API lines (the ring holds only the latest)\n");
        File wifiPrev = new File(e.data, "public/wifi-session.log.prev");
        File wifiCur = new File(e.data, "public/wifi-session.log");
        if (wifiPrev.isFile() || wifiCur.isFile()) {
            s.append("Home Wi-Fi parked session log:\n");
            List<String> wlines = new ArrayList<String>();
            for (File wf : new File[] { wifiPrev, wifiCur }) {
                if (!wf.isFile()) continue;
                try {
                    for (String wl : Engine.read(wf).split("\n")) {
                        if (!wl.trim().isEmpty()) wlines.add(wl);
                    }
                } catch (Exception ignored) {
                }
            }
            int start = Math.max(0, wlines.size() - 250);
            for (int i = start; i < wlines.size(); i++) {
                s.append("  ").append(scrub(wlines.get(i))).append('\n');
            }
        }
    }

    /** Android's own crash reports (dropbox), newest first, each cut to its header and stack trace */
    static void crashReports(Engine e, StringBuilder s) {
        File[] fs = new File(e.root + "/data/system/dropbox").listFiles();
        List<Object[]> list = new ArrayList<Object[]>();   // {file, time, type}
        if (fs != null) {
            for (File f : fs) {
                Matcher m = DROPBOX.matcher(f.getName());
                if (f.isFile() && m.matches()) list.add(new Object[] { f, Long.parseLong(m.group(4)), m.group(1) });
            }
        }
        if (list.isEmpty()) { s.append("(none on this unit)\n"); return; }
        java.util.Collections.sort(list, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) b[1], (Long) a[1]);
            }
        });
        Map<String, Integer> counts = new TreeMap<String, Integer>();
        for (Object[] o : list) {
            Integer c = counts.get((String) o[2]);
            counts.put((String) o[2], c == null ? 1 : c + 1);
        }
        s.append("on this unit: ").append(counts).append(" (the newest ").append(Math.min(MAX_REPORTS, list.size())).append(" below)\n");
        SimpleDateFormat when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (int i = 0; i < list.size() && i < MAX_REPORTS; i++) {
            File f = (File) list.get(i)[0];
            String type = (String) list.get(i)[2];
            s.append("--- ").append(type).append(" (").append(when.format(new Date((Long) list.get(i)[1]))).append(")\n");
            try {
                String text = readMaybeGz(f);
                if (type.endsWith("native_crash") || type.equals("SYSTEM_TOMBSTONE")) {
                    s.append(trimDump(text));
                    continue;
                }
                String[] ls = text.split("\n");
                int k = 0;
                for (String l : ls) {
                    if (++k > MAX_REPORT_LINES) { s.append("(cut at ").append(MAX_REPORT_LINES).append(" lines)\n"); break; }
                    s.append(scrub(l)).append('\n');
                }
            } catch (Exception x) {
                s.append("(unreadable: ").append(scrub(String.valueOf(x))).append(")\n");
            }
        }
    }

    static String readMaybeGz(File f) throws java.io.IOException {
        if (!f.getName().endsWith(".gz")) return Engine.read(f);
        java.io.InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f));
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0 && out.size() < (4 << 20)) out.write(b, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /** the newest tombstones, trimmed to what says where it crashed */
    static void crashDumps(Engine e, StringBuilder s) {
        File[] ts = new File(e.root + "/data/tombstones").listFiles();
        List<File> list = new ArrayList<File>();
        if (ts != null) for (File t : ts) if (t.isFile() && t.getName().startsWith("tombstone_")) list.add(t);
        if (list.isEmpty()) { s.append("(none on this unit)\n"); return; }
        java.util.Collections.sort(list, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        for (int i = 0; i < list.size() && i < MAX_DUMPS; i++) {
            File t = list.get(i);
            s.append("--- ").append(t.getName()).append(" (").append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(t.lastModified()))).append(")\n");
            try {
                s.append(trimDump(Engine.read(t)));
            } catch (Exception x) {
                s.append("(unreadable: ").append(scrub(String.valueOf(x))).append(")\n");
            }
        }
    }

    /** a tombstone without its raw memory and log lines: header, registers, backtraces, maps near the fault */
    static String trimDump(String text) {
        StringBuilder o = new StringBuilder();
        boolean on = true;
        int n = 0;
        for (String l : text.split("\n")) {
            String x = l.trim();
            if (x.startsWith("stack:") || x.startsWith("memory near") || x.startsWith("code around") || x.startsWith("---------")) on = false;
            else if (x.startsWith("backtrace:") || x.startsWith("memory map") || x.startsWith("--- --- ---") || x.startsWith("pid: ")
                    || x.startsWith("*** ") || x.startsWith("Build fingerprint") || x.startsWith("signal ")) on = true;
            if (!on) continue;
            if (++n > MAX_DUMP_LINES) { o.append("(cut at ").append(MAX_DUMP_LINES).append(" lines)\n"); break; }
            o.append(DUMP_FORMAT.matcher(l).matches() ? l : scrub(l)).append('\n');
        }
        return o.toString();
    }
}

package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * engine nemesis-sync: makes NEMESIS's package list match what HMG-API has installed.
 * HMG-API does every install and uninstall; NEMESIS (1.16 or newer) only lists them:
 *
 *   HMG-API installed, NEMESIS has no "hmgapi" (or another version)   list it
 *   a mod installed, no "hmgapi.<id>" (or another version)            list it
 *   "hmgapi.<id>" listed, the mod not installed                       unlist it
 *
 * A package it lists is a small one: NEMESIS.data, HMG-API's NEMESIS scripts and a
 * "registered" marker, no payload or mod zip. NEMESIS runs its scripts as for any
 * package; with HMGAPI_NEMESIS_SYNC=1 (set here) they do nothing, so listing and
 * unlisting never install or remove anything. Uninstalling it in NEMESIS Manager later
 * runs the scripts without that, and HMG-API uninstalls (or removes the mod) as usual.
 *
 * Run after `engine install` and `engine remove` (not with --from-nemesis: NEMESIS is
 * doing that one itself), by install.sh, at boot (the boot hook), and by hand.
 * uninstall.sh unlists everything itself (the engine is gone by then). It never fails
 * an install or a remove: what it can't do it says, and the next sync tries again.
 * The scripts and the incompatible-package list come from /data/hmgapi/nemesis
 * (install.sh, from the payload); a mod's own list is its mod.json "nemesis_conflicts",
 * as tools/mknemesismod.sh reads it. ROOT (HMGAPI_ROOT) prefixes every path, for tests.
 */
final class NemesisSync {
    static final String NEED = "hmgapi@>=1.0";   // mods as NEMESIS packages are new in HMG-API 1.0.0

    static int run(Engine e) {
        java.io.RandomAccessFile lf = null;
        try {
            // one at a time (the boot hook runs from two boot scripts; NEMESIS itself has no lock)
            e.stateDir.mkdirs();
            lf = new java.io.RandomAccessFile(new File(e.stateDir, "nemesis-sync.lock"), "rw");
            java.nio.channels.FileLock lock = lf.getChannel().tryLock();
            if (lock == null) {
                e.out.add("NEMESIS sync: another one is running");
                return 0;
            }
            return sync(e);
        } catch (Exception x) {
            e.out.add("NEMESIS sync: " + x + " (the next sync tries again)");
            return 1;
        } finally {
            try {
                if (lf != null) lf.close();
            } catch (IOException x) {
                // released with the process anyway
            }
        }
    }

    static int sync(Engine e) throws Exception {
        File nemesis = new File(e.root + "/system/xbin/nemesis");
        if (!nemesis.isFile()) return 0;   // no NEMESIS: nothing to list
        Engine.Run v = Engine.exec(nemesis.getPath(), "version");
        String ver = v.text.trim();
        if (v.code != 0 || !ver.matches("[0-9]+(\\.[0-9]+)*") || cmp(ver, "1.16") < 0) {
            e.out.add("NEMESIS sync: NEMESIS " + (ver.isEmpty() ? "?" : ver) + " is older than 1.16, so HMG-API isn't listed in it (update NEMESIS)");
            return 0;
        }
        File tpl = new File(e.data, "nemesis");
        if (!new File(tpl, "scripts/preuninstall.sh").isFile() || !new File(tpl, "mod/scripts/preuninstall.sh").isFile()) {
            e.out.add("NEMESIS sync: no NEMESIS scripts in " + tpl + " (install HMG-API again)");
            return 1;
        }
        Map<String, String> listed = listed(e);
        Map<String, String> conflicts = conflicts(new File(tpl, "conflicts.txt"));
        int bad = 0;
        // HMG-API first: the mods' packages depend on it
        String pv = packageVersion(Engine.HMGAPI_VERSION);
        if (!pv.equals(listed.get("hmgapi"))) {
            Map<String, String> d = new LinkedHashMap<String, String>();
            d.put("id", "hmgapi");
            d.put("name", "HMG-API");
            d.put("package-version", pv);
            d.put("short-desc", "Mod manager for Gen5W head units: hooks, mods, Car Report.");
            d.put("detailed-desc", "HMG-API " + Engine.HMGAPI_VERSION + ", listed here by HMG-API. Uninstalling it here or in HMG-API uninstalls HMG-API.");
            d.put("depends", depends("hmgapi", "", conflicts, null));
            d.put("postinstall", "scripts/postinstall.sh");
            d.put("preuninstall", "scripts/preuninstall.sh");
            if (list(e, nemesis, "hmgapi", descriptor(d), new File(tpl, "scripts"), null, listed.containsKey("hmgapi"))) listed.put("hmgapi", pv);
            else bad++;
        }
        // the mods
        Map<String, String> mods = new TreeMap<String, String>();
        File[] dirs = new File(e.data, "mods").listFiles();
        if (dirs != null) for (File m : dirs) {
            if (m.getName().startsWith(".") || !new File(m, "mod.json").isFile()) continue;
            mods.put(m.getName(), m.getPath());
        }
        for (Map.Entry<String, String> m : mods.entrySet()) {
            String id = m.getKey(), pkg = "hmgapi." + id;
            if (!id.matches("[a-z0-9][a-z0-9-]{0,56}")) {
                e.out.add("NEMESIS sync: " + id + " isn't a name NEMESIS takes, so it isn't listed there");
                continue;
            }
            Map<String, Object> mj = Json.object(read(new File(m.getValue(), "mod.json")));
            String mver = String.valueOf(mj.get("version"));
            String mpv;
            try {
                mpv = packageVersion(mver);
            } catch (IllegalArgumentException x) {
                e.out.add("NEMESIS sync: " + id + " " + mver + ": " + x.getMessage() + ", so it isn't listed there");
                continue;
            }
            if (mpv.equals(listed.get(pkg))) continue;
            String name = text(mj.get("name") == null ? id : String.valueOf(mj.get("name")), 64);
            Map<String, String> d = new LinkedHashMap<String, String>();
            d.put("id", pkg);
            d.put("name", name);
            d.put("package-version", mpv);
            d.put("short-desc", text("HMG-API mod: " + name, 64));
            d.put("detailed-desc", text(name + " " + mver + ", a HMG-API mod, listed here by HMG-API. Uninstalling it here or in HMG-API removes the mod.", 256));
            String dep = depends(pkg, NEED, conflicts, own(e, id, mj.get("nemesis_conflicts")));
            if (dep.length() > 511) {   // native/meta.c: depends[512] (tools/nemesis-meta.py refuses the same)
                e.out.add("NEMESIS sync: " + id + ": \"nemesis_conflicts\" makes depends= longer than NEMESIS takes, left out");
                dep = depends(pkg, NEED, conflicts, null);
            }
            d.put("depends", dep);
            d.put("preinstall", "scripts/preinstall.sh");
            d.put("postinstall", "scripts/postinstall.sh");
            d.put("preuninstall", "scripts/preuninstall.sh");
            if (list(e, nemesis, pkg, descriptor(d), new File(tpl, "mod/scripts"), id, listed.containsKey(pkg))) listed.put(pkg, mpv);
            else bad++;
        }
        // listed in NEMESIS, gone from HMG-API. A mod's package can depend on another's (its
        // "requires", tools/mknemesismod.sh), and NEMESIS won't unlist that one while it's
        // listed (exit 4): go round until a round unlists nothing.
        List<String> gone = new ArrayList<String>();
        for (String pkg : listed.keySet()) {
            if (pkg.startsWith("hmgapi.") && !mods.containsKey(pkg.substring(7))) gone.add(pkg);
        }
        Map<String, String> why = new LinkedHashMap<String, String>();
        for (boolean more = true; more && !gone.isEmpty(); ) {
            more = false;
            why.clear();
            for (java.util.Iterator<String> it = gone.iterator(); it.hasNext(); ) {
                String pkg = it.next();
                refresh(e, pkg, new File(tpl, "mod/scripts"));
                Engine.Run r = nemesis(nemesis, "uninstall", pkg);
                if (r.code == 0) {
                    e.out.add("NEMESIS sync: " + pkg + " isn't installed in HMG-API any more: unlisted it from NEMESIS");
                    it.remove();
                    more = true;
                } else why.put(pkg, last(r.text));
            }
        }
        for (Map.Entry<String, String> w : why.entrySet()) {
            e.out.add("NEMESIS sync: couldn't unlist " + w.getKey() + " from NEMESIS: " + w.getValue());
            bad++;
        }
        if (bad == 0) e.out.add("NEMESIS sync: NEMESIS lists HMG-API and its " + mods.size() + " mod(s)");
        return bad == 0 ? 0 : 1;
    }

    /** id to package-version of every package NEMESIS has installed (/data/nemesis/apps/<id>/NEMESIS.data) */
    static Map<String, String> listed(Engine e) throws IOException {
        Map<String, String> out = new TreeMap<String, String>();
        File[] apps = new File(e.root + "/data/nemesis/apps").listFiles();
        if (apps == null) return out;
        for (File a : apps) {
            File d = new File(a, "NEMESIS.data");
            if (!d.isFile()) continue;
            Map<String, String> ini = ini(read(d));
            String id = ini.get("id");
            if (id != null && id.equals(a.getName())) out.put(id, ini.containsKey("package-version") ? ini.get("package-version") : "");
        }
        return out;
    }

    /**
     * HMG-API's current NEMESIS scripts over an installed package's own copies, before NEMESIS
     * runs them for an update or an uninstall: an older package's scripts don't know
     * HMGAPI_NEMESIS_SYNC, and would uninstall instead of only unlisting.
     */
    static void refresh(Engine e, String pkg, File scripts) throws IOException {
        File dst = new File(e.root + "/data/nemesis/apps/" + pkg + "/scripts");
        File[] fs = scripts.listFiles();
        if (!dst.isDirectory() || fs == null) return;
        for (File f : fs) {
            if (!f.isFile()) continue;
            FileOutputStream o = new FileOutputStream(new File(dst, f.getName()));
            try {
                o.write(bytes(f));
            } finally {
                o.close();
            }
        }
    }

    static boolean list(Engine e, File nemesis, String pkg, String desc, File scripts, String modId, boolean update) throws IOException, InterruptedException {
        File dir = new File(e.stateDir, "nemesis-sync");
        dir.mkdirs();
        File zip = new File(dir, pkg + ".zip");
        ZipOutputStream z = new ZipOutputStream(new FileOutputStream(zip));
        try {
            put(z, "NEMESIS.data", desc.getBytes("UTF-8"));
            put(z, "registered", ("Listed here by HMG-API's NEMESIS sync: HMG-API installed this"
                    + (modId == null ? "" : " mod") + "; this package only lists it.\n").getBytes("UTF-8"));
            File[] fs = scripts.listFiles();
            if (fs != null) for (File f : fs) if (f.isFile()) put(z, "scripts/" + f.getName(), bytes(f));
            File open = new File(e.data, "nemesis/bin/open.sh");
            if (open.isFile()) put(z, "bin/open.sh", bytes(open));
            File start = new File(e.data, "nemesis/bin/start.sh");   // HMG-API's boot service (descriptor)
            if (modId == null && start.isFile()) put(z, "bin/start.sh", bytes(start));
            if (modId != null) put(z, "mod/id", (modId + "\n").getBytes("UTF-8"));
        } finally {
            z.close();
        }
        if (update) refresh(e, pkg, scripts);
        Engine.Run r = update ? nemesis(nemesis, "install", "--update", zip.getPath()) : nemesis(nemesis, "install", zip.getPath());
        zip.delete();
        if (r.code == 0) {
            e.out.add("NEMESIS sync: listed " + pkg + " in NEMESIS");
            return true;
        }
        String why = r.code == 23 ? "a NEMESIS package that can't be installed with it is installed (" + last(r.text) + ")"
                : r.code == 22 ? "it needs " + (modId == null ? "something" : "HMG-API listed first") + " (" + last(r.text) + ")"
                : last(r.text);
        e.out.add("NEMESIS sync: couldn't list " + pkg + " in NEMESIS: " + why);
        return false;
    }

    static Engine.Run nemesis(File nemesis, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<String>();
        cmd.add(nemesis.getPath());
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("HMGAPI_NEMESIS_SYNC", "1");   // HMG-API's NEMESIS scripts: list or unlist only
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Engine.Run r = new Engine.Run();
        r.text = new String(Engine.readAll(p.getInputStream()), "UTF-8");
        r.code = p.waitFor();
        return r;
    }

    static String descriptor(Map<String, String> d) {
        StringBuilder b = new StringBuilder("[nemesis]\nversion=1.1\n");
        b.append("id=").append(d.get("id")).append('\n');
        b.append("name=").append(d.get("name")).append('\n');
        b.append("type=application\n");
        b.append("package-version=").append(d.get("package-version")).append('\n');
        b.append("short-desc=\"").append(d.get("short-desc")).append("\"\n");
        b.append("detailed-desc=\"").append(d.get("detailed-desc")).append("\"\n");
        b.append("os=\"any\"\nmodels=\"any\"\n");
        b.append("depends=\"").append(d.get("depends")).append("\"\n");
        for (String h : new String[] {"preinstall", "postinstall", "preuninstall"}) {
            if (d.containsKey(h)) b.append(h).append('=').append(d.get(h)).append('\n');
        }
        b.append("\n[app.0]\nkind=sh\npath=bin/open.sh\nuid=root\n");
        // HMG-API itself: a boot service, so NEMESIS's svcmgr starts HMG-API too (hmgapid boot:
        // once per boot, whichever of it and the boot hook comes first)
        if ("hmgapi".equals(d.get("id"))) b.append("\n[service.0]\nname=boot\nkind=sh\npath=bin/start.sh\nuid=root\nmode=background\n");
        return b.toString();
    }

    /**
     * NEMESIS's package-version (dotted numbers only), sorting as the versions do: a
     * pre-release is the version before it, then .97.n (-alpha.n), .98.n (-beta.n) or
     * .99.n (-rc.n), as tools/nemesis-version.py
     */
    static String packageVersion(String v) {
        if (v.matches("[0-9]+(\\.[0-9]+)*")) return v;
        Matcher m = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)-(alpha|beta|rc)\\.(0|[1-9][0-9]*)").matcher(v);
        if (!m.matches()) throw new IllegalArgumentException("no NEMESIS package-version for " + v);
        int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
        String s = "alpha".equals(m.group(4)) ? "97" : "beta".equals(m.group(4)) ? "98" : "99", n = m.group(5);
        if (z > 0) return x + "." + y + "." + (z - 1) + "." + s + "." + n;
        if (y > 0) return x + "." + (y - 1) + "." + s + "." + n;
        if (x > 0) return (x - 1) + "." + s + "." + n;
        throw new IllegalArgumentException("no NEMESIS package-version for " + v + ": nothing comes before 0.0.0");
    }

    /**
     * depends=: the needs, then "!<id>" for each package conflicts.txt lists against this one,
     * then each of the mod's own (mod.json "nemesis_conflicts") not listed yet
     */
    static String depends(String pkg, String needs, Map<String, String> conflicts, List<String> own) {
        List<String> ids = new ArrayList<String>();
        String c = conflicts.get(pkg);
        if (c != null) for (String id : c.split(",")) if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        if (own != null) for (String id : own) if (!ids.contains(id)) ids.add(id);
        StringBuilder b = new StringBuilder(needs);
        for (String id : ids) b.append(b.length() == 0 ? "" : ",").append('!').append(id);
        return b.toString();
    }

    /**
     * a mod's "nemesis_conflicts": NEMESIS package ids (tools/nemesis-meta.py's rule). The engine
     * doesn't check the field at install, so one that isn't an id, or would refuse HMG-API or the
     * mod itself, is left out here and said.
     */
    static List<String> own(Engine e, String id, Object v) {
        List<String> out = new ArrayList<String>();
        if (v == null) return out;
        if (!(v instanceof List)) {
            e.out.add("NEMESIS sync: " + id + ": \"nemesis_conflicts\" isn't a list, left out");
            return out;
        }
        for (Object o : (List<?>) v) {
            String s = o instanceof String ? (String) o : null;
            if (s == null || !s.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}") || s.contains("..") || s.equals("hmgapi") || s.equals("hmgapi." + id)) {
                e.out.add("NEMESIS sync: " + id + ": \"nemesis_conflicts\": " + o + " left out (not a NEMESIS package id it can refuse)");
                continue;
            }
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    /** install/nemesis/conflicts.txt: "<package id> <ids,comma-separated>" per line, # comments */
    static Map<String, String> conflicts(File f) throws IOException {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (!f.isFile()) return out;
        for (String l : read(f).split("\n")) {
            int h = l.indexOf('#');
            String[] w = (h >= 0 ? l.substring(0, h) : l).trim().split("\\s+");
            if (w.length == 2 && w[0].matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}") && w[1].matches("[A-Za-z0-9_.,-]+")) out.put(w[0], w[1]);
        }
        return out;
    }

    static Map<String, String> ini(String text) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        boolean in = false;
        for (String l : text.split("\n")) {
            l = l.trim();
            if (l.startsWith("[")) {
                in = l.equals("[nemesis]");
                continue;
            }
            int eq = l.indexOf('=');
            if (!in || eq <= 0) continue;
            String v = l.substring(eq + 1).trim();
            if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
            out.put(l.substring(0, eq).trim(), v);
        }
        return out;
    }

    /** one line, no double quotes, at most n characters (as tools/mknemesismod.sh) */
    static String text(String s, int n) {
        s = s.replace('"', '\'').replaceAll("\\s+", " ").trim();
        return s.length() <= n ? s : s.substring(0, n - 3).trim() + "...";
    }

    static int cmp(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long p = i < x.length ? Long.parseLong(x[i]) : 0, q = i < y.length ? Long.parseLong(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    static String last(String text) {
        String[] l = text.trim().split("\n");
        return l.length == 0 || l[l.length - 1].isEmpty() ? "no output" : l[l.length - 1].trim();
    }

    static void put(ZipOutputStream z, String name, byte[] b) throws IOException {
        ZipEntry en = new ZipEntry(name);
        en.setTime(1767225600000L);   // 2026-01-01: the same bytes every time
        z.putNextEntry(en);
        z.write(b);
        z.closeEntry();
    }

    static byte[] bytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    static String read(File f) throws IOException {
        return new String(bytes(f), "UTF-8");
    }
}

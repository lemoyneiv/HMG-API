package hmgapi.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Mod packages: a zip (or folder) holding one mod folder's contents, signed
 * with its author's key (see Signing). From a USB stick (the HMG-API app) or
 * adb (/data/hmgapi/inbox), through the engine:
 *
 *   engine install <zip|dir> [--uuid <uuid>] [--force]
 *   engine remove <id>
 *
 * install checks, in order: the package is whole and signed; an update has the
 * installed mod's author key (nobody installs over someone else's mod); a first
 * install records its key and says whether it's on HMG-API's trusted author list
 * (maps/authors.json; not listed: a warning, not a refusal; --uuid, if given, must
 * match); HMG-API's version, the system
 * requirements and the mods it requires. Then it swaps the folder in, keeping
 * the owner's settings and whether the mod is on. Files change only at the
 * next `engine apply`. --force: an older version, or (with
 * hmgapi.json overrides.allow_unsigned) an unsigned package, at the owner's risk.
 */
final class Packages {
    private Packages() {}

    static final int MAX_FILES = 2000;
    static final long MAX_BYTES = 64L << 20;

    @SuppressWarnings("unchecked")
    static int install(Engine e, String path, String uuid, boolean force, boolean anyCar) throws Exception {
        File src = new File(path);
        if (!src.exists()) {
            e.out.add("no package at " + path);
            return 2;
        }
        File stage = new File(e.stageDir, "package");
        Engine.rm(stage);
        stage.mkdirs();
        try {
            if (src.isDirectory()) copyTree(src, stage);
            else unzip(src, stage);
            File dir = root(stage);
            Verdict v = check(e, dir, uuid, force, anyCar);
            e.out.addAll(v.notes);
            if (v.code != 0) {
                e.out.add("refused: " + v.reason);
                return v.code;
            }
            // swap in, keeping on/off
            File installed = new File(e.data, "mods/" + v.id);
            boolean wasOff = new File(installed, ".disabled").exists();
            File prev = new File(e.stageDir, "package-prev");
            Engine.rm(prev);
            new File(e.data, "mods").mkdirs();
            if (installed.isDirectory() && !installed.renameTo(prev)) throw new IOException("can't move the installed " + v.id + " aside");
            if (!dir.renameTo(installed)) {
                if (prev.isDirectory()) prev.renameTo(installed);
                throw new IOException("can't put " + v.id + " in place");
            }
            if (wasOff) new File(installed, ".disabled").createNewFile();
            Engine.rm(prev);
            if (anyCar && v.otherCar != null) ignoreSystem(e, v.id, true);
            e.chmodTree(installed);
            e.out.add("installed " + v.id + " " + v.version + (v.uuid != null ? " (author key " + v.uuid + ")" : " (unsigned)")
                    + (wasOff ? ", still turned off" : ""));
            scriptWarnings(e, installed);
            e.out.add("run `engine apply` (Apply changes in the app) to use it");
            LiveState.publish(e);
            return 0;
        } finally {
            Engine.rm(stage);
        }
    }

    /** What installing a package would do. code 0: it can be installed; 3: refused (reason). */
    static final class Verdict {
        int code;
        String id, version, name, description, uuid, installedVersion, reason;
        String author;   // a new mod's author on the trusted list (null: not listed)
        String status;   // new, update, same, older, refused
        String otherCar;   // why it isn't for this head unit (the owner may install it anyway: --any-car)
        final java.util.List<String> notes = new java.util.ArrayList<String>();
    }

    static Verdict refuse(Verdict v, String why) {
        v.code = 3;
        v.status = "refused";
        v.reason = why;
        return v;
    }

    /** Every check install makes, without changing anything. */
    @SuppressWarnings("unchecked")
    static Verdict check(Engine e, File dir, String uuid, boolean force, boolean anyCar) throws Exception {
        Verdict v = new Verdict();
        File mj = new File(dir, "mod.json");
        if (!mj.isFile()) return refuse(v, "not a HMG-API mod package (no mod.json)");
        Map<String, Object> mod = Json.object(Engine.read(mj));
        v.id = String.valueOf(mod.get("id"));
        v.version = String.valueOf(mod.get("version"));
        v.name = mod.get("name") == null ? v.id : String.valueOf(mod.get("name"));
        v.description = mod.get("description") == null ? "" : String.valueOf(mod.get("description"));
        if (!v.id.matches("[a-z0-9][a-z0-9-]{0,63}") || "hmgapi".equals(v.id)) return refuse(v, "bad mod id \"" + v.id + "\"");
        try {
            new Version(v.version);
        } catch (IllegalArgumentException x) {
            return refuse(v, v.id + ": " + x.getMessage());
        }
        File installed = new File(e.data, "mods/" + v.id);
        String haveUuid = installedUuid(installed);
        File old = new File(installed, "mod.json");
        v.installedVersion = old.isFile() ? String.valueOf(Json.object(Engine.read(old)).get("version")) : null;

        // signature and author
        Signing.Result sig = Signing.verify(dir, haveUuid);
        if (sig.ok) v.uuid = sig.uuid;
        if (!sig.ok) {
            boolean unsigned = !new File(dir, Signing.DIR + "/signature").isFile();
            if (unsigned && force && allowUnsigned(e)) {
                v.notes.add("WARNING: " + v.id + " is unsigned; installing it because overrides.allow_unsigned is set and --force was given");
            } else {
                return refuse(v, v.id + " " + v.version + ": " + sig.error
                        + (unsigned ? " (unsigned packages need hmgapi.json overrides.allow_unsigned and --force)" : ""));
            }
        }
        if (sig.ok && haveUuid == null && installed.isDirectory() && !force) {
            return refuse(v, "the installed " + v.id + " isn't signed, so this signed package can't be checked against it;"
                    + " remove it first, or use --force");
        }
        // whole-file replacements: fine, but other mods then can't share those files
        File pj = new File(dir, "patches.json");
        if (pj.isFile()) {
            String fc = Engine.fileChanges(Json.object(Engine.read(pj)));
            if (fc != null) v.notes.add("note     " + v.id + " " + Engine.ownsWhy(fc));
        }
        // what it needs
        Object need = mod.get("hmgapi");
        if (need != null && !Version.meetsHmgapi(Engine.HMGAPI_VERSION, String.valueOf(need))) {
            return refuse(v, v.id + " " + v.version + " needs HMG-API " + need + " (this is " + Engine.HMGAPI_VERSION + ")");
        }
        Map<String, Object> car = (Map<String, Object>) e.hmgapi.get("car");
        String sys = SystemCheck.check(mod.get("system"), new SystemCheck.Facts(e.root, car));
        if (sys != null) {
            v.otherCar = sys;
            if (SystemCheck.ignored(e.hmgapi, v.id) || anyCar) {
                v.notes.add("WARNING: " + v.id + " isn't for this head unit (" + sys + "); installing it anyway, at your own risk");
            } else {
                return refuse(v, v.id + " " + v.version + " isn't for this head unit: " + sys
                        + ". To install it anyway, at your own risk: \"Install anyway\" in HMG-API's updates, or engine install --any-car");
            }
        }
        for (Version.Dep d : Version.deps(mod.get("requires"))) {
            File dm = new File(e.data, "mods/" + d.id + "/mod.json");
            String dv = dm.isFile() ? String.valueOf(Json.object(Engine.read(dm)).get("version")) : null;
            if (dv == null) v.notes.add("note     " + v.id + " requires " + d + ", which isn't installed: install it before applying");
            else if (!Version.satisfies(dv, d.range)) {
                v.notes.add("note     " + v.id + " requires " + d + ", but " + d.id + " " + dv + " is installed: update it before applying");
            }
        }
        if (v.installedVersion != null) {
            int cmp = new Version(v.version).compareTo(new Version(v.installedVersion));
            if (cmp < 0 && !force) {
                return refuse(v, v.id + " " + v.version + " is older than the installed " + v.installedVersion + " (--force installs it anyway)");
            }
            v.status = cmp > 0 ? "update" : cmp == 0 ? "same" : "older";
        } else {
            v.status = "new";
        }
        if (sig.ok && uuid != null && !uuid.equalsIgnoreCase(sig.uuid)) {
            return refuse(v, v.id + " is signed by author key " + sig.uuid + ", not the expected " + uuid);
        }
        if (sig.ok && haveUuid == null) {   // a first install: its key is recorded, and said
            v.author = trustedAuthor(e, sig.uuid);
            v.notes.add(v.author != null ? "author   " + v.author + " (author key " + sig.uuid + ", on HMG-API's trusted author list)"
                    : "WARNING: " + v.id + " is by author key " + sig.uuid + ", which isn't on HMG-API's trusted author list. A mod's "
                    + "code runs inside the head unit's apps: install mods only from authors you trust. Its updates must come from this same key.");
        }
        v.code = 0;
        return v;
    }

    /** the author's name from maps/authors.json, or null when the key isn't listed */
    @SuppressWarnings("unchecked")
    static String trustedAuthor(Engine e, String uuid) {
        try {
            File f = new File(e.data, "maps/authors.json");
            if (!f.isFile() || uuid == null) return null;
            Object a = ((Map<String, Object>) Json.object(Engine.read(f)).get("authors")).get(uuid.toLowerCase());
            return a instanceof Map ? String.valueOf(((Map<String, Object>) a).get("name")) : null;
        } catch (Exception x) {
            return null;
        }
    }

    static int remove(Engine e, String id) throws Exception {
        if (!id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            e.out.add("bad mod id");
            return 2;
        }
        File installed = new File(e.data, "mods/" + id);
        if (!installed.isDirectory()) {
            e.out.add(id + " isn't installed");
            return 1;
        }
        Engine.rm(installed);
        File cache = new File(e.data, "dalvik-cache");
        DexCacheCleanup.removeObsoleteShared(cache);
        DexCacheCleanup.removeMod(cache, id);
        ignoreSystem(e, id, false);
        LiveState.publish(e);
        e.out.add("removed " + id + " (its settings stay in /data/hmgapi/settings); run `engine apply` to put its files back to stock");
        return 0;
    }

    /**
     * hmgapi.json overrides.ignore_system: add or drop one mod id (the owner's "Install anyway").
     * true (every mod) is left as it is.
     */
    @SuppressWarnings("unchecked")
    static void ignoreSystem(Engine e, String id, boolean on) throws IOException {
        Object o = e.hmgapi.get("overrides");
        Map<String, Object> ov = o instanceof Map ? (Map<String, Object>) o : new java.util.LinkedHashMap<String, Object>();
        Object v = ov.get("ignore_system");
        if (Boolean.TRUE.equals(v)) return;
        java.util.List<Object> ids = v instanceof java.util.List ? (java.util.List<Object>) v : new java.util.ArrayList<Object>();
        if (on == ids.contains(id)) return;
        if (on) ids.add(id);
        else ids.remove(id);
        ov.put("ignore_system", ids);
        e.hmgapi.put("overrides", ov);
        File lj = new File(e.data, "settings/hmgapi.json");
        lj.getParentFile().mkdirs();
        Engine.write(lj, Json.write(e.hmgapi));
        e.out.add(on ? "noted: " + id + " runs on this head unit at your own risk (hmgapi.json overrides.ignore_system)"
                : "dropped " + id + " from hmgapi.json overrides.ignore_system");
    }

    /** The package's mod folder: the top, or its single subfolder. */
    static File root(File stage) {
        if (new File(stage, "mod.json").isFile()) return stage;
        File[] fs = stage.listFiles();
        if (fs != null && fs.length == 1 && fs[0].isDirectory()) return fs[0];
        return stage;
    }

    /**
     * Warns (doesn't refuse: the owner decides) when a mod's scripts call commands this
     * head unit doesn't have (ScriptCheck): they'd fail on the car, often quietly.
     */
    static void scriptWarnings(Engine e, File mod) {
        try {
            java.util.Set<String> unit = e.host ? ScriptCheck.list(new File("core/runtime/unit-commands.txt")) : ScriptCheck.unit(e.root);
            java.util.List<File> scripts = ScriptCheck.scripts(mod);
            java.util.Set<String> fn = ScriptCheck.functions(scripts);
            for (File sc : scripts) {
                for (String m : ScriptCheck.missing(Engine.read(sc), unit, fn)) {
                    e.out.add("warning: " + sc.getPath().substring(mod.getPath().length() + 1) + " calls " + m);
                }
            }
        } catch (Exception x) {
            // a check only
        }
    }

    static String installedUuid(File installed) {
        try {
            File mf = new File(installed, Signing.DIR + "/manifest.json");
            if (!mf.isFile()) return null;
            Object u = Json.object(Engine.read(mf)).get("uuid");
            return u == null ? null : String.valueOf(u);
        } catch (Exception x) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static boolean allowUnsigned(Engine e) {
        Object o = e.hmgapi.get("overrides");
        return o instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) o).get("allow_unsigned"));
    }

    static void unzip(File zip, File to) throws IOException {
        ZipInputStream in = new ZipInputStream(new FileInputStream(zip));
        int files = 0;
        long bytes = 0;
        String base = to.getCanonicalPath() + File.separator;
        try {
            ZipEntry z;
            byte[] buf = new byte[65536];
            while ((z = in.getNextEntry()) != null) {
                String name = z.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new IOException("bad path in package: " + name);
                File f = new File(to, name);
                if (!f.getCanonicalPath().startsWith(base)) throw new IOException("bad path in package: " + name);
                if (z.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                if (++files > MAX_FILES) throw new IOException("package has too many files");
                f.getParentFile().mkdirs();
                OutputStream o = new FileOutputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bytes += n;
                        if (bytes > MAX_BYTES) throw new IOException("package is too big (over 64 MB)");
                        o.write(buf, 0, n);
                    }
                } finally {
                    o.close();
                }
            }
        } finally {
            in.close();
        }
    }

    static void copyTree(File from, File to) throws IOException {
        File[] fs = from.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            File t = new File(to, f.getName());
            if (f.isDirectory()) {
                t.mkdirs();
                copyTree(f, t);
            } else {
                Engine.copy(f, t);
            }
        }
    }

}

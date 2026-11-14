package hmgapi.hook;

import dalvik.system.DexClassLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import hmgapi.engine.DexCacheCleanup;
import hmgapi.engine.Json;
import hmgapi.engine.SystemCheck;

/**
 * Reads the installed mods in the zygote (as root) and keeps what they provide
 * in memory, so every app forked afterwards has it without reading files.
 *
 *   /data/hmgapi/mods/<id>/mod.json       identity, provides.launcher, hooks
 *   /data/hmgapi/mods/<id>/settings.json  optional: settings and their defaults
 *   /data/hmgapi/mods/<id>/.disabled      optional: mod is off
 *   /data/hmgapi/settings/<id>.json       optional: the owner's values
 *   /data/hmgapi/settings/hmgapi.json     optional: HMG-API's own settings
 *
 * A mod whose "system" requirements this unit doesn't meet isn't loaded
 * (state "unsupported"), unless hmgapi.json overrides.ignore_system names it.
 */
final class Mods {
    private Mods() {}

    static final File ROOT = new File("/data/hmgapi");
    static final File MODS = new File(ROOT, "mods");
    static final File SETTINGS = new File(ROOT, "settings");
    static final File DEX_CACHE = new File(ROOT, "dalvik-cache");

    /** A launcher menu entry a mod provides. */
    static final class LauncherEntry {
        final String mod, packageName, className, title;
        final int icon, iconDisabled, titleId;
        /** the mod's own tile pictures (mod.json "icon", "icon_night"), read in the zygote; null: the activity's icon */
        byte[] iconDay, iconNight;
        /** the greyed-out tile ("icon_disabled", "icon_disabled_night"); null: the normal tile */
        byte[] iconDisabledDay, iconDisabledNight;

        LauncherEntry(String mod, String packageName, String className, String title, int index) {
            this.mod = mod;
            this.packageName = packageName;
            this.className = className;
            this.title = title;
            int base = 0x7f7e0000 + 3 * index;   // made-up resource ids, answered by HMG-API's hooks
            this.icon = base + 1;
            this.iconDisabled = base + 2;
            this.titleId = base + 3;
        }
    }

    /** each loaded mod's class loader, and the API packages it exports (for the mods that require it) */
    static final Map<String, ClassLoader> loaders = new java.util.concurrent.ConcurrentHashMap<String, ClassLoader>();
    static final Map<String, List<String>> exportsOf = new java.util.concurrent.ConcurrentHashMap<String, List<String>>();

    /** A mod's hook code, with the API packages of the mods it requires visible to it. */
    @SuppressWarnings("unchecked")
    static ModEntry loadEntry(String id, Map<String, Object> raw, File dir, File optDir) throws Exception {
        Map<String, Object> hooks = (Map<String, Object>) raw.get("hooks");
        File jar = new File(dir, String.valueOf(hooks.containsKey("jar") ? hooks.get("jar") : "hooks.jar"));
        if (!jar.isFile()) throw new IOException("no " + jar);
        ApiLoader parent = new ApiLoader(Mods.class.getClassLoader());   // so mods see the hmgapi.hook API
        for (hmgapi.engine.Version.Dep d : hmgapi.engine.Version.deps(raw.get("requires"))) {
            ClassLoader l = loaders.get(d.id);
            List<String> ex = exportsOf.get(d.id);
            if (l != null && ex != null) parent.exports.add(new ApiLoader.Export(d.id, ex, l));
        }
        DexClassLoader cl = new DexClassLoader(jar.getPath(), ModDexCache.directory(optDir, id).getPath(), null, parent);
        loaders.put(id, cl);
        List<String> ex = new ArrayList<String>();
        Object api = raw.get("api");
        if (api instanceof Map && ((Map<String, Object>) api).get("packages") instanceof List) {
            for (Object o : (List<Object>) ((Map<String, Object>) api).get("packages")) ex.add(String.valueOf(o));
        }
        exportsOf.put(id, ex);
        return (ModEntry) cl.loadClass(String.valueOf(hooks.get("entry"))).newInstance();
    }

    /** loaded app parts: "<mod>/<package>" -> its loader in this app */
    static final Map<String, ClassLoader> parts = new java.util.concurrent.ConcurrentHashMap<String, ClassLoader>();

    /** ModContext.appPart: the class from the mod's jar, loaded with this app's classes behind it. */
    @SuppressWarnings("unchecked")
    static synchronized Class<?> appPart(ModContext ctx, LoadedApp app, String className) throws Exception {
        int dot = className.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException(className + ": an app part needs a package of its own");
        String pkg = className.substring(0, dot);
        ClassLoader main = loaders.get(ctx.id);
        if (main == null) throw new IllegalStateException(ctx.id + " isn't loaded");
        String key = ctx.id + "/" + pkg;
        ClassLoader cl = parts.get(key);
        if (cl == null) {
            Map<String, Object> hooks = (Map<String, Object>) rawOf.get(ctx.id).get("hooks");
            File jar = new File(ctx.dir, String.valueOf(hooks.containsKey("jar") ? hooks.get("jar") : "hooks.jar"));
            cl = new DexClassLoader(jar.getPath(), ModDexCache.directory(app.context.getDir("hmgapi-dex", 0), ctx.id).getPath(), null,
                    new AppPartLoader(pkg, main, app.classLoader));
            parts.put(key, cl);
            HmgApi.i("mods: " + ctx.id + " app part " + pkg + " loaded in " + app.processName);
        }
        return cl.loadClass(className);
    }

    /**
     * A "live" mod turned on (or installed) while this app runs: loaded here now, with
     * its settings from the public state. Its zygoteInit runs here (late, as this app's
     * uid), then appLoaded. The mods it requires are loaded first.
     */
    @SuppressWarnings("unchecked")
    static synchronized void lateLoad(String id, LoadedApp app, Map<String, Object> state, java.util.Set<String> trail) {
        if (loaders.containsKey(id) || !trail.add(id)) return;
        Map<String, Object> mods = (Map<String, Object>) state.get("mods");
        Map<String, Object> s = (Map<String, Object>) mods.get(id);
        if (s == null || !Boolean.TRUE.equals(s.get("enabled")) || !Boolean.TRUE.equals(s.get("live"))) return;
        File dir = new File(MODS, id);
        try {
            Map<String, Object> raw = Json.object(read(new File(dir, "mod.json")));
            if (!(raw.get("hooks") instanceof Map)) return;
            for (hmgapi.engine.Version.Dep d : hmgapi.engine.Version.deps(raw.get("requires"))) lateLoad(d.id, app, state, trail);
            Map<String, Map<String, Object>> all = new java.util.LinkedHashMap<String, Map<String, Object>>(rawOf);
            all.put(id, raw);
            hmgapi.engine.HookPlan p = hmgapi.engine.HookPlan.of(all, Points.map == null ? null : Points.map.points.keySet(), Points.map == null ? null : Points.map.missing);
            if (p.refused.containsKey(id)) {
                HmgApi.i("mods: " + id + " not loaded live: " + p.refused.get(id));
                return;
            }
            JSONObject settings = new JSONObject((Map<String, Object>) s.get("settings"));
            ModContext ctx = new ModContext(id, String.valueOf(raw.get("version")), dir, settings);
            for (hmgapi.engine.Version.Dep d : hmgapi.engine.Version.deps(raw.get("requires"))) ctx.requires.add(d.id);
            for (Map.Entry<String, Map<String, String>> e : p.points.entrySet()) {
                String mode = e.getValue().get(id);
                if (mode != null) ctx.declared.put(e.getKey(), mode);
            }
            ModEntry entry = loadEntry(id, raw, dir, app.context.getDir("hmgapi-dex", 0));
            rawOf.put(id, raw);
            entry.zygoteInit(ctx);
            entry.appLoaded(ctx, app);
            withHooks.add(new Loaded(ctx, entry));
            HmgApi.i("mods: " + id + " loaded live in " + app.processName);
        } catch (Throwable t) {
            HmgApi.e("mods: " + id + " live load in " + app.processName, t);
        }
    }

    /** loaded mods' mod.json, for hook plans of mods loaded later */
    static final Map<String, Map<String, Object>> rawOf = new java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>>();

    static final class Loaded {
        final ModContext context;
        final ModEntry entry;

        Loaded(ModContext context, ModEntry entry) {
            this.context = context;
            this.entry = entry;
        }
    }

    /**
     * A ★-key action a mod offers as a choice in the stock button settings:
     * open an activity, or send a broadcast. The key stores {@link #action}.
     */
    static final class StarAction {
        final String mod, modName, id, title, packageName, className, broadcast, action;
        final int titleId, summaryId, iconId;

        StarAction(String mod, String modName, String id, String title, String packageName, String className,
                String broadcast, int index) {
            this.mod = mod;
            this.modName = modName;
            this.id = id;
            this.title = title;
            this.packageName = packageName;
            this.className = className;
            this.broadcast = broadcast;
            this.action = "hmgapi.star." + mod + "." + id;
            int base = 0x7f7d0000 + 3 * index;   // made-up resource ids, answered by HMG-API's hooks
            this.titleId = base + 1;
            this.summaryId = base + 2;
            this.iconId = base + 3;
        }
    }

    /** Every mod folder seen at boot, for the HMG-API app's list. */
    static final class Info {
        final String id;
        String name, version, description = "", state = "loaded", error;
        int launcher, starKey;
        boolean hooks;

        Info(String id) {
            this.id = id;
            this.name = id;
            this.version = "?";
        }
    }

    static final String VERSION = hmgapi.engine.Engine.HMGAPI_VERSION;
    // HMG-API's own app: always offered (tile and star key choice) while it is installed
    static final String APP_PACKAGE = "hmgapi.app", APP_CLASS = "hmgapi.app.MainActivity";

    static final List<Info> infos = new ArrayList<Info>();
    static final List<StarAction> starActions = new ArrayList<StarAction>();
    static JSONObject hmgapiSettings = new JSONObject();
    static final List<LauncherEntry> launcher = new ArrayList<LauncherEntry>();
    static final List<Loaded> withHooks = new ArrayList<Loaded>();
    static hmgapi.engine.HookPlan plan;
    static final java.util.Set<String> installedIds = new java.util.LinkedHashSet<String>();

    static void load() {
        try {
            File f = new File(SETTINGS, "hmgapi.json");
            if (f.isFile()) hmgapiSettings = new JSONObject(read(f));
            // the owner's switch from the HMG-API app (hmgapid allow ota_updates): read here, in the zygote (root)
            if (new File(SETTINGS, "allow/ota_updates").isFile()) hmgapiSettings.put("updates", true);
        } catch (Exception e) {
            HmgApi.e("mods: hmgapi.json", e);
        }
        // HMG-API's own entries come first, so their ids never move
        launcher.add(new LauncherEntry("hmgapi", APP_PACKAGE, APP_CLASS, "HMG-API", 0));
        starActions.add(new StarAction("hmgapi", "HMG-API", "open", "Open HMG-API", APP_PACKAGE, APP_CLASS, null, 0));
        File[] dirs = MODS.listFiles();
        if (dirs == null) dirs = new File[0];
        Arrays.sort(dirs);
        // which mods load, in what order, and hook conflicts (HookPlan: the engine's plan uses the same rules)
        Map<String, Map<String, Object>> enabled = new java.util.LinkedHashMap<String, Map<String, Object>>();
        Map<String, File> dirOf = new java.util.LinkedHashMap<String, File>();
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            String id = dir.getName();
            if (DexCacheCleanup.validModId(id)) installedIds.add(id);
            dirOf.put(id, dir);
            if ("hmgapi".equals(id) || new File(dir, ".disabled").exists()) continue;
            try {
                enabled.put(id, Json.object(read(new File(dir, "mod.json"))));
            } catch (Exception e) {
                // reported below, when it's loaded
                enabled.put(id, new java.util.LinkedHashMap<String, Object>());
            }
        }
        DexCacheCleanup.reconcile(DEX_CACHE, installedIds);
        plan = hmgapi.engine.HookPlan.of(enabled, Points.map == null ? null : Points.map.points.keySet(), Points.map == null ? null : Points.map.missing);
        List<String> order = new ArrayList<String>(plan.order);
        for (String id : dirOf.keySet()) if (!order.contains(id)) order.add(id);   // disabled ones, for the list
        for (String id : order) {
            File dir = dirOf.get(id);
            Info info = new Info(id);
            infos.add(info);
            if ("hmgapi".equals(id)) {
                info.state = "error";
                info.error = "the id hmgapi is reserved";
                continue;
            }
            if (new File(dir, ".disabled").exists()) {
                info.state = "disabled";
                HmgApi.i("mods: " + id + " is disabled");
                continue;
            }
            if (plan.refused.containsKey(id)) {
                info.state = plan.refused.get(id).startsWith("conflict") ? "conflict" : "error";
                info.error = plan.refused.get(id);
                HmgApi.i("mods: " + id + " not loaded: " + info.error);
                continue;
            }
            try {
                loadOne(id, dir, info);
            } catch (Throwable t) {
                info.state = "error";
                info.error = String.valueOf(t);
                HmgApi.e("mods: " + id + " not loaded", t);
            }
        }
        HmgApi.i("mods: " + launcher.size() + " launcher entries, " + starActions.size() + " star key actions, "
                + withHooks.size() + " with hooks");
    }

    private static void loadOne(String id, File dir, Info info) throws Throwable {
        JSONObject mod = new JSONObject(read(new File(dir, "mod.json")));
        if (!id.equals(mod.optString("id"))) {
            throw new IllegalArgumentException("mod.json id \"" + mod.optString("id") + "\" doesn't match folder " + id);
        }
        String version = mod.optString("version", "?");
        info.name = mod.optString("name", id);
        info.version = version;
        info.description = mod.optString("description", "");
        if (mod.has("system")) {
            Map<String, Object> hmgapi = hmgapiRaw();
            String why = SystemCheck.check(Json.object(read(new File(dir, "mod.json"))).get("system"), facts(hmgapi));
            if (why != null && !SystemCheck.ignored(hmgapi, id)) {
                info.state = "unsupported";
                info.error = why;
                HmgApi.i("mods: " + id + " not loaded: " + why);
                return;
            }
            if (why != null) HmgApi.i("mods: " + id + ": " + why + "; overrides.ignore_system is set, loading it at the owner's risk");
        }
        ModContext ctx = new ModContext(id, version, dir, settings(id, dir));
        for (Map.Entry<String, Map<String, String>> e : plan.points.entrySet()) {
            String mode = e.getValue().get(id);
            if (mode != null) ctx.declared.put(e.getKey(), mode);
        }

        JSONObject provides = mod.optJSONObject("provides");
        JSONArray entries = provides == null ? null : provides.optJSONArray("launcher");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject e = entries.getJSONObject(i);
            String pkg = e.getString("package"), cls = e.getString("class");
            if (cls.startsWith(".")) cls = pkg + cls;
            LauncherEntry le = new LauncherEntry(id, pkg, cls, e.optString("title", null), launcher.size());
            le.iconDay = picture(dir, e.optString("icon", null));
            le.iconNight = picture(dir, e.optString("icon_night", null));
            le.iconDisabledDay = picture(dir, e.optString("icon_disabled", null));
            le.iconDisabledNight = picture(dir, e.optString("icon_disabled_night", null));
            launcher.add(le);
            info.launcher++;
        }

        JSONArray star = provides == null ? null : provides.optJSONArray("star_key");
        for (int i = 0; star != null && i < star.length(); i++) {
            JSONObject a = star.getJSONObject(i);
            JSONObject act = a.optJSONObject("activity");
            String pkg = act == null ? null : act.getString("package");
            String cls = act == null ? null : act.getString("class");
            if (cls != null && cls.startsWith(".")) cls = pkg + cls;
            String bc = a.optString("broadcast", null);
            if (act == null && bc == null) throw new IllegalArgumentException("star_key " + a.optString("id") + ": needs activity or broadcast");
            starActions.add(new StarAction(id, mod.optString("name", id), a.getString("id"),
                    a.optString("title", a.getString("id")), pkg, cls, bc, starActions.size()));
            info.starKey++;
        }

        Map<String, Object> raw = Json.object(read(new File(dir, "mod.json")));
        rawOf.put(id, raw);
        for (hmgapi.engine.Version.Dep d : hmgapi.engine.Version.deps(raw.get("requires"))) ctx.requires.add(d.id);
        JSONObject hooks = mod.optJSONObject("hooks");
        if (hooks != null) {
            DEX_CACHE.mkdirs();
            ModEntry entry = loadEntry(id, raw, dir, DEX_CACHE);
            entry.zygoteInit(ctx);
            withHooks.add(new Loaded(ctx, entry));
            info.hooks = true;
        }
        HmgApi.i("mods: loaded " + id + " " + version);
    }

    private static Map<String, Object> hmgapiRaw;
    private static SystemCheck.Facts facts;

    /** hmgapi.json as plain maps (SystemCheck's form); read once, only if a mod has requirements. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> hmgapiRaw() {
        if (hmgapiRaw == null) hmgapiRaw = Json.object(hmgapiSettings.toString());
        return hmgapiRaw;
    }

    @SuppressWarnings("unchecked")
    private static SystemCheck.Facts facts(Map<String, Object> hmgapi) {
        if (facts == null) {
            facts = new SystemCheck.Facts("", (Map<String, Object>) hmgapi.get("car"));
            HmgApi.i("mods: this unit: " + facts.describe());
        }
        return facts;
    }

    /** Defaults from settings.json, overridden by the owner's /data/hmgapi/settings/<id>.json. */
    private static JSONObject settings(String id, File dir) {
        JSONObject out = new JSONObject();
        try {
            File f = new File(dir, "settings.json");
            if (f.isFile()) {
                JSONArray list = new JSONObject(read(f)).optJSONArray("settings");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject s = list.getJSONObject(i);
                    if (s.has("key") && s.has("default")) out.put(s.getString("key"), s.get("default"));
                }
            }
            File own = new File(SETTINGS, id + ".json");
            if (own.isFile()) {
                JSONObject values = new JSONObject(read(own));
                for (Iterator<?> it = values.keys(); it.hasNext();) {
                    String k = (String) it.next();
                    out.put(k, values.get(k));
                }
            }
        } catch (Exception e) {
            HmgApi.e("mods: settings of " + id, e);
        }
        return out;
    }

    /** What the HMG-API app shows (hmgapi.app.HmgApiStatus.json). */
    static String statusJson(String updates) {
        try {
            JSONObject s = new JSONObject();
            s.put("hmgapi", VERSION);
            s.put("updates", updates);
            if (Points.map != null) {
                JSONObject m = new JSONObject();
                m.put("status", Points.map.status);
                m.put("on", Points.map.runnable);
                if (Points.map.car != null) m.put("car", Points.map.car);
                if (Points.map.folder != null) m.put("folder", Points.map.folder);
                if (Points.map.reason != null) m.put("reason", Points.map.reason);
                m.put("checked_here", Points.map.checkedHere);
                s.put("map", m);
            }
            JSONArray mods = new JSONArray();
            for (Info i : infos) {
                JSONObject m = new JSONObject();
                m.put("id", i.id);
                m.put("name", i.name);
                m.put("version", i.version);
                m.put("description", i.description);
                m.put("state", i.state);
                if (i.error != null) m.put("error", i.error);
                m.put("launcher", i.launcher);
                m.put("star_key", i.starKey);
                m.put("hooks", i.hooks);
                mods.put(m);
            }
            s.put("mods", mods);
            return s.toString();
        } catch (Exception e) {
            return null;
        }
    }

    static void appLoaded(LoadedApp app) {
        try {
            if (app.context != null) DexCacheCleanup.reconcile(app.context.getDir("hmgapi-dex", 0), installedIds);
        } catch (Exception ignored) {
            // Stale optimized code is harmless; cleanup must not disrupt app startup.
        }
        for (Loaded m : withHooks) {
            try {
                m.entry.appLoaded(m.context, app);
            } catch (Throwable t) {
                m.context.error("appLoaded in " + app.packageName, t);
            }
        }
    }

    /** a PNG in the mod's folder (at most 256 KB), or null */
    static byte[] picture(File dir, String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.contains("..")) return null;
        File f = new File(dir, rel);
        if (!f.isFile() || f.length() > 256 * 1024) {
            HmgApi.i("mods: " + dir.getName() + ": tile picture " + rel + " missing or over 256 KB");
            return null;
        }
        try {
            byte[] b = readBytes(f);
            return b.length == 0 ? null : b;
        } catch (IOException e) {
            return null;
        }
    }

    static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    static List<LauncherEntry> launcherEntries() {
        return Collections.unmodifiableList(launcher);
    }

    static String read(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}

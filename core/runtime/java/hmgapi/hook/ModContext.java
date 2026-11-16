package hmgapi.hook;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** What a mod's hook code gets from HMG-API: its id, folder, settings and log. */
public final class ModContext {
    public final String id;
    public final String version;
    /** The mod's folder (/data/hmgapi/mods/<id>). Readable in the zygote only: apps run as their own uid. */
    public final File dir;
    /** the mod's settings; replaced (not changed) when the owner saves new ones, so reads never tear */
    volatile JSONObject settings;
    /** off: its hooks pass straight through (the owner turned it off, or a mod it requires) */
    volatile boolean enabled = true;
    /** mods this one declared in "requires": the only ones whose API it may use */
    final java.util.Set<String> requires = new java.util.HashSet<String>();
    /** objects mods hand to the mods that require them: mod id -> name -> object */
    static final Map<String, Map<String, Object>> published = new java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>>();

    /** point id -> declared mode (mod.json hooks.points) */
    final Map<String, String> declared = new HashMap<String, String>();

    ModContext(String id, String version, File dir, JSONObject settings) {
        this.id = id;
        this.version = version;
        this.dir = dir;
        this.settings = settings;
    }

    /**
     * Hooks one of HMG-API's hook points. It must be declared in mod.json
     * ("hooks": {"points": {"<id>": "observe|change|replace"}}), and the hook may do
     * only what its mode allows: HMG-API undoes the rest and logs it. A replace hook
     * runs after every other hook on that method.
     */
    public java.lang.reflect.Member hook(ClassLoader cl, String point, MethodHook hook) {
        String mode = declared.get(point);
        if (mode == null) throw new IllegalStateException(id + ": hook point " + point + " isn't declared in mod.json");
        Guard g = new Guard(this, point, mode, hook);
        g.priority = "replace".equals(mode) ? 1 : 0;
        return Points.hookAs(cl, point, g, mode);
    }

    /** Keeps a mod's hook to what it declared. */
    static final class Guard extends MethodHook {
        final ModContext mod;
        final String point, mode;
        final MethodHook inner;
        boolean warned;

        Guard(ModContext mod, String point, String mode, MethodHook inner) {
            this.mod = mod;
            this.point = point;
            this.mode = mode;
            this.inner = inner;
        }

        void warn(String what) {
            if (warned) return;
            warned = true;
            mod.error(what + " on " + point + ", but it declared \"" + mode + "\": undone (declare the mode it needs in mod.json)", null);
        }

        @Override
        protected void beforeHookedMethod(Param p) throws Throwable {
            if (!mod.enabled) return;   // turned off: pass straight through
            Object[] was = p.args.clone();
            inner.beforeHookedMethod(p);
            if (p.returnEarly && !"replace".equals(mode)) {
                p.reset();
                warn("it skipped the original method");
            }
            if ("observe".equals(mode)) {
                for (int i = 0; i < was.length; i++) {
                    if (p.args[i] != was[i]) {
                        System.arraycopy(was, 0, p.args, 0, was.length);
                        warn("it changed the arguments");
                        break;
                    }
                }
            }
        }

        @Override
        protected void afterHookedMethod(Param p) throws Throwable {
            if (!mod.enabled) return;
            Object r = p.getResult();
            Throwable t = p.getThrowable();
            inner.afterHookedMethod(p);
            if ("observe".equals(mode) && (p.getResult() != r || p.getThrowable() != t)) {
                if (t != null) p.setThrowable(t);
                else p.setResult(r);
                warn("it changed the result");
            }
        }
    }

    /**
     * A class from this mod's app part, for code that must link against one app's own
     * classes or the libraries it declares (subclassing its library's listener classes,
     * say), which the mod's main code can't: that is loaded in the zygote, before any app.
     * Every class in className's package is loaded from the mod's jar in this app, with
     * the app's class loader behind it. Keep the part in a package of its own that uses
     * only HMG-API's API, Android and the app's classes: no direct reference between it
     * and the mod's other classes, either way (Dalvik rejects a pre-verified class whose
     * same-jar reference resolves through another loader). The main code reaches it
     * through this call and reflection; share state through ModContext.
     */
    public Class<?> appPart(LoadedApp app, String className) throws Exception {
        return Mods.appPart(this, app, className);
    }

    /**
     * Patches a native library in this app's memory, never its file: the recipes this
     * mod declares for it in mod.json ("hooks": {"native": {"<lib>.so": ["<recipe>", ...]}},
     * lpatch recipes, see hmgapi.engine.MemRecipe). The first that fits this build of the
     * library is applied once it's loaded (now, if it already is). A recipe overlapping
     * another mod's on the same library isn't applied (logged). Call it in appLoaded.
     */
    @SuppressWarnings("unchecked")
    public void nativePatch(LoadedApp app, String lib) {
        try {
            Object hooks = Mods.rawOf.get(id) == null ? null : Mods.rawOf.get(id).get("hooks");
            Object nat = hooks instanceof Map ? ((Map<String, Object>) hooks).get("native") : null;
            Object files = nat instanceof Map ? ((Map<String, Object>) nat).get(lib) : null;
            if (!(files instanceof java.util.List)) throw new IllegalStateException(id + ": no recipes for " + lib + " in mod.json hooks.native");
            java.util.List<String> names = new java.util.ArrayList<String>();
            for (Object o : (java.util.List<Object>) files) names.add(String.valueOf(o));
            NativePatch.request(this, lib, NativePatch.read(this, names));
        } catch (Throwable t) {
            error("native patch " + lib + " in " + app.processName, t);
        }
    }

    /** Is the mod on right now? (Its hooks don't run while it's off.) */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Hands an object to the mods that require this one (they get it with use()).
     * Its type should come from this mod's exported API packages (mod.json "api").
     */
    public void publish(String name, Object api) {
        Map<String, Object> m = published.get(id);
        if (m == null) published.put(id, m = new java.util.concurrent.ConcurrentHashMap<String, Object>());
        m.put(name, api);
    }

    /** An object another mod published; that mod must be in this one's "requires". Null if it hasn't published it. */
    public Object use(String modId, String name) {
        if (!requires.contains(modId)) throw new IllegalStateException(id + " uses " + modId + "'s API but doesn't require it in mod.json");
        Map<String, Object> m = published.get(modId);
        return m == null ? null : m.get(name);
    }

    /** A setting: the owner's value if set, else the default from the mod's settings.json. */
    public Object setting(String key) {
        return settings.opt(key);
    }

    public String settingString(String key, String fallback) {
        Object v = settings.opt(key);
        return v == null || v == JSONObject.NULL ? fallback : String.valueOf(v);
    }

    public boolean settingBoolean(String key, boolean fallback) {
        Object v = settings.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    public int settingInt(String key, int fallback) {
        Object v = settings.opt(key);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    /** Logs to logcat with tag HMG-API, prefixed with the mod id (HMG-API's log views filter on it). */
    public void log(String message) {
        Bridge.log(4, "[" + id + "] " + message);
    }

    private static final Map<String, String> MD5S = new HashMap<String, String>();

    /**
     * md5 of a file, or null if it can't be read: how a mod tells which build of
     * an app it's looking at (its hook maps are keyed by the stock file's md5).
     * Call it in zygoteInit: there it runs once, as root, before any app starts.
     */
    public static String md5(String path) {
        synchronized (MD5S) {
            if (MD5S.containsKey(path)) return MD5S.get(path);
            String r = null;
            try {
                MessageDigest d = MessageDigest.getInstance("MD5");
                InputStream in = new FileInputStream(path);
                try {
                    byte[] b = new byte[65536];
                    int n;
                    while ((n = in.read(b)) > 0) d.update(b, 0, n);
                } finally {
                    in.close();
                }
                StringBuilder s = new StringBuilder();
                for (byte x : d.digest()) s.append(String.format("%02x", x & 0xff));
                r = s.toString();
            } catch (Exception e) {
                r = null;
            }
            MD5S.put(path, r);
            return r;
        }
    }

    public void error(String message, Throwable t) {
        Bridge.log(6, "[" + id + "] " + message + (t == null ? "" : ": " + Bridge.trace(t)));
    }
}

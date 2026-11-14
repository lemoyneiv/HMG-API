package hmgapi.engine;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * engine publish-state: what every app process may know about the mods, so mods go
 * on, off and change settings without a restart. Writes /data/hmgapi/public/state.json
 * (world-readable; everything else in /data/hmgapi stays root-only), then broadcasts
 * hmgapi.intent.action.STATE_CHANGED. HMG-API's runtime in each app re-reads it.
 *
 *   {"serial": <ms>, "mods": {"<id>": {"enabled": true, "version": "1.0.0", "live": true,
 *                                       "settings": {<values; "private" ones left out>},
 *                                       "apps": [<its apps' packages>], "usb": [<its USB folders>],
 *                                       "not_while_driving": [<its apps that follow the driving restriction>],
 *                                       "panel": [{"title", "widget": "<package>/<provider>"}]}}}
 *
 * "input" (top level): the enabled mods' button bindings, clashes settled (Keys).
 * "projection" (top level): Android Auto density, sensors, fuel and connector types,
 * and which apps hear projection events (Projection.settle).
 *
 * A mod is "enabled" only if it's turned on and every mod it requires is too. Mods'
 * mod.json and declared hook JARs are made readable (their folders traversable), so an app can
 * load a "live" mod that wasn't loaded when it started; settings files are not.
 */
final class LiveState {
    private LiveState() {}

    static final String ACTION = "hmgapi.intent.action.STATE_CHANGED";

    /** every installed mod's mod.json; on gets the enabled ones (turned on, and every mod they require too) */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> mods(Engine e, Set<String> on) {
        File[] ds = new File(e.data, "mods").listFiles();
        Map<String, Map<String, Object>> json = new LinkedHashMap<String, Map<String, Object>>();
        if (ds != null) {
            java.util.Arrays.sort(ds);
            for (File d : ds) {
                File mj = new File(d, "mod.json");
                if (!d.isDirectory() || !mj.isFile()) continue;
                try {
                    json.put(d.getName(), Json.object(Engine.read(mj)));
                    if (!new File(d, ".disabled").exists()) on.add(d.getName());
                } catch (Exception x) {
                    // a broken mod.json: left out (the loader reports it)
                }
            }
        }
        // a mod whose requirement is off is off too
        boolean more = true;
        while (more) {
            more = false;
            for (String id : new LinkedHashSet<String>(on)) {
                for (Version.Dep d : Version.deps(json.get(id).get("requires"))) {
                    if (!on.contains(d.id)) {
                        on.remove(id);
                        more = true;
                    }
                }
            }
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    static int publish(Engine e) throws Exception {
        File modsDir = new File(e.data, "mods");
        Set<String> on = new LinkedHashSet<String>();
        Map<String, Map<String, Object>> json = mods(e, on);
        Map<String, Object> mods = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Map<String, Object>> m : json.entrySet()) {
            Map<String, Object> s = new LinkedHashMap<String, Object>();
            s.put("enabled", on.contains(m.getKey()));
            s.put("version", m.getValue().get("version"));
            s.put("live", Boolean.TRUE.equals(m.getValue().get("live")));
            s.put("settings", publicSettings(e, new File(modsDir, m.getKey()), m.getKey()));
            ModRuntime.Decl d = ModRuntime.of(m.getKey(), m.getValue(), null);
            List<Object> apps = new java.util.ArrayList<Object>();
            for (ModRuntime.App a : d.apps) apps.add(a.pkg);
            if (!apps.isEmpty()) s.put("apps", apps);
            if (!d.usb.isEmpty()) s.put("usb", new java.util.ArrayList<Object>(d.usb));
            if (d.ble) s.put("ble", true);
            if (!d.notWhileDriving.isEmpty()) s.put("not_while_driving", new java.util.ArrayList<Object>(d.notWhileDriving));
            if (!d.panel.isEmpty()) s.put("panel", new java.util.ArrayList<Object>(d.panel));
            mods.put(m.getKey(), s);
            readable(e, new File(modsDir, m.getKey()), m.getValue());
            nativeReadable(new File(modsDir, m.getKey()), m.getValue());
        }
        ModRuntime.publish(e, json, on);   // apps, services, USB folders: hmgapid acts on them
        List<String> notes = new java.util.ArrayList<String>();
        List<Object> input = new java.util.ArrayList<Object>();
        for (Keys.Binding k : ModRuntime.bindings(e, json, on, notes)) input.add(k.json());
        for (String n : notes) e.out.add(n);
        Map<String, Object> state = new LinkedHashMap<String, Object>();
        state.put("serial", System.currentTimeMillis());
        state.put("mods", mods);
        state.put("input", input);   // button bindings, settled: hook.Input in system_server acts on them
        state.put("projection", ModRuntime.projection(e, json, on, notes));   // hook.Projection in GPM
        for (String n : notes) if (!e.out.contains(n)) e.out.add(n);
        File pub = new File(e.data, "public");
        pub.mkdirs();
        File tmp = new File(pub, ".state.json.tmp");
        Engine.write(tmp, Json.write(state));
        tmp.setReadable(true, false);
        if (!tmp.renameTo(new File(pub, "state.json"))) throw new java.io.IOException("can't write state.json");
        traversable(e.data);
        traversable(modsDir);
        pub.setReadable(true, false);
        pub.setExecutable(true, false);
        if (e.host) {
            e.hostAction("broadcast " + ACTION);
        } else {
            try {
                Engine.exec("/system/bin/am", "broadcast", "-a", ACTION);
            } catch (Exception x) {
                // Android not up: apps read the file when they start anyway
            }
        }
        e.out.add("state published: " + on.size() + " of " + json.size() + " mods on");
        return 0;
    }

    /** Defaults from settings.json, the owner's values over them, without "private" ones. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> publicSettings(Engine e, File dir, String id) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Set<String> priv = new LinkedHashSet<String>();
        try {
            File sj = new File(dir, "settings.json");
            if (sj.isFile()) {
                Object list = Json.object(Engine.read(sj)).get("settings");
                if (list instanceof List) {
                    for (Object o : (List<Object>) list) {
                        Map<String, Object> s = (Map<String, Object>) o;
                        String k = String.valueOf(s.get("key"));
                        if (Boolean.TRUE.equals(s.get("private"))) priv.add(k);
                        else if (s.containsKey("default")) out.put(k, s.get("default"));
                    }
                }
            }
            File own = new File(e.data, "settings/" + id + ".json");
            if (own.isFile()) {
                for (Map.Entry<String, Object> v : Json.object(Engine.read(own)).entrySet()) {
                    if (!priv.contains(v.getKey())) out.put(v.getKey(), v.getValue());
                }
            }
        } catch (Exception x) {
            // unreadable settings: defaults only
        }
        return out;
    }

    /** Only metadata and the declared code JAR readable; other files stay private. */
    @SuppressWarnings("unchecked")
    static void readable(Engine e, File dir, Map<String, Object> mod) throws Exception {
        traversable(dir);
        new File(dir, "mod.json").setReadable(true, false);
        Object hooks = mod.get("hooks");
        Object declared = hooks instanceof Map ? ((Map<String, Object>) hooks).get("jar") : null;
        String name = declared == null ? "hooks.jar" : String.valueOf(declared);
        File jar = new File(dir, name);
        // A manifest (or a symlink in it) must not expose another mod's/private file.
        if (new File(name).isAbsolute() || !jar.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) return;
        if (!jar.isFile()) return;
        for (File d = jar.getParentFile(); d != null && !d.equals(dir); d = d.getParentFile()) traversable(d);
        jar.setReadable(true, false);
    }

    /** a mod's native patch recipes (hooks.native) too: the apps that load the libraries read them */
    @SuppressWarnings("unchecked")
    static void nativeReadable(File dir, Map<String, Object> mod) {
        Object hooks = mod.get("hooks");
        Object nat = hooks instanceof Map ? ((Map<String, Object>) hooks).get("native") : null;
        if (!(nat instanceof Map)) return;
        for (Object files : ((Map<String, Object>) nat).values()) {
            if (!(files instanceof List)) continue;
            for (Object f : (List<Object>) files) {
                File r = new File(dir, String.valueOf(f));
                for (File d = r.getParentFile(); d != null && !d.equals(dir); d = d.getParentFile()) traversable(d);
                r.setReadable(true, false);
            }
        }
    }

    static void traversable(File d) {
        d.setExecutable(true, false);
    }
}

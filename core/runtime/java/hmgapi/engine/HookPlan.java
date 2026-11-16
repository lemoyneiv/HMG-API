package hmgapi.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which mods load and how their hooks fit together, decided before any mod code loads
 * (the zygote's mod loader and the engine's plan use the same rules: Mods.load reads
 * the mods with {@link #enabled}, Mods.lateLoad asks {@link #late}).
 *
 * A mod declares each hook point it hooks, in mod.json:
 *   "hooks": { "jar": ..., "entry": ..., "points": { "<point id>": "observe" | "change" | "replace" } }
 *   observe  reads only (arguments, result)
 *   change   may change arguments (before) or the result (after)
 *   replace  may skip the original method (sets the result before it runs)
 *
 * Order: mods a mod requires come before it, otherwise by id. On one method, every
 * observe/change hook runs before a replace hook, so a replacement never hides
 * another mod's hook. Going down that order, a mod isn't loaded (the reason says why) when:
 *   - a hook point it declares has an unknown mode, or isn't on this car's map;
 *   - a mod it requires isn't installed and turned on, isn't in the version range
 *     it asks for, or isn't loaded;
 *   - it and a mod before it that loads can't run together: either lists the other
 *     in "conflicts" (with the other's version in the range). The first of the two
 *     loads, whichever of them lists the other, so when both list each other the
 *     outcome is the same;
 *   - it and a mod before it that loads both replace the same point.
 */
public final class HookPlan {
    public static final String OBSERVE = "observe", CHANGE = "change", REPLACE = "replace";

    /** mods in load order */
    public final List<String> order = new ArrayList<String>();
    /** mod -> why it isn't loaded */
    public final Map<String, String> refused = new LinkedHashMap<String, String>();
    /** point -> (mod -> mode), loaded mods only */
    public final Map<String, Map<String, String>> points = new TreeMap<String, Map<String, String>>();

    /**
     * mods: id -> mod.json (enabled mods only). known: the point ids this car's map
     * has (null: don't check). missing: catalogue points this car's map lacks, and why
     * (a mod that hooks one isn't loaded here; its files and settings still apply).
     */
    public static HookPlan of(Map<String, Map<String, Object>> mods, Set<String> known) {
        return of(mods, known, null);
    }

    public static HookPlan of(Map<String, Map<String, Object>> mods, Set<String> known, Map<String, String> missing) {
        return plan(mods, null, known, missing, null);
    }

    /** observeOnly: points mapped on this unit only (CarMap.localOnly), which only observe hooks may use */
    public static HookPlan of(Map<String, Map<String, Object>> mods, Set<String> known, Map<String, String> missing, Set<String> observeOnly) {
        return plan(mods, null, known, missing, observeOnly);
    }

    /**
     * The enabled mods in a mods folder (/data/hmgapi/mods): every folder but "hmgapi"
     * without a .disabled file, by name, with its mod.json (an empty one if it can't be
     * read: the loader reports that when it loads it).
     */
    public static Map<String, Map<String, Object>> enabled(File modsDir) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        File[] dirs = modsDir.listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File dir : dirs) {
            String id = dir.getName();
            if (!dir.isDirectory() || "hmgapi".equals(id) || new File(dir, ".disabled").exists()) continue;
            try {
                out.put(id, Json.object(read(new File(dir, "mod.json"))));
            } catch (Exception e) {
                out.put(id, new LinkedHashMap<String, Object>());
            }
        }
        return out;
    }

    /**
     * A "live" mod turned on while an app runs (Mods.lateLoad): the plan of the mods
     * already loaded in this app (loaded: id -> mod.json) and this one. on: every mod
     * turned on now (id -> version, from the public state), for "requires": a mod
     * that's on but not loaded in this app counts as there. The new mod isn't loaded
     * if the plan refuses it, or refuses a mod already loaded here that it loads
     * without the new one. A loaded mod counts even if it was turned off since (its
     * hooks pass through, but turned on again it runs without another check), so the
     * one loaded first stays until the next restart, where the usual order decides.
     */
    public static HookPlan late(Map<String, Map<String, Object>> loaded, String id, Map<String, Object> mod,
            Map<String, String> on, Set<String> known, Map<String, String> missing) {
        return late(loaded, id, mod, on, known, missing, null);
    }

    public static HookPlan late(Map<String, Map<String, Object>> loaded, String id, Map<String, Object> mod,
            Map<String, String> on, Set<String> known, Map<String, String> missing, Set<String> observeOnly) {
        Map<String, String> present = new LinkedHashMap<String, String>(on);
        for (Map.Entry<String, Map<String, Object>> m : loaded.entrySet()) present.put(m.getKey(), version(m.getValue()));
        present.put(id, version(mod));
        Map<String, Map<String, Object>> all = new LinkedHashMap<String, Map<String, Object>>(loaded);
        all.put(id, mod);
        HookPlan before = plan(loaded, present, known, missing, observeOnly), p = plan(all, present, known, missing, observeOnly);
        if (p.refused.containsKey(id)) return p;
        for (String other : loaded.keySet()) {
            if (p.refused.containsKey(other) && !before.refused.containsKey(other)) {
                p.refused.put(id, p.refused.get(other) + " (" + other + " is loaded in this app already: the next restart settles it)");
                for (Map<String, String> on2 : p.points.values()) on2.remove(id);
                break;
            }
        }
        return p;
    }

    /** the state the HMG-API app shows for a refused mod: "conflict" (yellow) or "error" (red) */
    public static String state(String why) {
        return why != null && why.startsWith("conflict") ? "conflict" : "error";
    }

    /** present: id -> version of the mods "requires" may name (null: the mods themselves) */
    @SuppressWarnings("unchecked")
    static HookPlan plan(Map<String, Map<String, Object>> mods, Map<String, String> present, Set<String> known, Map<String, String> missing,
            Set<String> observeOnly) {
        if (present == null) {
            present = new LinkedHashMap<String, String>();
            for (Map.Entry<String, Map<String, Object>> m : mods.entrySet()) present.put(m.getKey(), version(m.getValue()));
        }
        HookPlan p = new HookPlan();
        // order: requires first, then by id (a loop is broken by id)
        Set<String> done = new LinkedHashSet<String>();
        for (String id : new TreeSet<String>(mods.keySet())) visit(id, mods, done, new LinkedHashSet<String>());
        p.order.addAll(done);
        for (String id : p.order) {
            Map<String, Object> mod = mods.get(id);
            Object hooks = mod.get("hooks");
            Object decl = hooks instanceof Map ? ((Map<String, Object>) hooks).get("points") : null;
            String why = null;
            if (decl != null && !(decl instanceof Map)) {
                why = "\"hooks\": \"points\" must be an object: {\"<point id>\": \"observe|change|replace\"}";
            }
            if (why == null && decl != null) why = p.pointProblem(id, (Map<String, Object>) decl, known, missing, observeOnly);
            if (why == null) why = p.requiresProblem(id, mod, present);
            if (why == null) why = p.conflict(id, mods);
            if (why != null) {
                p.refused.put(id, why);
                continue;
            }
            if (decl == null) continue;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) decl).entrySet()) {
                Map<String, String> on = p.points.get(e.getKey());
                if (on == null) p.points.put(e.getKey(), on = new LinkedHashMap<String, String>());
                on.put(id, String.valueOf(e.getValue()));
            }
        }
        // a mod that requires a refused mod can't load either (one that comes later: a requires loop)
        boolean more = true;
        while (more) {
            more = false;
            for (String id : p.order) {
                if (p.refused.containsKey(id)) continue;
                for (Version.Dep d : Version.deps(mods.get(id).get("requires"))) {
                    if (p.refused.containsKey(d.id)) {
                        p.refused.put(id, "requires " + d.id + ", which isn't loaded (" + p.refused.get(d.id) + ")");
                        for (Map<String, String> on : p.points.values()) on.remove(id);
                        more = true;
                        break;
                    }
                }
            }
        }
        return p;
    }

    /** a declared point this mod can't hook here, or replaces after a loaded mod: why, or null */
    String pointProblem(String id, Map<String, Object> decl, Set<String> known, Map<String, String> missing, Set<String> observeOnly) {
        for (Map.Entry<String, Object> e : decl.entrySet()) {
            String mode = String.valueOf(e.getValue());
            if (!OBSERVE.equals(mode) && !CHANGE.equals(mode) && !REPLACE.equals(mode)) {
                return "hook point " + e.getKey() + ": mode \"" + mode + "\" (observe, change or replace)";
            }
            if (known != null && !known.contains(e.getKey())) {
                return missing != null && missing.containsKey(e.getKey())
                        ? "hook point " + e.getKey() + " isn't mapped on this car (" + missing.get(e.getKey())
                                + "): its hooks are off here, its other parts still apply"
                        : "hook point " + e.getKey() + " isn't one HMG-API has (a new one can be added to HMG-API)";
            }
            if (observeOnly != null && observeOnly.contains(e.getKey()) && !OBSERVE.equals(mode)) {
                return "hook point " + e.getKey() + " is mapped on this car only, which is enough for observe hooks: a " + mode
                        + " hook needs it in HMG-API's map, or the owner's \"Try unchecked maps\"";
            }
            if (REPLACE.equals(mode)) {
                Map<String, String> on = points.get(e.getKey());
                if (on == null) continue;
                for (Map.Entry<String, String> o : on.entrySet()) {
                    if (REPLACE.equals(o.getValue())) {
                        return "conflict: " + id + " and " + o.getKey() + " both replace " + e.getKey() + "; turn one of them off";
                    }
                }
            }
        }
        return null;
    }

    /** a mod this one requires isn't there, in range, or loaded: why, or null */
    String requiresProblem(String id, Map<String, Object> mod, Map<String, String> present) {
        for (Version.Dep d : Version.deps(mod.get("requires"))) {
            String v = present.get(d.id);
            if (v == null) return "requires " + d + ", which isn't installed and turned on";
            if (!in(v, d.range, false)) return "requires " + d + ", but " + d.id + " " + v + " is installed";
            if (refused.containsKey(d.id)) return "requires " + d.id + ", which isn't loaded (" + refused.get(d.id) + ")";
        }
        return null;
    }

    /** a mod before this one that loads and can't run with it ("conflicts", either way): why, or null */
    String conflict(String id, Map<String, Map<String, Object>> mods) {
        Map<String, Object> mine = mods.get(id);
        for (String other : order) {
            if (other.equals(id)) break;
            if (refused.containsKey(other)) continue;
            Map<String, Object> theirs = mods.get(other);
            Version.Dep a = listed(mine, other, version(theirs)), b = listed(theirs, id, version(mine));
            if (a == null && b == null) continue;
            String by = a != null && b != null ? "each lists the other in \"conflicts\""
                    : a != null ? id + "'s \"conflicts\" lists " + a : other + "'s \"conflicts\" lists " + b;
            return "conflict: " + id + " " + version(mine) + " and " + other + " " + version(theirs) + " can't run together ("
                    + by + "); " + other + " loads, turn it off to use " + id + " instead";
        }
        return null;
    }

    /** mod's "conflicts" entry for other at version v, or null */
    static Version.Dep listed(Map<String, Object> mod, String other, String v) {
        for (Version.Dep d : Version.deps(mod.get("conflicts"))) {
            if (d.id.equals(other) && in(v, d.range, true)) return d;
        }
        return null;
    }

    /** does version v meet range? (a version or range that can't be read: unreadable) */
    static boolean in(String v, String range, boolean unreadable) {
        try {
            return Version.satisfies(v, range);
        } catch (IllegalArgumentException e) {
            return unreadable;
        }
    }

    static String version(Map<String, Object> mod) {
        Object v = mod.get("version");
        return v == null ? "0" : String.valueOf(v);
    }

    static void visit(String id, Map<String, Map<String, Object>> mods, Set<String> done, Set<String> path) {
        if (done.contains(id) || !mods.containsKey(id) || !path.add(id)) return;
        for (Version.Dep d : Version.deps(mods.get(id).get("requires"))) visit(d.id, mods, done, path);
        done.add(id);
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

    /** The plan's lines about hooks, for engine plan. */
    public List<String> report() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> r : refused.entrySet()) out.add("hooks    " + r.getKey() + " won't load: " + r.getValue());
        for (Map.Entry<String, Map<String, String>> e : points.entrySet()) {
            if (e.getValue().size() < 2) continue;
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, String> m : e.getValue().entrySet()) {
                if (b.length() > 0) b.append(", ");
                b.append(m.getKey()).append(" (").append(m.getValue()).append(')');
            }
            out.add("hooks    " + e.getKey() + ": " + b + " share it");
        }
        return out;
    }
}

package hmgapi.engine;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This car's hook map: which of HMG-API's hook points (core/hookpoints) are which
 * class, method or field in this car's own apps. HMG-API's hooks (its own features
 * and mods' hooks) run only on a car with a map that's good enough; everything else
 * (mods' files, settings, apps, services, readings) runs on any car:
 *
 *   none        no map for this car (make/model/region/firmware): hooks off
 *   mismatch    a map, but the car's framework isn't the one it was made from: hooks off
 *   mapped      points found from the car's stock files, not checked on a car yet:
 *               runs if hmgapi.json "testing_maps" is true, or after this car passed
 *               `engine verify-map` (state/map-check.json)
 *   probed      made on the car itself (engine map-car --write, from its own files): as mapped
 *   verified    checked on a real car: runs
 *
 * A map may lack points (missing): ones the mapper didn't find (car.json "missing"),
 * ones newer than the map, and every point of an app whose stock file isn't the one
 * the map was made from. Only the features and mods that need them are off.
 *
 * Maps: /data/hmgapi/maps/<make>/<model>/<region>/<firmware>/{car.json, <app>.json},
 * and /data/hmgapi/maps/points.txt (every point id in the catalogue). Used by the
 * hook runtime in the zygote and by the engine; plain Java (Json, java.io).
 *
 * What this unit mapped itself (MapProbe: engine map-car --local / --write) is in
 * /data/hmgapi/state/local-map.json, outside maps/, which every HMG-API update replaces:
 *   "points"     points HMG-API's map lacks only because it's older than them, found
 *                here by name and checked here (MapCheck) before they were written. They
 *                fill those gaps (never a point HMG-API's map has) while every stock file
 *                they came from is unchanged; their ids are in local. Reads and mods'
 *                observe hooks use them; anything else needs HMG-API's map, or the owner's
 *                "Try unchecked maps" (localOnly()).
 *   "whole"      this car mapped from its own files, for a car HMG-API has no map for, or
 *                whose map doesn't fit its framework (mismatch): used as a probed map
 *                (folder "local"), with the same md5 checks.
 * The owner's switch settings/allow/no_local_points turns both off.
 */
public final class CarMap {
    public String status = "none";
    public String reason;
    public String folder;           // relative to the maps dir, e.g. kia/ev6/usa/260507
    public String car;              // "Kia EV6 (usa 260507)"
    public boolean checkedHere;     // passed `engine verify-map` on this unit
    public boolean runnable;
    public Map<String, Object> carJson;
    /** point id -> its entry ({"class": ...}, {"method": ..., "params": [...]}, ...) */
    public final Map<String, Map<String, Object>> points = new LinkedHashMap<String, Map<String, Object>>();
    /** point id -> app (the catalogue file it's in) */
    public final Map<String, String> appOf = new LinkedHashMap<String, String>();
    /** point id -> why this car's map doesn't have it */
    public final Map<String, String> missing = new LinkedHashMap<String, String>();
    /** points this unit mapped itself (state/local-map.json "points"), not in HMG-API's map */
    public final java.util.Set<String> local = new java.util.TreeSet<String>();
    /** the owner's "Try unchecked maps" */
    public boolean testing;
    /** this unit's product and firmware */
    String product, firmware;
    /** why state/local-map.json isn't used, or null */
    public String localWhy;

    static final String LOCAL = "/data/hmgapi/state/local-map.json";

    /** the reasons a shipped map gives for a point that's only newer than the map (what a unit may map itself) */
    public static boolean olderMap(String why) {
        return why != null && (why.startsWith("newer than") || why.startsWith("not in the Car Report"));
    }

    /**
     * Points that only reads and mods' observe hooks may use here: those mapped on this
     * unit only, unless the owner tries unchecked maps.
     */
    public java.util.Set<String> localOnly() {
        return testing ? java.util.Collections.<String>emptySet() : local;
    }

    /** the catalogue app a point belongs to: its id's first part ("themesettings.split_items.make") */
    public static String appOfId(String id) {
        int dot = id.indexOf('.');
        return dot < 0 ? id : id.substring(0, dot);
    }

    /** Has this map every point of a catalogue app ("launcher", "gpm", "framework")? */
    public boolean complete(String app) {
        if (points.isEmpty()) return false;
        for (String id : missing.keySet()) if (appOfId(id).equals(app)) return false;
        return true;
    }

    /** The first point of an app this map lacks, and why (for "off" log lines), or null. */
    public String firstMissing(String app) {
        for (Map.Entry<String, String> e : missing.entrySet()) {
            if (appOfId(e.getKey()).equals(app)) return e.getKey() + " (" + e.getValue() + ")";
        }
        return null;
    }

    public static CarMap load(String root, SystemCheck.Facts facts, Map<String, Object> hmgapi) {
        return load(root, facts, hmgapi, true);
    }

    /** useLocal false: HMG-API's map alone, as if this unit had mapped nothing itself (the mapper's view) */
    @SuppressWarnings("unchecked")
    public static CarMap load(String root, SystemCheck.Facts facts, Map<String, Object> hmgapi, boolean useLocal) {
        CarMap m = new CarMap();
        File maps = new File(root + "/data/hmgapi/maps");
        String product = facts.get("product"), firmware = facts.get("firmware");
        m.product = product;
        m.firmware = firmware;
        m.testing = Boolean.TRUE.equals(hmgapi == null ? null : hmgapi.get("testing_maps"))
                || new File(root + "/data/hmgapi/settings/allow/testing_maps").isFile();   // the app's switch (hmgapid allow)
        Map<String, Object> local = null;
        if (!useLocal) {
            m.localWhy = "not used here";
        } else if (new File(root + "/data/hmgapi/settings/allow/no_local_points").isFile()) {
            m.localWhy = "the owner turned off maps made on this car";
        } else if (new File(root + LOCAL).isFile()) {
            try {
                local = Json.object(Engine.read(new File(root + LOCAL)));
            } catch (Exception e) {
                m.localWhy = "can't read " + LOCAL + ": " + e;
            }
        }
        try {
            File dir = find(maps, product, firmware, m);
            if (dir == null) {
                if (useWhole(root, m, local, product, firmware)) return finish(root, m, maps, local);
                m.reason = "no hook map for this car (" + product + "): hooks are off, everything else runs";
                return m;
            }
            m.folder = rel(maps, dir);
            m.carJson = Json.object(Engine.read(new File(dir, "car.json")));
            m.car = m.carJson.get("name") + " (" + m.carJson.get("region") + " " + m.carJson.get("firmware") + ")";
            for (Object app : (List<Object>) m.carJson.get("apps")) {
                Map<String, Object> entries = Json.object(Engine.read(new File(dir, app + ".json")));
                for (Map.Entry<String, Object> e : entries.entrySet()) {
                    m.points.put(e.getKey(), (Map<String, Object>) e.getValue());
                    m.appOf.put(e.getKey(), String.valueOf(app));
                }
            }
            return finish(root, m, maps, local);
        } catch (Exception e) {
            m.status = "none";
            m.reason = "can't read the hook map: " + e;
        }
        return m;
    }

    /** the rest of load, for HMG-API's map or the whole map made on this unit (already in m) */
    @SuppressWarnings("unchecked")
    static CarMap finish(String root, CarMap m, File maps, Map<String, Object> local) {
        try {
            // points the mapper didn't find, and points newer than the map
            Object listed = m.carJson.get("missing");
            if (listed instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) listed).entrySet()) {
                    m.missing.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            File list = new File(maps, "points.txt");
            if (list.isFile()) {
                for (String id : Engine.read(list).split("\n")) {
                    id = id.trim();
                    if (!id.isEmpty() && !m.points.containsKey(id) && !m.missing.containsKey(id)) {
                        m.missing.put(id, "newer than this car's map");
                    }
                }
            }
            // the stock files it was made from: an app that differs loses its points; the framework, everything
            Map<String, Object> files = (Map<String, Object>) m.carJson.get("files");
            Map<String, Object> appFiles = (Map<String, Object>) m.carJson.get("app_files");
            Map<String, Object> cache = md5Cache(root);
            for (Map.Entry<String, Object> f : files.entrySet()) {
                String want = String.valueOf(f.getValue());
                String live = md5Cached(root, f.getKey(), cache);
                String backup = md5(new File(root + "/data/hmgapi/stock" + f.getKey()));
                if (want.equals(live) || want.equals(backup)) continue;
                String why = f.getKey() + " is " + live + ", but the map for " + m.car + " was made from " + want;
                List<String> apps = appsUsing(appFiles, f.getKey());
                if (apps.isEmpty() || apps.contains("framework")) {
                    saveMd5Cache(root, cache);
                    if (!"local".equals(m.folder) && useWhole(root, m, local, m.product, m.firmware)) return finish(root, m, maps, local);
                    m.status = "mismatch";
                    m.reason = why + ": hooks are off (another build of the framework needs its own map: `hmgapid engine map-car --write` makes one on this car)";
                    return m;
                }
                for (String app : apps) drop(m, app, why);
            }
            saveMd5Cache(root, cache);
            m.status = String.valueOf(m.carJson.get("status"));
            m.checkedHere = checkedHere(root, m);
            if (!"local".equals(m.folder)) {
                try {
                    mergeLocal(root, m, local, cache);
                } catch (Exception e) {   // the map runs as HMG-API made it
                    m.localWhy = "can't use " + LOCAL + ": " + e;
                    for (String id : m.local) {
                        m.points.remove(id);
                        m.appOf.remove(id);
                    }
                    m.local.clear();
                }
            }
            boolean testing = m.testing;
            if ("verified".equals(m.status)) {
                m.runnable = true;
            } else if (("mapped".equals(m.status) || "probed".equals(m.status)) && (m.checkedHere || testing)) {
                m.runnable = true;
                m.reason = m.checkedHere ? "map checked on this car (not yet marked verified)"
                        : "map not checked on a car yet; running because hmgapi.json testing_maps is on";
            } else {
                m.reason = "the map for " + m.car + " isn't checked on a car yet: run `hmgapid engine verify-map`"
                        + " (or set hmgapi.json testing_maps to try it)";
            }
        } catch (Exception e) {
            m.status = "none";
            m.reason = "can't read the hook map: " + e;
        }
        return m;
    }

    /**
     * The whole map made on this unit (local-map.json "whole") in place of HMG-API's (none
     * for this car, or one that doesn't fit its framework): true if it's for this unit's
     * product and firmware.
     */
    @SuppressWarnings("unchecked")
    static boolean useWhole(String root, CarMap m, Map<String, Object> local, String product, String firmware) {
        if (local == null || !Boolean.TRUE.equals(local.get("whole"))) return false;
        if (product == null || firmware == null || !firmware.equals(String.valueOf(local.get("firmware")))) return false;
        boolean fits = false;
        Object ps = local.get("products");
        if (ps instanceof List) for (Object p : (List<Object>) ps) if (product.startsWith(String.valueOf(p))) fits = true;
        if (!fits) return false;
        m.points.clear();
        m.appOf.clear();
        m.missing.clear();
        m.folder = "local";
        m.carJson = local;
        m.car = local.get("name") + " (" + local.get("region") + " " + local.get("firmware") + ", mapped on this car)";
        for (Map.Entry<String, Object> e : ((Map<String, Object>) local.get("points")).entrySet()) {
            m.points.put(e.getKey(), (Map<String, Object>) e.getValue());
            m.appOf.put(e.getKey(), appOfId(e.getKey()));
        }
        return true;
    }

    /**
     * local-map.json "points" into HMG-API's map: each only where the map lacks it because
     * the map is older, and only while every stock file of its app is the one it was found
     * in (the md5s the unit saw then are the car's now).
     */
    @SuppressWarnings("unchecked")
    static void mergeLocal(String root, CarMap m, Map<String, Object> local, Map<String, Object> cache) {
        if (local == null || Boolean.TRUE.equals(local.get("whole")) || !(local.get("points") instanceof Map)) return;
        Map<String, Object> files = (Map<String, Object>) local.get("files");
        Map<String, Object> appFiles = (Map<String, Object>) m.carJson.get("app_files");
        for (Map.Entry<String, Object> e : ((Map<String, Object>) local.get("points")).entrySet()) {
            String id = e.getKey(), app = appOfId(id);
            if (m.points.containsKey(id) || !olderMap(m.missing.get(id))) continue;
            Object af = appFiles == null ? null : appFiles.get(app);
            Object fs = af instanceof Map ? ((Map<String, Object>) af).get("files") : null;
            if (!(fs instanceof List) || files == null) continue;
            boolean same = true;
            for (Object f : (List<Object>) fs) {
                String p = String.valueOf(f), live = md5Cached(root, p, cache), backup = md5(new File(root + "/data/hmgapi/stock" + p));
                Object seen = files.get(p);
                if (seen == null || !(seen.equals(live) || seen.equals(backup))) same = false;
            }
            if (!same) continue;
            m.points.put(id, (Map<String, Object>) e.getValue());
            m.appOf.put(id, app);
            m.missing.remove(id);
            m.local.add(id);
        }
    }

    @SuppressWarnings("unchecked")
    static File find(File maps, String product, String firmware, CarMap m) throws Exception {
        if (product == null || !maps.isDirectory()) return null;
        File best = null;
        int bestLen = -1;
        for (File make : list(maps)) for (File model : list(make)) for (File region : list(model)) for (File fw : list(region)) {
            File cj = new File(fw, "car.json");
            if (!cj.isFile()) continue;
            Map<String, Object> c = Json.object(Engine.read(cj));
            if (firmware == null || !firmware.equals(String.valueOf(c.get("firmware")))) continue;
            for (Object p : (List<Object>) c.get("products")) {
                String prefix = String.valueOf(p);
                if (product.startsWith(prefix) && prefix.length() > bestLen) {
                    best = fw;
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    static File[] list(File d) {
        File[] fs = d.listFiles();
        if (fs == null) return new File[0];
        List<File> dirs = new ArrayList<File>();
        for (File f : fs) if (f.isDirectory()) dirs.add(f);
        java.util.Collections.sort(dirs);
        return dirs.toArray(new File[0]);
    }

    static String rel(File base, File f) {
        return f.getPath().substring(base.getPath().length() + 1);
    }

    /** the catalogue apps whose names come from a stock file (car.json app_files) */
    @SuppressWarnings("unchecked")
    static List<String> appsUsing(Map<String, Object> appFiles, String file) {
        List<String> out = new ArrayList<String>();
        if (appFiles == null) return out;
        for (Map.Entry<String, Object> e : appFiles.entrySet()) {
            Object fs = e.getValue() instanceof Map ? ((Map<String, Object>) e.getValue()).get("files") : null;
            if (fs instanceof List && ((List<Object>) fs).contains(file)) out.add(e.getKey());
        }
        return out;
    }

    /** an app's points out of the map (its stock file differs): they become missing */
    static void drop(CarMap m, String app, String why) {
        for (java.util.Iterator<Map.Entry<String, String>> it = m.appOf.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            if (!e.getValue().equals(app)) continue;
            m.points.remove(e.getKey());
            m.missing.put(e.getKey(), why);
            it.remove();
        }
    }

    @SuppressWarnings("unchecked")
    static boolean checkedHere(String root, CarMap m) {
        try {
            File f = new File(root + "/data/hmgapi/state/map-check.json");
            if (!f.isFile()) return false;
            Map<String, Object> c = Json.object(Engine.read(f));
            return Boolean.TRUE.equals(c.get("ok")) && m.folder.equals(c.get("folder"))
                    && String.valueOf(m.carJson.get("files")).equals(String.valueOf(c.get("files")));
        } catch (Exception e) {
            return false;
        }
    }

    // ---- md5s, cached by size and time (framework.odex is big; this runs at every start)

    @SuppressWarnings("unchecked")
    static Map<String, Object> md5Cache(String root) {
        try {
            File f = new File(root + "/data/hmgapi/state/md5-cache.json");
            if (f.isFile()) return Json.object(Engine.read(f));
        } catch (Exception ignored) {
            // rebuilt below
        }
        return new LinkedHashMap<String, Object>();
    }

    static void saveMd5Cache(String root, Map<String, Object> cache) {
        try {
            File d = new File(root + "/data/hmgapi/state");
            if (d.isDirectory()) Engine.write(new File(d, "md5-cache.json"), Json.write(cache));
        } catch (Exception ignored) {
            // only a cache
        }
    }

    @SuppressWarnings("unchecked")
    static String md5Cached(String root, String path, Map<String, Object> cache) {
        File f = new File(root + path);
        if (!f.isFile()) return "absent";
        String key = f.length() + " " + f.lastModified();
        Object c = cache.get(path);
        if (c instanceof Map && key.equals(((Map<String, Object>) c).get("key"))) return String.valueOf(((Map<String, Object>) c).get("md5"));
        String m = md5(f);
        Map<String, Object> e = new LinkedHashMap<String, Object>();
        e.put("key", key);
        e.put("md5", m);
        cache.put(path, e);
        return m;
    }

    public static String md5(File f) {
        if (!f.isFile()) return "absent";
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            InputStream in = new FileInputStream(f);
            try {
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) d.update(b, 0, n);
            } finally {
                in.close();
            }
            StringBuilder s = new StringBuilder();
            for (byte x : d.digest()) s.append(String.format("%02x", x & 0xff));
            return s.toString();
        } catch (Exception e) {
            return "unreadable";
        }
    }

    /** One line for logs and the app. */
    public String describe() {
        return "hook map: " + status + (car == null ? "" : " for " + car) + (runnable ? ", hooks on" : ", hooks off")
                + (runnable && !local.isEmpty() ? ", " + local.size() + " point(s) mapped on this car" + (testing ? "" : " (reads and observe hooks)") : "")
                + (runnable && !missing.isEmpty() ? ", " + missing.size() + " point(s) not mapped (their features and mods are off)" : "")
                + (reason == null ? "" : " (" + reason + ")");
    }
}

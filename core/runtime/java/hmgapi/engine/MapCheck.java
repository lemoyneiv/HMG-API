package hmgapi.engine;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * engine verify-map: the on-car check of this car's hook map. Loads each mapped
 * app's own code (its stock APK and odex, with a class loader of its own: the app
 * doesn't start) and resolves every entry: the class, the method or constructor
 * with exactly those parameter and return types, the field with that type.
 * Writes state/map-check.json; a "mapped" map that passes runs on this car, and the
 * report is what marks the map "verified" in HMG-API's repo.
 */
final class MapCheck {
    private MapCheck() {}

    @SuppressWarnings("unchecked")
    static int run(Engine e) throws Exception {
        CarMap m = CarMap.load(e.root, e.facts, e.hmgapi);
        if (m.folder == null || "mismatch".equals(m.status) || m.points.isEmpty()) {
            e.out.add("can't check: " + m.reason);
            return 5;
        }
        if (e.host && System.getenv("HMGAPI_TEST_MAPCHECK") == null) {
            e.out.add("verify-map runs on the head unit only");
            return 2;
        }
        List<String> failed = new ArrayList<String>();
        Map<String, String> why = resolve(e, m, m.points.keySet());
        for (Map.Entry<String, String> f : why.entrySet()) failed.add(f.getKey() + ": " + f.getValue());
        int ok = m.points.size() - why.size();
        Map<String, Object> rep = new LinkedHashMap<String, Object>();
        rep.put("folder", m.folder);
        rep.put("files", m.carJson.get("files"));
        rep.put("ok", failed.isEmpty());
        rep.put("points", (long) m.points.size());
        rep.put("failed", new ArrayList<Object>(failed));
        rep.put("date", new java.util.Date().toString());
        rep.put("product", e.facts.get("product"));
        Engine.write(new File(e.stateDir, "map-check.json"), Json.write(rep));
        e.out.add("map " + m.folder + ": " + ok + " of " + m.points.size() + " hook points resolve in this car's apps");
        for (String f : failed) e.out.add("  FAILED " + f);
        e.out.add(failed.isEmpty() ? "map checked on this car: hooks run with it from the next start (report: state/map-check.json)"
                        + (m.missing.isEmpty() ? "" : "; " + m.missing.size() + " point(s) aren't in it, their features and mods stay off")
                : "the map doesn't fit this car; hooks stay off");
        return failed.isEmpty() ? 0 : 1;
    }

    /**
     * Resolves these points of m in this car's own apps (each app's stock APK in a class
     * loader of its own: the app doesn't start): id -> why it doesn't, for each that doesn't.
     * Off the unit (tests, HMGAPI_HOST) only with HMGAPI_TEST_MAPCHECK, a stand-in: "pass",
     * or "fail:<id>,<id>" for the ones that fail.
     */
    @SuppressWarnings("unchecked")
    static Map<String, String> resolve(Engine e, CarMap m, java.util.Collection<String> ids) throws Exception {
        Map<String, String> failed = new LinkedHashMap<String, String>();
        if (e.host) {
            String t = System.getenv("HMGAPI_TEST_MAPCHECK");
            for (String id : ids) {
                if (t == null) failed.put(id, "can't check off the head unit");
                else if (t.startsWith("fail:") && java.util.Arrays.asList(t.substring(5).split(",")).contains(id)) failed.put(id, "test: doesn't resolve");
            }
            return failed;
        }
        Map<String, Object> appFiles = (Map<String, Object>) m.carJson.get("app_files");
        Map<String, ClassLoader> loaders = new LinkedHashMap<String, ClassLoader>();
        ClassLoader boot = ClassLoader.getSystemClassLoader().getParent();
        Class<?> pcl = Class.forName("dalvik.system.PathClassLoader");
        Constructor<?> newPcl = pcl.getConstructor(String.class, ClassLoader.class);
        for (String id : ids) {
            String app = m.appOf.get(id);
            try {
                ClassLoader cl = loaders.get(app);
                if (cl == null) {
                    Map<String, Object> af = (Map<String, Object>) appFiles.get(app);
                    if (af == null || af.get("package") == null) {
                        // the framework: the boot classpath, plus the framework jars the map lists that aren't on it
                        // (com.hkmc.ext.hcan: the telematics CAN service; automotive-service: system_server's own loader)
                        cl = boot;
                        String bcp = ":" + System.getenv("BOOTCLASSPATH") + ":";
                        StringBuilder extra = new StringBuilder();
                        if (af != null) {
                            for (Object f : (List<Object>) af.get("files")) {
                                String jar = String.valueOf(f).replaceAll("\\.odex$", ".jar");
                                if (!bcp.contains(":" + jar + ":")) extra.append(extra.length() == 0 ? "" : ":").append(jar);
                            }
                        }
                        if (extra.length() > 0) cl = (ClassLoader) newPcl.newInstance(extra.toString(), boot);
                    } else {
                        // the app's APK, then the libraries it uses (uses-library: com.tcc.iap2, com.broadcom.bt), as
                        // its own class loader has them: classes in the app that build on a library resolve too
                        StringBuilder path = new StringBuilder();
                        for (Object f : (List<Object>) af.get("files")) {
                            String x = String.valueOf(f);
                            String file = x.startsWith("/system/app/") ? x.replaceAll("\\.odex$", ".apk") : x.replaceAll("\\.odex$", ".jar");
                            path.append(path.length() == 0 ? "" : ":").append(file);
                        }
                        cl = (ClassLoader) newPcl.newInstance(path.toString(), boot);
                    }
                    loaders.put(app, cl);
                }
                check(cl, m, id, m.points.get(id));
            } catch (Throwable t) {
                failed.put(id, String.valueOf(t));
            }
        }
        return failed;
    }

    static String owner(String id) {
        return id.substring(0, id.lastIndexOf('.'));
    }

    static void check(ClassLoader cl, CarMap m, String id, Map<String, Object> e) throws Exception {
        if (e.containsKey("class")) {
            Class.forName(String.valueOf(e.get("class")), false, cl);
            return;
        }
        Map<String, Object> oe = m.points.get(owner(id));
        if (oe == null || !oe.containsKey("class")) throw new IllegalStateException("its class point isn't in the map");
        Class<?> c = Class.forName(String.valueOf(oe.get("class")), false, cl);
        if (e.containsKey("method")) {
            Method x = c.getDeclaredMethod(String.valueOf(e.get("method")), types(cl, e.get("params")));
            if (x.getReturnType() != type(cl, String.valueOf(e.get("ret")))) throw new IllegalStateException("returns " + x.getReturnType());
            if (Boolean.TRUE.equals(e.get("static")) != Modifier.isStatic(x.getModifiers())) throw new IllegalStateException("static differs");
        } else if (e.containsKey("ctor")) {
            c.getDeclaredConstructor(types(cl, e.get("ctor")));
        } else if (e.containsKey("field")) {
            Field f = c.getDeclaredField(String.valueOf(e.get("field")));
            if (f.getType() != type(cl, String.valueOf(e.get("type")))) throw new IllegalStateException("is a " + f.getType());
            if (Boolean.TRUE.equals(e.get("static")) != Modifier.isStatic(f.getModifiers())) throw new IllegalStateException("static differs");
        } else {
            throw new IllegalStateException("unknown entry " + e);
        }
    }

    @SuppressWarnings("unchecked")
    static Class<?>[] types(ClassLoader cl, Object list) throws Exception {
        List<Object> l = (List<Object>) list;
        Class<?>[] t = new Class<?>[l == null ? 0 : l.size()];
        for (int i = 0; i < t.length; i++) t[i] = type(cl, String.valueOf(l.get(i)));
        return t;
    }

    static Class<?> type(ClassLoader cl, String n) throws Exception {
        if (n.endsWith("[]")) return java.lang.reflect.Array.newInstance(type(cl, n.substring(0, n.length() - 2)), 0).getClass();
        if ("int".equals(n)) return int.class;
        if ("boolean".equals(n)) return boolean.class;
        if ("long".equals(n)) return long.class;
        if ("byte".equals(n)) return byte.class;
        if ("short".equals(n)) return short.class;
        if ("char".equals(n)) return char.class;
        if ("float".equals(n)) return float.class;
        if ("double".equals(n)) return double.class;
        if ("void".equals(n)) return void.class;
        return Class.forName(n, false, cl);
    }
}

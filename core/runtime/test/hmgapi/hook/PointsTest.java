package hmgapi.hook;

import hmgapi.engine.CarMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Points with a point mapped on this unit only (CarMap.local): reads and mods' observe
 * hooks use it; HMG-API's own hooks (need/needAll), calls, constructors and mods' change or
 * replace hooks don't, unless the owner tries unchecked maps. Compiled with Points.java
 * alone and the stand-ins below (the real Bridge is native).
 */
public final class PointsTest {
    static int fail;
    static final List<String> log = new ArrayList<String>(), hooked = new ArrayList<String>();

    public static final class A {
        public static final int FLAG = 7;
        static int calls;

        public static void go() {
            calls++;
        }

        public A() {
        }
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static boolean refused(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return e.getMessage().contains("mapped on this car only");
        }
    }

    static Map<String, Object> entry(String... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) {
        CarMap m = new CarMap();
        m.runnable = true;
        m.points.put("test.a", entry("class", A.class.getName()));
        Map<String, Object> flag = entry("field", "FLAG", "type", "int");
        flag.put("static", true);
        m.points.put("test.a.flag", flag);
        Map<String, Object> go = entry("method", "go", "ret", "void");
        go.put("params", new ArrayList<Object>());
        go.put("static", true);
        m.points.put("test.a.go", go);
        Map<String, Object> ctor = new LinkedHashMap<String, Object>();
        ctor.put("ctor", new ArrayList<Object>());
        m.points.put("test.a.new", ctor);
        m.local.add("test.a.flag");
        m.local.add("test.a.go");
        m.local.add("test.a.new");
        Points.use(m);

        check("a point mapped on this car only: has() says it's there (mods and readings)", Points.has("test.a.go"));
        check("... complete() counts it (a mod's own check)", Points.complete("test"));
        check("... a field read works (readings)", Integer.valueOf(7).equals(Points.get(null, "test.a.flag", null)));
        check("... its class too", Points.cls(null, "test.a") == A.class);
        check("... a call: refused", refused(new Runnable() { public void run() { Points.call(null, "test.a.go", null); } }));
        check("... nothing was called", A.calls == 0);
        check("... a constructor: refused", refused(new Runnable() { public void run() { Points.newInstance(null, "test.a.new"); } }));
        check("... HMG-API's own hook: refused", refused(new Runnable() { public void run() { Points.hook(null, "test.a.go", new MethodHook() {}); } }));
        check("... a mod's change hook: refused", refused(new Runnable() { public void run() { Points.hookAs(null, "test.a.go", new MethodHook() {}, "change"); } }));
        check("... a mod's replace hook: refused", refused(new Runnable() { public void run() { Points.hookAs(null, "test.a.go", new MethodHook() {}, "replace"); } }));
        check("... nothing hooked so far", hooked.isEmpty());
        Points.hookAs(null, "test.a.go", new MethodHook() {}, "observe");
        check("... a mod's observe hook: hooked", hooked.size() == 1);
        check("needAll for HMG-API's own feature: off, and says why", !Points.needAll("Feature X", "test.a", "test.a.go")
                && log.get(log.size() - 1).contains("test.a.go is mapped on this car only"));
        check("need(app) for HMG-API's own feature: off", !Points.need("Feature Y", "test"));
        check("needAll on HMG-API's map's points only: on", Points.needAll("Feature Z", "test.a"));

        m.testing = true;   // the owner's "Try unchecked maps"
        Points.call(null, "test.a.go", null);
        check("with \"Try unchecked maps\": a call works", A.calls == 1);
        check("... a constructor too", Points.newInstance(null, "test.a.new") instanceof A);
        Points.hook(null, "test.a.go", new MethodHook() {});
        Points.hookAs(null, "test.a.go", new MethodHook() {}, "replace");
        check("... HMG-API's own hook and a mod's replace hook too", hooked.size() == 3);
        check("... and need/needAll say on", Points.need("Feature Y", "test") && Points.needAll("Feature X", "test.a.go"));
        System.exit(fail);
    }
}

// stand-ins for what Points uses from the hook runtime (the real ones need the head unit)
abstract class MethodHook {
}

final class Bridge {
    static void hookMethod(java.lang.reflect.Member m, MethodHook h) {
        PointsTest.hooked.add(m.getName());
    }
}

final class Hooks {
    static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl != null ? cl : PointsTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}

final class HmgApi {
    static void i(String msg) {
        PointsTest.log.add(msg);
    }
}

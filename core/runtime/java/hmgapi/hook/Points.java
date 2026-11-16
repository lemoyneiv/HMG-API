package hmgapi.hook;

import hmgapi.engine.CarMap;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * HMG-API's hook points, by their stable names (core/hookpoints): what mods and
 * HMG-API itself hook and call, instead of an app's own obfuscated names. This
 * car's map says what each name is here; HMG-API only runs with a good map.
 *
 *   Points.hook(app.classLoader, "launcher.menu_list.load", new MethodHook() { ... });
 *   Points.call(cl, "btsettings.projection_helper.open_other_page", null, context);
 *   Points.get(cl, "launcher.menu_list.items", menuList);
 *
 * Pass the app's class loader (LoadedApp.classLoader), or null for framework points.
 * A name this car's map doesn't have throws IllegalArgumentException: a map may
 * lack points (CarMap.missing), so code that hooks several points of an app first
 * checks complete(app) and stays off as a whole, never half hooked.
 *
 * Points this unit mapped itself (CarMap.local, not in HMG-API's map) are for reading
 * (has, get, cls, readings) and mods' observe hooks. HMG-API's own hooks (need/needAll),
 * calls and constructors use them only when the owner tries unchecked maps
 * (CarMap.localOnly); until then they count as not mapped there.
 */
public final class Points {
    private Points() {}

    static CarMap map;

    /** Outside the zygote (HMG-API's own services): this car's map, loaded by the caller. */
    public static void use(CarMap m) {
        map = m;
    }

    /** Is HMG-API running on this car (a good hook map)? */
    public static boolean ready() {
        return map != null && map.runnable;
    }

    public static boolean has(String id) {
        return map != null && map.points.containsKey(id);
    }

    /** Has this car's map every point of a catalogue app ("launcher", "gpm")? */
    public static boolean complete(String app) {
        return map != null && map.complete(app);
    }

    /**
     * For a feature built on a few points (framework ones, shared by many features):
     * true if they're all mapped; otherwise false and one line saying why it's off.
     */
    static boolean needAll(String feature, String... ids) {
        for (String id : ids) {
            if (localOnly(id)) {
                HmgApi.i(feature + ": off on this car (" + id + " is mapped on this car only: HMG-API's hooks need it in HMG-API's map, or \"Try unchecked maps\")");
                return false;
            }
            if (!has(id)) {
                HmgApi.i(feature + ": off on this car (" + id + " not mapped"
                        + (map != null && map.missing.containsKey(id) ? ": " + map.missing.get(id) : "") + ")");
                return false;
            }
        }
        return true;
    }

    /**
     * For a feature built on one app's points: true if they're all mapped; otherwise
     * false and one line saying the feature is off here, and why.
     */
    static boolean need(String feature, String app) {
        if (complete(app)) {
            for (String id : map.localOnly()) {
                if (CarMap.appOfId(id).equals(app)) {
                    HmgApi.i(feature + ": off on this car (" + id + " is mapped on this car only: HMG-API's hooks need it in HMG-API's map, or \"Try unchecked maps\")");
                    return false;
                }
            }
            return true;
        }
        HmgApi.i(feature + ": off on this car (" + app + " point " + (map == null ? "map" : map.firstMissing(app)) + " not mapped)");
        return false;
    }

    /** mapped on this unit only, and the owner doesn't try unchecked maps: reads and observe hooks only */
    static boolean localOnly(String id) {
        return map != null && map.localOnly().contains(id);
    }

    static void notLocal(String id, String what) {
        if (localOnly(id)) throw new IllegalArgumentException(id + " is mapped on this car only: " + what + " needs it in HMG-API's map, or the owner's \"Try unchecked maps\"");
    }

    static Map<String, Object> entry(String id) {
        Map<String, Object> e = map == null ? null : map.points.get(id);
        if (e == null) throw new IllegalArgumentException("no hook point " + id + " in this car's map");
        return e;
    }

    static String owner(String id) {
        int dot = id.lastIndexOf('.');
        return id.substring(0, dot);   // "launcher.menu_list.load" -> "launcher.menu_list"
    }

    public static Class<?> cls(ClassLoader cl, String id) {
        return Hooks.findClass(String.valueOf(entry(id).get("class")), cl);
    }

    @SuppressWarnings("unchecked")
    static Class<?>[] types(ClassLoader cl, Object list) {
        List<Object> l = (List<Object>) list;
        Class<?>[] t = new Class<?>[l == null ? 0 : l.size()];
        for (int i = 0; i < t.length; i++) t[i] = type(cl, String.valueOf(l.get(i)));
        return t;
    }

    static Class<?> type(ClassLoader cl, String name) {
        if (name.endsWith("[]")) return java.lang.reflect.Array.newInstance(type(cl, name.substring(0, name.length() - 2)), 0).getClass();
        if ("int".equals(name)) return int.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("long".equals(name)) return long.class;
        if ("byte".equals(name)) return byte.class;
        if ("short".equals(name)) return short.class;
        if ("char".equals(name)) return char.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("void".equals(name)) return void.class;
        return Hooks.findClass(name, cl);
    }

    public static Method method(ClassLoader cl, String id) {
        Map<String, Object> e = entry(id);
        Class<?> c = cls(cl, owner(id));
        try {
            Method m = c.getDeclaredMethod(String.valueOf(e.get("method")), types(cl, e.get("params")));
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException x) {
            throw new IllegalArgumentException(id + ": " + c.getName() + "." + e.get("method") + " isn't there", x);
        }
    }

    public static Constructor<?> ctor(ClassLoader cl, String id) {
        Map<String, Object> e = entry(id);
        Class<?> c = cls(cl, owner(id));
        try {
            Constructor<?> k = c.getDeclaredConstructor(types(cl, e.get("ctor")));
            k.setAccessible(true);
            return k;
        } catch (NoSuchMethodException x) {
            throw new IllegalArgumentException(id + ": no such constructor in " + c.getName(), x);
        }
    }

    public static Field field(ClassLoader cl, String id) {
        Map<String, Object> e = entry(id);
        Class<?> c = cls(cl, owner(id));
        try {
            Field f = c.getDeclaredField(String.valueOf(e.get("field")));
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException x) {
            throw new IllegalArgumentException(id + ": " + c.getName() + "." + e.get("field") + " isn't there", x);
        }
    }

    /** Hooks a method or constructor point: HMG-API core only (mods hook through ModContext.hook, which checks their declaration). */
    static java.lang.reflect.Member hook(ClassLoader cl, String id, MethodHook hook) {
        notLocal(id, "HMG-API's own hook");
        return hookAny(cl, id, hook);
    }

    /** a mod's hook (ModContext.hook): an observe hook may use a point mapped on this car only */
    static java.lang.reflect.Member hookAs(ClassLoader cl, String id, MethodHook hook, String mode) {
        if (!"observe".equals(mode)) notLocal(id, "a " + mode + " hook");
        return hookAny(cl, id, hook);
    }

    private static java.lang.reflect.Member hookAny(ClassLoader cl, String id, MethodHook hook) {
        java.lang.reflect.Member m = entry(id).containsKey("ctor") ? ctor(cl, id) : method(cl, id);
        Bridge.hookMethod(m, hook);
        return m;
    }

    /** Calls a method point (target null for a static one). */
    public static Object call(ClassLoader cl, String id, Object target, Object... args) {
        notLocal(id, "a call");
        try {
            return method(cl, id).invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(id + " threw", e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(id, e);
        }
    }

    /** Reads a field point (target null for a static one). */
    public static Object get(ClassLoader cl, String id, Object target) {
        try {
            return field(cl, id).get(target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(id, e);
        }
    }

    public static Object newInstance(ClassLoader cl, String id, Object... args) {
        notLocal(id, "a constructor call");
        try {
            return ctor(cl, id).newInstance(args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(id + " threw", e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException(id, e);
        }
    }
}

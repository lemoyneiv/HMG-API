package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a mod runs besides hooks, from its mod.json, so a project that is a NEMESIS
 * package today (its own APK, a daemon, a USB folder) moves over without changing
 * its code:
 *
 *   "apps":     [{"apk": "app/Dashcam.apk", "package": "dev.example.dashcam"}]
 *   "services": [{"name": "recorder", "jar": "dashcam.jar", "class": "dev.example.Main",
 *                 "args": ["--quiet"], "user": "root|system|app|app:<package>",
 *                 "start": "boot|android|manual", "restart": true},
 *                {"name": "watch", "script": "svc/watch.sh", ...}]
 *   "usb":      ["Dashcam"]
 *   "vehicle":  {"read": ["speed", "gear.park"], "commands": ["charge_door"]}
 *   "not_while_driving": ["dev.example.player"]
 *   "provides": {"panel": [{"title": "Battery", "widget": "dev.example.cam/.BatteryWidget"}]}
 *   "input":    [{"key": "map", "press": "x3", "broadcast": "dev.example.cam.SAVE"}]
 *   "projection": {"aa_density": 200, "aa_sensors": {"remove": ["speed"]}, "events": true}
 *   "network":  {"rules": [{"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [7000], "action": "accept"}]}
 *
 * apps: installed, turned off and removed with the mod (ModApps, `engine apps`).
 * services: run and watched by hmgapid (state/services.txt): "jar" runs app_process
 * with the jar (or an APK) as CLASSPATH, "script" runs sh; "user" app means the mod's
 * first app's uid. start "android" waits for sys.boot_completed; "manual" runs only
 * when asked (service-start, e.g. from the mod's app), once Android is up. restart (default
 * true): started again when it exits. usb: folders on a stick the mod's app and
 * services can ask about (hmgapid `usb`), and that the launcher announces
 * (hmgapi.intent.action.USB_FOLDER to the mod's apps). vehicle read: the readings
 * (Vehicle.READINGS) the mod's app and services may ask hmgapid for; while any
 * enabled mod has some, hmgapid runs HMG-API's vehicle service. not_while_driving:
 * the mod's own apps that follow the car's driving restriction (hook.Driving).
 * provides.panel: app widgets (AppWidgetProvider) of the mod's own apps, shown in
 * the right-hand split-screen panel (hook.Panel). input: button bindings (Keys),
 * delivered by the runtime in system_server (hook.Input) to the mod's own apps.
 * network: firewall rules, routes and network settings, kept in place by HMG-API's
 * network keeper (Network).
 *
 * LiveState.publish writes the state files for the enabled mods only, so turning a
 * mod off stops its services and takes its app's access away at once.
 */
final class ModRuntime {
    private ModRuntime() {}

    static final class App {
        String mod, apk, pkg;
    }

    static final class Service {
        String mod, name, user, start, kind, path, cls;
        boolean restart;
        List<String> args = new ArrayList<String>();

        /** hmgapid's line: <mod> <name> <user> <start> <restart> <kind> <path> <class|-> [args...] */
        String line() {
            StringBuilder b = new StringBuilder();
            b.append(mod).append(' ').append(name).append(' ').append(user).append(' ').append(start).append(' ')
                    .append(restart ? "yes" : "no").append(' ').append(kind).append(' ').append(path).append(' ')
                    .append(cls == null ? "-" : cls);
            for (String a : args) b.append(' ').append(a);
            return b.toString();
        }
    }

    /** Everything one mod declares; errors are what's wrong with its mod.json (those entries are left out). */
    static final class Decl {
        final List<App> apps = new ArrayList<App>();
        final List<Service> services = new ArrayList<Service>();
        final List<String> usb = new ArrayList<String>();
        final List<String> readings = new ArrayList<String>();
        final List<String> commands = new ArrayList<String>();
        final List<String> notWhileDriving = new ArrayList<String>();
        final List<Map<String, Object>> panel = new ArrayList<Map<String, Object>>();
        final List<Keys.Binding> input = new ArrayList<Keys.Binding>();
        boolean ble;
        Projection.Ask projection;
        Network.Ask network;
        final List<String> errors = new ArrayList<String>();
    }

    static final String PKG = "[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+";
    static final String REL = "[A-Za-z0-9_][A-Za-z0-9._/-]*";
    static final String ARG = "[A-Za-z0-9_.,:=@%+/-]+";

    @SuppressWarnings("unchecked")
    static Decl of(String id, Map<String, Object> mod, File dir) {
        Decl d = new Decl();
        Object apps = mod.get("apps");
        if (apps instanceof List) {
            for (Object o : (List<Object>) apps) {
                Map<String, Object> a = o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
                App x = new App();
                x.mod = id;
                x.apk = str(a.get("apk"));
                x.pkg = str(a.get("package"));
                if (x.pkg == null || !x.pkg.matches(PKG)) d.errors.add(id + ": app: \"package\" must be an Android package name");
                else if (x.pkg.equals("hmgapi.app")) d.errors.add(id + ": app: hmgapi.app is HMG-API's own");
                else if (x.apk == null || !x.apk.matches(REL) || x.apk.contains("..")) d.errors.add(id + ": app " + x.pkg + ": \"apk\" must be a file in the mod");
                else if (dir != null && !new File(dir, x.apk).isFile()) d.errors.add(id + ": app " + x.pkg + ": no " + x.apk + " in the mod");
                else d.apps.add(x);
            }
        }
        Object svcs = mod.get("services");
        if (svcs instanceof List) {
            for (Object o : (List<Object>) svcs) {
                Map<String, Object> s = o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
                Service v = new Service();
                v.mod = id;
                v.name = str(s.get("name"));
                String what = id + ": service " + v.name;
                if (v.name == null || !v.name.matches("[a-z0-9_-]{1,32}")) {
                    d.errors.add(id + ": service: \"name\" must be 1-32 of a-z 0-9 _ -");
                    continue;
                }
                String jar = str(s.get("jar")), script = str(s.get("script"));
                v.kind = jar != null ? "jar" : "script";
                v.path = jar != null ? jar : script;
                v.cls = str(s.get("class"));
                if ((jar == null) == (script == null)) {
                    d.errors.add(what + ": needs \"jar\" (with \"class\") or \"script\"");
                    continue;
                }
                if (!v.path.matches(REL) || v.path.contains("..")) {
                    d.errors.add(what + ": " + v.path + " must be a file in the mod");
                    continue;
                }
                if (dir != null && !new File(dir, v.path).isFile()) {
                    d.errors.add(what + ": no " + v.path + " in the mod");
                    continue;
                }
                if (jar != null && (v.cls == null || !v.cls.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))) {
                    d.errors.add(what + ": a jar service needs its main \"class\"");
                    continue;
                }
                if (jar == null) v.cls = null;
                String user = s.containsKey("user") ? str(s.get("user")) : "root";
                if ("app".equals(user)) {
                    String first = null;
                    if (apps instanceof List && !((List<Object>) apps).isEmpty() && ((List<Object>) apps).get(0) instanceof Map) {
                        first = str(((Map<String, Object>) ((List<Object>) apps).get(0)).get("package"));
                    }
                    if (first == null) {
                        d.errors.add(what + ": user \"app\" needs an app in \"apps\"");
                        continue;
                    }
                    user = "app:" + first;
                }
                if (!("root".equals(user) || "system".equals(user) || (user != null && user.startsWith("app:") && user.substring(4).matches(PKG)))) {
                    d.errors.add(what + ": user must be root, system, app or app:<package>");
                    continue;
                }
                v.user = user;
                v.start = s.containsKey("start") ? str(s.get("start")) : "android";
                if (!"boot".equals(v.start) && !"android".equals(v.start) && !"manual".equals(v.start)) {
                    d.errors.add(what + ": start must be boot, android or manual");
                    continue;
                }
                v.restart = !Boolean.FALSE.equals(s.get("restart"));
                Object args = s.get("args");
                boolean bad = false;
                if (args instanceof List) {
                    for (Object a : (List<Object>) args) {
                        String as = String.valueOf(a);
                        if (!as.matches(ARG)) bad = true;
                        v.args.add(as);
                    }
                }
                if (bad || v.args.size() > 12) {
                    d.errors.add(what + ": args may be at most 12 words of letters, digits and _.,:=@%+/-");
                    continue;
                }
                d.services.add(v);
            }
        }
        Object usb = mod.get("usb");
        if (usb instanceof List) {
            for (Object o : (List<Object>) usb) {
                String f = String.valueOf(o);
                if (!f.matches("[A-Za-z0-9_][A-Za-z0-9 ._-]{0,63}") || f.contains(" ")) d.errors.add(id + ": usb folder \"" + f + "\": one folder name, no spaces");
                else d.usb.add(f);
            }
        }
        Object ble = mod.get("ble");
        if (ble != null) {
            Map<String, Object> bm = ble instanceof Map ? (Map<String, Object>) ble : null;
            Object central = bm == null ? null : bm.get("central");
            if (bm == null || bm.size() != 1 || !(central instanceof Boolean)) d.errors.add(id + ": ble: needs {\"central\": true}");
            else d.ble = Boolean.TRUE.equals(central);
        }
        Object veh = mod.get("vehicle");
        Object reads = veh instanceof Map ? ((Map<String, Object>) veh).get("read") : null;
        Object cmds = veh instanceof Map ? ((Map<String, Object>) veh).get("commands") : null;
        if (veh != null && !(reads instanceof List) && !(cmds instanceof List)) d.errors.add(id + ": vehicle: needs \"read\" and/or \"commands\": [<names>]");
        if (cmds instanceof List) {
            for (Object o : (List<Object>) cmds) {
                String c = String.valueOf(o);
                if (!Vehicle.COMMANDS.containsKey(c)) d.errors.add(id + ": vehicle command \"" + c + "\" isn't one HMG-API offers");
                else if (!d.commands.contains(c)) d.commands.add(c);
            }
        }
        if (reads instanceof List) {
            for (Object o : (List<Object>) reads) {
                String r = String.valueOf(o);
                if (!Vehicle.READINGS.containsKey(r)) d.errors.add(id + ": vehicle reading \"" + r + "\" isn't one HMG-API offers");
                else if (!d.readings.contains(r)) d.readings.add(r);
            }
        }
        Object nwd = mod.get("not_while_driving");
        if (nwd instanceof List) {
            for (Object o : (List<Object>) nwd) {
                String p = String.valueOf(o);
                boolean own = false;
                for (App a : d.apps) own |= a.pkg.equals(p);
                if (!own) d.errors.add(id + ": not_while_driving: " + p + " isn't one of the mod's apps");
                else d.notWhileDriving.add(p);
            }
        }
        Object prov = mod.get("provides");
        Object pan = prov instanceof Map ? ((Map<String, Object>) prov).get("panel") : null;
        if (pan instanceof List) {
            for (Object o : (List<Object>) pan) {
                Map<String, Object> p = o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
                String w = str(p.get("widget")), title = str(p.get("title"));
                String pkg = w == null || w.indexOf('/') < 0 ? null : w.substring(0, w.indexOf('/'));
                String cls = pkg == null ? null : w.substring(w.indexOf('/') + 1);
                boolean own = false;
                for (App a : d.apps) own |= a.pkg.equals(pkg);
                if (cls == null || !cls.matches("\\.?[A-Za-z_$][A-Za-z0-9_$.]*")) d.errors.add(id + ": panel: \"widget\" is <package>/<widget provider class>");
                else if (!own) d.errors.add(id + ": panel: " + pkg + " isn't one of the mod's apps");
                else if (title == null || title.trim().isEmpty() || title.length() > 40) d.errors.add(id + ": panel " + w + ": needs a \"title\" (up to 40 characters)");
                else {
                    Map<String, Object> e = new LinkedHashMap<String, Object>();
                    e.put("title", title.trim());
                    e.put("widget", w);
                    d.panel.add(e);
                }
            }
        }
        d.projection = Projection.of(id, mod.get("projection"), d.errors);
        if (d.projection != null && d.projection.events && d.apps.isEmpty()) d.errors.add(id + ": projection events: the mod has no app to tell (\"apps\")");
        d.network = Network.of(id, mod.get("network"), d.errors);
        Object in = mod.get("input");
        if (in instanceof List) {
            for (Object o : (List<Object>) in) {
                Map<String, Object> b = o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>();
                Keys.Binding k = new Keys.Binding();
                k.mod = id;
                k.key = str(b.get("key"));
                k.press = b.containsKey("press") ? str(b.get("press")) : "short";
                k.stock = b.containsKey("stock") ? str(b.get("stock")) : "hold".equals(k.press) || b.containsKey("send") ? "replace" : "keep";
                k.broadcast = str(b.get("broadcast"));
                k.open = str(b.get("open"));
                k.when = str(b.get("when"));
                k.send = str(b.get("send"));
                k.release = str(b.get("release"));
                String what = id + ": input " + k.key + " " + k.press;
                for (App a : d.apps) k.packages.add(a.pkg);
                String openPkg = k.open == null || k.open.indexOf('/') < 0 ? null : k.open.substring(0, k.open.indexOf('/'));
                if (!Keys.KEYS.containsKey(k.key)) d.errors.add(id + ": input: key \"" + k.key + "\" isn't one HMG-API knows (docs: buttons)");
                else if (k.press.matches("x[0-9]+")) d.errors.add(what + ": multi-press isn't offered (it would delay every press of the key, or the car would act on each); use hold, or an entry in the car's star-key menu");
                else if (!Keys.PRESSES.contains(k.press)) d.errors.add(what + ": press must be short, hold or down");
                else if ("hold".equals(k.press) && "keep".equals(k.stock)) d.errors.add(what + ": hold always takes the car's hold (a short press still reaches the car); leave out \"stock\"");
                else if (!"keep".equals(k.stock) && !"replace".equals(k.stock)) d.errors.add(what + ": stock must be keep or replace");
                else if ("replace".equals(k.stock) && Keys.KEEP_ONLY.contains(k.key)) d.errors.add(what + ": " + k.key + " stays the car's (it can be watched, stock keep)");
                else if ("replace".equals(k.stock) && "down".equals(k.press)) d.errors.add(what + ": \"down\" can only watch (stock keep)");
                else if (k.broadcast == null && k.open == null && k.send == null) d.errors.add(what + ": needs \"broadcast\" (an action for the mod's apps), \"open\" (one of its activities) or \"send\" (a press for the car)");
                else if (k.send != null && sendBad(k.send) != null) d.errors.add(what + ": send " + sendBad(k.send));
                else if (k.release != null && !"hold".equals(k.press)) d.errors.add(what + ": \"release\" is for hold bindings (it's sent when the hold ends)");
                else if (k.release != null && sendBad(k.release) != null) d.errors.add(what + ": release " + sendBad(k.release));
                else if (k.send != null && "keep".equals(k.stock)) d.errors.add(what + ": a binding that sends a press takes this one from the car (\"stock\": \"replace\")");
                else if ((k.broadcast != null || k.open != null) && k.packages.isEmpty()) d.errors.add(what + ": the mod has no app to tell (\"apps\")");
                else if (k.broadcast != null && !k.broadcast.matches("[A-Za-z0-9_.]{1,100}")) d.errors.add(what + ": broadcast must be an action name");
                else if (k.open != null && (openPkg == null || !k.packages.contains(openPkg))) d.errors.add(what + ": open must be <one of the mod's apps>/<activity>");
                else if (k.when != null && !k.when.matches("!?settings\\.[A-Za-z0-9_]+(=[A-Za-z0-9_.-]+)?")) d.errors.add(what + ": when must be settings.<key>, !settings.<key> or settings.<key>=<value>");
                else d.input.add(k);
            }
        }
        return d;
    }

    /** every enabled mod's bindings, settled (Keys.settle); notes: what the owner should see */
    static List<Keys.Binding> bindings(Engine e, Map<String, Map<String, Object>> json, Set<String> on, List<String> notes) {
        List<Keys.Binding> all = new ArrayList<Keys.Binding>();
        for (String id : on) all.addAll(active(e, id, of(id, json.get(id), null).input));
        Object ov = e.hmgapi == null ? null : e.hmgapi.get("overrides");
        return Keys.settle(all, ov instanceof Map ? (Map<String, Object>) ov : null, notes);
    }

    /** why "<key> <press>" can't be sent to the car, or null */
    static String sendBad(String s) {
        String[] w = s.trim().split(" +");
        if (w.length != 2 || !Keys.KEYS.containsKey(w[0])) return "must be \"<key> <short|hold>\" with a key HMG-API knows";
        if (!"short".equals(w[1]) && !"hold".equals(w[1])) return "press must be short or hold";
        if (Keys.KEEP_ONLY.contains(w[0])) return w[0] + " is never sent by a mod";
        return null;
    }

    /** the bindings whose "when" holds with the mod's settings */
    static List<Keys.Binding> active(Engine e, String id, List<Keys.Binding> in) {
        Map<String, Object> s;
        try {
            s = Engine.settingsOf(new File(e.data, "mods/" + id), e.data, id);
        } catch (Exception x) {
            s = new LinkedHashMap<String, Object>();
        }
        List<Keys.Binding> out = new ArrayList<Keys.Binding>();
        for (Keys.Binding b : in) if (Engine.when(b.when, s)) out.add(b);
        return out;
    }

    /** every enabled mod's projection asks, settled (Projection.settle) */
    @SuppressWarnings("unchecked")
    static Map<String, Object> projection(Engine e, Map<String, Map<String, Object>> json, Set<String> on, List<String> notes) {
        List<Projection.Ask> asks = new ArrayList<Projection.Ask>();
        Map<String, List<String>> pkgs = new LinkedHashMap<String, List<String>>();
        for (String id : on) {
            Decl d = of(id, json.get(id), null);
            if (d.projection == null) continue;
            asks.add(d.projection);
            List<String> p = new ArrayList<String>();
            for (App a : d.apps) p.add(a.pkg);
            pkgs.put(id, p);
        }
        Object ov = e.hmgapi == null ? null : e.hmgapi.get("overrides");
        return Projection.settle(asks, pkgs, ov instanceof Map ? (Map<String, Object>) ov : null, notes);
    }

    /** HMG-API's own vehicle service (hmgapi.vehicle.Hub), run by hmgapid while a mod reads the car */
    static final String VEHICLE_SERVICE = "hmgapi vehicle root android yes jar /system/framework/hmgapi-hook.jar hmgapi.vehicle.Hub";
    /** HMG-API's log keeper (hmgapi.engine.LogKeeper): always on, logs/hmgapi/log.log for the app's Logs and the Car Report */
    static final String LOG_SERVICE = "hmgapi log root boot yes jar /system/framework/hmgapi-hook.jar hmgapi.engine.LogKeeper";

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** state/apps.txt, services.txt, usb.txt for hmgapid; the service files made readable by their user. */
    static void publish(Engine e, Map<String, Map<String, Object>> json, Set<String> on) throws Exception {
        StringBuilder apps = new StringBuilder(), svcs = new StringBuilder(), usb = new StringBuilder(), veh = new StringBuilder(), cmd = new StringBuilder();
        Map<String, String> owner = new LinkedHashMap<String, String>();
        for (String id : on) {
            File dir = new File(e.data, "mods/" + id);
            Decl d = of(id, json.get(id), dir);
            for (String err : d.errors) e.out.add("left out: " + err);
            for (App a : d.apps) {
                if (owner.containsKey(a.pkg)) {
                    e.out.add("left out: " + id + ": app " + a.pkg + " is already " + owner.get(a.pkg) + "'s");
                    continue;
                }
                owner.put(a.pkg, id);
                apps.append(a.pkg).append(' ').append(id).append('\n');
            }
            for (Service s : d.services) {
                svcs.append(s.line()).append('\n');
                if (!"root".equals(s.user)) readable(dir, new File(dir, s.path));
            }
            for (String f : d.usb) usb.append(id).append(' ').append(f).append('\n');
            for (String r : d.readings) veh.append(id).append(' ').append(r).append('\n');
            for (String c : d.commands) cmd.append(id).append(' ').append(c).append('\n');
        }
        boolean watch = new File(e.stateDir, "micom-watch.txt").isFile();   // the owner's MICOM recorder (hmgapid micom-watch)
        if (veh.length() > 0 || cmd.length() > 0 || watch) svcs.append(VEHICLE_SERVICE).append('\n');
        svcs.append(LOG_SERVICE).append('\n');
        e.stateDir.mkdirs();
        String net = Network.publish(e, json, on);   // the network keeper, while a mod has network asks (or some are still in)
        if (net != null) svcs.append(net).append('\n');
        writeIfChanged(new File(e.stateDir, "apps.txt"), apps.toString());
        writeIfChanged(new File(e.stateDir, "services.txt"), svcs.toString());
        writeIfChanged(new File(e.stateDir, "usb.txt"), usb.toString());
        writeIfChanged(new File(e.stateDir, "vehicle.txt"), veh.toString());
        writeIfChanged(new File(e.stateDir, "commands.txt"), cmd.toString());
    }

    /** what the plan shows the owner: each mod's apps, services and USB folders */
    static List<String> describe(Engine e, Map<String, Map<String, Object>> json, Set<String> on) {
        List<String> out = new ArrayList<String>();
        for (String id : on) {
            Decl d = of(id, json.get(id), new File(e.data, "mods/" + id));
            for (App a : d.apps) out.add("app      " + a.pkg + " (" + a.apk + ") [" + id + "]");
            for (Service s : d.services) {
                out.add("service  " + id + "/" + s.name + " as " + s.user + ", at " + s.start
                        + (s.restart ? ", restarted if it stops" : "") + " [" + id + "]");
            }
            for (String f : d.usb) out.add("usb      " + f + "/ on a stick [" + id + "]");
            if (d.ble) out.add("ble      BLE scanner and GATT central [" + id + "]");
            if (!d.readings.isEmpty()) out.add("vehicle  reads " + join(d.readings) + " [" + id + "]");
            for (String c : d.commands) out.add("vehicle  may " + c + " (" + Vehicle.COMMANDS.get(c).guard + ") [" + id + "]");
            for (String p : d.notWhileDriving) out.add("driving  " + p + " closes while the car's driving restriction is on [" + id + "]");
            for (Map<String, Object> p : d.panel) out.add("panel    \"" + p.get("title") + "\" (" + p.get("widget") + ") in the side panel [" + id + "]");
            for (String l : Projection.describe(d.projection)) out.add(l);
            for (String l : Network.describe(id, d.network, allowNetwork(e, id))) out.add(l);
            for (Keys.Binding b : active(e, id, d.input)) {
                out.add("button   " + b.key + " " + b.press + (b.stock.equals("replace") ? " (instead of the car's)" : "") + " -> "
                        + (b.send != null ? "the car gets " + b.send + (b.release != null ? ", then " + b.release + " when let go" : "")
                                + (b.open != null || b.broadcast != null ? "; " : "") : "")
                        + (b.open != null ? "opens " + b.open : b.broadcast != null ? "tells its app " + b.broadcast : "") + " [" + id + "]");
            }
            for (String err : d.errors) out.add("problem  " + err);
        }
        List<String> notes = new ArrayList<String>();
        bindings(e, json, on, notes);
        projection(e, json, on, notes);
        for (String n : notes) out.add("problem  " + n);
        for (String n : Network.settle(e, json, on).notes) out.add(n.startsWith("WARNING") ? n : "problem  " + n);
        return out;
    }

    @SuppressWarnings("unchecked")
    static boolean allowNetwork(Engine e, String id) {
        Object ov = e.hmgapi == null ? null : e.hmgapi.get("overrides");
        Object l = ov instanceof Map ? ((Map<String, Object>) ov).get("allow_network") : null;
        return l instanceof List && ((List<Object>) l).contains(id);
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) b.append(b.length() == 0 ? "" : ", ").append(s);
        return b.toString();
    }

    static void readable(File modDir, File f) {
        for (File d = f.getParentFile(); d != null && !d.equals(modDir); d = d.getParentFile()) d.setExecutable(true, false);
        modDir.setExecutable(true, false);
        f.setReadable(true, false);
    }

    static void writeIfChanged(File f, String s) throws Exception {
        if (!(f.isFile() && Engine.read(f).equals(s))) Engine.write(f, s);
    }
}

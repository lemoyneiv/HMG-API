package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Maps HMG-API's hook points on a car from its own files: tools/map-car.py's rules
 * (core/hookpoints: classes, members and their shapes, strings in their code), on
 * the dex in each stock .odex (Dex) instead of baksmali's output, so it runs on the
 * head unit itself (`engine map-car`) and off-car on a stock tree (tests: it must
 * find what map-car.py finds).
 *
 * Points described by a regex over smali ("pattern", "code") can't be matched from
 * the dex: for those it tries the names the other cars' maps give the same point,
 * and keeps one only if this car has it with the point's shape ("by reference");
 * a method is the one with the same code as theirs (map "sig", Dex.walk), whatever
 * its name.
 * Everything else that doesn't match exactly once is missing, with why.
 *
 *   java -cp <engine classes> hmgapi.engine.MapProbe <stock tree> <catalogue dir> [<maps dir>]
 *   prints {"points": {...}, "missing": {...}, "by_reference": [...]}
 *
 * On the unit: `engine map-car [--write]` (run below).
 */
public final class MapProbe {
    static final String[] BCP = { "core", "conscrypt", "okhttp", "core-junit", "bouncycastle", "ext", "framework", "framework2",
        "telephony-common", "voip-common", "mms-common", "android.policy", "services", "apache-xml", "webviewchromium", "automotive" };

    final String sys;      // what /system is: a stock tree (app/, framework/), or <root>/system
    final String backups;  // HMG-API's stock backups (<root>/data/hmgapi/stock), or null
    final File mapsDir;
    final Map<String, Dex.Cls> framework = new LinkedHashMap<String, Dex.Cls>();
    /** point id -> class name (class points) or member entry */
    public final Map<String, Object> found = new LinkedHashMap<String, Object>();
    /** point id -> its map entry, as map-car.py writes it */
    public final Map<String, Map<String, Object>> points = new TreeMap<String, Map<String, Object>>();
    public final Map<String, String> missing = new TreeMap<String, String>();
    public final Set<String> byReference = new java.util.TreeSet<String>();
    /** stock files read, path -> md5 (car.json "files") */
    public final Map<String, String> files = new TreeMap<String, String>();
    /** catalogue app -> {"package", "files"} (car.json "app_files") */
    public final Map<String, Object> appFiles = new TreeMap<String, Object>();
    final Map<String, List<Map<String, Object>>> references = new LinkedHashMap<String, List<Map<String, Object>>>();
    /** Keep each build's members together when identifying an obfuscated class. */
    final List<Map<String, Object>> referenceMaps = new ArrayList<Map<String, Object>>();

    static final class Miss extends Exception {
        final boolean later;   // needs another point first

        Miss(String why, boolean later) {
            super(why);
            this.later = later;
        }
    }

    /** sys: a stock tree (app/, framework/) or a unit's /system; backups: <root>/data/hmgapi/stock or null; mapsDir: other cars' maps (names for smali-only points) */
    public MapProbe(String sys, String backups, File mapsDir) throws Exception {
        this.sys = sys;
        this.backups = backups;
        this.mapsDir = mapsDir;
        for (String j : BCP) {
            File o = local("/system/framework/" + j + ".odex");
            if (o.isFile()) framework.putAll(Dex.read(o));
        }
        loadReferences();
    }

    /** a stock file: HMG-API's backup of it when it has one (a mod changed the file), else the file */
    File local(String path) {
        if (backups != null && new File(backups + path).isFile()) return new File(backups + path);
        return new File(sys + path.substring("/system".length()));
    }

    @SuppressWarnings("unchecked")
    void loadReferences() {
        if (mapsDir == null || !mapsDir.isDirectory()) return;
        for (File make : CarMap.list(mapsDir)) for (File model : CarMap.list(make)) for (File region : CarMap.list(model)) for (File fw : CarMap.list(region)) {
            File[] js = fw.listFiles();
            if (js == null) continue;
            for (File j : js) {
                if (!j.getName().endsWith(".json") || j.getName().equals("car.json")) continue;
                try {
                    Map<String, Object> entries = Json.object(Engine.read(j));
                    for (Map.Entry<String, Object> e : entries.entrySet()) {
                        List<Map<String, Object>> l = references.get(e.getKey());
                        if (l == null) references.put(e.getKey(), l = new ArrayList<Map<String, Object>>());
                        l.add((Map<String, Object>) e.getValue());
                    }
                    referenceMaps.add(entries);
                } catch (Exception ignored) {
                    // a broken map gives no references
                }
            }
        }
    }

    /** Maps every catalogue file (core/hookpoints/*.json) in a directory. */
    @SuppressWarnings("unchecked")
    public void run(File catalogueDir) throws Exception {
        File[] cats = catalogueDir.listFiles();
        java.util.Arrays.sort(cats);
        for (File f : cats) {
            if (!f.getName().endsWith(".json")) continue;
            Map<String, Object> cat = Json.object(Engine.read(f));
            if (!cat.containsKey("points")) continue;
            mapCatalogue(cat);
        }
    }

    @SuppressWarnings("unchecked")
    void mapCatalogue(Map<String, Object> cat) throws Exception {
        String app = String.valueOf(cat.get("app"));
        List<Map<String, Object>> pts = (List<Map<String, Object>>) cat.get("points");
        List<Object> catFiles = (List<Object>) cat.get("files");
        Map<String, Object> af = new LinkedHashMap<String, Object>();
        af.put("package", cat.get("package"));
        af.put("files", catFiles);
        appFiles.put(app, af);
        Map<String, Dex.Cls> classes = new LinkedHashMap<String, Dex.Cls>(framework);
        String noFiles = null;
        for (Object o : catFiles) {
            String path = String.valueOf(o);
            File lf = local(path);
            if (!lf.isFile()) {
                noFiles = "no stock " + path + (backups != null ? " on this car" : " in the tree");
                continue;
            }
            files.put(path, CarMap.md5(lf));
            String base = lf.getName().replaceAll("\\.odex$", "");
            if (cat.get("package") == null && java.util.Arrays.asList(BCP).contains(base)) continue;   // already in framework
            classes.putAll(Dex.read(lf));
        }
        if (noFiles != null) {
            for (Map<String, Object> p : pts) missing.put(String.valueOf(p.get("id")), noFiles);
            return;
        }
        List<Map<String, Object>> pending = new ArrayList<Map<String, Object>>(pts);
        while (!pending.isEmpty()) {
            boolean progress = false;
            for (Map<String, Object> pt : new ArrayList<Map<String, Object>>(pending)) {
                String id = String.valueOf(pt.get("id"));
                try {
                    Object r = point(classes, pt);
                    found.put(id, r);
                    points.put(id, r instanceof String ? entry("class", r) : (Map<String, Object>) r);
                } catch (Miss m) {
                    if (m.later) continue;
                    missing.put(id, m.getMessage());
                }
                pending.remove(pt);
                progress = true;
            }
            if (!progress) {
                for (Map<String, Object> pt : pending) {
                    missing.put(String.valueOf(pt.get("id")), "needs " + (pt.get("of") != null ? pt.get("of") : "a point that is missing"));
                }
                break;
            }
        }
    }

    static Map<String, Object> entry(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    String resolve(String t) throws Miss {
        if (!t.startsWith("@")) return t;
        String base = t.substring(1), arr = "";
        while (base.endsWith("[]")) {
            base = base.substring(0, base.length() - 2);
            arr += "[]";
        }
        Object c = found.get(base);
        if (!(c instanceof String)) throw new Miss("needs " + base + " first", true);
        return c + arr;
    }

    List<String> resolveAll(Object list) throws Miss {
        List<String> out = new ArrayList<String>();
        if (list instanceof List) for (Object t : (List<?>) list) out.add(resolve(String.valueOf(t)));
        return out;
    }

    // ---- shapes (map-car.py: class_matches, field_ok, method_ok)

    @SuppressWarnings("unchecked")
    boolean classMatches(Dex.Cls c, Map<String, Object> f) throws Miss {
        if (f.containsKey("name") && !c.name.equals(f.get("name"))) return false;
        if (f.containsKey("package") && !c.name.startsWith(f.get("package") + ".")) return false;
        if (f.containsKey("super") && !String.valueOf(f.get("super")).equals(c.sup)) return false;
        for (Object ct : list(f.get("ctors"))) {
            List<String> want = resolveAll(ct);
            boolean any = false;
            for (Dex.Member m : c.methods) if (m.name.equals("<init>") && m.params.equals(want)) any = true;
            if (!any) return false;
        }
        for (Object fs : list(f.get("fields"))) {
            boolean any = false;
            for (Dex.Member x : c.fields) if (fieldOk(x, (Map<String, Object>) fs)) any = true;
            if (!any) return false;
        }
        for (Object ms : list(f.get("methods"))) {
            boolean any = false;
            for (Dex.Member x : c.methods) if (methodOk(x, (Map<String, Object>) ms)) any = true;
            if (!any) return false;
        }
        for (Object s : list(f.get("strings"))) if (!c.strings.contains(String.valueOf(s))) return false;
        return true;
    }

    static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<Object>();
    }

    boolean fieldOk(Dex.Member x, Map<String, Object> fs) throws Miss {
        if (fs.containsKey("name") && !x.name.equals(fs.get("name"))) return false;
        if (fs.containsKey("type") && !x.type.equals(resolve(String.valueOf(fs.get("type"))))) return false;
        if (fs.containsKey("static") && x.isStatic != Boolean.TRUE.equals(fs.get("static"))) return false;
        return true;
    }

    boolean methodOk(Dex.Member x, Map<String, Object> ms) throws Miss {
        if (x.name.equals("<init>") || x.name.equals("<clinit>")) return false;
        if (ms.containsKey("name") && !x.name.equals(ms.get("name"))) return false;
        if (ms.containsKey("params") && !x.params.equals(resolveAll(ms.get("params")))) return false;
        if (ms.containsKey("ret") && !x.ret.equals(resolve(String.valueOf(ms.get("ret"))))) return false;
        if (ms.containsKey("static") && x.isStatic != Boolean.TRUE.equals(ms.get("static"))) return false;
        for (Object s : list(ms.get("strings"))) if (!x.strings.contains(String.valueOf(s))) return false;
        return true;
    }

    // ---- points (map-car.py: point)

    @SuppressWarnings("unchecked")
    Object point(Map<String, Dex.Cls> classes, Map<String, Object> pt) throws Miss {
        String id = String.valueOf(pt.get("id")), kind = String.valueOf(pt.get("kind"));
        Map<String, Object> f = (Map<String, Object>) pt.get("find");
        boolean smali = f.containsKey("pattern") || f.containsKey("code");
        if ("class".equals(kind)) {
            if (smali) return byReferenceClass(classes, id, f);
            List<String> hits = new ArrayList<String>();
            for (Dex.Cls c : classes.values()) if (classMatches(c, f)) hits.add(c.name);
            if (hits.size() != 1) throw new Miss(hits.size() + " classes match" + (hits.isEmpty() ? "" : " (" + first(hits) + ")"), false);
            return hits.get(0);
        }
        Object owner = found.get(String.valueOf(pt.get("of")));
        if (!(owner instanceof String)) throw new Miss("its class " + pt.get("of") + " isn't mapped", true);
        Dex.Cls c = classes.get(owner);
        if (c == null) throw new Miss("no class " + owner, false);
        if ("field".equals(kind)) {
            List<Dex.Member> hits = new ArrayList<Dex.Member>();
            for (Dex.Member x : c.fields) {
                if (fieldOk(x, f) && (!smali || referenced(id, "field", x.name))) hits.add(x);
            }
            if (smali) byRef(id, hits.size());
            if (hits.size() != 1) throw new Miss(hits.size() + " fields match in " + owner, false);
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("field", hits.get(0).name);
            e.put("type", hits.get(0).type);
            e.put("static", hits.get(0).isStatic);
            return e;
        }
        if ("ctor".equals(kind)) {
            List<String> want = resolveAll(f.get("params"));
            for (Dex.Member m : c.methods) {
                if (m.name.equals("<init>") && m.params.equals(want)) return entry("ctor", new ArrayList<Object>(want));
            }
            throw new Miss("no such constructor in " + owner, false);
        }
        if ("method".equals(kind)) {
            Dex.Member h = smali ? methodByReference(classes, c, id, f) : null;
            if (!smali) {
                List<Dex.Member> hits = new ArrayList<Dex.Member>();
                for (Dex.Member x : c.methods) if (methodOk(x, f)) hits.add(x);
                if (hits.size() != 1) throw new Miss(hits.size() + " methods match in " + owner, false);
                h = hits.get(0);
            }
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("method", h.name);
            e.put("params", new ArrayList<Object>(h.params));
            e.put("ret", h.ret);
            e.put("static", h.isStatic);
            if (smali) e.put("sig", Dex.sigHex(h.sig));
            memberOwner.put(id, String.valueOf(owner));
            return e;
        }
        throw new Miss("unknown kind " + kind, false);
    }

    /**
     * A method only a smali pattern finds: the one method of the point's shape whose code is the
     * same as another car's for it (map "sig", tools/dexsig.py). Names move between builds (py on
     * one is pB on another, and py there is something else), so a name is never enough.
     */
    Dex.Member methodByReference(Map<String, Dex.Cls> classes, Dex.Cls c, String id, Map<String, Object> f) throws Miss {
        List<Map<String, Object>> l = references.get(id);
        if (l == null) throw new Miss("found by a smali pattern only, and no other car's map has it", false);
        Set<String> sigs = new java.util.HashSet<String>();
        for (Map<String, Object> e : l) if (e.get("sig") != null) sigs.add(String.valueOf(e.get("sig")));
        if (sigs.isEmpty()) throw new Miss("found by a smali pattern only, and no other car's map has its code signature", false);
        List<Dex.Member> same = new ArrayList<Dex.Member>();
        for (Dex.Member x : c.methods) if (methodOk(x, f) && sigs.contains(Dex.sigHex(x.sig))) same.add(x);
        // several methods with the same code (getters of different fields, say): the catalogue's
        // call site, when it has one, tells them apart; otherwise missing, never a guess
        if (same.size() > 1 && f.get("site") instanceof Map) same = bySite(classes, c, same, (Map<String, Object>) f.get("site"));
        if (same.size() == 1) {
            byReference.add(id);
            return same.get(0);
        }
        throw new Miss("found by a smali pattern only; " + same.size() + " methods in " + c.name
            + " have the other cars' code for it" + (same.size() > 1 ? " (needs this car's map)" : ""), false);
    }

    /** methods by id: the class they're in (for a "site" hint's then_calls) */
    final Map<String, String> memberOwner = new java.util.HashMap<String, String>();

    /**
     * A catalogue "site" hint (MapProbe only; map-car.py has the smali pattern): what calls the
     * method, in a form the dex still shows after odex quickening.
     *   "virtual": true        the pattern calls it with invoke-virtual: a vtable method
     *   "in": class or @point  a method of that class calls it with invoke-static ...
     *   "next": [families]     ... followed by these instructions (Dex.family) ...
     *   "then_calls": @point   ... the first invoke-static among them calling that method point
     */
    @SuppressWarnings("unchecked")
    List<Dex.Member> bySite(Map<String, Dex.Cls> classes, Dex.Cls c, List<Dex.Member> same, Map<String, Object> site) throws Miss {
        List<Dex.Member> out = new ArrayList<Dex.Member>(same);
        if (Boolean.TRUE.equals(site.get("virtual"))) {
            for (java.util.Iterator<Dex.Member> it = out.iterator(); it.hasNext(); ) if (!it.next().isVirtual) it.remove();
        }
        if (site.get("in") == null || out.size() <= 1) return out;
        Dex.Cls in = classes.get(resolve(String.valueOf(site.get("in"))));
        if (in == null) throw new Miss("no class " + site.get("in") + " for the call site", false);
        List<Object> next = site.get("next") instanceof List ? (List<Object>) site.get("next") : new ArrayList<Object>();
        String then = null;
        if (site.get("then_calls") != null) {
            String pid = String.valueOf(site.get("then_calls")).replaceFirst("^@", "");
            Object e = found.get(pid);
            if (!(e instanceof Map) || memberOwner.get(pid) == null) throw new Miss("needs " + pid + " first", true);
            Map<String, Object> me = (Map<String, Object>) e;
            StringBuilder t = new StringBuilder(memberOwner.get(pid)).append('.').append(me.get("method")).append('(');
            int k = 0;
            for (Object p : (List<Object>) me.get("params")) t.append(k++ > 0 ? "," : "").append(p);
            then = t.append(')').append(me.get("ret")).toString();
        }
        Set<String> called = new java.util.HashSet<String>();
        try {
            for (Dex.Member m : in.methods) {
                for (Dex.Site st : Dex.sites(in, m, Math.max(next.size(), 8))) {
                    if (!"invoke-static".equals(st.op) || st.target == null) continue;
                    boolean ok = st.next.size() >= next.size();
                    for (int k = 0; ok && k < next.size(); k++) ok = st.next.get(k).equals(String.valueOf(next.get(k)));
                    if (ok && then != null) {
                        String first = null;
                        for (int k = 0; k < next.size() && first == null; k++) if ("invoke-static".equals(st.next.get(k))) first = st.nextTargets.get(k);
                        ok = then.equals(first);
                    }
                    if (ok) called.add(st.target);
                }
            }
        } catch (Miss x) {
            throw x;
        } catch (Exception x) {
            throw new Miss("can't read the code of " + in.name + ": " + x, false);
        }
        for (java.util.Iterator<Dex.Member> it = out.iterator(); it.hasNext(); ) {
            Dex.Member x = it.next();
            StringBuilder t = new StringBuilder(c.name).append('.').append(x.name).append('(');
            for (int k = 0; k < x.params.size(); k++) t.append(k > 0 ? "," : "").append(x.params.get(k));
            if (!called.contains(t.append(')').append(x.ret).toString())) it.remove();
        }
        return out;
    }

    void byRef(String id, int hits) throws Miss {
        if (!references.containsKey(id)) throw new Miss("found by a smali pattern only, and no other car's map has it", false);
        if (hits == 1) byReference.add(id);
    }

    /** another car's map names this point so (smali-only points) */
    boolean referenced(String id, String key, String name) {
        List<Map<String, Object>> l = references.get(id);
        if (l != null) for (Map<String, Object> e : l) if (name.equals(e.get(key))) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    String byReferenceClass(Map<String, Dex.Cls> classes, String id, Map<String, Object> f) throws Miss {
        if (!references.containsKey(id)) throw new Miss("found by a smali pattern only, and no other car's map has it", false);
        Set<String> hits = new LinkedHashSet<String>();
        boolean requireMembers = false;
        for (Map<String, Object> entries : referenceMaps) {
            for (Map.Entry<String, Object> en : entries.entrySet()) {
                if (referenceMember(id, en)) requireMembers = true;
            }
        }
        for (Map<String, Object> entries : referenceMaps) {
            Object raw = entries.get(id);
            if (!(raw instanceof Map)) continue;
            Map<String, Object> e = (Map<String, Object>) raw;
            String n = String.valueOf(e.get("class"));
            Dex.Cls c = classes.get(n);
            if (c != null && classMatches(c, f) && referenceMembersMatch(c, id, entries, requireMembers)) hits.add(n);
        }
        if (hits.isEmpty()) throw new Miss("found by a smali pattern only; no other car's reference class and members fit", false);
        if (hits.size() != 1) throw new Miss("found by a smali pattern only; " + hits.size() + " of the other cars' names fit", false);
        byReference.add(id);
        return hits.iterator().next();
    }

    static boolean referenceMember(String id, Map.Entry<String, Object> en) {
        int dot = en.getKey().lastIndexOf('.');
        if (dot < 0 || !id.equals(en.getKey().substring(0, dot)) || !(en.getValue() instanceof Map)) return false;
        Map<?, ?> e = (Map<?, ?>) en.getValue();
        return e.containsKey("method") || e.containsKey("field") || e.containsKey("ctor");
    }

    /** A reused class name is not enough: its members must fit one build's map. */
    @SuppressWarnings("unchecked")
    boolean referenceMembersMatch(Dex.Cls c, String id, Map<String, Object> entries, boolean requireMembers) throws Miss {
        boolean hasMembers = false;
        for (Map.Entry<String, Object> en : entries.entrySet()) {
            if (!referenceMember(id, en)) continue;
            hasMembers = true;
            Map<String, Object> e = (Map<String, Object>) en.getValue();
            boolean matches = false;
            if (e.containsKey("method")) {
                for (Dex.Member m : c.methods) {
                    if (m.name.equals(e.get("method")) && methodOk(m, e)) matches = true;
                }
            } else if (e.containsKey("field")) {
                for (Dex.Member m : c.fields) {
                    if (m.name.equals(e.get("field")) && fieldOk(m, e)) matches = true;
                }
            } else if (e.containsKey("ctor")) {
                List<String> params = resolveAll(e.get("ctor"));
                for (Dex.Member m : c.methods) {
                    if (m.name.equals("<init>") && m.params.equals(params)) matches = true;
                }
            } else continue;
            if (!matches) return false;
        }
        // A partial map may contain only the wrong class name and none of its
        // members. It cannot override the member evidence in other builds.
        return hasMembers || !requireMembers;
    }

    static String first(List<String> l) {
        java.util.Collections.sort(l);
        return l.size() > 3 ? l.subList(0, 3) + "..." : l.toString();
    }

    public Map<String, Object> result() {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("points", new TreeMap<String, Object>(points));
        r.put("missing", new TreeMap<String, Object>(missing));
        r.put("by_reference", new ArrayList<Object>(byReference));
        return r;
    }

    /**
     * engine map-car [--local [--if-needed] | --write | --forget]: maps this car from its own
     * files and keeps the result (state/map-probe.json, for the Car Report).
     *   --local   the points HMG-API's map lacks only because it's older than them, found here
     *             by name, each checked here (MapCheck) before it's written: state/local-map.json
     *             "points". --if-needed: only if HMG-API's map, its catalogue or the stock files
     *             changed since (the boot step after an install or update runs it so).
     *   --write   this car's whole map, for a car HMG-API has no map for, or whose map doesn't
     *             fit its framework: state/local-map.json "whole", status probed; like any
     *             unchecked map, its hooks run after `engine verify-map` passes or with
     *             "Try unchecked maps".
     *   --forget  deletes state/local-map.json: HMG-API's map alone from the next start.
     * Kept in state/, not maps/: an HMG-API update replaces maps/ as a whole.
     */
    static int run(Engine e, String[] args) throws Exception {
        java.util.List<String> a = java.util.Arrays.asList(args);
        boolean write = a.contains("--write"), local = a.contains("--local"), ifNeeded = a.contains("--if-needed");
        File lf = new File(e.root + CarMap.LOCAL);
        if (a.contains("--forget")) {
            boolean had = lf.isFile();
            lf.delete();
            e.out.add(had ? "forgotten: " + CarMap.LOCAL + " (HMG-API's map alone from the next start)" : "nothing to forget");
            return 0;
        }
        File maps = new File(e.root + "/data/hmgapi/maps");
        CarMap shipped = CarMap.load(e.root, e.facts, e.hmgapi, false);
        String key = null;
        if (local) {
            if (shipped.folder == null || "mismatch".equals(shipped.status) || shipped.carJson == null) {
                e.out.add("no HMG-API map to add points to on this car (" + shipped.reason + "): `hmgapid engine map-car --write` maps the whole car");
                return ifNeeded ? 0 : 5;
            }
            key = shipped.folder + " " + CarMap.md5(new File(maps, "points.txt")) + " " + new TreeMap<String, Object>((Map<String, Object>) shipped.carJson.get("files"));
            if (ifNeeded && lf.isFile()) {
                try {
                    Map<String, Object> was = Json.object(Engine.read(lf));
                    if (key.equals(was.get("key"))) {
                        e.out.add("map-car: up to date (" + ((Map<?, ?>) was.get("points")).size() + " point(s) mapped on this car)");
                        return 0;
                    }
                } catch (Exception x) {
                    // mapped again below
                }
            }
        }
        MapProbe p = new MapProbe(e.root + "/system", e.root + "/data/hmgapi/stock", maps);
        p.run(new File(maps, "catalogue"));
        Map<String, Object> r = p.result();
        r.put("product", e.facts.get("product"));
        r.put("files", new TreeMap<String, Object>(p.files));
        r.put("date", new java.util.Date().toString());
        Engine.write(new File(e.stateDir, "map-probe.json"), Json.write(r));
        e.out.add("map-car: " + p.points.size() + " hook point(s) found on this car (" + p.byReference.size()
                + " by other cars' names), " + p.missing.size() + " not found (state/map-probe.json)");
        int shown = 0;
        for (Map.Entry<String, String> m : p.missing.entrySet()) {
            if (m.getValue().startsWith("no stock ")) continue;   // apps this car doesn't have
            if (shown++ < 20) e.out.add("  not found: " + m.getKey() + " (" + m.getValue() + ")");
        }
        if (local) return writeLocal(e, p, shipped, key, lf, new File(maps, "catalogue"));
        if (!write) return 0;
        if (shipped.folder != null && !"mismatch".equals(shipped.status)) {
            e.out.add("not written: HMG-API has a map for this car (" + shipped.folder + "): `hmgapid engine map-car --local` adds the points it lacks");
            return 0;
        }
        String model = e.facts.get("model"), region = e.facts.get("region"), fw = e.facts.get("firmware");
        if (model == null || region == null || fw == null) {
            e.out.add("not written: this unit's product (" + e.facts.get("product") + ") doesn't say its model, region and firmware");
            return 5;
        }
        String make = e.facts.get("make") != null ? e.facts.get("make") : "unknown";
        Map<String, Object> car = new LinkedHashMap<String, Object>();
        car.put("whole", true);
        car.put("make", make.toLowerCase());
        car.put("model", model.toLowerCase());
        car.put("name", e.facts.get("name") != null ? e.facts.get("name") : model);
        car.put("region", region.toLowerCase());
        car.put("firmware", fw);
        car.put("product", e.facts.get("product"));
        List<Object> products = new ArrayList<Object>();
        products.add(model + "." + region + ".");
        car.put("products", products);
        car.put("status", "probed");
        car.put("verified", null);
        car.put("files", new TreeMap<String, Object>(p.files));
        Set<String> apps = new java.util.TreeSet<String>();
        for (String id : p.points.keySet()) apps.add(CarMap.appOfId(id));
        car.put("apps", new ArrayList<Object>(apps));
        car.put("app_files", p.appFiles);
        car.put("missing", new TreeMap<String, Object>(p.missing));
        car.put("by_reference", new ArrayList<Object>(p.byReference));
        car.put("points", new TreeMap<String, Object>(p.points));
        car.put("date", new java.util.Date().toString());
        Engine.write(lf, Json.write(car));
        e.out.add("written: " + CarMap.LOCAL + " (this car's own map, status probed" + (shipped.folder != null ? ", instead of " + shipped.folder
                + ", which doesn't fit its framework" : "") + "): run `hmgapid engine verify-map`, then restart, for its hooks");
        return 0;
    }

    /** the points HMG-API's map lacks only because it's older, found by name, checked here: state/local-map.json "points" */
    @SuppressWarnings("unchecked")
    static int writeLocal(Engine e, MapProbe p, CarMap shipped, String key, File lf, File catalogueDir) throws Exception {
        Map<String, Map<String, Object>> cat = catalogue(catalogueDir);
        Map<String, Object> shippedFiles = (Map<String, Object>) shipped.carJson.get("files");
        Map<String, Object> appFiles = (Map<String, Object>) shipped.carJson.get("app_files");
        Map<String, Object> candidates = new TreeMap<String, Object>(), files = new TreeMap<String, Object>();
        Map<String, Object> skipped = new TreeMap<String, Object>();
        CarMap t = new CarMap();   // HMG-API's map plus the candidates, for MapCheck (a member needs its class)
        t.carJson = shipped.carJson;
        t.points.putAll(shipped.points);
        t.appOf.putAll(shipped.appOf);
        for (Map.Entry<String, String> m : shipped.missing.entrySet()) {
            String id = m.getKey(), app = CarMap.appOfId(id);
            if (!CarMap.olderMap(m.getValue())) continue;   // the mapper looked for it and didn't find it: a maintainer's call
            String why = null;
            if (!p.points.containsKey(id)) why = "not found on this car (" + p.missing.get(id) + ")";
            else if (p.byReference.contains(id) || !byName(cat, id)) why = "not a by-name point: a maintainer maps it";
            Object af = appFiles == null ? null : appFiles.get(app);
            Object fs = af instanceof Map ? ((Map<String, Object>) af).get("files") : null;
            if (why == null && !(fs instanceof List)) why = "HMG-API's map has no stock files for its app";
            if (why == null) {
                for (Object f : (List<Object>) fs) {
                    String path = String.valueOf(f);
                    Object want = shippedFiles == null ? null : shippedFiles.get(path), seen = p.files.get(path);
                    if (seen == null || !seen.equals(want)) why = path + " isn't the build HMG-API's map was made from";
                    else files.put(path, seen);
                }
            }
            if (why != null) {
                skipped.put(id, why);
                continue;
            }
            candidates.put(id, p.points.get(id));
            t.points.put(id, p.points.get(id));
            t.appOf.put(id, app);
        }
        Map<String, String> failed = MapCheck.resolve(e, t, candidates.keySet());
        for (String id : failed.keySet()) candidates.remove(id);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("whole", false);
        out.put("key", key);
        out.put("base", shipped.folder);
        out.put("files", files);
        out.put("points", candidates);
        out.put("failed", new TreeMap<String, Object>(failed));
        out.put("skipped", skipped);
        out.put("date", new java.util.Date().toString());
        Engine.write(lf, Json.write(out));
        e.out.add("local: " + candidates.size() + " point(s) HMG-API's map " + shipped.folder + " lacks, mapped and checked on this car ("
                + CarMap.LOCAL + "); " + failed.size() + " didn't resolve, " + skipped.size() + " left to a maintainer");
        for (Map.Entry<String, String> f : failed.entrySet()) e.out.add("  didn't resolve: " + f.getKey() + " (" + f.getValue() + ")");
        if (!candidates.isEmpty()) e.out.add("they're used from the next start: readings and mods' observe hooks (the rest needs HMG-API's map, or \"Try unchecked maps\")");
        return 0;
    }

    /** the catalogue's points by id */
    @SuppressWarnings("unchecked")
    static Map<String, Map<String, Object>> catalogue(File dir) throws Exception {
        Map<String, Map<String, Object>> out = new LinkedHashMap<String, Map<String, Object>>();
        File[] cats = dir.listFiles();
        if (cats == null) return out;
        for (File f : cats) {
            if (!f.getName().endsWith(".json")) continue;
            Map<String, Object> cat = Json.object(Engine.read(f));
            Object pts = cat.get("points");
            if (pts instanceof List) for (Object pt : (List<Object>) pts) out.put(String.valueOf(((Map<String, Object>) pt).get("id")), (Map<String, Object>) pt);
        }
        return out;
    }

    /** found by name: its class by name, the member by name (a constructor by its parameters), no smali pattern */
    @SuppressWarnings("unchecked")
    static boolean byName(Map<String, Map<String, Object>> cat, String id) {
        Map<String, Object> pt = cat.get(id);
        if (pt == null) return false;
        Map<String, Object> f = (Map<String, Object>) pt.get("find");
        if (f == null || f.containsKey("pattern") || f.containsKey("code")) return false;
        String kind = String.valueOf(pt.get("kind"));
        if ("class".equals(kind)) return f.containsKey("name");
        if (!"ctor".equals(kind) && !f.containsKey("name")) return false;
        return pt.get("of") != null && byName(cat, String.valueOf(pt.get("of")));
    }

    public static void main(String[] a) throws Exception {
        MapProbe p = new MapProbe(new File(a[0]).getAbsolutePath(), null, a.length > 2 ? new File(a[2]) : null);
        p.run(new File(a[1]));
        System.out.println(Json.write(p.result()));
    }
}

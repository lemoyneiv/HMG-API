package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * System requirements: which head units a mod (or one of its parts) is for.
 * Used by the patch engine and by the hook runtime's mod loader, so a mod is
 * refused the same way whether it patches files or only hooks.
 *
 * mod.json "system" (or a part's "system"):
 *
 *   "system": {
 *     "make":     ["kia", "hyundai"],          car make (from the model table, or the owner's setting)
 *     "model":    ["CVEV", "NEEV", "NEEV23"],  model code: first field of ro.build.product
 *     "name":     ["EV6"],                     model name (table / owner's setting)
 *     "year":     ">=2022 <2025",              model year: the owner's setting (the unit doesn't know it)
 *     "region":   ["USA", "CAN"],              second field of ro.build.product
 *     "firmware": [">=260206"],                last field of ro.build.product
 *     "product":  ["CVEV.USA."],               ro.build.product prefixes
 *     "platform": ["tcc803x"],                 ro.board.platform
 *     "android":  "4.4.2",                     ro.build.version.release
 *     "onebin":   ["cvev_us"],                 ro.mobis.onebindir
 *     "features": ["hmgapi.wifi", "!com.hkmc.hardware.ev"],   all must hold
 *     "props":    {"ro.product.opt.stepofspec": ">=100"}
 *   }
 *
 * "system": "all" (or no "system") is any head unit. Every option may be left out or set to "all" (or "*"): any value. Otherwise a
 * value or a list: the unit must match one entry; "!x" entries exclude. For
 * firmware, year, android and props, an entry starting with an operator
 * (>=, >, <=, <, =, ^, ~) is a version range. "features" lists what the unit
 * must have (or, with "!", must not); features are the <feature name> entries
 * in /system/etc/permissions plus HMG-API's own: hmgapi.wifi (a Wi-Fi chip
 * answered on SDIO, or its driver and firmware are in /system/wifi on a car with
 * the stock hotspot; the wifi.module feature file is there even without one), and
 * hmgapi.wifi.unpowered (not seen, but the driver, firmware and wifi.module feature are
 * there and the stock hotspot feature isn't: the unit never powers the chip, so it may
 * be there; Korean units and some US trims). A mod that asks HMG-API for the hotspot
 * ("wifi": {"hotspot": true}) turns it on, so for that mod hmgapi.wifi.unpowered meets
 * hmgapi.wifi; once the chip has shown on SDIO, it's hmgapi.wifi for every mod.
 *
 * "system" may also be a list of such objects: any one of them is enough, for
 * a mod that supports e.g. the US EV6 on 260507 and the Canadian Ioniq 5 on 260206.
 *
 * Unknown facts (no model year set, a model not in the table) fail a
 * requirement that lists values, and the reason says what to set.
 */
public final class SystemCheck {
    private SystemCheck() {}

    /**
     * model code -> {make, name}. Owner's hmgapi.json "car" overrides it. Every model with
     * a hook map (maps/, car.json "products") is here (system-test.sh checks): without its
     * line a car has no make or name, so a mod's "system" that names one refuses it and
     * its Car Report has no name to go by.
     */
    static final String[][] MODELS = {
        {"CVEV", "kia", "EV6"},
        {"CEEV", "hyundai", "Ioniq 6"},
        {"NEEV", "hyundai", "Ioniq 5"},
        {"NEEV23", "hyundai", "Ioniq 5"},
        {"NEEV23SE", "hyundai", "Ioniq 5"},
        {"LX2PE", "hyundai", "Palisade"},
        {"NQ5", "kia", "Sportage"},
        {"ONPE", "kia", "Telluride"},
        {"SG2EV", "kia", "Niro EV"},
        {"KA423", "kia", "Carnival"},
        {"KA4", "kia", "Carnival"},
        {"BDPE", "kia", "Forte (K3)"},
        {"NQ5HEV", "kia", "Sportage Hybrid"},
        {"NQ5PHEV", "kia", "Sportage Plug-in Hybrid"},
        {"SG2HEV", "kia", "Niro Hybrid"},
        {"SG2PHEV", "kia", "Niro Plug-in Hybrid"},
        {"SK3PE", "kia", "Soul"},
        {"SK3EPE", "kia", "Soul EV"},
        {"SP2PE", "kia", "Seltos"},
        {"IKFL", "genesis", "G70"},
        {"SU2IFL", "hyundai", "Creta"},
        {"DL3", "kia", "K5"},
        {"DL3HEV", "kia", "K5 Hybrid"},
        // the older Hyundai and Kia builds (US 260507, Korea 260902): one map per build, many models
        {"AEPEEV", "hyundai", "Ioniq Electric"},
        {"AEPEHEV", "hyundai", "Ioniq Hybrid"},
        {"AEPEPHEV", "hyundai", "Ioniq Plug-in Hybrid"},
        {"CN7", "hyundai", "Elantra"},
        {"CN723", "hyundai", "Elantra"},
        {"CN7HEV", "hyundai", "Elantra Hybrid"},
        {"CN7HEV23", "hyundai", "Elantra Hybrid"},
        {"DN8", "hyundai", "Sonata"},
        {"DN822", "hyundai", "Sonata"},
        {"DN823", "hyundai", "Sonata"},
        {"DN8HEV", "hyundai", "Sonata Hybrid"},
        {"DN822HEV", "hyundai", "Sonata Hybrid"},
        {"DN823HEV", "hyundai", "Sonata Hybrid"},
        {"JSN", "hyundai", "Veloster N"},
        {"LX2", "hyundai", "Palisade"},
        {"OSEV", "hyundai", "Kona Electric"},
        {"OSPE", "hyundai", "Kona"},
        {"OSPE23", "hyundai", "Kona"},
        {"QX21", "hyundai", "Venue"},
        {"TMFL", "hyundai", "Santa Fe"},
        {"TM23", "hyundai", "Santa Fe"},
        {"TMHEV", "hyundai", "Santa Fe Hybrid"},
        {"TMHEV23", "hyundai", "Santa Fe Hybrid"},
        {"TMPHEV", "hyundai", "Santa Fe Plug-in Hybrid"},
        {"TMPHEV23", "hyundai", "Santa Fe Plug-in Hybrid"},
        {"DL322", "kia", "K5"},
        {"DL3HEV22", "kia", "K5 Hybrid"},
        {"CKPE", "kia", "Stinger"},
        {"CKPE22", "kia", "Stinger"},
        {"CKPE23", "kia", "Stinger"},
        {"DE20EV", "kia", "Niro EV"},
        {"DE22EV", "kia", "Niro EV"},
        {"DEPEHEV", "kia", "Niro Hybrid"},
        {"DE22HEV", "kia", "Niro Hybrid"},
        {"DEPEPHEV", "kia", "Niro Plug-in Hybrid"},
        {"DE22PHEV", "kia", "Niro Plug-in Hybrid"},
        {"DEEVPBV", "kia", "Niro Plus"},
        {"HMPE2", "kia", "Mohave"},
        {"JAPE", "kia", "Morning (Picanto)"},
        {"JAPE22", "kia", "Morning (Picanto)"},
        {"MQ4", "kia", "Sorento"},
        {"MQ422", "kia", "Sorento"},
        {"MQ4HEV", "kia", "Sorento Hybrid"},
        {"MQ422HEV", "kia", "Sorento Hybrid"},
        {"MQ4PHEV", "kia", "Sorento Plug-in Hybrid"},
        {"ON", "kia", "Telluride"},
        {"ON22", "kia", "Telluride"},
        {"SK3", "kia", "Soul"},
        {"SK321", "kia", "Soul"},
        {"SK322", "kia", "Soul"},
        {"SK3EV", "kia", "Soul EV"},
        {"SK3EV21", "kia", "Soul EV"},
        {"SK3EV22", "kia", "Soul EV"},
        {"SP2", "kia", "Seltos"},
        {"SP221", "kia", "Seltos"},
        {"SP222", "kia", "Seltos"},
        {"TAM", "kia", "Ray"},
        {"TAM22", "kia", "Ray"},
        {"YGPE", "kia", "K7 (Cadenza)"},
        {"YGPEHEV", "kia", "K7 Hybrid (Cadenza Hybrid)"},
    };

    static final String[] KEYS = {"make", "model", "name", "year", "region", "firmware", "product",
        "platform", "android", "onebin", "features", "props"};

    /** a Wi-Fi chip answered on SDIO on this unit once (hmgapi.wifi) */
    static final String WIFI_SEEN = "/data/hmgapi/public/wifi-chip";

    /** What the head unit is. */
    public static final class Facts {
        final Map<String, String> f = new LinkedHashMap<String, String>();
        final String root;
        final Map<String, String> fileProps;
        TreeSet<String> features;

        /**
         * root: "" on the unit, a folder in tests. Properties come from
         * android.os.SystemProperties, or root/props.txt (k=v lines) when that
         * isn't there (desktop tests). car: hmgapi.json's "car" object or null.
         */
        public Facts(String root, Map<String, Object> car) {
            this.root = root;
            fileProps = readProps(new File(root + "/props.txt"));
            String product = env("HMGAPI_PRODUCT", prop("ro.build.product"));
            f.put("product", product);
            if (product != null) {
                String[] p = product.split("\\.");
                f.put("model", p.length > 0 ? p[0] : null);
                f.put("region", p.length > 1 ? p[1] : null);
                f.put("firmware", p.length > 2 ? p[p.length - 1] : null);
            }
            for (String[] m : MODELS) {
                if (m[0].equals(f.get("model"))) {
                    f.put("make", m[1]);
                    f.put("name", m[2]);
                }
            }
            f.put("platform", prop("ro.board.platform"));
            f.put("android", prop("ro.build.version.release"));
            f.put("onebin", env("HMGAPI_ONEBIN", prop("ro.mobis.onebindir")));
            if (car != null) {
                for (String k : new String[] {"make", "name", "year"}) {
                    if (car.get(k) != null) f.put(k, String.valueOf(car.get(k)));
                }
            }
        }

        public String get(String k) {
            return f.get(k);
        }

        public String prop(String k) {
            if (fileProps != null) return fileProps.get(k);
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                String v = (String) sp.getMethod("get", String.class).invoke(null, k);
                return v == null || v.isEmpty() ? null : v;
            } catch (Throwable t) {
                return null;
            }
        }

        /** Read only when a requirement asks: every app start runs the mod loader. */
        public TreeSet<String> features() {
            if (features != null) return features;
            features = new TreeSet<String>();
            File[] xs = new File(root + "/system/etc/permissions").listFiles();
            if (xs != null) {
                for (File x : xs) {
                    if (!x.getName().endsWith(".xml")) continue;
                    try {
                        String t = Engine.read(x);
                        for (int i = t.indexOf("<feature"); i >= 0; i = t.indexOf("<feature", i + 1)) {
                            int n = t.indexOf("name=\"", i), e = n < 0 ? -1 : t.indexOf('"', n + 6);
                            if (n > 0 && e > 0 && n < t.indexOf('>', i)) features.add(t.substring(n + 6, e));
                        }
                    } catch (Exception ignored) {
                        // unreadable file: its features don't count
                    }
                }
            }
            // the chip shows up on SDIO a few seconds into boot, after the zygote loads mods;
            // once seen it's remembered (the engine, as root, writes the marker)
            String[] sdio = new File(root + "/sys/bus/sdio/devices").list();
            File seen = new File(root + WIFI_SEEN);
            if (sdio != null && sdio.length > 0) {
                features.add("hmgapi.wifi");
                if (!seen.exists()) {
                    try {
                        seen.createNewFile();
                    } catch (Exception ignored) {
                        // not root: the engine records it
                    }
                }
            } else if (seen.exists() || wifiDriver()) {
                features.add("hmgapi.wifi");
            } else if (driverFiles() && features.contains("com.hkmc.software.wifi.module")) {
                features.add("hmgapi.wifi.unpowered");
            }
            return features;
        }

        /**
         * The chip is only powered once Wi-Fi or the hotspot is turned on (the Wi-Fi HAL loads
         * its driver then), so a unit that never had Wi-Fi on shows nothing on SDIO. Its
         * Broadcom driver and firmware in /system/wifi, on a car whose stock software has the
         * hotspot, stand for it.
         */
        boolean wifiDriver() {
            return features.contains(Engine.HOTSPOT) && !hmgapiHotspot() && driverFiles();
        }

        /** the Broadcom driver and its firmware */
        boolean driverFiles() {
            return new File(root + "/system/wifi/bcmdhd.ko").isFile() && new File(root + "/system/wifi/fw_bcmdhd.bin").isFile();
        }

        /**
         * Is the hotspot feature HMG-API's own file ("wifi": {"hotspot": true})? Then it isn't the
         * car's: it says nothing about a chip, until the chip shows on SDIO. Known by its bytes,
         * which HMG-API writes the same everywhere (Engine.featureXml) and no stock file has.
         */
        boolean hmgapiHotspot() {
            File x = new File(root + "/system/etc/permissions/" + Engine.HOTSPOT + ".xml");
            try {
                return x.isFile() && Engine.read(x).equals(Engine.featureXml(Engine.HOTSPOT));
            } catch (Exception e) {
                return false;
            }
        }

        /** for the Car Report: "seen" (on SDIO, now or once), "stock hotspot", "unpowered" or "none" */
        public String wifi() {
            TreeSet<String> fs = features();
            if (fs.contains("hmgapi.wifi")) {
                String[] sdio = new File(root + "/sys/bus/sdio/devices").list();
                return (sdio != null && sdio.length > 0) || new File(root + WIFI_SEEN).exists() ? "seen" : "stock hotspot";
            }
            return fs.contains("hmgapi.wifi.unpowered") ? "unpowered" + (hmgapiHotspot() ? " (HMG-API turned the hotspot feature on)" : "") : "none";
        }

        /** One line for logs and the app: what HMG-API thinks this unit is. */
        public String describe() {
            StringBuilder s = new StringBuilder();
            for (Map.Entry<String, String> e : f.entrySet()) {
                if (s.length() > 0) s.append(", ");
                s.append(e.getKey()).append(' ').append(e.getValue() == null ? "?" : e.getValue());
            }
            return s.toString();
        }

        static String env(String k, String d) {
            String v = System.getenv(k);
            return v == null ? d : v;
        }

        static Map<String, String> readProps(File f) {
            if (!f.isFile()) return null;
            Map<String, String> m = new LinkedHashMap<String, String>();
            try {
                for (String l : Engine.read(f).split("\n")) {
                    int eq = l.indexOf('=');
                    if (eq > 0) m.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
                }
            } catch (Exception ignored) {
                // no props
            }
            return m;
        }
    }

    /** null if the unit meets the requirements, else why not (plain words). */
    @SuppressWarnings("unchecked")
    public static String check(Object system, Facts facts) {
        return check(system, facts, false);
    }

    /** does this mod.json ask HMG-API for the hotspot ("wifi": {"hotspot": true})? */
    @SuppressWarnings("unchecked")
    public static boolean asksHotspot(Map<String, Object> mod) {
        Object w = mod == null ? null : mod.get("wifi");
        return w instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) w).get("hotspot"));
    }

    /** powersWifi: the mod asks for the hotspot, so a chip that's only unpowered will do (hmgapi.wifi.unpowered) */
    @SuppressWarnings("unchecked")
    public static String check(Object system, Facts facts, boolean powersWifi) {
        if (system == null || (!(system instanceof Map) && !(system instanceof List) && isAll(system))) return null;
        if (system instanceof List) {
            List<Object> alts = (List<Object>) system;
            if (alts.isEmpty()) return null;
            List<String> why = new ArrayList<String>();
            for (Object o : alts) {
                String r = check(o, facts, powersWifi);
                if (r == null) return null;
                why.add(r);
            }
            return alts.size() == 1 ? why.get(0) : "matches none of its " + alts.size() + " supported setups (" + join(why, "; ") + ")";
        }
        if (!(system instanceof Map)) return "\"system\" must be an object or a list of objects";
        Map<String, Object> req = (Map<String, Object>) system;
        for (String k : req.keySet()) {
            boolean known = false;
            for (String x : KEYS) known |= x.equals(k);
            if (!known) return "unknown system requirement \"" + k + "\"";
        }
        for (String k : KEYS) {
            Object spec = req.get(k);
            if (spec == null || isAll(spec)) continue;
            String r;
            if ("features".equals(k)) r = features(spec, facts, powersWifi);
            else if ("props".equals(k)) r = props(spec, facts);
            else r = one(k, facts.get(k), spec);
            if (r != null) return r;
        }
        return null;
    }

    static boolean isAll(Object spec) {
        if (spec instanceof List) {
            for (Object o : (List<?>) spec) if (isAll(o)) return true;
            return false;
        }
        String s = String.valueOf(spec).trim();
        return "all".equalsIgnoreCase(s) || "*".equals(s);
    }

    static List<String> entries(Object spec) {
        List<String> out = new ArrayList<String>();
        if (spec instanceof List) for (Object o : (List<?>) spec) out.add(String.valueOf(o).trim());
        else out.add(String.valueOf(spec).trim());
        return out;
    }

    static final String[] HINT = {
        "year", "set the car's model year in HMG-API's settings (car.year)",
        "make", "set the car's make in HMG-API's settings (car.make)",
        "name", "set the car's model name in HMG-API's settings (car.name)",
    };

    /** One option: the fact must match a positive entry (if any) and no "!" entry. */
    static String one(String key, String have, Object spec) {
        List<String> pos = new ArrayList<String>(), neg = new ArrayList<String>();
        for (String e : entries(spec)) {
            if (e.startsWith("!")) neg.add(e.substring(1).trim());
            else pos.add(e);
        }
        if (have == null) {
            if (pos.isEmpty()) return null;
            String hint = "";
            for (int i = 0; i < HINT.length; i += 2) if (HINT[i].equals(key)) hint = ": " + HINT[i + 1];
            return "needs " + key + " " + join(pos, " or ") + ", and this unit's " + key + " is unknown" + hint;
        }
        for (String e : neg) {
            if (matches(key, have, e)) return "not for " + key + " " + have;
        }
        if (pos.isEmpty()) return null;
        for (String e : pos) if (matches(key, have, e)) return null;
        return "needs " + key + " " + join(pos, " or ") + " (this unit: " + have + ")";
    }

    static boolean matches(String key, String have, String e) {
        if ("product".equals(key)) return have.toUpperCase().startsWith(e.toUpperCase());
        if (isRange(e)) {
            try {
                return Version.satisfies(have, e);
            } catch (IllegalArgumentException x) {
                return false;   // not a version (a text prop): a range can't hold
            }
        }
        return have.equalsIgnoreCase(e);
    }

    static boolean isRange(String e) {
        return !e.isEmpty() && "<>=^~".indexOf(e.charAt(0)) >= 0;
    }

    static String features(Object spec, Facts facts, boolean powersWifi) {
        for (String e : entries(spec)) {
            boolean not = e.startsWith("!");
            String name = not ? e.substring(1).trim() : e;
            boolean unpowered = facts.features().contains("hmgapi.wifi.unpowered");
            boolean has = facts.features().contains(name) || ("hmgapi.wifi".equals(name) && powersWifi && unpowered);
            if (not && has) return "not for units with " + name;
            if (!not && !has) {
                if (!"hmgapi.wifi".equals(name)) return "needs " + name;
                return "needs " + name + (unpowered
                        ? " (no Wi-Fi chip seen yet: this car's hotspot feature is off, so the unit never powers it; a mod that"
                                + " asks HMG-API for the hotspot, \"wifi\": {\"hotspot\": true}, turns it on)"
                        : " (no Wi-Fi chip or driver found on this unit)");
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String props(Object spec, Facts facts) {
        if (!(spec instanceof Map)) return "\"props\" must be an object: {\"<property>\": <value or list>}";
        for (Map.Entry<String, Object> e : ((Map<String, Object>) spec).entrySet()) {
            if (isAll(e.getValue())) continue;
            String r = one(e.getKey(), facts.prop(e.getKey()), e.getValue());
            if (r != null) return r;
        }
        return null;
    }

    static String join(List<String> l, String sep) {
        StringBuilder s = new StringBuilder();
        for (String x : l) {
            if (s.length() > 0) s.append(sep);
            s.append(x);
        }
        return s.toString();
    }

    /**
     * hmgapi.json overrides.ignore_system: true, or a list of mod ids. At the owner's own risk.
     * The app's "Install anyway" (engine install --any-car) adds the mod's id to the list.
     */
    @SuppressWarnings("unchecked")
    public static boolean ignored(Map<String, Object> hmgapi, String id) {
        Object o = hmgapi.get("overrides");
        if (!(o instanceof Map)) return false;
        Object v = ((Map<String, Object>) o).get("ignore_system");
        if (Boolean.TRUE.equals(v)) return true;
        return v instanceof List && ((List<Object>) v).contains(id);
    }

    /** Test/inspect: print the facts, or check a mod folder. */
    public static void main(String[] a) throws Exception {
        String root = Facts.env("HMGAPI_ROOT", "");
        File lj = new File(root + "/data/hmgapi/settings/hmgapi.json");
        Map<String, Object> hmgapi = lj.isFile() ? Json.object(Engine.read(lj)) : new LinkedHashMap<String, Object>();
        Facts facts = new Facts(root, (Map<String, Object>) hmgapi.get("car"));
        if (a.length == 0) {
            System.out.println(facts.describe());
            System.out.println("features: " + facts.features().size() + (facts.features().contains("hmgapi.wifi") ? ", Wi-Fi chip found"
                    : facts.features().contains("hmgapi.wifi.unpowered") ? ", Wi-Fi chip not seen: the hotspot feature is off, so the unit never powers it" : ", no Wi-Fi chip"));
            return;
        }
        Map<String, Object> mod = Json.object(Engine.read(new File(a[0], "mod.json")));
        String r = check(mod.get("system"), facts, asksHotspot(mod));
        System.out.println(r == null ? "ok: " + mod.get("id") + " supports this unit" : mod.get("id") + ": " + r);
        System.exit(r == null ? 0 : 1);
    }
}

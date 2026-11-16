package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home-screen launcher tile visibility configuration, catalog reader, and saved-menu
 * grid synchroniser. Kept in pure Java 7 (no Android or org.json imports) so it compiles
 * cleanly with the standalone engine SDK build and runs in off-car JVM unit tests.
 */
public final class LauncherTilesStore {
    private LauncherTilesStore() {}

    public static final String ACTION = "hmgapi.intent.action.LAUNCHER_TILES_CHANGED";
    public static final String SETTINGS_REL = "settings/launcher-tiles.json";
    public static final String PUBLIC_REL = "public/launcher-tiles.json";
    public static final String CATALOG_REL = "/data/data/com.android.launcher3/files/hmgapi-launcher-catalog.json";
    public static final String BACKUP_REL = "/data/data/com.android.launcher3/files/hmgapi-hidden-backup.json";

    public static final int ITEM_ID_BASE = 9000;
    public static final int ITEM_ID_LEGACY = 90;

    public static final class Config {
        public final boolean exists;
        public final boolean compactGaps;
        public final Set<String> hidden;

        public Config(boolean exists, boolean compactGaps, Set<String> hidden) {
            this.exists = exists;
            this.compactGaps = compactGaps;
            this.hidden = Collections.unmodifiableSet(new LinkedHashSet<String>(hidden));
        }
    }

    public static final class ModTile {
        public final String packageName;
        public final String className;
        public final int itemId;

        public ModTile(String packageName, String className, int itemId) {
            this.packageName = packageName;
            this.className = className;
            this.itemId = itemId;
        }
    }

    public static final class SyncResult {
        public final String updatedMenuJson;
        public final boolean backupChanged;
        public final List<String> logs;

        SyncResult(String updatedMenuJson, boolean backupChanged, List<String> logs) {
            this.updatedMenuJson = updatedMenuJson;
            this.backupChanged = backupChanged;
            this.logs = logs;
        }
    }

    public static boolean isEmptySlot(String pkg, String cls) {
        return pkg == null || pkg.isEmpty() || "Empty view".equals(pkg) || "Empty view".equals(cls);
    }

    public static String normKey(String pkg, String cls) {
        String p = pkg == null ? "" : pkg.trim();
        String c = cls == null ? "" : cls.trim();
        if (!p.isEmpty() && c.startsWith(".")) c = p + c;
        if (p.isEmpty() || c.isEmpty()) return "";
        return p + "/" + c;
    }

    public static String normKeyString(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash + 1 >= s.length()) return "";
        return normKey(s.substring(0, slash), s.substring(slash + 1));
    }

    @SuppressWarnings("unchecked")
    public static Config parseConfig(String jsonText, boolean exists) {
        Map<String, Object> m = Json.object(jsonText);
        boolean compact = !Boolean.FALSE.equals(m.get("compact_gaps"));
        Set<String> hidden = new LinkedHashSet<String>();
        Object rawHidden = m.get("hidden");
        if (rawHidden instanceof List) {
            for (Object o : (List<Object>) rawHidden) {
                if (o == null) continue;
                String k = normKeyString(String.valueOf(o));
                if (!k.isEmpty()) hidden.add(k);
            }
        }
        return new Config(exists, compact, hidden);
    }

    public static Config readSettingsConfig(File dataDir) {
        File sf = new File(dataDir, SETTINGS_REL);
        File pf = new File(dataDir, PUBLIC_REL);
        File target = sf.isFile() ? sf : pf.isFile() ? pf : null;
        if (target == null) return new Config(false, true, Collections.<String>emptySet());
        try {
            return parseConfig(Engine.read(target), true);
        } catch (Exception ignored) {
            return new Config(false, true, Collections.<String>emptySet());
        }
    }

    public static Config readPublicConfig(String rootPrefix) {
        String base = rootPrefix == null ? "" : rootPrefix;
        File pf = new File(base + "/data/hmgapi/" + PUBLIC_REL);
        File sf = new File(base + "/data/hmgapi/" + SETTINGS_REL);
        File target = pf.isFile() ? pf : sf.isFile() ? sf : null;
        if (target == null) return new Config(false, true, Collections.<String>emptySet());
        try {
            return parseConfig(Engine.read(target), true);
        } catch (Exception ignored) {
            return new Config(false, true, Collections.<String>emptySet());
        }
    }

    public static void publishPublicConfig(Engine e) throws IOException {
        File sf = new File(e.data, SETTINGS_REL);
        File pubDir = new File(e.data, "public");
        File pf = new File(pubDir, "launcher-tiles.json");
        if (!sf.isFile()) {
            if (pf.exists()) pf.delete();
            return;
        }
        Config cfg = readSettingsConfig(e.data);
        pubDir.mkdirs();
        File tmp = new File(pubDir, ".launcher-tiles.json.tmp");
        Engine.write(tmp, Json.write(configMap(cfg)));
        tmp.setReadable(true, false);
        if (!tmp.renameTo(pf)) throw new IOException("can't write public/launcher-tiles.json");
        LiveState.traversable(e.data);
        pubDir.setReadable(true, false);
        pubDir.setExecutable(true, false);
    }

    static Map<String, Object> configMap(Config cfg) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("compact_gaps", Boolean.valueOf(cfg.compactGaps));
        out.put("hidden", new ArrayList<Object>(cfg.hidden));
        return out;
    }

    @SuppressWarnings("unchecked")
    public static int get(Engine e) throws Exception {
        Config cfg = readSettingsConfig(e.data);
        File catFile = new File(e.root + CATALOG_REL);
        List<Object> tiles = new ArrayList<Object>();
        if (catFile.isFile()) {
            try {
                Map<String, Object> cat = Json.object(Engine.read(catFile));
                Object list = cat.get("tiles");
                if (list instanceof List) {
                    for (Object item : (List<Object>) list) {
                        if (!(item instanceof Map)) continue;
                        Map<String, Object> copy = new LinkedHashMap<String, Object>((Map<String, Object>) item);
                        String key = normKeyString(String.valueOf(copy.get("key")));
                        if (key.isEmpty()) {
                            key = normKey(str(copy.get("packageName")), str(copy.get("className")));
                        }
                        if (key.isEmpty()) continue;
                        copy.put("key", key);
                        copy.put("visible", Boolean.valueOf(!cfg.hidden.contains(key)));
                        tiles.add(copy);
                    }
                }
            } catch (Exception ignored) {
                // unreadable or corrupt catalog: return empty tiles list
            }
        }
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("configured", Boolean.valueOf(cfg.exists));
        out.put("compact_gaps", Boolean.valueOf(cfg.compactGaps));
        out.put("hidden", new ArrayList<Object>(cfg.hidden));
        out.put("tiles", tiles);
        e.out.add(Json.write(out));
        return 0;
    }

    @SuppressWarnings("unchecked")
    public static int set(Engine e, String rawJson) throws Exception {
        if (rawJson == null || rawJson.trim().isEmpty()) {
            e.out.add("usage: engine launcher-tiles-set <json>");
            return 2;
        }
        Map<String, Object> in;
        try {
            in = Json.object(rawJson);
        } catch (Exception x) {
            e.out.add("bad launcher-tiles json: " + x.getMessage());
            return 2;
        }
        if (in.containsKey("hidden") && !(in.get("hidden") instanceof List)) {
            e.out.add("bad launcher-tiles json: hidden must be a list");
            return 2;
        }
        Config cfg = parseConfig(rawJson, true);
        File sf = new File(e.data, SETTINGS_REL);
        Engine.write(sf, Json.write(configMap(cfg)));
        sf.setReadable(false, false);
        sf.setReadable(true, true);
        publishPublicConfig(e);
        if (e.host) {
            e.hostAction("broadcast " + ACTION);
        } else {
            try {
                Engine.exec("/system/bin/am", "broadcast", "-a", ACTION);
            } catch (Exception ignored) {
                // Android not up yet: launcher reads public/launcher-tiles.json when it starts
            }
        }
        e.out.add("saved: " + cfg.hidden.size() + " hidden");
        return 0;
    }

    /**
     * Synchronises one profile's all_menu JSON against active HMG-API mods and hidden-tile
     * settings, backing up hidden stock tiles and restoring them when unhidden.
     */
    @SuppressWarnings("unchecked")
    public static SyncResult syncProfileMenu(String user, String menuJson, Config cfg,
            List<ModTile> activeMods, Map<String, Object> allBackups) {
        List<String> logs = new ArrayList<String>();
        Map<String, Object> root = Json.object(menuJson);
        Object rawArr = root.get("menuItems");
        if (!(rawArr instanceof List)) return new SyncResult(null, false, logs);
        List<Object> arr = (List<Object>) rawArr;

        Map<String, ModTile> modByKey = new LinkedHashMap<String, ModTile>();
        Map<String, ModTile> modByClass = new LinkedHashMap<String, ModTile>();
        if (activeMods != null) {
            for (ModTile m : activeMods) {
                String k = normKey(m.packageName, m.className);
                if (!k.isEmpty()) modByKey.put(k, m);
                if (m.className != null) modByClass.put(m.className, m);
            }
        }

        Map<String, Object> profiles = ensureMap(allBackups, "profiles");
        Map<String, Object> userBackup = ensureMap(profiles, user);

        boolean changed = false;
        boolean backupChanged = false;

        // 1. Remove gone HMG-API mod items and keep active mod itemIds current
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            if (isEmptySlot(pkg, cls)) continue;
            int id = intVal(it.get("itemId"), -1);
            boolean ours = id >= ITEM_ID_BASE || id == ITEM_ID_LEGACY;
            String key = normKey(pkg, cls);
            ModTile m = modByKey.get(key);
            if (m == null) m = modByClass.get(cls);
            if (ours && m == null) {
                arr.set(k, emptySlotMap());
                changed = true;
                if (userBackup.remove(key) != null) backupChanged = true;
                logs.add("saved menu (" + user + "): removed " + cls + " (its mod is gone), slot " + (k + 1));
            } else if (m != null && id != m.itemId) {
                it.put("itemId", Long.valueOf(m.itemId));
                changed = true;
            }
        }

        // 2. Back up and clear any currently-present tile that is hidden
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            if (isEmptySlot(pkg, cls)) continue;
            int id = intVal(it.get("itemId"), -1);
            String key = normKey(pkg, cls);
            if (!cfg.hidden.contains(key)) continue;
            boolean ours = id >= ITEM_ID_BASE || id == ITEM_ID_LEGACY || modByKey.containsKey(key) || modByClass.containsKey(cls);
            if (!ours) {
                Map<String, Object> bak = new LinkedHashMap<String, Object>();
                bak.put("packageName", pkg);
                bak.put("itemId", Long.valueOf(id));
                bak.put("className", cls);
                bak.put("slot", Long.valueOf(k));
                userBackup.put(key, bak);
                backupChanged = true;
            }
            arr.set(k, emptySlotMap());
            changed = true;
            logs.add("saved menu (" + user + "): hid " + key + ", slot " + (k + 1));
        }

        // 3. Collect all non-empty keys currently placed on the grid
        Set<String> presentKeys = new LinkedHashSet<String>();
        Set<String> presentClasses = new LinkedHashSet<String>();
        for (int k = 0; k < arr.size(); k++) {
            if (!(arr.get(k) instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) arr.get(k);
            String pkg = str(it.get("packageName"));
            String cls = str(it.get("className"));
            if (isEmptySlot(pkg, cls)) continue;
            presentKeys.add(normKey(pkg, cls));
            presentClasses.add(cls);
        }

        // 4. Restore unhidden stock tiles from backup
        for (String key : new ArrayList<String>(userBackup.keySet())) {
            if (cfg.hidden.contains(key)) continue;
            Object rawBak = userBackup.get(key);
            if (!(rawBak instanceof Map)) {
                userBackup.remove(key);
                backupChanged = true;
                continue;
            }
            if (presentKeys.contains(key)) {
                userBackup.remove(key);
                backupChanged = true;
                continue;
            }
            Map<String, Object> bak = (Map<String, Object>) rawBak;
            String bPkg = str(bak.get("packageName"));
            String bCls = str(bak.get("className"));
            int bId = intVal(bak.get("itemId"), 0);
            int prefSlot = intVal(bak.get("slot"), -1);
            int target = -1;
            if (prefSlot >= 0 && prefSlot < arr.size() && slotEmpty(arr.get(prefSlot))) {
                target = prefSlot;
            } else {
                target = firstEmptySlot(arr);
            }
            if (target < 0) {
                logs.add("saved menu (" + user + "): no empty slot to restore " + key);
                continue;
            }
            Map<String, Object> restored = new LinkedHashMap<String, Object>();
            restored.put("packageName", bPkg);
            restored.put("itemId", Long.valueOf(bId));
            restored.put("className", bCls);
            arr.set(target, restored);
            presentKeys.add(key);
            presentClasses.add(bCls);
            userBackup.remove(key);
            backupChanged = true;
            changed = true;
            logs.add("saved menu (" + user + "): restored " + key + " in slot " + (target + 1));
        }

        // 5. Add visible active HMG-API mod tiles not yet on the grid
        if (activeMods != null) {
            for (ModTile m : activeMods) {
                String key = normKey(m.packageName, m.className);
                if (cfg.hidden.contains(key) || presentKeys.contains(key) || presentClasses.contains(m.className)) continue;
                int empty = firstEmptySlot(arr);
                if (empty < 0) {
                    logs.add("saved menu (" + user + "): no empty slot for " + m.className);
                    continue;
                }
                Map<String, Object> it = new LinkedHashMap<String, Object>();
                it.put("packageName", m.packageName);
                it.put("itemId", Long.valueOf(m.itemId));
                it.put("className", m.className);
                arr.set(empty, it);
                presentKeys.add(key);
                presentClasses.add(m.className);
                changed = true;
                logs.add("saved menu (" + user + "): added " + m.className + " in slot " + (empty + 1));
            }
        }

        // 6. Close empty gaps between remaining tiles when configured
        if (cfg.exists && cfg.compactGaps) {
            List<Object> nonEmpty = new ArrayList<Object>();
            for (int k = 0; k < arr.size(); k++) {
                if (!slotEmpty(arr.get(k))) nonEmpty.add(arr.get(k));
            }
            boolean hasGap = false;
            for (int k = 0; k < nonEmpty.size(); k++) {
                if (slotEmpty(arr.get(k))) {
                    hasGap = true;
                    break;
                }
            }
            if (hasGap) {
                int total = arr.size();
                arr.clear();
                arr.addAll(nonEmpty);
                while (arr.size() < total) arr.add(emptySlotMap());
                changed = true;
                logs.add("saved menu (" + user + "): compacted empty tile gaps");
            }
        }

        if (userBackup.isEmpty() && profiles.containsKey(user)) {
            profiles.remove(user);
        }

        return new SyncResult(changed ? writeCompact(root) : null, backupChanged, logs);
    }

    @SuppressWarnings("unchecked")
    static boolean slotEmpty(Object o) {
        if (!(o instanceof Map)) return true;
        Map<String, Object> m = (Map<String, Object>) o;
        return isEmptySlot(str(m.get("packageName")), str(m.get("className")));
    }

    static int firstEmptySlot(List<Object> arr) {
        for (int k = 0; k < arr.size(); k++) {
            if (slotEmpty(arr.get(k))) return k;
        }
        return -1;
    }

    public static Map<String, Object> emptySlotMap() {
        Map<String, Object> it = new LinkedHashMap<String, Object>();
        it.put("packageName", "Empty view");
        it.put("itemId", Long.valueOf(39));
        it.put("className", "Empty view");
        return it;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> ensureMap(Map<String, Object> parent, String key) {
        Object cur = parent.get(key);
        if (cur instanceof Map) return (Map<String, Object>) cur;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        parent.put(key, m);
        return m;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static int intVal(Object o, int def) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o).trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    /** Single-line compact JSON writer matching org.json.JSONObject.toString() for all_menu rows. */
    @SuppressWarnings("unchecked")
    public static String writeCompact(Object v) {
        StringBuilder b = new StringBuilder();
        appendCompact(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendCompact(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quoteCompact(b, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (n++ > 0) b.append(',');
                quoteCompact(b, e.getKey());
                b.append(':');
                appendCompact(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            List<Object> a = (List<Object>) v;
            for (int k = 0; k < a.size(); k++) {
                if (k > 0) b.append(',');
                appendCompact(b, a.get(k));
            }
            b.append(']');
        } else {
            quoteCompact(b, String.valueOf(v));
        }
    }

    private static void quoteCompact(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}

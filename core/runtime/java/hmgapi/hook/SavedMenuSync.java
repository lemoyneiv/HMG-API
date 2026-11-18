package hmgapi.hook;

import hmgapi.engine.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-profile saved-menu (HomeSetting all_menu) coordination for HMG-API launcher entries.
 *
 * Kept free of Android framework and org.json imports so off-car unit tests can exercise
 * seeding, upgrade migration, hidden-tile preservation, and mod removal/disable cleanup
 * directly on the host JVM.
 */
final class SavedMenuSync {
    private SavedMenuSync() {}

    static final String SEEDED_FILE = "hmgapi-seeded-menu.json";
    static final int ITEM_ID_BASE = 9000;
    static final int ITEM_ID_LEGACY = 90;
    static final int EMPTY_ITEM_ID = 39;
    static final String EMPTY_TOKEN = "Empty view";

    static final class Spec {
        final String packageName;
        final String className;
        final int itemId;
        final boolean active;

        Spec(String packageName, String className, int itemId, boolean active) {
            this.packageName = packageName;
            this.className = className;
            this.itemId = itemId;
            this.active = active;
        }
    }

    static final class ProfileResult {
        final String updatedMenuJson;
        final boolean seededChanged;
        final List<String> logs;

        ProfileResult(String updatedMenuJson, boolean seededChanged, List<String> logs) {
            this.updatedMenuJson = updatedMenuJson;
            this.seededChanged = seededChanged;
            this.logs = logs;
        }
    }

    /**
     * Syncs one profile's all_menu JSON against HMG-API's registered launcher entries:
     * - Active entries already on the grid are recorded in {@code seeded} (covering upgrades)
     *   and have stale itemIds updated in place without moving their slot.
     * - Active entries not on the grid and not yet in {@code seeded} are placed into the first
     *   "Empty view" slot and recorded in {@code seeded}.
     * - Active entries already in {@code seeded} that were later removed or hidden from the
     *   grid are NOT re-inserted on subsequent launcher starts.
     * - Entries whose mod was uninstalled or disabled have any HMG-API slot emptied and are
     *   removed from {@code seeded}, so re-installing or re-enabling seeds them cleanly again.
     */
    @SuppressWarnings("unchecked")
    static ProfileResult syncProfile(String user, String menuJson, List<Spec> specs, Set<String> seeded) {
        Map<String, Object> root = Json.object(menuJson);
        Object rawArr = root.get("menuItems");
        if (!(rawArr instanceof List)) {
            return new ProfileResult(null, false, Collections.<String>emptyList());
        }
        List<Object> arr = (List<Object>) rawArr;
        Map<String, Spec> byClass = new LinkedHashMap<String, Spec>();
        for (Spec s : specs) byClass.put(s.className, s);

        boolean menuChanged = false;
        boolean seededChanged = false;
        List<String> logs = new ArrayList<String>();
        Set<String> present = new HashSet<String>();

        for (int k = 0; k < arr.size(); k++) {
            Object elem = arr.get(k);
            if (!(elem instanceof Map)) continue;
            Map<String, Object> it = (Map<String, Object>) elem;
            int id = intVal(it.get("itemId"), -1);
            String cls = strVal(it.get("className"));
            boolean ours = id >= ITEM_ID_BASE || id == ITEM_ID_LEGACY;
            Spec m = byClass.get(cls);
            if (ours && (m == null || !m.active)) {
                arr.set(k, emptySlot());
                menuChanged = true;
                if (seeded.remove(cls)) seededChanged = true;
                logs.add("saved menu (" + user + "): removed " + cls + " (its mod is gone), slot " + (k + 1));
            } else if (m != null && m.active) {
                present.add(cls);
                if (seeded.add(cls)) seededChanged = true;
                if (id != m.itemId) {
                    it.put("itemId", Long.valueOf(m.itemId));
                    menuChanged = true;
                }
            }
        }

        for (Iterator<String> sit = seeded.iterator(); sit.hasNext();) {
            String cls = sit.next();
            Spec m = byClass.get(cls);
            if (m == null || !m.active) {
                sit.remove();
                seededChanged = true;
            }
        }

        for (Spec m : specs) {
            if (!m.active || present.contains(m.className) || seeded.contains(m.className)) continue;
            int empty = -1;
            for (int k = 0; k < arr.size() && empty < 0; k++) {
                Object elem = arr.get(k);
                if (elem instanceof Map && EMPTY_TOKEN.equals(strVal(((Map<String, Object>) elem).get("packageName")))) {
                    empty = k;
                }
            }
            if (empty < 0) {
                logs.add("saved menu (" + user + "): no empty slot for " + m.className);
                continue;
            }
            arr.set(empty, slot(m.packageName, m.itemId, m.className));
            seeded.add(m.className);
            seededChanged = true;
            menuChanged = true;
            logs.add("saved menu (" + user + "): added " + m.className + " in slot " + (empty + 1));
        }

        return new ProfileResult(menuChanged ? compactJson(root) : null, seededChanged, logs);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Set<String>> readSeeded(File file) {
        Map<String, Set<String>> out = new LinkedHashMap<String, Set<String>>();
        if (file == null || !file.isFile()) return out;
        try {
            Map<String, Object> root = Json.object(readUtf8(file));
            Object raw = root.get("seeded");
            if (!(raw instanceof Map)) return out;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) raw).entrySet()) {
                Set<String> set = new LinkedHashSet<String>();
                if (e.getValue() instanceof List) {
                    for (Object v : (List<Object>) e.getValue()) {
                        if (v != null) set.add(String.valueOf(v));
                    }
                }
                out.put(e.getKey(), set);
            }
        } catch (Exception ignored) {
            // Corrupt state must never prevent launcher menu sync; existing grid items re-record cleanly.
        }
        return out;
    }

    static void writeSeeded(File file, Map<String, Set<String>> seededByProfile) throws IOException {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Map<String, Object> seededObj = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Set<String>> e : seededByProfile.entrySet()) {
            seededObj.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("seeded", seededObj);
        byte[] bytes = (Json.write(root) + "\n").getBytes("UTF-8");
        File tmp = new File(parent, "." + file.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) {
            FileOutputStream direct = new FileOutputStream(file);
            try {
                direct.write(bytes);
                direct.getFD().sync();
            } finally {
                direct.close();
            }
            tmp.delete();
        }
    }

    private static Map<String, Object> emptySlot() {
        return slot(EMPTY_TOKEN, EMPTY_ITEM_ID, EMPTY_TOKEN);
    }

    private static Map<String, Object> slot(String packageName, int itemId, String className) {
        Map<String, Object> it = new LinkedHashMap<String, Object>();
        it.put("packageName", packageName);
        it.put("itemId", Long.valueOf(itemId));
        it.put("className", className);
        return it;
    }

    private static int intVal(Object o, int fallback) {
        return o instanceof Number ? ((Number) o).intValue() : fallback;
    }

    private static String strVal(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String readUtf8(File f) throws IOException {
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

    /** Single-line JSON matching org.json.JSONObject.toString() for the all_menu column. */
    @SuppressWarnings("unchecked")
    static String compactJson(Object v) {
        StringBuilder b = new StringBuilder();
        appendCompact(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendCompact(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean || v instanceof Number) {
            b.append(v);
        } else if (v instanceof Map) {
            b.append('{');
            int n = 0;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (n++ > 0) b.append(',');
                quote(b, e.getKey());
                b.append(':');
                appendCompact(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            List<Object> a = (List<Object>) v;
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) b.append(',');
                appendCompact(b, a.get(i));
            }
            b.append(']');
        } else {
            quote(b, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\t': b.append("\\t"); break;
                case '\r': b.append("\\r"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}

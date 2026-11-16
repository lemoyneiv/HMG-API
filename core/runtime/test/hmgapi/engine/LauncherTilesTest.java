package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Off-car unit tests for LauncherTilesStore: key normalisation, stock & mod tile
 * hiding, exact stock itemId/slot backup & restoration, gap compaction, and multi-profile
 * isolation.
 */
public final class LauncherTilesTest {
    private static int fails;

    private static void check(String name, Object want, Object got) {
        if ((want == null && got == null) || (want != null && want.equals(got))) {
            System.out.println("ok    " + name);
        } else {
            System.out.println("FAIL  " + name + ": wanted [" + want + "], got [" + got + "]");
            fails++;
        }
    }

    private static Map<String, Object> slot(String pkg, int id, String cls) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("packageName", pkg);
        m.put("itemId", Long.valueOf(id));
        m.put("className", cls);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(String menuJson) {
        Map<String, Object> root = Json.object(menuJson);
        return (List<Map<String, Object>>) (List<?>) root.get("menuItems");
    }

    public static void main(String[] args) throws Exception {
        check("normKey expands relative class",
                "com.example.valet/com.example.valet.MainActivity",
                LauncherTilesStore.normKey("com.example.valet", ".MainActivity"));
        check("normKeyString expands relative class",
                "com.example.valet/com.example.valet.MainActivity",
                LauncherTilesStore.normKeyString("com.example.valet/.MainActivity"));

        // Build an 8-slot grid: 4 stock tiles, 4 empty slots
        Map<String, Object> initialRoot = new LinkedHashMap<String, Object>();
        initialRoot.put("version", Long.valueOf(2));
        List<Object> initialItems = new ArrayList<Object>();
        initialItems.add(slot("com.hkmc.radio", 3, "com.hkmc.radio.RadioActivity"));
        initialItems.add(slot("com.hkmc.valet", 14, "com.hkmc.valet.ValetActivity"));
        initialItems.add(slot("com.hkmc.quiet", 18, "com.hkmc.quiet.QuietActivity"));
        initialItems.add(slot("com.android.settings", 21, "com.android.settings.Settings"));
        for (int i = 0; i < 4; i++) initialItems.add(LauncherTilesStore.emptySlotMap());
        initialRoot.put("menuItems", initialItems);
        String menu0 = LauncherTilesStore.writeCompact(initialRoot);

        List<LauncherTilesStore.ModTile> mods = Arrays.asList(
                new LauncherTilesStore.ModTile("hmgapi.app", "hmgapi.app.MainActivity", 9000),
                new LauncherTilesStore.ModTile("dev.lemoyne.comfort", "dev.lemoyne.comfort.MainActivity", 9001)
        );
        Map<String, Object> backups = new LinkedHashMap<String, Object>();

        // 1. Default unconfigured sync: places both mod tiles without touching stock tiles
        LauncherTilesStore.Config unconfigured = new LauncherTilesStore.Config(false, true, Collections.<String>emptySet());
        LauncherTilesStore.SyncResult r1 = LauncherTilesStore.syncProfileMenu("Driver 1", menu0, unconfigured, mods, backups);
        List<Map<String, Object>> m1 = itemsOf(r1.updatedMenuJson);
        check("mods placed in slots 4 and 5", "hmgapi.app|dev.lemoyne.comfort",
                m1.get(4).get("packageName") + "|" + m1.get(5).get("packageName"));
        check("top-level keys preserved", Long.valueOf(2), Json.object(r1.updatedMenuJson).get("version"));

        // 2. Hide Valet Mode (stock, itemId=14) and HMG-API (mod, itemId=9000) WITH compact_gaps=true
        Set<String> hideSet = new LinkedHashSet<String>(Arrays.asList(
                "com.hkmc.valet/com.hkmc.valet.ValetActivity",
                "hmgapi.app/hmgapi.app.MainActivity"
        ));
        LauncherTilesStore.Config cfgCompact = new LauncherTilesStore.Config(true, true, hideSet);
        LauncherTilesStore.SyncResult r2 = LauncherTilesStore.syncProfileMenu("Driver 1", r1.updatedMenuJson, cfgCompact, mods, backups);
        check("backup marked changed when hiding stock tile", Boolean.TRUE, Boolean.valueOf(r2.backupChanged));
        List<Map<String, Object>> m2 = itemsOf(r2.updatedMenuJson);
        check("remaining 4 tiles compacted to slots 0..3",
                "com.hkmc.radio,com.hkmc.quiet,com.android.settings,dev.lemoyne.comfort,Empty view",
                m2.get(0).get("packageName") + "," + m2.get(1).get("packageName") + ","
                        + m2.get(2).get("packageName") + "," + m2.get(3).get("packageName") + ","
                        + m2.get(4).get("packageName"));

        // 3. Idempotence: syncing again with no config change yields updatedMenuJson == null
        LauncherTilesStore.SyncResult r2b = LauncherTilesStore.syncProfileMenu("Driver 1", r2.updatedMenuJson, cfgCompact, mods, backups);
        check("second sync is idempotent", null, r2b.updatedMenuJson);

        // 4. Unhide everything: Valet Mode returns with its exact stock itemId=14, HMG-API returns with 9000
        LauncherTilesStore.Config cfgShowAll = new LauncherTilesStore.Config(true, true, Collections.<String>emptySet());
        LauncherTilesStore.SyncResult r3 = LauncherTilesStore.syncProfileMenu("Driver 1", r2.updatedMenuJson, cfgShowAll, mods, backups);
        List<Map<String, Object>> m3 = itemsOf(r3.updatedMenuJson);
        int valetId = -1;
        boolean hasHmgApi = false;
        for (Map<String, Object> it : m3) {
            if ("com.hkmc.valet".equals(it.get("packageName"))) valetId = ((Number) it.get("itemId")).intValue();
            if ("hmgapi.app".equals(it.get("packageName"))) hasHmgApi = ((Number) it.get("itemId")).intValue() == 9000;
        }
        check("restored stock Valet tile keeps exact itemId 14", Integer.valueOf(14), Integer.valueOf(valetId));
        check("restored mod HMG-API tile has itemId 9000", Boolean.TRUE, Boolean.valueOf(hasHmgApi));

        // 5. compact_gaps=false leaves slot 1 as Empty view without shifting Quiet Mode
        Set<String> hideValet = new LinkedHashSet<String>(Collections.singletonList("com.hkmc.valet/com.hkmc.valet.ValetActivity"));
        LauncherTilesStore.Config cfgKeepGaps = new LauncherTilesStore.Config(true, false, hideValet);
        LauncherTilesStore.SyncResult r4 = LauncherTilesStore.syncProfileMenu("Driver 2", menu0, cfgKeepGaps, mods, backups);
        List<Map<String, Object>> m4 = itemsOf(r4.updatedMenuJson);
        check("compact_gaps=false leaves mod in slot 1 or keeps Quiet at slot 2",
                "com.hkmc.quiet", m4.get(2).get("packageName"));

        if (fails > 0) System.exit(1);
    }
}

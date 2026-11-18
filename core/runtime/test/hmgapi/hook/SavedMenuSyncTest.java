package hmgapi.hook;

import hmgapi.engine.Json;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Off-car tests for Bridge priority ordering (PRIORITY_CORE_AFTER vs mod observe/change/replace)
 * and SavedMenuSync (seeding, upgrade migration, hidden-tile preservation, and mod disable/removal).
 */
public final class SavedMenuSyncTest {
    static int fail;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (!ok) fail = 1;
    }

    static MethodHook tagged(final String tag, int priority) {
        MethodHook h = new MethodHook() {
            @Override
            public String toString() {
                return tag;
            }
        };
        h.priority = priority;
        return h;
    }

    static String beforeOrder(MethodHook[] hooks) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < hooks.length; i++) {
            if (i > 0) b.append(',');
            b.append(hooks[i]);
        }
        return b.toString();
    }

    static String afterOrder(MethodHook[] hooks) {
        StringBuilder b = new StringBuilder();
        for (int i = hooks.length - 1; i >= 0; i--) {
            if (b.length() > 0) b.append(',');
            b.append(hooks[i]);
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> slotsOf(String json) {
        return (List<Map<String, Object>>) Json.object(json).get("menuItems");
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();

        // 1. Hook priority ordering: core after-hooks run before boot-loaded and late-loaded mod after-hooks,
        //    while replace hooks still run last in beforeHookedMethod.
        MethodHook[] hooks = new MethodHook[0];
        hooks = Bridge.insertHook(hooks, tagged("core", MethodHook.PRIORITY_CORE_AFTER));
        hooks = Bridge.insertHook(hooks, tagged("modA", MethodHook.PRIORITY_DEFAULT));
        hooks = Bridge.insertHook(hooks, tagged("rep", MethodHook.PRIORITY_REPLACE));
        hooks = Bridge.insertHook(hooks, tagged("liveModB", MethodHook.PRIORITY_DEFAULT));
        check("Bridge.insertHook before order: observe/change (incl. live), core, replace",
                "modA,liveModB,core,rep".equals(beforeOrder(hooks)));
        check("Bridge.insertHook after order (no replace): core populates items before mod after-hooks",
                "core,liveModB,modA".equals(afterOrder(Arrays.copyOf(hooks, 3))));

        // 2. Fresh seeding into first Empty view slots + compact JSON + file persistence
        String initial = "{\"menuItems\":["
                + "{\"packageName\":\"com.stock.radio\",\"itemId\":1,\"className\":\"com.stock.radio.Main\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        List<SavedMenuSync.Spec> specs = Arrays.asList(
                new SavedMenuSync.Spec("hmgapi.app", "hmgapi.app.MainActivity", 9000, true),
                new SavedMenuSync.Spec("dev.mod.one", "dev.mod.one.MainActivity", 9001, true));
        Set<String> seeded = new LinkedHashSet<String>();
        SavedMenuSync.ProfileResult r1 = SavedMenuSync.syncProfile("Driver 1", initial, specs, seeded);
        check("fresh active mods are seeded into Empty view slots",
                r1.updatedMenuJson != null && !r1.updatedMenuJson.contains("\n") && r1.seededChanged);
        List<Map<String, Object>> s1 = slotsOf(r1.updatedMenuJson);
        check("slot 0 stays stock, slots 1 and 2 get HMG-API and mod one",
                "com.stock.radio.Main".equals(s1.get(0).get("className"))
                        && "hmgapi.app.MainActivity".equals(s1.get(1).get("className"))
                        && Long.valueOf(9000).equals(s1.get(1).get("itemId"))
                        && "dev.mod.one.MainActivity".equals(s1.get(2).get("className"))
                        && Long.valueOf(9001).equals(s1.get(2).get("itemId")));

        File seededFile = new File(dir, SavedMenuSync.SEEDED_FILE);
        Map<String, Set<String>> persisted = new LinkedHashMap<String, Set<String>>();
        persisted.put("Driver 1", seeded);
        SavedMenuSync.writeSeeded(seededFile, persisted);
        Map<String, Set<String>> reloaded = SavedMenuSync.readSeeded(seededFile);
        check("seeded state round-trips through hmgapi-seeded-menu.json",
                reloaded.get("Driver 1") != null
                        && reloaded.get("Driver 1").contains("hmgapi.app.MainActivity")
                        && reloaded.get("Driver 1").contains("dev.mod.one.MainActivity"));

        // 3. Previously seeded active mod removed/hidden from grid is NOT re-inserted on restart
        String hiddenModOne = "{\"menuItems\":["
                + "{\"packageName\":\"com.stock.radio\",\"itemId\":1,\"className\":\"com.stock.radio.Main\"},"
                + "{\"packageName\":\"hmgapi.app\",\"itemId\":9000,\"className\":\"hmgapi.app.MainActivity\"},"
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"}"
                + "]}";
        SavedMenuSync.ProfileResult r2 = SavedMenuSync.syncProfile("Driver 1", hiddenModOne, specs,
                reloaded.get("Driver 1"));
        check("hidden/removed active mod tile is not re-inserted on launcher restart",
                r2.updatedMenuJson == null && !r2.seededChanged);

        // 4. Upgrade from older HMG-API (empty seeded set): existing tiles stay put, stale itemId updates in place
        String legacyGrid = "{\"menuItems\":["
                + "{\"packageName\":\"Empty view\",\"itemId\":39,\"className\":\"Empty view\"},"
                + "{\"packageName\":\"dev.mod.one\",\"itemId\":90,\"className\":\"dev.mod.one.MainActivity\"},"
                + "{\"packageName\":\"hmgapi.app\",\"itemId\":9000,\"className\":\"hmgapi.app.MainActivity\"}"
                + "]}";
        Set<String> upgradeSeeded = new LinkedHashSet<String>();
        SavedMenuSync.ProfileResult r3 = SavedMenuSync.syncProfile("Driver 2", legacyGrid, specs, upgradeSeeded);
        List<Map<String, Object>> s3 = slotsOf(r3.updatedMenuJson);
        check("upgrade records existing grid tiles as seeded without moving them and updates legacy itemId",
                r3.seededChanged
                        && upgradeSeeded.size() == 2
                        && "Empty view".equals(s3.get(0).get("packageName"))
                        && "dev.mod.one.MainActivity".equals(s3.get(1).get("className"))
                        && Long.valueOf(9001).equals(s3.get(1).get("itemId")));

        // 5. Disabling or uninstalling a mod empties its HMG-API slot and forgets its seeded flag,
        //    so re-enabling later seeds it again cleanly (while stock items are untouched).
        List<SavedMenuSync.Spec> modOneOff = Arrays.asList(
                new SavedMenuSync.Spec("hmgapi.app", "hmgapi.app.MainActivity", 9000, true),
                new SavedMenuSync.Spec("dev.mod.one", "dev.mod.one.MainActivity", 9001, false));
        SavedMenuSync.ProfileResult r4 = SavedMenuSync.syncProfile("Driver 1", r1.updatedMenuJson, modOneOff, seeded);
        List<Map<String, Object>> s4 = slotsOf(r4.updatedMenuJson);
        check("disabled mod has its HMG-API slot emptied and is pruned from seeded",
                "Empty view".equals(s4.get(2).get("packageName"))
                        && "com.stock.radio.Main".equals(s4.get(0).get("className"))
                        && !seeded.contains("dev.mod.one.MainActivity")
                        && r4.seededChanged);
        SavedMenuSync.ProfileResult r5 = SavedMenuSync.syncProfile("Driver 1", r4.updatedMenuJson, specs, seeded);
        check("re-enabled mod is seeded back into an Empty view slot",
                r5.updatedMenuJson != null
                        && "dev.mod.one.MainActivity".equals(slotsOf(r5.updatedMenuJson).get(2).get("className"))
                        && seeded.contains("dev.mod.one.MainActivity"));

        // 6. Full grid with no Empty view slot does not mark unplaced mod as seeded
        String fullGrid = "{\"menuItems\":["
                + "{\"packageName\":\"com.stock.radio\",\"itemId\":1,\"className\":\"com.stock.radio.Main\"}"
                + "]}";
        Set<String> fullSeeded = new LinkedHashSet<String>();
        SavedMenuSync.ProfileResult r6 = SavedMenuSync.syncProfile("Guest", fullGrid, specs, fullSeeded);
        check("full grid leaves unplaced mod unseeded so a freed slot can receive it later",
                r6.updatedMenuJson == null && fullSeeded.isEmpty() && !r6.logs.isEmpty());

        // 7. Corrupt seeded file falls back to empty map without throwing
        File broken = new File(dir, "broken.json");
        FileOutputStream bad = new FileOutputStream(broken);
        bad.write("{not json".getBytes("UTF-8"));
        bad.close();
        check("corrupt seeded file is ignored safely", SavedMenuSync.readSeeded(broken).isEmpty());

        System.exit(fail);
    }
}

// Stand-ins so Bridge.java compiles on a desktop JVM alongside MethodHook and SavedMenuSync.
final class HmgApi {
    static void zygoteInit() {}
}

final class SelfTest {
    static void selfTest() {}
}

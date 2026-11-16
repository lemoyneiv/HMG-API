package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;

import hmgapi.engine.Json;
import hmgapi.engine.LauncherTilesStore;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live home-screen launcher and menu-editor tile filtering, catalog recording,
 * and saved-menu grid synchronisation.
 */
final class LauncherTiles {
    private LauncherTiles() {}

    static final String CATALOG_FILE = "hmgapi-launcher-catalog.json";
    static final String BACKUP_FILE = "hmgapi-hidden-backup.json";

    static final class EntryMeta {
        final String key;
        final String pkg;
        final String cls;
        final String title;
        final String kind;
        final String mod;

        EntryMeta(String key, String pkg, String cls, String title, String kind, String mod) {
            this.key = key;
            this.pkg = pkg;
            this.cls = cls;
            this.title = title;
            this.kind = kind;
            this.mod = mod;
        }
    }

    private static final Map<Object, EntryMeta> metaByEntry = new WeakHashMap<Object, EntryMeta>();
    private static final Object SYNC_LOCK = new Object();
    private static final AtomicBoolean launcherReloadPending = new AtomicBoolean(false);
    private static volatile boolean launcherReceiverStarted;
    private static volatile boolean editorReceiverStarted;

    private static ClassLoader editorLoader;
    private static List<Object> liveEditorList;
    private static List<Object> allEditorItems;

    static void hookEntryCtor(ClassLoader cl, String pointId) {
        Points.hook(cl, pointId, new MethodHook() {
            @Override
            protected void afterHookedMethod(Param param) {
                try {
                    if (param.thisObject == null || param.args == null || param.args.length < 7) return;
                    Context ctx = param.args[1] instanceof Context ? (Context) param.args[1] : HmgApi.appContext;
                    String pkg = param.args[2] == null ? "" : String.valueOf(param.args[2]);
                    String cls = param.args[3] == null ? "" : String.valueOf(param.args[3]);
                    int titleId = param.args[6] instanceof Integer ? ((Integer) param.args[6]).intValue() : 0;
                    EntryMeta meta = buildMeta(ctx, pkg, cls, titleId);
                    synchronized (metaByEntry) {
                        metaByEntry.put(param.thisObject, meta);
                    }
                } catch (Throwable t) {
                    HmgApi.e("launcher tile ctor hook", t);
                }
            }
        });
    }

    private static EntryMeta buildMeta(Context ctx, String pkg, String cls, int titleId) {
        String key = LauncherTilesStore.normKey(pkg, cls);
        Mods.LauncherEntry modEntry = HmgApi.entryForResource(titleId);
        if (modEntry == null) modEntry = HmgApi.entryForClass(cls);

        String title = null;
        if (modEntry != null) {
            CharSequence cs = HmgApi.label(modEntry);
            if (cs != null) title = cs.toString();
        }
        if ((title == null || title.trim().isEmpty()) && ctx != null && titleId != 0) {
            try {
                CharSequence cs = ctx.getResources().getText(titleId);
                if (cs != null) title = cs.toString();
            } catch (Throwable ignored) {}
        }
        if ((title == null || title.trim().isEmpty()) && ctx != null && !pkg.isEmpty() && !cls.isEmpty()) {
            try {
                String fullCls = cls.startsWith(".") ? pkg + cls : cls;
                PackageManager pm = ctx.getPackageManager();
                CharSequence cs = pm.getActivityInfo(new ComponentName(pkg, fullCls), 0).loadLabel(pm);
                if (cs != null) title = cs.toString();
            } catch (Throwable ignored) {}
        }
        if (title == null || title.trim().isEmpty()) {
            int dot = cls.lastIndexOf('.');
            title = dot >= 0 && dot + 1 < cls.length() ? cls.substring(dot + 1) : (!cls.isEmpty() ? cls : pkg);
        }
        title = title.replace('\n', ' ').replace('\r', ' ').replaceAll(" +", " ").trim();

        boolean isNemesis = "com.nemesis.manager".equals(pkg)
                || pkg.startsWith("com.nemesis.")
                || title.toUpperCase(Locale.US).contains("NEMESIS");
        String kind = (modEntry != null || isNemesis) ? "package" : "system";
        String mod = modEntry != null ? modEntry.mod : (isNemesis ? "nemesis" : null);
        return new EntryMeta(key, pkg, cls, title, kind, mod);
    }

    private static EntryMeta metaOf(Object entry) {
        synchronized (metaByEntry) {
            return metaByEntry.get(entry);
        }
    }

    static int onLauncherLoaded(Context ctx, List<Object> list, Object lock) {
        if (ctx != null) startLauncherReceiver(ctx);
        Map<String, Map<String, Object>> catalogByKey = new LinkedHashMap<String, Map<String, Object>>();
        synchronized (lock) {
            for (Object item : list) {
                EntryMeta m = metaOf(item);
                if (m == null || m.key.isEmpty() || catalogByKey.containsKey(m.key)) continue;
                Map<String, Object> row = new LinkedHashMap<String, Object>();
                row.put("key", m.key);
                row.put("packageName", m.pkg);
                row.put("className", m.cls);
                row.put("title", m.title);
                row.put("kind", m.kind);
                if (m.mod != null) row.put("mod", m.mod);
                catalogByKey.put(m.key, row);
            }
        }
        if (ctx != null && !catalogByKey.isEmpty()) {
            try {
                Map<String, Object> doc = new LinkedHashMap<String, Object>();
                doc.put("tiles", new ArrayList<Object>(catalogByKey.values()));
                writeAtomic(new File(ctx.getFilesDir(), CATALOG_FILE), Json.write(doc));
            } catch (Throwable t) {
                HmgApi.e("launcher tile catalog", t);
            }
        }

        LauncherTilesStore.Config cfg = LauncherTilesStore.readPublicConfig("");
        if (cfg.hidden.isEmpty()) return 0;
        int removed = 0;
        synchronized (lock) {
            for (Iterator<Object> it = list.iterator(); it.hasNext();) {
                EntryMeta m = metaOf(it.next());
                if (m != null && !m.key.isEmpty() && cfg.hidden.contains(m.key)) {
                    it.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) HmgApi.i("launcher: hid " + removed + " tile(s)");
        return removed;
    }

    static int onEditorLoaded(Context ctx, ClassLoader cl, List<Object> list) {
        if (ctx != null) startEditorReceiver(ctx);
        synchronized (list) {
            editorLoader = cl;
            liveEditorList = list;
            allEditorItems = new ArrayList<Object>(list);
            return filterEditorLocked(cl, list);
        }
    }

    private static int filterEditorLocked(ClassLoader cl, List<Object> list) {
        if (allEditorItems == null) return 0;
        list.clear();
        LauncherTilesStore.Config cfg = LauncherTilesStore.readPublicConfig("");
        int removed = 0;
        for (Object item : allEditorItems) {
            EntryMeta m = metaOf(item);
            String key = m != null ? m.key : "";
            if (key.isEmpty() && cl != null) {
                try {
                    String cls = (String) Points.call(cl, "homesetting.edit_entry.get_class_name", item);
                    if (cls != null) {
                        Mods.LauncherEntry me = HmgApi.entryForClass(cls);
                        if (me != null) key = LauncherTilesStore.normKey(me.packageName, me.className);
                    }
                } catch (Throwable ignored) {}
            }
            if (!key.isEmpty() && cfg.hidden.contains(key)) {
                removed++;
            } else {
                list.add(item);
            }
        }
        if (removed > 0) HmgApi.i("editor: hid " + removed + " tile(s)");
        return removed;
    }

    static void startLauncherReceiver(Context ctx) {
        if (ctx == null || launcherReceiverStarted) return;
        synchronized (LauncherTiles.class) {
            if (launcherReceiverStarted) return;
            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (!launcherReloadPending.compareAndSet(false, true)) return;
                    new Thread("hmgapi-tiles-sync") {
                        @Override
                        public void run() {
                            try {
                                Thread.sleep(150);
                                syncSavedMenu(appCtx);
                            } catch (Throwable t) {
                                HmgApi.e("launcher live tile sync", t);
                            } finally {
                                launcherReloadPending.set(false);
                            }
                            HmgApi.i("launcher: restarting process to apply tile visibility changes");
                            android.os.Process.killProcess(android.os.Process.myPid());
                        }
                    }.start();
                }
            }, new IntentFilter(LauncherTilesStore.ACTION));
            launcherReceiverStarted = true;
        }
    }

    static void startEditorReceiver(Context ctx) {
        if (ctx == null || editorReceiverStarted) return;
        synchronized (LauncherTiles.class) {
            if (editorReceiverStarted) return;
            final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            appCtx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    List<Object> list = liveEditorList;
                    if (list == null) return;
                    synchronized (list) {
                        try {
                            filterEditorLocked(editorLoader, list);
                        } catch (Throwable t) {
                            HmgApi.e("editor live tile filter", t);
                        }
                    }
                }
            }, new IntentFilter(LauncherTilesStore.ACTION));
            editorReceiverStarted = true;
        }
    }

    @SuppressWarnings("unchecked")
    static void syncSavedMenu(Context ctx) {
        if (ctx == null) return;
        synchronized (SYNC_LOCK) {
            ContentResolver cr = ctx.getContentResolver();
            Cursor cur = null;
            try {
                List<LauncherTilesStore.ModTile> activeMods = new ArrayList<LauncherTilesStore.ModTile>();
                for (Mods.LauncherEntry m : Mods.launcherEntries()) {
                    if (HmgApi.installed(ctx, m)) {
                        activeMods.add(new LauncherTilesStore.ModTile(m.packageName, m.className, HmgApi.itemId(m)));
                    }
                }
                LauncherTilesStore.Config cfg = LauncherTilesStore.readPublicConfig("");
                File bakFile = new File(ctx.getFilesDir(), BACKUP_FILE);
                Map<String, Object> backups = new LinkedHashMap<String, Object>();
                if (bakFile.isFile()) {
                    try {
                        backups = Json.object(Mods.read(bakFile));
                    } catch (Throwable ignored) {
                        backups = new LinkedHashMap<String, Object>();
                    }
                }

                cur = cr.query(HmgApi.ALL_MENU, null, null, null, null);
                if (cur == null) return;
                int userCol = cur.getColumnIndex("userName"), itemsCol = cur.getColumnIndex("menu_items");
                boolean backupChanged = false;
                while (cur.moveToNext()) {
                    String user = cur.getString(userCol);
                    String json = cur.getString(itemsCol);
                    if (user == null || json == null) continue;
                    LauncherTilesStore.SyncResult res = LauncherTilesStore.syncProfileMenu(user, json, cfg, activeMods, backups);
                    for (String line : res.logs) HmgApi.i(line);
                    if (res.backupChanged) backupChanged = true;
                    if (res.updatedMenuJson != null) {
                        ContentValues v = new ContentValues();
                        v.put("menu_items", res.updatedMenuJson);
                        cr.update(HmgApi.ALL_MENU, v, "userName=?", new String[] { user });
                    }
                }
                if (backupChanged) {
                    writeAtomic(bakFile, Json.write(backups));
                }
            } catch (Throwable t) {
                HmgApi.e("saved menu", t);
            } finally {
                if (cur != null) cur.close();
            }
        }
    }

    private static void writeAtomic(File dst, String content) throws Exception {
        File dir = dst.getParentFile();
        if (dir != null) dir.mkdirs();
        File tmp = new File(dst.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(dst)) throw new java.io.IOException("can't rename " + tmp + " to " + dst);
    }
}

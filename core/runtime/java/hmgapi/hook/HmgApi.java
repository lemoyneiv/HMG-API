package hmgapi.hook;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/**
 * HMG-API's own hooks. In the zygote: load the installed mods (Mods) and hook
 * ActivityThread.handleBindApplication (every app start: package, process,
 * class loader). In the launcher and the home-screen editor (HomeSetting): the
 * mods' launcher entries in their all-apps menu.
 *
 * Launcher_WP3 builds that list in com.android.launcher3.view.a.g.load() as
 * a/Q(g, Context, package, class, iconId, disabledIconId, titleId) entries
 * with ids from its own resources, and drops any saved menu item that isn't on
 * the list. HMG-API adds a Q per entry with made-up ids (0x7f7e00xx, a resource
 * type the launcher doesn't have) and answers Resources.getDrawable/getText for
 * those ids with the target activity's own icon and label. Launching is the
 * launcher's: it starts MAIN/LAUNCHER on the entry's package and class.
 *
 * HomeSetting (Edit menu) has the same design: entries allmenuedit/c(z,
 * Context, package, class, ids) in the static list allmenuedit/z.cs filled
 * by z.load(), matched against the saved menu by package and class. HMG-API
 * adds the same entries there, so the editor can show, move and save them.
 *
 * The saved menu (HomeSetting provider all_menu, one JSON row per profile) is
 * synced at the launcher's first menu load: a new entry goes into the first
 * "Empty view" slot (the owner's placement is kept after that), and a HMG-API
 * item (itemId >= 9000) whose mod is gone becomes an empty slot again.
 */
final class HmgApi {
    private HmgApi() {}

    static void i(String msg) { Bridge.log(4, msg); }

    static void e(String msg, Throwable t) { Bridge.log(6, msg + ": " + Bridge.trace(t)); }

    /**
     * In the zygote: this car's hook map first (CarMap). Without a good one, HMG-API's
     * hooks and the mods stay off; the only hook left is the one that tells the
     * HMG-API app why (Android's own names, the same on every car).
     */
    @SuppressWarnings("unchecked")
    static void zygoteInit() {
        java.util.Map<String, Object> lj = null;
        try {
            java.io.File f = new java.io.File(Mods.SETTINGS, "hmgapi.json");
            if (f.isFile()) lj = hmgapi.engine.Json.object(Mods.read(f));
        } catch (Exception x) {
            e("hmgapi.json", x);
        }
        Points.map = hmgapi.engine.CarMap.load("", new hmgapi.engine.SystemCheck.Facts("",
                lj == null ? null : (java.util.Map<String, Object>) lj.get("car")), lj);
        i(Points.map.describe());
        if (!Points.ready() || !Points.needAll("hooks", "framework.activity_thread.handle_bind_application",
                "framework.app_bind_data.app_info", "framework.app_bind_data.process_name", "framework.activity_thread.initial_application")) {
            statusOnly();
            return;
        }
        Mods.load();
        try {
            Input.zygote();   // mods' button bindings, in system_server
        } catch (Throwable t) {
            e("input", t);
        }
        Points.hook(null, "framework.activity_thread.handle_bind_application",
                new MethodHook() {
                    @Override
                    protected void beforeHookedMethod(Param param) {
                        // before the app's own code runs (Application.onCreate comes inside this method)
                        try {
                            Object data = param.args[0];
                            String pkg = ((android.content.pm.ApplicationInfo) Points.get(null, "framework.app_bind_data.app_info", data)).packageName;
                            if (Updates.OTA.equals(pkg)) {
                                Updates.otaProcess(new LoadedApp(pkg, (String) Points.get(null, "framework.app_bind_data.process_name", data), null, null));
                            }
                        } catch (Throwable t) {
                            e("handleBindApplication before hook", t);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(Param param) {
                        try {
                            Object data = param.args[0];
                            String pkg = ((android.content.pm.ApplicationInfo) Points.get(null, "framework.app_bind_data.app_info", data)).packageName;
                            String proc = (String) Points.get(null, "framework.app_bind_data.process_name", data);
                            Object app = Points.get(null, "framework.activity_thread.initial_application", param.thisObject);
                            ClassLoader cl = app == null ? null : (ClassLoader) Hooks.callMethod(app, "getClassLoader");
                            appLoaded(new LoadedApp(pkg, proc, cl, (Context) app));
                        } catch (Throwable t) {
                            e("handleBindApplication hook", t);
                        }
                    }
                });
        i("zygote: hooked ActivityThread.handleBindApplication");
    }

    /** HMG-API is off on this car: only the HMG-API app hears why. */
    static void statusOnly() {
        Hooks.findAndHookMethod(Hooks.findClass("android.app.ActivityThread", null), "handleBindApplication",
                "android.app.ActivityThread$AppBindData", new MethodHook() {
                    @Override
                    protected void afterHookedMethod(Param param) {
                        try {
                            Object appInfo = Hooks.getObjectField(param.args[0], "appInfo");
                            if (!Mods.APP_PACKAGE.equals(Hooks.getObjectField(appInfo, "packageName"))) return;
                            Object app = Hooks.getObjectField(param.thisObject, "mInitialApplication");
                            ClassLoader cl = (ClassLoader) Hooks.callMethod(app, "getClassLoader");
                            Hooks.findClass("hmgapi.app.HmgApiStatus", cl).getField("json").set(null, Mods.statusJson(Updates.NOT_BLOCKED));
                        } catch (Throwable t) {
                            e("status for the HMG-API app", t);
                        }
                    }
                });
    }

    static void appLoaded(LoadedApp app) {
        i("app " + app.packageName + " (" + app.processName + ")");
        if (app.classLoader == null) return;
        try {
            // always, even with no entries: the launcher's sync also clears items of removed mods
            // each feature hooks one app's points: all of them mapped on this car, or the feature is off
            if ("com.android.launcher3".equals(app.packageName)) {
                if (Points.need("launcher entries", "launcher")) launcher(app.classLoader);
                if (app.context != null) LauncherTiles.startLauncherReceiver(app.context);
            } else if ("com.hkmc.system.app.homesetting".equals(app.packageName)) {
                if (Points.need("menu editor entries", "homesetting")) editor(app.classLoader);
                if (app.context != null) LauncherTiles.startEditorReceiver(app.context);
            } else if ("com.android.buttonsettings".equals(app.packageName)) {
                if (Points.need("star key choices", "buttonsettings")) StarKey.settingsScreens(app.classLoader);
            } else if (Panel.PACKAGE.equals(app.packageName)) {
                if (Points.need("side panel pages", "panel")) Panel.panel(app);
            } else if ("com.android.themesettings".equals(app.packageName)) {
                if (Points.need("split-screen editor", "themesettings")) Panel.editor(app.classLoader);
            } else if ("com.android.settings".equals(app.packageName)) {
                if (Points.need("split-screen settings", "settings")) Panel.settings(app.classLoader);
            } else if ("com.google.android.projection.sink".equals(app.packageName)) {
                if (Points.need("Android Auto projection", "gpm")) ProjectionHooks.gpm(app);
            } else if ("com.daudio.app.dipo".equals(app.packageName)) {
                if (Points.need("CarPlay projection", "dipo")) ProjectionHooks.dipo(app);
            } else if (BleHooks.PACKAGE.equals(app.packageName)) {
                BleHooks.init(app);
            }
            else if (Mods.APP_PACKAGE.equals(app.packageName)) {
                Hooks.findClass("hmgapi.app.HmgApiStatus", app.classLoader).getField("json")
                        .set(null, Mods.statusJson(Updates.state()));
            }
            if ("com.android.launcher3".equals(app.packageName) && app.context != null) {
                StarKey.start(app.context);
                Updates.apply(app.context);
            }
        } catch (Throwable t) {
            e(app.packageName + " hooks", t);
        }
        Mods.appLoaded(app);
        try {
            Driving.appLoaded(app);
        } catch (Throwable t) {
            e("driving restriction in " + app.processName, t);
        }
        if (app.context != null) {
            try {
                Live.start(app);   // mods on/off and settings without a restart
            } catch (Throwable t) {
                e("live state in " + app.processName, t);
            }
        }
    }

    static final int ITEM_ID_BASE = 9000;   // HMG-API's saved items; neither app indexes with itemId
    static final int ITEM_ID_LEGACY = 90;   // the first test build's NEMESIS item
    static final Uri ALL_MENU = Uri.parse("content://com.hkmc.system.app.homesetting.provider/all_menu");

    static volatile Context appContext;   // this process's context, for icons and labels
    static boolean savedSynced;

    static void launcher(ClassLoader cl) throws Exception {
        final ClassLoader loader = cl;
        final Constructor<?> newEntry = Points.ctor(cl, "launcher.menu_entry.new");
        hookEntryResources();
        LauncherTiles.hookEntryCtor(cl, "launcher.menu_entry.new");

        Points.hook(cl, "launcher.menu_list.load", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param param) throws Throwable {
                Context ctx = (Context) Points.call(loader, "launcher.menu_list.get_context", param.thisObject);
                appContext = ctx;
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) Points.get(loader, "launcher.menu_list.items", param.thisObject);
                if (list == null) return;
                Object lock = Points.get(loader, "launcher.menu_list.lock", param.thisObject);
                int added = 0;
                synchronized (lock) {
                    for (Mods.LauncherEntry m : Mods.launcherEntries()) {
                        if (!installed(ctx, m)) continue;
                        list.add(newEntry.newInstance(param.thisObject, ctx, m.packageName, m.className, m.icon,
                                m.iconDisabled, m.titleId));
                        added++;
                    }
                }
                int hidden = LauncherTiles.onLauncherLoaded(ctx, list, lock);
                i("launcher: menu list loaded (" + param.getResult() + "), added " + added + ", hid " + hidden
                        + ", " + list.size() + " visible entries");
                if (!savedSynced) {   // load() runs on the launcher's background task
                    savedSynced = true;
                    syncSavedMenu(ctx);
                }
            }
        });
        i("launcher: hooked menu list and entry resources");
    }

    static void editor(ClassLoader cl) throws Exception {
        final ClassLoader loader = cl;
        final Constructor<?> newEntry = Points.ctor(cl, "homesetting.edit_entry.new");
        final Field cs = Points.field(cl, "homesetting.edit_list.items");
        hookEntryResources();
        LauncherTiles.hookEntryCtor(cl, "homesetting.edit_entry.new");

        Points.hook(cl, "homesetting.edit_list.load", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param param) throws Throwable {
                Context ctx = (Context) Points.call(loader, "homesetting.edit_list.get_context", param.thisObject);
                appContext = ctx;
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) cs.get(null);
                if (list == null) return;
                int added = 0;
                synchronized (list) {
                    for (Mods.LauncherEntry m : Mods.launcherEntries()) {
                        if (!installed(ctx, m) || listed(loader, list, m)) continue;
                        list.add(newEntry.newInstance(param.thisObject, ctx, m.packageName, m.className, m.icon,
                                m.iconDisabled, m.titleId));
                        added++;
                    }
                }
                int hidden = LauncherTiles.onEditorLoaded(ctx, loader, list);
                i("editor: menu list loaded (" + param.getResult() + "), added " + added + ", hid " + hidden
                        + ", " + list.size() + " visible entries");
            }
        });
        i("editor: hooked menu list and entry resources");
    }

    static boolean listed(ClassLoader cl, List<Object> list, Mods.LauncherEntry m) {
        for (Object o : list) {
            if (m.className.equals(Points.call(cl, "homesetting.edit_entry.get_class_name", o))) return true;
        }
        return false;
    }

    static boolean installed(Context ctx, Mods.LauncherEntry m) {
        try {
            ctx.getPackageManager().getActivityInfo(new ComponentName(m.packageName, m.className), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            i("[" + m.mod + "] launcher entry " + m.className + " not installed, skipped");
            return false;
        }
    }

    static Mods.LauncherEntry entryForClass(String cls) {
        for (Mods.LauncherEntry m : Mods.launcherEntries()) {
            if (m.className.equals(cls)) return m;
        }
        return null;
    }

    /** Adds missing entries to each profile's saved menu, hides/restores configured tiles, and empties gone mods. */
    static void syncSavedMenu(Context ctx) {
        LauncherTiles.syncSavedMenu(ctx);
    }

    static int itemId(Mods.LauncherEntry m) {
        return ITEM_ID_BASE + Mods.launcherEntries().indexOf(m);
    }

    static Mods.LauncherEntry entryForResource(int id) {
        if ((id & 0xffff0000) != 0x7f7e0000) return null;
        for (Mods.LauncherEntry m : Mods.launcherEntries()) {
            if (id == m.icon || id == m.iconDisabled || id == m.titleId) return m;
        }
        return null;
    }

    static boolean resourcesHooked;

    static synchronized void hookEntryResources() {
        if (resourcesHooked) return;
        resourcesHooked = true;
        if (!Points.needAll("HMG-API's pictures and labels", "framework.resources.get_drawable", "framework.resources.get_text")) return;
        Points.hook(null, "framework.resources.get_drawable", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param param) {
                int id = (Integer) param.args[0];
                Mods.StarAction a = StarKey.forResource(id);
                if (a != null) {
                    param.setResult(StarKey.icon(a));
                    return;
                }
                Mods.LauncherEntry m = entryForResource(id);
                if (m == null) return;
                Drawable d = icon(m, id == m.iconDisabled);
                // never let a made-up id reach the real lookup: it would throw NotFoundException
                param.setResult(d != null ? d
                        : Resources.getSystem().getDrawable(android.R.drawable.sym_def_app_icon));
            }
        });
        Points.hook(null, "framework.resources.get_text", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param param) {
                int id = (Integer) param.args[0];
                if (id == Panel.TITLE_ID) {
                    param.setResult(Panel.slotTitle(Panel.pages()));
                    return;
                }
                Mods.StarAction a = StarKey.forResource(id);
                if (a != null) {
                    param.setResult(StarKey.text(a, id));
                    return;
                }
                Mods.LauncherEntry m = entryForResource(id);
                if (m == null) return;
                CharSequence t = label(m);
                param.setResult(t != null ? t : "HMG-API");
            }
        });
    }

    static Drawable icon(Mods.LauncherEntry m, boolean disabled) {
        Context ctx = appContext;
        if (ctx == null) return null;
        // the mod's own pictures: the night one while the car's display is in night mode
        // (the launcher's own icons switch the same way, by drawable-night resources)
        boolean night = (ctx.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        byte[] pic = null;
        if (disabled) {
            pic = night && m.iconDisabledNight != null ? m.iconDisabledNight : m.iconDisabledDay;
            if (pic == null && Mods.APP_PACKAGE.equals(m.packageName)) {
                // HMG-API's own greyed-out tile is in its app (ic_hmgapi_disabled, day and night)
                try {
                    Resources r = ctx.getPackageManager().getResourcesForApplication(Mods.APP_PACKAGE);
                    int rid = r.getIdentifier("ic_hmgapi_disabled", "drawable", Mods.APP_PACKAGE);
                    if (rid != 0) return r.getDrawable(rid);
                } catch (Exception e) {
                    e("[" + m.mod + "] disabled entry icon", e);
                }
            }
        }
        if (pic == null) pic = night && m.iconNight != null ? m.iconNight : m.iconDay;
        if (pic != null) {
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(pic, 0, pic.length);
            if (b != null) return new android.graphics.drawable.BitmapDrawable(ctx.getResources(), b);
            i("[" + m.mod + "] tile picture isn't a PNG/JPEG; using the app's icon");
        }
        try {
            Drawable d = ctx.getPackageManager().getActivityIcon(new ComponentName(m.packageName, m.className));
            Drawable.ConstantState cs = d.getConstantState();
            return cs != null ? cs.newDrawable(ctx.getResources()) : d;   // one per view
        } catch (Exception e) {
            e("[" + m.mod + "] entry icon", e);
            return null;
        }
    }

    static CharSequence label(Mods.LauncherEntry m) {
        if (m.title != null) return m.title;
        Context ctx = appContext;
        if (ctx == null) return null;
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getActivityInfo(new ComponentName(m.packageName, m.className), 0).loadLabel(pm);
        } catch (Exception e) {
            e("[" + m.mod + "] entry label", e);
            return null;
        }
    }
}

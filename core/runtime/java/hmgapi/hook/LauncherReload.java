package hmgapi.hook;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import hmgapi.engine.Json;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-killing Home Screen reload support inside com.android.launcher3.
 *
 * Tracks the Launcher home activity itself (resolved via CATEGORY_HOME on the launcher
 * package, never other activities in the process) via ActivityLifecycleCallbacks on
 * getApplicationContext(), notifies HomeSetting's all_menu observers, resets
 * HmgApi.savedSynced, and coalesces a main-thread Activity.recreate() once the Launcher
 * is in the background (deferring until onStop if the owner is looking at the home screen).
 */
final class LauncherReload {
    private LauncherReload() {}

    private static final String DEFAULT_LAUNCHER_CLASS = "com.android.launcher3.Launcher";

    private static volatile Context appContext;
    private static volatile LauncherReloadState state;
    private static volatile Handler worker;
    private static volatile Handler mainHandler;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final Set<String> everHadTile = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static final Map<String, String> checkedVersions =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    private static final LauncherReloadState.Out OUT = new LauncherReloadState.Out() {
        @Override
        public boolean isAlive(Object activity) {
            if (!(activity instanceof Activity)) return false;
            Activity a = (Activity) activity;
            return !a.isFinishing() && !a.isDestroyed();
        }

        @Override
        public void notifyMenuChanged() {
            Context ctx = appContext;
            if (ctx == null) return;
            HmgApi.savedSynced = false;
            try {
                ctx.getContentResolver().notifyChange(HmgApi.ALL_MENU, null);
            } catch (Throwable t) {
                HmgApi.e("launcher reload notifyChange", t);
            }
        }

        @Override
        public void postMain(Runnable r) {
            Handler h = mainHandler;
            if (h == null) mainHandler = h = new Handler(Looper.getMainLooper());
            h.post(r);
        }

        @Override
        public void recreate(Object activity) {
            if (!(activity instanceof Activity)) return;
            Activity target = (Activity) activity;
            try {
                target.recreate();
                HmgApi.i("launcher: recreated activity in background for live menu update");
            } catch (Throwable t) {
                HmgApi.e("launcher activity recreate", t);
            }
        }
    };

    private static final Runnable initTask = new Runnable() {
        @Override
        public void run() {
            LauncherReloadState s = state;
            Map<String, Object> mods = readStateMods();
            if (s != null && mods != null) s.seedModState(tileModIds(mods), mods);
        }
    };

    private static final Runnable broadcastTask = new Runnable() {
        @Override
        public void run() {
            if (Live.app != null) Live.sync();
            LauncherReloadState s = state;
            Map<String, Object> mods = readStateMods();
            if (s != null && mods != null) s.onLiveMods(tileModIds(mods), mods);
        }
    };

    static void start(Context ctx) {
        if (ctx == null || !started.compareAndSet(false, true)) return;
        Context app = ctx.getApplicationContext();
        if (app == null) app = ctx;
        if (!(app instanceof Application)) {
            HmgApi.i("launcher: can't register lifecycle callbacks ("
                    + app.getClass().getName() + " isn't an Application)");
            return;
        }
        appContext = app;
        mainHandler = new Handler(Looper.getMainLooper());
        final LauncherReloadState st = new LauncherReloadState(
                resolveLauncherClasses(app), initialModState(), OUT);
        state = st;

        ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity act, Bundle b) {
                if (act != null) st.onActivityCreated(act, act.getClass().getName());
            }

            @Override
            public void onActivityStarted(Activity act) {
                if (act != null) st.onActivityStarted(act, act.getClass().getName());
            }

            @Override
            public void onActivityResumed(Activity act) {
                if (act != null) st.onActivityResumed(act, act.getClass().getName());
            }

            @Override
            public void onActivityPaused(Activity act) {
                if (act != null) st.onActivityPaused(act, act.getClass().getName());
            }

            @Override
            public void onActivityStopped(Activity act) {
                if (act != null) st.onActivityStopped(act, act.getClass().getName());
            }

            @Override
            public void onActivitySaveInstanceState(Activity act, Bundle b) {}

            @Override
            public void onActivityDestroyed(Activity act) {
                if (act != null) st.onActivityDestroyed(act, act.getClass().getName());
            }
        });

        HandlerThread ht = new HandlerThread("hmgapi-launcher-reload");
        ht.start();
        Handler w = new Handler(ht.getLooper());
        worker = w;
        w.post(initTask);

        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Handler h = worker;
                if (h != null) {
                    h.removeCallbacks(broadcastTask);
                    h.post(broadcastTask);
                }
            }
        }, new IntentFilter(Live.ACTION));
    }

    static boolean request() {
        LauncherReloadState s = state;
        if (s == null || appContext == null) return false;
        return s.request();
    }

    private static Set<String> resolveLauncherClasses(Context app) {
        Set<String> out = new HashSet<String>();
        try {
            PackageManager pm = app.getPackageManager();
            if (pm != null) {
                Intent home = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage(app.getPackageName());
                List<ResolveInfo> list = pm.queryIntentActivities(home, 0);
                if (list != null) {
                    for (ResolveInfo ri : list) {
                        ActivityInfo ai = ri == null ? null : ri.activityInfo;
                        if (ai == null) continue;
                        addClass(out, ai.packageName, ai.targetActivity);
                        addClass(out, ai.packageName, ai.name);
                    }
                }
            }
        } catch (Throwable t) {
            HmgApi.e("launcher: home activity lookup", t);
        }
        if (out.isEmpty()) out.add(DEFAULT_LAUNCHER_CLASS);
        return out;
    }

    private static void addClass(Set<String> out, String pkg, String cls) {
        if (cls == null || cls.length() == 0) return;
        out.add(cls.charAt(0) == '.' && pkg != null ? pkg + cls : cls);
    }

    private static Map<String, Boolean> initialModState() {
        Map<String, Boolean> out = new HashMap<String, Boolean>();
        for (Mods.LauncherEntry e : Mods.launcherEntries()) {
            if ("hmgapi".equals(e.mod)) continue;
            everHadTile.add(e.mod);
            out.put(e.mod, Boolean.TRUE);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> tileModIds(Map<String, Object> mods) {
        for (Mods.LauncherEntry e : Mods.launcherEntries()) {
            if (!"hmgapi".equals(e.mod)) everHadTile.add(e.mod);
        }
        for (Map.Entry<String, Object> entry : mods.entrySet()) {
            String id = entry.getKey();
            if ("hmgapi".equals(id)) continue;
            Object raw = entry.getValue();
            String ver = raw instanceof Map ? String.valueOf(((Map<String, Object>) raw).get("version")) : "?";
            if (!ver.equals(checkedVersions.get(id))) {
                if (declaresLauncherTile(id)) everHadTile.add(id);
                checkedVersions.put(id, ver);
            }
        }
        return new HashSet<String>(everHadTile);
    }

    private static boolean declaresLauncherTile(String id) {
        try {
            File mj = new File(new File(Mods.MODS, id), "mod.json");
            if (!mj.isFile()) return false;
            JSONObject mod = new JSONObject(Mods.read(mj));
            JSONObject provides = mod.optJSONObject("provides");
            JSONArray entries = provides == null ? null : provides.optJSONArray("launcher");
            return entries != null && entries.length() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readStateMods() {
        try {
            if (!Live.STATE.isFile()) return null;
            Map<String, Object> doc = Json.object(Mods.read(Live.STATE));
            Object rawMods = doc.get("mods");
            return rawMods instanceof Map ? (Map<String, Object>) rawMods : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}

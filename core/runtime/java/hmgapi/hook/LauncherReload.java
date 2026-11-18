package hmgapi.hook;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import hmgapi.engine.Json;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-killing Home Screen reload support inside com.android.launcher3.
 *
 * Tracks the live Launcher Activity from process start via ActivityLifecycleCallbacks
 * (so even late-loaded "live" mods can recreate it while Launcher is paused behind
 * another app), notifies HomeSetting's all_menu observers, resets HmgApi.savedSynced,
 * and coalesces a main-thread Activity.recreate() call instead of killing the process.
 */
final class LauncherReload {
    private LauncherReload() {}

    private static volatile Context appContext;
    private static volatile WeakReference<Activity> currentActivity;
    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean pendingRecreate = new AtomicBoolean(false);
    private static Map<String, Boolean> lastLauncherModState;

    static void start(Context ctx) {
        if (ctx == null) return;
        appContext = ctx;
        if (!started.compareAndSet(false, true)) return;
        if (ctx instanceof Application) {
            ((Application) ctx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity act, Bundle b) {
                    currentActivity = new WeakReference<Activity>(act);
                }

                @Override
                public void onActivityStarted(Activity act) {
                    currentActivity = new WeakReference<Activity>(act);
                }

                @Override
                public void onActivityResumed(Activity act) {
                    currentActivity = new WeakReference<Activity>(act);
                }

                @Override
                public void onActivityPaused(Activity act) {}

                @Override
                public void onActivityStopped(Activity act) {}

                @Override
                public void onActivitySaveInstanceState(Activity act, Bundle b) {}

                @Override
                public void onActivityDestroyed(Activity act) {
                    WeakReference<Activity> ref = currentActivity;
                    if (ref != null && ref.get() == act) currentActivity = null;
                }
            });
        }
        lastLauncherModState = readLauncherModState();
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                new Thread("hmgapi-launcher-reload") {
                    @Override
                    public void run() {
                        onLiveStateBroadcast();
                    }
                }.start();
            }
        }, new IntentFilter(Live.ACTION));
    }

    static boolean request() {
        Context ctx = appContext;
        if (ctx == null) return false;
        HmgApi.savedSynced = false;
        try {
            ctx.getContentResolver().notifyChange(HmgApi.ALL_MENU, null);
        } catch (Throwable t) {
            HmgApi.e("launcher reload notifyChange", t);
        }
        Activity act = liveActivity();
        if (act == null) return false;
        if (pendingRecreate.compareAndSet(false, true)) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    pendingRecreate.set(false);
                    Activity target = liveActivity();
                    if (target == null) return;
                    try {
                        target.recreate();
                        HmgApi.i("launcher: recreated activity for live menu update");
                    } catch (Throwable t) {
                        HmgApi.e("launcher activity recreate", t);
                    }
                }
            });
        }
        return true;
    }

    private static Activity liveActivity() {
        WeakReference<Activity> ref = currentActivity;
        Activity act = ref == null ? null : ref.get();
        if (act == null || act.isFinishing() || act.isDestroyed()) return null;
        return act;
    }

    private static synchronized void onLiveStateBroadcast() {
        Map<String, Boolean> now = readLauncherModState();
        if (now == null) return;
        Map<String, Boolean> prev = lastLauncherModState;
        lastLauncherModState = now;
        if (prev != null && !prev.equals(now)) {
            request();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> readLauncherModState() {
        try {
            if (!Live.STATE.isFile()) return null;
            Map<String, Object> state = Json.object(Mods.read(Live.STATE));
            Object rawMods = state.get("mods");
            if (!(rawMods instanceof Map)) return null;
            Map<String, Object> mods = (Map<String, Object>) rawMods;
            Map<String, Boolean> out = new HashMap<String, Boolean>();
            for (Mods.LauncherEntry e : Mods.launcherEntries()) {
                if ("hmgapi".equals(e.mod)) continue;
                Object ms = mods.get(e.mod);
                boolean on = ms instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) ms).get("enabled"));
                out.put(e.mod, Boolean.valueOf(on));
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }
}

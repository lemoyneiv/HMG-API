package hmgapi.hook;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Plain-Java state machine for LauncherReload (tested off-car in LauncherReloadTest):
 * - tracks only the Launcher home activity instance (matched by class name, never other
 *   activities in the com.android.launcher3 process);
 * - diffs public/state.json so toggling a mod with a launcher tile requests one reload
 *   while other mods or setting edits request none;
 * - coalesces concurrent or repeated reload requests into a single main-thread recreate,
 *   and while the Launcher activity is visible on screen, defers that recreate until the
 *   Launcher stops (goes to the background) so the home screen never flashes under the owner.
 */
final class LauncherReloadState {
    interface Out {
        boolean isAlive(Object activity);
        void notifyMenuChanged();
        void postMain(Runnable r);
        void recreate(Object activity);
    }

    private final Set<String> launcherClasses;
    private final Out out;

    private WeakReference<Object> launcherRef;
    private boolean visible;
    private boolean deferred;
    private boolean mainScheduled;
    private Map<String, Boolean> lastModState;

    LauncherReloadState(Set<String> launcherClasses, Map<String, Boolean> initialModState, Out out) {
        this.launcherClasses = new HashSet<String>(
                launcherClasses == null ? Collections.<String>emptySet() : launcherClasses);
        this.lastModState = initialModState == null ? null : new HashMap<String, Boolean>(initialModState);
        this.out = out;
    }

    boolean isLauncherClass(String className) {
        return className != null && launcherClasses.contains(className);
    }

    synchronized void onActivityCreated(Object act, String className) {
        if (!isLauncherClass(className)) return;
        launcherRef = new WeakReference<Object>(act);
        deferred = false;
    }

    synchronized void onActivityStarted(Object act, String className) {
        if (!isLauncherClass(className)) return;
        launcherRef = new WeakReference<Object>(act);
        visible = true;
    }

    synchronized void onActivityResumed(Object act, String className) {
        if (!isLauncherClass(className)) return;
        launcherRef = new WeakReference<Object>(act);
        visible = true;
    }

    synchronized void onActivityPaused(Object act, String className) {
        // Still visible behind translucent dialogs until onActivityStopped; wait for stop.
    }

    synchronized void onActivityStopped(Object act, String className) {
        if (!isLauncherClass(className)) return;
        Object cur = liveLauncher();
        if (cur != null && cur != act) return;
        visible = false;
        if (deferred && cur != null) {
            deferred = false;
            scheduleMainRecreateLocked();
        }
    }

    synchronized void onActivityDestroyed(Object act, String className) {
        if (!isLauncherClass(className)) return;
        WeakReference<Object> ref = launcherRef;
        if (ref != null && ref.get() == act) {
            launcherRef = null;
            visible = false;
        }
    }

    /** Seeds the baseline mod state at startup without triggering a reload. */
    @SuppressWarnings("unchecked")
    synchronized void seedModState(Set<String> tileMods, Map<String, Object> mods) {
        if (mods == null || tileMods == null) return;
        lastModState = snapshotModState(tileMods, mods);
    }

    /**
     * Diffs enabled state of mods that provide launcher tiles.
     * Returns true if a tile-bearing mod changed state and a reload was requested.
     */
    boolean onLiveMods(Set<String> tileMods, Map<String, Object> mods) {
        if (mods == null || tileMods == null) return false;
        Map<String, Boolean> now = snapshotModState(tileMods, mods);
        boolean changed;
        synchronized (this) {
            if (lastModState == null) {
                lastModState = now;
                return false;
            }
            changed = !lastModState.equals(now);
            lastModState = now;
        }
        if (changed) {
            request();
            return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> snapshotModState(Set<String> tileMods, Map<String, Object> mods) {
        Map<String, Boolean> now = new HashMap<String, Boolean>();
        for (String id : tileMods) {
            if ("hmgapi".equals(id)) continue;
            Object raw = mods.get(id);
            boolean on = raw instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) raw).get("enabled"));
            now.put(id, Boolean.valueOf(on));
        }
        return now;
    }

    boolean request() {
        out.notifyMenuChanged();
        synchronized (this) {
            Object cur = liveLauncher();
            if (cur == null) return false;
            if (visible) {
                deferred = true;
                return true;
            }
            deferred = false;
            scheduleMainRecreateLocked();
            return true;
        }
    }

    private void scheduleMainRecreateLocked() {
        if (mainScheduled) return;
        mainScheduled = true;
        out.postMain(new Runnable() {
            @Override
            public void run() {
                Object target;
                synchronized (LauncherReloadState.this) {
                    mainScheduled = false;
                    target = liveLauncher();
                    if (target == null) return;
                    if (visible) {
                        deferred = true;
                        return;
                    }
                    deferred = false;
                }
                out.recreate(target);
            }
        });
    }

    private Object liveLauncher() {
        WeakReference<Object> ref = launcherRef;
        Object act = ref == null ? null : ref.get();
        if (act == null || !out.isAlive(act)) return null;
        return act;
    }
}

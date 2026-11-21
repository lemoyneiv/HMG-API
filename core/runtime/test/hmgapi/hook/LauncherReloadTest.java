package hmgapi.hook;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Off-car tests for LauncherReloadState:
 * - tracks only the Launcher home activity class (never other activities in the process);
 * - diffs public/state.json so toggling a mod with a tile triggers one reload while
 *   non-tile mods and setting changes trigger none;
 * - coalesces reload requests and defers Activity.recreate() while Launcher is visible
 *   until onActivityStopped moves it to the background.
 */
public final class LauncherReloadTest {
    private static final String LAUNCHER = "com.android.launcher3.Launcher";
    private static final String OTHER = "com.android.launcher3.SettingsActivity";

    static final class FakeActivity {
        final String label;
        boolean alive = true;

        FakeActivity(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static final class Harness implements LauncherReloadState.Out {
        final List<Runnable> mainQueue = new ArrayList<Runnable>();
        final List<String> recreated = new ArrayList<String>();
        int notified;
        int mainPosts;

        @Override
        public boolean isAlive(Object activity) {
            return activity instanceof FakeActivity && ((FakeActivity) activity).alive;
        }

        @Override
        public void notifyMenuChanged() {
            notified++;
        }

        @Override
        public void postMain(Runnable r) {
            mainPosts++;
            mainQueue.add(r);
        }

        @Override
        public void recreate(Object activity) {
            recreated.add(String.valueOf(activity));
        }

        void drainMain() {
            while (!mainQueue.isEmpty()) {
                Runnable r = mainQueue.remove(0);
                r.run();
            }
        }

        void resetCounts() {
            notified = 0;
            mainPosts = 0;
            recreated.clear();
        }
    }

    static int fail;

    static void check(String what, Object want, Object got) {
        if (String.valueOf(want).equals(String.valueOf(got))) {
            System.out.println("ok    " + what);
        } else {
            System.out.println("FAIL  " + what + ": wanted [" + want + "], got [" + got + "]");
            fail = 1;
        }
    }

    static Set<String> setOf(String... items) {
        return new HashSet<String>(Arrays.asList(items));
    }

    static Map<String, Object> modEntry(boolean enabled, String settingVal) {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("opt", settingVal);
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("enabled", Boolean.valueOf(enabled));
        m.put("version", "1.0.0");
        m.put("settings", s);
        return m;
    }

    static Map<String, Object> modsMap(boolean comfortOn, boolean adbOn, String comfortSetting) {
        Map<String, Object> mods = new LinkedHashMap<String, Object>();
        mods.put("comfort", modEntry(comfortOn, comfortSetting));
        mods.put("adb", modEntry(adbOn, "default"));
        return mods;
    }

    public static void main(String[] args) {
        testStateDiffInBackground();
        testIgnoresOtherActivitiesInProcess();
        testDefersWhileVisibleUntilStopped();
        testCoalescingAndResumeRace();
        testUncreatedDestroyedAndFinishingActivity();
        System.exit(fail);
    }

    private static void testStateDiffInBackground() {
        Harness h = new Harness();
        Set<String> tileMods = setOf("comfort");
        LauncherReloadState st = new LauncherReloadState(setOf(LAUNCHER), null, h);

        FakeActivity home = new FakeActivity("home1");
        st.onActivityCreated(home, LAUNCHER);
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityResumed(home, LAUNCHER);
        st.onActivityPaused(home, LAUNCHER);
        st.onActivityStopped(home, LAUNCHER);

        // Initial seed records baseline without triggering any reload.
        st.seedModState(tileMods, modsMap(true, true, "v1"));
        h.drainMain();
        check("seed baseline: no recreate", "[]", h.recreated);
        check("seed baseline: no notify", Integer.valueOf(0), Integer.valueOf(h.notified));

        // Toggling a non-tile mod ("adb") triggers nothing.
        boolean r1 = st.onLiveMods(tileMods, modsMap(true, false, "v1"));
        h.drainMain();
        check("non-tile mod toggled: returns false", Boolean.FALSE, Boolean.valueOf(r1));
        check("non-tile mod toggled: 0 recreates", "[]", h.recreated);
        check("non-tile mod toggled: 0 notifies", Integer.valueOf(0), Integer.valueOf(h.notified));

        // Changing settings on a tile mod without changing enabled triggers nothing.
        boolean r2 = st.onLiveMods(tileMods, modsMap(true, false, "v2"));
        h.drainMain();
        check("tile mod settings-only edit: returns false", Boolean.FALSE, Boolean.valueOf(r2));
        check("tile mod settings-only edit: 0 recreates", "[]", h.recreated);

        // Toggling a tile mod ("comfort": true -> false) while Launcher is in background recreates once.
        boolean r3 = st.onLiveMods(tileMods, modsMap(false, false, "v2"));
        h.drainMain();
        check("tile mod disabled in background: returns true", Boolean.TRUE, Boolean.valueOf(r3));
        check("tile mod disabled in background: 1 notify", Integer.valueOf(1), Integer.valueOf(h.notified));
        check("tile mod disabled in background: 1 main post", Integer.valueOf(1), Integer.valueOf(h.mainPosts));
        check("tile mod disabled in background: 1 recreate", "[home1]", h.recreated);

        // Duplicate broadcast with identical state does nothing.
        h.resetCounts();
        boolean r4 = st.onLiveMods(tileMods, modsMap(false, false, "v2"));
        h.drainMain();
        check("duplicate state broadcast: 0 recreates", Boolean.FALSE, Boolean.valueOf(r4));
        check("duplicate state broadcast: empty", "[]", h.recreated);

        // Toggling the tile mod back on ("comfort": false -> true) recreates once.
        boolean r5 = st.onLiveMods(tileMods, modsMap(true, false, "v2"));
        h.drainMain();
        check("tile mod re-enabled in background: 1 recreate", Boolean.TRUE, Boolean.valueOf(r5));
        check("tile mod re-enabled target", "[home1]", h.recreated);

        // Uninstalling a tile mod (absent from mods map) recreates once, then settles.
        h.resetCounts();
        Map<String, Object> withoutComfort = new LinkedHashMap<String, Object>();
        withoutComfort.put("adb", modEntry(false, "default"));
        boolean r6 = st.onLiveMods(tileMods, withoutComfort);
        h.drainMain();
        check("tile mod removed: 1 recreate", Boolean.TRUE, Boolean.valueOf(r6));
        check("tile mod removed target", "[home1]", h.recreated);
        h.resetCounts();
        st.onLiveMods(tileMods, withoutComfort);
        h.drainMain();
        check("tile mod stays removed: 0 further recreates", "[]", h.recreated);
    }

    private static void testIgnoresOtherActivitiesInProcess() {
        Harness h = new Harness();
        LauncherReloadState st = new LauncherReloadState(setOf(LAUNCHER),
                Collections.<String, Boolean>singletonMap("comfort", Boolean.TRUE), h);

        // Only a non-Launcher activity exists: request returns false and never recreates it.
        FakeActivity settings = new FakeActivity("settings");
        st.onActivityCreated(settings, OTHER);
        st.onActivityStarted(settings, OTHER);
        st.onActivityResumed(settings, OTHER);
        boolean beforeHome = st.request();
        h.drainMain();
        check("other activity alone: request returns false", Boolean.FALSE, Boolean.valueOf(beforeHome));
        check("other activity alone: never recreated", "[]", h.recreated);

        // Launcher created and stopped; SettingsActivity started on top in same process.
        FakeActivity home = new FakeActivity("home");
        st.onActivityCreated(home, LAUNCHER);
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityStopped(home, LAUNCHER);
        st.onActivityStarted(settings, OTHER);
        st.onActivityResumed(settings, OTHER);

        h.resetCounts();
        boolean ok = st.request();
        h.drainMain();
        check("Launcher in background behind other activity: recreates Launcher immediately",
                Boolean.TRUE, Boolean.valueOf(ok));
        check("only Launcher instance recreated", "[home]", h.recreated);
    }

    private static void testDefersWhileVisibleUntilStopped() {
        Harness h = new Harness();
        Set<String> tileMods = setOf("comfort");
        LauncherReloadState st = new LauncherReloadState(setOf(LAUNCHER),
                Collections.singletonMap("comfort", Boolean.TRUE), h);

        FakeActivity home = new FakeActivity("home");
        st.onActivityCreated(home, LAUNCHER);
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityResumed(home, LAUNCHER);

        // Toggling a tile mod while the owner is looking at Launcher defers recreate.
        boolean req = st.onLiveMods(tileMods, modsMap(false, true, "v1"));
        h.drainMain();
        check("visible Launcher: request accepted", Boolean.TRUE, Boolean.valueOf(req));
        check("visible Launcher: all_menu notified immediately", Integer.valueOf(1), Integer.valueOf(h.notified));
        check("visible Launcher: 0 main posts while visible", Integer.valueOf(0), Integer.valueOf(h.mainPosts));
        check("visible Launcher: 0 recreates while visible", "[]", h.recreated);

        // Translucent dialog pauses Launcher without stopping it: still visible, still deferred.
        st.onActivityPaused(home, LAUNCHER);
        h.drainMain();
        check("paused (translucent overlay): still 0 recreates", "[]", h.recreated);

        // Navigating away stops Launcher -> deferred recreate fires once in background.
        st.onActivityStopped(home, LAUNCHER);
        h.drainMain();
        check("stopped into background: 1 deferred recreate", "[home]", h.recreated);

        // Subsequent stop without new changes does not recreate again.
        h.resetCounts();
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityStopped(home, LAUNCHER);
        h.drainMain();
        check("next stop without change: 0 recreates", "[]", h.recreated);

        // If Android itself recreates Launcher (e.g. day/night config change) before stop,
        // the pending deferred recreate is cleared because onCreate already loaded fresh menus.
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityResumed(home, LAUNCHER);
        st.request();
        FakeActivity home2 = new FakeActivity("home2");
        st.onActivityDestroyed(home, LAUNCHER);
        st.onActivityCreated(home2, LAUNCHER);
        st.onActivityStarted(home2, LAUNCHER);
        st.onActivityStopped(home2, LAUNCHER);
        h.drainMain();
        check("config-change onCreate clears redundant deferred recreate", "[]", h.recreated);
    }

    private static void testCoalescingAndResumeRace() {
        Harness h = new Harness();
        LauncherReloadState st = new LauncherReloadState(setOf(LAUNCHER), null, h);
        FakeActivity home = new FakeActivity("home");
        st.onActivityCreated(home, LAUNCHER);
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityStopped(home, LAUNCHER);

        // Burst of 3 reload requests in background before main thread runs -> 1 post, 1 recreate.
        st.request();
        st.request();
        st.request();
        check("burst in background: 3 notifies", Integer.valueOf(3), Integer.valueOf(h.notified));
        check("burst in background: coalesced to 1 main post", Integer.valueOf(1), Integer.valueOf(h.mainPosts));
        h.drainMain();
        check("burst in background: coalesced to 1 recreate", "[home]", h.recreated);

        // Burst while visible -> 0 posts while visible, 1 recreate on stop.
        h.resetCounts();
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityResumed(home, LAUNCHER);
        st.request();
        st.request();
        check("burst while visible: 0 main posts", Integer.valueOf(0), Integer.valueOf(h.mainPosts));
        st.onActivityStopped(home, LAUNCHER);
        h.drainMain();
        check("burst while visible: 1 recreate on stop", "[home]", h.recreated);

        // Race: queued while stopped, but owner returns to Launcher before main Runnable executes.
        h.resetCounts();
        st.request();
        check("race setup: 1 main post queued", Integer.valueOf(1), Integer.valueOf(h.mainPosts));
        st.onActivityStarted(home, LAUNCHER);
        st.onActivityResumed(home, LAUNCHER);
        h.drainMain();
        check("race guard: aborted immediate recreate because Launcher became visible", "[]", h.recreated);
        st.onActivityStopped(home, LAUNCHER);
        h.drainMain();
        check("race guard: deferred recreate ran once Launcher stopped again", "[home]", h.recreated);
    }

    private static void testUncreatedDestroyedAndFinishingActivity() {
        Harness h = new Harness();
        LauncherReloadState st = new LauncherReloadState(setOf(LAUNCHER), null, h);

        check("before create: returns false", Boolean.FALSE, Boolean.valueOf(st.request()));
        h.drainMain();
        check("before create: 0 recreates", "[]", h.recreated);

        FakeActivity home = new FakeActivity("home");
        st.onActivityCreated(home, LAUNCHER);
        home.alive = false;   // finishing/destroyed
        check("finishing activity: returns false", Boolean.FALSE, Boolean.valueOf(st.request()));
        h.drainMain();
        check("finishing activity: 0 recreates", "[]", h.recreated);

        home.alive = true;
        st.onActivityDestroyed(home, LAUNCHER);
        check("destroyed activity: returns false", Boolean.FALSE, Boolean.valueOf(st.request()));
        h.drainMain();
        check("destroyed activity: 0 recreates", "[]", h.recreated);
    }
}

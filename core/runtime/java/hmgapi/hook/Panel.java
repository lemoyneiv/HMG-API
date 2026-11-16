package hmgapi.hook;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mods' pages in the right-hand split-screen panel (mod.json provides.panel). Each
 * page is a standard Android app widget from one of the mod's own apps
 * (AppWidgetProvider + RemoteViews: live updates and taps work as on any home
 * screen), shown in HMG-API's page of the panel. The owner adds or removes that page
 * in the car's own split-screen editor like any other; with several mod pages, the
 * page's header switches between them.
 *
 * The panel (com.mobis.splitscreen.right) has 23 fixed page slots; slot 22 belongs to
 * Mobis' unused test page (its getView returns null, its field is never set, its
 * switch is always off). HMG-API takes that slot: it sets the field to its own page
 * and answers the switch "on" only inside the two methods that build slot 22, so no
 * stock page or list changes. Settings and the editor learn id 22 only while an
 * enabled mod has a page.
 */
final class Panel {
    private Panel() {}

    static final int SLOT = 22;
    static final String SLOT_ID = "22";
    static final String PACKAGE = "com.mobis.splitscreen.right";
    static final int HOST_ID = 0x484d47;   // "HMG"
    static final String[] LISTS = { "ss_list", "ss_list_dr1", "ss_list_dr2" };
    static final String[] CHOSEN = { "ss_setting", "ss_setting_dr1", "ss_setting_dr2" };

    static final class Page {
        String mod, title;
        ComponentName widget;
    }

    /** enabled mods' pages, from public/state.json (any process may read it) */
    static List<Page> pages() {
        List<Page> out = new ArrayList<Page>();
        try {
            JSONObject mods = new JSONObject(Mods.read(Live.STATE)).optJSONObject("mods");
            for (Iterator<?> it = mods == null ? null : mods.keys(); it != null && it.hasNext(); ) {
                String id = String.valueOf(it.next());
                JSONObject m = mods.optJSONObject(id);
                if (m == null || !m.optBoolean("enabled")) continue;
                JSONArray l = m.optJSONArray("panel");
                for (int i = 0; l != null && i < l.length(); i++) {
                    JSONObject p = l.optJSONObject(i);
                    ComponentName c = p == null ? null : ComponentName.unflattenFromString(p.optString("widget"));
                    if (c == null) continue;
                    Page pg = new Page();
                    pg.mod = id;
                    pg.title = p.optString("title", id);
                    pg.widget = c;
                    out.add(pg);
                }
            }
        } catch (Exception e) {
            // no state yet: no pages
        }
        return out;
    }

    /** made-up string id for HMG-API's page title in the editor, answered by HMG-API's resource hooks */
    static final int TITLE_ID = 0x7f7c0016;

    static String slotTitle(List<Page> pages) {
        return pages.size() == 1 ? pages.get(0).title : "HMG-API";
    }

    // ---------------- the panel's process ----------------

    static final ThreadLocal<Boolean> building = new ThreadLocal<Boolean>();
    static Object page;
    static Host host;

    static void panel(final LoadedApp app) throws Exception {
        final ClassLoader cl = app.classLoader;
        Points.hook(cl, "panel.test_gate.on", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Boolean.TRUE.equals(building.get())) p.setResult(Boolean.TRUE);
            }
        });
        MethodHook slot = new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) throws Throwable {
                boolean ours = p.args.length != 1 || Integer.valueOf(SLOT).equals(p.args[0]);
                if (!ours || host == null || host.pages.isEmpty()) return;
                ensurePage(cl, p.thisObject);
                building.set(Boolean.TRUE);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                building.set(null);
            }
        };
        Points.hook(cl, "panel.home.page_for_id", slot);
        Points.hook(cl, "panel.home.build_views", slot);
        Points.hook(cl, "panel.test_page.get_view", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (p.thisObject == page && host != null) p.setResult(host.ui());
            }
        });
        if (app.context == null) return;
        final Context ctx = app.context;
        host = new Host(ctx);
        host.load();
        ctx.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                host.load();
            }
        }, new IntentFilter(Live.ACTION));
        HmgApi.i("panel: " + host.pages.size() + " mod page(s)");
    }

    /** HMG-API's page object: Mobis' test page class (it has no constructor of its own), filled in like theirs would be */
    static void ensurePage(ClassLoader cl, Object home) throws Exception {
        if (page != null) return;
        Object pg = allocate(Points.cls(cl, "panel.test_page"));
        Points.field(cl, "panel.page.context").set(pg, host.context);
        Points.field(cl, "panel.page.handler").set(pg, new Handler(Looper.getMainLooper()));
        Points.field(cl, "panel.page.view_id").set(pg, SLOT);
        Points.field(cl, "panel.page.view").set(pg, host.ui());
        Points.field(cl, "panel.home.test_page").set(home, pg);
        page = pg;
        HmgApi.i("panel: HMG-API's page is in slot " + SLOT);
    }

    /** an instance without running a constructor (Dalvik: as objenesis does) */
    static Object allocate(Class<?> c) throws Exception {
        try {
            Class<?> u = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field f = u.getDeclaredField("THE_ONE");
            f.setAccessible(true);
            return u.getMethod("allocateInstance", Class.class).invoke(f.get(null), c);
        } catch (Throwable t) {
            java.lang.reflect.Method id = java.io.ObjectStreamClass.class.getDeclaredMethod("getConstructorId", Class.class);
            id.setAccessible(true);
            java.lang.reflect.Method n = java.io.ObjectStreamClass.class.getDeclaredMethod("newInstance", Class.class, int.class);
            n.setAccessible(true);
            return n.invoke(null, c, id.invoke(null, Object.class));
        }
    }

    /**
     * HMG-API's page: a header (title, ‹ ›) over the chosen mod's widget.
     *
     * Its views are made only when the panel first builds HMG-API's page (ui()), never
     * when the process starts. A TextView with text makes the process load its fonts,
     * and Skia builds the process's fallback font list once, on that first use: made
     * at start (after handleBindApplication), it came before the Mobis font's
     * framework-level fallback, so the whole panel lost the Mobis font for Korean
     * text, even with no mod page (#174, Avante CN7 KOR 260902).
     */
    static final class Host {
        final Context context;
        final AppWidgetHost widgets;
        LinearLayout view;   // null until ui()
        TextView title, prev, next;
        FrameLayout body;
        final Map<ComponentName, AppWidgetHostView> shown = new LinkedHashMap<ComponentName, AppWidgetHostView>();
        List<Page> pages = new ArrayList<Page>();
        int current;

        Host(Context c) {
            context = c;
            widgets = new AppWidgetHost(c, HOST_ID);
            try {
                widgets.deleteHost();   // widget ids from an earlier run of this process
            } catch (Throwable t) {
                HmgApi.e("panel: widget host", t);
            }
            widgets.startListening();
        }

        /** the page's views, made on first use (see the class comment) */
        LinearLayout ui() {
            if (view != null) return view;
            Context c = context;
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.VERTICAL);
            LinearLayout head = new LinearLayout(c);
            head.setGravity(Gravity.CENTER_VERTICAL);
            prev = label(c, "‹");
            title = label(c, "");
            next = label(c, "›");
            head.addView(prev);
            head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            head.addView(next);
            prev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(current - 1);
                }
            });
            next.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(current + 1);
                }
            });
            body = new FrameLayout(c);
            view.addView(head);
            view.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            HmgApi.i("panel: HMG-API's page views made");
            show(current);
            return view;
        }

        static TextView label(Context c, String s) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextSize(22);
            t.setTextColor(Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(24, 8, 24, 8);
            return t;
        }

        /** (re)reads the mods' pages; called at start and when mods change */
        void load() {
            pages = pages();
            listed(context.getContentResolver(), !pages.isEmpty());
            show(current);
        }

        void show(int i) {
            if (view == null) return;   // not shown yet: ui() shows the current page when it's made
            body.removeAllViews();
            boolean many = pages.size() > 1;
            prev.setVisibility(many ? View.VISIBLE : View.INVISIBLE);
            next.setVisibility(many ? View.VISIBLE : View.INVISIBLE);
            if (pages.isEmpty()) {
                title.setText("");
                return;
            }
            current = (i % pages.size() + pages.size()) % pages.size();
            Page p = pages.get(current);
            title.setText(p.title);
            try {
                AppWidgetHostView w = shown.get(p.widget);
                if (w == null) {
                    w = bind(p);
                    shown.put(p.widget, w);
                }
                if (w != null) body.addView(w, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            } catch (Throwable t) {
                HmgApi.e("panel: " + p.mod + " page", t);
            }
        }

        AppWidgetHostView bind(Page p) {
            AppWidgetManager m = AppWidgetManager.getInstance(context);
            int id = widgets.allocateAppWidgetId();
            if (!m.bindAppWidgetIdIfAllowed(id, p.widget)) {   // the panel runs as system: allowed
                widgets.deleteAppWidgetId(id);
                HmgApi.i("panel: " + p.widget.flattenToShortString() + " couldn't be bound (not a widget provider?)");
                return null;
            }
            AppWidgetProviderInfo info = m.getAppWidgetInfo(id);
            AppWidgetHostView v = widgets.createView(context, id, info);
            v.setAppWidget(id, info);
            HmgApi.i("panel: " + p.mod + " page " + p.widget.flattenToShortString() + " shown");
            return v;
        }
    }

    /** id 22 in the panel's page lists (what the editor offers) while there are mod pages, out of all of them when not */
    static void listed(ContentResolver cr, boolean on) {
        for (String k : LISTS) edit(cr, k, on);
        if (!on) for (String k : CHOSEN) edit(cr, k, false);
    }

    static void edit(ContentResolver cr, String key, boolean add) {
        String v = Settings.System.getString(cr, key);
        if (v == null || v.isEmpty()) return;   // the car fills its lists itself on first use
        List<String> ids = new ArrayList<String>(Arrays.asList(v.split(",")));
        boolean has = ids.contains(SLOT_ID);
        if (add == has) return;
        if (add) ids.add(SLOT_ID);
        else while (ids.remove(SLOT_ID)) ;
        StringBuilder b = new StringBuilder();
        for (String s : ids) if (!s.isEmpty()) b.append(b.length() == 0 ? "" : ",").append(s);
        Settings.System.putString(cr, key, b.toString());
        HmgApi.i("panel: " + key + " " + (add ? "+" : "-") + SLOT_ID);
    }

    // ---------------- Settings and the page editor ----------------

    /** Settings drops unsupported ids from the page lists: 22 is supported while a mod has a page */
    static void settings(ClassLoader cl) throws Exception {
        Points.hook(cl, "settings.split_screen.supported", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Integer.valueOf(SLOT).equals(p.args[1]) && !pages().isEmpty()) p.setResult(Boolean.TRUE);
            }
        });
    }

    /** the car's split-screen editor (ThemeSettings): HMG-API's page in its list, by its title */
    static void editor(final ClassLoader cl) throws Exception {
        // the saved-list builder (the editor's first screen) takes each page's title by string id;
        // the car has none for slot 22 (getString(0) crashed it). Also once no mod has a page:
        // a saved list can still hold 22
        HmgApi.hookEntryResources();
        Points.hook(cl, "themesettings.split_items.string_id", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Integer.valueOf(SLOT).equals(p.args[0])) p.setResult(TITLE_ID);
            }
        });
        // the editor's own check (as Settings'): unknown ids are dropped and the list saved without them
        Points.hook(cl, "themesettings.split_items.available", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Integer.valueOf(SLOT).equals(p.args[0]) && !pages().isEmpty()) p.setResult(Boolean.TRUE);
            }
        });
        Points.hook(cl, "themesettings.split_items.view_name", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Integer.valueOf(SLOT).equals(p.args[1])) p.setResult(slotTitle(pages()));
            }
        });
        Points.hook(cl, "themesettings.split_items.make", new MethodHook() {
            @Override
            @SuppressWarnings("unchecked")
            protected void afterHookedMethod(Param p) throws Throwable {
                Map<String, String> in = (Map<String, String>) p.args[1];
                List<Object> out = (List<Object>) p.getResult();
                List<Page> pages = pages();
                if (in == null || out == null || !in.containsKey(SLOT_ID) || pages.isEmpty()) return;
                for (Object it : out) if (Integer.valueOf(SLOT).equals(Points.call(cl, "themesettings.split_item.type", it))) return;
                // where the map's order puts it: after the items whose ids come before it
                int at = 0;
                List<String> order = new ArrayList<String>(in.keySet());
                int mine = order.indexOf(SLOT_ID);
                for (Object it : out) {
                    String id = String.valueOf(Points.call(cl, "themesettings.split_item.type", it));
                    if (order.indexOf(id) >= 0 && order.indexOf(id) < mine) at++;
                }
                Object item = Points.newInstance(cl, "themesettings.split_item.new", SLOT, slotTitle(pages), "on".equals(in.get(SLOT_ID)));
                out.add(Math.min(at, out.size()), item);
            }
        });
    }
}

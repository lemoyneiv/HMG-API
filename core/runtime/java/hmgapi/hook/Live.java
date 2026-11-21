package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import hmgapi.engine.Json;

import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

/**
 * Mods on, off and their settings without a restart, in every app process.
 * HMG-API (engine publish-state, after any change) writes /data/hmgapi/public/state.json
 * and broadcasts hmgapi.intent.action.STATE_CHANGED; each app re-reads the file:
 * a mod that's off has its hooks pass through, new public settings replace the old
 * (ModEntry.onSettingsChanged), and a "live" mod turned on is loaded here and now.
 * The broadcast carries nothing: anyone may send it, it only makes apps re-read
 * the file only root writes.
 */
final class Live {
    private Live() {}

    static final String ACTION = "hmgapi.intent.action.STATE_CHANGED";
    static final File STATE = new File("/data/hmgapi/public/state.json");
    static long serial = -1;
    static LoadedApp app;

    static void start(LoadedApp a) {
        app = a;
        sync();
        a.context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                new Thread("hmgapi-live") {   // a live load optimises a jar: not on the main thread
                    @Override
                    public void run() {
                        sync();
                    }
                }.start();
            }
        }, new IntentFilter(ACTION));
    }

    @SuppressWarnings("unchecked")
    static synchronized void sync() {
        Map<String, Object> state;
        try {
            if (!STATE.isFile()) return;
            state = Json.object(Mods.read(STATE));
        } catch (Exception e) {
            HmgApi.e("live state", e);
            return;
        }
        Object sr = state.get("serial");
        long now = sr instanceof Number ? ((Number) sr).longValue() : 0;
        if (now == serial) return;
        serial = now;
        Map<String, Object> mods = (Map<String, Object>) state.get("mods");
        if (mods == null) return;
        for (Mods.Loaded m : new java.util.ArrayList<Mods.Loaded>(Mods.withHooks)) {
            ModContext ctx = m.context;
            Map<String, Object> s = (Map<String, Object>) mods.get(ctx.id);
            boolean on = s != null && Boolean.TRUE.equals(s.get("enabled"));
            if (on != ctx.enabled) {
                ctx.enabled = on;
                try {
                    m.entry.onEnabled(ctx, on);
                } catch (Throwable t) {
                    ctx.error("onEnabled", t);
                }
                HmgApi.i("live: " + ctx.id + (on ? " on" : " off") + " in " + (app == null ? "?" : app.processName));
            }
            if (s == null || !(s.get("settings") instanceof Map)) continue;
            try {
                JSONObject merged = new JSONObject(ctx.settings.toString());   // keeps private ones from the start
                for (Map.Entry<String, Object> v : ((Map<String, Object>) s.get("settings")).entrySet()) {
                    merged.put(v.getKey(), JSONObject.wrap(v.getValue()));
                }
                if (!same(merged, ctx.settings)) {
                    ctx.settings = merged;
                    m.entry.onSettingsChanged(ctx);
                }
            } catch (Throwable t) {
                ctx.error("onSettingsChanged", t);
            }
        }
        BleHooks.sync(mods);
        if (app != null && app.context != null) {
            for (String id : mods.keySet()) Mods.lateLoad(id, app, state, new HashSet<String>());
        }
    }

    static boolean same(JSONObject a, JSONObject b) {
        if (a.length() != b.length()) return false;
        for (Iterator<?> it = a.keys(); it.hasNext();) {
            String k = (String) it.next();
            if (!String.valueOf(a.opt(k)).equals(String.valueOf(b.opt(k)))) return false;
        }
        return true;
    }
}

package hmgapi.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * HMG-API's app: a top bar (what HMG-API is doing on this car: hooks, changes not
 * applied yet, a restart needed; Apply changes, USB updates, Restart) over four
 * tabs: Mods (each mod's state, switch, settings and log), Settings (HMG-API's own,
 * by category), Car Report, Logs.
 */
public class MainActivity extends Activity {
    static final String[] TABS = { "Mods", "Settings", "Car Report", "Logs" };
    private LinearLayout pills;
    private FrameLayout content;
    private final TextView[] tabs = new TextView[TABS.length];
    private final View[] marks = new View[TABS.length];
    private int current;
    private boolean pendingChanges;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);

        // top bar
        LinearLayout top = Ui.row(this);
        top.setBackgroundColor(Ui.PANEL);
        top.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        ImageView logo = new ImageView(this);
        logo.setImageResource(getApplicationInfo().icon);
        top.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout title = Ui.column(this);
        title.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 24), 0);
        Ui.text(title, "HMG-API", 26, true, Ui.TEXT).setPadding(0, 0, 0, 0);
        Ui.text(title, subtitle(), 15, false, Ui.GREY).setPadding(0, 0, 0, 0);
        top.addView(title);
        pills = Ui.row(this);
        top.addView(pills, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout actions = Ui.row(this);
        Ui.button(actions, "Apply changes", true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                plan();
            }
        });
        Ui.button(actions, "Check USB updates", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, UpdatesActivity.class), UPDATES);
            }
        });
        Ui.button(actions, "Restart unit", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestart();
            }
        });
        top.addView(actions);
        root.addView(top);

        // tabs
        LinearLayout tabRow = Ui.row(this);
        tabRow.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        for (int i = 0; i < TABS.length; i++) {
            final int n = i;
            LinearLayout t = Ui.column(this);
            tabs[i] = new TextView(this);
            tabs[i].setText(TABS[i]);
            Ui.size(tabs[i], 21);
            tabs[i].setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 8));
            t.addView(tabs[i]);
            marks[i] = new View(this);
            marks[i].setBackground(Ui.box(this, Ui.ACCENT, 0, 2));
            int w = (int) tabs[i].getPaint().measureText(TABS[i]) + Ui.dp(this, 8);   // the underline: as wide as its name
            t.addView(marks[i], new LinearLayout.LayoutParams(w, Ui.dp(this, 4)));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(n);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = Ui.dp(this, 44);
            tabRow.addView(t, lp);
        }
        root.addView(tabRow);
        View rule = new View(this);
        rule.setBackgroundColor(Ui.LINE);
        root.addView(rule, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        show(getIntent().getIntExtra("tab", 0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    void show(int n) {
        current = n;
        for (int i = 0; i < TABS.length; i++) {
            tabs[i].setTextColor(i == n ? Ui.TEXT : Ui.GREY);
            tabs[i].setTypeface(i == n ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            marks[i].setVisibility(i == n ? View.VISIBLE : View.INVISIBLE);
        }
        content.removeAllViews();
        View v = n == 0 ? new ModsTab(this).view() : n == 1 ? new SettingsTab(this).view()
                : n == 2 ? new ReportTab(this).view() : new LogsTab(this).view();
        content.addView(v);
    }

    /** "1.0.0 · Kia EV6 (usa 260507)" from the hook runtime's status */
    static String subtitle() {
        try {
            JSONObject s = new JSONObject(HmgApiStatus.json);
            JSONObject map = s.optJSONObject("map");
            return s.optString("hmgapi") + (map != null && map.has("car") ? "  ·  " + map.optString("car") : "");
        } catch (Exception e) {
            return "hooks not running";
        }
    }

    // ---- status: hooks, changes not applied, restart needed

    void refreshStatus() {
        pills.removeAllViews();
        hooksPill();
        if (restartNeeded()) Ui.pill(pills, "Restart needed", Ui.WARN);
        new Ui.Job<Boolean>() {
            @Override
            Boolean work() throws Exception {
                Hmgapid.Result r = Hmgapid.call(600000, "engine", "plan");
                return r.code == 0 && changes(r.output);
            }

            @Override
            void done(Boolean changed, Exception e) {
                pendingChanges = e == null && Boolean.TRUE.equals(changed);
                if (pendingChanges) Ui.pill(pills, "Changes not applied", Ui.WARN);
            }
        }.start(this);
    }

    private void hooksPill() {
        try {
            JSONObject map = new JSONObject(HmgApiStatus.json).optJSONObject("map");
            if (map == null || !map.optBoolean("on")) Ui.pill(pills, "Hooks off for this car", Ui.WARN);
            else Ui.pill(pills, "Hooks on" + (map.optBoolean("checked_here") || "verified".equals(map.optString("status"))
                    ? " (map checked)" : ""), Ui.ACCENT);
        } catch (Exception e) {
            Ui.pill(pills, "Hooks not running", Ui.ERROR);
        }
    }

    /** the boot this process runs in (to the 10 s): a restart flag is only for the boot it was set in */
    static long boot() {
        return (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 10000;
    }

    boolean restartNeeded() {
        return getSharedPreferences("hmgapi", MODE_PRIVATE).getLong("restart_boot", -1) == boot();
    }

    /** a change that takes effect at the next restart (a mod with code on or off, OTA updates) */
    void needRestart() {
        SharedPreferences p = getSharedPreferences("hmgapi", MODE_PRIVATE);
        p.edit().putLong("restart_boot", boot()).apply();
        refreshStatus();
    }

    // ---- apply (engine plan first, then apply on confirmation)

    static final int UPDATES = 1;

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == UPDATES && result == RESULT_OK) plan();   // mods were installed: apply them (plan first)
    }

    void plan() {
        final AlertDialog wait = new AlertDialog.Builder(this).setMessage("Checking what would change…").show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(600000, "engine", "plan");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                if (e != null) {
                    message("Couldn't check", e.getMessage());
                    return;
                }
                String shown = readable(r.output);
                if (r.code != 0) {
                    message("Can't apply", shown);
                    return;
                }
                if (!changes(r.output)) {
                    message("Nothing to change", restartNeeded() ? "Everything is applied. Mods with code start or stop at the next restart."
                            : "Everything is applied.");
                    return;
                }
                new AlertDialog.Builder(MainActivity.this).setTitle("Apply these changes?").setMessage(shown)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                apply();
                            }
                        }).show();
            }
        }.start(this);
    }

    private void apply() {
        final AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("Applying… (rebuilding apps can take a few minutes; leave the head unit on)").setCancelable(false).show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(900000, "engine", "apply");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                if (e != null) {
                    message("Lost contact while applying", e.getMessage() + ". The apply finishes on its own; open HMG-API again to check.");
                    return;
                }
                boolean restart = restartNeeded() || r.output.contains("restart the head unit");
                if (r.output.contains("restart the head unit")) needRestart();
                AlertDialog.Builder d = new AlertDialog.Builder(MainActivity.this)
                        .setTitle(r.code == 0 ? (restart ? "Applied: restart to finish" : "Applied") : "Not applied")
                        .setMessage(readable(r.output));
                if (r.code == 0 && restart) {
                    d.setNegativeButton("Later", null).setPositiveButton("Restart now", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface x, int which) {
                            restart();
                        }
                    });
                } else {
                    d.setPositiveButton("OK", null);
                }
                d.show();
                refreshStatus();
                show(current);
            }
        }.start(this);
    }

    void message(String title, String text) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    /** Does an engine plan change anything? */
    static boolean changes(String out) {
        for (String l : out.split("\n")) {
            if (l.startsWith("change ") || l.startsWith("update ") || l.startsWith("restore ")) return true;
            if (l.startsWith("setting ")) return true;
        }
        return false;
    }

    /** Engine output for people: no unchanged files, md5s or per-file write lines. */
    static String readable(String out) {
        StringBuilder b = new StringBuilder();
        for (String l : out.split("\n")) {
            if (l.startsWith("same ") || l.startsWith("wrote ") || l.startsWith("ok ")) continue;
            l = l.replaceAll(" -> [0-9a-f]{32}", "").replaceAll("^([a-z]+) +", "$1  ");
            b.append(l).append('\n');
        }
        return b.toString().trim();
    }

    private void confirmRestart() {
        new AlertDialog.Builder(this)
                .setTitle("Restart the head unit?")
                .setMessage("The screen goes dark for about a minute. Don't use this while driving with navigation or a call active.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restart", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        restart();
                    }
                })
                .show();
    }

    void restart() {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("reboot");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e == null && r.code == 0) return;
                message("Couldn't restart", e != null ? "hmgapid isn't running (" + e.getMessage() + ")" : r.output.trim());
            }
        }.start(this);
    }
}

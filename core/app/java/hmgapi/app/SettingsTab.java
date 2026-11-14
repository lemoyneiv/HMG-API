package hmgapi.app;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;

/**
 * HMG-API's own settings, by category: Vehicle (the owner's switches for mods'
 * commands), Updates, Phone projection (what mods set), Hook map (this car's map,
 * checks, mapping it from its own files), About. The switches are kept by hmgapid
 * (allow <name>).
 */
final class SettingsTab {
    static final String[] CATS = { "Vehicle", "Updates", "Phone projection", "Hook map", "About" };
    final MainActivity a;
    LinearLayout cats, page;
    static int current;   // the category shown last, kept while the app runs

    SettingsTab(MainActivity a) {
        this.a = a;
    }

    View view() {
        LinearLayout root = Ui.row(a);
        root.setGravity(0);
        LinearLayout side = Ui.column(a);
        side.setBackgroundColor(Ui.PANEL);
        cats = Ui.scrolling(side, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        root.addView(side, new LinearLayout.LayoutParams(Ui.dp(a, 360), LinearLayout.LayoutParams.MATCH_PARENT));
        LinearLayout right = Ui.column(a);
        page = Ui.scrolling(right, 1, LinearLayout.LayoutParams.MATCH_PARENT);
        page.setPadding(Ui.dp(a, 36), Ui.dp(a, 16), Ui.dp(a, 32), Ui.dp(a, 24));
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        show(current);
        return root;
    }

    void show(int n) {
        current = n;
        cats.removeAllViews();
        for (int i = 0; i < CATS.length; i++) {
            final int k = i;
            TextView t = Ui.text(cats, CATS[i], 21, i == n, i == n ? Ui.TEXT : Ui.GREY);
            t.setPadding(Ui.dp(a, 28), Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14));
            if (i == n) t.setBackgroundColor(Ui.PANEL2);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(k);
                }
            });
        }
        page.removeAllViews();
        Ui.text(page, CATS[n], 28, true, Ui.TEXT);
        if (n == 0) vehicle();
        else if (n == 1) updates();
        else if (n == 2) projection();
        else if (n == 3) hookMap();
        else about();
    }

    void owner(String title, String about, String label, String name, boolean inverted, Runnable changed) {
        Ui.text(page, title, 21, true, Ui.TEXT);
        Ui.text(page, about, 16, false, Ui.GREY);
        Switch sw = Ui.switchRow(page, label);
        Ui.allowSwitch(a, sw, name, inverted, changed);
        Ui.line(page);
    }

    void vehicle() {
        owner("Commands from mods", "The first time a mod uses a car command (seat heat, charge door, ambient light…) HMG-API asks "
                + "you on screen, once per mod and command. Giving back what a mod took (release, stop, off) never asks.",
                "Ask before a mod's first command", "trust_commands", true, null);
        Ui.button(page, "Forget my answers (ask again)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Ui.Job<Hmgapid.Result>() {
                    @Override
                    Hmgapid.Result work() throws Exception {
                        return Hmgapid.call("allow", "commands_reset", "on");
                    }

                    @Override
                    void done(Hmgapid.Result r, Exception e) {
                        a.message("Answers", e == null && r.code == 0 ? "Forgotten: mods ask again." : "Couldn't forget them.");
                    }
                }.start(a);
            }
        });
        Ui.line(page);
        owner("Startup notice", "Lets a mod dismiss the notice the car shows at start, which the driver is meant to read and "
                + "accept. It uses a factory function with no known way to undo it; only in P, once per start.",
                "Allow mods to dismiss it", "startup_notice", false, null);
        owner("Ambient light", "Mods that change the ambient light put your colour or theme and brightness back when they let "
                + "go; a change you make in the car's menu becomes the one they put back. Turn this on to keep every mod off the light.",
                "Block mod light control", "block_ambient_light", false, null);
    }

    void updates() {
        owner("Over-the-air updates", "Off (the default): HMG-API blocks the car's own update downloads, so an update can't "
                + "replace what HMG-API and your mods depend on. USB and dealer updates still work. Takes effect at the next restart.",
                "Allow over-the-air updates", "ota_updates", false, new Runnable() {
                    @Override
                    public void run() {
                        a.needRestart();
                    }
                });
        Ui.text(page, "Updates from USB", 21, true, Ui.TEXT);
        Ui.text(page, "HMG-API releases and mod packages (zips) in HMGAPI/updates/ on a USB stick.", 16, false, Ui.GREY);
        Ui.button(page, "Check the USB stick", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                a.startActivityForResult(new Intent(a, UpdatesActivity.class), MainActivity.UPDATES);
            }
        });
    }

    void projection() {
        Ui.text(page, "What mods change in Android Auto and CarPlay (set in each mod's settings). Changes take effect the "
                + "next time the phone connects after a restart.", 16, false, Ui.GREY);
        try {
            JSONObject p = new JSONObject(read(new File("/data/hmgapi/public/state.json"))).optJSONObject("projection");
            if (p == null || p.length() == 0) {
                Ui.text(page, "No mod changes phone projection.", 19, false, Ui.TEXT);
                return;
            }
            if (p.has("aa_density")) fact("Android Auto density", p.optInt("aa_density") + " DPI (stock 240), set by " + p.optString("aa_density_by"));
            JSONArray rm = p.optJSONArray("aa_sensors_remove");
            if (rm != null) fact("Sensors not offered to the phone", rm.join(", ").replace("\"", ""));
            JSONArray ev = p.optJSONArray("events");
            if (ev != null) fact("Apps told about connections", ev.join(", ").replace("\"", ""));
        } catch (Exception e) {
            Ui.text(page, "Can't read HMG-API's state: " + e.getMessage(), 17, false, Ui.ERROR);
        }
    }

    void fact(String k, String v) {
        LinearLayout r = Ui.row(a);
        TextView kt = new TextView(a);
        kt.setText(k);
        Ui.size(kt, 18);
        kt.setTextColor(Ui.GREY);
        r.addView(kt, new LinearLayout.LayoutParams(Ui.dp(a, 320), LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView vt = new TextView(a);
        vt.setText(v);
        Ui.size(vt, 18);
        vt.setTextColor(Ui.TEXT);
        r.addView(vt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
        page.addView(r);
    }

    void hookMap() {
        final LinearLayout facts = Ui.column(a);
        page.addView(facts);
        Ui.text(facts, "Reading the map…", 17, false, Ui.GREY);
        new Ui.Job<String[]>() {
            @Override
            String[] work() throws Exception {
                String map = Hmgapid.call("engine", "map").output.trim();
                return new String[] { map };
            }

            @Override
            void done(String[] r, Exception e) {
                facts.removeAllViews();
                LinearLayout keep = page;
                page = facts;
                if (e != null) {
                    Ui.text(facts, Hmgapid.unreachable(e), 17, false, Ui.ERROR);
                } else {
                    String s = r[0];
                    fact("This car", MainActivity.subtitle());
                    fact("Hook map", s.replaceFirst("^hook map: ", ""));
                }
                page = keep;
            }
        }.start(a);
        LinearLayout buttons = Ui.row(a);
        Ui.button(buttons, "Check the map on this car", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run("Checking the map…", "verify-map");
            }
        });
        Ui.button(buttons, "Map this car from its files", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(a).setTitle("Map this car from its own files?")
                        .setMessage("HMG-API finds its hook points in this car's apps. If HMG-API has no map for this car, it "
                                + "saves this one (\"probed\"); its hooks run after the map check passes and a restart. "
                                + "A Car Report sends it to HMG-API's maintainers.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Map it", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                run("Mapping this car (about a minute)…", "map-car", "--write");
                            }
                        }).show();
            }
        });
        page.addView(buttons);
        Ui.line(page);
        owner("Maps not checked on a car yet", "For a car HMG-API is new on: hooks run with a map nobody has checked on this car. "
                + "If the head unit fails to start three times in a row, HMG-API's boot guard turns its hooks off. Takes effect at the next restart.",
                "Try unchecked maps", "testing_maps", false, new Runnable() {
                    @Override
                    public void run() {
                        a.needRestart();
                    }
                });
    }

    void run(String waiting, final String... cmd) {
        final AlertDialog wait = new AlertDialog.Builder(a).setMessage(waiting).setCancelable(false).show();
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                String[] c = new String[cmd.length + 1];
                c[0] = "engine";
                System.arraycopy(cmd, 0, c, 1, cmd.length);
                return Hmgapid.call(600000, c);
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                wait.dismiss();
                a.message(e != null || r.code != 0 ? "Didn't work" : "Done", e != null ? e.getMessage() : r.output.trim());
                if (e == null && r.code == 0) a.needRestart();
                show(current);
            }
        }.start(a);
    }

    void about() {
        String v;
        try {
            v = new JSONObject(HmgApiStatus.json).optString("hmgapi");
        } catch (Exception e) {
            v = "?";
        }
        fact("HMG-API", v);
        fact("License", "AGPL-3.0 (mods have their own)");
        fact("Source and help", "github.com/Open-HMG/HMG-API");
        fact("Guides", "github.com/Open-HMG/HMG-API/wiki");
        fact("Support the project", "github.com/Open-HMG/HMG-API/wiki/Support");
        Ui.text(page, "HMG-API installs through NEMESIS, adb or a USB stick, and every change it makes to the head unit can be "
                + "put back to stock.", 16, false, Ui.GREY);
    }

    static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int n = 0, k;
            while (n < b.length && (k = in.read(b, n, b.length - n)) > 0) n += k;
            return new String(b, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }
}

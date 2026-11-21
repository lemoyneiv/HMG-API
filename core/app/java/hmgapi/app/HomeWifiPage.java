package hmgapi.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Settings > Home Wi-Fi: the owner's switch, the saved networks (Android's own Wi-Fi config), the
 * networks seen last time home Wi-Fi was on, add and forget. Everything goes through hmgapid wifi
 * (the keeper's script, as root); a key is only ever sent to it, never shown or logged. Typing a
 * key needs the car in P.
 */
final class HomeWifiPage {
    final MainActivity a;
    final LinearLayout page;
    LinearLayout saved, found, cellular;
    TextView nowView;

    HomeWifiPage(MainActivity a, LinearLayout page) {
        this.a = a;
        this.page = page;
    }

    void show(final SettingsTab tab) {
        Ui.text(page, "Home Wi-Fi", 21, true, Ui.TEXT);
        Ui.text(page, "On: when the car has been off for 30 seconds, the head unit joins your home Wi-Fi. "
                + "The car's Wi-Fi for wireless CarPlay and Android Auto goes off meanwhile and comes back when you switch "
                + "on. Only HMG-API and mods that ask use home Wi-Fi; the car's own services stay on its mobile data. "
                + "Turning it on needs applying and a restart.", 16, false, Ui.GREY);
        android.widget.Switch sw = Ui.switchRow(page, "Use home Wi-Fi");
        Ui.allowSwitch(a, sw, "home_wifi", false, new Runnable() {
            @Override
            public void run() {
                a.plan();   // apply() marks needRestart() once applied; calling needRestart() before plan() ran two engine plans at once
                loadSaved();
                scan();
            }
        });
        nowView = Ui.text(page, "Now: " + now(), 16, false, Ui.TEXT);
        startNowPoll();
        final SharedPreferences prefs = a.getSharedPreferences("hmgapi", Context.MODE_PRIVATE);
        LinearLayout timer = Ui.row(a);
        page.addView(timer);
        final CheckBox limit = new CheckBox(a);
        limit.setText("Turn off after");
        limit.setTextColor(Ui.TEXT);
        Ui.size(limit, 18);
        limit.setChecked(prefs.getBoolean("wifi_now_timer", true));
        timer.addView(limit);
        final EditText minutes = new EditText(a);
        minutes.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutes.setSingleLine(true);
        minutes.setEms(3);
        minutes.setTextColor(Ui.TEXT);
        Ui.size(minutes, 18);
        minutes.setText(String.valueOf(prefs.getInt("wifi_now_minutes", 30)));
        minutes.setEnabled(limit.isChecked());
        timer.addView(minutes);
        Ui.text(timer, "minutes", 18, false, Ui.TEXT);
        limit.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                minutes.setEnabled(on);
                prefs.edit().putBoolean("wifi_now_timer", on).apply();
            }
        });
        LinearLayout manual = Ui.row(a);
        page.addView(manual);
        Ui.button(manual, "Connect now", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!limit.isChecked()) {
                    request("now", "0");
                    return;
                }
                int m = -1;
                try {
                    m = Integer.parseInt(minutes.getText().toString().trim());
                } catch (NumberFormatException e) {
                    // left at -1: refused below
                }
                if (m < 1 || m > 240) {
                    a.message("Home Wi-Fi", "Minutes: 1 to 240. Untick the box for no time limit.");
                    return;
                }
                prefs.edit().putInt("wifi_now_minutes", m).apply();
                request("now", String.valueOf(m));
            }
        });
        Ui.button(manual, "Back to the car's Wi-Fi", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                request("back", null);
            }
        });
        Ui.text(page, "Connect now works in P. Wireless CarPlay and Android Auto stay off until it ends: when you shift out of P, "
                + "tap Back, the head unit restarts, or the minutes are up (with the box ticked). You can leave this app meanwhile.",
                14, false, Ui.GREY);
        Ui.line(page);
        Ui.text(page, "Saved networks", 21, true, Ui.TEXT);
        saved = Ui.column(a);
        page.addView(saved);
        Ui.button(page, "Add a network", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                add();
            }
        });
        Ui.text(page, "Seen last time home Wi-Fi was on", 18, true, Ui.TEXT);
        found = Ui.column(a);
        page.addView(found);
        Ui.line(page);
        Ui.text(page, "Mobile data", 21, true, Ui.TEXT);
        Ui.text(page, "Mods never use the car's mobile data, DNS lookups included: only home Wi-Fi, for the mods that ask. "
                + "You can let a mod use mobile data below, at your own risk (kept in hmgapi.json overrides.allow_cellular); "
                + "it takes effect within a few seconds.", 16, false, Ui.GREY);
        cellular = Ui.column(a);
        page.addView(cellular);
        loadSaved();
        scan();
        loadCellular();
    }

    void startNowPoll() {
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (a.isFinishing() || nowView == null || page.getWindowToken() == null) return;
                nowView.setText("Now: " + now());
                page.postDelayed(this, 3000);
            }
        };
        page.postDelayed(tick, 3000);
    }

    void add() {
        final EditText name = new EditText(a);
        name.setHint("Network name");
        name.setSingleLine(true);
        new AlertDialog.Builder(a).setTitle("Add a network").setView(name)
                .setPositiveButton("Next", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String n = name.getText().toString().trim();
                        if (!n.isEmpty()) join(n, false);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    static String now() {
        try {
            JSONObject w = new JSONObject(UsbFolders.read(new File("/data/hmgapi/public/wifi.json")));
            String s = w.optString("state");
            if ("up".equals(s)) return "connected, " + w.optInt("rssi") + " dBm, " + w.optInt("freq") + " MHz";
            if ("waiting".equals(s)) return "the car is on: home Wi-Fi waits until it's off";
            if ("searching".equals(s)) return "the car is off: looking for a saved network";
            if ("nonetworks".equals(s)) return "no saved networks yet";
            if ("nolease".equals(s)) return "joined, waiting for an address";
            if ("stock".equals(s)) return "the car's own Wi-Fi settings have the Wi-Fi";
            if ("clash".equals(s)) return "a network uses 192.168.0.x, the car Wi-Fi's own addresses: it can't be used";
            if ("off".equals(s)) return "off";
            return s;
        } catch (Exception e) {
            return "off";
        }
    }

    /** hmgapid wifi now [minutes] | back: home Wi-Fi on request (in P; 0 minutes: until out of P), and back */
    void request(final String verb, final String minutes) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return minutes == null ? Hmgapid.call("wifi", verb) : Hmgapid.call("wifi", verb, minutes);
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (nowView != null) nowView.setText("Now: " + now());
                a.message("Home Wi-Fi", e != null ? "HMG-API isn't answering." : r.output.trim());
            }
        }.start(a);
    }

    void loadSaved() {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("wifi", "saved");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                saved.removeAllViews();
                if (e != null || r.code != 0) {
                    String msg = e != null ? "HMG-API isn't answering." : r.output.trim();
                    boolean unapplied = msg.contains("isn't applied yet");
                    Ui.text(saved, msg, 16, unapplied, unapplied ? Ui.WARN : Ui.GREY);
                    if (unapplied) {
                        Ui.button(saved, "Apply changes", true, new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                a.plan();
                            }
                        });
                    }
                    return;
                }
                boolean any = false;
                for (final String name : r.output.split("\n")) {
                    if (name.trim().isEmpty()) continue;
                    any = true;
                    LinearLayout row = Ui.row(a);
                    saved.addView(row);
                    Ui.text(row, name, 18, false, Ui.TEXT);
                    Ui.button(row, "Forget", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            forget(name);
                        }
                    });
                }
                if (!any) Ui.text(saved, "None yet: look for networks below.", 16, false, Ui.GREY);
            }
        }.start(a);
    }

    void scan() {
        found.removeAllViews();

        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("wifi", "seen");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                found.removeAllViews();
                if (e != null || r.code != 0) {
                    Ui.text(found, e != null ? "HMG-API isn't answering." : r.output.trim(), 16, false, Ui.GREY);
                    return;
                }
                // strongest per name; "<MHz>\t<dBm>\t<flags>\t<name>"
                Map<String, String[]> best = new LinkedHashMap<String, String[]>();
                for (String l : r.output.split("\n")) {
                    String[] f = l.split("\t", 4);
                    if (f.length < 4 || f[3].isEmpty()) continue;
                    String[] was = best.get(f[3]);
                    if (was == null || Integer.parseInt(f[1]) > Integer.parseInt(was[1])) best.put(f[3], f);
                }
                List<String[]> list = new ArrayList<String[]>(best.values());
                java.util.Collections.sort(list, new java.util.Comparator<String[]>() {
                    @Override
                    public int compare(String[] x, String[] y) {
                        return Integer.parseInt(y[1]) - Integer.parseInt(x[1]);
                    }
                });
                if (list.isEmpty()) Ui.text(found, "Nothing yet: home Wi-Fi hasn't been on (it looks while the car is off). Add a network by its name.", 16, false, Ui.GREY);
                for (final String[] f : list) {
                    final boolean open = !f[2].contains("WPA") && !f[2].contains("WEP");
                    final boolean usable = !f[2].contains("WEP") && !f[2].contains("EAP");
                    LinearLayout row = Ui.row(a);
                    found.addView(row);
                    int mhz = Integer.parseInt(f[0]);
                    Ui.text(row, f[3] + "   " + f[1] + " dBm, " + (mhz < 3000 ? "2.4" : "5") + " GHz" + (open ? ", open" : ""), 18, false,
                            usable ? Ui.TEXT : Ui.GREY);
                    if (!usable) continue;
                    Ui.button(row, "Join", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            join(f[3], open);
                        }
                    });
                }
            }
        }.start(a);
    }

    void join(final String name, final boolean open) {
        if (open) {
            save(name, "");
            return;
        }
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("vehicle", "gear.park");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                boolean parked = e == null && r.code == 0 && r.output.trim().endsWith(" 1");
                if (!parked) {
                    a.message("Home Wi-Fi", "Shift to P to type a Wi-Fi key.");
                    return;
                }
                final EditText key = new EditText(a);
                key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                key.setHint("Key");
                new AlertDialog.Builder(a).setTitle(name).setView(key)
                        .setPositiveButton("Join", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                save(name, key.getText().toString());
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        }.start(a);
    }

    void save(final String name, final String key) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return key.isEmpty() ? Hmgapid.call("wifi", "join", name) : Hmgapid.call("wifi", "join", name, key);
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                if (e != null || r.code != 0) {
                    a.message("Home Wi-Fi", e != null ? "HMG-API isn't answering." : r.output.trim());
                    return;
                }
                a.message("Home Wi-Fi", "Saved. The head unit joins it the next time the car is off in range.");
                loadSaved();
            }
        }.start(a);
    }

    void forget(final String name) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("wifi", "forget", name);
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                loadSaved();
            }
        }.start(a);
    }

    /** Per-mod switches for hmgapi.json overrides.allow_cellular (live via Engine publish-state). */
    void loadCellular() {
        new Ui.Job<List<String[]>>() {
            @Override
            List<String[]> work() throws Exception {
                Hmgapid.Result mr = Hmgapid.call("mods");
                if (mr.code != 0) throw new IllegalStateException(mr.output.trim());
                Hmgapid.Result sr = Hmgapid.call("setting-get", "hmgapi");
                String raw = sr.code == 0 ? sr.output.trim() : "";
                JSONObject root = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                JSONObject ov = root.optJSONObject("overrides");
                JSONArray arr = ov == null ? null : ov.optJSONArray("allow_cellular");
                Set<String> allowed = new LinkedHashSet<String>();
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) allowed.add(s);
                }
                List<String[]> rows = new ArrayList<String[]>();
                Set<String> seen = new LinkedHashSet<String>();
                for (String line : mr.output.split("\n")) {
                    String[] w = line.split("\t");
                    if (w.length < 2) continue;
                    String id = w[0];
                    boolean enabled = "enabled".equals(w[1].trim());
                    String name = id;
                    try {
                        Hmgapid.Result jf = Hmgapid.call("mod-file", id, "mod.json");
                        if (jf.code == 0) name = new JSONObject(jf.output).optString("name", id);
                    } catch (Exception ignored) {
                    }
                    seen.add(id);
                    rows.add(new String[] { id, name, enabled ? name : name + " (off)", allowed.contains(id) ? "1" : "0" });
                }
                for (String id : allowed) {
                    if (!seen.contains(id)) rows.add(new String[] { id, id, id + " (not installed)", "1" });
                }
                return rows;
            }

            @Override
            void done(List<String[]> rows, Exception e) {
                cellular.removeAllViews();
                if (e != null) {
                    Ui.text(cellular, "HMG-API isn't answering.", 16, false, Ui.GREY);
                    return;
                }
                if (rows.isEmpty()) {
                    Ui.text(cellular, "No mods installed.", 16, false, Ui.GREY);
                    return;
                }
                for (String[] row : rows) {
                    Switch sw = Ui.switchRow(cellular, row[2]);
                    sw.setChecked("1".equals(row[3]));
                    bindCellularSwitch(sw, row[0], row[1]);
                }
            }
        }.start(a);
    }

    void bindCellularSwitch(final Switch sw, final String id, final String name) {
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!checked) {
                    setCellular(sw, id, name, false);
                    return;
                }
                new AlertDialog.Builder(a)
                        .setTitle("Allow mobile data for " + name + "?")
                        .setMessage("Its apps and services will be able to send traffic and DNS lookups over the car's "
                                + "cellular link, at your own risk.")
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                setCheckedQuietly(sw, id, name, false);
                            }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(DialogInterface d) {
                                setCheckedQuietly(sw, id, name, false);
                            }
                        })
                        .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                setCellular(sw, id, name, true);
                            }
                        }).show();
            }
        });
    }

    void setCheckedQuietly(Switch sw, String id, String name, boolean checked) {
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(checked);
        bindCellularSwitch(sw, id, name);
    }

    void setCellular(final Switch sw, final String id, final String name, final boolean allow) {
        sw.setEnabled(false);
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                Hmgapid.Result cur = Hmgapid.call("setting-get", "hmgapi");
                if (cur.code != 0) return cur;
                String raw = cur.output.trim();
                JSONObject root = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
                JSONObject ov = root.optJSONObject("overrides");
                if (ov == null) ov = new JSONObject();
                JSONArray prev = ov.optJSONArray("allow_cellular");
                Set<String> ids = new LinkedHashSet<String>();
                for (int i = 0; prev != null && i < prev.length(); i++) {
                    String s = prev.optString(i, "").trim();
                    if (!s.isEmpty()) ids.add(s);
                }
                if (allow) ids.add(id);
                else ids.remove(id);
                if (ids.isEmpty()) {
                    ov.remove("allow_cellular");
                } else {
                    JSONArray next = new JSONArray();
                    for (String s : ids) next.put(s);
                    ov.put("allow_cellular", next);
                }
                if (ov.length() == 0) root.remove("overrides");
                else root.put("overrides", ov);
                return Hmgapid.call("setting-set", "hmgapi", root.toString());
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                sw.setEnabled(true);
                if (e != null || r.code != 0) {
                    setCheckedQuietly(sw, id, name, !allow);
                    Toast.makeText(a, "Couldn't save it", Toast.LENGTH_SHORT).show();
                    return;
                }
                a.refreshStatus();
            }
        }.start(a);
    }
}

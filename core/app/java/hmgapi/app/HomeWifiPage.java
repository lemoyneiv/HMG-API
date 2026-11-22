package hmgapi.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings > Home Wi-Fi: live connection status, interactive Park scanning & tap-to-connect,
 * saved networks with per-SSID "Use for Home Wi-Fi" exclusion (for dashcams / non-internet APs),
 * hidden network setup, and per-mod mobile data overrides.
 */
final class HomeWifiPage {
    private static final long POLL_INTERVAL_MS = 1500L;

    final MainActivity a;
    final LinearLayout page;

    LinearLayout statusCard;
    LinearLayout statusPillHolder;
    TextView detailView;
    LinearLayout controlsHolder;
    Button keepAwakeUnpluggedBtn;
    Button keepAwakePluggedBtn;
    TextView keepAwakeLiveNote;
    TextView savedHeaderNote;
    LinearLayout savedList;
    TextView nearbyTitle;
    TextView nearbyNote;
    LinearLayout nearbyList;
    LinearLayout cellular;

    boolean pollInFlight = false;
    String lastControlsSig = "";
    String lastSavedSig = "";
    String lastNearbySig = "";
    String curKeepAwakeUnplugged = "30m";
    String curKeepAwakePlugged = "plugged";
    JSONObject lastStatus = null;

    private static final String[] UNPLUGGED_CODES = {
            "off", "5m", "15m", "30m", "1h", "2h", "4h", "8h"
    };
    private static final String[] UNPLUGGED_LABELS = {
            "Off (car's default ~5 min)",
            "5 minutes",
            "15 minutes",
            "30 minutes (default)",
            "1 hour",
            "2 hours",
            "4 hours",
            "8 hours"
    };
    private static final String[] PLUGGED_CODES = {
            "off", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "plugged"
    };
    private static final String[] PLUGGED_LABELS = {
            "Off (car's default ~5 min)",
            "5 minutes",
            "15 minutes",
            "30 minutes",
            "1 hour",
            "2 hours",
            "4 hours",
            "8 hours",
            "While plugged in — no time limit (default)"
    };

    HomeWifiPage(MainActivity a, LinearLayout page) {
        this.a = a;
        this.page = page;
    }

    void show(final SettingsTab tab) {
        SharedPreferences prefs = a.getSharedPreferences("hmgapi", Context.MODE_PRIVATE);
        curKeepAwakeUnplugged = prefs.getString("wifi_keepawake_unplugged", "30m");
        curKeepAwakePlugged = prefs.getString("wifi_keepawake_plugged", "plugged");

        Ui.text(page, "Automatically connects to your saved Wi-Fi networks after you park and turn off the car so HMG-API "
                + "and enabled mods can sync without using mobile data. Wireless CarPlay and Android Auto pause while Home Wi-Fi "
                + "is active and resume automatically when you start the car.", 16, false, Ui.GREY);

        Switch enableSw = Ui.switchRow(page, "Enable Home Wi-Fi");
        Ui.allowSwitch(a, enableSw, "home_wifi", false, new Runnable() {
            @Override
            public void run() {
                a.plan(); // apply() marks needRestart() once applied
                refreshNow();
            }
        });

        Ui.gap(page, 8);

        // Live connection status & session controls card
        statusCard = Ui.column(a);
        statusCard.setBackground(Ui.box(a, Ui.PANEL, Ui.LINE, 12));
        int pad = Ui.dp(a, 18);
        statusCard.setPadding(pad, Ui.dp(a, 14), pad, Ui.dp(a, 14));
        page.addView(statusCard);

        statusPillHolder = Ui.row(a);
        statusCard.addView(statusPillHolder);
        Ui.pill(statusPillHolder, "Checking Home Wi-Fi status…", Ui.GREY);

        detailView = Ui.text(statusCard, "Loading current Wi-Fi state…", 16, false, Ui.GREY);
        controlsHolder = Ui.column(a);
        statusCard.addView(controlsHolder);

        Ui.line(page);

        // Parked Keep-Awake section (Unplugged vs. Plugged In)
        Ui.text(page, "Keep Awake When Parked", 22, true, Ui.TEXT);
        Ui.text(page, "Keeps the head unit powered with the screen completely dark after you turn off the car so Home Wi-Fi "
                + "stays connected. Automatically coordinates with OpenLink (staying awake for whichever timer is longer) and "
                + "disconnects early if the 12V battery gets low.", 15, false, Ui.GREY);

        LinearLayout kaCard = Ui.column(a);
        kaCard.setBackground(Ui.box(a, Ui.PANEL, Ui.LINE, 10));
        kaCard.setPadding(pad, Ui.dp(a, 10), pad, Ui.dp(a, 10));
        page.addView(kaCard);

        LinearLayout rowUnplugged = Ui.row(a);
        kaCard.addView(rowUnplugged);
        TextView lblUnplugged = Ui.text(rowUnplugged, "When unplugged (on 12V battery)", 18, false, Ui.TEXT);
        lblUnplugged.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        keepAwakeUnpluggedBtn = Ui.button(rowUnplugged, shortKeepAwakeLabel(curKeepAwakeUnplugged), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptKeepAwakeChoice(false);
            }
        });

        LinearLayout rowPlugged = Ui.row(a);
        kaCard.addView(rowPlugged);
        TextView lblPlugged = Ui.text(rowPlugged, "When plugged in (charging or scheduled)", 18, false, Ui.TEXT);
        lblPlugged.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        keepAwakePluggedBtn = Ui.button(rowPlugged, shortKeepAwakeLabel(curKeepAwakePlugged), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptKeepAwakeChoice(true);
            }
        });

        keepAwakeLiveNote = Ui.text(kaCard, "12V battery protection: 65% floor when unplugged • 55% floor when plugged in idle.", 14, false, Ui.GREY);

        Ui.line(page);

        // Saved Networks section
        Ui.text(page, "Saved Networks", 22, true, Ui.TEXT);
        savedHeaderNote = Ui.text(page,
                "Turn off \"Use for Home Wi-Fi\" for dashcams or hotspots without internet so Home Wi-Fi ignores them "
                + "while keeping them saved for other apps.", 15, false, Ui.GREY);
        savedList = Ui.column(a);
        page.addView(savedList);

        Ui.line(page);

        // Nearby Wi-Fi Networks section
        nearbyTitle = Ui.text(page, "Nearby Wi-Fi Networks", 22, true, Ui.TEXT);
        nearbyNote = Ui.text(page, "Tap \"Scan & Connect Now\" above while in Park to scan live for nearby networks.", 15, false, Ui.GREY);
        nearbyList = Ui.column(a);
        page.addView(nearbyList);

        LinearLayout hiddenRow = Ui.row(a);
        page.addView(hiddenRow);
        Ui.button(hiddenRow, "Add Hidden Network…", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptAddHiddenNetwork();
            }
        });

        Ui.line(page);

        // Mobile Data Overrides section
        Ui.text(page, "Mobile Data Overrides", 22, true, Ui.TEXT);
        Ui.text(page, "By default, mods never use the car's cellular connection (including DNS lookups) and only access the "
                + "internet over Home Wi-Fi. You can allow specific mods to use mobile data below at your own risk; changes "
                + "take effect within a few seconds.", 16, false, Ui.GREY);
        cellular = Ui.column(a);
        page.addView(cellular);

        refreshNow();
        startLivePoll();
        loadCellular();
    }

    private boolean isPageVisible() {
        return !a.isFinishing()
                && SettingsTab.current == 2
                && statusCard != null
                && statusCard.getParent() != null
                && page.getWindowToken() != null;
    }

    private void startLivePoll() {
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!isPageVisible()) return;
                refreshNow();
                page.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        page.postDelayed(tick, POLL_INTERVAL_MS);
    }

    void refreshNow() {
        if (pollInFlight) return;
        pollInFlight = true;
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(8000, "wifi", "status");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                pollInFlight = false;
                if (!isPageVisible()) return;
                if (e != null) {
                    renderOfflineState("Cannot reach HMG-API service", Hmgapid.unreachable(e), false);
                    return;
                }
                if (r.code != 0) {
                    String msg = r.output.trim();
                    boolean unapplied = msg.toLowerCase().contains("isn't applied yet");
                    boolean isOff = msg.toLowerCase().contains("is off");
                    if (unapplied) {
                        renderOfflineState("Apply & restart required",
                                "Home Wi-Fi has been switched on, but changes haven't been applied yet. Tap \"Apply Changes\" below, then restart the head unit.",
                                true);
                    } else if (isOff) {
                        renderOfflineState("Home Wi-Fi is disabled",
                                "Turn on \"Enable Home Wi-Fi\" above, tap \"Apply Changes\", and restart the head unit to enable automatic and manual Wi-Fi connections.",
                                false);
                    } else {
                        renderOfflineState("Home Wi-Fi unavailable", msg, false);
                    }
                    return;
                }
                try {
                    JSONObject st = new JSONObject(r.output);
                    lastStatus = st;
                    renderLiveStatus(st);
                } catch (Exception parseErr) {
                    renderOfflineState("Status unavailable", r.output.trim(), false);
                }
            }
        }.start(a);
    }

    private void renderOfflineState(String headline, String detail, boolean showApplyButton) {
        statusPillHolder.removeAllViews();
        Ui.pill(statusPillHolder, headline, showApplyButton ? Ui.WARN : Ui.GREY);
        detailView.setText(detail);
        detailView.setTextColor(showApplyButton ? Ui.WARN : Ui.GREY);

        String ctrlSig = "offline|" + showApplyButton;
        if (!ctrlSig.equals(lastControlsSig)) {
            lastControlsSig = ctrlSig;
            controlsHolder.removeAllViews();
            if (showApplyButton) {
                LinearLayout r = Ui.row(a);
                controlsHolder.addView(r);
                Ui.button(r, "Apply Changes", true, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.plan();
                    }
                });
            }
        }

        String emptySig = "offline";
        if (!emptySig.equals(lastSavedSig)) {
            lastSavedSig = emptySig;
            savedList.removeAllViews();
            Ui.text(savedList, showApplyButton
                    ? "Apply changes and restart first to manage Wi-Fi networks."
                    : "Enable Home Wi-Fi above to view and manage Wi-Fi networks.", 16, false, Ui.GREY);
        }
        if (!emptySig.equals(lastNearbySig)) {
            lastNearbySig = emptySig;
            nearbyTitle.setText("Nearby Wi-Fi Networks");
            nearbyNote.setText("");
            nearbyList.removeAllViews();
        }
    }

    private void renderLiveStatus(JSONObject st) {
        String phase = st.optString("phase", "standby");
        String headline = st.optString("headline", "Standby");
        String detail = st.optString("detail", "");
        boolean active = st.optBoolean("active", false);

        int pillColor = Ui.GREY;
        if ("connected".equals(phase)) pillColor = Ui.GREEN;
        else if ("auth_failed".equals(phase) || "dhcp_timeout".equals(phase)) pillColor = Ui.ERROR;
        else if (active) pillColor = Ui.WARN;

        statusPillHolder.removeAllViews();
        Ui.pill(statusPillHolder, headline, pillColor);
        detailView.setText(detail);
        detailView.setTextColor(("auth_failed".equals(phase) || "dhcp_timeout".equals(phase)) ? Ui.ERROR : Ui.TEXT);

        renderKeepAwakeStatus(st);
        renderControls(active);
        renderSavedNetworks(st.optJSONArray("saved"));
        renderNearbyNetworks(st.optJSONArray("nearby"), active);
    }

    private void renderKeepAwakeStatus(JSONObject st) {
        String u = st.optString("keepAwakeUnplugged", curKeepAwakeUnplugged);
        String p = st.optString("keepAwakePlugged", curKeepAwakePlugged);
        if (!u.isEmpty() && !u.equals(curKeepAwakeUnplugged)) {
            curKeepAwakeUnplugged = u;
            a.getSharedPreferences("hmgapi", Context.MODE_PRIVATE)
                    .edit().putString("wifi_keepawake_unplugged", u).apply();
        }
        if (!p.isEmpty() && !p.equals(curKeepAwakePlugged)) {
            curKeepAwakePlugged = p;
            a.getSharedPreferences("hmgapi", Context.MODE_PRIVATE)
                    .edit().putString("wifi_keepawake_plugged", p).apply();
        }
        if (keepAwakeUnpluggedBtn != null) {
            keepAwakeUnpluggedBtn.setText(shortKeepAwakeLabel(curKeepAwakeUnplugged));
        }
        if (keepAwakePluggedBtn != null) {
            keepAwakePluggedBtn.setText(shortKeepAwakeLabel(curKeepAwakePlugged));
        }
        if (keepAwakeLiveNote != null) {
            boolean pluggedIn = st.optBoolean("pluggedIn", false);
            boolean charging = st.optBoolean("charging", false);
            int b12 = st.optInt("bat12vSoc", -1);
            boolean kaActive = st.optBoolean("keepAwakeActive", false);
            boolean kaUnlimited = st.optBoolean("keepAwakeUnlimited", false);
            int kaRem = st.optInt("keepAwakeRemainingSec", 0);
            boolean extOl = st.optBoolean("keepAwakeExtendedByOpenLink", false);

            StringBuilder sb = new StringBuilder();
            sb.append("Current vehicle state: ");
            sb.append(charging ? "Actively charging" : (pluggedIn ? "Plugged in (idle / scheduled)" : "Unplugged"));
            if (b12 >= 0) sb.append(" • 12V: ").append(b12).append("%");
            if (kaActive) {
                sb.append(" • Keep-awake active");
                if (kaUnlimited) {
                    sb.append(" (while plugged in)");
                } else if (kaRem > 0) {
                    sb.append(" (").append(Math.max(1, (kaRem + 59) / 60)).append(" min left)");
                }
                if (extOl) sb.append(" via OpenLink");
            }
            keepAwakeLiveNote.setText(sb.toString());
            keepAwakeLiveNote.setTextColor(pluggedIn ? Ui.GREEN : Ui.GREY);
        }
    }

    private void promptKeepAwakeChoice(final boolean forPluggedIn) {
        final String[] codes = forPluggedIn ? PLUGGED_CODES : UNPLUGGED_CODES;
        final String[] labels = forPluggedIn ? PLUGGED_LABELS : UNPLUGGED_LABELS;
        String current = forPluggedIn ? curKeepAwakePlugged : curKeepAwakeUnplugged;
        int checkedIdx = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) {
                checkedIdx = i;
                break;
            }
        }
        new AlertDialog.Builder(a)
                .setTitle(forPluggedIn
                        ? "Keep Awake When Plugged In"
                        : "Keep Awake When Unplugged")
                .setSingleChoiceItems(labels, checkedIdx, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        if (which < 0 || which >= codes.length) return;
                        final String nextUnplugged = forPluggedIn ? curKeepAwakeUnplugged : codes[which];
                        final String nextPlugged = forPluggedIn ? codes[which] : curKeepAwakePlugged;
                        curKeepAwakeUnplugged = nextUnplugged;
                        curKeepAwakePlugged = nextPlugged;
                        a.getSharedPreferences("hmgapi", Context.MODE_PRIVATE).edit()
                                .putString("wifi_keepawake_unplugged", nextUnplugged)
                                .putString("wifi_keepawake_plugged", nextPlugged)
                                .apply();
                        if (keepAwakeUnpluggedBtn != null) {
                            keepAwakeUnpluggedBtn.setText(shortKeepAwakeLabel(curKeepAwakeUnplugged));
                        }
                        if (keepAwakePluggedBtn != null) {
                            keepAwakePluggedBtn.setText(shortKeepAwakeLabel(curKeepAwakePlugged));
                        }
                        runWifiAction("keepawake", nextUnplugged, nextPlugged, true);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static String shortKeepAwakeLabel(String code) {
        if ("off".equals(code)) return "Off";
        if ("5m".equals(code)) return "5 minutes";
        if ("15m".equals(code)) return "15 minutes";
        if ("30m".equals(code)) return "30 minutes";
        if ("1h".equals(code)) return "1 hour";
        if ("2h".equals(code)) return "2 hours";
        if ("4h".equals(code)) return "4 hours";
        if ("8h".equals(code)) return "8 hours";
        if ("plugged".equals(code)) return "While plugged in";
        return "30 minutes";
    }

    private void renderControls(boolean active) {
        String sig = active ? "active" : "standby";
        if (sig.equals(lastControlsSig)) return;
        lastControlsSig = sig;
        controlsHolder.removeAllViews();
        Ui.gap(controlsHolder, 6);

        final SharedPreferences prefs = a.getSharedPreferences("hmgapi", Context.MODE_PRIVATE);

        if (!active) {
            LinearLayout btnRow = Ui.row(a);
            controlsHolder.addView(btnRow);

            final CheckBox limitBox = new CheckBox(a);
            final EditText minutesInput = new EditText(a);

            Ui.button(btnRow, "Scan & Connect Now", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String minsArg = "0";
                    if (limitBox.isChecked()) {
                        int m = parseMinutesInput(minutesInput.getText().toString());
                        if (m < 1 || m > 240) {
                            a.message("Home Wi-Fi", "Enter a duration between 1 and 240 minutes, or uncheck \"Auto-disconnect after\" for no time limit while in Park.");
                            return;
                        }
                        prefs.edit().putInt("wifi_now_minutes", m).apply();
                        minsArg = String.valueOf(m);
                    }
                    runWifiAction("scan", minsArg, null, false);
                }
            });

            LinearLayout timerRow = Ui.row(a);
            controlsHolder.addView(timerRow);
            limitBox.setText("Auto-disconnect after ");
            limitBox.setTextColor(Ui.TEXT);
            Ui.size(limitBox, 17);
            limitBox.setChecked(prefs.getBoolean("wifi_now_timer", true));
            timerRow.addView(limitBox);

            minutesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
            minutesInput.setSingleLine(true);
            minutesInput.setEms(3);
            minutesInput.setTextColor(Ui.TEXT);
            Ui.size(minutesInput, 17);
            minutesInput.setText(String.valueOf(prefs.getInt("wifi_now_minutes", 30)));
            minutesInput.setEnabled(limitBox.isChecked());
            timerRow.addView(minutesInput);

            Ui.text(timerRow, " minutes  (always disconnects automatically when shifted out of Park)", 16, false, Ui.GREY);

            limitBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    minutesInput.setEnabled(on);
                    prefs.edit().putBoolean("wifi_now_timer", on).apply();
                }
            });
        } else {
            LinearLayout btnRow = Ui.row(a);
            controlsHolder.addView(btnRow);
            Ui.button(btnRow, "Rescan Networks", true, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(a, "Scanning for nearby Wi-Fi networks…", Toast.LENGTH_SHORT).show();
                    runWifiAction("scan", "30", null, true);
                }
            });
            Ui.button(btnRow, "Disconnect & Resume CarPlay / AA", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    runWifiAction("back", null, null, true);
                }
            });
        }
    }

    private void renderSavedNetworks(JSONArray arr) {
        String sig = arr == null ? "[]" : arr.toString();
        if (sig.equals(lastSavedSig)) return;
        lastSavedSig = sig;
        savedList.removeAllViews();

        if (arr == null || arr.length() == 0) {
            Ui.text(savedList, "No saved Wi-Fi networks yet. Tap \"Scan & Connect Now\" above to pick a nearby network, or use \"Add Hidden Network…\" below.", 16, false, Ui.GREY);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            final JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            final String ssid = item.optString("ssid", "");
            if (ssid.isEmpty()) continue;

            final boolean open = item.optBoolean("open", false);
            final boolean useForHome = item.optBoolean("useForHomeWifi", true);
            final boolean dashcam = item.optBoolean("dashcam", false);
            final boolean connected = item.optBoolean("connected", false);
            final boolean connecting = item.optBoolean("connecting", false);
            final boolean authFailed = item.optBoolean("authFailed", false);
            final boolean inRange = item.optBoolean("inRange", false);
            final int rssi = item.optInt("rssi", 0);
            final int freq = item.optInt("freq", 0);
            final String security = item.optString("security", open ? "Open" : "WPA2");

            int border = authFailed ? Ui.ERROR : (connected ? Ui.ACCENT : Ui.LINE);
            LinearLayout card = Ui.column(a);
            card.setBackground(Ui.box(a, Ui.PANEL, border, 10));
            int pad = Ui.dp(a, 16);
            card.setPadding(pad, Ui.dp(a, 10), pad, Ui.dp(a, 10));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, Ui.dp(a, 6), 0, Ui.dp(a, 6));
            savedList.addView(card, clp);

            // Top row: SSID + status subtitle on left, "Use for Home Wi-Fi" toggle on right
            LinearLayout top = Ui.row(a);
            card.addView(top);

            LinearLayout infoCol = Ui.column(a);
            top.addView(infoCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            String titleText = dashcam ? (ssid + "  [Dashcam]") : ssid;
            Ui.text(infoCol, titleText, 19, true, useForHome ? Ui.TEXT : Ui.GREY);

            String subText;
            int subColor = Ui.GREY;
            if (!useForHome) {
                subText = "Ignored by Home Wi-Fi (profile kept saved for dashcam / other apps)";
                subColor = Ui.GREY;
            } else if (connected) {
                String band = freq <= 0 ? "" : (freq < 3000 ? "2.4 GHz" : "5 GHz");
                String ip = lastStatus != null ? lastStatus.optString("ip", "") : "";
                subText = "Connected"
                        + (!ip.isEmpty() ? " • " + ip : "")
                        + (rssi != 0 ? " • " + rssi + " dBm" : "")
                        + (!band.isEmpty() ? " • " + band : "");
                subColor = Ui.GREEN;
            } else if (authFailed) {
                subText = "Incorrect Wi-Fi password — tap \"Password\" below to update it";
                subColor = Ui.ERROR;
            } else if (connecting) {
                subText = "Connecting…";
                subColor = Ui.WARN;
            } else if (inRange) {
                String band = freq <= 0 ? "" : (freq < 3000 ? "2.4 GHz" : "5 GHz");
                subText = "Enabled for Home Wi-Fi • In range (" + rssi + " dBm"
                        + (!band.isEmpty() ? ", " + band : "") + ", " + security + ")";
                subColor = Ui.TEXT;
            } else {
                subText = "Enabled for Home Wi-Fi (" + security + ") • Out of range / Standby";
                subColor = Ui.GREY;
            }
            Ui.text(infoCol, subText, 15, false, subColor);

            LinearLayout useToggleBox = Ui.row(a);
            top.addView(useToggleBox);
            TextView useLbl = new TextView(a);
            useLbl.setText("Use for Home Wi-Fi  ");
            Ui.size(useLbl, 16);
            useLbl.setTextColor(useForHome ? Ui.TEXT : Ui.GREY);
            useToggleBox.addView(useLbl);

            final Switch useSw = new Switch(a);
            useSw.setChecked(useForHome);
            useSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean checked) {
                    setNetworkUseForHomeWifi(useSw, ssid, checked);
                }
            });
            useToggleBox.addView(useSw);

            // Bottom row: Connect / Password / Forget buttons
            LinearLayout actions = Ui.row(a);
            card.addView(actions);

            if (useForHome && !connected) {
                Ui.button(actions, authFailed ? "Retry Connect" : "Connect", !connecting, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (authFailed) {
                            promptPasswordAndConnect(ssid, false, true);
                        } else {
                            runWifiAction("connect", ssid, null, false);
                        }
                    }
                });
            }
            if (!open) {
                Ui.button(actions, "Password", authFailed, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        promptPasswordAndConnect(ssid, false, true);
                    }
                });
            }
            Ui.button(actions, "Forget", false, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirmForgetNetwork(ssid, dashcam, useForHome);
                }
            });
        }
    }

    private void renderNearbyNetworks(JSONArray arr, boolean active) {
        nearbyTitle.setText(active ? "Nearby Wi-Fi Networks (Live Scan)" : "Nearby Wi-Fi Networks");
        if (active) {
            nearbyNote.setText(arr != null && arr.length() > 0
                    ? "Tap \"Connect\" next to any network below to join it immediately."
                    : "Scanning for Wi-Fi networks in range…");
        } else {
            nearbyNote.setText(arr != null && arr.length() > 0
                    ? "Showing networks from the most recent scan. Tap \"Scan & Connect Now\" above in Park to scan live."
                    : "No recent scan results yet. Shift to Park and tap \"Scan & Connect Now\" above to scan nearby Wi-Fi networks.");
        }

        String sig = (active ? "1|" : "0|") + (arr == null ? "[]" : arr.toString());
        if (sig.equals(lastNearbySig)) return;
        lastNearbySig = sig;
        nearbyList.removeAllViews();

        if (arr == null || arr.length() == 0) {
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            final JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            final String ssid = item.optString("ssid", "");
            if (ssid.isEmpty()) continue;

            final int rssi = item.optInt("rssi", 0);
            final String band = item.optString("band", "");
            final String security = item.optString("security", "WPA2");
            final boolean open = item.optBoolean("open", false);
            final boolean supported = item.optBoolean("supported", true);
            final boolean isSaved = item.optBoolean("saved", false);
            final boolean useForHome = item.optBoolean("useForHomeWifi", true);
            final boolean connected = item.optBoolean("connected", false);
            final boolean dashcam = item.optBoolean("dashcam", false);

            LinearLayout row = Ui.row(a);
            row.setBackground(Ui.box(a, Ui.PANEL, connected ? Ui.ACCENT : Ui.LINE, 8));
            int pad = Ui.dp(a, 14);
            row.setPadding(pad, Ui.dp(a, 8), pad, Ui.dp(a, 8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, Ui.dp(a, 4), 0, Ui.dp(a, 4));
            nearbyList.addView(row, rlp);

            LinearLayout left = Ui.column(a);
            row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Ui.text(left, ssid, 18, true, supported ? Ui.TEXT : Ui.GREY);
            StringBuilder meta = new StringBuilder();
            meta.append(rssi).append(" dBm");
            if (!band.isEmpty()) meta.append(" • ").append(band);
            meta.append(" • ").append(security);
            if (connected) meta.append(" • Connected");
            else if (isSaved && !useForHome) meta.append(" • Saved (Ignored by Home Wi-Fi)");
            else if (isSaved) meta.append(" • Saved");
            else if (dashcam) meta.append(" • Dashcam network");
            Ui.text(left, meta.toString(), 15, false, connected ? Ui.GREEN : Ui.GREY);

            if (!supported) {
                TextView unsup = new TextView(a);
                unsup.setText("Unsupported");
                Ui.size(unsup, 15);
                unsup.setTextColor(Ui.GREY);
                row.addView(unsup);
            } else if (connected) {
                TextView badge = new TextView(a);
                badge.setText("Connected");
                Ui.size(badge, 16);
                badge.setTextColor(Ui.GREEN);
                row.addView(badge);
            } else {
                Ui.button(row, isSaved ? "Connect" : "Join", false, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (isSaved && useForHome) {
                            runWifiAction("connect", ssid, null, false);
                        } else if (open) {
                            runWifiAction("connect", ssid, "", false);
                        } else {
                            promptPasswordAndConnect(ssid, open, true);
                        }
                    }
                });
            }
        }
    }

    private void setNetworkUseForHomeWifi(final Switch sw, final String ssid, final boolean use) {
        sw.setEnabled(false);
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call("wifi", "use", ssid, use ? "on" : "off");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                sw.setEnabled(true);
                if (e != null || r.code != 0) {
                    sw.setOnCheckedChangeListener(null);
                    sw.setChecked(!use);
                    sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b, boolean checked) {
                            setNetworkUseForHomeWifi(sw, ssid, checked);
                        }
                    });
                    a.message("Home Wi-Fi", e != null ? Hmgapid.unreachable(e) : r.output.trim());
                    return;
                }
                Toast.makeText(a, r.output.trim(), Toast.LENGTH_SHORT).show();
                lastSavedSig = "";
                lastNearbySig = "";
                refreshNow();
            }
        }.start(a);
    }

    private void confirmForgetNetwork(final String ssid, boolean dashcam, boolean useForHome) {
        String msg = (dashcam || !useForHome)
                ? "Remove \"" + ssid + "\" completely? Deleting a dashcam network here also removes its saved profile from Android. "
                  + "If you only want Home Wi-Fi to ignore it, leave it saved with \"Use for Home Wi-Fi\" switched off."
                : "Remove \"" + ssid + "\" from saved Wi-Fi networks?";
        new AlertDialog.Builder(a)
                .setTitle("Forget " + ssid + "?")
                .setMessage(msg)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget Network", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        runWifiAction("forget", ssid, null, true);
                    }
                })
                .show();
    }

    private void promptPasswordAndConnect(final String ssid, final boolean open, final boolean connectNow) {
        if (open) {
            runWifiAction(connectNow ? "connect" : "join", ssid, "", false);
            return;
        }
        checkParkedBeforeTyping(new Runnable() {
            @Override
            public void run() {
                int pad = Ui.dp(a, 20);
                LinearLayout box = Ui.column(a);
                box.setPadding(pad, Ui.dp(a, 12), pad, Ui.dp(a, 8));

                TextView hint = new TextView(a);
                hint.setText("Enter the Wi-Fi password for \"" + ssid + "\" (8 to 63 characters):");
                Ui.size(hint, 16);
                hint.setTextColor(Ui.TEXT);
                box.addView(hint);

                Ui.gap(box, 8);

                final EditText keyInput = new EditText(a);
                keyInput.setSingleLine(true);
                keyInput.setHint("Wi-Fi password");
                keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                Ui.size(keyInput, 18);
                box.addView(keyInput);

                final CheckBox showPass = new CheckBox(a);
                showPass.setText("Show password");
                Ui.size(showPass, 16);
                showPass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        int pos = keyInput.getSelectionEnd();
                        keyInput.setInputType(InputType.TYPE_CLASS_TEXT
                                | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                           : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                        if (pos >= 0) keyInput.setSelection(pos);
                    }
                });
                box.addView(showPass);

                final AlertDialog dlg = new AlertDialog.Builder(a)
                        .setTitle("Connect to " + ssid)
                        .setView(box)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton(connectNow ? "Connect" : "Save", null)
                        .create();
                dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface di) {
                        Button okBtn = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                        okBtn.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                String pass = keyInput.getText().toString();
                                if (!isValidWpaKey(pass)) {
                                    Toast.makeText(a, "Password must be 8 to 63 characters (or 64 hex digits).", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                dlg.dismiss();
                                runWifiAction(connectNow ? "connect" : "join", ssid, pass, false);
                            }
                        });
                    }
                });
                dlg.show();
            }
        });
    }

    private void promptAddHiddenNetwork() {
        checkParkedBeforeTyping(new Runnable() {
            @Override
            public void run() {
                int pad = Ui.dp(a, 20);
                LinearLayout box = Ui.column(a);
                box.setPadding(pad, Ui.dp(a, 12), pad, Ui.dp(a, 8));

                final EditText ssidInput = new EditText(a);
                ssidInput.setSingleLine(true);
                ssidInput.setHint("Network name (SSID)");
                Ui.size(ssidInput, 18);
                box.addView(ssidInput);

                Ui.gap(box, 8);

                final CheckBox openNetBox = new CheckBox(a);
                openNetBox.setText("Open network (no password)");
                Ui.size(openNetBox, 16);
                box.addView(openNetBox);

                Ui.gap(box, 6);

                final EditText passInput = new EditText(a);
                passInput.setSingleLine(true);
                passInput.setHint("Wi-Fi password (8 to 63 characters)");
                passInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                Ui.size(passInput, 18);
                box.addView(passInput);

                final CheckBox showPass = new CheckBox(a);
                showPass.setText("Show password");
                Ui.size(showPass, 16);
                box.addView(showPass);

                openNetBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean isOpen) {
                        passInput.setEnabled(!isOpen);
                        showPass.setEnabled(!isOpen);
                    }
                });
                showPass.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton b, boolean checked) {
                        int pos = passInput.getSelectionEnd();
                        passInput.setInputType(InputType.TYPE_CLASS_TEXT
                                | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                                           : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                        if (pos >= 0) passInput.setSelection(pos);
                    }
                });

                final AlertDialog dlg = new AlertDialog.Builder(a)
                        .setTitle("Add Hidden Wi-Fi Network")
                        .setView(box)
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Save Only", null)
                        .setPositiveButton("Save & Connect", null)
                        .create();
                dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                    @Override
                    public void onShow(DialogInterface di) {
                        View.OnClickListener handler = new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                boolean connectNow = (v == dlg.getButton(AlertDialog.BUTTON_POSITIVE));
                                String ssid = ssidInput.getText().toString().trim();
                                if (ssid.isEmpty() || ssid.length() > 32) {
                                    Toast.makeText(a, "Enter a valid network name (1 to 32 characters).", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                boolean isOpen = openNetBox.isChecked();
                                String pass = isOpen ? "" : passInput.getText().toString();
                                if (!isOpen && !isValidWpaKey(pass)) {
                                    Toast.makeText(a, "Password must be 8 to 63 characters (or 64 hex digits).", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                dlg.dismiss();
                                runWifiAction(connectNow ? "connect" : "join", ssid, pass, false);
                            }
                        };
                        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(handler);
                        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(handler);
                    }
                });
                dlg.show();
            }
        });
    }

    private void checkParkedBeforeTyping(final Runnable onParked) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                return Hmgapid.call(4000, "vehicle", "gear.park");
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                boolean parked = e == null && r.code == 0 && r.output.trim().endsWith(" 1");
                if (!parked) {
                    a.message("Shift to Park", "Shift to Park (P) before typing a Wi-Fi network name or password.");
                    return;
                }
                onParked.run();
            }
        }.start(a);
    }

    private void runWifiAction(final String verb, final String arg1, final String pskOrNull, final boolean quietToastOnSuccess) {
        new Ui.Job<Hmgapid.Result>() {
            @Override
            Hmgapid.Result work() throws Exception {
                if (arg1 == null) return Hmgapid.call("wifi", verb);
                if (pskOrNull == null) return Hmgapid.call("wifi", verb, arg1);
                return Hmgapid.call("wifi", verb, arg1, pskOrNull);
            }

            @Override
            void done(Hmgapid.Result r, Exception e) {
                lastSavedSig = "";
                lastNearbySig = "";
                refreshNow();
                if (e != null) {
                    a.message("Home Wi-Fi", Hmgapid.unreachable(e));
                    return;
                }
                String msg = r.output.trim();
                if (r.code != 0) {
                    a.message("Home Wi-Fi", msg.isEmpty() ? "Command failed." : msg);
                } else if (!msg.isEmpty()) {
                    if (quietToastOnSuccess || "connect".equals(verb) || "scan".equals(verb)) {
                        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
                    } else {
                        a.message("Home Wi-Fi", msg);
                    }
                }
            }
        }.start(a);
    }

    private static int parseMinutesInput(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean isValidWpaKey(String pass) {
        if (pass == null) return false;
        int len = pass.length();
        if (len >= 8 && len <= 63) return true;
        if (len == 64) {
            for (int i = 0; i < 64; i++) {
                char c = pass.charAt(i);
                boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if (!hex) return false;
            }
            return true;
        }
        return false;
    }

    // ---- Per-mod Mobile Data Overrides (hmgapi.json overrides.allow_cellular) ----

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
                    rows.add(new String[] { id, name, enabled ? name : name + " (disabled)", allowed.contains(id) ? "1" : "0" });
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
                    Ui.text(cellular, Hmgapid.unreachable(e), 16, false, Ui.GREY);
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
                        .setMessage("Apps and background services in \"" + name + "\" will be allowed to send network traffic "
                                + "and DNS queries over the car's cellular connection, at your own risk.")
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
                        .setPositiveButton("Allow Mobile Data", new DialogInterface.OnClickListener() {
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
                    Toast.makeText(a, "Could not save mobile data setting.", Toast.LENGTH_SHORT).show();
                    return;
                }
                a.refreshStatus();
            }
        }.start(a);
    }
}

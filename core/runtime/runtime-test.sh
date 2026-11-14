#!/bin/sh
# Off-car test of what mods run besides hooks (ModRuntime, ModApps): the state
# files hmgapid reads (apps.txt, services.txt, usb.txt), the plan's lines, and
# `engine apps` installing, turning off and removing the mods' own apps
# (HMGAPI_HOST: pm is recorded in actions.log, packages.list is a file).
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
D=$R/data/hmgapi
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
actions() { cat "$R/actions.log" 2>/dev/null; : > "$R/actions.log"; }

mkdir -p "$D/mods/cam/app" "$D/mods/cam/svc" "$D/mods/game/app" "$R/data/system"
: > "$R/data/system/packages.list"
echo apk1 > "$D/mods/cam/app/Cam.apk"
echo jar > "$D/mods/cam/svc/cam.jar"
echo 'exec sleep 1' > "$D/mods/cam/svc/watch.sh"
cat > "$D/mods/cam/mod.json" <<'J'
{"id": "cam", "version": "1.0.0",
 "apps": [{"apk": "app/Cam.apk", "package": "dev.example.cam"}],
 "services": [{"name": "recorder", "jar": "svc/cam.jar", "class": "dev.example.cam.Main", "args": ["--quiet", "dir=/storage"], "user": "system", "start": "boot"},
              {"name": "watch", "script": "svc/watch.sh", "user": "app", "restart": false}],
 "usb": ["Dashcam"]}
J
echo apk2 > "$D/mods/game/app/Game.apk"
cat > "$D/mods/game/mod.json" <<'J'
{"id": "game", "version": "1.0.0",
 "apps": [{"apk": "app/Game.apk", "package": "dev.example.game"}, {"apk": "app/Gone.apk", "package": "dev.example.gone"}],
 "services": [{"name": "Bad Name", "script": "x.sh"}, {"name": "esc", "script": "../../x.sh"}, {"name": "nocls", "jar": "app/Game.apk"},
              {"name": "who", "script": "app/Game.apk", "user": "nobody"}, {"name": "args", "script": "app/Game.apk", "args": ["a b"]}],
 "usb": ["two words"]}
J

eng publish-state
check "publish runs" 0 $rc
check "apps.txt: enabled mods' apps" "dev.example.cam cam
dev.example.game game" "$(cat $D/state/apps.txt)"
check "services.txt: hmgapid's lines" "cam recorder system boot yes jar svc/cam.jar dev.example.cam.Main --quiet dir=/storage
cam watch app:dev.example.cam android no script svc/watch.sh -" "$(grep -v '^hmgapi log ' $D/state/services.txt)"
check "usb.txt" "cam Dashcam" "$(cat $D/state/usb.txt)"
has "missing apk reported" "game: app dev.example.gone: no app/Gone.apk in the mod" "$out"
has "bad service name reported" "game: service: \"name\" must be" "$out"
has "path outside the mod refused" "game: service esc: ../../x.sh must be a file in the mod" "$out"
has "jar without class refused" "game: service nocls: a jar service needs its main \"class\"" "$out"
has "unknown user refused" "game: service who: user must be" "$out"
has "args with spaces refused" "game: service args: args may be" "$out"
has "usb folder with a space refused" "usb folder \"two words\"" "$out"

eng plan
has "plan: app" "app      dev.example.cam (app/Cam.apk) [cam]" "$out"
has "plan: service" "service  cam/recorder as system, at boot, restarted if it stops [cam]" "$out"
has "plan: one-shot service" "service  cam/watch as app:dev.example.cam, at android [cam]" "$out"
has "plan: usb" "usb      Dashcam/ on a stick [cam]" "$out"
has "plan: problems shown" "problem  game: service esc" "$out"

# ---- apps
actions >/dev/null
# export, then unset: VAR=1 eng (a function) leaves VAR set after it in bash 3.2's sh (macOS)
export HMGAPI_ANDROID_DOWN=1; eng apps; unset HMGAPI_ANDROID_DOWN
has "Android down: waits" "waiting for Android" "$out"
check "... and does nothing" "" "$(actions)"
eng apps
has "installed" "apps: dev.example.cam installed (cam)" "$out"
has "both" "apps: dev.example.game installed (game)" "$out"
check "pm calls" "pm install -r Cam.apk dev.example.cam
pm install -r Game.apk dev.example.game" "$(actions)"
check "uninstaller's list" "dev.example.cam
dev.example.game" "$(cat $D/state/apps.installed)"
eng apps
check "nothing to do the second time" "" "$(actions)"
echo apk1-v2 > "$D/mods/cam/app/Cam.apk"
eng apps
has "changed apk: updated" "apps: dev.example.cam updated (cam)" "$out"
check "... with pm install -r (data kept)" "pm install -r Cam.apk dev.example.cam" "$(actions)"
: > "$D/mods/game/.disabled"
eng publish-state
hasnt "mod off: its app loses access" "dev.example.game" "$(cat $D/state/apps.txt)"
actions >/dev/null
eng apps
has "mod off: app turned off" "dev.example.game turned off (game is off; its data stays)" "$out"
check "pm disable" "pm disable dev.example.game" "$(actions)"
rm "$D/mods/game/.disabled"
eng apps
has "mod on again: app turned on" "dev.example.game turned on (game)" "$out"
check "pm enable" "pm enable dev.example.game" "$(actions)"
rm -rf "$D/mods/game"
eng apps
has "mod removed: app removed" "dev.example.game removed" "$out"
check "pm uninstall" "pm uninstall dev.example.game" "$(actions)"
check "uninstaller's list follows" "dev.example.cam" "$(cat $D/state/apps.installed)"

# an app installed some other way
mkdir -p "$D/mods/kb/app"
echo kb > "$D/mods/kb/app/Kb.apk"
printf '{"id": "kb", "version": "1.0.0", "apps": [{"apk": "app/Kb.apk", "package": "org.example.keyboard"}]}\n' > "$D/mods/kb/mod.json"
echo "org.example.keyboard 10077 0 /data/data/org.example.keyboard default none" >> "$R/data/system/packages.list"
eng apps
has "installed by hand: left alone" "org.example.keyboard (kb) is already installed some other way: left alone" "$out"
check "... no pm call" "" "$(actions)"
mkdir -p "$D/settings"
printf '{"overrides": {"replace_apps": ["org.example.keyboard"]}}\n' > "$D/settings/hmgapi.json"
eng apps
has "overrides.replace_apps: the mod takes it over" "org.example.keyboard updated (kb)" "$out"
rm "$D/settings/hmgapi.json"
export HMGAPI_PM_FAIL=1; eng apps; unset HMGAPI_PM_FAIL
echo apk1-v3 > "$D/mods/cam/app/Cam.apk"
export HMGAPI_PM_FAIL=1; eng apps; unset HMGAPI_PM_FAIL
has "pm failure reported" "WARNING: dev.example.cam (cam) didn't install: Failure [TEST]" "$out"
eng apps
has "... and tried again next time" "dev.example.cam updated (cam)" "$out"

# HMG-API uninstalled (apps turned off) and installed again
: > "$D/state/apps.alloff"
actions >/dev/null
eng apps
has "reinstall: apps turned on again" "dev.example.cam turned on (cam)" "$out"
check "... marker used up" no "$([ -e $D/state/apps.alloff ] && echo yes || echo no)"

# two mods, one app
mkdir -p "$D/mods/zz/app"
echo z > "$D/mods/zz/app/C.apk"
printf '{"id": "zz", "version": "1.0.0", "apps": [{"apk": "app/C.apk", "package": "dev.example.cam"}]}\n' > "$D/mods/zz/mod.json"
eng publish-state
has "an app only one mod may own" "left out: zz: app dev.example.cam is already cam's" "$out"
rm -r "$D/mods/zz"

# vehicle readings and the driving restriction
check "no readings: no vehicle service" "" "$(grep '^hmgapi vehicle ' $D/state/services.txt)"
check "HMG-API's log keeper always runs" "hmgapi log root boot yes jar /system/framework/hmgapi-hook.jar hmgapi.engine.LogKeeper" "$(grep '^hmgapi log ' $D/state/services.txt)"
mkdir -p "$D/mods/tube/app"
echo t > "$D/mods/tube/app/T.apk"
cat > "$D/mods/tube/mod.json" <<'J'
{"id": "tube", "version": "1.0.0",
 "apps": [{"apk": "app/T.apk", "package": "dev.example.tube"}],
 "vehicle": {"read": ["speed", "gear.park", "vin", "speed"]},
 "not_while_driving": ["dev.example.tube", "com.android.settings"]}
J
eng publish-state
check "vehicle.txt: declared readings, once each" "tube speed
tube gear.park" "$(cat $D/state/vehicle.txt)"
has "unknown reading refused" "tube: vehicle reading \"vin\" isn't one HMG-API offers" "$out"
check "the vehicle service runs while a mod reads" "hmgapi vehicle root android yes jar /system/framework/hmgapi-hook.jar hmgapi.vehicle.Hub" "$(grep '^hmgapi vehicle ' $D/state/services.txt)"
has "not_while_driving: only the mod's own apps" "tube: not_while_driving: com.android.settings isn't one of the mod's apps" "$out"
has "state.json lists it for the app's process" '"not_while_driving":["dev.example.tube"]' "$(tr -d ' \n' < $D/public/state.json)"
eng plan
has "plan: readings" "vehicle  reads speed, gear.park [tube]" "$out"
has "plan: driving" "driving  dev.example.tube closes while the car's driving restriction is on [tube]" "$out"
# vehicle commands
cat > "$D/mods/tube/mod.json" <<'J'
{"id": "tube", "version": "1.0.0",
 "apps": [{"apk": "app/T.apk", "package": "dev.example.tube"}],
 "vehicle": {"commands": ["charge_door", "startup_notice", "launch_rockets"]}}
J
eng publish-state
check "commands.txt: declared commands" "tube charge_door
tube startup_notice" "$(cat $D/state/commands.txt)"
has "unknown command refused" 'tube: vehicle command "launch_rockets" isn'"'"'t one HMG-API offers' "$out"
check "commands alone start the vehicle service" "hmgapi vehicle root android yes jar /system/framework/hmgapi-hook.jar hmgapi.vehicle.Hub" "$(grep '^hmgapi vehicle ' $D/state/services.txt)"
eng plan
has "plan: a command with its guard" "vehicle  may charge_door (gear in P; once every 5 s) [tube]" "$out"
has "plan: the startup notice says it needs the owner" "vehicle  may startup_notice (only if the owner allowed it" "$out"

# the owner's MICOM watch keeps the vehicle service running without any mod
rm -r "$D/mods/tube"; mkdir -p "$D/mods/tube/app"; echo t > "$D/mods/tube/app/T.apk"
printf '{"id": "tube", "version": "1.0.0", "apps": [{"apk": "app/T.apk", "package": "dev.example.tube"}]}\n' > "$D/mods/tube/mod.json"
eng publish-state
check "no readings, no watch: no vehicle service" "" "$(grep '^hmgapi vehicle ' $D/state/services.txt)"
echo all > "$D/state/micom-watch.txt"
eng publish-state
check "MICOM watch on: the vehicle service runs" "hmgapi vehicle root android yes jar /system/framework/hmgapi-hook.jar hmgapi.vehicle.Hub" "$(grep '^hmgapi vehicle ' $D/state/services.txt)"
rm "$D/state/micom-watch.txt"

# phone projection: Android Auto density, sensors, events
cat > "$D/mods/tube/mod.json" <<'J'
{"id": "tube", "version": "1.0.0", "apps": [{"apk": "app/T.apk", "package": "dev.example.tube"}],
 "projection": {"aa_density": 200, "aa_sensors": {"remove": ["speed", "gps"], "add": [23]}, "aa_fuel_types": [10],
                "aa_ev_connectors": [4, 1], "events": true}}
J
mkdir -p "$D/mods/dpi"
printf '{"id": "dpi", "version": "1.0.0", "projection": {"aa_density": 160, "aa_sensors": {"remove": ["speed"]}, "aa_ev_connectors": [1, 5]}}\n' > "$D/mods/dpi/mod.json"
eng publish-state
st=$(tr -d ' \n' < $D/public/state.json)
has "density: the first by id wins" '"projection":{"aa_density":160,"aa_density_by":"dpi"' "$st"
has "... and the owner is told how to pick" 'conflict: tube asks Android Auto density 200, dpi 160; dpi has it' "$out"
has "sensors, fuel and connectors: together" '"aa_sensors_remove":["speed"],"aa_sensors_add":[23],"aa_fuel_types":[10],"aa_ev_connectors":[1,5,4]' "$st"
has "events: the asking mod's apps" '"events":["dev.example.tube"]' "$st"
has "only speed can be left out" "tube: projection aa_sensors remove: gps (only [speed])" "$out"
mkdir -p "$D/settings"; printf '{"overrides": {"projection": {"aa_density": "tube"}}}\n' > "$D/settings/hmgapi.json"
eng publish-state
has "the owner's pick wins" '"aa_density":200,"aa_density_by":"tube"' "$(tr -d ' \n' < $D/public/state.json)"
eng plan
has "plan: density" "projection Android Auto density 200 [tube]" "$out"
has "plan: sensor removal says it's for every mod" "projection Android Auto: the phone isn't offered [speed] (for every mod) [dpi]" "$out"
rm -r "$D/mods/dpi" "$D/settings/hmgapi.json"

# side panel pages: the mod's own apps' widgets
cat > "$D/mods/tube/mod.json" <<'J'
{"id": "tube", "version": "1.0.0",
 "apps": [{"apk": "app/T.apk", "package": "dev.example.tube"}],
 "provides": {"panel": [{"title": "Now playing", "widget": "dev.example.tube/.NowWidget"},
                        {"title": "Other", "widget": "com.android.music/.Widget"},
                        {"widget": "dev.example.tube/.NoTitle"}]}}
J
eng publish-state
has "panel page published for the panel's process" '"panel":[{"title":"Nowplaying","widget":"dev.example.tube/.NowWidget"}]' "$(tr -d ' \n' < $D/public/state.json)"
has "panel: only the mod's own apps' widgets" "tube: panel: com.android.music isn't one of the mod's apps" "$out"
has "panel: a page needs a title" "tube: panel dev.example.tube/.NoTitle: needs a \"title\"" "$out"
eng plan
has "plan: panel page" "panel    \"Now playing\" (dev.example.tube/.NowWidget) in the side panel [tube]" "$out"
# button bindings
cat > "$D/mods/tube/mod.json" <<'J'
{"id": "tube", "version": "1.0.0",
 "apps": [{"apk": "app/T.apk", "package": "dev.example.tube"}],
 "input": [{"key": "map", "press": "short", "broadcast": "dev.example.tube.DOOR"},
           {"key": "tune_enter", "press": "hold", "open": "dev.example.tube/.Grid"},
           {"key": "custom", "press": "hold", "stock": "keep", "broadcast": "x.y"},
           {"key": "seek_up", "press": "x2", "broadcast": "x.y"},
           {"key": "sos", "press": "short", "stock": "replace", "broadcast": "x.y"},
           {"key": "warp", "press": "short", "broadcast": "x.y"},
           {"key": "mute", "press": "x9", "broadcast": "x.y"},
           {"key": "ptt", "press": "short", "open": "com.other/.A"},
           {"key": "mute", "press": "down", "stock": "replace", "broadcast": "x.y"},
           {"key": "call", "press": "short"},
           {"key": "ptt", "press": "short", "stock": "replace", "when": "settings.voice=short", "broadcast": "x.v"},
           {"key": "ptt", "press": "hold", "when": "settings.voice=hold", "broadcast": "x.v"},
           {"key": "phone", "press": "short", "when": "rm -rf", "broadcast": "x.y"},
           {"key": "navi", "press": "short", "send": "warp short"},
           {"key": "media", "press": "short", "release": "ptt short", "broadcast": "x.y"},
           {"key": "seek_down", "press": "hold", "send": "ptt hold", "release": "ptt short"}]}
J
mkdir -p "$D/mods/grid/app"
echo g > "$D/mods/grid/app/G.apk"
cat > "$D/mods/grid/mod.json" <<'J'
{"id": "grid", "version": "1.0.0", "apps": [{"apk": "app/G.apk", "package": "dev.example.grid"}],
 "input": [{"key": "tune_enter", "press": "hold", "stock": "replace", "broadcast": "dev.example.grid.OPEN"},
           {"key": "map", "press": "short", "broadcast": "dev.example.grid.ALSO"}]}
J
eng publish-state
st=$(tr -d ' \n' < $D/public/state.json)
has "binding published with its key code and the mod's apps" '{"mod":"tube","key":"map","keycode":"KEYCODE_SHORTCUT_MAP","press":"short","stock":"keep","broadcast":"dev.example.tube.DOOR","packages":["dev.example.tube"]}' "$st"
has "two mods keeping the same press: both bound" '"mod":"grid","key":"map","keycode":"KEYCODE_SHORTCUT_MAP","press":"short"' "$st"
has "two mods replacing the same press: the first by id wins" '"mod":"grid","key":"tune_enter","keycode":"KEYCODE_TUNE_ENTER","press":"hold","stock":"replace"' "$st"
hasnt "... the other is left out" '"mod":"tube","key":"tune_enter"' "$st"
has "... and the owner is told how to pick" 'conflict: tube and grid both take tune_enter hold; grid has it (pick another: hmgapi.json overrides.input' "$out"
has "sos stays the car's" "tube: input sos short: sos stays the car's" "$out"
has "unknown key refused" 'tube: input: key "warp" isn'"'"'t one HMG-API knows' "$out"
has "unknown press refused" "tube: input mute x9: multi-press isn't offered" "$out"
has "multi-press refused, with the reason" "tube: input seek_up x2: multi-press isn't offered (it would delay every press of the key" "$out"
has "hold can't keep the car's hold" "tube: input custom hold: hold always takes the car's hold" "$out"
has "open: only the mod's own activities" "tube: input ptt short: open must be <one of the mod's apps>/<activity>" "$out"
has "down can only watch" "tube: input mute down: \"down\" can only watch" "$out"
has "a binding needs something to do" "tube: input call short: needs \"broadcast\"" "$out"
has "send: only known keys" "tube: input navi short: send must be" "$out"
has "release: hold bindings only" "tube: input media short: \"release\" is for hold bindings" "$out"
has "a remap published with key codes" '"key":"seek_down","keycode":"KEYCODE_SEEK_DOWN","press":"hold","stock":"replace","send":"ptthold","send_keycode":"KEYCODE_SHORTCUT_PTT","release":"pttshort","release_keycode":"KEYCODE_SHORTCUT_PTT"' "$st"
has "when: only settings conditions" "tube: input phone short: when must be settings.<key>" "$out"
hasnt "when: off by the mod's setting (none set): no ptt binding" '"key":"ptt"' "$st"
mkdir -p "$D/settings"; printf '{"voice": "hold"}\n' > "$D/settings/tube.json"
eng publish-state
st=$(tr -d ' \n' < $D/public/state.json)
has "when: the owner's choice picks the binding" '"mod":"tube","key":"ptt","keycode":"KEYCODE_SHORTCUT_PTT","press":"hold"' "$st"
hasnt "... and only that one" '"press":"short","stock":"replace","broadcast":"x.v"' "$st"
rm "$D/settings/tube.json"
mkdir -p "$D/settings"
printf '{"overrides": {"input": {"tune_enter hold": "tube"}}}\n' > "$D/settings/hmgapi.json"
eng publish-state
st=$(tr -d ' \n' < $D/public/state.json)
has "the owner's pick wins" '"mod":"tube","key":"tune_enter","keycode":"KEYCODE_TUNE_ENTER","press":"hold","stock":"replace","open":"dev.example.tube/.Grid"' "$st"
has "... noted as the owner's choice" "tube has it (the owner's choice)" "$out"
eng plan
has "plan: button" "button   map short -> tells its app dev.example.tube.DOOR [tube]" "$out"
has "plan: replacing button" "button   tune_enter hold (instead of the car's) -> opens dev.example.tube/.Grid [tube]" "$out"
rm -r "$D/mods/grid" "$D/settings/hmgapi.json"
touch "$D/mods/tube/.disabled"
eng publish-state
check "mod off: no readings" "" "$(cat $D/state/vehicle.txt)"
check "... and no vehicle service" "" "$(grep '^hmgapi vehicle ' $D/state/services.txt)"

# BLE central declaration
mkdir -p "$D/mods/batt/app" "$D/mods/badble"
echo b > "$D/mods/batt/app/Batt.apk"
cat > "$D/mods/batt/mod.json" <<'J'
{"id": "batt", "version": "1.0.0",
 "apps": [{"apk": "app/Batt.apk", "package": "dev.example.batt"}],
 "ble": {"central": true}}
J
printf '{"id": "badble", "version": "1.0.0", "ble": true}\n' > "$D/mods/badble/mod.json"
eng publish-state
has "state.json marks BLE for com.android.bluetooth" '"batt":{"enabled":true,"version":"1.0.0","live":false,"settings":{},"apps":["dev.example.batt"],"ble":true}' "$(tr -d ' \n' < $D/public/state.json)"
has "bad ble declaration reported" 'badble: ble: needs {"central": true}' "$out"
check "no unused state/ble.txt written" no "$([ -e "$D/state/ble.txt" ] && echo yes || echo no)"
eng plan
has "plan: ble" "ble      BLE scanner and GATT central [batt]" "$out"
has "plan: bad ble problem" 'problem  badble: ble: needs {"central": true}' "$out"
touch "$D/mods/batt/.disabled"
eng publish-state
has "mod off: state.json marks batt disabled" '"batt":{"enabled":false,"version":"1.0.0","live":false,"settings":{},"apps":["dev.example.batt"],"ble":true}' "$(tr -d ' \n' < $D/public/state.json)"
exit $fail

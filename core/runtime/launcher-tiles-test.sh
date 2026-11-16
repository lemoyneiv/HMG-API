#!/bin/sh
# Off-car unit and CLI test for LauncherTilesStore and `engine launcher-tiles[-set]`.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
T=$(mktemp -d)
trap 'rm -rf "$CP" "$T"' EXIT
sh tools/engine-classes.sh "$CP"
javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/LauncherTilesTest.java
java -cp "$CP" hmgapi.engine.LauncherTilesTest

R=$T/root
D=$R/data/hmgapi
CAT=$R/data/data/com.android.launcher3/files
mkdir -p "$D" "$CAT"
cat > "$CAT/hmgapi-launcher-catalog.json" <<'J'
{
  "tiles": [
    {"key": "hmgapi.app/hmgapi.app.MainActivity", "packageName": "hmgapi.app", "className": "hmgapi.app.MainActivity", "title": "HMG-API", "kind": "package", "mod": "hmgapi"},
    {"key": "com.nemesis.manager/com.nemesis.manager.MainActivity", "packageName": "com.nemesis.manager", "className": "com.nemesis.manager.MainActivity", "title": "NEMESIS", "kind": "package", "mod": "nemesis"},
    {"key": "com.hkmc.valet/com.hkmc.valet.ValetActivity", "packageName": "com.hkmc.valet", "className": "com.hkmc.valet.ValetActivity", "title": "Valet Mode", "kind": "system"}
  ]
}
J

fail=0
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
eng() { HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=NEEV23.USA.S5W_M.V016.001.260507 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1; }

out=$(eng launcher-tiles)
has "launcher-tiles lists catalog with visible=true by default" '"visible": true' "$out"
has "launcher-tiles includes Valet Mode" '"title": "Valet Mode"' "$out"

out=$(eng launcher-tiles-set '{"compact_gaps":true,"hidden":["com.hkmc.valet/.ValetActivity"]}')
check "launcher-tiles-set saves normalised hidden entry" "saved: 1 hidden" "$out"
has "launcher-tiles-set broadcasts live reload" "broadcast hmgapi.intent.action.LAUNCHER_TILES_CHANGED" "$(cat "$R/actions.log")"
check "public/launcher-tiles.json created" "yes" "$([ -f "$D/public/launcher-tiles.json" ] && echo yes || echo no)"

out=$(eng launcher-tiles)
has "launcher-tiles marks Valet Mode visible=false" '"visible": false' "$out"
exit $fail

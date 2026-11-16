#!/bin/sh
# Off-car test of what a unit maps itself (state/local-map.json: CarMap, MapProbe
# map-car --local/--write/--forget, HookPlan, Points):
#   - points HMG-API's map lacks only because it's older fill those gaps, never a point the
#     map has or one the mapper couldn't find; only while their stock files are unchanged;
#     not with the owner's switch off; a broken file leaves HMG-API's map running;
#   - mods' observe hooks use them, change/replace hooks need HMG-API's map or "Try
#     unchecked maps" (as do HMG-API's own hooks, calls and constructors: PointsTest);
#   - a whole map made on the unit stands in for none, or one that doesn't fit;
#   - map-car on a real dex (with d8): by-name points only, each checked before it's
#     written, --if-needed, --forget, and --write kept outside maps/.
set -e
cd "$(dirname "$0")/../.."
. tools/env.sh
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
D=$R/data/hmgapi
M=$D/maps/kia/test/usa/260507
L=$D/state/local-map.json
mkdir -p $M $R/system/app $R/system/framework $D/state $D/mods $D/settings
printf 'ro.build.product=TEST.USA.0000.V015.012.260507\nro.board.platform=tcc803x\n' > $R/props.txt
echo stock-app > $R/system/app/Test.odex
echo stock-fw > $R/system/framework/framework.odex
md5of() { md5sum < "$1" | cut -c1-32; }
S=$(md5of $R/system/app/Test.odex); F=$(md5of $R/system/framework/framework.odex)
car() {   # car <status>
    printf '{"make": "kia", "model": "test", "name": "Test", "region": "usa", "firmware": "260507", "products": ["TEST.USA."], "status": "%s", "files": {"/system/app/Test.odex": "%s", "/system/framework/framework.odex": "%s"}, "apps": ["test", "framework"], "app_files": {"test": {"package": "x", "files": ["/system/app/Test.odex"]}, "framework": {"package": null, "files": ["/system/framework/framework.odex"]}}, "missing": {"test.a.flag": "newer than this map: map-car.py on the car'"'"'s stock tree maps it", "test.a.bad": "0 fields match in x.A"}}\n' "$1" "$S" "$F" > $M/car.json
}
car verified
printf '{"test.a": {"class": "x.A"}, "test.a.go": {"method": "go", "params": [], "ret": "void", "static": false}}\n' > $M/test.json
printf '{"framework.x": {"class": "android.X"}}\n' > $M/framework.json
printf 'test.a\ntest.a.go\ntest.a.flag\ntest.a.bad\ntest.a.later\nframework.x\n' > $D/maps/points.txt
local_map() {   # the overlay a unit wrote: flag (older map), later (newer than the map), bad (mapper didn't find it), go (in the map)
    printf '{"whole": false, "files": {"/system/app/Test.odex": "%s"}, "points": {"test.a.flag": {"field": "FLAG", "type": "int", "static": true}, "test.a.later": {"field": "LATER", "type": "int", "static": true}, "test.a.bad": {"field": "BAD", "type": "int", "static": true}, "test.a.go": {"method": "other", "params": [], "ret": "void", "static": false}}}\n' "$S" > $L
}

eng map; has "HMG-API's map alone: 3 points not mapped" "hooks on, 3 point(s) not mapped" "$out"
local_map
eng map; has "with the overlay: 2 points mapped on this car (reads and observe hooks)" "hooks on, 2 point(s) mapped on this car (reads and observe hooks), 1 point(s) not mapped" "$out"
eng report >/dev/null 2>&1 || true
REP=$(ls $D/public/reports/*.json 2>/dev/null | head -1)
[ -n "$REP" ] && check "the Car Report lists them (for a maintainer to make official)" '["test.a.flag", "test.a.later"]' \
    "$(python3 -c 'import json,sys; print(json.dumps(json.load(open(sys.argv[1]))["map"]["mapped_here"]))' "$REP")"

mkdir -p $D/mods/watch $D/mods/change
printf '{"id": "watch", "name": "W", "version": "1.0.0", "hmgapi": ">=1.0.0", "hooks": {"jar": "h.jar", "entry": "h.H", "points": {"test.a.flag": "observe"}}}\n' > $D/mods/watch/mod.json
printf '{"id": "change", "name": "C", "version": "1.0.0", "hmgapi": ">=1.0.0", "hooks": {"jar": "h.jar", "entry": "h.H", "points": {"test.a.later": "change"}}}\n' > $D/mods/change/mod.json
printf '{"enabled": {"watch": true, "change": true}}\n' > $D/settings/hmgapi.json
eng plan
hasnt "a mod observing a point mapped on this car: loads" "watch: " "$(echo "$out" | grep -i 'not loaded\|refused' || true)"
has "a mod changing one: not loaded, and why" "test.a.later is mapped on this car only, which is enough for observe hooks: a change hook needs it in HMG-API's map" "$out"
mkdir -p $D/settings/allow; touch $D/settings/allow/testing_maps
eng plan; hasnt "with \"Try unchecked maps\": loads" "mapped on this car only" "$out"
eng map; has "... and the map says they're for everything" "2 point(s) mapped on this car, 1 point(s) not mapped" "$out"
rm $D/settings/allow/testing_maps; rm -r $D/mods/watch $D/mods/change $D/settings/hmgapi.json

echo changed >> $R/system/app/Test.odex
eng map; hasnt "the app's stock file changed: its points mapped here aren't used" "mapped on this car" "$out"
mkdir -p $D/stock/system/app; echo stock-app > $D/stock/system/app/Test.odex
eng map; has "... unless it's a mod's change on HMG-API's stock backup of it" "2 point(s) mapped on this car" "$out"
rm -r $D/stock; echo stock-app > $R/system/app/Test.odex
touch $D/settings/allow/no_local_points
eng map; hasnt "the owner's switch off: not used" "mapped on this car" "$out"
rm $D/settings/allow/no_local_points
echo '{"points": [' > $L
eng map; has "a broken file: HMG-API's map runs as it is" "hook map: verified for Test (usa 260507), hooks on, 3 point(s) not mapped" "$out"

# a whole map made on the unit: for a car with no map, and for one whose map doesn't fit its framework
whole() {   # whole <products prefix> <framework md5>
    printf '{"whole": true, "make": "kia", "model": "test", "name": "Test", "region": "usa", "firmware": "260507", "product": "TEST.USA.0000.V015.012.260507", "products": ["%s"], "status": "probed", "files": {"/system/app/Test.odex": "%s", "/system/framework/framework.odex": "%s"}, "apps": ["test", "framework"], "app_files": {"test": {"package": "x", "files": ["/system/app/Test.odex"]}, "framework": {"package": null, "files": ["/system/framework/framework.odex"]}}, "points": {"test.a": {"class": "x.A"}, "framework.x": {"class": "android.X"}}, "missing": {}}\n' "$1" "$S" "$2" > $L
}
echo stock-fw-2 > $R/system/framework/framework.odex
F2=$(md5of $R/system/framework/framework.odex)
rm $L
eng map; has "another framework build: HMG-API's map doesn't fit" "hook map: mismatch" "$out"
whole TEST.USA. "$F2"
eng map; has "... the whole map made on this car stands in, probed, until checked" "hook map: probed for Test (usa 260507, mapped on this car), hooks off" "$out"
printf '{"folder": "local", "files": {"/system/app/Test.odex": "%s", "/system/framework/framework.odex": "%s"}, "ok": true}\n' "$S" "$F2" > $D/state/map-check.json
eng map; has "... checked on this car (verify-map): hooks on" "hook map: probed for Test (usa 260507, mapped on this car), hooks on" "$out"
rm -r $D/maps/kia
eng map; has "HMG-API's maps replaced by an update without this car: still there" "probed for Test (usa 260507, mapped on this car), hooks on" "$out"
whole OTHER.USA. "$F2"
eng map; has "... but only for this unit's product" "no hook map for this car" "$out"
echo stock-fw > $R/system/framework/framework.odex
whole TEST.USA. "$F2"
eng map; has "a firmware update since (other stock files): not used" "hooks off" "$out"
rm -f $L $D/state/map-check.json

# map-car on a real dex: the mapper, MapCheck (a stand-in off the unit), the file it writes
if [ ! -x "$D8" ] || [ -z "$ANDROID_JAR" ]; then
    echo "skip  map-car on a real dex (needs d8 and android.jar: ANDROID_HOME)"
else
    mkdir -p $T/src/x $T/cls $T/dex
    printf 'package x;\npublic class A {\n  public static final int FLAG = 1, LATER = 2;\n  public static int BAD2;\n  public void go() {}\n}\n' > $T/src/x/A.java
    printf 'package x;\npublic class Shape {\n  public static int shape(String s, int n) { return n; }\n}\n' > $T/src/x/Shape.java
    javac --release 8 -nowarn -d $T/cls $T/src/x/*.java 2>/dev/null
    "$D8" --min-api 19 --output $T/dex $T/cls/x/*.class >/dev/null 2>&1
    cp $T/dex/classes.dex $R/system/app/Test.odex
    S=$(md5of $R/system/app/Test.odex)
    for v in 1 2; do   # two builds of a framework (the mapper reads it too)
        mkdir -p $T/fw$v/src/android $T/fw$v/cls $T/fw$v/dex
        printf 'package android;
public class X {
  public static int build = %s;
}
' $v > $T/fw$v/src/android/X.java
        javac --release 8 -nowarn -d $T/fw$v/cls $T/fw$v/src/android/X.java 2>/dev/null
        "$D8" --min-api 19 --output $T/fw$v/dex $T/fw$v/cls/android/X.class >/dev/null 2>&1
    done
    cp $T/fw1/dex/classes.dex $R/system/framework/framework.odex
    F=$(md5of $R/system/framework/framework.odex)
    mkdir -p $M $D/maps/catalogue
    car verified
    printf '{"test.a": {"class": "x.A"}, "test.a.go": {"method": "go", "params": [], "ret": "void", "static": false}}\n' > $M/test.json
    printf '{"framework.x": {"class": "android.X"}}\n' > $M/framework.json
    # the catalogue: flag and later by name, shape by its shape, bad (the mapper looked and didn't find it)
    printf '{"app": "test", "package": "x", "files": ["/system/app/Test.odex"], "points": [{"id": "test.a", "kind": "class", "find": {"name": "x.A"}}, {"id": "test.a.go", "kind": "method", "of": "test.a", "find": {"name": "go", "params": [], "ret": "void"}}, {"id": "test.a.flag", "kind": "field", "of": "test.a", "find": {"name": "FLAG", "type": "int", "static": true}}, {"id": "test.a.later", "kind": "field", "of": "test.a", "find": {"name": "LATER", "type": "int", "static": true}}, {"id": "test.a.bad", "kind": "field", "of": "test.a", "find": {"name": "BAD", "type": "int", "static": true}}, {"id": "test.shape", "kind": "class", "find": {"package": "x", "methods": [{"params": ["java.lang.String", "int"], "ret": "int", "static": true}]}}]}\n' > $D/maps/catalogue/test.json
    printf 'test.a\ntest.a.go\ntest.a.flag\ntest.a.bad\ntest.a.later\ntest.shape\nframework.x\n' > $D/maps/points.txt
    eng map-car --local
    has "map-car --local without the unit's check: nothing written as checked" "0 point(s) HMG-API's map kia/test/usa/260507 lacks" "$out"
    has "... (it can't check off the unit)" "didn't resolve: test.a.flag (can't check off the head unit)" "$out"
    set +e; out=$(HMGAPI_TEST_MAPCHECK=fail:test.a.later HMGAPI_ROOT=$R HMGAPI_HOST=1 java -cp "$CP" hmgapi.engine.Engine map-car --local 2>&1); set -e
    has "map-car --local: the by-name point that resolves here is kept" "1 point(s) HMG-API's map kia/test/usa/260507 lacks, mapped and checked on this car" "$out"
    has "... one that doesn't resolve isn't" "didn't resolve: test.a.later (test: doesn't resolve)" "$out"
    has "... a point by shape is left to a maintainer" "1 didn't resolve, 1 left to a maintainer" "$out"
    hasnt "... one the mapper looked for and didn't find isn't tried" "test.a.bad" "$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(list(d["points"]) + list(d["skipped"]) + list(d["failed"]))' $L)"
    check "... written: flag only" '["test.a.flag"]' "$(python3 -c 'import json,sys; print(json.dumps(sorted(json.load(open(sys.argv[1]))["points"])))' $L)"
    check "... why the others aren't" '{"test.shape": "not a by-name point: a maintainer maps it"}' \
        "$(python3 -c 'import json,sys; s=json.load(open(sys.argv[1]))["skipped"]; print(json.dumps({k: v for k, v in s.items() if k == "test.shape"}))' $L)"
    eng map; has "... and used" "1 point(s) mapped on this car" "$out"
    eng map-car --local --if-needed; has "--if-needed with nothing changed: not mapped again" "map-car: up to date (1 point(s) mapped on this car)" "$out"
    printf 'test.a\ntest.a.go\ntest.a.flag\ntest.a.bad\ntest.a.later\ntest.shape\nframework.x\ntest.a.newest\n' > $D/maps/points.txt
    set +e; out=$(HMGAPI_TEST_MAPCHECK=pass HMGAPI_ROOT=$R HMGAPI_HOST=1 java -cp "$CP" hmgapi.engine.Engine map-car --local --if-needed 2>&1); set -e
    has "--if-needed after an update (a newer catalogue): mapped again" "2 point(s) HMG-API's map kia/test/usa/260507 lacks" "$out"
    eng map-car --forget; has "--forget" "forgotten" "$out"
    check "... the file is gone" no "$([ -f $L ] && echo yes || echo no)"
    eng map-car --write; has "--write with HMG-API's map fitting: says --local instead" "map-car --local\` adds the points it lacks" "$out"
    cp $T/fw2/dex/classes.dex $R/system/framework/framework.odex
    eng map-car --write; has "--write on a map that doesn't fit (mismatch): written, outside maps/" "written: /data/hmgapi/state/local-map.json (this car's own map, status probed, instead of kia/test/usa/260507" "$out"
    eng map; has "... and used as this car's map" "(usa 260507, mapped on this car), hooks off" "$out"
    check "... maps/ untouched" "car.json framework.json test.json" "$(ls $M | tr '\n' ' ' | sed 's/ $//')"
fi

# Points: what a point mapped on this car only may be used for
mkdir -p $T/pt
if javac --release 8 -Xlint:-options -nowarn -d $T/pt core/runtime/java/hmgapi/engine/*.java core/runtime/java/hmgapi/hook/Points.java core/runtime/test/hmgapi/hook/PointsTest.java 2>/dev/null; then
    java -cp $T/pt hmgapi.hook.PointsTest || fail=1
else
    echo "FAIL  PointsTest doesn't compile"; fail=1
fi
exit $fail

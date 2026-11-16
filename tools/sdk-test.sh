#!/bin/sh
# Off-car test of the mod SDK (./build.sh sdk): it has what docs/mod-repos.md section 3
# lists, and a mod repo outside HMG-API builds, tests, checks and packages the example
# mods with it alone, to the same bytes as this repo's own build and tools.
# Needs the build (./build.sh) and the Android SDK.
set -e
cd "$(dirname "$0")/.."
REPO=$PWD
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
md5() { md5sum < "$1" | cut -c1-32; }
# a mod package's files, all but its signature (ECDSA: a new one each time it's signed)
pkg() { (rm -rf $T/u && mkdir $T/u && cd $T/u && unzip -q "$1" && find . -type f ! -path ./META-HMGAPI/signature | sort | xargs md5sum); }

sh build.sh sdk > $T/build.log 2>&1 || { cat $T/build.log; exit 1; }
V=$(sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' core/runtime/java/hmgapi/engine/Engine.java)
Z=out/dist/hmgapi-mod-sdk-$V.zip
check "zip named by the version" yes "$([ -f $Z ] && echo yes)"
L=$(unzip -Z1 $Z | sort)
missing=""
for f in lib/hmgapi-runtime.jar lib/hmgapi-runtime-stubs.jar lib/hmgapi-client.jar lib/hmgapi-engine.jar bin/lpatch-host \
    src/lpatch/lpatch.c src/lpatch/build.sh bin/build-mod.sh bin/mkmodpkg.sh bin/mknemesismod.sh bin/nemesis-meta.py \
    bin/nemesis-version.py nemesis/mod/NEMESIS.data.in nemesis/mod/README.txt nemesis/mod/scripts/preinstall.sh \
    nemesis/mod/scripts/postinstall.sh nemesis/mod/scripts/preuninstall.sh nemesis/bin/open.sh bin/check-scripts.sh \
    unit-commands.txt bin/mkrecipe.py bin/mkdelta.py bin/mkmemrecipe.py bin/mkicon.sh bin/mod-draft.py test/lib.sh \
    points.txt VERSION bin/check-points.py bin/env.sh test/hmgapi-engine-test.jar; do
    echo "$L" | grep -qx "$f" || missing="$missing $f"
done
check "every part the doc lists" "" "$missing"
for f in core/hookpoints/*.json; do echo "$L" | grep -qx "catalogue/${f##*/}" || missing="$missing $f"; done
check "the hook point catalogue" "" "$missing"
check "no Android SDK or manufacturer files (android.jar, d8, apk, odex, so)" "" "$(echo "$L" | grep -E 'android\.jar|/d8$|\.apk$|\.odex$|\.so$' || true)"
check "no keys" "" "$(echo "$L" | grep -E '\.(key|p12|pass)$' || true)"
mkdir -p $T/sdk && unzip -q $Z -d $T/sdk
check "VERSION is HMG-API's" "$V" "$(cat $T/sdk/VERSION)"
check "points.txt: every point in the catalogue" \
    "$(python3 -c 'import glob,json; print(len([p for f in glob.glob("core/hookpoints/*.json") for p in json.load(open(f))["points"]]))')" \
    "$(grep -c . $T/sdk/points.txt)"
check "the scripts are executable" yes "$([ -x $T/sdk/bin/build-mod.sh ] && [ -x $T/sdk/bin/mkmodpkg.sh ] && [ -x $T/sdk/bin/lpatch-host ] && echo yes)"
check "MemRecipeTest runs from the SDK (synthetic libraries)" 0 "$(java -cp $T/sdk/lib/hmgapi-engine.jar:$T/sdk/test/hmgapi-engine-test.jar hmgapi.engine.MemRecipeTest > /dev/null; echo $?)"
check "the engine jar runs on its own" yes "$(java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing keygen $T/author.key > /dev/null && [ -s $T/author.key ] && echo yes)"

# ---- a mod repo outside HMG-API (mods in <id>/ folders), the SDK alone, run from its root
M=$T/mods; mkdir -p $M
cp -r mods/examples/hello mods/examples/hello-api mods/examples/hello-user mods/examples/side-panel $M/
cd $M
S=$T/sdk/bin
o=$(sh $S/build-mod.sh hello $T/b/hello $T/w/hello 2>&1) || echo "$o"
check "hello builds" yes "$([ -f $T/b/hello/hooks.jar ] && echo yes)"
check "... the same hooks.jar as build.sh mods" "$(md5 $REPO/out/mods/hello/hooks.jar)" "$(md5 $T/b/hello/hooks.jar)"
o=$(sh $S/build-mod.sh hello-api $T/b/hello-api $T/w/hello-api 2>&1) || echo "$o"
o=$(HMGAPI_MOD_CP=$T/w/hello-api/classes sh $S/build-mod.sh hello-user $T/b/hello-user $T/w/hello-user 2>&1) || echo "$o"
check "a mod that requires another builds against it (HMGAPI_MOD_CP)" "$(md5 $REPO/out/mods/hello-user/hooks.jar)" "$(md5 $T/b/hello-user/hooks.jar 2>/dev/null)"
o=$(sh $S/build-mod.sh side-panel $T/b/side-panel 2>&1) || echo "$o"
APK=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["apps"][0]["apk"])' side-panel/mod.json)
check "an app part builds, hmgapi-client inside" yes "$(unzip -p $T/b/side-panel/$APK classes.dex 2>/dev/null | grep -q 'Lhmgapi/' && echo yes)"
check "... the mod's json beside it" yes "$([ -f $T/b/side-panel/mod.json ] && echo yes)"

# hello's own test.sh, as the mod workflow runs it
o=$(HMGAPI_SDK=$T/sdk HMGAPI_MOD_BUILD=$T/b/hello sh hello/test.sh 2>&1) && r=0 || r=$?
check "hello's test.sh passes on the SDK" 0 $r
[ $r = 0 ] || echo "$o"
check "... with its checks" yes "$([ "$(echo "$o" | grep -c '^ok')" -ge 5 ] && echo yes)"

# ---- packaging: the same package as tools/mkmodpkg.sh in HMG-API, with the same key
o=$(HMGAPI_MOD_BUILD=$T/b/hello sh $S/mkmodpkg.sh hello $T/author.key 2>&1) || echo "$o"
check "mkmodpkg.sh: ./out/packages of the mod repo" yes "$([ -f $M/out/packages/hello-1.0.0.zip ] && echo yes)"
check "... test.sh and src left out" "" "$(unzip -Z1 $M/out/packages/hello-1.0.0.zip | grep -E '^(test\.sh|src/)' || true)"
cp $M/out/packages/hello-1.0.0.zip $T/prebuilt.zip
check "... its signature verifies" yes "$(rm -rf $T/v && mkdir $T/v && unzip -q $T/prebuilt.zip -d $T/v && java -cp $T/sdk/lib/hmgapi-engine.jar hmgapi.engine.Signing verify $T/v > /dev/null && echo yes)"
o=$(sh $S/mkmodpkg.sh hello $T/author.key 2>&1) || echo "$o"
check "... building it itself (no HMGAPI_MOD_BUILD) gives the same files" "$(pkg $T/prebuilt.zip)" "$(pkg $M/out/packages/hello-1.0.0.zip)"
(cd $REPO && tools/mkmodpkg.sh mods/examples/hello $T/author.key > /dev/null)
check "... and the same files as HMG-API's tools/mkmodpkg.sh" "$(pkg $REPO/out/packages/hello-1.0.0.zip)" "$(pkg $M/out/packages/hello-1.0.0.zip)"
# a signing key left in the mod's folder never goes into the (public) package
cp -r hello leaky; cp $T/author.key leaky/notes.txt; mkdir leaky/app2; echo x > leaky/app2/release.p12
before=$(md5sum < $M/out/packages/hello-1.0.0.zip)
o=$(HMGAPI_MOD_BUILD=$T/b/hello sh $S/mkmodpkg.sh leaky $T/author.key 2>&1) && r=0 || r=$?
check "mkmodpkg.sh: a mod folder with keys in it isn't packaged" 1 $r
check "... it names the author key's copy (whatever its name) and the keystore" "  app2/release.p12|  notes.txt|" "$(echo "$o" | grep '^  ' | tr '\n' '|')"
check "... and no package was written" "$before" "$(md5sum < $M/out/packages/hello-1.0.0.zip)"
rm -rf leaky
cp $M/out/packages/hello-1.0.0.zip $REPO/out/packages/hello-1.0.0.zip   # one package for both NEMESIS builds below
o=$(sh $S/mknemesismod.sh out/packages/hello-1.0.0.zip 2>&1) || echo "$o"
check "mknemesismod.sh: ./out/dist" yes "$([ -f $M/out/dist/hello-1.0.0-nemesis-package.zip ] && [ -f $M/out/dist/hello-1.0.0-nemesis.zip ] && echo yes)"
(cd $REPO && tools/mknemesismod.sh out/packages/hello-1.0.0.zip $T/core-dist > /dev/null)
check "... the same NEMESIS package as HMG-API's" "$(md5 $T/core-dist/hello-1.0.0-nemesis-package.zip)" "$(md5 $M/out/dist/hello-1.0.0-nemesis-package.zip)"

# zipdir (tools/mkinstallers.sh, tools/mknemesismod.sh) and the other zip steps: the same zip bytes in any time zone and locale
ZD=$(grep '^zipdir()' $REPO/tools/mkinstallers.sh)
check "zipdir: the same line in mkinstallers.sh and mknemesismod.sh" "$ZD" "$(grep '^zipdir()' $REPO/tools/mknemesismod.sh)"
if locale -a 2>/dev/null | grep -qiE '^en_US\.utf-?8$' && [ -f /usr/share/zoneinfo/America/Los_Angeles ]; then
    for e in utc:"TZ=UTC LC_ALL=C" la:"TZ=America/Los_Angeles LC_ALL=en_US.UTF-8"; do
        d=$T/zd-${e%%:*}; e=${e#*:}
        (cd $REPO && env $e tools/mknemesismod.sh out/packages/hello-1.0.0.zip $d > /dev/null)
        # mkinstallers.sh's line, on names that C and en_US sort apart (case, punctuation)
        mkdir -p $d/tree/sub && for f in B.txt a.txt _x a-b ab sub/Z sub/y; do echo $f > $d/tree/$f; done
        env $e sh -c "$ZD; zipdir $d/tree $d/tree.zip"
        # the same names through build.sh's SDK jars and zip, tools/mkmodpkg.sh's and tools/mkrelease.sh's zip lines
        env $e sh -c "$(grep '^    jarof()' $REPO/build.sh); jarof $d/tree $d/jar.zip"
        env $e S=$d/tree Z=$d/sdk.zip sh -c "$(grep '^    (cd "\$S" && ' $REPO/build.sh)"
        env $e W=$d ID=tree Z=$d/pkg.zip sh -c "$(grep '^(cd "\$W/\$ID" && ' $REPO/tools/mkmodpkg.sh)"
        mkdir -p $d/rel && cp -R $d/tree $d/rel/hmgapi-0
        env $e W=$d/rel V=0 Z=$d/rel.zip sh -c "$(grep '^(cd "\$W" && ' $REPO/tools/mkrelease.sh)"
    done
    for z in hello-1.0.0-nemesis-package.zip hello-1.0.0-nemesis.zip tree.zip jar.zip sdk.zip pkg.zip rel.zip; do
        check "zipdir: $z the same in UTC/C and Los Angeles/en_US.UTF-8" "$(md5 $T/zd-utc/$z)" "$(md5 $T/zd-la/$z)"
    done
else
    echo "skip  zipdir across time zones and locales (needs the en_US.UTF-8 locale and America/Los_Angeles)"
fi
rm -f $REPO/out/packages/hello-1.0.0.zip

# "nemesis_conflicts" in mod.json: depends= refuses them (no conflicts.txt in the SDK)
mkdir -p $T/c/conf && printf '{"id": "conf", "version": "1.0.0", "hmgapi": ">=1.0.0", "nemesis_conflicts": ["other.pkg", "legacy.pkg"]}\n' > $T/c/conf/mod.json
(cd $T/c && sh $S/mkmodpkg.sh conf $T/author.key > /dev/null && sh $S/mknemesismod.sh out/packages/conf-1.0.0.zip > /dev/null)
check "nemesis_conflicts go in depends as !id" "depends=hmgapi@>=1.0,!other.pkg,!legacy.pkg" \
    "$(unzip -p $T/c/out/dist/conf-1.0.0-nemesis-package.zip NEMESIS.data | grep '^depends=' | tr -d '"')"
printf '{"id": "conf", "version": "1.0.0", "nemesis_conflicts": ["hmgapi"]}\n' > $T/c/conf/mod.json
(cd $T/c && sh $S/mkmodpkg.sh conf $T/author.key > /dev/null)
set +e; o=$(cd $T/c && sh $S/mknemesismod.sh out/packages/conf-1.0.0.zip 2>&1); r=$?; set -e
check "... refusing hmgapi itself: refused" 1 "$([ $r -ne 0 ] && echo 1)"
has "... says why" "both needs and refuses hmgapi" "$o"
# "requires": hmgapi.<id>@>=<the range's lowest version>, as this repo's tools/mknemesismod.sh does
printf '{"id": "conf", "version": "1.0.0", "requires": ["base ^2.2.0-rc.1", "lib"]}\n' > $T/c/conf/mod.json
(cd $T/c && sh $S/mkmodpkg.sh conf $T/author.key > /dev/null && sh $S/mknemesismod.sh out/packages/conf-1.0.0.zip > /dev/null 2>&1)
check "requires go in depends as hmgapi.<id>@>=<lowest>" "depends=hmgapi@>=1.0,hmgapi.base@>=2.1.99.1,hmgapi.lib" \
    "$(unzip -p $T/c/out/dist/conf-1.0.0-nemesis-package.zip NEMESIS.data | grep '^depends=' | tr -d '"')"
(cd $REPO && tools/mknemesismod.sh $T/c/out/packages/conf-1.0.0.zip $T/c/core > /dev/null 2>&1)
check "... the same NEMESIS.data as HMG-API's tools" "$(unzip -p $T/c/core/conf-1.0.0-nemesis-package.zip NEMESIS.data | md5sum)" \
    "$(unzip -p $T/c/out/dist/conf-1.0.0-nemesis-package.zip NEMESIS.data | md5sum)"
printf '{"id": "conf", "version": "1.0.0", "requires": ["Bad id"]}\n' > $T/c/conf/mod.json
(cd $T/c && sh $S/mkmodpkg.sh conf $T/author.key > /dev/null)
set +e; o=$(cd $T/c && sh $S/mknemesismod.sh out/packages/conf-1.0.0.zip 2>&1); r=$?; set -e
check "... a bad id in requires: refused" 1 "$([ $r -ne 0 ] && echo 1)"
has "... says why" "isn't a mod id" "$o"

# ---- check-scripts.sh and check-points.py
o=$(sh $S/check-scripts.sh hello side-panel 2>&1) && r=0 || r=$?
check "check-scripts.sh: the examples pass" 0 $r
mkdir -p $T/bad/bad && printf '{"id": "bad", "version": "1.0.0", "services": [{"name": "s", "script": "s.sh"}]}\n' > $T/bad/bad/mod.json
printf '#!/system/bin/sh\necho x | sed s/x/y/\n' > $T/bad/bad/s.sh
set +e; o=$(sh $S/check-scripts.sh $T/bad/bad 2>&1); r=$?; set -e
check "... a script calling sed: fails" 1 "$([ $r -ne 0 ] && echo 1)"
has "... names it" "sed" "$o"
o=$(python3 $S/check-points.py hello hello-user side-panel 2>&1) && r=0 || r=$?
check "check-points.py: the examples pass (no points to fetch)" 0 $r
mkdir -p $T/p/p
pts() { printf '{"id": "p", "version": "1.0.0", "hmgapi": "%s", "hooks": {"jar": "hooks.jar", "entry": "x.X", "points": {%s}}}\n' "$1" "$2" > $T/p/p/mod.json; }
head -2 $T/sdk/points.txt > $T/floor.txt
P1=$(sed -n 1p $T/floor.txt) P3=$(sed -n 3p $T/sdk/points.txt)
pts ">=1.0.0" "\"$P1\": \"observe\""
o=$(HMGAPI_FLOOR_POINTS=$T/floor.txt python3 $S/check-points.py $T/p/p 2>&1) && r=0 || r=$?
check "... a point the floor release has: ok" 0 $r
pts ">=1.0.0" "\"$P1\": \"observe\", \"$P3\": \"observe\""
set +e; o=$(HMGAPI_FLOOR_POINTS=$T/floor.txt python3 $S/check-points.py $T/p/p 2>&1); r=$?; set -e
check "... a point only a later release has: fails" 1 $r
has "... names it" "$P3" "$o"
pts ">=99.0.0" ""
set +e; o=$(python3 $S/check-points.py $T/p/p 2>&1); r=$?; set -e
check "... a floor above the SDK: fails" 1 $r
has "... says to pin a newer SDK" "but the SDK is $V" "$o"
printf '{"id": "p", "version": "1.0.0-rc.1", "hmgapi": ">=%s", "hooks": {"jar": "hooks.jar", "entry": "x.X", "points": {"%s": "observe"}}}\n' "$V" "$P3" > $T/p/p/mod.json
o=$(python3 $S/check-points.py $T/p/p 2>&1) && r=0 || r=$?
check "... a floor that is the SDK's own version: its own points, nothing fetched" 0 $r
pts "<3" "\"$P1\": \"observe\""
set +e; o=$(HMGAPI_FLOOR_POINTS=$T/floor.txt python3 $S/check-points.py $T/p/p 2>&1); r=$?; set -e
check "... points but no floor: fails" 1 $r
exit $fail

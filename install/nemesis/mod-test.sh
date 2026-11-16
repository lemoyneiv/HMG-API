#!/bin/sh
# Off-car test of a HMG-API mod as a NEMESIS package (tools/mknemesismod.sh,
# install/nemesis/mod): the descriptor, then NEMESIS's install, update and
# uninstall (as native/pkg.c runs the hooks) on a fake head unit, with hmgapid
# running the real engine on this JVM. /system must come back byte-identical.
set -e
cd "$(dirname "$0")/../.."
SH=${UNIT_SH:-sh}
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root S=$T/stick
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
sig() { java -cp "$CP" hmgapi.engine.Signing "$@"; }
tree() { (cd $R/system && find . -type f | sort | while read f; do echo "$(md5sum < "$f" | cut -c1-32) $f"; done) }
mods() { ls $R/data/hmgapi/mods 2>/dev/null | grep -v '^\.' | tr '\n' ' '; }
prop() { grep "^$1=" $R/system/build.prop | cut -d= -f2-; }

# ---- a fake head unit with HMG-API on it: hmgapid runs the engine here (host mode)
mkdir -p $R/system/bin $R/system/etc/permissions $R/data/hmgapi/mods $R/data/hmgapi/settings $R/data/local/tmp/nemesis/stage $R/data/nemesis/apps $S/HMGAPI
printf 'ro.build.product=CVEV.USA.0000.V015.012.260507\npersist.demo=stock\n' > $R/system/build.prop
: > $R/data/hmgapi/uninstall.sh
cat > $R/system/bin/hmgapid <<EOF
#!/bin/sh
case \$1 in
    ping|daemon) exit 0 ;;
    engine) shift; echo "engine \$*" >> $T/calls
        exec env HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 java -cp $CP hmgapi.engine.Engine "\$@" ;;
esac
echo "hmgapid stub: \$*"; exit 2
EOF
chmod 755 $R/system/bin/hmgapid
STOCK=$(tree)
mkdir -p $T/bin
printf '#!/bin/sh\necho 0\n' > $T/bin/getprop
printf '#!/bin/sh\nexit 0\n' > $T/bin/am
chmod 755 $T/bin/*

# NEMESIS, as native/pkg.c runs a package's hooks (its command line is what preuninstall reads):
#   nemesis install [--update] <zip> | nemesis uninstall <id>
cat > $T/nemesis <<'EOF'
#!/bin/sh
A=$ROOT/data/nemesis/apps
hook() { [ -f "$2/$1" ] || return 0; (cd "$2" && $UNIT_SH "$1"); }
case $1 in
    install)
        [ "$2" = --update ] && { up=1; shift; }
        id=$(unzip -p "$2" NEMESIS.data | grep '^id=' | cut -d= -f2)
        st=$ROOT/data/local/tmp/nemesis/stage/$id
        [ -d "$A/$id" ] && [ -z "$up" ] && { echo "already installed: $id (pass --update)"; exit 3; }
        rm -rf "$st"; mkdir -p "$st"; unzip -q "$2" -d "$st"
        if [ -d "$A/$id" ]; then hook scripts/preuninstall.sh "$A/$id"; rm -rf "$A/$id"; fi
        hook scripts/preinstall.sh "$st" || { rc=$?; echo "preinstall failed rc=$rc"; rm -rf "$st"; exit $rc; }
        mkdir -p "$A/$id"; cp -r "$st/." "$A/$id/"
        echo "$id" >> $ROOT/packages.db
        hook scripts/postinstall.sh "$A/$id"; rc=$?
        rm -rf "$st"; exit $rc ;;
    uninstall)
        hook scripts/preuninstall.sh "$A/$2" || { rc=$?; echo "preuninstall failed: package stays"; exit 5; }
        rm -rf "$A/$2"; grep -v "^$2\$" $ROOT/packages.db > $ROOT/packages.db.n; mv $ROOT/packages.db.n $ROOT/packages.db ;;
esac
EOF
chmod 755 $T/nemesis
nem() { set +e; out=$(cd $T && env PATH="$T/bin:$PATH" ROOT=$R UNIT_SH="$SH" HMGAPI_USB_DIRS="$S" sh $T/nemesis "$@" 2>&1); rc=$?; set -e; }

# ---- a signed mod (tools/mkmodpkg.sh's layout), made into NEMESIS packages
sig keygen $T/author.key > /dev/null
sig keygen $T/other.key > /dev/null
EV6='[{"model": ["CVEV"], "region": "USA", "firmware": "260507"}]'
mk() {   # mk <version> <prop value> <key> [system json] [more mod.json fields, each ending in ", "]
    rm -rf $T/m; mkdir -p $T/m/files
    printf '{"id": "demo", "name": "Demo \\"quoted\\" mod", "version": "%s", "hmgapi": ">=0.1.0", "description": "A test mod.", %s"system": %s}\n' \
        "$1" "$5" "${4:-$EV6}" > $T/m/mod.json
    printf '{"changes": [{"type": "props", "path": "/system/build.prop", "set": {"persist.demo": "%s"}, "stock": "any"},\n {"type": "add", "path": "/system/etc/permissions/demo.xml", "src": "files/demo.xml"}]}\n' "$2" > $T/m/patches.json
    echo "<permissions/>" > $T/m/files/demo.xml
    sig sign $T/m $3 > /dev/null
    rm -f $T/demo.zip; (cd $T/m && zip -q -r -X $T/demo.zip .)
    rm -rf $T/dist; tools/mknemesismod.sh $T/demo.zip $T/dist > /dev/null
    P=$T/dist/demo-$1-nemesis-package.zip
}
mk 1.0.0 on $T/author.key
val() { unzip -p "$P" NEMESIS.data | grep "^$1=" | cut -d= -f2- | sed 's/^"\(.*\)"$/\1/'; }
check "package id is hmgapi.<mod id>" hmgapi.demo "$(val id)"
check "descriptor version 1.1" 1.1 "$(val version)"
check "package-version is the mod's" 1.0.0 "$(val package-version)"
check "needs HMG-API's package, 1.0 or newer" "hmgapi@>=1.0" "$(val depends)"
printf '# test\nhmgapi.demo  other.pkg,legacy.pkg   # why\nhmgapi.else  third.pkg\n' > $T/conflicts.txt
check "incompatible packages (conflicts.txt) go in depends as !id" "hmgapi@>=1.0,!other.pkg,!legacy.pkg" \
    "$(HMGAPI_NEMESIS_CONFLICTS=$T/conflicts.txt tools/mknemesismod.sh $T/demo.zip $T/dist > /dev/null; val depends)"
printf 'hmgapi.demo  hmgapi\n' > $T/conflicts.txt
set +e; o=$(HMGAPI_NEMESIS_CONFLICTS=$T/conflicts.txt tools/mknemesismod.sh $T/demo.zip $T/dist 2>&1); r=$?; set -e
has "a mod package refusing the HMG-API it needs: refused" "both needs and refuses hmgapi" "$o"
check "... and it fails" 1 "$([ $r -ne 0 ] && echo 1)"
# a mod's own list: mod.json "nemesis_conflicts" (inside the signed package, so every build of it agrees)
NC='"nemesis_conflicts": ["mine.pkg", "other.pkg"], '
mk 1.0.0 on $T/author.key "$EV6" "$NC"
check "the mod's nemesis_conflicts go in depends as !id" "hmgapi@>=1.0,!mine.pkg,!other.pkg" "$(val depends)"
printf 'hmgapi.demo  other.pkg,legacy.pkg\n' > $T/conflicts.txt
check "... with conflicts.txt's: both, each once" "hmgapi@>=1.0,!other.pkg,!legacy.pkg,!mine.pkg" \
    "$(HMGAPI_NEMESIS_CONFLICTS=$T/conflicts.txt tools/mknemesismod.sh $T/demo.zip $T/dist > /dev/null; val depends)"
for bad in '"nemesis_conflicts": ["not an id"], ' '"nemesis_conflicts": "one.pkg", '; do
    mk 1.0.0 on $T/author.key "$EV6" "$bad" 2>/dev/null || true
    set +e; o=$(tools/mknemesismod.sh $T/demo.zip $T/dist 2>&1); r=$?; set -e
    has "nemesis_conflicts $bad: refused" "nemesis_conflicts" "$o"
done
mk 1.0.0 on $T/author.key "$EV6" '"nemesis_conflicts": ["hmgapi"], ' 2>/dev/null || true
set +e; o=$(tools/mknemesismod.sh $T/demo.zip $T/dist 2>&1); r=$?; set -e
has "... refusing the HMG-API it needs: refused" "both needs and refuses hmgapi" "$o"
# the mods it requires (mod.json "requires"): hmgapi.<id>@>=<the range's lowest version, as a package-version>
mk 1.0.0 on $T/author.key "$EV6" '"requires": ["wireless-carplay-aa >=2.1.0"], '
check "a required mod goes in depends as hmgapi.<id>@>=<lowest>" "hmgapi@>=1.0,hmgapi.wireless-carplay-aa@>=2.1.0" "$(val depends)"
mk 1.0.0 on $T/author.key "$EV6" '"requires": ["base ^2.2.0-rc.1", {"id": "lib", "version": ">=1.2 <2"}, "any", "base >=2.0"], ' 2> $T/err
check "... a pre-release floor as its package-version; an object entry; no range: any version; one entry each, the highest floor" \
    "hmgapi@>=1.0,hmgapi.base@>=2.1.99.1,hmgapi.lib@>=1.2.0,hmgapi.any" "$(val depends)"
has "... what NEMESIS can't check (^, an upper bound) is said" "requires base ^2.2.0-rc.1; its NEMESIS package checks only hmgapi.base@>=2.1.99.1, HMG-API checks the rest" "$(cat $T/err)"
has "... both of them" "requires lib >=1.2 <2; its NEMESIS package checks only hmgapi.lib@>=1.2.0" "$(cat $T/err)"
check "... and nothing for a plain >=" "" "$(mk 1.0.0 on $T/author.key "$EV6" '"requires": ["base >=2.0"], ' 2>&1)"
mk 1.0.0 on $T/author.key "$EV6" '"requires": ["base >=2.0"], "nemesis_conflicts": ["mine.pkg"], '
check "... before the refusals" "hmgapi@>=1.0,hmgapi.base@>=2.0.0,!mine.pkg" "$(val depends)"
mk 1.0.0 on $T/author.key "$EV6" '"requires": [{"id": "extra", "version": "^3.0", "optional": true, "adds": "more"}, {"id": "base", "version": ">=2.0", "optional": false}], "nemesis_conflicts": ["hmgapi.extra"], ' 2> $T/err || true
check "an optional requires (\"optional\": true, the mod runs without it): left out, so not refused either" "hmgapi@>=1.0,hmgapi.base@>=2.0.0,!hmgapi.extra" "$(val depends)"
check "... and no note about its range" "" "$(cat $T/err)"
for bad in '"Not-An-Id >=1.0"' '"hmgapi >=1.0"' '"demo"' '"base >=2.0-foo"' '"base >=x"'; do
    mk 1.0.0 on $T/author.key "$EV6" "\"requires\": [$bad], " 2>/dev/null || true
    set +e; o=$(tools/mknemesismod.sh $T/demo.zip $T/dist 2>&1); r=$?; set -e
    check "requires [$bad]: refused" 1 "$([ $r -ne 0 ] && echo 1)"
    has "... says why" 'mod.json "requires"' "$o"
done
mk 1.0.0 on $T/author.key "$EV6" '"requires": ["other"], "nemesis_conflicts": ["hmgapi.other"], ' 2>/dev/null || true
set +e; o=$(tools/mknemesismod.sh $T/demo.zip $T/dist 2>&1); r=$?; set -e
check "a mod requiring a mod whose package it refuses: refused" 1 "$([ $r -ne 0 ] && echo 1)"
has "... says why" "both needs and refuses hmgapi.other: it's in \"requires\"" "$o"
L=$(i=0; while [ $i -lt 9 ]; do printf '"mod-with-a-rather-long-name-for-the-limit-%02d >=1.0.0-rc.1", ' $i; i=$((i + 1)); done)
mk 1.0.0 on $T/author.key "$EV6" "\"requires\": [${L%, }], " 2>/dev/null || true
set +e; o=$(tools/mknemesismod.sh $T/demo.zip $T/dist 2>&1); r=$?; set -e
has "requires making depends= longer than NEMESIS takes: refused" "NEMESIS takes at most 511" "$o"
mk 1.0.0 on $T/author.key
check "os from the mod's system" "CVEV.USA.*.260507" "$(val os)"
check "name, with no double quotes left in it" "Demo 'quoted' mod" "$(val name)"
check "short-desc at most 64 characters" yes "$([ ${#V} -ge 0 ] && python3 -c 'import sys; print("yes" if 0 < len(sys.argv[1]) <= 64 else "no")' "$(val short-desc)")"
check "the mod zip goes in as it is" "$(md5sum < $T/demo.zip)" "$(unzip -p "$P" mod/package.zip | md5sum)"
check "Launch opens HMG-API's app, naming no app for NEMESIS to pm uninstall" "kind=sh path=bin/open.sh " \
    "$(unzip -p "$P" NEMESIS.data | grep -A3 '^\[app.0\]' | grep '^kind=\|^path=' | tr '\n' ' ')$(unzip -p "$P" NEMESIS.data | grep '^package=\|^component=')"
check "the stick download installs the package" "type=install path=packages/hmgapi.demo.zip" \
    "$(unzip -p $T/dist/demo-1.0.0-nemesis.zip NEMESIS.data | grep '^type=\|^path=' | tr '\n' ' ' | sed 's/ $//')"
check "... which is the same package" "$(md5sum < "$P")" "$(unzip -p $T/dist/demo-1.0.0-nemesis.zip packages/hmgapi.demo.zip | md5sum)"
check "the stick says how to uninstall" yes "$(unzip -p $T/dist/demo-1.0.0-nemesis.zip README.txt | grep -q 'nemesis uninstall hmgapi.demo' && echo yes)"
check "a mod with no system: os any" any "$(mk 1.0.0 on $T/author.key '[]'; val os)"
check "system as one object, platform only: os any" any "$(mk 1.0.0 on $T/author.key '{"platform": ["tcc803x"]}'; val os)"
check "product prefixes: os globs" "CVEV.USA.*,NEEV.CAN.*" "$(mk 1.0.0 on $T/author.key '{"product": ["CVEV.USA.", "NEEV.CAN."]}'; val os)"
set +e; o=$(tools/mknemesismod.sh mods/examples/hello/mod.json $T/dist 2>&1); r=$?; set -e
check "not a zip mod package: refused" 1 "$([ $r -ne 0 ] && echo 1)"
rm -rf $T/u; mkdir $T/u; cp mods/examples/hello/mod.json $T/u/; (cd $T/u && zip -q $T/u.zip mod.json)
set +e; o=$(tools/mknemesismod.sh $T/u.zip $T/dist 2>&1); r=$?; set -e
has "an unsigned mod: refused" "isn't signed" "$o"

# ---- install (a mod.json with nemesis_conflicts: the engine ignores the field)
mk 1.0.0 on $T/author.key "$EV6" "$NC"
mv $R/system/bin/hmgapid $R/system/bin/hmgapid.keep
nem install $P
check "no HMG-API: preinstall refuses" 1 $rc
has "... says to install HMG-API first" "install HMG-API first" "$out"
check "... and NEMESIS registered nothing" "" "$(ls $R/data/nemesis/apps)"
mv $R/system/bin/hmgapid.keep $R/system/bin/hmgapid
nem install $P
check "install" 0 $rc
has "... through HMG-API's engine" "installed demo 1.0.0" "$out"
check "... and applied" on "$(prop persist.demo)"
check "... its file added" yes "$([ -f $R/system/etc/permissions/demo.xml ] && echo yes)"
check "... engine install (from NEMESIS: no sync) then apply" "engine install $R/data/nemesis/apps/hmgapi.demo/mod/package.zip --from-nemesis engine apply" "$(tr '\n' ' ' < $T/calls | sed 's/ $//')"
check "mod in HMG-API" "demo " "$(mods)"

# ---- update: NEMESIS runs the old preuninstall first, with the new package staged
printf '{"keep": true}\n' > $R/data/hmgapi/settings/demo.json
mk 1.1.0 two $T/author.key
: > $T/calls
nem install --update $P
check "update" 0 $rc
has "... the old preuninstall leaves the mod in" "update: demo stays installed" "$out"
check "... no remove" "" "$(grep 'engine remove' $T/calls)"
check "... applied" two "$(prop persist.demo)"
has "... the new version in HMG-API" '"version": "1.1.0"' "$(cat $R/data/hmgapi/mods/demo/mod.json)"
check "... settings kept" '{"keep": true}' "$(cat $R/data/hmgapi/settings/demo.json)"

# an update signed by someone else: HMG-API refuses it, nothing changes
mk 1.2.0 three $T/other.key
nem install --update $P
check "an update by another author key: fails" 1 "$([ $rc -ne 0 ] && echo 1)"
has "... HMG-API says why" "signed by a different author" "$out"
check "... the installed mod untouched" two "$(prop persist.demo)"
has "... still 1.1.0" '"version": "1.1.0"' "$(cat $R/data/hmgapi/mods/demo/mod.json)"

# ---- uninstall: removed from HMG-API, files back to stock, settings kept
mk 1.1.0 two $T/author.key
nem install --update $P
nem uninstall hmgapi.demo
check "uninstall" 0 $rc
has "... removed" "removed demo" "$out"
check "... /system back to stock bytes" "$STOCK" "$(tree)"
check "... no mod left in HMG-API" "" "$(mods)"
check "... its settings kept" '{"keep": true}' "$(cat $R/data/hmgapi/settings/demo.json)"
check "... package gone" "" "$(ls $R/data/nemesis/apps)"

# an uninstall with a stage left by an interrupted install still uninstalls
# (preuninstall tells an uninstall from an update by /proc/$PPID/cmdline, as on the unit)
if [ -f /proc/self/cmdline ]; then
    nem install $P
    mkdir -p $R/data/local/tmp/nemesis/stage/hmgapi.demo; unzip -q -o $P -d $R/data/local/tmp/nemesis/stage/hmgapi.demo
    nem uninstall hmgapi.demo
    check "uninstall with a stale stage: still removes the mod" "" "$(mods)"
    check "... back to stock" "$STOCK" "$(tree)"
    rm -rf $R/data/local/tmp/nemesis/stage/hmgapi.demo
else
    echo "skip  uninstall with a stale stage (no /proc here)"
fi

# removed in the HMG-API app first: the package uninstalls without touching anything
nem install $P
(env HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 java -cp $CP hmgapi.engine.Engine remove demo
 env HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 java -cp $CP hmgapi.engine.Engine apply) > /dev/null
nem uninstall hmgapi.demo
check "removed in the app first: uninstall is fine" 0 $rc
has "... says there's nothing to do" "isn't installed in HMG-API" "$out"

# HMG-API uninstalled first (its uninstall restores /system, keeps /data/hmgapi): the mod's folder goes
nem install $P
env HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 java -cp $CP hmgapi.engine.Engine restore > /dev/null
mv $R/system/bin/hmgapid $R/system/bin/hmgapid.keep
nem uninstall hmgapi.demo
check "HMG-API gone: uninstall is fine" 0 $rc
check "... the mod dropped from /data/hmgapi/mods" "" "$(mods)"
mv $R/system/bin/hmgapid.keep $R/system/bin/hmgapid
check "... /system as stock" "$STOCK" "$(tree)"

# ---- another car: refused, unless HMGAPI/ANY-CAR is on the stick
mk 1.0.0 on $T/author.key '[{"model": ["NEEV"], "region": "USA", "firmware": "260507"}]'
nem install $P
check "a mod for another car: fails" 1 "$([ $rc -ne 0 ] && echo 1)"
has "... HMG-API says why" "isn't for this head unit" "$out"
check "... nothing applied" "$STOCK" "$(tree)"
check "... nothing in HMG-API" "" "$(mods)"
nem uninstall hmgapi.demo
check "... its package uninstalls" 0 $rc
: > $S/HMGAPI/ANY-CAR
nem install $P
check "HMGAPI/ANY-CAR on the stick: installed anyway" 0 $rc
has "... with HMG-API's warning" "at your own risk" "$out"
check "... applied" on "$(prop persist.demo)"
nem uninstall hmgapi.demo
check "... uninstalled back to stock" "$STOCK" "$(tree)"
rm $S/HMGAPI/ANY-CAR

# ---- an apply that fails on a first install: the mod comes out of HMG-API again
mk 1.0.0 on $T/author.key
printf '{"changes": [{"type": "props", "path": "/system/missing.prop", "set": {"x": "1"}, "stock": "any"}]}\n' > $T/m/patches.json
sig sign $T/m $T/author.key > /dev/null; rm -f $T/demo.zip; (cd $T/m && zip -q -r -X $T/demo.zip .)
rm -rf $T/dist; tools/mknemesismod.sh $T/demo.zip $T/dist > /dev/null
nem install $T/dist/demo-1.0.0-nemesis-package.zip
check "apply fails: the install fails" 1 "$([ $rc -ne 0 ] && echo 1)"
has "... says nothing changed" "so it's not installed: nothing changed" "$out"
check "... not left in HMG-API" "" "$(mods)"
check "... /system as stock" "$STOCK" "$(tree)"

exit $fail

#!/bin/sh
# Off-car test of the patch engine (hmgapi.engine) on a desktop JVM, against a
# fake head unit in a temp folder: real recipes/deltas (tools/mkrecipe.py,
# mkdelta.py) applied by lpatch-host. Needs a JDK, python3 with bsdiff4, and
# out/lpatch-host (./build.sh lpatch).
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -x out/lpatch-host ] || { echo "run ./build.sh lpatch first"; exit 1; }
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
md5() { [ -f "$1" ] && md5sum "$1" | cut -d' ' -f1 || echo absent; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 HMGAPI_ONEBIN=cvev_us \
        HMGAPI_LPATCH=$REPO/out/lpatch-host java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

# ---- a fake head unit
mkdir -p $R/system/lib $R/system/app $R/system/onebin/cvev_us $R/system/etc/permissions $R/data/hmgapi/mods $R/data/hmgapi/settings
printf 'ro.build.product=CVEV.USA.0000.V015\npersist.ium.support=none\nro.other=1\n' > $R/system/build.prop
printf '%s\n' "-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "-/system/etc/permissions/x.xml" > $R/system/onebin/cvev_us/featurexml.cfg
python3 - "$T" "$R" <<'PY'
import os, sys, random, zipfile
T, R = sys.argv[1], sys.argv[2]
random.seed(7)
stock = bytearray(random.getrandbits(8) for _ in range(65536))
open(R + "/system/lib/libtest.so", "wb").write(stock)
a = bytearray(stock); a[0x1000:0x1004] = b"\x01\x02\x03\x04"; open(T + "/lib.a", "wb").write(a)
b = bytearray(stock); b[0x3000:0x3004] = b"\x05\x06\x07\x08"; open(T + "/lib.b", "wb").write(b)
c = bytearray(stock); c[0x1002:0x1006] = b"\x09\x09\x09\x09"; open(T + "/lib.c", "wb").write(c)
ab = bytearray(a); ab[0x3000:0x3004] = b"\x05\x06\x07\x08"; open(T + "/lib.ab", "wb").write(ab)
with zipfile.ZipFile(R + "/system/app/Test.apk", "w") as z:
    z.writestr("AndroidManifest.xml", b"manifest"); z.writestr("resources.arsc", b"res")
open(R + "/system/app/Test.odex", "wb").write(bytes(random.getrandbits(8) for _ in range(8192)))
open(T + "/classes.dex", "wb").write(b"dex\n035\0" + bytes(random.getrandbits(8) for _ in range(4096)))
PY
STOCK_PROP=$(md5 $R/system/build.prop); STOCK_CFG=$(md5 $R/system/onebin/cvev_us/featurexml.cfg)
STOCK_LIB=$(md5 $R/system/lib/libtest.so); STOCK_APK=$(md5 $R/system/app/Test.apk); STOCK_ODEX=$(md5 $R/system/app/Test.odex)

mod() {   # mod <id> <version> <mod.json extra> <patches.json changes>
    d=$R/data/hmgapi/mods/$1; mkdir -p $d/patches/ev6
    printf '{"id": "%s", "version": "%s"%s}\n' "$1" "$2" "$3" > $d/mod.json
    printf '{"cars": {"CVEV.USA.": "ev6"}, "changes": [%s]}\n' "$4" > $d/patches.json
    printf 'libtest.so %s\nTest.odex %s\nTest.apk %s\n' $STOCK_LIB $STOCK_ODEX $STOCK_APK > $d/patches/ev6/stock
}
mkdir -p $T/m
python3 tools/mkrecipe.py ra /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.a > $T/m/ra.recipe
python3 tools/mkrecipe.py rb /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.b > $T/m/rb.recipe
python3 tools/mkrecipe.py rc /system/lib/libtest.so $R/system/lib/libtest.so $T/lib.c > $T/m/rc.recipe
python3 tools/mkdelta.py $R/system/app/Test.odex $T/classes.dex $T/m/Test.lpd > /dev/null
echo '<permissions/>' > $T/m/a.xml

mod moda 1.0.0 ', "parts": [{"id": "main"}, {"id": "extra", "when": "settings.extra"}]' '
  {"type": "props", "path": "/system/build.prop", "set": {"persist.ium.support": "carplay"}, "stock": "any"},
  {"type": "lines", "path": "/system/onebin/{onebin}/featurexml.cfg", "comment": ["-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml"], "stock": "any"},
  {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "ra.recipe"},
  {"type": "add", "path": "/system/etc/permissions/a.xml", "src": "a.xml"},
  {"type": "app", "path": "/system/app/Test.apk", "delta": "Test.lpd"},
  {"type": "setting", "namespace": "global", "key": "wifi_on", "value": "1"},
  {"type": "props", "path": "/system/build.prop", "set": {"persist.wifi.state": "3"}, "stock": "any", "part": "extra"}'
cp $T/m/ra.recipe $T/m/a.xml $T/m/Test.lpd $R/data/hmgapi/mods/moda/
printf '{"settings": [{"key": "extra", "type": "toggle", "default": false}]}\n' > $R/data/hmgapi/mods/moda/settings.json
mod modb 2.1.0 ', "requires": ["moda >=1.0 <2"]' '
  {"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rb.recipe"},
  {"type": "props", "path": "/system/build.prop", "set": {"persist.modb": "yes"}, "stock": "any"}'
cp $T/m/rb.recipe $R/data/hmgapi/mods/modb/

# ---- plan and apply
eng plan
check "plan exit" 0 $rc
has "plan lists the library" "change   /system/lib/libtest.so [moda, modb]" "$out"
has "plan says restart" "restart the head unit" "$out"
check "plan writes nothing" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
eng apply
check "apply exit" 0 $rc
check "two recipes combined" "$(md5 $T/lib.ab)" "$(md5 $R/system/lib/libtest.so)"
has "props set" "persist.ium.support=carplay" "$(cat $R/system/build.prop)"
has "props from second mod" "persist.modb=yes" "$(cat $R/system/build.prop)"
has "other props kept" "ro.other=1" "$(cat $R/system/build.prop)"
check "part off by default" "" "$(grep persist.wifi.state $R/system/build.prop || true)"
has "line commented" "#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
has "other line kept" "-/system/etc/permissions/x.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
check "new file added" "<permissions/>" "$(cat $R/system/etc/permissions/a.xml)"
check "app rebuilt with classes.dex" "$(md5 $T/classes.dex)" "$(unzip -p $R/system/app/Test.apk classes.dex | md5sum | cut -d' ' -f1)"
check "setting written" "global/wifi_on=1" "$(cat $R/settings.txt)"
check "stock backup taken" "$STOCK_LIB" "$(md5 $R/data/hmgapi/stock/system/lib/libtest.so)"
check "odex backed up" "$STOCK_ODEX" "$(md5 $R/data/hmgapi/stock/system/app/Test.odex)"
check "no journal left" "no" "$([ -e $R/data/hmgapi/state/journal.json ] && echo yes || echo no)"
eng status
check "status ok" 0 $rc
eng apply
has "second apply has nothing to do" "nothing to do" "$out"

# ---- refusals: nothing changes
LIB_NOW=$(md5 $R/system/lib/libtest.so)
mod modc 1.0.0 '' '{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "rc.recipe"}'
cp $T/m/rc.recipe $R/data/hmgapi/mods/modc/
eng apply
check "overlapping recipe refused" 3 $rc
has "conflict named" "conflict: /system/lib/libtest.so" "$out"
check "refused apply wrote nothing" "$LIB_NOW" "$(md5 $R/system/lib/libtest.so)"
rm -r $R/data/hmgapi/mods/modc
mod modd 1.0.0 ', "requires": ["moda >=2"]' ''
eng plan
has "version requirement" "modd: requires moda >=2, but moda 1.0.0 is installed" "$out"
rm -r $R/data/hmgapi/mods/modd
mod mode 1.0.0 ', "conflicts": ["moda <1.5"]' ''
eng plan
has "conflicts list" "conflict: mode 1.0.0 can't be used with moda 1.0.0" "$out"
rm -r $R/data/hmgapi/mods/mode
mod modf 1.0.0 ', "hmgapi": ">=3.0"' ''
eng plan
has "needs newer HMG-API" "modf 1.0.0: needs HMG-API >=3.0" "$out"
rm -r $R/data/hmgapi/mods/modf
mod modg 1.0.0 ', "requires": [{"id": "modb", "version": "^2.0"}]' ''
eng plan
check "object-form requires met" 0 $rc
rm -r $R/data/hmgapi/mods/modg

# ---- a file changed outside HMG-API
cp $R/system/build.prop $T/bp.keep; echo "x=1" >> $R/system/build.prop
eng apply
check "outside change stops apply" 3 $rc
has "outside change reported" "/system/build.prop: changed outside HMG-API" "$out"
cp $T/bp.keep $R/system/build.prop

# ---- mod off: rebuilt from stock without it
touch $R/data/hmgapi/mods/modb/.disabled
eng apply
check "disable modb" 0 $rc
check "library without modb" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
check "modb's prop gone" "" "$(grep persist.modb $R/system/build.prop || true)"

# ---- trial: undone by recover, or kept
echo '{"extra": true}' > $R/data/hmgapi/settings/moda.json
BP_BEFORE=$(md5 $R/system/build.prop)
eng apply --trial
check "trial apply" 0 $rc
has "trial applied the part" "persist.wifi.state=3" "$(cat $R/system/build.prop)"
eng apply
check "another apply waits for keep/recover" 1 $rc
eng recover
check "recover" 0 $rc
check "trial undone" "$BP_BEFORE" "$(md5 $R/system/build.prop)"
eng apply --trial
eng keep
check "keep" 0 $rc
has "kept trial stays" "persist.wifi.state=3" "$(cat $R/system/build.prop)"
eng status
check "status after keep" 0 $rc

# ---- a firmware update put /system back to stock under HMG-API (install.sh runs engine firmware-reset)
echo '{"extra": false}' > $R/data/hmgapi/settings/moda.json
eng apply --trial
check "a trial waiting when the update comes" yes "$([ -f $R/data/hmgapi/state/journal.json ] && echo yes)"
S=$R/data/hmgapi/stock/system
for f in lib/libtest.so app/Test.apk app/Test.odex onebin/cvev_us/featurexml.cfg; do cp $S/$f $R/system/$f; done
printf 'ro.build.product=CVEV.USA.0000.V015\npersist.ium.support=none\nro.other=1\nro.newer=1\n' > $R/system/build.prop
rm -f $R/system/etc/permissions/a.xml
NEWPROP=$(md5 $R/system/build.prop)
eng firmware-reset
check "firmware-reset" 0 $rc
has "the trial dropped, not rolled back (that would write the old firmware's files)" "dropped an unfinished apply or trial" "$out"
check "... no journal" no "$([ -f $R/data/hmgapi/state/journal.json ] && echo yes || echo no)"
check "... the new build.prop untouched" "$NEWPROP" "$(md5 $R/system/build.prop)"
has "each replaced file said" "firmware  /system/build.prop: replaced by the update" "$out"
hasnt "... no file record left" "/system/" "$(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["files"]))' $R/data/hmgapi/state/files.json)"
check "... their backups gone" "" "$(find $R/data/hmgapi/stock -type f)"
has "... settings still recorded (an update keeps them)" "global/wifi_on" "$(cat $R/data/hmgapi/state/files.json)"
eng apply
check "the next apply builds on the new files" 0 $rc
has "... the mods' props on the new build.prop" "ro.newer=1" "$(cat $R/system/build.prop)"
has "... and their change" "persist.ium.support=carplay" "$(cat $R/system/build.prop)"
check "... the added file back" yes "$([ -f $R/system/etc/permissions/a.xml ] && echo yes)"
eng firmware-reset
has "firmware-reset with nothing replaced: nothing dropped" "no file HMG-API changed needed dropping" "$out"
STOCK_PROP=$NEWPROP   # the update's build.prop is stock from here on

# ---- restore everything
rm -f $R/data/hmgapi/mods/modb/.disabled
eng restore
check "restore exit" 0 $rc
check "build.prop stock" "$STOCK_PROP" "$(md5 $R/system/build.prop)"
check "featurexml stock" "$STOCK_CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
check "library stock" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
check "apk stock" "$STOCK_APK" "$(md5 $R/system/app/Test.apk)"
check "odex stock" "$STOCK_ODEX" "$(md5 $R/system/app/Test.odex)"
check "added file removed" "absent" "$(md5 $R/system/etc/permissions/a.xml)"
check "setting back to unset" "" "$(cat $R/settings.txt)"

# ---- baselines: not stock, override, NEMESIS backup
rm -r $R/data/hmgapi/mods/moda $R/data/hmgapi/state
mod modh 1.0.0 '' '{"type": "recipe", "path": "/system/lib/libtest.so", "recipe": "ra.recipe"}'
cp $T/m/ra.recipe $R/data/hmgapi/mods/modh/
rm -r $R/data/hmgapi/mods/modb
cp $T/lib.b $R/system/lib/libtest.so
eng plan
has "non-stock refused" "not the stock file [modh] expect" "$out"
mkdir -p $R/data/nemesis/backup/oldpkg/lib && cp $R/data/hmgapi/stock/system/lib/libtest.so $R/data/nemesis/backup/oldpkg/lib/libtest.so
eng apply
check "NEMESIS stock copy used" 0 $rc
has "NEMESIS note" "using the stock copy NEMESIS kept (lib)" "$out"
check "built from the NEMESIS copy" "$(md5 $T/lib.a)" "$(md5 $R/system/lib/libtest.so)"
eng restore
check "restore to stock bytes from the NEMESIS copy" "$STOCK_LIB" "$(md5 $R/system/lib/libtest.so)"
rm -r $R/data/nemesis $R/data/hmgapi/state $R/data/hmgapi/stock
cp $T/lib.b $R/system/lib/libtest.so
printf '{"overrides": {"accept_baseline": [{"path": "/system/lib/libtest.so", "md5": "%s"}]}}\n' "$(md5 $T/lib.b)" > $R/data/hmgapi/settings/hmgapi.json
eng apply
check "override accepts it" 0 $rc
has "override reported" "override /system/lib/libtest.so" "$out"
check "override: both changes" "$(md5 $T/lib.ab)" "$(md5 $R/system/lib/libtest.so)"
eng restore
check "restore to the owner's file" "$(md5 $T/lib.b)" "$(md5 $R/system/lib/libtest.so)"

# ---- feature flags: shared between mods, kept while any mod wants them
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock $R/data/hmgapi/settings/hmgapi.json
CFG=$(md5 $R/system/onebin/cvev_us/featurexml.cfg)
for m in fa fb; do
    mkdir -p $R/data/hmgapi/mods/$m
    printf '{"id": "%s", "version": "1.0.0", "feature_flags": ["com.hkmc.software.connectivity.carplay_wireless"]}\n' $m > $R/data/hmgapi/mods/$m/mod.json
done
eng apply
check "two mods, one feature: applies" 0 $rc
has "the feature file" '<feature name="com.hkmc.software.connectivity.carplay_wireless" />' "$(cat $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml 2>/dev/null)"
has "its featurexml line commented" "#-/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml" "$(cat $R/system/onebin/cvev_us/featurexml.cfg)"
export HMGAPI_PROPS="persist.service.adb.tcp.enable=1"; eng plan
has "the car's ADB TCP switch on with wireless projection: warned" "WARNING: the car's ADB TCP switch is on" "$out"
export HMGAPI_PROPS="persist.service.adb.tcp.enable=0"; eng plan
hasnt "... off: no warning" "ADB TCP switch" "$out"
unset HMGAPI_PROPS
touch $R/data/hmgapi/mods/fa/.disabled
eng apply
check "one mod off: the other still has it" yes "$([ -f $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml ] && echo yes || echo no)"
touch $R/data/hmgapi/mods/fb/.disabled
eng apply
check "both off: the file goes" no "$([ -f $R/system/etc/permissions/com.hkmc.software.connectivity.carplay_wireless.xml ] && echo yes || echo no)"
check "... and featurexml.cfg is stock again" "$CFG" "$(md5 $R/system/onebin/cvev_us/featurexml.cfg)"
rm -r $R/data/hmgapi/mods/fa $R/data/hmgapi/mods/fb
mkdir -p $R/data/hmgapi/mods/fc
printf '{"id": "fc", "version": "1.0.0", "feature_flags": ["rm -rf"]}\n' > $R/data/hmgapi/mods/fc/mod.json
eng plan
has "odd feature names refused" 'feature "rm -rf": a com.hkmc.*' "$out"

# ---- media_codecs.xml: edits by codec name, merged between mods
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
cat > $R/system/etc/media_codecs.xml <<'X'
<MediaCodecs>
    <Include href="media_codecs_google_audio.xml" />
    <Decoders>
        <MediaCodec name="OMX.TCC.avcdec" type="video/avc" />
        <MediaCodec name="OMX.TCC.hevcdec" type="video/hevc">
            <Quirk name="requires-allocate-on-input-ports" />
        </MediaCodec>
        <!-- <MediaCodec name="OMX.google.h264.decoder" type="video/avc" /> -->
        <MediaCodec name="OMX.google.h264.decoder" type="video/avc" />
    </Decoders>
    <Encoders>
        <MediaCodec name="OMX.TCC.avcenc" type="video/avc" />
    </Encoders>
</MediaCodecs>
X
CODECS=$(md5 $R/system/etc/media_codecs.xml)
mkdir -p $R/data/hmgapi/mods/ca $R/data/hmgapi/mods/cb
printf '{"id": "ca", "version": "1.0.0"}\n' > $R/data/hmgapi/mods/ca/mod.json
printf '{"id": "cb", "version": "1.0.0"}\n' > $R/data/hmgapi/mods/cb/mod.json
printf '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "remove": ["OMX.google.h264.decoder"], "add": [{"name": "OMX.google.vp9.decoder", "type": "video/x-vnd.on2.vp9"}]}]}\n' > $R/data/hmgapi/mods/ca/patches.json
printf '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "remove": ["OMX.TCC.hevcdec"]}]}\n' > $R/data/hmgapi/mods/cb/patches.json
eng apply
check "two mods' codec edits: applied" 0 $rc
x=$(cat $R/system/etc/media_codecs.xml)
hasnt "a removed codec is gone" '    <MediaCodec name="OMX.google.h264.decoder" type="video/avc" />
    </Decoders>' "$x"
has "... the commented-out one is untouched" '<!-- <MediaCodec name="OMX.google.h264.decoder" type="video/avc" /> -->' "$x"
hasnt "the other mod's removal, with its quirks" "OMX.TCC.hevcdec" "$x"
has "an added codec goes last in Decoders" '        <MediaCodec name="OMX.google.vp9.decoder" type="video/x-vnd.on2.vp9" />
    </Decoders>' "$x"
has "encoders untouched" '<MediaCodec name="OMX.TCC.avcenc" type="video/avc" />' "$x"
mkdir -p $R/data/hmgapi/mods/cc
printf '{"id": "cc", "version": "1.0.0"}\n' > $R/data/hmgapi/mods/cc/mod.json
printf '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "add": [{"name": "OMX.google.h264.decoder", "type": "video/avc"}]}]}\n' > $R/data/hmgapi/mods/cc/patches.json
eng plan
has "adding what another mod removes: conflict" "conflict: /system/etc/media_codecs.xml: codec OMX.google.h264.decoder: ca removes it, cc adds it" "$out"
rm -r $R/data/hmgapi/mods/cc
eng restore
check "restore: stock bytes" "$CODECS" "$(md5 $R/system/etc/media_codecs.xml)"

# ---- a mod's install-time probe (Codec Patch: decided on the unit)
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
mkdir -p $R/data/hmgapi/mods/probe/lib
printf '{"id": "probe", "version": "1.0.0", "probe": {"script": "probe.sh"}}\n' > $R/data/hmgapi/mods/probe/mod.json
echo soft > $R/data/hmgapi/mods/probe/lib/libsoft.so
cat > $R/data/hmgapi/mods/probe/probe.sh <<'P'
echo "probe ran" >> "$HMGAPI_MOD_DIR/runs"
echo "looking at codecs" >&2
echo '{"changes": [{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any", "remove": ["OMX.TCC.hevcdec"]},'
echo '             {"type": "add", "path": "/system/lib/libsoft.so", "src": "lib/libsoft.so", "mode": "644", "stock": "absent"}]}'
P
eng apply
check "probe's changes applied" 0 $rc
has "probe reported in the plan" "probe    probe: 2 change(s) from its look at this unit" "$out"
hasnt "... codec removed" "OMX.TCC.hevcdec" "$(cat $R/system/etc/media_codecs.xml)"
check "... file added" soft "$(cat $R/system/lib/libsoft.so 2>/dev/null)"
has "its stderr in its log" "looking at codecs" "$(cat $R/data/hmgapi/logs/probe/probe.log 2>/dev/null)"
eng plan
check "the answer is kept: not run again" 1 "$(grep -c ran $R/data/hmgapi/mods/probe/runs)"
check "... and the plan stays the same" 0 $rc
eng probe probe
eng plan
check "engine probe <mod>: runs again" 2 "$(grep -c ran $R/data/hmgapi/mods/probe/runs)"
eng restore
cat > $R/data/hmgapi/mods/probe/probe.sh <<'P'
echo '{"changes": [{"type": "add", "path": "/system/bin/sh", "src": "../../../../system/bin/x"}]}'
P
eng probe probe
eng plan
has "probe: only codecs and the mod's own files" "probe: only" "$out"
printf 'exit 3\n' > $R/data/hmgapi/mods/probe/probe.sh
eng probe probe
eng plan
has "a failing probe: reported, nothing from it" "probe failed (exit 3" "$out"

# ---- list settings (KeyBORD: enabled keyboards), merged per entry
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
echo "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME" > $R/settings.txt
for m in ka kb; do mkdir -p $R/data/hmgapi/mods/$m; printf '{"id": "%s", "version": "1.0.0"}\n' $m > $R/data/hmgapi/mods/$m/mod.json; done
printf '{"changes": [{"type": "setting", "namespace": "secure", "key": "enabled_input_methods", "add": ["org.pocketworkstation.pckeyboard/.LatinIME"]},
  {"type": "setting", "namespace": "secure", "key": "default_input_method", "value": "org.pocketworkstation.pckeyboard/.LatinIME"}]}\n' > $R/data/hmgapi/mods/ka/patches.json
printf '{"changes": [{"type": "setting", "namespace": "secure", "key": "enabled_input_methods", "add": ["dev.example/.Ime", "com.android.inputmethod.latin/.LatinIME"]}]}\n' > $R/data/hmgapi/mods/kb/patches.json
eng apply
check "list setting: applies" 0 $rc
has "stock entry kept, both mods' added once each" "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME:org.pocketworkstation.pckeyboard/.LatinIME:dev.example/.Ime" "$(cat $R/settings.txt)"
touch $R/data/hmgapi/mods/kb/.disabled
eng apply
check "one mod off: its entry goes" "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME:org.pocketworkstation.pckeyboard/.LatinIME" "$(grep enabled_input $R/settings.txt)"
touch $R/data/hmgapi/mods/ka/.disabled
eng apply
check "both off: the stock list" "secure/enabled_input_methods=com.android.inputmethod.latin/.LatinIME" "$(grep enabled_input $R/settings.txt)"
check "... and the default keyboard back to stock (unset)" "" "$(grep default_input $R/settings.txt)"
rm $R/data/hmgapi/mods/ka/.disabled $R/data/hmgapi/mods/kb/.disabled
printf '{"changes": [{"type": "setting", "namespace": "secure", "key": "enabled_input_methods", "value": "only/.This"}]}\n' > $R/data/hmgapi/mods/kb/patches.json
eng plan
has "a value and a list edit on one key: conflict" "conflict: setting secure/enabled_input_methods: ka adds to it, kb sets it to only/.This" "$out"

# ---- Wi-Fi client: the same two changes for every mod that asks
rm -rf $R/data/hmgapi/mods/* $R/data/hmgapi/state $R/data/hmgapi/stock
for m in wa wb; do mkdir -p $R/data/hmgapi/mods/$m; printf '{"id": "%s", "version": "1.0.0", "wifi": {"client": true}}\n' $m > $R/data/hmgapi/mods/$m/mod.json; done
eng apply
check "two mods ask for the Wi-Fi client: applies" 0 $rc
has "the client feature" '<feature name="android.hardware.wifi" />' "$(cat $R/system/etc/permissions/android.hardware.wifi.xml 2>/dev/null)"
check "persist.wifi.state=3" "prop/persist.wifi.state=3" "$(grep persist.wifi.state $R/settings.txt)"
touch $R/data/hmgapi/mods/wa/.disabled $R/data/hmgapi/mods/wb/.disabled
eng apply
check "both off: feature gone" no "$([ -f $R/system/etc/permissions/android.hardware.wifi.xml ] && echo yes || echo no)"
check "... state back" "" "$(grep persist.wifi.state $R/settings.txt)"
exit $fail

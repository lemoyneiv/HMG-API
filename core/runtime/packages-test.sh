#!/bin/sh
# Off-car test of mod packages (engine install/remove): signatures, author UUIDs,
# the first-install confirmation, versions, system requirements, unsafe zips,
# and that settings and on/off survive an update.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
sig() { java -cp "$CP" hmgapi.engine.Signing "$@"; }
pkg() {   # pkg <dir> <zip>
    (cd "$1" && rm -f "$2" && zip -q -r -X "$2" .)
}
mkdir -p $R/data/hmgapi/mods $R/data/hmgapi/settings
printf 'ro.board.platform=tcc803x\n' > $R/props.txt
sig keygen $T/author.key > /dev/null
sig keygen $T/other.key > /dev/null
U=$(sig uuid $T/author.key)
mk() {   # mk <version> [system json]
    rm -rf $T/m; mkdir -p $T/m
    printf '{"id": "demo", "version": "%s", "hmgapi": ">=1.0.0"%s}\n' "$1" "${2:+, \"system\": $2}" > $T/m/mod.json
    printf '{"changes": []}\n' > $T/m/patches.json
}

mk 1.0.0; sig sign $T/m $T/author.key > /dev/null; pkg $T/m $T/demo.zip
eng install $T/demo.zip --uuid 00000000-0000-8000-8000-000000000000
check "a given UUID that doesn't match: refused" 3 $rc
check "nothing installed yet" no "$([ -d $R/data/hmgapi/mods/demo ] && echo yes || echo no)"
eng install $T/demo.zip
check "first install: no key step" 0 $rc
has "an author not on the trusted list: warned" "WARNING: demo is by author key $U, which isn't on HMG-API's trusted author list" "$out"
has "install message" "installed demo 1.0.0 (author key $U)" "$out"
rm -r $R/data/hmgapi/mods/demo
mkdir -p $R/data/hmgapi/maps
printf '{"authors": {"%s": {"name": "Test Author"}}}\n' "$U" > $R/data/hmgapi/maps/authors.json
eng install $T/demo.zip
has "an author on the trusted list: named, no warning" "author   Test Author (author key $U, on HMG-API's trusted author list)" "$out"
hasnt "... no warning" "WARNING" "$out"

printf '{"x": 1}\n' > $R/data/hmgapi/settings/demo.json; : > $R/data/hmgapi/mods/demo/.disabled
mk 1.1.0; sig sign $T/m $T/author.key > /dev/null; pkg $T/m $T/demo2.zip
eng install $T/demo2.zip
check "update by the same author" 0 $rc
check "settings kept" '{"x": 1}' "$(cat $R/data/hmgapi/settings/demo.json)"
check "still turned off" yes "$([ -f $R/data/hmgapi/mods/demo/.disabled ] && echo yes)"
has "update is in place" '"version": "1.1.0"' "$(cat $R/data/hmgapi/mods/demo/mod.json)"

mk 1.2.0; sig sign $T/m $T/other.key > /dev/null; pkg $T/m $T/fake.zip
eng install $T/fake.zip --uuid $(sig uuid $T/other.key)
check "update signed by someone else refused" 3 $rc
has "says why" "signed by a different author" "$out"
has "installed one untouched" '"version": "1.1.0"' "$(cat $R/data/hmgapi/mods/demo/mod.json)"

mk 1.3.0; sig sign $T/m $T/author.key > /dev/null; echo tampered >> $T/m/patches.json; pkg $T/m $T/tamper.zip
eng install $T/tamper.zip; check "changed after signing: refused" 3 $rc
has "names the file" "file changed after signing: patches.json" "$out"

mk 1.0.5; sig sign $T/m $T/author.key > /dev/null; pkg $T/m $T/old.zip
eng install $T/old.zip; check "older version refused" 3 $rc
eng install $T/old.zip --force; check "older version with --force" 0 $rc

mk 2.0.0; pkg $T/m $T/unsigned.zip
eng install $T/unsigned.zip; check "unsigned refused" 3 $rc
mk 2.0.0 '{"platform": ["tcc897x"]}'; sig sign $T/m $T/author.key > /dev/null; pkg $T/m $T/kr.zip
eng install $T/kr.zip; check "wrong head unit refused" 3 $rc
has "with the reason" "isn't for this head unit: needs platform tcc897x" "$out"
has "and how to install it anyway" "Install anyway" "$out"

python3 - $T/evil.zip <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as z:
    z.writestr("mod.json", '{"id": "evil", "version": "1.0.0"}')
    z.writestr("../../escape.txt", "x")
PY
eng install $T/evil.zip; check "path escape in a zip refused" 1 $rc
check "nothing written outside" no "$([ -e $R/data/escape.txt ] || [ -e $R/escape.txt ] && echo yes || echo no)"

mkdir -p $R/data/hmgapi/dalvik-cache/demo $R/data/hmgapi/dalvik-cache/other-mod
echo old > $R/data/hmgapi/dalvik-cache/hooks.dex
echo demo > $R/data/hmgapi/dalvik-cache/demo/hooks.dex
echo other > $R/data/hmgapi/dalvik-cache/other-mod/hooks.dex
eng remove demo; check "remove" 0 $rc
check "removed" no "$([ -d $R/data/hmgapi/mods/demo ] && echo yes || echo no)"
check "settings stay after remove" '{"x": 1}' "$(cat $R/data/hmgapi/settings/demo.json)"
check "obsolete shared DEX removed" no "$([ -e $R/data/hmgapi/dalvik-cache/hooks.dex ] && echo yes || echo no)"
check "removed mod's central DEX directory removed" no "$([ -e $R/data/hmgapi/dalvik-cache/demo ] && echo yes || echo no)"
check "another mod's central DEX directory preserved" other "$(cat $R/data/hmgapi/dalvik-cache/other-mod/hooks.dex)"

# "Install anyway": a mod for another head unit, at the owner's risk
ign() { python3 -c "import json,sys; print(json.load(open(sys.argv[1])).get('overrides', {}).get('ignore_system'))" $R/data/hmgapi/settings/hmgapi.json 2>/dev/null; }
eng install $T/kr.zip --any-car; check "install anyway" 0 $rc
has "with a warning" "WARNING: demo isn't for this head unit (needs platform tcc897x (this unit: tcc803x)); installing it anyway" "$out"
check "recorded for the loader and the engine" "['demo']" "$(ign)"
eng plan; has "the engine goes on with it" "WARNING: demo: needs platform tcc897x (this unit: tcc803x); overrides.ignore_system is set" "$out"
eng remove demo; check "remove it" 0 $rc
check "the override goes with it" "[]" "$(ign)"
eng install $T/demo.zip --any-car; check "--any-car on a mod for this unit: plain install" 0 $rc
check "... nothing recorded" "[]" "$(ign)"
exit $fail

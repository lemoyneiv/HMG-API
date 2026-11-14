#!/bin/sh
# Run the off-car tests, several at once (-j, default one per CPU).
#   tools/test-all.sh [--fast] [-j N] [test script...]   only those (default: every one)
# --fast: the tests that need no ARM toolchain and no build: a JDK, python3 with
#   tools/requirements.txt and a C compiler (for out/lpatch-host). Seconds, on Linux or macOS.
# Otherwise every test (CI): needs the build (./build.sh), clang + lld, qemu-arm binfmt, mksh.
# Each test: "ok" (its checks, and how many it skipped), "skip" (it skipped everything,
# and why) or "FAIL" (its failed checks).
cd "$(dirname "$0")/.."
ALL="core/hmgapid/test.sh core/zygote/test.sh tools/lpatch/test.sh core/runtime/engine-test.sh core/runtime/signing-test.sh core/runtime/system-test.sh core/runtime/safety-test.sh core/runtime/packages-test.sh core/runtime/map-test.sh core/runtime/usb-test.sh core/runtime/hooks-test.sh core/runtime/mod-dex-test.sh core/runtime/live-test.sh core/runtime/native-test.sh core/runtime/runtime-test.sh core/runtime/net-test.sh core/runtime/overlap-test.sh core/runtime/keys-test.sh core/runtime/commands-test.sh core/runtime/ble-test.sh core/runtime/scriptcheck-test.sh core/hookpoints/test.sh core/app/qr-test.sh install/common/test.sh install/common/install-test.sh install/usb/test.sh install/nemesis/mod-test.sh install/nemesis/sync-test.sh tools/mod-draft-test.sh tools/catalog-test.sh tools/version-test.sh tools/mkicon-test.sh tools/micom-decode-test.sh tools/cars-test.sh tools/sdk-test.sh tools/check-scripts.sh"
# no build, no ARM toolchain: all but hmgapid, the zygote wrapper, lpatch, the installers and the SDK
FAST="core/runtime/engine-test.sh core/runtime/signing-test.sh core/runtime/system-test.sh core/runtime/safety-test.sh core/runtime/packages-test.sh core/runtime/map-test.sh core/runtime/usb-test.sh core/runtime/hooks-test.sh core/runtime/mod-dex-test.sh core/runtime/live-test.sh core/runtime/native-test.sh core/runtime/runtime-test.sh core/runtime/net-test.sh core/runtime/overlap-test.sh core/runtime/keys-test.sh core/runtime/commands-test.sh core/runtime/ble-test.sh core/runtime/scriptcheck-test.sh core/hookpoints/test.sh core/app/qr-test.sh install/common/test.sh install/nemesis/mod-test.sh install/nemesis/sync-test.sh tools/mod-draft-test.sh tools/catalog-test.sh tools/version-test.sh tools/mkicon-test.sh tools/micom-decode-test.sh tools/check-scripts.sh"
# these write out/ (usb: out/payload and out/dist; sdk: out/mods, out/packages, out/dist) or read
# what another writes (install-test: out/payload), so they run one after another
SERIAL="install/common/install-test.sh install/usb/test.sh tools/sdk-test.sh"

# --run <dir> <test[,test...]>: run them in turn, each one's output and exit code in <dir>
if [ "$1" = --run ]; then
    for t in $(echo "$3" | sed 's/,/ /g'); do
        f=$2/$(echo "$t" | sed 's#/#_#g')
        s=$(date +%s); sh "$t" > "$f.out" 2>&1; echo $? > "$f.rc"; echo $(($(date +%s) - s)) > "$f.time"
    done
    exit 0
fi

J=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)
TESTS=$ALL
while [ $# -gt 0 ]; do
    case $1 in
        --fast) TESTS=$FAST; FASTMODE=1 ;;
        -j) J=$2; shift ;;
        -j*) J=${1#-j} ;;
        *) break ;;
    esac
    shift
done
[ $# -gt 0 ] && TESTS="$*"   # tests named on the command line (tools/test-select.py picks them in CI)

# the Python packages at tools/requirements.txt's versions: bsdiff4 1.4 changed bsdiff4.core.diff,
# which tools/mkdelta.py uses, so a plain `pip install bsdiff4` breaks the delta tests
python3 - tools/requirements.txt <<'PY' || exit 1
import re, sys
from importlib import metadata
bad = []
for name, want in re.findall(r'^([A-Za-z0-9_.-]+)==(\S+)', open(sys.argv[1]).read(), re.M):
    try:
        have = metadata.version(name)
    except metadata.PackageNotFoundError:
        have = 'not installed'
    if have != want:
        bad.append('%s %s (tools/requirements.txt: %s)' % (name, have, want))
if bad:
    sys.exit('FAIL  python packages: %s\n      pip install --require-hashes -r tools/requirements.txt (in a venv if pip refuses)' % '; '.join(bad))
PY
if [ -n "$FASTMODE" ]; then
    # lpatch-host alone (tools/lpatch/build.sh's host line), so no clang + lld
    [ -x out/lpatch-host ] || { mkdir -p out && cc -DHOST -O2 -Wall -o out/lpatch-host tools/lpatch/lpatch.c; } || exit 1
else
    [ -x out/lpatch-host ] || sh build.sh lpatch >/dev/null
fi

R=$(mktemp -d); trap 'rm -rf "$R"' EXIT
START=$(date +%s)
# hmgapi.engine compiled once for every test (tools/engine-classes.sh); if it doesn't compile,
# each test tries and says so
mkdir -p "$R/engine" && javac --release 8 -Xlint:-options -nowarn -d "$R/engine" core/runtime/java/hmgapi/engine/*.java 2>/dev/null \
    && HMGAPI_TEST_ENGINE=$R/engine && export HMGAPI_TEST_ENGINE
# the serial ones as one item, first (together they take longest), then the rest, J at a time
lane=""; rest=""
for t in $TESTS; do
    case " $SERIAL " in *" $t "*) lane="${lane:+$lane,}$t" ;; *) rest="$rest $t" ;; esac
done
printf '%s\n' $lane $rest | xargs -P "$J" -n 1 sh tools/test-all.sh --run "$R"

rc=0; n_ok=0; n_skip=0; n_fail=0
for t in $TESTS; do
    f=$R/$(echo "$t" | sed 's#/#_#g')
    c=$(grep -c '^ok' "$f.out"); s=$(grep -c '^skip' "$f.out"); d="$(cat "$f.time" 2>/dev/null || echo 0) s"
    if [ "$(cat "$f.rc" 2>/dev/null)" != 0 ]; then
        echo "FAIL  $t ($d)"; grep -E '^FAIL' "$f.out" || tail -15 "$f.out"; rc=1; n_fail=$((n_fail + 1))
    elif [ "$c" = 0 ] && [ "$s" -gt 0 ]; then
        echo "skip  $t ($(sed -n 's/^skip *//p' "$f.out" | sed -n 1p))"; n_skip=$((n_skip + 1))
    elif [ "$c" = 0 ]; then
        echo "FAIL  $t: no checks ran, and it didn't say it skipped"; tail -15 "$f.out"; rc=1; n_fail=$((n_fail + 1))
    elif [ "$s" -gt 0 ]; then
        echo "ok    $t ($c checks, $s skipped, $d)"; n_ok=$((n_ok + 1))
    else
        echo "ok    $t ($c checks, $d)"; n_ok=$((n_ok + 1))
    fi
done
echo "$n_ok ok, $n_skip skipped, $n_fail failed, in $(($(date +%s) - START)) s (-j $J)"
exit $rc

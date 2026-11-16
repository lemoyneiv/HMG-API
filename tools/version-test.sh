#!/bin/sh
# Versions sort the same everywhere (docs/releasing.md): hmgapi.engine.Version (order and
# ranges, VersionTest), and on VersionTest's list tools/check-points.py and tools/catalog.py,
# and NEMESIS's package-versions from tools/nemesis-version.py and NemesisSync.packageVersion.
set -e
cd "$(dirname "$0")/.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
sh tools/engine-classes.sh "$CP"
javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" core/runtime/test/hmgapi/engine/VersionTest.java
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
java -cp "$CP" hmgapi.engine.VersionTest || fail=1
ORDER=$(java -cp "$CP" hmgapi.engine.VersionTest order)

# the Python tools' order: the same list, unsorted, sorted back by each one's key
check "check-points.py sorts as Version" "$ORDER" "$(echo "$ORDER" | python3 -c '
import random, runpy, sys
cp = runpy.run_path("tools/check-points.py")
vs = sys.stdin.read().split(); random.Random(1).shuffle(vs)
print("\n".join(sorted(vs, key=lambda v: cp["key"](cp["ver"](v)))))')"
check "catalog.py sorts as Version" "$ORDER" "$(echo "$ORDER" | python3 -c '
import random, runpy, sys
c = runpy.run_path("tools/catalog.py")
vs = sys.stdin.read().split(); random.Random(2).shuffle(vs)
print("\n".join(sorted(vs, key=c["vkey"])))')"

# check-points.py: a pre-release floor against the SDK's version (no hook points: nothing fetched)
mkdir -p "$CP/sdk/bin" "$CP/m/m"; cp tools/check-points.py "$CP/sdk/bin/"
floor() {   # floor <SDK version> <mod version> <"hmgapi">: check-points.py's exit code
    echo "$1" > "$CP/sdk/VERSION"
    printf '{"id": "m", "version": "%s", "hmgapi": "%s"}\n' "$2" "$3" > "$CP/m/m/mod.json"
    set +e; out=$(python3 "$CP/sdk/bin/check-points.py" "$CP/m/m" 2>&1); echo $?; set -e
}
check "check-points: mod rc, floor rc.1, SDK rc.1" 0 "$(floor 1.1.0-rc.1 2.2.0-rc.1 '>=1.1.0-rc.1')"
check "check-points: mod rc, floor rc.1, SDK rc.2" 0 "$(floor 1.1.0-rc.2 2.2.0-rc.1 '>=1.1.0-rc.1')"
check "check-points: mod rc, floor rc.9, SDK rc.10" 0 "$(floor 1.1.0-rc.10 2.2.0-rc.1 '>=1.1.0-rc.9')"
check "check-points: mod rc, floor rc.1, SDK 1.1.0" 0 "$(floor 1.1.0 2.2.0-rc.1 '>=1.1.0-rc.1')"
check "check-points: mod rc, floor rc.1, SDK beta.1: fails" 1 "$(floor 1.1.0-beta.1 2.2.0-rc.1 '>=1.1.0-rc.1')"
check "check-points: mod rc, floor 1.1.0, SDK rc.1 (as the engine: rc.1 meets >=1.1.0)" 0 "$(floor 1.1.0-rc.1 2.2.0-rc.1 '>=1.1.0')"
check "check-points: final mod, floor 1.1.0, SDK rc.2 (a mod's final before core's)" 0 "$(floor 1.1.0-rc.2 2.2.0 '>=1.1.0')"
# ... where a floor's hook points come from: the release's SDK zip from 1.1.0-beta.1 (the first
# with one), the catalogue at its tag before; a floor that isn't a release fails with a line
# saying so, not a traceback (">=1.0.3": the release planned under that name became 1.1.0)
src() {   # src <floor>: "git" or "sdk" (what was asked for), then check-points.py's last line
    echo 1.1.0-rc.1 > "$CP/sdk/VERSION"
    printf '{"id": "m", "version": "2.2.0-rc.1", "hmgapi": ">=%s", "hooks": {"points": {"a.b": "observe"}}}\n' "$1" > "$CP/m/m/mod.json"
    python3 - "$CP/sdk/bin/check-points.py" "$CP/m/m" <<'PY'
import contextlib, io, runpy, subprocess, sys, urllib.error
cp = runpy.run_path(sys.argv[1])
g = cp["main"].__globals__
def no_git(*a, **k): print("git"); raise subprocess.CalledProcessError(128, a[0])
def no_net(*a, **k): print("sdk"); raise urllib.error.URLError("no network in the test")
g["subprocess"].run, g["urllib"].request.urlopen = no_git, no_net
o = io.StringIO()
with contextlib.redirect_stdout(o):
    rc = cp["main"]([sys.argv[2]])
lines = o.getvalue().split("\n")
print(lines[0], rc, lines[1][:60])
PY
}
check "check-points: a floor before the first SDK (1.0.2): the catalogue at its tag" "git 1 FAIL  m: \"hmgapi\": '>=1.0.2': can't get HMG-API 1.0.2's hook" "$(src 1.0.2)"
check "check-points: floor 1.0.3, never a release: said in one line, no traceback" "git 1 FAIL  m: \"hmgapi\": '>=1.0.3': can't get HMG-API 1.0.3's hook" "$(src 1.0.3)"
check "check-points: floor 1.1.0-beta.1, the first release with an SDK: its SDK zip" "sdk 1 FAIL  m: \"hmgapi\": '>=1.1.0-beta.1': can't get HMG-API 1.1.0" "$(src 1.1.0-beta.1)"
check "check-points: final mod, floor 1.2.0, SDK 1.1.0-rc.2: fails" 1 "$(floor 1.1.0-rc.2 2.2.0 '>=1.2.0')"
check "check-points: final mod, floor rc.1: fails" 1 "$(floor 1.1.0 2.2.0 '>=1.1.0-rc.1')"
check "check-points: final mod, floor 1.1.0, SDK 1.1.0" 0 "$(floor 1.1.0 2.2.0 '>=1.1.0')"
# with hook points, a final floor on its own rc's SDK takes the SDK's own points.txt
printf 'a.point\nb.point\n' > "$CP/sdk/points.txt"; echo 1.1.0-rc.2 > "$CP/sdk/VERSION"
printf '{"id": "m", "version": "2.2.0", "hmgapi": ">=1.1.0", "hooks": {"points": {"a.point": "observe"}}}\n' > "$CP/m/m/mod.json"
check "check-points: ... its points from the SDK's own list" 0 "$(set +e; python3 "$CP/sdk/bin/check-points.py" "$CP/m/m" >/dev/null 2>&1; echo $?)"
printf '{"id": "m", "version": "2.2.0", "hmgapi": ">=1.1.0", "hooks": {"points": {"c.point": "observe"}}}\n' > "$CP/m/m/mod.json"
check "check-points: ... a point it doesn't have: fails" 1 "$(set +e; python3 "$CP/sdk/bin/check-points.py" "$CP/m/m" >/dev/null 2>&1; echo $?)"

# NEMESIS's package-version
pv() { python3 tools/nemesis-version.py "$1" 2>/dev/null || echo none; }
for v in 1.1.0 1.1.0-alpha.1 1.1.0-beta.1 1.1.0-rc.1 1.1.0-rc.10 1.0.3-rc.2 2.0.0-beta.3 1.0.0-rc.1; do
    case $v in
        1.1.0) want=1.1.0 ;; 1.1.0-alpha.1) want=1.0.97.1 ;; 1.1.0-beta.1) want=1.0.98.1 ;; 1.1.0-rc.1) want=1.0.99.1 ;;
        1.1.0-rc.10) want=1.0.99.10 ;; 1.0.3-rc.2) want=1.0.2.99.2 ;; 2.0.0-beta.3) want=1.98.3 ;; 1.0.0-rc.1) want=0.99.1 ;;
    esac
    check "package-version of $v" "$want" "$(pv $v)"
done
for v in 1.1.0-pre.1 1.1.0-rc 1.1.0-rc.01 1.1.0-beta.x 0.0.0-rc.1; do
    check "no package-version for $v" none "$(pv $v)"
done
# every list form, Python and Java alike; and they sort as the versions, compared as NEMESIS
# does (dotted numbers, part by part)
EVERY="0.9.0 1.0.0-alpha.1 1.0.0-beta.4 1.0.0 1.0.2 1.0.3-rc.1 1.1.0-alpha.1 1.1.0-alpha.2 1.1.0-beta.1 1.1.0-beta.2 1.1.0-beta.11 1.1.0-rc.1 1.1.0-rc.2 1.1.0-rc.9 1.1.0-rc.10 1.1.0 1.1.1-rc.1 1.1.1 1.2.0-beta.1 1.10.0 2.0.0-rc.1 2.0.0"
py=$(for v in $EVERY; do pv $v; done)
check "NemesisSync.packageVersion is nemesis-version.py" "$py" "$(java -cp "$CP" hmgapi.engine.VersionTest pv $EVERY)"
check "package-versions sort as the versions" yes "$(echo "$py" | python3 -c '
import sys
k = [tuple(map(int, l.split("."))) for l in sys.stdin.read().split()]
print("yes" if all(a < b for a, b in zip(k, k[1:])) else "no: %s" % k)')"
# what has shipped: 1.0.0's betas were 0.99.<n> (the old rule), below everything since
check "1.0.0's betas (0.99.1..0.99.6) below every version since" yes "$(echo "$py" | python3 -c '
import sys
since = [tuple(map(int, l.split("."))) for l in sys.stdin.read().split()][3:]   # 1.0.0 on
print("yes" if all((0, 99, n) < s for n in range(1, 7) for s in since) else "no")')"
check "1.1.0-beta.1 below 1.1.0-rc.1" yes "$(python3 -c '
import sys
a, b = (tuple(map(int, x.split("."))) for x in sys.argv[1:])
print("yes" if a < b else "no")' "$(pv 1.1.0-beta.1)" "$(pv 1.1.0-rc.1)")"

[ $fail = 0 ] && echo "version: all ok" || { echo "version: FAILED"; exit 1; }

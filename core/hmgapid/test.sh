#!/bin/sh
# Off-car test of hmgapid under qemu-arm (binfmt): the real daemon and client,
# pointed at a temp root, the host's sh, and a fake packages.list. Three builds
# test the three kinds of caller: shell/root, the hmgapi.app uid, anyone else.
set -e
cd "$(dirname "$0")"
# what the daemon runs gets the unit's PATH (/sbin:/system/bin:...): host commands by full path
SLEEP=$(command -v sleep); CAT=$(command -v cat); SH=$(command -v sh)
T=$(mktemp -d)
cleanup() {
    pkill -f "$T/" 2>/dev/null || true   # the test daemons (their paths contain $T)
    rm -rf "$T"
}
trap cleanup EXIT
[ -n "$KEEP_T" ] && trap - EXIT
ME=$(id -u)
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }

# build <name> <shell uid> <packages.list line or empty>
build() {
    mkdir -p "$T/$1/root/mods"
    printf '%s\n' "$3" > "$T/$1/packages.list"
    printf '#!/bin/sh\necho "fake logcat $*"\n' > "$T/$1/logcat"
    printf '#!/bin/sh\necho "$*" >> "%s/engine.log"\ncase "$*" in *--trial*) '"$SLEEP"' 2; : > "%s/engine.done" ;; esac\necho "app_process $* CLASSPATH=$CLASSPATH ROOT=$ANDROID_ROOT KEEP=$KEEPME PRELOAD=${LD_PRELOAD:-none}"\n' "$T/$1" "$T/$1" > "$T/$1/app_process"
    printf '#!/bin/sh\n'"$CAT"' "%s/bootprop" 2>/dev/null\n' "$T/$1" > "$T/$1/getprop"
    chmod +x "$T/$1/logcat" "$T/$1/app_process" "$T/$1/getprop"
    OUT="$T/$1" sh build.sh -DNOCAPCHECK -DROOTDIR="\"$T/$1/root\"" -DSH="\"/bin/sh\"" -DLOGCAT="\"$T/$1/logcat\"" \
        -DREBOOT="\"/bin/true\"" -DAPP_PROCESS="\"$T/$1/app_process\"" -DPACKAGES_LIST="\"$T/$1/packages.list\"" -DGETPROP="\"$T/$1/getprop\"" -DUSBROOT="\"$T/$1/usb\"" -DBOOTMARK="\"$T/$1/bootmark\"" -DSOCK_NAME="\"hmgapid-test-$1-$$\"" -DVEHICLE_SOCK="\"hmgapi-vehicle-test-$$\"" \
        -DSHELL_UID="$2" >/dev/null
}
run() { b=$1; shift; "$T/$b/hmgapid" "$@" 2>&1; }
code() { b=$1; shift; set +e; "$T/$b/hmgapid" "$@" >/dev/null 2>&1; c=$?; set -e; echo $c; }

build root "$ME" ""
build app 99999 "hmgapi.app $ME 0 /data/data/hmgapi.app default 3003"
build other 99999 "hmgapi.app 12345 0 /data/data/hmgapi.app default 3003"
build mod 99999 "hmgapi.app 12345 0 /data/data/hmgapi.app default 3003
com.example.demo $ME 0 /data/data/com.example.demo default 3003,1015"
mkdir -p "$T/mod/root/state" "$T/mod/root/mods/demo"
printf 'com.example.demo demo\n' > "$T/mod/root/state/apps.txt"

check "client without daemon" "hmgapid: not running" "$(run root ping)"
for b in root app other mod; do
    out=$(KEEPME=yes LD_PRELOAD= "$T/$b/hmgapid" daemon)
    check "$b daemon starts" "hmgapid: started" "$out"
done
check "second daemon refuses" "hmgapid: already running" "$("$T/root/hmgapid" daemon)"

# --- root/shell caller
check "ping" "ok" "$(run root ping)"
check "version" "hmgapid $(sed -n 's/^#define VERSION "\(.*\)"/\1/p' hmgapid.c)" "$(run root version)"
check "sh output and exit code" "hi 3" "$(run root sh 'echo hi; exit 3' | tr '\n' ' ')$(code root sh 'exit 3')"
check "sh stderr streamed" "err" "$(run root sh 'echo err >&2')"
# a background job with its output elsewhere (the boot hook's "(sleep 120; ...) &") must not
# hold the client: only fds 1 and 2 may have the pipe, and those it redirected
t0=$(date +%s)
run root sh "($SLEEP 6) </dev/null >/dev/null 2>&1 &" >/dev/null
check "sh: a background job doesn't hold the client" "yes" "$([ $(( $(date +%s) - t0 )) -lt 4 ] && echo yes || echo no)"
# ... nor one holding a copy of the pipe made after the exec: the unit's mksh keeps fds 1 and 2
# above 9 while it applies ">file 2>&1", and the job inherits them (seen on the US EV6: 1.0.1's
# boot hook still waited two minutes). 9>&1 makes such a copy here (dash takes one digit only).
t0=$(date +%s)
out=$(run root sh "echo before; ($SLEEP 6) 9>&1 </dev/null >/dev/null 2>&1 & echo after")
check "sh: a job holding a copy of the pipe doesn't hold the client either" "yes|before after" "$([ $(( $(date +%s) - t0 )) -lt 4 ] && echo yes || echo no)|$(echo $out)"
check "... and the exit code still comes back" "5" "$(code root sh "($SLEEP 6) 9>&1 </dev/null >/dev/null 2>&1 & exit 5")"
big=$(run root sh 'i=0; while [ $i -lt 20000 ]; do echo 0123456789; i=$((i+1)); done' | wc -c)
check "220 KB of output, not cut" "220000" "$big"
mkdir -p "$T/root/root/mods/demo" "$T/root/root/mods/other"
check "mods list" "demo	enabled other	enabled" "$(run root mods | sort | tr '\n' ' ' | sed 's/ $//')"
check "disable" "disabled (apply to change files; its hooks stop now)" "$(run root disable demo)"
check "disabled file" "yes" "$([ -e "$T/root/root/mods/demo/.disabled" ] && echo yes || echo no)"
has "mods shows disabled" "demo	disabled" "$(run root mods)"
check "enable" "enabled (apply to change files; live mods change now, others at the next start)" "$(run root enable demo)"
sleep 1
has "a switch publishes the live state" "hmgapi.engine.Engine publish-state" "$(cat "$T/root/engine.log" 2>/dev/null)"
check "bad id refused" "2" "$(code root disable ../x)"
check "reserved id refused" "2" "$(code root disable hmgapi)"
check "missing mod" "1" "$(code root disable nope)"
printf '{"id": "demo"}\n' > "$T/root/root/mods/demo/mod.json"
printf '{"settings": []}\n' > "$T/root/root/mods/demo/settings.json"
check "mod-file mod.json" '{"id": "demo"}' "$(run root mod-file demo mod.json)"
check "mod-file settings.json" '{"settings": []}' "$(run root mod-file demo settings.json)"
check "mod-file other names refused" "2" "$(code root mod-file demo patches.json)"
check "mod-file no path tricks" "2" "$(code root mod-file ../demo mod.json)"
check "mod-file missing file" "1" "$(code root mod-file other mod.json)"
# binds (the mount itself needs real root; these check everything around it)
mkdir -p "$T/root/root/state"
check "binds: none" "no binds" "$(run root binds)"
printf '/system/bin/sh /sbin/adbd adbd\n' > "$T/root/root/state/binds.txt"
has "binds: source outside a mod's folder refused" "bind refused" "$(run root binds)"
printf '%s/root/mods/x/files/nope /sbin/adbd adbd\n' "$T/root" > "$T/root/root/state/binds.txt"
rm -f "$T/root/root/state/binds.failed"
has "binds: missing binary" "bind: no $T/root/root/mods/x/files/nope" "$(run root binds)"
check "binds: failure recorded" "$T/root/root/mods/x/files/nope /sbin/adbd adbd" "$(cat "$T/root/root/state/binds.failed")"
has "binds: failed line not tried again" "skip (failed before)" "$(run root binds)"
check "binds: app may not" "13" "$(code app binds)"
rm -f "$T/root/root/state/binds.txt" "$T/root/root/state/binds.failed"
# a client that goes away: sh is stopped, an engine command finishes
rm -f "$T/root/sh.done" "$T/root/engine.done"
"$T/root/hmgapid" sh "$SLEEP 2; echo x; $SLEEP 1; : > $T/root/sh.done" >/dev/null 2>&1 & c1=$!
"$T/root/hmgapid" engine apply --trial >/dev/null 2>&1 & c2=$!
sleep 1; kill $c1 $c2 2>/dev/null || true
sleep 4
check "client gone: sh command stopped" "no" "$([ -e "$T/root/sh.done" ] && echo yes || echo no)"
check "client gone: engine apply still finishes" "yes" "$([ -e "$T/root/engine.done" ] && echo yes || echo no)"
check "setting-set" "saved (live settings change now; apply for file changes)" "$(run root setting-set demo '{"a": 1}')"
check "setting-get" '{"a": 1}' "$(run root setting-get demo)"
check "setting file mode" "-rw-------" "$(ls -l "$T/root/root/settings/demo.json" | cut -c1-10)"
check "settings must be an object" "2" "$(code root setting-set demo 'nope')"
check "hmgapi settings allowed" "0" "$(code root setting-set hmgapi '{}')"
check "unset setting is {}" "{}" "$(run root setting-get other)"
has "log runs logcat" "fake logcat -d -v time -s HMG-API:V" "$(run root log)"
check "unknown op" "2" "$(code root frobnicate)"
check "engine runs app_process" "app_process /system/bin hmgapi.engine.Engine apply --trial CLASSPATH=/system/framework/hmgapi-hook.jar ROOT=/system KEEP=yes PRELOAD=none" "$(run root engine apply --trial)"
check "engine refuses other subcommands" "2" "$(code root engine rm -rf)"
check "root dir mode (traverse only for apps)" "drwx--x--x" "$(ls -ld "$T/root/root" | cut -c1-10)"
has "log file" "(root) disable -> 0" "$(cat "$T/root/root/hmgapid.log")"

# --- the hmgapi.app uid
check "app: mods" "0" "$(code app mods)"
check "app: setting-set" "0" "$(code app setting-set x '{}')"
check "app: sh refused" "13" "$(code app sh 'echo no')"
check "app: remount refused" "13" "$(code app remount rw)"
check "app: reboot allowed" "0" "$(code app reboot)"
check "app: engine allowed" "0" "$(code app engine status)"

# --- anyone else
check "other: ping" "ok" "$(run other ping)"
check "other: mods refused" "13" "$(code other mods)"
check "other: sh refused" "13" "$(code other sh 'echo no')"
check "other: engine refused" "13" "$(code other engine status)"

# --- services (the supervisor runs them as this user: off-car tests aren't root)
S=$T/root/root
mkdir -p "$S/mods/svc/bin"
printf 'echo "started $HMGAPI_MOD/$HMGAPI_SERVICE data=$HMGAPI_DATA android_data=$ANDROID_DATA args=$*"\necho run >> "$HMGAPI_DATA/runs"\nexec '"$SLEEP"' 60\n' > "$S/mods/svc/bin/loop.sh"
printf 'echo crash >> "$HMGAPI_DATA/runs"\nexit 3\n' > "$S/mods/svc/bin/crash.sh"
printf 'echo once\n' > "$S/mods/svc/bin/once.sh"
printf '"$1" whoami > "$HMGAPI_DATA/who"; '"$SH"' -c "\\"$1\\" whoami > \\"$HMGAPI_DATA/who2\\""\n' > "$S/mods/svc/bin/ask.sh"
cat > "$S/state/services.txt" <<EOS
svc loop root boot yes script bin/loop.sh - a b
svc crash root boot yes script bin/crash.sh -
svc once root boot no script bin/once.sh -
svc later root android yes script bin/loop.sh -
svc dexy root boot no jar bin/x.jar com.example.Main --flag
svc bad root boot yes script ../../etc/passwd -
svc ask root boot no script bin/ask.sh - $T/root/hmgapid
hmgapi vehicle root boot no jar /system/framework/hmgapi-hook.jar hmgapi.vehicle.Hub
svc steal root boot no jar /system/framework/hmgapi-hook.jar hmgapi.vehicle.Hub
hmgapi other root boot no jar /system/framework/other.jar x.Main
hmgapi net root boot no script $S/state/net.sh - 1a2b
hmgapi netx root boot no script $S/state/other.sh -
svc netsteal root boot no script $S/state/net.sh -
EOS
printf 'echo "keeper $*"\n' > "$S/state/net.sh"
waitfor() { i=0; while [ $i -lt 40 ]; do if eval "$1"; then return 0; fi; sleep 0.25; i=$((i+1)); done; return 1; }
waitfor '[ -f "$S/run/svc/loop/runs" ]' || true
sleep 2
st() { run root services svc | awk -v n="$1" '$2 == n { print $3 }'; }
check "service started at boot" running "$(st loop)"
has "its log: env and args" "started svc/loop data=$S/run/svc/loop android_data=$S/run/svc/loop args=a b" "$(run root service-log svc loop)"
check "its own folder" "drwx------" "$(ls -ld "$S/run/svc/loop" | cut -c1-10)"
check "one-shot service ran and stays exited" exited "$(st once)"
check "android service waits for Android" waiting "$(st later)"
check "bad path refused" "" "$(st bad)"
has "HMG-API's own service runs from its jar" "app_process /system/bin --nice-name=hmgapi.vehicle hmgapi.vehicle.Hub CLASSPATH=/system/framework/hmgapi-hook.jar" "$(run root service-log hmgapi vehicle)"
check "a mod can't run HMG-API's jar" "" "$(st steal)"
check "HMG-API's services: only its jar" "" "$(run root services hmgapi | awk '$2 == "other"')"
has "HMG-API's network keeper runs from state/net.sh" "keeper 1a2b" "$(run root service-log hmgapi net)"
check "... no other HMG-API script" "" "$(run root services hmgapi | awk '$2 == "netx"')"
check "... and a mod can't run it" "" "$(st netsteal)"
has "refusal logged" "service line refused" "$(cat "$S/hmgapid.log")"
check "log times are wall-clock seconds (time(2) is OABI only; the cars answer -ENOSYS)" yes "$(awk 'NR==1 { print ($1 > 1600000000) ? "yes" : "no" }' "$S/hmgapid.log")"
has "jar service: app_process with its jar and main class" "app_process /system/bin --nice-name=svc.dexy com.example.Main --flag CLASSPATH=$S/mods/svc/bin/x.jar" "$(run root service-log svc dexy)"
waitfor '[ "$(grep -c crash "$S/run/svc/crash/runs" 2>/dev/null)" -ge 3 ]' || true
check "a service is known by its session: whoami" svc "$(cat "$S/run/svc/ask/who" 2>/dev/null)"
check "... and so are processes it starts" svc "$(cat "$S/run/svc/ask/who2" 2>/dev/null)"
check "a plain root caller" root "$(run root whoami)"
check "crashing service restarted with backoff" yes "$([ "$(grep -c crash "$S/run/svc/crash/runs")" -ge 3 ] && echo yes || echo no)"
echo 1 > "$T/root/bootprop"
waitfor '[ "$(st later)" = running ]' || true
check "android service starts once Android is up" running "$(st later)"
pid=$(run root services svc | awk '$2 == "loop" { print $4 }')
check "restart asked" "restart svc/loop: asked" "$(run root service-restart svc loop)"
waitfor '[ "$(grep -c run "$S/run/svc/loop/runs")" -ge 2 ]' || true
check "restarted: runs again with a new pid" yes "$(n=$(run root services svc | awk '$2 == "loop" { print $4 }'); [ "$n" != "$pid" ] && [ "$(st loop)" = running ] && echo yes || echo no)"
run root service-stop svc loop >/dev/null
waitfor '[ "$(st loop)" = stopped ]' || true
check "stopped" stopped "$(st loop)"
check "old process gone" no "$(kill -0 "$pid" 2>/dev/null && echo yes || echo no)"
run root service-start svc loop >/dev/null
waitfor '[ "$(st loop)" = running ]' || true
check "started again" running "$(st loop)"
pid=$(run root services svc | awk '$2 == "loop" { print $4 }')
has "public/services.txt lists running mod service pid" "$pid svc" "$(cat "$S/public/services.txt")"
check "public/services.txt leaves out core hmgapi services" "" "$(grep ' hmgapi$' "$S/public/services.txt" || true)"
grep -v '^svc loop ' "$S/state/services.txt" > "$S/state/s.tmp" && mv "$S/state/s.tmp" "$S/state/services.txt"
waitfor '[ -z "$(st loop)" ]' || true
check "mod turned off (line gone): service stopped and dropped" "" "$(st loop)"
check "public/services.txt drops stopped service" "" "$(grep "^$pid svc$" "$S/public/services.txt" || true)"
check "... its process ended" no "$(kill -0 "$pid" 2>/dev/null && echo yes || echo no)"
check "app may list services" 0 "$(code app services)"
check "other may not" 13 "$(code other services)"
check "bad names refused" 2 "$(code root service-restart ../x loop)"

# --- a mod's own app (its uid is an app an enabled mod declared)
check "mod app: whoami" demo "$(run mod whoami)"
check "mod app: own settings" 0 "$(code mod setting-set '{"x": 2}')"
check "mod app: reads them" '{"x": 2}' "$(run mod setting-get)"
check "mod app: not another mod's" 13 "$(code mod setting-get other)"
check "mod app: no sh" 13 "$(code mod sh 'echo no')"
check "mod app: no engine" 13 "$(code mod engine status)"
check "mod app: no enable" 13 "$(code mod enable demo)"
mkdir -p "$T/mod/usb/storage/usb1/Dashcam" "$T/mod/usb/storage/usb0/Other"
printf 'demo Dashcam\nother Other\n' > "$T/mod/root/state/usb.txt"
check "mod app: its USB folder" "/storage/usb1/Dashcam" "$(run mod usb)"
mkdir -p "$T/root/usb/storage/usb1/Dashcam" "$T/root/usb/storage/usb0/Other"
cp "$T/mod/root/state/usb.txt" "$S/state/usb.txt"
check "root: every mod's" "demo /storage/usb1/Dashcam other /storage/usb0/Other" "$(run root usb | sort | tr '\n' ' ' | sed 's/ $//')"
# --- vehicle readings: relayed to HMG-API's vehicle service, only what the mod declared
check "vehicle: service not running" 69 "$(code root vehicle speed)"
python3 - "hmgapi-vehicle-test-$$" "$T/vehicle.req" <<'PY' &
import socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.bind("\0" + sys.argv[1]); s.listen(4); s.settimeout(30)
held = []
for _ in range(6):
    try: c, _ = s.accept()
    except socket.timeout: break
    line = c.makefile().readline()
    open(sys.argv[2], "a").write(line)
    if line.startswith("do "): c.sendall(("ok %s\n" % line.strip()).encode()); c.close(); continue
    c.sendall("".join("%s 42\n" % n for n in line.split()[1:]).encode())
    if "speed.held" in line: held.append(c); continue   # the car's service: its close never reached hmgapid (#145)
    c.close()
PY
VP=$!
sleep 0.5
printf 'demo speed\ndemo gear.park\nother hv.soc\n' > "$T/mod/root/state/vehicle.txt"
check "vehicle: mod reads what it declared" "speed 42 gear.park 42" "$(run mod vehicle speed gear.park | tr '\n' ' ' | sed 's/ $//')"
check "vehicle: request passed on" "read speed gear.park" "$(head -1 "$T/vehicle.req")"
check "vehicle: not declared refused" 13 "$(code mod vehicle hv.soc)"
check "vehicle: bad name refused" 2 "$(code mod vehicle 'speed;x')"
check "vehicle: root reads any" "hv.soc 42" "$(run root vehicle hv.soc)"
check "vehicle: a whole answer with the connection left open is still ok (#145)" "0 speed.held 42" "$(set +e; o=$(run root vehicle speed.held); echo "$? $o")"
check "vehicle: other app refused" 13 "$(code other vehicle speed)"
printf 'demo charge_door\n' > "$T/mod/root/state/commands.txt"
check "vehicle-do: a declared command passed on with the mod" "ok do demo charge_door open" "$(run mod vehicle-do charge_door open)"
check "vehicle-do: undeclared refused" 13 "$(code mod vehicle-do wheel_heat on)"
check "vehicle-do: root as the owner" "ok do - wheel_heat on" "$(run root vehicle-do wheel_heat on)"
check "vehicle-do: odd arguments refused" 2 "$(code root vehicle-do wheel_heat 'on;reboot')"
check "micom-watch: off by default" off "$(run root micom-watch)"
check "micom-watch: categories by hex id" "88 c2" "$(run root micom-watch 88 0xc2 | sed 's/0x//')"
check "... saved for the vehicle service" "88 0xc2" "$(cat "$T/root/root/state/micom-watch.txt")"
check "micom-watch: odd ids refused" 2 "$(code root micom-watch hev)"
check "micom-watch: telematics never" 13 "$(code root micom-watch 88 c5)"
check "micom-watch: a mod's app can't" 13 "$(code mod micom-watch all)"
check "micom-watch: off" no "$(run root micom-watch off >/dev/null; [ -f "$T/root/root/state/micom-watch.txt" ] && echo yes || echo no)"
mkdir -p "$T/root/root/logs/hmgapi"; echo "123 88 8c1b 0102" > "$T/root/root/logs/hmgapi/micom.log"
check "micom-log: the recording" "123 88 8c1b 0102" "$(run root micom-log)"
check "allow: off by default" off "$(run root allow startup_notice)"
check "allow: the owner turns it on" on "$(run root allow startup_notice on)"
check "... saved for the vehicle service" yes "$([ -f "$T/root/root/settings/allow/startup_notice" ] && echo yes || echo no)"
check "allow: a mod's app can't" 13 "$(code mod allow startup_notice on)"
check "allow: only known switches" 2 "$(code root allow everything on)"
check "allow: block_ambient_light off by default" off "$(run root allow block_ambient_light)"
check "allow: block_ambient_light on" on "$(run root allow block_ambient_light on)"
run root allow block_ambient_light off >/dev/null
check "allow: off again" no "$(run root allow startup_notice off >/dev/null; [ -f "$T/root/root/settings/allow/startup_notice" ] && echo yes || echo no)"
kill $VP 2>/dev/null || true; wait $VP 2>/dev/null || true
check "vehicle: nothing reached the service for refused asks" 5 "$(wc -l < "$T/vehicle.req" | tr -d ' ')"

: > "$T/mod/root/state/apps.txt"
check "mod turned off: its app is anyone again" 13 "$(code mod whoami)"

# --- robustness
python3 - "hmgapid-test-root-$$" <<'PY' && echo "ok    oversized request dropped" || { echo "FAIL  oversized request"; exit 1; }
import socket, struct, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect("\0" + sys.argv[1])
s.sendall(b"LFD1" + struct.pack("<I", 1) + struct.pack("<I", 10_000_000))
s.settimeout(5)
assert s.recv(10) == b""
PY
python3 - "hmgapid-test-root-$$" <<'PY' && echo "ok    bad magic dropped" || { echo "FAIL  bad magic"; exit 1; }
import socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.connect("\0" + sys.argv[1])
s.sendall(b"NEM\x01")
s.settimeout(5)
assert s.recv(10) == b""
PY
check "still answers after bad clients" "ok" "$(run root ping)"

# --- boot: the boot hook's line returns at once (NEMESIS's start comes after it); detached, it
# starts the daemon if none runs and runs boot.sh, once per boot (BOOTMARK): a second trigger
# (NEMESIS's service) stops there. On the car the zygote wrapper has usually started the daemon
# already, so boot.sh must run then too.
build boot "$ME" ""
B=$T/boot
printf 'echo ran >> "%s/ran"
echo "boot output"
%s 3
echo done >> "%s/ran"
' "$B" "$SLEEP" "$B" > "$B/root/boot.sh"
LD_PRELOAD= "$B/hmgapid" daemon >/dev/null   # the zygote wrapper's start
t0=$(date +%s)
out=$("$B/hmgapid" boot 2>&1)   # $(...) waits for every holder of its pipe: the detached child may hold none
check "boot returns at once, saying nothing" "yes|" "$([ $(( $(date +%s) - t0 )) -lt 2 ] && echo yes || echo no)|$out"
waitfor '[ "$(run boot ping)" = ok ]' || true
check "... the daemon runs" "ok" "$(run boot ping)"
waitfor '[ -f "$B/ran" ]' || true
check "... and it ran boot.sh, though the daemon was already up (the zygote started it)" "ran" "$(cat "$B/ran" 2>/dev/null)"
check "... its output in boot.log" "boot output" "$(cat "$B/root/boot.log" 2>/dev/null)"
t0=$(date +%s)
out=$("$B/hmgapid" boot 2>&1)
check "a second boot returns at once too" "yes|" "$([ $(( $(date +%s) - t0 )) -lt 2 ] && echo yes || echo no)|$out"
waitfor 'grep -q done "$B/ran"' || true
check "... and doesn't run boot.sh again" "ran done" "$(cat "$B/ran" | tr '\n' ' ' | sed 's/ $//')"
has "... boot.sh logged in hmgapid.log" "boot: running boot.sh" "$(cat "$B/root/hmgapid.log")"
check "boot takes no arguments" "2" "$(code boot boot extra)"
# no daemon yet (the zygote wrapper didn't start one): boot starts it, and runs boot.sh
build boot2 "$ME" ""
B=$T/boot2
printf 'echo ran >> "%s/ran"\n' "$B" > "$B/root/boot.sh"
"$B/hmgapid" boot >/dev/null 2>&1
waitfor '[ "$(run boot2 ping)" = ok ] && [ -f "$B/ran" ]' || true
check "boot with no daemon: starts it and runs boot.sh" "ok ran" "$(run boot2 ping) $(cat "$B/ran" 2>/dev/null)"
exit $fail

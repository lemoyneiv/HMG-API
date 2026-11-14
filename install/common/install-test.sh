#!/bin/sh
# Off-car test of install.sh / uninstall.sh against a fake head unit: install,
# update, uninstall must leave /system byte-identical; refusals change nothing.
# UNIT_SH: the shell to run them with (busybox ash is closest to the unit's mksh).
# Needs out/payload (./build.sh payload).
set -e
cd "$(dirname "$0")/../.."
SH=${UNIT_SH:-sh}
PL=$PWD/out/payload
[ -f "$PL/MANIFEST" ] || { echo "run ./build.sh payload first"; exit 1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
# stubs for the unit's tools
mkdir -p $T/bin
printf '#!/bin/sh\n[ -f "$1" ] || exit 1\nset -- $(md5sum "$1"); echo "$1 $2"\n' > $T/bin/md5
printf '#!/bin/sh\nexit 0\n' > $T/bin/chown
# Android's tools, for the app's install and open.sh: what they're asked goes to calls.log
printf '#!/bin/sh\necho "${FAKE_BOOT:-0}"\n' > $T/bin/getprop
printf '#!/bin/sh\necho "setprop $*" >> "%s/calls.log"\n' "$T" > $T/bin/setprop
printf '#!/bin/sh\necho "pm $*" >> "%s/calls.log"\ncase "$1" in path) [ -n "$FAKE_HAVE_APP" ] && echo package:/data/app/hmgapi.app-1.apk ;; install) echo "${FAKE_PM:-Success}" ;; esac\nexit 0\n' "$T" > $T/bin/pm
printf '#!/bin/sh\necho "am $*" >> "%s/calls.log"\n' "$T" > $T/bin/am
chmod +x $T/bin/*
calls() { cat $T/calls.log 2>/dev/null || true; : > $T/calls.log; }
run() { set +e; out=$(cd $T && env PATH="$T/bin:$PATH" ROOT=$R CAPCHECK=0 $SH "$@" 2>&1); rc=$?; set -e; }
tree() { (cd $R/system && find . -type f | sort | while read f; do echo "$(md5sum < "$f" | cut -c1-32) $f"; done) }

mkdir -p $R/system/bin $R/system/lib $R/system/framework $R/system/etc $R/data/dalvik-cache $R/dev
# a car's own app_process when tools/cars.py runs this on its stock tree (HMGAPI_CAR, docs/car-trees.md)
STOCK=${HMGAPI_CAR:+$HMGAPI_CAR/bin/app_process}
if [ -f "$STOCK" ]; then cp $STOCK $R/system/bin/app_process; else head -c 9000 /dev/urandom > $R/system/bin/app_process; fi
chmod 755 $R/system/bin/app_process
printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > $R/system/etc/install-recovery.sh; chmod 755 $R/system/etc/install-recovery.sh
printf 'echo qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog\n' > $R/system/etc/qb_runtime.sh
echo stock > $R/system/lib/libc.so
# NEMESIS: uninstall <id> runs the package's preuninstall as native/pkg.c does (cwd the package,
# the caller's environment), then removes it; every call goes to nemesis.log
mkdir -p $R/system/xbin $R/data/nemesis/apps
cat > $R/system/xbin/nemesis <<NEM
#!/bin/sh
echo "nemesis \$* sync=\${HMGAPI_NEMESIS_SYNC:-}" >> $T/nemesis.log
[ "\$1" = uninstall ] || exit 0
A=$R/data/nemesis/apps/\$2
(cd "\$A" && ROOT=$R $SH scripts/preuninstall.sh) || { echo "preuninstall failed: package stays"; exit 5; }
rm -rf "\$A"
NEM
chmod 755 $R/system/xbin/nemesis
BEFORE=$(tree)
# the payload's binaries, with this tree's scripts (so a script edit needs no rebuild)
P=$T/payload; cp -r $PL $P
cp install/common/install.sh install/common/uninstall.sh install/common/lib.sh install/common/stock.txt install/common/app-pending.sh install/common/app-open.sh install/common/hook-check.sh install/common/boot.sh $P/
rm -rf $P/nemesis; mkdir -p $P/nemesis/mod $P/nemesis/bin
cp -r install/nemesis/scripts $P/nemesis/scripts; cp -r install/nemesis/mod/scripts $P/nemesis/mod/scripts
cp install/nemesis/bin/open.sh install/nemesis/bin/start.sh $P/nemesis/bin/; cp install/nemesis/conflicts.txt $P/nemesis/
[ -f "$STOCK" ] || echo "$(md5sum < $R/system/bin/app_process | cut -c1-32) test stock" > $P/stock.txt
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)

# refusals first: nothing may change
cp $R/system/bin/app_process $T/real; echo x >> $R/system/bin/app_process
run $P/install.sh $P
has "unknown app_process refused" "isn't a known stock build, so nothing was changed" "$out"
check "refusal exit code" 1 $rc
check "nothing installed" no "$([ -e $R/system/bin/hmgapid ] && echo yes || echo no)"
cp $T/real $R/system/bin/app_process
echo junk >> $P/lpatch
run $P/install.sh $P
has "damaged payload refused" "payload file lpatch is damaged" "$out"
cp $PL/lpatch $P/lpatch
mv $P/maps $T/maps.away   # unzipped where MAPS.md5 and maps/ were one name (beta 3 and earlier)
run $P/install.sh $P
has "payload without its hook maps refused" "the hook maps folder (payload/maps) is missing" "$out"
has "... before anything changed" "Nothing was changed." "$out"
mv $T/maps.away $P/maps
check "still stock after refusals" "$BEFORE" "$(tree)"

run $P/install.sh $P
has "install finishes" "installed. Restart the head unit" "$out"
check "app_process is the wrapper" "$(md5sum < $P/app_process)" "$(md5sum < $R/system/bin/app_process)"
check "stock app_process kept beside it" "$(md5sum < $T/real)" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "stock app_process backed up" "$(md5sum < $T/real)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
for f in bin/hmgapid bin/lpatch lib/libhmgapi_hook.so framework/hmgapi-hook.jar; do
    check "installed $f" "$(md5sum < $P/${f##*/})" "$(md5sum < $R/system/$f 2>/dev/null)"
done
check "boot hook in install-recovery.sh" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/install-recovery.sh)"
check "boot hook in qb_runtime.sh" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/qb_runtime.sh)"
check "boot hook: only hmgapid boot, which returns at once (NEMESIS's start comes after it)" "if [ -x /system/bin/hmgapid ]; then /system/bin/hmgapid boot; fi" "$(sed -n 3p $R/system/etc/install-recovery.sh)"
check "... and NEMESIS's own lines still follow it" "# NEMESIS|nemesis watchdog" "$(sed -n 5p $R/system/etc/install-recovery.sh)|$(sed -n 6p $R/system/etc/install-recovery.sh)"
check "boot.sh (the boot steps hmgapid boot runs) kept, root only" "-rwx------" "$(ls -l $R/data/hmgapi/boot.sh | cut -c1-10)"
check "this boot marked done, so a boot service NEMESIS starts after the install runs no boot steps (no engine recover mid-install)" yes "$([ -f $R/dev/.hmgapi-boot ] && echo yes)"
check "hooks on at the next start" "yes" "$([ -f $R/data/hmgapi/hook/enable ] && echo yes)"
check "updates off by default" '{"updates": false}' "$(cat $R/data/hmgapi/settings/hmgapi.json)"
check "/data/hmgapi: traverse only for apps" "drwx--x--x" "$(ls -ld $R/data/hmgapi | cut -c1-10)"
check "Android down: app kept for later, readable by the package manager" "-rw-r--r--" "$(ls -l $R/data/hmgapi/public/HmgApi.apk | cut -c1-10)"
check "... and its installer kept" yes "$([ -f $R/data/hmgapi/app-pending.sh ] && echo yes)"
has "boot.sh starts it while the app waits" "app-pending.sh" "$(cat $R/data/hmgapi/boot.sh)"
check "uninstaller kept on the unit" yes "$([ -f $R/data/hmgapi/uninstall.sh ] && echo yes)"
check "a copy of the app kept, readable by the package manager" "-rw-r--r--" "$(ls -l $R/data/hmgapi/HmgApi.apk | cut -c1-10)"
check "open.sh kept" yes "$([ -f $R/data/hmgapi/open.sh ] && echo yes)"
check "hook-check.sh kept, root only" "-rwx------" "$(ls -l $R/data/hmgapi/hook-check.sh | cut -c1-10)"
has "boot.sh: puts a rewritten boot script's block back (hook-check.sh)" "hook-check.sh" "$(cat $R/data/hmgapi/boot.sh)"
check "HMG-API's NEMESIS scripts and conflicts kept, for engine nemesis-sync" yes "$([ -f $R/data/hmgapi/nemesis/scripts/preuninstall.sh ] && [ -f $R/data/hmgapi/nemesis/mod/scripts/preuninstall.sh ] && [ -f $R/data/hmgapi/nemesis/conflicts.txt ] && echo yes)"
has "boot.sh: engine nemesis-sync when NEMESIS is there" "engine nemesis-sync" "$(cat $R/data/hmgapi/boot.sh)"
check "hook maps installed" yes "$([ -f $R/data/hmgapi/maps/kia/ev6/usa/260507/car.json ] && [ -s $R/data/hmgapi/maps/points.txt ] && echo yes)"
has "install record" "file $(md5sum < $P/app_process | cut -c1-32) /system/bin/app_process" "$(cat $R/data/hmgapi/installed.txt)"
echo '{"updates": true}' > $R/data/hmgapi/settings/hmgapi.json

run $P/install.sh $P
has "update over itself" "HMG-API's wrapper is in place" "$out"
check "update keeps the stock copy" "$(md5sum < $T/real)" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "update keeps one boot hook" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/install-recovery.sh)"
check "update keeps the owner's settings" '{"updates": true}' "$(cat $R/data/hmgapi/settings/hmgapi.json)"

# the app's install with Android up (stand-ins for pm and the rest)
rm -f $R/data/hmgapi/public/HmgApi.apk; calls >/dev/null
# export, then unset: VAR=1 run (a function) leaves VAR set after it in bash 3.2's sh (macOS)
export FAKE_BOOT=1 FAKE_PM='Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]' HMGAPI_TEST_ANDROID=1; run $P/install.sh $P; unset FAKE_BOOT FAKE_PM HMGAPI_TEST_ANDROID
has "app install failed: said, with the ways to fix it" "the HMG-API app didn't install: Failure [INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES]. It's tried again at the next restart; or NEMESIS Manager > HMG-API > Launch" "$out"
check "... kept waiting, so the next restart tries again" yes "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes)"
c=$(calls)
has "... pm ran with sys.boot_completed 0" "setprop sys.boot_completed 0
pm install -r $P/HmgApi.apk
setprop sys.boot_completed 1" "$c"
has "... a toast on the screen" "Restart the head unit to try again, or use Launch in NEMESIS Manager" "$c"
export FAKE_BOOT=1 HMGAPI_TEST_ANDROID=1; run $P/install.sh $P; unset FAKE_BOOT HMGAPI_TEST_ANDROID
has "app install works: said" "installed the HMG-API app" "$out"
check "... nothing left waiting" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"

# open.sh: open the app, installing it first if it's missing
calls >/dev/null
export FAKE_BOOT=1 FAKE_HAVE_APP=1; run $R/data/hmgapi/open.sh; unset FAKE_BOOT FAKE_HAVE_APP
check "open.sh: app there, opened (quietly: Manager would toast any output)" "" "$out"
check "... nothing installed" "pm path hmgapi.app
am start -n hmgapi.app/.MainActivity" "$(calls)"
cp $P/HmgApi.apk $R/data/hmgapi/public/HmgApi.apk
export FAKE_BOOT=1; run $R/data/hmgapi/open.sh; unset FAKE_BOOT
check "open.sh: app missing, installed from the kept copy, then opened" "HMG-API: installed the app" "$out"
has "... pm on the kept copy, then am start" "pm install -r $R/data/hmgapi/HmgApi.apk
setprop sys.boot_completed 1
am start -n hmgapi.app/.MainActivity" "$(calls)"
check "... nothing left waiting" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"
export FAKE_BOOT=1 FAKE_PM='Failure [X]'; run $R/data/hmgapi/open.sh; unset FAKE_BOOT FAKE_PM
check "open.sh: install fails, says why" "1 HMG-API: the app didn't install: Failure [X]" "$rc $out"
check "... once (Manager toasts the output; no toast of its own)" "" "$(calls | grep TOAST || true)"
run $R/data/hmgapi/open.sh
check "open.sh: Android not up" "1 HMG-API: Android isn't up yet: try again in a minute" "$rc $out"
mv $R/data/hmgapi/HmgApi.apk $T/apk.away
export FAKE_BOOT=1; run $R/data/hmgapi/open.sh; unset FAKE_BOOT
has "open.sh: no copy of the app" "there's no copy to install: install HMG-API again" "$out"
mv $T/apk.away $R/data/hmgapi/HmgApi.apk

# listed in NEMESIS: HMG-API and a mod, the mod's package with an older script copy that would uninstall
for id in hmgapi hmgapi.demo; do
    mkdir -p $R/data/nemesis/apps/$id/scripts $R/data/nemesis/apps/$id/mod
    printf '[nemesis]\nid=%s\npackage-version=1.0.0\n' $id > $R/data/nemesis/apps/$id/NEMESIS.data
done
echo demo > $R/data/nemesis/apps/hmgapi.demo/mod/id
cp install/nemesis/scripts/*.sh $R/data/nemesis/apps/hmgapi/scripts/
printf '#!/system/bin/sh\necho "old script: would remove the mod"\nexit 1\n' > $R/data/nemesis/apps/hmgapi.demo/scripts/preuninstall.sh
: > $T/nemesis.log
run $R/data/hmgapi/uninstall.sh
has "uninstall finishes" "HMG-API uninstalled" "$out"
check "... NEMESIS: the mod's package unlisted first, then HMG-API's, list-only" "nemesis uninstall hmgapi.demo sync=1|nemesis uninstall hmgapi sync=1" "$(tr '\n' '|' < $T/nemesis.log | sed 's/|$//')"
check "... neither listed any more (the old script copy refreshed first)" "" "$(ls $R/data/nemesis/apps)"
has "... said" "NEMESIS: hmgapi unlisted" "$out"
check "the app's copy, open.sh, hook-check.sh and boot.sh gone" no "$([ -e $R/data/hmgapi/HmgApi.apk ] || [ -e $R/data/hmgapi/open.sh ] || [ -e $R/data/hmgapi/hook-check.sh ] || [ -e $R/data/hmgapi/boot.sh ] && echo yes || echo no)"
check "/system byte-identical to stock" "$BEFORE" "$(tree)"
check "settings kept without --purge" yes "$([ -f $R/data/hmgapi/settings/hmgapi.json ] && echo yes)"
check "hooks off" no "$([ -f $R/data/hmgapi/hook/enable ] && echo yes || echo no)"
check "waiting app removed" no "$([ -f $R/data/hmgapi/public/HmgApi.apk ] && echo yes || echo no)"

run $P/install.sh $P
mkdir -p $R/data/nemesis/apps/hmgapi/scripts; printf '[nemesis]\nid=hmgapi\n' > $R/data/nemesis/apps/hmgapi/NEMESIS.data; : > $T/nemesis.log
export HMGAPI_FROM_NEMESIS=1; run $R/data/hmgapi/uninstall.sh; unset HMGAPI_FROM_NEMESIS
check "uninstall run by NEMESIS: NEMESIS isn't called (it's unlisting it already)" "" "$(cat $T/nemesis.log)"
rm -rf $R/data/nemesis/apps/hmgapi
run $P/install.sh $P
run $R/data/hmgapi/uninstall.sh --purge
check "reinstall + purge: /system stock" "$BEFORE" "$(tree)"
check "purge removes /data/hmgapi" no "$([ -e $R/data/hmgapi ] && echo yes || echo no)"
# ---- a firmware update after the install: /system back to stock (a new app_process, the boot
# scripts without HMG-API's block, HMG-API's files gone), /data/hmgapi as it was
printf '#!/bin/sh\necho "engine $*" >> "%s/calls.log"\necho "firmware update: 1 file(s) are the new firmware'"'"'s stock now"\n' "$T" > $T/bin/engine; chmod +x $T/bin/engine
firmware_update() {
    rm -f $R/system/bin/hmgapid $R/system/bin/lpatch $R/system/lib/libhmgapi_hook.so $R/system/framework/hmgapi-hook.jar $R/system/bin/app_process.hmgapi-stock
    cp $T/newfw $R/system/bin/app_process
    printf '#!/system/bin/sh\n# NEMESIS\nnemesis watchdog\nexit 0\n' > $R/system/etc/install-recovery.sh
    printf 'echo qb stock\n# NEMESIS_QB_HOOK\nnemesis watchdog\n' > $R/system/etc/qb_runtime.sh
}
head -c 9000 /dev/urandom > $T/newfw; chmod 755 $T/newfw
echo "$(md5sum < $T/newfw | cut -c1-32) test new firmware" >> $P/stock.txt
(cd $P && md5sum hmgapid lpatch app_process libhmgapi_hook.so hmgapi-hook.jar HmgApi.apk install.sh uninstall.sh lib.sh stock.txt app-pending.sh app-open.sh hook-check.sh boot.sh MAPS.md5 > MANIFEST)
export HMGAPI_TEST_ENGINE=$T/bin/engine
run $P/install.sh $P
mkdir -p $R/data/hmgapi/state; echo '{"files": {}, "settings": {}}' > $R/data/hmgapi/state/files.json
firmware_update; NEWFW=$(tree); calls >/dev/null

# (a) installed again over it
run $P/install.sh $P
has "firmware update, then install: found" "a firmware update replaced /system since HMG-API was installed" "$out"
check "... the engine drops its old-firmware records first" "engine firmware-reset" "$(calls | grep '^engine firmware' || true)"
has "... said" "file(s) are the new firmware's stock now" "$out"
has "... installs" "installed. Restart the head unit" "$out"
check "... the new firmware's app_process is the backup now" "$(md5sum < $T/newfw)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
check "... and kept beside the wrapper" "$(md5sum < $T/newfw)" "$(md5sum < $R/system/bin/app_process.hmgapi-stock)"
check "... one boot hook" 1 "$(grep -c 'HMGAPI_HOOK begin' $R/system/etc/install-recovery.sh)"
run $P/install.sh $P
check "an update after that isn't a firmware reset" "" "$(calls | grep '^engine firmware' || true)"
run $R/data/hmgapi/uninstall.sh
has "... uninstall finishes" "HMG-API uninstalled" "$out"
check "... /system is the new firmware's stock" "$NEWFW" "$(tree)"

# (b) uninstalled after it (NEMESIS Manager's Uninstall; hmgapid and the engine are gone)
run $P/install.sh $P
cp $T/real $R/data/hmgapi/stock/system/bin/app_process   # the old firmware's backup, as an install before the update took it
firmware_update; calls >/dev/null
mkdir -p $R/data/nemesis/apps/hmgapi/scripts; printf '[nemesis]\nid=hmgapi\n' > $R/data/nemesis/apps/hmgapi/NEMESIS.data
cp install/nemesis/scripts/*.sh $R/data/nemesis/apps/hmgapi/scripts/
set +e; out=$(cd $R/data/nemesis/apps/hmgapi && env PATH="$T/bin:$PATH" ROOT=$R CAPCHECK=0 $R/system/xbin/nemesis uninstall hmgapi 2>&1); rc=$?; set -e
check "firmware update, then NEMESIS uninstall: works" 0 $rc
has "... found the update" "a firmware update replaced /system since HMG-API was installed" "$out"
has "... says what stays" "Settings mods changed stay as they are" "$out"
check "... unlisted" no "$([ -d $R/data/nemesis/apps/hmgapi ] && echo yes || echo no)"
check "... the old firmware's app_process never put back: /system is the new firmware's stock" "$NEWFW" "$(tree)"
check "... marked for the next install" yes "$([ -f $R/data/hmgapi/firmware-reset ] && echo yes)"
run $P/install.sh $P
has "... install after that: starts from the new firmware" "a firmware update replaced /system since HMG-API was installed" "$out"
check "... engine firmware-reset" "engine firmware-reset" "$(calls | grep '^engine firmware' || true)"
check "... new backup" "$(md5sum < $T/newfw)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
check "... mark gone" no "$([ -f $R/data/hmgapi/firmware-reset ] && echo yes || echo no)"
run $R/data/hmgapi/uninstall.sh --purge
check "... uninstall: /system is the new firmware's stock" "$NEWFW" "$(tree)"

# (c) a firmware update after an uninstall (only the backup tells): reinstall takes a new one
run $P/install.sh $P
run $R/data/hmgapi/uninstall.sh
cp $T/real $R/data/hmgapi/stock/system/bin/app_process
cp $T/real $R/system/bin/app_process
calls >/dev/null
run $P/install.sh $P
check "uninstalled, same firmware: no reset" "" "$(calls | grep '^engine firmware' || true)"
run $R/data/hmgapi/uninstall.sh
cp $T/real $R/data/hmgapi/stock/system/bin/app_process; cp $T/newfw $R/system/bin/app_process
run $P/install.sh $P
check "uninstalled, then a firmware update: the stale backup found, reset" "engine firmware-reset" "$(calls | grep '^engine firmware' || true)"
check "... new backup" "$(md5sum < $T/newfw)" "$(md5sum < $R/data/hmgapi/stock/system/bin/app_process)"
unset HMGAPI_TEST_ENGINE
exit $fail

#!/system/bin/sh
# HMG-API: install or update on the head unit, from an unpacked payload folder.
# Every way in (adb script, NEMESIS package, USB stick) ends up here, as full root:
#   sh install.sh <payload dir>
# Puts hmgapid, lpatch, the zygote wrapper, the hook loader and runtime in /system,
# adds the boot hook, installs the HMG-API app, and turns the hooks on at the next
# restart. Every /system file it replaces is backed up once (stock bytes, md5
# checked) into /data/hmgapi/stock; uninstall.sh puts them back.
# HMGAPI_FORCE=1: go on when app_process isn't a known stock build (own risk).
# ROOT prefixes every path, for off-car tests only.
umask 077
P=${1:-${0%/*}}
ROOT=${ROOT:-}
. "$P/lib.sh"
D=$ROOT/data/hmgapi
LOG=$D/install.log
VERSION=$(cat "$P/VERSION" 2>/dev/null)

mkdir -p "$D" 2>/dev/null

capcheck() {
    [ "${CAPCHECK:-1}" = 1 ] || return 0
    local k v
    while read k v; do [ "$k" = "CapEff:" ] && break; done < /proc/self/status
    v=${v#????????}
    [ $((0x$v & 0x200003)) = $((0x200003)) ] && return 0
    echo "FAIL: not full root (CapEff ${v}). From adb, use install/adb/hmgapi-adb.sh, or run it"
    echo "through the NEMESIS daemon (a non-SUID copy of nemesis) or hmgapid."
    exit 1
}

# remount rw|ro: NEMESIS if it's there, else a running hmgapid, else toolbox
# mount (refused on these units, even as root, except in the DSP OTA's process,
# where the USB installer runs)
remount() {
    [ -n "$ROOT" ] && return 0
    if [ -x /system/xbin/nemesis ] && nemesis remount "$1" >/dev/null 2>&1; then return 0; fi
    "$P/hmgapid" remount "$1" >/dev/null 2>&1 && return 0
    mount -o remount,"$1" /system >/dev/null 2>&1
}

# put <payload file> <path> <mode> <group>: copy, md5 check, rename into place
put() {
    local src=$P/$1 dst=$ROOT$2 want
    want=$(sum "$src")
    [ "$(sum "$dst")" = "$want" ] && return 0
    cp "$src" "$dst.hmgapi-new" || fail "copy $1"
    chmod "$3" "$dst.hmgapi-new"; chown root:"$4" "$dst.hmgapi-new" 2>/dev/null
    [ "$(sum "$dst.hmgapi-new")" = "$want" ] || { rm -f "$dst.hmgapi-new"; fail "md5 mismatch after copying $1"; }
    mv "$dst.hmgapi-new" "$dst" || { rm -f "$dst.hmgapi-new"; fail "rename onto $2"; }
    log "installed $2"
}

# backup <path>: the stock file, once (a later install never overwrites it)
backup() {
    local src=$ROOT$1 bak=$D/stock$1
    [ -f "$bak" ] && return 0
    mkdir -p "${bak%/*}" || fail "backup folder for $1"
    if [ -f "$src" ]; then
        cp "$src" "$bak" || fail "backup $1"
        [ "$(sum "$bak")" = "$(sum "$src")" ] || { rm -f "$bak"; fail "backup md5 mismatch for $1"; }
    else
        : > "$bak.absent"
        : > "$bak"
    fi
}

capcheck
log "HMG-API $VERSION install $(date)"

# the payload is whole
while read m f; do
    [ -n "$f" ] || continue
    [ "$(sum "$P/$f")" = "$m" ] || fail "payload file $f is damaged or missing (copy it again)"
done < "$P/MANIFEST"
# ... the hook maps too (MAPS.md5, in the MANIFEST, lists them), before anything changes
if [ -s "$P/MAPS.md5" ]; then
    [ -d "$P/maps" ] || fail "the hook maps folder (payload/maps) is missing: unzip the download again, onto the stick itself (see the README). Nothing was changed."
    while read m f; do
        [ "$(sum "$P/$f")" = "$m" ] || fail "hook map file $f is damaged or missing (copy the payload again). Nothing was changed."
    done < "$P/MAPS.md5"
fi

# a dev build from before the rename (Leafia) has its own wrapper in place: it uninstalls itself first
if [ -f "$ROOT/system/bin/app_process.leafia-stock" ]; then
    fail "an earlier dev build (Leafia) is installed. Uninstall it first, as root: sh /data/leafia/uninstall.sh (then restart). Nothing was changed."
fi

# app_process: stock (a known build), or already our wrapper
B=$ROOT/system/bin/app_process
KEEP=$ROOT/system/bin/app_process.hmgapi-stock
WRAP=$(sum "$P/app_process")
HAVE=$(sum "$B")
known=
while read m label; do [ "$m" = "$HAVE" ] && known=$label; done < "$P/stock.txt"
if [ "$HAVE" = "$WRAP" ] || { [ -f "$KEEP" ] && [ -f "$D/stock/system/bin/app_process" ]; }; then
    log "app_process: HMG-API's wrapper is in place (stock kept as app_process.hmgapi-stock)"
    UPDATE=1
elif [ -n "$known" ]; then
    log "app_process: stock ($known)"
elif [ "$HMGAPI_FORCE" = 1 ]; then
    log "WARNING: app_process md5 $HAVE isn't a known stock build; HMGAPI_FORCE=1, going on at your own risk"
else
    fail "app_process md5 $HAVE isn't a known stock build, so nothing was changed. Please send this line to the HMG-API maintainers. To install anyway, at your own risk: an empty file HMGAPI/FORCE on the USB stick, or HMGAPI_FORCE=1 over adb."
fi

# a firmware update since HMG-API was installed put /system back to stock: the wrapper and its
# stock copy are gone, but /data/hmgapi still says installed (or an uninstall afterwards left
# the firmware-reset mark, or the app_process backup isn't this stock). The backups and records
# hold the old firmware's bytes, which must never go back: they're dropped (the engine's own:
# engine firmware-reset, from the payload's jar) and this install starts from the files as they are.
BAK=$D/stock/system/bin/app_process
if [ -z "$UPDATE" ] && { [ -f "$D/installed.txt" ] || [ -f "$D/firmware-reset" ] || { [ -s "$BAK" ] && [ "$(sum "$BAK")" != "$HAVE" ]; }; }; then
    log "a firmware update replaced /system since HMG-API was installed: starting again from the new firmware's files"
    r=
    if [ -z "$ROOT" ]; then
        r=$(CLASSPATH=$P/hmgapi-hook.jar ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/app_process /system/bin hmgapi.engine.Engine firmware-reset 2>&1) \
            || fail "engine firmware-reset: $r. Nothing on /system was changed."
    elif [ -n "$HMGAPI_TEST_ENGINE" ]; then
        r=$($HMGAPI_TEST_ENGINE firmware-reset 2>&1) || fail "engine firmware-reset: $r. Nothing on /system was changed."
    fi
    [ -n "$r" ] && log "$r"
    for f in /system/bin/app_process /system/etc/install-recovery.sh /system/etc/qb_runtime.sh; do
        rm -f "$D/stock$f" "$D/stock$f.absent"
    done
    rm -f "$D/firmware-reset"
    log "the old firmware's backups of app_process and the boot scripts dropped; new ones are taken now"
fi

# this boot's boot steps are done: an install does what they would (the app, the NEMESIS
# sync). NEMESIS starts HMG-API's boot service after an install, and after each listing
# the sync makes; on a unit that started with an older hmgapid there's no marker yet, and
# boot.sh's engine recover would then undo a waiting trial in the middle of this install
echo > "$ROOT/dev/.hmgapi-boot" 2>/dev/null || true   # not ":": a failed redirect on a special builtin ends the script

# /data/hmgapi: root only (NEMESIS runs commands with umask 0)
for d in "" /stock /state /mods /settings /hook /inbox /dalvik-cache /public; do
    mkdir -p "$D$d" && chmod 700 "$D$d" || fail "make $D$d"
done
# apps may traverse (not list) /data/hmgapi and mods, and read public/ (live state)
chmod 711 "$D" "$D/mods"; chmod 755 "$D/public"
[ -f "$D/settings/hmgapi.json" ] || echo '{"updates": false}' > "$D/settings/hmgapi.json"
chmod 600 "$D/settings/hmgapi.json"

backup /system/bin/app_process
backup /system/etc/install-recovery.sh
backup /system/etc/qb_runtime.sh

remount rw || fail "can't remount /system rw (needs NEMESIS or a running hmgapid)"
REMOUNTED=1
put hmgapid /system/bin/hmgapid 755 shell
put lpatch /system/bin/lpatch 755 shell
put libhmgapi_hook.so /system/lib/libhmgapi_hook.so 644 root
put hmgapi-hook.jar /system/framework/hmgapi-hook.jar 644 root
rm -f "$ROOT/data/dalvik-cache/system@framework@hmgapi-hook.jar@classes.dex"
if [ -z "$UPDATE" ]; then
    cp "$B" "$KEEP.hmgapi-new" || fail "copy app_process"
    chmod 755 "$KEEP.hmgapi-new"; chown root:shell "$KEEP.hmgapi-new" 2>/dev/null
    [ "$(sum "$KEEP.hmgapi-new")" = "$HAVE" ] || { rm -f "$KEEP.hmgapi-new"; fail "app_process copy md5 mismatch"; }
    mv "$KEEP.hmgapi-new" "$KEEP" || fail "keep stock app_process"
fi
put app_process /system/bin/app_process 755 shell
hook_add /system/etc/install-recovery.sh top || fail "boot hook in install-recovery.sh"
hook_add /system/etc/qb_runtime.sh end || fail "boot hook in qb_runtime.sh"
sync
remount ro || log "note: /system stays rw until the next restart (it's busy; NEMESIS also mounts it rw at boot)"
REMOUNTED=

# hooks on at the next restart (the zygote wrapper's boot guard counts from 0)
echo 0 > "$D/hook/starts"
rm -f "$D/hook/disable"
: > "$D/hook/enable"

# what's installed, for uninstall.sh and the app
{
    echo "version $VERSION"
    echo "build $(cat "$P/BUILD" 2>/dev/null)"
    echo "date $(date)"
    for f in /system/bin/hmgapid /system/bin/lpatch /system/bin/app_process /system/lib/libhmgapi_hook.so /system/framework/hmgapi-hook.jar; do
        echo "file $(sum "$ROOT$f") $f"
    done
} > "$D/installed.txt"
cp "$P/uninstall.sh" "$P/lib.sh" "$P/stock.txt" "$P/app-pending.sh" "$P/hook-check.sh" "$P/boot.sh" "$D/" && chmod 700 "$D/uninstall.sh" "$D/app-pending.sh" "$D/hook-check.sh" "$D/boot.sh"
# open.sh (NEMESIS Manager's Launch, adb) installs the app from this copy if it's missing, then opens it
cp "$P/app-open.sh" "$D/open.sh" && chmod 700 "$D/open.sh" || fail "keep open.sh"
cp "$P/HmgApi.apk" "$D/HmgApi.apk" && chmod 644 "$D/HmgApi.apk" || fail "keep a copy of the app"
# HMG-API's NEMESIS scripts and incompatible packages: engine nemesis-sync lists HMG-API and its mods in NEMESIS with them
if [ -d "$P/nemesis" ]; then
    rm -rf "$D/nemesis" && cp -r "$P/nemesis" "$D/nemesis" && chmod -R 700 "$D/nemesis" || fail "keep the NEMESIS scripts"
fi

# the hook maps (HMG-API runs only on a car with a good one): replaced as a whole
if [ -d "$P/maps" ]; then
    rm -r "$D/maps.new" 2>/dev/null
    cp -r "$P/maps" "$D/maps.new" || fail "copy the hook maps"
    while read m f; do
        [ "$(sum "$D/maps.new/${f#maps/}")" = "$m" ] || fail "hook map file $f is damaged (copy the payload again)"
    done < "$P/MAPS.md5"
    rm -r "$D/maps" 2>/dev/null
    mv "$D/maps.new" "$D/maps" || fail "put the hook maps in place"
    chmod -R 700 "$D/maps" 2>/dev/null
    log "hook maps: $(grep -c car.json "$P/MAPS.md5") car(s)"
fi

# start hmgapid now if none runs. A running one (maybe an older build) is left
# alone: this script may be its child (hmgapid sh), and the restart swaps it.
if [ -z "$ROOT" ] && ! /system/bin/hmgapid ping >/dev/null 2>&1; then
    /system/bin/hmgapid daemon >/dev/null 2>&1
elif [ -z "$ROOT" ]; then
    # the running vehicle service still has the old jar loaded
    /system/bin/hmgapid service-restart hmgapi vehicle >/dev/null 2>&1 && log "restarted the vehicle service"
fi

# the app: pm only takes our signature while sys.boot_completed is 0 (OEM
# allowlist), so set it for the pm call only. Android down: app-pending.sh puts it
# in once Android is up (started now, and by the boot hook until it's in).
# Failed with Android up: kept waiting like that, so the next restart tries again (the boot
# hook), and NEMESIS Manager's Launch (or open.sh over adb) tries at once.
# HMGAPI_TEST_ANDROID: off-car tests take this branch with stand-ins for pm, getprop and am.
if { [ -z "$ROOT" ] || [ "$HMGAPI_TEST_ANDROID" = 1 ]; } && [ "$(getprop sys.boot_completed)" = 1 ]; then
    setprop sys.boot_completed 0
    r=$(pm install -r "$P/HmgApi.apk" 2>&1)
    setprop sys.boot_completed 1
    case "$r" in
        *Success*) rm -f "$D/public/HmgApi.apk"; log "installed the HMG-API app" ;;
        *)
            cp "$P/HmgApi.apk" "$D/public/HmgApi.apk" && chmod 644 "$D/public/HmgApi.apk"
            log "WARNING: the HMG-API app didn't install: $r. It's tried again at the next restart; or NEMESIS Manager > HMG-API > Launch, or over adb: hmgapid sh 'sh /data/hmgapi/open.sh'"
            t="HMG-API: the app didn't install. Restart the head unit to try again, or use Launch in NEMESIS Manager."
            am broadcast -a com.hkmc.intent.action.TOAST -e com.hkmc.intent.extra.TOAST_MESSAGE "$t" --ez com.hkmc.intent.extra.TOAST_POPUPSHOWING true >/dev/null 2>&1 ;;
    esac
else
    cp "$P/HmgApi.apk" "$D/public/HmgApi.apk" && chmod 644 "$D/public/HmgApi.apk" || fail "keep the app for later"
    [ -n "$ROOT" ] || /system/bin/hmgapid sh "sh /data/hmgapi/app-pending.sh </dev/null >/dev/null 2>&1 &" >/dev/null 2>&1
    log "Android isn't up: the HMG-API app installs by itself once it is"
fi
# NEMESIS's package list: HMG-API and its mods listed there too (not when NEMESIS runs this: it's listing it already)
if [ "$HMGAPI_FROM_NEMESIS" != 1 ] && [ -x "$ROOT/system/xbin/nemesis" ]; then
    if [ -z "$ROOT" ]; then
        r=$(CLASSPATH=/system/framework/hmgapi-hook.jar ANDROID_ROOT=/system ANDROID_DATA=/data /system/bin/app_process /system/bin hmgapi.engine.Engine nemesis-sync 2>&1)
        log "$r"
    elif [ -n "$HMGAPI_TEST_ENGINE" ]; then
        r=$($HMGAPI_TEST_ENGINE nemesis-sync 2>&1)
        log "$r"
    fi
fi
log "HMG-API $VERSION installed. Restart the head unit to turn it on."

#!/system/bin/sh
# HMG-API: uninstall from the head unit, as full root:
#   sh /data/hmgapi/uninstall.sh [--purge]
# First every file and setting mods changed goes back to stock (engine restore),
# then HMG-API's own files: app_process and the boot scripts get their stock
# bytes back (md5 checked), HMG-API's files in /system are removed, and the app
# is uninstalled. /data/hmgapi (mods, settings, stock backups) stays unless
# --purge. Takes full effect at the next restart.
# HMGAPI_FORCE=1: restore a backup whose md5 isn't a known stock build (own risk).
# ROOT prefixes every path, for off-car tests only.
umask 077
H=${0%/*}
ROOT=${ROOT:-}
. "$H/lib.sh"
D=$ROOT/data/hmgapi
LOG=$D/install.log
remount() {
    [ -n "$ROOT" ] && return 0
    if [ -x /system/xbin/nemesis ] && nemesis remount "$1" >/dev/null 2>&1; then return 0; fi
    $ROOT/system/bin/hmgapid remount "$1" >/dev/null 2>&1 && return 0
    mount -o remount,"$1" /system >/dev/null 2>&1   # works in the DSP OTA's process (USB uninstall)
}
capcheck() {
    [ "${CAPCHECK:-1}" = 1 ] || return 0
    local k v
    while read k v; do [ "$k" = "CapEff:" ] && break; done < /proc/self/status
    v=${v#????????}
    [ $((0x$v & 0x200003)) = $((0x200003)) ] && return 0
    echo "FAIL: not full root (CapEff ${v}). Use install/adb/hmgapi-adb.sh uninstall, or hmgapid sh."
    exit 1
}
capcheck
log "HMG-API uninstall $(date)"

B=$ROOT/system/bin/app_process
KEEP=$ROOT/system/bin/app_process.hmgapi-stock
BAK=$D/stock/system/bin/app_process
# a firmware update since the install put /system back to stock: HMG-API's wrapper and its stock
# copy are gone (and hmgapid and the engine with them). app_process is the new firmware's own then,
# and the backups hold the old firmware's bytes: they never go back. Mods' file changes went with
# the update; what's left is /data, settings and the NEMESIS listings.
WRAPPED=
[ -f "$D/installed.txt" ] && while read k m f; do [ "$k $f" = "file /system/bin/app_process" ] && WRAPPED=$m; done < "$D/installed.txt"
RESET=
LIVE=$(sum "$B")
if [ ! -f "$KEEP" ] && [ -n "$WRAPPED" ] && [ "$LIVE" != "$WRAPPED" ]; then
    RESET=1
    log "a firmware update replaced /system since HMG-API was installed: app_process is the new firmware's ($LIVE) and stays, and the old firmware's backups aren't put back"
else
    [ -f "$BAK" ] || fail "no stock app_process backup in $D/stock; nothing changed"
    ORIG=$(sum "$BAK")
    known=
    while read m label; do [ "$m" = "$ORIG" ] && known=$label; done < "$H/stock.txt"
    if [ -z "$known" ] && [ "$HMGAPI_FORCE" != 1 ]; then
        fail "the app_process backup (md5 $ORIG) isn't a known stock build, so nothing was changed. To restore it anyway, at your own risk, set HMGAPI_FORCE=1."
    fi
fi

# 1. mods: every changed file and setting back to stock, while the engine is still here
if [ -n "$RESET" ]; then
    # the next install drops the old firmware's records and backups (install.sh, engine firmware-reset)
    : > "$D/firmware-reset"
    [ -f "$D/state/files.json" ] && log "mods' file changes went with the firmware update. Settings mods changed stay as they are: to put them back too, install HMG-API again, then uninstall it"
elif [ -z "$ROOT" ] && [ -f "$D/state/files.json" ]; then
    /system/bin/hmgapid ping >/dev/null 2>&1 || /system/bin/hmgapid daemon >/dev/null 2>&1   # the engine runs through it
    [ -f "$D/state/journal.json" ] && /system/bin/hmgapid engine recover
    if ! r=$(/system/bin/hmgapid engine restore 2>&1); then
        echo "$r"
        fail "engine restore didn't finish, so HMG-API stays installed (mods' files must go back first). See the lines above."
    fi
    log "mods' changes back to stock"
fi

# 2. star keys and OTA updates back to the owner's stock settings (needs Android up)
if [ -z "$ROOT" ] && [ "$(getprop sys.boot_completed)" = 1 ]; then
    case "$(getprop persist.sys.key.swrccustom)" in
        hmgapi.star.*) setprop persist.sys.key.swrccustom com.mobis.intent.action.SWRC_CUSTOM_SHORTCUT; log "wheel star key: set to None" ;;
    esac
    case "$(getprop persist.sys.key.custom)" in
        hmgapi.star.*) service call com.hkmc.micom.mode 37 s16 com.mobis.intent.action.CUSTOM_SHORTCUT >/dev/null 2>&1 && log "head unit star key: set to None" ;;
    esac
    if [ "$(getprop persist.sys.hmgapi.otaoff)" = 1 ]; then pm enable com.hkmc.system.app.OTAClient >/dev/null && setprop persist.sys.hmgapi.otaoff ""; fi
    p=$(getprop persist.sys.hmgapi.autoupd)
    [ -n "$p" ] && settings put system gen_auto_update "$p" && setprop persist.sys.hmgapi.autoupd ""
    # the side panel's page lists: HMG-API's page (slot 22) out
    for k in ss_list ss_list_dr1 ss_list_dr2 ss_setting ss_setting_dr1 ss_setting_dr2; do
        v=$(settings get system $k)
        case ",$v," in *,22,*) ;; *) continue ;; esac
        n=""; o=$IFS; IFS=,
        for i in $v; do [ "$i" = 22 ] || n="${n:+$n,}$i"; done
        IFS=$o
        settings put system $k "$n" && log "side panel: HMG-API's page out of $k"
    done
fi

# mods' services stop (hmgapid keeps running until the restart; an empty list stops them)
if [ -f "$D/state/services.txt" ]; then
    : > "$D/state/services.txt"
    [ -n "$ROOT" ] || sleep 7
    log "mods' services stopped"
fi
# mods' network rules, routes and settings out (the keeper does it when stopped; again in case it couldn't)
if [ -z "$ROOT" ] && [ -f "$D/state/net.sh" ]; then
    sh "$D/state/net.sh" down >/dev/null 2>&1 && log "mods' network rules out"
fi

# 3. hooks off at the next start even if a step below fails
: > "$D/hook/disable" 2>/dev/null

# 4. /system back
remount rw || fail "can't remount /system rw"
REMOUNTED=1
if [ -n "$RESET" ]; then
    log "app_process: the new firmware's, left as it is"
else
    if [ "$(sum "$B")" != "$ORIG" ]; then
        cp "$BAK" "$B.hmgapi-new" || fail "copy the app_process backup"
        chmod 755 "$B.hmgapi-new"; chown root:shell "$B.hmgapi-new" 2>/dev/null
        [ "$(sum "$B.hmgapi-new")" = "$ORIG" ] || { rm -f "$B.hmgapi-new"; fail "app_process md5 mismatch after copy"; }
        mv "$B.hmgapi-new" "$B" || { rm -f "$B.hmgapi-new"; fail "rename onto app_process"; }
    fi
    log "app_process: stock again ($ORIG)"
fi
rm -f "$KEEP"
hook_remove /system/etc/install-recovery.sh || fail "boot hook in install-recovery.sh"
hook_remove /system/etc/qb_runtime.sh || fail "boot hook in qb_runtime.sh"
for f in /system/lib/libhmgapi_hook.so /system/framework/hmgapi-hook.jar /system/bin/lpatch /system/bin/hmgapid; do
    rm -f "$ROOT$f"
done
rm -f "$ROOT/data/dalvik-cache/system@framework@hmgapi-hook.jar@classes.dex"
sync
remount ro || log "note: /system stays rw until the next restart"
REMOUNTED=
for f in /system/lib/libhmgapi_hook.so /system/framework/hmgapi-hook.jar /system/bin/lpatch /system/bin/hmgapid /system/bin/app_process.hmgapi-stock; do
    [ ! -e "$ROOT$f" ] || fail "$f is still there"
done
[ -n "$RESET" ] || [ "$(sum "$B")" = "$ORIG" ] || fail "app_process isn't stock after the restore"
log "/system: HMG-API's files removed, app_process and boot scripts stock"

# 5. the app
if [ -z "$ROOT" ]; then
    pm uninstall hmgapi.app >/dev/null 2>&1 && log "removed the HMG-API app"
    # the mods' own apps: off (data kept, they come back with a reinstall), or gone with --purge
    for a in $(cat "$D/state/apps.installed" 2>/dev/null); do
        if [ "$1" = --purge ]; then pm uninstall "$a" >/dev/null 2>&1 && log "removed $a (a mod's app)"
        else pm disable "$a" >/dev/null 2>&1 && log "turned off $a (a mod's app; its data stays)"; fi
    done
    [ -s "$D/state/apps.installed" ] && : > "$D/state/apps.alloff"   # a reinstall turns them on again
fi
rm -f "$D/installed.txt" "$D/hook/enable" "$D/public/HmgApi.apk" "$D/HmgApi.apk" "$D/open.sh" "$D/hook-check.sh" "$D/boot.sh"

# 6. NEMESIS's package list: HMG-API and its mods off it too, the mods first (they depend on
# HMG-API's package). HMGAPI_NEMESIS_SYNC=1: their scripts only unlist (refreshed first: an
# older package's copies would uninstall again). Not when NEMESIS runs this: it's unlisting it already.
N=$ROOT/system/xbin/nemesis
if [ "$HMGAPI_FROM_NEMESIS" != 1 ] && [ -x "$N" ] && [ -d "$ROOT/data/nemesis/apps" ]; then
    for a in "$ROOT"/data/nemesis/apps/hmgapi.* "$ROOT/data/nemesis/apps/hmgapi"; do
        [ -f "$a/NEMESIS.data" ] || continue
        id=${a##*/}
        t=$D/nemesis/scripts; [ "$id" = hmgapi ] || t=$D/nemesis/mod/scripts
        [ -d "$t" ] && [ -d "$a/scripts" ] && cp "$t"/*.sh "$a/scripts/" 2>/dev/null
        if r=$(HMGAPI_NEMESIS_SYNC=1 "$N" uninstall "$id" 2>&1); then log "NEMESIS: $id unlisted"
        else log "NEMESIS: couldn't unlist $id ($r): uninstall it in NEMESIS Manager"; fi
    done
fi
if [ "$1" = --purge ]; then
    rm -r "$D"
    echo "removed /data/hmgapi (mods, settings, backups)"
else
    log "kept /data/hmgapi (mods, settings and stock backups); uninstall with --purge to remove it"
fi
echo "HMG-API uninstalled. Restart the head unit."

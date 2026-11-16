#!/system/bin/sh
# HMG-API on subsequent `hmgapid boot` calls (QuickBoot STR resume via qb_runtime.sh).
# Increments /data/hmgapi/public/wake once Android is already up so Hub and mod
# services detect resume immediately without waiting for polling intervals.
umask 077
ROOT=${ROOT:-}
if [ -z "$ROOT" ]; then
    export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
    export LD_LIBRARY_PATH=/vendor/lib:/system/lib
    unset LD_PRELOAD
fi
[ "$(getprop sys.boot_completed)" = 1 ] || exit 0
D=$ROOT/data/hmgapi
if [ -d "$D/public" ]; then
    n=0
    [ -f "$D/public/wake" ] && read n < "$D/public/wake"
    case "$n" in ''|*[!0-9]*) n=0 ;; esac
    n=$((n + 1))
    umask 022
    echo "$n" > "$D/public/wake.tmp" 2>/dev/null && mv "$D/public/wake.tmp" "$D/public/wake"
    umask 077
fi

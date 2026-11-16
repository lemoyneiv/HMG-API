#!/system/bin/sh
# HMG-API at boot, once hmgapid is up. Run by `hmgapid boot` (the boot hook's one line
# in install-recovery.sh and qb_runtime.sh, and HMG-API's NEMESIS service), detached
# from the boot script, and once per boot: the call that started hmgapid runs this,
# the others stop there. So nothing here holds a boot script or NEMESIS's start.
# Output: /data/hmgapi/boot.log.
#
# The app's installer if the app is still waiting; hook-check.sh, which puts the boot
# hook back in a boot script something else rewrote; with NEMESIS, two minutes later,
# engine nemesis-sync, so NEMESIS lists what HMG-API has installed. If an engine apply
# was cut off (or a trial is waiting), roll it back, and restart once if anything was
# rolled back (apps may already have loaded the files).
# ROOT prefixes every path, for off-car tests only.
umask 077
ROOT=${ROOT:-}
if [ -z "$ROOT" ]; then
    # what hmgapid gives its commands (the caller's environment is init's or NEMESIS's)
    export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
    export LD_LIBRARY_PATH=/vendor/lib:/system/lib
    unset LD_PRELOAD
fi
D=$ROOT/data/hmgapi
H=$ROOT/system/bin/hmgapid
echo "$(date) boot"
# Already detached (own session, output to boot.log), so the background jobs start from
# here: through `hmgapid sh` they'd hold its output pipe (mksh's saved fds), and this with it.
if [ -s $D/state/binds.txt ]; then $H binds > $D/binds.log 2>&1 & fi
if [ -f $D/public/HmgApi.apk ]; then sh $D/app-pending.sh </dev/null >/dev/null 2>&1 & fi
if [ -f $D/hook-check.sh ]; then sh $D/hook-check.sh </dev/null >/dev/null 2>&1 & fi
if [ -x $ROOT/system/xbin/nemesis ]; then (sleep 120; $H engine nemesis-sync) </dev/null >$D/nemesis-sync.log 2>&1 & fi
# the points HMG-API's map lacks only because it's older than them, mapped and checked on this
# car (state/local-map.json): only when the map, the catalogue or the stock files changed since,
# so after an HMG-API install or update. Used from the next start. The owner's switch skips it.
if [ -d $D/maps ] && [ ! -f $D/settings/allow/no_local_points ]; then (sleep 90; $H engine map-car --local --if-needed) </dev/null >$D/map-local.log 2>&1 & fi
if [ -f $D/state/journal.json ]; then
    # Restart only after a recover that finished (it takes the journal away). One that
    # failed part-way has "rolled back" in its log too and keeps the journal: restarting
    # on that would come straight back here, at every start. It's tried again at the
    # next start instead, and `hmgapid engine recover` says what's wrong.
    if $H engine recover > $D/recover.log 2>&1; then
        if grep -q "rolled back" $D/recover.log || grep -q "trial undone" $D/recover.log; then
            echo "$(date) recover rolled something back: restarting"
            $H reboot
        fi
    else
        echo "$(date) recover didn't finish (see $D/recover.log): not restarting, tried again at the next start"
    fi
fi
echo "$(date) boot done"

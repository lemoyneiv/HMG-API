#!/system/bin/sh
# HMG-API: install the HMG-API app once Android is up. An install that ran while
# Android was down (adb, or a NEMESIS stick with NEMESIS.emergency) leaves the app in
# /data/hmgapi/public/HmgApi.apk (readable by the package manager); install.sh
# and the boot hook start this in the background through hmgapid. One at a time;
# done when the apk is gone. pm takes HMG-API's signature only while
# sys.boot_completed is 0 (OEM allowlist), so it's set for the pm call only.
exec 3>&- 4>&- 5>&- 6>&- 7>&- 8>&- 9>&-   # hmgapid waits for its pipe to close: don't hold it
D=${ROOT:-}/data/hmgapi
A=$D/public/HmgApi.apk
L=$D/state/app-pending.pid
LOG=$D/install.log
[ -f "$A" ] || exit 0
# another one running? (a stale pid after a restart may be any process: check it's this script)
if [ -f "$L" ]; then
    p=$(cat "$L")
    [ -n "$p" ] && grep -q app-pending "/proc/$p/cmdline" 2>/dev/null && exit 0
fi
echo $$ > "$L"
n=0
while [ "$(getprop sys.boot_completed)" != 1 ]; do
    sleep 5
    n=$((n + 1))
    [ $n -lt 720 ] || { rm -f "$L"; exit 1; }   # an hour; the next boot tries again
done
sleep 20   # let the launcher and the projection apps start first
setprop sys.boot_completed 0
r=$(pm install -r "$A" 2>&1)
setprop sys.boot_completed 1
case "$r" in
    *INCONSISTENT_CERTIFICATES*)
        setprop sys.boot_completed 0
        pm uninstall hmgapi.app >/dev/null 2>&1
        r=$(pm install -r "$A" 2>&1)
        setprop sys.boot_completed 1 ;;
esac
case "$r" in
    *Success*) rm -f "$A"; echo "installed the HMG-API app (Android is up now)" >> "$LOG" ;;
    *) echo "WARNING: the HMG-API app didn't install: $r (tried again at the next restart)" >> "$LOG" ;;
esac
rm -f "$L"

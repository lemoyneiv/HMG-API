#!/bin/sh
# Every script that runs on a head unit (each mod's, and HMG-API's installer) only
# calls commands the unit has: core/runtime/unit-commands.txt (a US EV6's /system/bin
# and /system/xbin: mksh and Android 4.4's toolbox, so no sed, tr, awk, head, tail...).
# The engine runs the same check at install, against the unit itself.
#   tools/check-scripts.sh [mod dir or script...]   (default: the example mods and the installer)
# In the mod SDK (bin/check-scripts.sh), against its unit-commands.txt; name the mod dirs.
HERE=$(cd "$(dirname "$0")" && pwd)
if [ -f "$HERE/../lib/hmgapi-engine.jar" ]; then   # the mod SDK
    [ $# -gt 0 ] || { echo "usage: check-scripts.sh <mod dir or script>..."; exit 2; }
    exec java -cp "$HERE/../lib/hmgapi-engine.jar" hmgapi.engine.ScriptCheck "$HERE/../unit-commands.txt" "$@"
fi
cd "$HERE/.."
CP=$(mktemp -d); trap 'rm -rf "$CP"' EXIT
javac --release 8 -Xlint:-options -nowarn -d "$CP" core/runtime/java/hmgapi/engine/*.java 2>/dev/null
# each example mod as its own folder; the mods HMG-API ships are checked in their own repos (bin/check-scripts.sh)
[ $# -gt 0 ] || set -- mods/examples/*/ install/common/install.sh install/common/uninstall.sh install/common/lib.sh install/common/app-pending.sh install/common/app-open.sh install/common/hook-check.sh install/common/boot.sh install/common/wake.sh install/nemesis/bin/start.sh install/nemesis/mod/scripts/*.sh
java -cp "$CP" hmgapi.engine.ScriptCheck core/runtime/unit-commands.txt "$@"

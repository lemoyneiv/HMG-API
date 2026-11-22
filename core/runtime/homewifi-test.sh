#!/bin/sh
# Off-car test of home Wi-Fi and mods kept off the car's cellular link (HomeWifi):
# what the engine makes of the owner's switch, mod.json "wifi": {"home": true} and
# overrides.allow_cellular (state/mods-net.txt, service-gids.txt, the keepers' lines,
# the plan), then the network keeper's mods section (net.sh) and the home Wi-Fi keeper
# (wifi.sh) against fake tools that keep their state in files.
# UNIT_SH: the shell to run the keepers with (mksh, the unit's, when it's installed).
set -e
cd "$(dirname "$0")/../.."
case "$UNIT_SH" in *busybox*) UNIT_SH= ;; esac
UNIT_SH=${UNIT_SH:-$(command -v mksh || command -v bash)}
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
cleanup() { r=$?; [ $r != 0 ] && [ -z "$done" ] && echo "FAIL  stopped (exit $r) after: ${last:-the start}"; [ -f "$T/fake/fw/wpa.sleep" ] && kill "$(cat "$T/fake/fw/wpa.sleep")" 2>/dev/null || true; rm -rf "$T" "$CP"; }
trap cleanup EXIT
R=$T/root
D=$R/data/hmgapi
fail=0
check() { last=$1; if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { last=$1; case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { last=$1; case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

mkdir -p "$D/mods/dl" "$D/mods/quiet" "$D/mods/cell" "$D/settings" "$D/state"
echo '{"id": "dl", "version": "1.0.0", "wifi": {"home": true}, "apps": [{"package": "com.example.dl", "apk": "dl.apk"}]}' > "$D/mods/dl/mod.json"
echo '{"id": "quiet", "version": "1.0.0", "apps": [{"package": "com.example.quiet", "apk": "q.apk"}]}' > "$D/mods/quiet/mod.json"
echo '{"id": "cell", "version": "1.0.0", "wifi": {"home": true}}' > "$D/mods/cell/mod.json"
: > "$D/mods/dl/dl.apk"; : > "$D/mods/quiet/q.apk"

# ---- the engine: home Wi-Fi off (the default)
eng publish-state
check "publish runs" 0 $rc
check "every mod kept off cellular" "block cell
block dl
block quiet" "$(cat "$D/state/mods-net.txt")"
check "their services' groups" "cell 2950
dl 2950
quiet 2950" "$(cat "$D/state/service-gids.txt")"
has "a mod that asks, home Wi-Fi off: told" "dl: asks for home Wi-Fi, which is off" "$out"
has "the network keeper runs for it" "hmgapi net " "$(cat "$D/state/services.txt")"
hasnt "no home Wi-Fi keeper while it's off" "hmgapi wifi " "$(cat "$D/state/services.txt")"

# ---- the owner's switch on (the app's file), one mod allowed cellular
mkdir -p "$D/settings/allow"; : > "$D/settings/allow/home_wifi"
echo '{"overrides": {"allow_cellular": ["cell"]}}' > "$D/settings/hmgapi.json"
eng plan
has "the plan marks unapplied home Wi-Fi switch" "change   home Wi-Fi -> on [hmgapi]" "$out"
eng publish-state
check "home for the mod that asked, block for the one that didn't, allow for the override" "allow cell
home dl
block quiet
homewifi on" "$(cat "$D/state/mods-net.txt")"
check "groups: none for the allowed one, home and plain" "cell 0
dl 2951
quiet 2950" "$(cat "$D/state/service-gids.txt")"
has "allowing cellular warns" "WARNING: cell: may use the car's cellular link (overrides.allow_cellular): at your own risk" "$out"
has "the home Wi-Fi keeper runs" "hmgapi wifi root android yes script /data/hmgapi/state/wifi.sh" "$(cat "$D/state/services.txt")"
check "its script written" yes "$([ -s "$D/state/wifi.sh" ] && echo yes || echo no)"
eng plan
hasnt "applied home Wi-Fi switch no longer marked pending" "change   home Wi-Fi ->" "$out"
has "the plan: home Wi-Fi on" "home Wi-Fi  on" "$out"
has "the plan: home Wi-Fi only for the mod that asked" "network  home Wi-Fi only (no cellular) [dl]" "$out"
has "the plan: the allowed one" "may use cellular (overrides.allow_cellular, at your own risk) [cell]" "$out"
has "the plan: the car's own Wi-Fi client feature, HMG-API's own" "android.hardware.wifi.xml [hmgapi]" "$out"
has "the vehicle service runs for it (parked, power)" "hmgapi vehicle " "$(cat "$D/state/services.txt")"
echo '{"id": "bad", "version": "1.0.0", "wifi": {"home": "yes"}}' > "$D/mods/cell/mod.json"
eng plan
has "a bad \"wifi\" ask refused" "\"home\": true}" "$out"
echo '{"id": "cell", "version": "1.0.0", "wifi": {"home": true}}' > "$D/mods/cell/mod.json"

# ---- the network keeper's mods section, against fake tools
F=$T/fake
mkdir -p "$F/bin" "$F/fw"
cat > "$F/bin/iptables" <<'S'
#!/bin/bash
# a fake iptables: one file per chain ($FW/<v>/<table>/<chain>), rules as given
v=4; [ "${0##*/}" = ip6tables ] && v=6
t=filter; a=()
while [ $# -gt 0 ]; do if [ "$1" = -t ]; then t=$2; shift 2; else a+=("$1"); shift; fi; done
set -- "${a[@]}"
[ $v = 6 ] && [ $t = nat ] && { echo "ip6tables: can't initialize table nat" >&2; exit 3; }
d=$FW/$v/$t; mkdir -p "$d"
stock() { case $1 in INPUT|OUTPUT|FORWARD|PREROUTING|POSTROUTING) return 0 ;; esac; return 1; }
op=$1 c=$2; shift 2
f=$d/$c
if ! stock "$c" && [ "$op" != -N ] && [ ! -f "$f" ]; then echo "iptables: No chain/target/match by that name." >&2; exit 1; fi
case $op in
    -S) if stock "$c"; then echo "-P $c ACCEPT"; else echo "-N $c"; fi
        [ -f "$f" ] && while IFS= read -r l; do echo "-A $c $l"; done < "$f"; exit 0 ;;
    -N) [ -f "$f" ] && { echo "iptables: Chain already exists." >&2; exit 1; }; : > "$f" ;;
    -F) : > "$f" ;;
    -X) grep -qx -- "-j $c" "$d"/* 2>/dev/null && { echo "iptables: Too many links." >&2; exit 1; }; rm -f "$f" ;;
    -C) grep -qx -- "$*" "$f" 2>/dev/null ;;
    -D) grep -qx -- "$*" "$f" 2>/dev/null || exit 1
        awk -v r="$*" '!done && $0 == r { done = 1; next } { print }' "$f" > "$f.new"; mv "$f.new" "$f" ;;
    -I) shift; { echo "$*"; cat "$f" 2>/dev/null; } > "$f.new"; mv "$f.new" "$f" ;;
    -A) echo "$*" >> "$f" ;;
esac
S
cp "$F/bin/iptables" "$F/bin/ip6tables"
cat > "$F/bin/ip" <<'S'
#!/bin/bash
# a fake ip: routes as lines in ip4.route ("<route> table <t>"; main has no "table"), rules as "<prio>:\t<rest>"
v=4; case $1 in -4) shift ;; -6) v=6; shift ;; esac
o=$1 c=$2; shift 2
f=$FW/ip$v.$o; touch "$f"
case $o.$c in
    route.show) if [ "$1" = dev ]; then grep -- " dev $2 .*table main\$" "$f" | sed 's/ table main$//' || true; else grep -- " table ${3:-main}\$" "$f" || true; fi ;;
    route.replace) n=$#; if [ "${@:$((n-1)):1}" = table ]; then tb=${!n}; set -- "${@:1:$((n-2))}"; else tb=main; fi
        grep -v -- "^$1 .*table $tb\$" "$f" > "$f.new"; mv "$f.new" "$f"; echo "$* table $tb" >> "$f" ;;
    route.del) grep -q -e "^$1 .*dev $3 .*table main\$" -e "^$1 dev $3 .*table main\$" "$f" || exit 2
        awk -v r="$1" -v d="$3" '!x && $1 == r && index($0, " dev " d " ") { x = 1; next } { print }' "$f" > "$f.new"; mv "$f.new" "$f" ;;
    route.flush) grep -v -- " table $2\$" "$f" > "$f.new"; mv "$f.new" "$f" ;;
    route.get) [ "$2" = oif ] && grep -q "^10\.1\." <<< "$1" && echo "$1 dev $3" ;;
    addr.flush) : ;;
    rule.show) echo "0:	from all lookup local"; cat "$f" ;;
    rule.add) p=$2; shift 2; printf '%s:\t%s\n' "$p" "$*" >> "$f" ;;
    rule.del) grep -q "^$2:" "$f" || exit 2; grep -v "^$2:" "$f" > "$f.new"; mv "$f.new" "$f" ;;
esac
S
cat > "$F/bin/getprop" <<'S'
#!/bin/sh
cat "$FW/props/$1" 2>/dev/null
S
cat > "$F/bin/setprop" <<'S'
#!/bin/sh
mkdir -p "$FW/props"; printf '%s' "$2" > "$FW/props/$1"
S
printf '#!/bin/sh\necho "$*" >> "$FW/ndc.log"\n' > "$F/bin/ndc"
chmod +x "$F/bin/"*
export FW=$F/fw
keeper() { HMGAPI_PACKAGES=$F/packages.list PATH=$F/bin:$PATH $UNIT_SH "$D/state/net.sh" "$@" 2>&1; }
ipt() { PATH=$F/bin:$PATH iptables "$@"; }
printf 'com.example.dl 10071 0 /data/data/com.example.dl default 3003\ncom.example.quiet 10072 0 /data/data/com.example.quiet default 3003\n' > "$F/packages.list"
printf 'com.example.dl dl\ncom.example.quiet quiet\n' > "$D/state/apps.txt"

log=$(keeper once)
has "mods kept off cellular" "mods kept off cellular: 2 app(s) and every mod service, home Wi-Fi routing in" "$log"
check "the cellular chain first in OUTPUT" "-A OUTPUT -j HMGAPI_CELL" "$(ipt -S OUTPUT | grep -m1 '^-A')"
check "its rules: both services' groups and both apps' uids" "-o rmnet+ -m owner --gid-owner 2950 -j REJECT
-o rmnet+ -m owner --gid-owner 2951 -j REJECT
-o rmnet+ -m owner --uid-owner 10071 -j REJECT
-o rmnet+ -m owner --uid-owner 10072 -j REJECT" "$(cat "$F/fw/4/filter/HMGAPI_CELL")"
check "IPv6 too" 4 "$(grep -c REJECT "$F/fw/6/filter/HMGAPI_CELL")"
check "only the mod that asked marked for home Wi-Fi (and the home group)" "-m owner --gid-owner 2951 -j MARK --set-mark 0x2951
-m owner --uid-owner 10071 -j MARK --set-mark 0x2951" "$(cat "$F/fw/4/mangle/HMGAPI_HOME")"
check "masqueraded on wlan0 (home Wi-Fi: the car's own client)" "-o wlan0 -m mark --mark 0x2951 -j MASQUERADE" "$(cat "$F/fw/4/nat/HMGAPI_HOMEOUT")"
has "marked traffic to table 95" "900:	fwmark 0x2951 table 95" "$(cat "$F/fw/ip4.rule")"
check "both mods' apps look names up through wlan0, never cellular DNS" "resolver setifaceforuidrange wlan0 10071 10071
resolver setifaceforuidrange wlan0 10072 10072" "$(cat "$F/fw/ndc.log")"
check "all there: a check is quiet" "" "$(keeper once)"
ipt -I OUTPUT 1 -o rmnet+ -j ACCEPT   # the stock firewall puts an accept first
has "moved: put back first" "mods' cellular block put back" "$(keeper once)"
check "... first again" "-A OUTPUT -j HMGAPI_CELL" "$(ipt -S OUTPUT | grep -m1 '^-A')"
printf 'com.example.dl 10091 0 /data/data/com.example.dl default 3003\ncom.example.quiet 10072 0 /data/data/com.example.quiet default 3003\n' > "$F/packages.list"
keeper once >/dev/null
has "a reinstalled app's new uid" "--uid-owner 10091 -j REJECT" "$(cat "$F/fw/4/filter/HMGAPI_CELL")"
hasnt "... the old one gone" "10071" "$(cat "$F/fw/4/filter/HMGAPI_CELL")"
rm "$D/settings/allow/home_wifi"
eng plan
has "turning home Wi-Fi off marks pending change" "change   home Wi-Fi -> off [hmgapi]" "$out"
eng publish-state
check "home Wi-Fi off: keeper script removed" no "$([ -f "$D/state/wifi.sh" ] && echo yes || echo no)"
keeper once >/dev/null
check "home Wi-Fi off: no marks" no "$([ -f "$F/fw/4/mangle/HMGAPI_HOME" ] && echo yes || echo no)"
hasnt "... no routing rule" "900:" "$(cat "$F/fw/ip4.rule")"
check "... still off cellular" 4 "$(grep -c REJECT "$F/fw/4/filter/HMGAPI_CELL")"
echo '{"overrides": {"allow_cellular": ["cell", "dl"]}}' > "$D/settings/hmgapi.json"
eng publish-state
: > "$F/fw/ndc.log"
keeper once >/dev/null
hasnt "allowing cellular live: its app uid dropped from HMGAPI_CELL" "10091" "$(cat "$F/fw/4/filter/HMGAPI_CELL")"
has "... the other mod's app uid stays blocked" "--uid-owner 10072 -j REJECT" "$(cat "$F/fw/4/filter/HMGAPI_CELL")"
check "... its wlan0 DNS range cleared in netd, only the blocked mod's put back" "resolver clearifaceforuidrange wlan0 10091 10091
resolver clearifaceforuidrange wlan0 10072 10072
resolver setifaceforuidrange wlan0 10072 10072" "$(cat "$F/fw/ndc.log")"
echo '{"overrides": {"allow_cellular": ["cell"]}}' > "$D/settings/hmgapi.json"
eng publish-state
keeper once >/dev/null
: > "$F/fw/ndc.log"
keeper down >/dev/null
hasnt "down: the cellular chain gone" "HMGAPI_CELL" "$(ipt -S OUTPUT)"
check "down: netd's wlan0 DNS ranges cleared too" "resolver clearifaceforuidrange wlan0 10091 10091
resolver clearifaceforuidrange wlan0 10072 10072" "$(cat "$F/fw/ndc.log")"
: > "$D/settings/allow/home_wifi"
eng publish-state

# ---- the home Wi-Fi keeper wrapper (wifi.sh), HomeWifiRouter, and HomeWifiControllerTest
java -cp "$CP" hmgapi.engine.ScriptCheck core/runtime/unit-commands.txt "$D/state/wifi.sh" || fail=1
echo "ok    wifi.sh passes ScriptCheck against unit-commands.txt"

cat > "$F/bin/app_process" <<'S'
#!/bin/sh
echo "app_process $* key=$HMGAPI_WIFI_PSK has=$HMGAPI_WIFI_HAS_PSK"
S
chmod +x "$F/bin/app_process"
has "wifi.sh service mode launches HomeWifiDaemon" "app_process /system/bin --nice-name=hmgapi.wifi hmgapi.wifi.HomeWifiDaemon" "$(PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" deadbeef)"
has "wifi.sh CLI fallback forwards status" "app_process /system/bin hmgapi.wifi.HomeWifiDaemon cli status" "$(PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" status)"
has "wifi.sh CLI fallback forwards use off" "app_process /system/bin hmgapi.wifi.HomeWifiDaemon cli use CAR_CAM_1 off" "$(PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" use CAR_CAM_1 off)"
has "wifi.sh CLI fallback forwards keepawake" "app_process /system/bin hmgapi.wifi.HomeWifiDaemon cli keepawake 15m plugged" "$(PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" keepawake 15m plugged)"
has "wifi.sh CLI fallback preserves key in env only" "app_process /system/bin hmgapi.wifi.HomeWifiDaemon cli connect Home key=sekret has=1" "$(HMGAPI_WIFI_PSK=sekret HMGAPI_WIFI_HAS_PSK=1 PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" connect Home)"

# Prepare fake routing table state for HomeWifiRouter check inside HomeWifiControllerTest
: > "$F/fw/ndc.log"
: > "$F/fw/ip4.route"
FW=$F/fw PATH=$F/bin:$PATH ip route replace default via 10.4.0.1 dev wlan0
FW=$F/fw PATH=$F/bin:$PATH ip route replace 10.4.0.0/16 dev wlan0 proto kernel

javac --release 8 -Xlint:-options -nowarn -cp "$CP" -d "$CP" \
    core/runtime/java/hmgapi/wifi/WifiSecurity.java \
    core/runtime/java/hmgapi/wifi/WifiPlatform.java \
    core/runtime/java/hmgapi/wifi/HomeWifiKeepAwake.java \
    core/runtime/java/hmgapi/wifi/HomeWifiStore.java \
    core/runtime/java/hmgapi/wifi/HomeWifiRouter.java \
    core/runtime/java/hmgapi/wifi/HomeWifiController.java \
    core/runtime/test/hmgapi/wifi/HomeWifiControllerTest.java
FW=$F/fw PATH=$F/bin:$PATH java -cp "$CP" hmgapi.wifi.HomeWifiControllerTest || fail=1

done=1
[ $fail = 0 ] && echo "all home Wi-Fi checks passed"
exit $fail

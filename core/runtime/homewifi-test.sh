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
eng publish-state
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

# ---- the home Wi-Fi keeper, against fake tools
X=$T/unit
mkdir -p "$X/data/misc/wifi/sockets" "$X/data/hmgapi/public" "$X/proc/sys/kernel/random"
echo boot-1 > "$X/proc/sys/kernel/random/boot_id"
echo "1000.00 2000.00" > "$X/proc/uptime"
cat > "$F/bin/hmgapid" <<'S'
#!/bin/bash
case $1 in
    vehicle) v=$(cat "$FW/veh.$2" 2>/dev/null); [ -n "$v" ] && echo "$2 $v"; exit 0 ;;
    wpa) c=$3; echo "$c" >> "$FW/wpa.log"
        [ -f "$FW/client" ] || exit 1   # the car's client isn't running: no socket
        case $c in
            STATUS) cat "$FW/wpa.status" ;;
            SIGNAL_POLL) echo "RSSI=-61" ;;
            SCAN_RESULTS) printf 'bssid / frequency / signal level / flags / ssid\naa:aa\t2437\t-61\t[WPA2-PSK-CCMP][ESS]\tWillows\nbb:bb\t5180\t-70\t[WPA2-PSK-CCMP][ESS]\tOther\n' ;;
            *) echo OK ;;
        esac ;;
esac
S
cat > "$F/bin/app_process" <<'S'
#!/bin/bash
# WifiAp: the hotspot ($FW/ap: its mode) and the car's own client ($FW/client)
echo "app_process $*" >> "$FW/apps.log"
case "$3 $4" in
    "stop ") if [ -f "$FW/ap" ]; then cat "$FW/ap"; rm "$FW/ap"; else echo 0; fi ;;
    "start "*) echo "$4" > "$FW/ap"; echo "hotspot on" ;;
    "client on") : > "$FW/client"; echo running > "$FW/props/init.svc.wpa_supplicant"; echo "client on" ;;
    "client off") rm -f "$FW/client"; echo stopped > "$FW/props/init.svc.wpa_supplicant"; echo "client off" ;;
esac
S
printf '#!/bin/sh\n:\n' > "$F/bin/chown"
chmod +x "$F/bin/"*
mkdir -p "$F/fw/props"
echo 2 > "$F/fw/ap"   # the projection hotspot is up
# the keeper's loop replaced by N keeps
sed -e 's/^while :; do$/for _k in $(seq 1 ${KEEPS:-1}); do keep; done; exit 0/' "$D/state/wifi.sh" > "$D/state/wifi.sh.n"
wk() { r=0; HMGAPI_TEST_ROOT=$X KEEPS=${1:-1} PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh.n" 2>&1 || r=$?
    [ $r = 0 ] || { echo "FAIL  the keeper exited $r, after: $last" >&2; fail=1; }; }
verb() { HMGAPI_TEST_ROOT=$X PATH=$F/bin:$PATH $UNIT_SH "$D/state/wifi.sh" "$@" 2>&1; }
pub() { cat "$X/data/hmgapi/public/wifi.json" 2>/dev/null; }
state() { pub | sed 's/.*"state": "\([a-z]*\)".*/\1/'; }
NET=$X/data/misc/wifi/wpa_supplicant.conf

echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
wk >/dev/null
check "the car on: waits for parked" waiting "$(state)"
check "... the hotspot left as it is" 2 "$(cat "$F/fw/ap")"
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
wk >/dev/null
check "parked with no saved networks: nothing switched" nonetworks "$(state)"
check "... the hotspot still up" 2 "$(cat "$F/fw/ap")"

# the app's verbs, on Android's own config
cat > "$NET" <<'C'
ctrl_interface=wlan0
update_config=1
p2p_disabled=1

network={
	ssid="Willows"
	psk="secret-not-shown"
	key_mgmt=WPA-PSK
}
C
check "saved: the names" "Willows" "$(verb saved)"
out=$(HMGAPI_WIFI_PSK=short verb join "Cascadia" || true)
check "join: a key under 8 characters refused" "a Wi-Fi key is 8 to 63 characters" "$out"
out=$(HMGAPI_WIFI_PSK='has"quote' verb join "Cascadia" || true)
has "join: a key that could break the config refused" "can't be used" "$out"
out=$(HMGAPI_WIFI_PSK= verb join 'Bad"Name' || true)
has "join: a name that could break the config refused" "can't be used" "$out"
check "join: saved" "saved: Cascadia" "$(HMGAPI_WIFI_PSK=cascadia-key verb join Cascadia)"
check "... both now" "Willows
Cascadia" "$(verb saved)"
has "... Android's own settings kept" "p2p_disabled=1" "$(cat "$NET")"
has "... its key in the config, nothing printed" 'psk="cascadia-key"' "$(cat "$NET")"
HMGAPI_WIFI_PSK=new-willows-key verb join Willows >/dev/null
check "join again: replaced, not doubled" 1 "$(grep -c 'ssid="Willows"' "$NET")"
has "... with its new key" 'psk="new-willows-key"' "$(cat "$NET")"
check "an open network" "saved: Cafe" "$(HMGAPI_WIFI_PSK= verb join Cafe)"
check "forget" "forgotten: Cafe" "$(verb forget Cafe)"
check "... gone, the others kept" "Cascadia
Willows" "$(verb saved | sort)"
check "seen: nothing before home Wi-Fi was on" "" "$(verb seen)"

# parked: the hotspot off, the car's client on
echo "wpa_state=SCANNING" > "$F/fw/wpa.status"
: > "$F/fw/apps.log"
log=$(wk)
has "parked: switched at once" "the car off: the hotspot off, the car's Wi-Fi client on" "$log"
check "... the hotspot off" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
has "... through Android's Wi-Fi service" "hmgapi.engine.WifiAp client on" "$(cat "$F/fw/apps.log")"
check "... looking" searching "$(state)"
out=$(HMGAPI_WIFI_PSK=some-new-key verb join Other || true)
has "while parked and on: networks wait for the car to be on" "add networks while the car is on" "$out"

# joined: the car's own traffic on mobile data, the mods that asked on home Wi-Fi
printf 'wpa_state=COMPLETED\nfreq=2437\n' > "$F/fw/wpa.status"
FW=$F/fw PATH=$F/bin:$PATH ip route replace default via 10.4.0.1 dev wlan0     # Android's, when Wi-Fi is default
FW=$F/fw PATH=$F/bin:$PATH ip route replace 10.4.0.0/16 dev wlan0 proto kernel
printf 10.10.10.100 > "$F/fw/props/dhcp.wlan0.dns1"
: > "$F/fw/ndc.log"
log=$(wk)
has "up" "home Wi-Fi up: the mods that asked go through it; the car stays on mobile data" "$log"
check "status: up, signal, channel, no network name" '{"state": "up", "rssi": -61, "freq": 2437}' "$(pub)"
hasnt "the car's default route back off Wi-Fi" "default via 10.4.0.1 dev wlan0 table main" "$(cat "$F/fw/ip4.route")"
has "table 95: Wi-Fi's default" "default via 10.4.0.1 dev wlan0 table 95" "$(cat "$F/fw/ip4.route")"
has "table 95: the subnet" "10.4.0.0/16 dev wlan0 table 95" "$(cat "$F/fw/ip4.route")"
has "the car's own lookups on its carrier's DNS" "resolver setdefaultif rmnet0" "$(cat "$F/fw/ndc.log")"
has "wlan0's DNS servers for the mods" "resolver setifdns wlan0  10.10.10.100" "$(cat "$F/fw/ndc.log")"
has "a DNS server off the subnet: through the gateway" "10.10.10.100/32 via 10.4.0.1 dev wlan0 metric 1000" "$(cat "$F/fw/ip4.route")"
check "mods' services' DNS to the home router's" "-m owner --gid-owner 2951 -p udp --dport 53 -j DNAT --to-destination 10.10.10.100
-m owner --gid-owner 2951 -p tcp --dport 53 -j DNAT --to-destination 10.10.10.100" "$(cat "$F/fw/4/nat/HMGAPI_HOMEDNS")"
has "what it saw, for the app" "2437	-61	[WPA2-PSK-CCMP][ESS]	Willows" "$(verb seen)"
hasnt "the key never in the log" "secret" "$log"
FW=$F/fw PATH=$F/bin:$PATH ip route replace default via 10.4.0.1 dev wlan0   # Android puts it back (a renewal)
wk >/dev/null
hasnt "... taken out again" "default via 10.4.0.1 dev wlan0 table main" "$(cat "$F/fw/ip4.route")"

# the car on again
echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
: > "$F/fw/apps.log"
log=$(wk)
has "the car on: back" "back to the car's Wi-Fi (the car is on): the car's Wi-Fi client off, the hotspot back" "$log"
check "... the projection hotspot, in its mode" 2 "$(cat "$F/fw/ap")"
check "... client off, then the hotspot" "client off
start 2" "$(grep -o 'client off\|start [12]' "$F/fw/apps.log")"
check "... table 95 empty" "" "$(grep 'table 95' "$F/fw/ip4.route" || true)"
check "... waiting again" waiting "$(state)"

# the car off without parked (the screen goes dark with it): 30 s, then home Wi-Fi
echo adm > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
echo "2000.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "the car just off: not yet (a stop for a moment)" 2 "$(cat "$F/fw/ap")"
echo "2020.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "... 20 s: not yet" 2 "$(cat "$F/fw/ap")"
echo on > "$F/fw/veh.power"; wk >/dev/null; echo adm > "$F/fw/veh.power"
echo "2040.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "on and off again: the 30 s start over" 2 "$(cat "$F/fw/ap")"
echo "2071.00 2000.00" > "$X/proc/uptime"
has "off 30 s: switched" "the car off: the hotspot off" "$(wk)"
check "... the hotspot off" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo on > "$F/fw/veh.power"
has "on again: back at once" "back to the car's Wi-Fi (the car is on): the car's Wi-Fi client off, the hotspot back" "$(wk)"

# on request (the app's "Connect now"; later the updater): in P, with the car on
echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"; echo 0 > "$F/fw/veh.gear.park"
wk >/dev/null
out=$(verb now 5 || true)
has "now: refused out of P" "shift to P first" "$out"
echo 1 > "$F/fw/veh.gear.park"
check "now: minutes checked" "minutes: 1 to 240, or 0 for no time limit" "$(verb now 999 || true)"
has "now: in P" "home Wi-Fi on for 5 minutes" "$(verb now 5)"
has "... switched on the next check" "on request: the hotspot off, the car's Wi-Fi client on" "$(wk)"
check "... the hotspot off" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
wk >/dev/null
check "... stays while the car is on, in P" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo 0 > "$F/fw/veh.gear.park"
has "out of P: back at once" "out of P: the car's Wi-Fi client off, the hotspot back" "$(wk)"
check "... the hotspot" 2 "$(cat "$F/fw/ap")"
echo 1 > "$F/fw/veh.gear.park"
verb now 1 >/dev/null; wk >/dev/null
verb back >/dev/null
has "back: the hotspot again" "the car's Wi-Fi client off, the hotspot back" "$(wk)"
verb now 1 >/dev/null; wk >/dev/null
echo "2400.00 2000.00" > "$X/proc/uptime"
has "the time up: back" "the car's Wi-Fi client off, the hotspot back" "$(wk)"
check "... the request gone" no "$([ -f "$X/data/hmgapi/wifi/manual" ] && echo yes || echo no)"
has "now 030: thirty minutes, not octal" "home Wi-Fi on for 30 minutes" "$(verb now 030)"
check "... ends 30 minutes on" "$(($(read -r u r < "$X/proc/uptime"; echo ${u%.*}) + 1800))" "$(read -r b m < "$X/data/hmgapi/wifi/manual"; echo "$m")"
verb back >/dev/null; wk >/dev/null
has "now 0: no time limit" "home Wi-Fi on until you shift out of P" "$(verb now 0)"
has "... switched on" "on request: the hotspot off, the car's Wi-Fi client on" "$(wk)"
echo "99999.00 2000.00" > "$X/proc/uptime"
wk >/dev/null
check "... still on hours later, in P" no "$([ -f "$F/fw/ap" ] && echo yes || echo no)"
echo 0 > "$F/fw/veh.gear.park"
has "... out of P: back" "out of P: the car's Wi-Fi client off, the hotspot back" "$(wk)"
check "... the request gone" no "$([ -f "$X/data/hmgapi/wifi/manual" ] && echo yes || echo no)"
echo 1 > "$F/fw/veh.gear.park"
echo "2400.00 2000.00" > "$X/proc/uptime"
mv "$NET" "$NET.keep"; check "now with no saved networks: refused" "no saved networks: add one first" "$(verb now 5 || true)"; mv "$NET.keep" "$NET"

# a restart while parked: Android remembers Wi-Fi on; the hotspot comes back first
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
wk >/dev/null
echo boot-2 > "$X/proc/sys/kernel/random/boot_id"; echo on > "$F/fw/veh.power"; echo 0 > "$F/fw/veh.parked"
has "after a restart: HMG-API's client from before put back to the hotspot" "after a restart while home Wi-Fi was on" "$(wk)"
check "... the hotspot" 2 "$(cat "$F/fw/ap")"

# the car's own Wi-Fi settings, not HMG-API: left alone
echo running > "$F/fw/props/init.svc.wpa_supplicant"
wk >/dev/null
check "the car's own client (its settings): left alone" stock "$(state)"
echo stopped > "$F/fw/props/init.svc.wpa_supplicant"

# home Wi-Fi turned off while parked and on: the hotspot back
echo delay_mode > "$F/fw/veh.power"; echo 1 > "$F/fw/veh.parked"
wk >/dev/null
: > "$F/fw/apps.log"
verb down >/dev/null
check "down while parked: the hotspot back" 2 "$(cat "$F/fw/ap")"
check "... the client off" no "$([ -f "$F/fw/client" ] && echo yes || echo no)"

done=1
[ $fail = 0 ] && echo "all home Wi-Fi checks passed"
exit $fail

#!/bin/sh
# Off-car test of mods' network asks (Network): what the engine makes of mod.json
# "network" (state/net.rules, the keeper's services.txt line, the plan's lines,
# warnings and overrides), then HMG-API's network keeper (state/net.sh) against a
# fake iptables/ip6tables/ip/getprop that keep their state in files: rules in, the
# stock firewall moving or flushing them, routes and sysctls, changes, down.
# UNIT_SH: the shell to run the keeper with (mksh, the unit's, when it's installed).
# It may carry arguments. A busybox one isn't used here: busybox's shell runs its own
# ip and iptables applets ahead of this test's stand-ins on PATH (the unit's keeper
# runs under mksh, with no busybox), so mksh or bash runs it instead.
set -e
cd "$(dirname "$0")/../.."
case "$UNIT_SH" in *busybox*) UNIT_SH= ;; esac
UNIT_SH=${UNIT_SH:-$(command -v mksh || command -v bash)}
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
D=$R/data/hmgapi
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CEEV.USA.0000.V015 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }

mkdir -p "$D/mods/cp" "$D/mods/vpn" "$D/mods/bad" "$D/mods/other" "$D/settings"
# the Ioniq 6's wired CarPlay ports, opened on the hotspot (hotspot only: nothing more to allow)
cat > "$D/mods/cp/mod.json" <<'J'
{"id": "cp", "version": "1.0.0",
 "network": {"rules": [
   {"chain": "input", "in": "hotspot", "proto": "tcp+udp", "ports": ["5000-5001", 5020, "6000-6003", 6011, 6020, 6030, 7000], "both_ways": true, "action": "accept"},
   {"chain": "input", "in": "hotspot", "proto": "icmpv6", "action": "accept"}]}}
J
cat > "$D/mods/vpn/mod.json" <<'J'
{"id": "vpn", "version": "1.0.0",
 "network": {"rules": [
   {"table": "nat", "chain": "postrouting", "out": "rmnet+", "from": "hotspot", "action": "masquerade"},
   {"chain": "forward", "in": "hotspot", "out": "rmnet+", "action": "accept", "when": "settings.share"}],
  "routes": [{"to": "10.8.0.0/24", "dev": "tun0", "table": 100}],
  "route_rules": [{"priority": 1000, "fwmark": 16, "table": 100}],
  "sysctl": {"net.ipv4.ip_forward": "1"}}}
J
echo '{"share": true}' > "$D/settings/vpn.json"
cat > "$D/mods/bad/mod.json" <<'J'
{"id": "bad", "version": "1.0.0",
 "network": {"rules": [
   {"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [70000], "action": "accept"},
   {"chain": "sideways", "action": "accept"},
   {"table": "nat", "chain": "postrouting", "ip": 6, "action": "masquerade"},
   {"chain": "input", "in": "wlan0; reboot", "action": "accept"},
   {"chain": "input", "from": "1.2.3.400", "action": "drop"},
   {"chain": "input", "action": "masquerade"},
   {"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [80], "action": "accept", "extra": "-j ACCEPT"}],
  "sysctl": {"kernel.panic": "1", "net.ipv4.ip_forward": "1; reboot"}}}
J
cat > "$D/mods/other/mod.json" <<'J'
{"id": "other", "version": "1.0.0",
 "network": {"rules": [{"chain": "input", "in": "hotspot", "proto": "tcp", "ports": [7000], "action": "drop"}]}}
J

eng publish-state
check "publish runs" 0 $rc
has "bad port refused" "bad: network rule 1: ports: 70000 isn't a port" "$out"
has "unknown chain refused" "bad: network rule 2: \"chain\" sideways isn't one of filter's" "$out"
has "IPv6 nat refused" "bad: network rule 3: nat is IPv4 only" "$out"
has "an interface with shell in it refused" "bad: network rule 4: interface wlan0; reboot" "$out"
has "bad address refused" "bad: network rule 5: address 1.2.3.400" "$out"
has "masquerade only on nat postrouting" "bad: network rule 6: masquerade is for nat postrouting" "$out"
has "no free-form iptables words" "bad: network rule 7: unknown \"extra\"" "$out"
has "only net.* sysctls" "bad: network sysctl kernel.panic: only net." "$out"
has "sysctl values are numbers" "bad: network sysctl net.ipv4.ip_forward: the value must be whole numbers" "$out"
has "risky asks left out with a warning" "WARNING: vpn: network firewall nat postrouting: masquerade out rmnet+ from hotspot (IPv4, 1 rule) left out: not only the hotspot" "$out"
has "... and how to allow them" "add \"vpn\" to overrides.allow_network" "$out"
has "routes need allowing" "WARNING: vpn: network route 10.8.0.0/24 dev tun0 (table 100) left out" "$out"
has "two mods matching the same traffic: whose comes first" "network: cp and other handle the same traffic differently; cp's rule comes first" "$out"
rules=$(cat "$D/state/net.rules")
# 7 port specs x tcp/udp x (dport + sport) = 28 a family (the Ioniq 6 run that worked: 28 IPv4,
# 29 IPv6 with neighbour discovery), plus other's drop
check "CarPlay rules: IPv4" 29 "$(grep -c '^ipt 4 filter HMGAPI_IN ' "$D/state/net.rules")"
check "... IPv6 (and neighbour discovery)" 30 "$(grep -c '^ipt 6 filter HMGAPI_IN ' "$D/state/net.rules")"
has "hotspot: wlan0 and its addresses only" "ipt 4 filter HMGAPI_IN -i wlan0 -s 192.168.0.0/24 -p tcp --dport 5000:5001 -j ACCEPT" "$rules"
has "... link-local over IPv6" "ipt 6 filter HMGAPI_IN -i wlan0 -s fe80::/10 -p udp --sport 7000 -j ACCEPT" "$rules"
has "chain counts first, for the keeper" "chain 4 filter HMGAPI_IN 29
chain 6 filter HMGAPI_IN 30" "$rules"
hasnt "nothing risky without allowing" "MASQUERADE" "$rules"
hasnt "nothing from the bad mod" "--dport 80 " "$rules"
svc=$(grep '^hmgapi net ' "$D/state/services.txt")
has "the keeper runs as HMG-API's own service" "hmgapi net root boot yes script /data/hmgapi/state/net.sh - " "$svc"
check "the keeper script written" yes "$([ -s "$D/state/net.sh" ] && echo yes)"
check "the keeper only uses the unit's commands" "ok    $D/state/net.sh" "$(java -cp "$CP" hmgapi.engine.ScriptCheck core/runtime/unit-commands.txt "$D/state/net.sh" 2>&1 || true)"

eng plan
has "plan: hotspot rule" "network  firewall filter input: accept tcp+udp port 5000-5001,5020,6000-6003,6011,6020,6030,7000 (either way) in hotspot (IPv4+6, 56 rules) [cp]" "$out"
has "plan: risky rule shown as left out" "network  sysctl net.ipv4.ip_forward = 1 (left out unless allowed: a network setting of the whole unit) [vpn]" "$out"

echo '{"overrides": {"allow_network": ["vpn"], "network_order": ["other"]}}' > "$D/settings/hmgapi.json"
eng publish-state
rules=$(cat "$D/state/net.rules")
has "allowed: nat" "ipt 4 nat HMGAPI_POST -o rmnet+ -s 192.168.0.0/24 -j MASQUERADE" "$rules"
has "allowed: forward (its setting on)" "ipt 4 filter HMGAPI_FWD -i wlan0 -o rmnet+ -s 192.168.0.0/24 -j ACCEPT" "$rules"
has "allowed: route" "route 4 100 10.8.0.0/24 dev tun0" "$rules"
has "allowed: routing rule" "rule 4 1000 fwmark 16 table 100" "$rules"
has "allowed: sysctl" "sysctl net/ipv4/ip_forward 1" "$rules"
check "network_order: other's rule first" "ipt 4 filter HMGAPI_IN -i wlan0 -s 192.168.0.0/24 -p tcp --dport 7000 -j DROP" "$(grep -m1 '^ipt 4 filter HMGAPI_IN' "$D/state/net.rules")"
eng plan
has "plan: allowed and said so" "(allowed: a network setting of the whole unit) [vpn]" "$out"
echo '{"share": false}' > "$D/settings/vpn.json"
eng publish-state
hasnt "a rule's \"when\" follows the mod's settings" "HMGAPI_FWD" "$(cat "$D/state/net.rules")"
echo '{"share": true}' > "$D/settings/vpn.json"
: > "$D/mods/other/.disabled"
eng publish-state

# ---- the keeper, against fake tools
F=$T/fake
mkdir -p "$F/bin" "$F/fw" "$F/proc/net/ipv4"
echo 0 > "$F/proc/net/ipv4/ip_forward"
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
    -S) if stock "$c"; then echo "-P $c DROP"; else echo "-N $c"; fi
        [ -f "$f" ] && while IFS= read -r l; do echo "-A $c $l"; done < "$f"; exit 0 ;;
    -N) [ -f "$f" ] && { echo "iptables: Chain already exists." >&2; exit 1; }; : > "$f" ;;
    -F) : > "$f" ;;
    -X) grep -qx -- "-j $c" "$d"/* 2>/dev/null && { echo "iptables: Too many links." >&2; exit 1; }; rm -f "$f" ;;
    -D) grep -qx -- "$*" "$f" 2>/dev/null || exit 1
        awk -v r="$*" '!done && $0 == r { done = 1; next } { print }' "$f" > "$f.new"; mv "$f.new" "$f" ;;
    -I) shift; { echo "$*"; cat "$f" 2>/dev/null; } > "$f.new"; mv "$f.new" "$f" ;;
    -A) echo "$*" >> "$f" ;;
esac
S
cp "$F/bin/iptables" "$F/bin/ip6tables"
cat > "$F/bin/ip" <<'S'
#!/bin/bash
# a fake ip: routes as "<to> <rest> table <t>" lines, routing rules as "<prio>:\t<rest>"
v=4; case $1 in -4) shift ;; -6) v=6; shift ;; esac
o=$1 c=$2; shift 2
f=$FW/ip$v.$o; touch "$f"
case $o.$c in
    route.show) grep -- "^$1 .* table $3\$" "$f" || true ;;
    route.replace|route.del) n=$#; tb=${!n}; grep -v -- "^$1 .* table $tb\$" "$f" > "$f.new"; mv "$f.new" "$f"
        [ $c = replace ] && echo "$*" >> "$f" ;;
    rule.show) echo "0:	from all lookup local"; cat "$f" ;;
    rule.add) p=$2; shift 2; printf '%s:\t%s\n' "$p" "$*" >> "$f" ;;
    rule.del) grep -v "^$2:" "$f" > "$f.new"; mv "$f.new" "$f" ;;
esac
S
printf '#!/bin/sh\ncat "$FW/prop" 2>/dev/null\n' > "$F/bin/getprop"
chmod +x "$F/bin/"*
keeper() { FW=$F/fw HMGAPI_PROCSYS=$F/proc PATH=$F/bin:$PATH $UNIT_SH "$D/state/net.sh" "$@" 2>&1; }
ipt() { FW=$F/fw PATH=$F/bin:$PATH iptables "$@"; }
first() { ipt "$@" | grep -m1 '^-A ' || true; }
# the stock firewall: INPUT jumps to WLIST_IN, which sends each interface to its own whitelist
stockfw() { ipt -N WL_WLAN0_IN; ipt -A WL_WLAN0_IN -p udp --dport 53 -j ACCEPT; ipt -N WLIST_IN; ipt -A WLIST_IN -i wlan0 -j WL_WLAN0_IN; ipt -A INPUT -j WLIST_IN; }
stockfw

echo running > "$F/fw/prop"
check "waits while the stock firewall loads" "" "$(keeper once)"
check "... nothing in yet" no "$([ -f "$D/state/net.applied" ] && echo yes || echo no)"
echo stopped > "$F/fw/prop"
log=$(keeper once)
has "rules in" "in: 60 firewall rule(s), 1 route(s), 1 routing rule(s), 1 setting(s)" "$log"
hasnt "nothing refused" "refused" "$log"
check "HMG-API's chain first in INPUT" "-A INPUT -j HMGAPI_IN" "$(first -S INPUT)"
check "the stock chain still there after it" "-A INPUT -j WLIST_IN" "$(ipt -S INPUT | grep -- '-A' | sed -n 2p)"
check "every rule in HMGAPI_IN" 28 "$(ipt -S HMGAPI_IN | grep -c '^-A')"
check "IPv6 too" 29 "$(FW=$F/fw PATH=$F/bin:$PATH ip6tables -S HMGAPI_IN | grep -c '^-A')"
check "nat chain first in POSTROUTING" "-A POSTROUTING -j HMGAPI_POST" "$(first -t nat -S POSTROUTING)"
check "sysctl set" 1 "$(cat "$F/proc/net/ipv4/ip_forward")"
check "... its stock value kept" 0 "$(cat "$D/state/net-stock/net:ipv4:ip_forward")"
check "route in" "10.8.0.0/24 dev tun0 table 100" "$(cat "$F/fw/ip4.route")"
has "routing rule in" "1000:	fwmark 16 table 100" "$(cat "$F/fw/ip4.rule")"
check "all there: a check is quiet" "" "$(keeper once)"

ipt -I INPUT 1 -j WLIST_IN   # the stock firewall jumps to its own chain first again
has "moved behind the stock chain: put back" "firewall rules put back" "$(keeper once)"
check "... first again" "-A INPUT -j HMGAPI_IN" "$(first -S INPUT)"
# another tool's chain that only accepts may go first: HMG-API's rules still see everything else
ipt -N OTHER_CP_IN; ipt -A OTHER_CP_IN -i wlan0 -p tcp --dport 7000 -j ACCEPT; ipt -I INPUT 1 -j OTHER_CP_IN
check "an accept-only chain ahead: left there, nothing redone" "" "$(keeper once)"
check "... it stays first" "-A INPUT -j OTHER_CP_IN" "$(first -S INPUT)"
ipt -I INPUT 1 -i wlan0 -j DROP
has "a drop ahead: HMG-API's chain first again" "firewall rules put back" "$(keeper once)"
check "... first" "-A INPUT -j HMGAPI_IN" "$(first -S INPUT)"
rm -rf "$F/fw/4" "$F/fw/6"   # the stock firewall flushed everything
stockfw
has "flushed: put back" "firewall rules put back" "$(keeper once)"
check "... all of them" 28 "$(ipt -S HMGAPI_IN | grep -c '^-A')"
: > "$F/fw/ip4.route"   # a Wi-Fi restart took the route
has "route put back" "route 10.8.0.0/24 dev tun0 (table 100) in" "$(keeper once)"

: > "$D/mods/vpn/.disabled"
eng publish-state
has "a mod off: the keeper stays to take its asks out" "hmgapi net " "$(cat "$D/state/services.txt")"
log=$(keeper once)
has "changed: everything out, the rest back in" "everything out" "$log"
has "... and in again" "in: 57 firewall rule(s), 0 route(s), 0 routing rule(s), 0 setting(s)" "$log"
check "sysctl back to stock" 0 "$(cat "$F/proc/net/ipv4/ip_forward")"
check "nat chain gone" "" "$(first -t nat -S POSTROUTING)"
check "route gone" "" "$(cat "$F/fw/ip4.route")"
hasnt "routing rule gone" "1000:" "$(cat "$F/fw/ip4.rule")"

: > "$D/mods/cp/.disabled"
eng publish-state
keeper once >/dev/null
check "nothing wanted: HMG-API's chain gone" "-A INPUT -j WLIST_IN" "$(first -S INPUT)"
check "... and nothing applied" no "$([ -f "$D/state/net.applied" ] && echo yes || echo no)"
eng publish-state
check "no keeper once nothing is in" "" "$(grep '^hmgapi net ' "$D/state/services.txt")"

rm "$D/mods/cp/.disabled" "$D/mods/vpn/.disabled"
eng publish-state
keeper once >/dev/null
keeper down >/dev/null
check "down (the uninstaller): INPUT stock" "-A INPUT -j WLIST_IN" "$(first -S INPUT)"
check "... no HMG-API chains" "" "$(ls "$F/fw/4/filter" "$F/fw/4/nat" "$F/fw/6/filter" | grep HMGAPI || true)"
check "... sysctl stock" 0 "$(cat "$F/proc/net/ipv4/ip_forward")"

# ---- the Car Report's network section (canned tool output; the stock firewall from the car's init)
B=$F/rbin
mkdir -p "$B" "$R/system/etc" "$R/proc/sys/net/ipv4"
printf 'service iptrules /system/bin/sh /system/etc/iptrules.sh\n    oneshot\nservice ueventd /sbin/ueventd\n' > "$R/init.daudio.rc"
printf '#!/system/bin/sh\niptables -P INPUT DROP\niptables -N WL_NCM0_IN\n' > "$R/system/etc/iptrules.sh"
echo 0 > "$R/proc/sys/net/ipv4/ip_forward"
cat > "$B/iptables" <<'S'
#!/bin/sh
case "$*" in *-S*) printf -- '-P INPUT DROP\n-N WLIST_IN\n-N WL_NCM0_IN\n-A WL_NCM0_IN -p tcp --dport 7000 -j ACCEPT\n' ;; *) echo "Chain INPUT (policy DROP 12 packets, 3400 bytes)" ;; esac
S
cp "$B/iptables" "$B/ip6tables"
cat > "$B/ip" <<'S'
#!/bin/sh
case "$*" in
    "-o addr show") printf '1: wlan0    inet 192.168.0.1/24 brd 192.168.0.255 scope global wlan0\n4: rmnet0    inet 100.77.12.34/30 scope global rmnet0\n1: wlan0    inet6 fe80::21a:2bff:fe3c:4d5e/64 scope link\n4: rmnet0    inet6 2600:1010:b04e:1234:5678:9abc:def0:1234/64 scope global\n' ;;
    "-o link show") printf '1: wlan0: <BROADCAST,UP> mtu 1500 state UP link/ether 00:1a:2b:3c:4d:5e brd ff:ff:ff:ff:ff:ff\n' ;;
    *) echo "default via 100.77.12.33 dev rmnet0" ;;
esac
S
printf '#!/bin/sh\necho "[  612.1] NFLOG_IN IN=wlan0 OUT= MAC=33:33:00:00:00:01:aa:bb:cc:dd:ee:ff:86:dd SRC=fe80:0000:0000:0000:1c2d:3eff:fe4f:5a6b DST=fe80:0000:0000:0000:021a:2bff:fe3c:4d5e PROTO=TCP SPT=49152 DPT=5001"\necho "[  613.0] wlan0: associated"\n' > "$B/dmesg"
chmod +x "$B/"*
# the keeper's log goes in too: a line with addresses in it (a mod's route, a phone) is scrubbed like the rest
mkdir -p "$D/logs/hmgapi"
printf '11-14 08:00:01 route 203.0.113.77 via wlan0\n11-14 08:00:02 peer 0c:1d:2e:3f:4a:5b fe80::e1d:2eff:fe3f:4a5b\n11-14 08:00:03 hotspot 192.168.0.1 up\n' > "$D/logs/hmgapi/net.log"
set +e
out=$(PATH=$B:$PATH HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 HMGAPI_PRODUCT=CEEV.USA.0000.V015 HMGAPI_PROPS="init.svc.iptrules=stopped" java -cp "$CP" hmgapi.engine.Engine report 2>&1); rc=$?
set -e
check "report runs" 0 $rc
has "report: network summary for the owner" "network: firewall iptrules (stopped); IPv4 input DROP, 2 chains" "$out"
j=$(cat "$D"/public/reports/*.json)
has "report: the stock firewall script" "iptables -N WL_NCM0_IN" "$j"
has "report: the rules" "-A WL_NCM0_IN -p tcp --dport 7000 -j ACCEPT" "$j"
has "report: the drop, its port kept" "DPT=5001" "$j"
hasnt "report: no MAC from the drop" "aa:bb:cc" "$j"
hasnt "report: no MAC from links" "00:1a:2b" "$j"
hasnt "report: no phone address (link-local from its MAC)" "1c2d" "$j"
has "report: link-local kept as such" "SRC=fe80::[removed]" "$j"
hasnt "report: no modem IPv4" "100.77.12.34" "$j"
has "report: ... cut to its network" "inet 100.77.x.x/30" "$j"
hasnt "report: no modem IPv6" "b04e" "$j"
has "report: hotspot address kept" "inet 192.168.0.1/24" "$j"
has "report: the keeper's log is there" "hotspot 192.168.0.1 up" "$j"
hasnt "report: keeper log, no public IPv4" "203.0.113.77" "$j"
has "report: keeper log, ... cut to its network" "route 203.0.x.x via wlan0" "$j"
hasnt "report: keeper log, no MAC" "0c:1d:2e" "$j"
hasnt "report: keeper log, no address made from a MAC" "4a5b" "$j"
has "report: sysctl" "\"net.ipv4.ip_forward\": \"0\"" "$j"
has "report: a car HMG-API has no name for goes by its model code" "# Car Report: CEEV USA" "$(cat "$D"/public/reports/*.md)"
has "... in the issue's title too (never \"null\")" "title=Car+Report%3A+CEEV+USA" "$out"
has "report: markdown has the network" "## Network" "$(cat "$D"/public/reports/*.md)"
exit $fail

package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Home Wi-Fi, and mods kept off the car's cellular link.
 *
 * Cellular: no mod's traffic goes over the car's cellular link (rmnet), DNS included. Its
 * apps (by uid) and its services (by group: hmgapid runs them with SERVICE_GID or
 * HOME_GID as their effective group, state/service-gids.txt) are refused there by the
 * network keeper (net.sh, chain HMGAPI_CELL), and their apps' DNS goes through wlan0 in
 * netd, which has no servers unless home Wi-Fi is up. The owner can let one mod use
 * cellular, at their own risk (hmgapi.json overrides.allow_cellular).
 *
 * Home Wi-Fi (the HMG-API app's switch, settings/allow/home_wifi, or hmgapi.json "home_wifi": true;
 * off by default): while the car is parked (car events), the keeper turns the projection hotspot
 * off and the car's own Wi-Fi client on (the stock client feature, android.hardware.wifi, comes on
 * with the switch: one file, a restart), which joins the networks in Android's own Wi-Fi config
 * (the HMG-API app adds and forgets them through hmgapid wifi). When the car is on again, the
 * client goes off and the hotspot comes back as it was. A client next to the hotspot (a second
 * interface) didn't hold up on the US EV6's driver (2026-11-18), the stock path does. While
 * joined, Android makes Wi-Fi its default; the keeper puts the car's own default route and DNS
 * back on rmnet0 (HKMC's mobile_hipri stays up for that), so only mods that ask ("wifi":
 * {"home": true}) use it: their traffic is marked (MARK) and routed through table TABLE. Home
 * Wi-Fi is the way HMG-API is meant to check for and fetch updates while parked.
 */
final class HomeWifi {
    private HomeWifi() {}

    static final int SERVICE_GID = 2950, HOME_GID = 2951, TABLE = 95, PRIORITY = 900;
    static final String MARK = "0x2951", IFACE = "wlan0";
    static final String SCRIPT_PATH = "/data/hmgapi/state/wifi.sh";

    /** the owner's switch: the HMG-API app (settings/allow/home_wifi), or hmgapi.json "home_wifi" */
    static boolean on(Engine e) {
        return new File(e.data, "settings/allow/home_wifi").isFile() || (e.hmgapi != null && Boolean.TRUE.equals(e.hmgapi.get("home_wifi")));
    }

    @SuppressWarnings("unchecked")
    static List<Object> allowed(Map<String, Object> hmgapi) {
        Object ov = hmgapi == null ? null : hmgapi.get("overrides");
        Object l = ov instanceof Map ? ((Map<String, Object>) ov).get("allow_cellular") : null;
        return l instanceof List ? (List<Object>) l : new ArrayList<Object>();
    }

    /** mod.json "wifi": {"home": true} */
    @SuppressWarnings("unchecked")
    static boolean asks(Map<String, Object> mj) {
        Object w = mj == null ? null : mj.get("wifi");
        return w instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) w).get("home"));
    }

    /** the owner's switch as HMG-API's own change set (id "hmgapi"): the stock client feature, in state/core */
    static Engine.Mod coreMod(Engine e) {
        if (!on(e)) return null;
        Engine.Mod m = new Engine.Mod();
        m.id = "hmgapi";
        m.dir = new File(e.stateDir, "core");
        m.dir.mkdirs();
        m.json = new LinkedHashMap<String, Object>();
        m.json.put("id", "hmgapi");
        Map<String, Object> wifi = new LinkedHashMap<String, Object>();
        wifi.put("client", Boolean.TRUE);
        m.json.put("wifi", wifi);
        m.patches = new LinkedHashMap<String, Object>();
        m.version = Engine.HMGAPI_VERSION;
        m.set = "";
        return m;
    }

    /** what each enabled mod gets: "home", "block" or "allow" */
    static Map<String, String> modes(Engine e, Map<String, Map<String, Object>> json, Set<String> on) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        List<Object> allow = allowed(e.hmgapi);
        boolean home = on(e);
        for (String id : on) {
            if (allow.contains(id)) out.put(id, "allow");
            else out.put(id, home && asks(json.get(id)) ? "home" : "block");
        }
        return out;
    }

    /**
     * state/mods-net.txt (the network keeper's "mods" lines) and state/service-gids.txt
     * (hmgapid: each mod's services' group); true when the network keeper has to run for it.
     */
    static boolean publish(Engine e, Map<String, Map<String, Object>> json, Set<String> on) throws Exception {
        Map<String, String> modes = modes(e, json, on);
        StringBuilder net = new StringBuilder(), gids = new StringBuilder();
        boolean home = on(e);
        for (Map.Entry<String, String> m : modes.entrySet()) {
            String id = m.getKey(), mode = m.getValue();
            net.append(mode).append(' ').append(id).append('\n');
            gids.append(id).append(' ').append("allow".equals(mode) ? 0 : "home".equals(mode) ? HOME_GID : SERVICE_GID).append('\n');
            if ("allow".equals(mode)) {
                e.out.add("WARNING: " + id + ": may use the car's cellular link (overrides.allow_cellular): at your own risk, as its"
                        + " traffic and DNS lookups then go over the car's mobile data");
            } else if (!home && asks(json.get(id))) {
                e.out.add("note     " + id + ": asks for home Wi-Fi, which is off (HMG-API settings): it has no internet until it's on");
            }
        }
        if (home) net.append("homewifi on\n");
        ModRuntime.writeIfChanged(new File(e.stateDir, "mods-net.txt"), net.toString());
        ModRuntime.writeIfChanged(new File(e.stateDir, "service-gids.txt"), gids.toString());
        return net.length() > 0 || new File(e.stateDir, "mods-net.applied").isFile();
    }

    /** the home Wi-Fi keeper's services.txt line, or null when it's off (its script written or removed) */
    static String service(Engine e) throws Exception {
        File script = new File(e.stateDir, "wifi.sh");
        if (!on(e)) {
            script.delete();
            new File(e.data, "public/wifi.json").delete();
            return null;
        }
        if (!(script.isFile() && Engine.read(script).equals(SCRIPT))) {
            File tmp = new File(e.stateDir, ".wifi.sh.tmp");
            Engine.write(tmp, SCRIPT);
            if (!tmp.renameTo(script)) throw new java.io.IOException("can't write wifi.sh");
        }
        return "hmgapi wifi root android yes script " + SCRIPT_PATH + " - " + Integer.toHexString(SCRIPT.hashCode());
    }

    /** the plan's lines */
    static List<String> describe(Engine e, Map<String, Map<String, Object>> json, Set<String> on) {
        List<String> out = new ArrayList<String>();
        boolean home = on(e);
        if (home != new File(e.stateDir, "wifi.sh").isFile()) {
            out.add("change   home Wi-Fi -> " + (home ? "on" : "off") + " [hmgapi]");
        }
        out.add("home Wi-Fi  " + (home ? "on: while the car is off, the hotspot off and the car's Wi-Fi client on the saved networks" : "off"));
        for (Map.Entry<String, String> m : modes(e, json, on).entrySet()) {
            String mode = m.getValue();
            out.add("network  " + ("home".equals(mode) ? "home Wi-Fi only (no cellular)" : "allow".equals(mode)
                    ? "may use cellular (overrides.allow_cellular, at your own risk)" : "no cellular" + (asks(json.get(m.getKey())) ? " (home Wi-Fi is off)" : ""))
                    + " [" + m.getKey() + "]");
        }
        return out;
    }

    /**
     * HMG-API's home Wi-Fi keeper, run by hmgapid as root (service "hmgapi wifi") once
     * Android is up; mksh and the unit's toolbox only. hmgapid vehicle reads "parked" and "power"
     * (car events), WifiAp switches the hotspot and the car's own client, hmgapid wpa talks to that
     * client's wpa_supplicant.
     */
    static final String SCRIPT = Network.lines(
        "# HMG-API's home Wi-Fi keeper (written by the engine: hmgapi.engine.HomeWifi). hmgapid runs it",
        "# as root (service \"hmgapi wifi\"). While the car is off (30 s, or parked), the hotspot goes off and",
        "# the car's own Wi-Fi client joins the saved networks; when the car is on, the client goes off and",
        "# the hotspot comes back as it was. While joined, the car's own traffic stays on mobile data; only",
        "# the mods that asked (and HMG-API) go through home Wi-Fi.",
        "#   sh wifi.sh                 keep (the service)",
        "#   sh wifi.sh down            the client off, the hotspot back (the uninstaller, home Wi-Fi off)",
        "#   sh wifi.sh now [minutes] | back   home Wi-Fi on request (the car in P; 0: until out of P), and back (hmgapid wifi)",
        "#   sh wifi.sh saved | seen | join <network> | forget <network>   for HMG-API's app (hmgapid wifi);",
        "#                              join's key comes in HMGAPI_WIFI_PSK, never on the command line",
        "set -f",
        "X=${HMGAPI_TEST_ROOT:-}   # off-car tests only",
        "W=$X/data/hmgapi/wifi I=" + IFACE + " T=" + TABLE,
        "NET=$X/data/misc/wifi/wpa_supplicant.conf   # Android's own Wi-Fi config: the saved networks",
        "WPA=$X/data/misc/wifi/sockets/$I            # the car's own client's control socket, while it runs",
        "PUB=$X/data/hmgapi/public/wifi.json",
        "JAR=/system/framework/hmgapi-hook.jar",
        "nl='",
        "'",
        "tab='\t'",
        "say() { echo \"$(date '+%m-%d %H:%M:%S') $*\"; }",
        "wpa() { hmgapid wpa $WPA \"$1\" 2>/dev/null; }",
        "field() { while IFS== read -r k v; do [ \"$k\" = \"$1\" ] && { echo \"$v\"; return; }; done; }",
        "status() { echo \"{\\\"state\\\": \\\"$1\\\", \\\"rssi\\\": ${2:-0}, \\\"freq\\\": ${3:-0}}\" > $PUB.tmp && chmod 644 $PUB.tmp && mv $PUB.tmp $PUB; }",
        "reading() { hmgapid vehicle \"$1\" 2>/dev/null | while read -r k v; do [ \"$k\" = \"$1\" ] && echo \"$v\"; done; }",
        "tool() { CLASSPATH=$JAR app_process /system/bin hmgapi.engine.WifiAp \"$@\" 2>&1; }",
        "boot() { cat $X/proc/sys/kernel/random/boot_id 2>/dev/null; }",
        "",
        "# ---- the saved networks: Android's own config (the network={...} blocks; the rest is left as it is)",
        "ssid_of() { while IFS= read -r l; do case $l in *ssid=\\\"*) l=${l#*ssid=\\\"}; echo \"${l%\\\"}\"; return ;; esac; done; }",
        "saved() {   # each saved network's name",
        "    b=",
        "    [ -f $NET ] && while IFS= read -r l; do",
        "        case $l in network=*) b= ;; esac",
        "        b=\"$b$l$nl\"",
        "        case $l in \"}\") echo \"$b\" | ssid_of ;; esac",
        "    done < $NET",
        "}",
        "without() {   # the config without the network named $1",
        "    b= in= keep=1",
        "    while IFS= read -r l; do",
        "        case $l in network=*) in=1 b= keep=1 ;; esac",
        "        if [ -z \"$in\" ]; then echo \"$l\"; continue; fi",
        "        case $l in *ssid=\\\"*) [ \"$(echo \"$l\" | ssid_of)\" = \"$1\" ] && keep= ;; esac",
        "        b=\"$b$l$nl\"",
        "        case $l in \"}\") [ -n \"$keep\" ] && echo -n \"$b\"; in= b= ;; esac",
        "    done < $NET",
        "}",
        "safe() {   # a name or key that can't break the config: no quotes, backslashes or line breaks",
        "    case $1 in *\\\"*|*\\\\*|*\"$nl\"*) return 1 ;; esac",
        "    return 0",
        "}",
        "client_mode() { [ -f $W/mode ] && [ \"$(cat $W/mode)\" = \"$(boot)\" ]; }",
        "put_net() {   # $1: the new config's text; Android's owner and mode kept",
        "    umask 077",
        "    echo -n \"$1\" > $NET.hmgapi",
        "    chown wifi:wifi $NET.hmgapi 2>/dev/null; chmod 660 $NET.hmgapi",
        "    mv $NET.hmgapi $NET",
        "}",
        "join() {",
        "    s=$1 k=$HMGAPI_WIFI_PSK",
        "    [ -n \"$s\" ] && [ ${#s} -le 32 ] && safe \"$s\" || { echo \"that network name can't be used\"; return 2; }",
        "    safe \"$k\" || { echo \"that key can't be used (quotes, backslashes)\"; return 2; }",
        "    if [ -n \"$k\" ] && [ ${#k} -lt 8 -o ${#k} -gt 63 ]; then echo \"a Wi-Fi key is 8 to 63 characters\"; return 2; fi",
        "    client_mode && { echo \"home Wi-Fi is on right now (parked): add networks while the car is on\"; return 1; }",
        "    [ -f $NET ] || printf 'ctrl_interface=wlan0\\nupdate_config=1\\n' > $NET",
        "    c=$(without \"$s\")",
        "    c=\"$c${nl}network={$nl\tssid=\\\"$s\\\"$nl\"",
        "    if [ -n \"$k\" ]; then c=\"$c\tpsk=\\\"$k\\\"$nl\tkey_mgmt=WPA-PSK$nl\"; else c=\"$c\tkey_mgmt=NONE$nl\"; fi",
        "    c=\"$c\tpriority=1$nl}$nl\"",
        "    put_net \"$c\"",
        "    echo \"saved: $s\"",
        "}",
        "forget() {",
        "    client_mode && { echo \"home Wi-Fi is on right now (parked): forget networks while the car is on\"; return 1; }",
        "    [ -f $NET ] || { echo \"forgotten: $1\"; return 0; }",
        "    put_net \"$(without \"$1\")$nl\"",
        "    echo \"forgotten: $1\"",
        "}",
        "seen() {   # the networks home Wi-Fi saw last time it was on: \"<MHz>\\t<dBm>\\t<flags>\\t<name>\"",
        "    cat $W/seen 2>/dev/null",
        "}",
        "",
        "# ---- while joined: the car's own traffic on mobile data, the mods that asked on home Wi-Fi",
        "route_up() {",
        "    gw=",
        "    for r in $(ip route show dev $I 2>/dev/null | while read -r a b c rest; do [ \"$a\" = default ] && [ \"$b\" = via ] && echo $c; done); do gw=$r; done",
        "    [ -n \"$gw\" ] || gw=$(cat $W/lease 2>/dev/null | while read -r g rest; do echo $g; done)",
        "    [ -n \"$gw\" ] || return 1",
        "    for _ in 1 2 3 4 5 6 7 8; do ip route del default dev $I 2>/dev/null || break; done   # Android's, in main",
        "    ip route replace default via $gw dev $I table $T",
        "    for r in $(ip route show dev $I 2>/dev/null | while read -r n rest; do case $n in (*/*) echo $n ;; esac; done); do",
        "        ip route replace $r dev $I table $T",
        "    done",
        "    d1=$(getprop dhcp.$I.dns1) d2=$(getprop dhcp.$I.dns2)",
        "    [ -n \"$d1\" ] || d1=$gw",
        "    ndc resolver setdefaultif rmnet0 >/dev/null 2>&1   # the car's own lookups: its carrier's DNS, as before",
        "    ndc resolver setifdns $I '' $d1 $d2 >/dev/null 2>&1",
        "    if [ \"$(cat $W/lease 2>/dev/null)\" != \"$gw $d1\" ]; then",
        "        for r in $(cat $W/dnsroutes 2>/dev/null); do ip route del $r dev $I 2>/dev/null; done",
        "        : > $W/dnsroutes",
        "        for d in $d1 $d2; do   # mods' lookups through wlan0 (netd): via the gateway, unless it is the gateway",
        "            [ \"$d\" = \"$gw\" ] && continue",
        "            ip route replace $d/32 via $gw dev $I metric 1000 && echo $d/32 >> $W/dnsroutes",
        "        done",
        "        iptables -t nat -N HMGAPI_HOMEDNS 2>/dev/null; iptables -t nat -F HMGAPI_HOMEDNS",
        "        iptables -t nat -C OUTPUT -j HMGAPI_HOMEDNS 2>/dev/null || iptables -t nat -I OUTPUT 1 -j HMGAPI_HOMEDNS",
        "        for p in udp tcp; do iptables -t nat -A HMGAPI_HOMEDNS -m owner --gid-owner " + HOME_GID + " -p $p --dport 53 -j DNAT --to-destination $d1; done",
        "        echo \"$gw $d1\" > $W/lease",
        "        say \"home Wi-Fi up: the mods that asked go through it; the car stays on mobile data\"",
        "    fi",
        "}",
        "route_down() {",
        "    ip route flush table $T 2>/dev/null",
        "    iptables -t nat -F HMGAPI_HOMEDNS 2>/dev/null",
        "    for r in $(cat $W/dnsroutes 2>/dev/null); do ip route del $r dev $I 2>/dev/null; done",
        "    rm -f $W/dnsroutes $W/lease",
        "}",
        "",
        "# ---- switching",
        "client_on() {",
        "    m=$(tool stop | while read -r l; do case $l in ([012]) echo $l ;; esac; done)",
        "    [ \"$m\" = 1 ] || [ \"$m\" = 2 ] || m=2   # the hotspot wasn't on: the projection one when it comes back",
        "    echo $m > $W/apmode",
        "    boot > $W/mode",
        "    r=$(tool client on)",
        "    if manual && [ \"$pw\" = on ]; then say \"on request: the hotspot off, the car's Wi-Fi client on ($r)\"",
        "    else say \"the car off: the hotspot off, the car's Wi-Fi client on ($r)\"; fi",
        "}",
        "client_off() {",
        "    route_down",
        "    r=$(tool client off)",
        "    m=$(cat $W/apmode 2>/dev/null); [ \"$m\" = 1 ] || m=2",
        "    a=$(tool start $m)",
        "    rm -f $W/mode",
        "    say \"$1: the car's Wi-Fi client off, the hotspot back ($r; $a)\"",
        "}",
        "# the car's own Wi-Fi client, not switched on by HMG-API (its Wi-Fi settings, Tilepad): left alone",
        "stock_client() { ! client_mode && case $(getprop init.svc.wpa_supplicant)$(getprop init.svc.p2p_supplicant) in *running*) true ;; *) false ;; esac; }",
        "",
        "now() { read -r u rest < $X/proc/uptime; echo ${u%.*}; }",
        "# home Wi-Fi's moment: the car off (its screen goes dark with it, and the stock apps end projection",
        "# then) for 30 s, or parked (the car events' rule), whichever comes first; a quick off and on again",
        "# (a stop for a moment) doesn't switch anything",
        "off_long() {",
        "    [ \"$p\" = 1 ] && return 0",
        "    case $pw in on|\"\") rm -f $W/offsince; return 1 ;; esac",
        "    t=$(now) b=$(boot)",
        "    [ -f $W/offsince ] && read -r ob ot < $W/offsince && [ \"$ob\" = \"$b\" ] || { echo \"$b $t\" > $W/offsince; return 1; }",
        "    [ $((t - ot)) -ge 30 ]",
        "}",
        "",
        "# on request (the app's \"Connect now\", later HMG-API's updater): until \"back\", out of P, a restart,",
        "# or the time is up (0 minutes: no time limit)",
        "manual() {",
        "    [ -f $W/manual ] && read -r mb mu < $W/manual || return 1",
        "    [ \"$mb\" = \"$(boot)\" ] && { [ \"$mu\" = 0 ] || [ \"$(now)\" -lt \"$mu\" ] 2>/dev/null; } && return 0",
        "    rm -f $W/manual; return 1",
        "}",
        "now_on() {",
        "    m=${1:-30}",
        "    case $m in [0-9]|[0-9][0-9]|[0-9][0-9][0-9]) ;; *) echo \"minutes: 1 to 240, or 0 for no time limit\"; return 2 ;; esac",
        "    [ \"$m\" -le 240 ] || { echo \"minutes: 1 to 240, or 0 for no time limit\"; return 2; }",
        "    [ -n \"$(saved)\" ] || { echo \"no saved networks: add one first\"; return 1; }",
        "    [ \"$(reading gear.park)\" = 1 ] || { echo \"shift to P first: home Wi-Fi turns the car's Wi-Fi for CarPlay and Android Auto off\"; return 1; }",
        "    while case $m in 0?*) true ;; *) false ;; esac; do m=${m#0}; done   # 030 is 30, not octal",
        "    mkdir -p $W",
        "    if [ \"$m\" = 0 ]; then",
        "        echo \"$(boot) 0\" > $W/manual",
        "        echo \"home Wi-Fi on until you shift out of P (wireless CarPlay and Android Auto are off meanwhile)\"",
        "    else",
        "        echo \"$(boot) $(($(now) + m * 60))\" > $W/manual",
        "        echo \"home Wi-Fi on for $m minutes (wireless CarPlay and Android Auto are off meanwhile); it ends sooner if you shift out of P\"",
        "    fi",
        "}",
        "back() {",
        "    rm -f $W/manual",
        "    echo \"back to the car's Wi-Fi within a few seconds\"",
        "}",
        "",
        "keep() {",
        "    mkdir -p $W",
        "    # HMG-API's client from before a restart (Android remembers \"Wi-Fi on\"): the hotspot back first",
        "    if [ -f $W/mode ] && ! client_mode; then",
        "        boot > $W/mode",
        "        client_off \"after a restart while home Wi-Fi was on\"",
        "    fi",
        "    if stock_client; then status stock; return; fi",
        "    pw=$(reading power) p=$(reading parked)",
        "    if client_mode; then",
        "        if [ \"$pw\" = on ]; then",
        "            rm -f $W/offsince",
        "            if manual; then",
        "                if [ \"$(reading gear.park)\" != 1 ]; then rm -f $W/manual; client_off \"out of P\"; status waiting; return; fi",
        "            else",
        "                client_off \"back to the car's Wi-Fi (the car is on)\"; status waiting; return",
        "            fi",
        "        fi",
        "        st=$(wpa STATUS)",
        "        if [ \"$(echo \"$st\" | field wpa_state)\" = COMPLETED ] && route_up; then",
        "            rssi=$(wpa SIGNAL_POLL | field RSSI)",
        "            status up \"$rssi\" \"$(echo \"$st\" | field freq)\"",
        "        else",
        "            [ -f $W/lease ] && { route_down; say \"home Wi-Fi lost\"; }",
        "            status searching",
        "        fi",
        "        wpa SCAN_RESULTS | while IFS=$tab read -r b f sig fl name; do",
        "            case $f in [0-9]*) [ -n \"$name\" ] && echo \"$f$tab$sig$tab$fl$tab$name\" ;; esac",
        "        done > $W/seen.tmp && [ -s $W/seen.tmp ] && mv $W/seen.tmp $W/seen",
        "        return",
        "    fi",
        "    if off_long || { manual && [ \"$(reading gear.park)\" = 1 ]; }; then",
        "        if [ -n \"$(saved)\" ]; then client_on; status searching; return; fi",
        "        status nonetworks; return",
        "    fi",
        "    status waiting",
        "}",
        "down() { client_mode && client_off \"home Wi-Fi off\"; route_down; status off; }",
        "umask 077",
        "case $1 in",
        "    down) down; exit 0 ;;",
        "    saved) saved; exit 0 ;;",
        "    seen) seen; exit 0 ;;",
        "    now) now_on \"$2\"; exit $? ;;",
        "    back) back; exit 0 ;;",
        "    join) join \"$2\"; exit $? ;;",
        "    forget) forget \"$2\"; exit $? ;;",
        "esac",
        "trap 'down; say stopped; exit 0' TERM   # never left with the hotspot off",
        "while :; do",
        "    keep",
        "    sleep 10",
        "done");
}

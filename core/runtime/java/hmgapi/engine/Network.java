package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A mod's "network": firewall rules, routes, routing rules and network settings, which
 * HMG-API keeps in place for it (no mod runs iptables itself):
 *
 *   "network": {
 *     "rules": [{"chain": "input", "in": "hotspot", "proto": "tcp+udp", "ports": [7000, "5000-5001"],
 *                "both_ways": true, "action": "accept"}],
 *     "routes": [{"to": "10.8.0.0/24", "dev": "tun0", "via": "10.8.0.1", "metric": 10, "table": 100}],
 *     "route_rules": [{"priority": 1000, "fwmark": 16, "table": 100}],
 *     "sysctl": {"net.ipv4.ip_forward": "1"}
 *   }
 *
 * rules: "table" filter (default), nat (IPv4 only) or mangle; "chain" input, output,
 * forward (filter), prerouting, postrouting, output (nat), prerouting, output,
 * postrouting (mangle); "in" / "out": an interface ("rmnet+" for all of them) or
 * "hotspot" (wlan0, and only from/to the hotspot's addresses: 192.168.0.0/24 and
 * link-local fe80::/10); "from" / "to": "hotspot" or an address/prefix; "ip" 4, 6 or
 * both (default: what the addresses say, else both); "proto" tcp, udp, tcp+udp, icmp,
 * icmpv6 or all; "ports" (destination) and "source_ports": numbers or "a-b";
 * "both_ways": also match the ports as the source (as the stock wired CarPlay rules
 * do); "icmp_type"; "state": [new, established, related, invalid]; "action" accept,
 * drop, reject, return, log:<prefix>, mark:<n> (mangle), masquerade, snat:<address>
 * (nat postrouting), dnat:<address[:port]>, redirect:<port> (nat prerouting/output);
 * "when": settings.<key> as for parts.
 *
 * Each rule goes in HMG-API's own chain (HMGAPI_IN, HMGAPI_OUT, ...), which the stock
 * chain jumps to first, so a mod's rule is checked before the car's own and taking
 * every rule out again is exact. Routes: "to", "dev", "via", "metric", "table" (main
 * or 1-252). route_rules: "priority" (100-32765), "fwmark", "from", "to", "iif",
 * "table". sysctl: net.* keys, whole numbers; the stock value comes back when no mod
 * asks any more.
 *
 * Accept/drop rules on the hotspot (input from it, output to it) need nothing more.
 * Anything else (another interface, forward, nat, mangle, routes, sysctl) can reach
 * the car's own network (the modem, the internal link), so it is left out with a
 * warning until the owner lists the mod in hmgapi.json overrides.allow_network.
 * Mods' rules go in mod order, or overrides.network_order first; where two mods match
 * the same traffic differently, the plan says whose rule comes first.
 *
 * The enabled mods' asks go to state/net.rules; HMG-API's network keeper
 * (state/net.sh, run by hmgapid as the service "hmgapi net") applies them, checks them
 * every 10 s, and puts back what the stock firewall or a Wi-Fi restart took away.
 */
final class Network {
    private Network() {}

    static final String HOTSPOT_IF = "wlan0", HOTSPOT4 = "192.168.0.0/24", HOTSPOT6 = "fe80::/10";
    static final String SCRIPT_PATH = "/data/hmgapi/state/net.sh";
    static final int MAX_LINES = 600;
    static final String IFACE = "[a-z][a-z0-9_-]{0,13}\\+?";

    /** our chain for table and stock chain */
    static String chain(String table, String chain) {
        if ("filter".equals(table)) return "input".equals(chain) ? "HMGAPI_IN" : "output".equals(chain) ? "HMGAPI_OUT" : "forward".equals(chain) ? "HMGAPI_FWD" : null;
        if ("nat".equals(table)) return "prerouting".equals(chain) ? "HMGAPI_PRE" : "postrouting".equals(chain) ? "HMGAPI_POST" : "output".equals(chain) ? "HMGAPI_NOUT" : null;
        if ("mangle".equals(table)) return "prerouting".equals(chain) ? "HMGAPI_MPRE" : "output".equals(chain) ? "HMGAPI_MOUT" : "postrouting".equals(chain) ? "HMGAPI_MPOST" : null;
        return null;
    }

    /** one item a mod asks for, expanded */
    static final class Item {
        String mod, what, when, risky;   // what: the plan's words; risky: why it needs overrides.allow_network
        final List<String> lines = new ArrayList<String>();   // ipt/route/rule/sysctl lines (net.rules)
        String sysctlKey, sysctlValue;
    }

    static final class Ask {
        String mod;
        final List<Item> items = new ArrayList<Item>();
    }

    @SuppressWarnings("unchecked")
    static Ask of(String id, Object o, List<String> errors) {
        if (o == null) return null;
        Ask a = new Ask();
        a.mod = id;
        if (!(o instanceof Map)) {
            errors.add(id + ": network: must be {\"rules\": [...], \"routes\": [...], \"route_rules\": [...], \"sysctl\": {...}}");
            return a;
        }
        Map<String, Object> n = (Map<String, Object>) o;
        for (String k : n.keySet()) {
            if (!"rules".equals(k) && !"routes".equals(k) && !"route_rules".equals(k) && !"sysctl".equals(k)) errors.add(id + ": network: unknown \"" + k + "\"");
        }
        int i = 0;
        for (Map<String, Object> r : maps(n.get("rules"), id + ": network rules", errors)) {
            String err = rule(a, r, ++i);
            if (err != null) errors.add(id + ": network rule " + i + ": " + err);
        }
        i = 0;
        for (Map<String, Object> r : maps(n.get("routes"), id + ": network routes", errors)) {
            String err = route(a, r);
            if (err != null) errors.add(id + ": network route " + (++i) + ": " + err);
        }
        i = 0;
        for (Map<String, Object> r : maps(n.get("route_rules"), id + ": network route_rules", errors)) {
            String err = routeRule(a, r);
            if (err != null) errors.add(id + ": network route rule " + (++i) + ": " + err);
        }
        Object sc = n.get("sysctl");
        if (sc != null && !(sc instanceof Map)) errors.add(id + ": network sysctl: must be {\"net.<key>\": \"<value>\"}");
        else if (sc != null) {
            for (Map.Entry<String, Object> s : ((Map<String, Object>) sc).entrySet()) {
                String k = s.getKey(), v = String.valueOf(s.getValue());
                if (!k.matches("net\\.(ipv4|ipv6|core|netfilter)(\\.[a-z0-9_]+(-[a-z0-9_]+)*)+")) {
                    errors.add(id + ": network sysctl " + k + ": only net.ipv4.*, net.ipv6.*, net.core.*, net.netfilter.* (interfaces without dots)");
                } else if (!v.matches("-?[0-9]{1,10}( -?[0-9]{1,10}){0,3}")) {
                    errors.add(id + ": network sysctl " + k + ": the value must be whole numbers");
                } else {
                    Item it = new Item();
                    it.mod = id;
                    it.sysctlKey = k;
                    it.sysctlValue = v;
                    it.what = "sysctl " + k + " = " + v;
                    it.risky = "a network setting of the whole unit";
                    it.lines.add("sysctl " + k.replace('.', '/') + " " + v.replace(' ', ','));
                    a.items.add(it);
                }
            }
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object o, String what, List<String> errors) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (o == null) return out;
        if (!(o instanceof List)) {
            errors.add(what + ": must be a list");
            return out;
        }
        for (Object x : (List<Object>) o) {
            if (x instanceof Map) out.add((Map<String, Object>) x);
            else errors.add(what + ": each entry must be an object");
        }
        return out;
    }

    static String s(Map<String, Object> m, String k) {
        Object o = m.get(k);
        return o == null ? null : String.valueOf(o);
    }

    static final Set<String> RULE_KEYS = new LinkedHashSet<String>(java.util.Arrays.asList("table", "chain", "in", "out", "from", "to", "ip",
            "proto", "ports", "source_ports", "both_ways", "icmp_type", "state", "action", "when", "comment"));

    /** a rule, expanded into ipt lines on a; null, or what's wrong */
    @SuppressWarnings("unchecked")
    static String rule(Ask a, Map<String, Object> r, int n) {
        for (String k : r.keySet()) if (!RULE_KEYS.contains(k)) return "unknown \"" + k + "\"";
        String table = r.containsKey("table") ? s(r, "table") : "filter", ch = s(r, "chain");
        String ours = chain(table, ch);
        if (ours == null) return "\"chain\" " + ch + " isn't one of " + table + "'s (input, output, forward; nat and mangle: prerouting, output, postrouting)";
        String in = s(r, "in"), out = s(r, "out"), from = s(r, "from"), to = s(r, "to");
        boolean pre = "prerouting".equals(ch), post = "postrouting".equals(ch);
        if (in != null && ("output".equals(ch) || post)) return "\"in\" isn't known on " + ch + " (use \"out\")";
        if (out != null && ("input".equals(ch) || pre)) return "\"out\" isn't known on " + ch + " (use \"in\")";
        for (String i : new String[] { in, out }) if (i != null && !"hotspot".equals(i) && !i.matches(IFACE)) return "interface " + i + ": a name like wlan0 or rmnet+, or \"hotspot\"";
        // which IP versions
        Set<Integer> ips = new LinkedHashSet<Integer>();
        Object ipo = r.get("ip");
        if (ipo == null || "both".equals(String.valueOf(ipo))) { ips.add(4); ips.add(6); }
        else if ("4".equals(String.valueOf(ipo).replace(".0", ""))) ips.add(4);
        else if ("6".equals(String.valueOf(ipo).replace(".0", ""))) ips.add(6);
        else return "\"ip\" must be 4, 6 or both";
        for (String x : new String[] { from, to }) {
            if (x == null || "hotspot".equals(x)) continue;
            int v = family(x);
            if (v == 0) return "address " + x + ": an IPv4 or IPv6 address, optionally /prefix, or \"hotspot\"";
            if (ipo != null && !"both".equals(String.valueOf(ipo)) && !ips.contains(v)) return "address " + x + " isn't IPv" + ips.iterator().next();
            ips.retainAll(java.util.Collections.singleton(v));
        }
        if ("nat".equals(table)) {
            if (ipo != null && !ips.contains(4)) return "nat is IPv4 only on this kernel";
            ips.retainAll(java.util.Collections.singleton(4));
        }
        if (ips.isEmpty()) return "\"from\" and \"to\" are different IP versions";
        String proto = r.containsKey("proto") ? s(r, "proto") : "all";
        List<String> protos = new ArrayList<String>();
        if ("tcp+udp".equals(proto)) { protos.add("tcp"); protos.add("udp"); }
        else if (proto.matches("tcp|udp|icmp|icmpv6|all")) protos.add(proto);
        else return "\"proto\" must be tcp, udp, tcp+udp, icmp, icmpv6 or all";
        if ("icmp".equals(proto)) ips.retainAll(java.util.Collections.singleton(4));
        if ("icmpv6".equals(proto)) ips.retainAll(java.util.Collections.singleton(6));
        if (ips.isEmpty()) return proto + " doesn't fit the addresses' IP version";
        List<String> ports = new ArrayList<String>(), sports = new ArrayList<String>();
        String pe = ports(r.get("ports"), ports), se = ports(r.get("source_ports"), sports);
        if (pe != null) return "ports: " + pe;
        if (se != null) return "source_ports: " + se;
        boolean both = Boolean.TRUE.equals(r.get("both_ways"));
        if ((!ports.isEmpty() || !sports.isEmpty()) && !protos.get(0).matches("tcp|udp")) return "ports need proto tcp, udp or tcp+udp";
        if (both && (ports.isEmpty() || !sports.isEmpty())) return "both_ways needs \"ports\" (and no \"source_ports\")";
        String icmp = s(r, "icmp_type");
        if (icmp != null && (!proto.startsWith("icmp") || !icmp.matches("[a-z-]{1,40}|[0-9]{1,3}(/[0-9]{1,3})?"))) return "icmp_type needs proto icmp or icmpv6, and a type name or number";
        String state = null;
        if (r.get("state") instanceof List) {
            StringBuilder b = new StringBuilder();
            for (Object o : (List<Object>) r.get("state")) {
                String x = String.valueOf(o).toLowerCase();
                if (!x.matches("new|established|related|invalid")) return "state must be new, established, related or invalid";
                b.append(b.length() == 0 ? "" : ",").append(x.toUpperCase());
            }
            state = b.toString();
        } else if (r.containsKey("state")) return "state must be a list";
        String act = r.containsKey("action") ? s(r, "action") : null, target;
        if (act == null) return "needs an \"action\"";
        String tgt = target(table, ch, act);
        if (tgt.startsWith("!")) return tgt.substring(1);
        target = tgt;

        Item it = new Item();
        it.mod = a.mod;
        it.when = s(r, "when");
        if (it.when != null && !it.when.matches("!?settings\\.[A-Za-z0-9_]+(=[A-Za-z0-9_.-]+)?")) return "when must be settings.<key>, !settings.<key> or settings.<key>=<value>";
        for (int v : ips) {
            for (String p : protos) {
                List<String[]> portSets = new ArrayList<String[]>();   // {dport, sport}
                if (ports.isEmpty() && sports.isEmpty()) portSets.add(new String[] { null, null });
                for (String dp : ports) {
                    if (sports.isEmpty()) portSets.add(new String[] { dp, null });
                    for (String sp : sports) portSets.add(new String[] { dp, sp });
                }
                if (ports.isEmpty()) for (String sp : sports) portSets.add(new String[] { null, sp });
                if (both) for (String dp : ports) portSets.add(new String[] { null, dp });
                for (String[] ps : portSets) {
                    StringBuilder b = new StringBuilder("ipt ").append(v).append(' ').append(table).append(' ').append(ours);
                    String i = "hotspot".equals(in) ? HOTSPOT_IF : in, o = "hotspot".equals(out) ? HOTSPOT_IF : out;
                    String src = "hotspot".equals(from) || ("hotspot".equals(in) && from == null) ? (v == 4 ? HOTSPOT4 : HOTSPOT6) : from;
                    String dst = "hotspot".equals(to) || ("hotspot".equals(out) && to == null) ? (v == 4 ? HOTSPOT4 : HOTSPOT6) : to;
                    if (i != null) b.append(" -i ").append(i);
                    if (o != null) b.append(" -o ").append(o);
                    if (src != null) b.append(" -s ").append(src);
                    if (dst != null) b.append(" -d ").append(dst);
                    if (!"all".equals(p)) b.append(" -p ").append(p);
                    if (ps[1] != null) b.append(" --sport ").append(ps[1]);
                    if (ps[0] != null) b.append(" --dport ").append(ps[0]);
                    if (icmp != null) b.append("icmp".equals(p) ? " --icmp-type " : " --icmpv6-type ").append(icmp);
                    if (state != null) b.append(" -m state --state ").append(state);
                    b.append(' ').append(target);
                    it.lines.add(b.toString());
                }
            }
        }
        boolean hotspotOnly = "filter".equals(table) && ("input".equals(ch) && "hotspot".equals(in) || "output".equals(ch) && "hotspot".equals(out))
                && (from == null || "hotspot".equals(from)) && (to == null || "hotspot".equals(to));
        if (!hotspotOnly) it.risky = "not only the hotspot (" + table + " " + ch + (in != null ? ", in " + in : "") + (out != null ? ", out " + out : "") + ")";
        it.what = "firewall " + table + " " + ch + ": " + act + " " + ("all".equals(proto) ? "" : proto + " ")
                + (ports.isEmpty() ? "" : "port " + join(ports).replace(':', '-') + (both ? " (either way)" : "") + " ")
                + (sports.isEmpty() ? "" : "from port " + join(sports).replace(':', '-') + " ")
                + (in != null ? "in " + in + " " : "") + (out != null ? "out " + out + " " : "")
                + (from != null && !"hotspot".equals(in) ? "from " + from + " " : "") + (to != null && !"hotspot".equals(out) ? "to " + to + " " : "")
                + "(IPv" + (ips.size() == 2 ? "4+6" : String.valueOf(ips.iterator().next())) + ", " + it.lines.size() + " rule" + (it.lines.size() == 1 ? "" : "s") + ")";
        a.items.add(it);
        return null;
    }

    /** iptables' target for an action, or "!" + what's wrong */
    static String target(String table, String ch, String act) {
        if (act.matches("accept|drop|reject|return")) {
            if ("nat".equals(table) && !"accept".equals(act) && !"return".equals(act)) return "!nat rules can accept, return, masquerade, snat, dnat or redirect";
            return "-j " + act.toUpperCase();
        }
        if (act.startsWith("log:")) {
            String p = act.substring(4);
            if (!p.matches("[A-Za-z0-9_.:-]{1,28}")) return "!log:<prefix>: up to 28 letters, digits and _.:-";
            return "-j LOG --log-prefix " + p;
        }
        if (act.startsWith("mark:")) {
            if (!"mangle".equals(table)) return "!mark is for mangle rules";
            String m = act.substring(5);
            if (!m.matches("[0-9]{1,10}|0x[0-9a-fA-F]{1,8}")) return "!mark:<number>";
            return "-j MARK --set-mark " + m;
        }
        if ("masquerade".equals(act) || act.startsWith("snat:")) {
            if (!"nat".equals(table) || !"postrouting".equals(ch)) return "!" + act.replaceFirst(":.*", "") + " is for nat postrouting";
            if ("masquerade".equals(act)) return "-j MASQUERADE";
            String ad = act.substring(5);
            if (family(ad) != 4 || ad.contains("/")) return "!snat:<IPv4 address>";
            return "-j SNAT --to-source " + ad;
        }
        if (act.startsWith("dnat:") || act.startsWith("redirect:")) {
            if (!"nat".equals(table) || !("prerouting".equals(ch) || "output".equals(ch))) return "!" + act.replaceFirst(":.*", "") + " is for nat prerouting or output";
            if (act.startsWith("redirect:")) {
                String p = act.substring(9);
                return port(p) ? "-j REDIRECT --to-ports " + p : "!redirect:<port>";
            }
            String ad = act.substring(5), host = ad.contains(":") ? ad.substring(0, ad.indexOf(':')) : ad;
            if (family(host) != 4 || host.contains("/") || (ad.contains(":") && !port(ad.substring(ad.indexOf(':') + 1)))) return "!dnat:<IPv4 address>[:<port>]";
            return "-j DNAT --to-destination " + ad;
        }
        return "!\"action\" must be accept, drop, reject, return, log:<prefix>, mark:<n>, masquerade, snat:<address>, dnat:<address[:port]> or redirect:<port>";
    }

    static boolean port(String p) {
        if (!p.matches("[0-9]{1,5}")) return false;
        int n = Integer.parseInt(p);
        return n >= 1 && n <= 65535;
    }

    /** ports into iptables' form (a, a:b); null, or what's wrong */
    @SuppressWarnings("unchecked")
    static String ports(Object o, List<String> out) {
        if (o == null) return null;
        if (!(o instanceof List)) return "must be a list";
        for (Object x : (List<Object>) o) {
            String p = String.valueOf(x).replaceFirst("\\.0$", "");
            if (port(p)) out.add(p);
            else if (p.matches("[0-9]{1,5}-[0-9]{1,5}") && port(p.split("-")[0]) && port(p.split("-")[1])
                    && Integer.parseInt(p.split("-")[0]) < Integer.parseInt(p.split("-")[1])) out.add(p.replace('-', ':'));
            else return p + " isn't a port (1-65535) or a range a-b";
        }
        if (out.size() > 40) return "at most 40";
        return null;
    }

    /** 4, 6, or 0 if it isn't an address (with optional /prefix) */
    static int family(String a) {
        String addr = a, pfx = null;
        if (a.indexOf('/') >= 0) {
            addr = a.substring(0, a.indexOf('/'));
            pfx = a.substring(a.indexOf('/') + 1);
            if (!pfx.matches("[0-9]{1,3}")) return 0;
        }
        if (addr.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            for (String o : addr.split("\\.")) if (Integer.parseInt(o) > 255) return 0;
            return pfx == null || Integer.parseInt(pfx) <= 32 ? 4 : 0;
        }
        if (addr.matches("[0-9a-fA-F:]{2,39}") && addr.contains(":") && addr.split("::", -1).length <= 2) {
            return pfx == null || Integer.parseInt(pfx) <= 128 ? 6 : 0;
        }
        return 0;
    }

    static String route(Ask a, Map<String, Object> r) {
        for (String k : r.keySet()) if (!k.matches("to|dev|via|metric|table|when|comment")) return "unknown \"" + k + "\"";
        String to = s(r, "to"), dev = s(r, "dev"), via = s(r, "via"), table = r.containsKey("table") ? s(r, "table").replaceFirst("\\.0$", "") : "main";
        int v = "default".equals(to) ? 0 : to == null ? -1 : family(to);
        if (v < 0 || (v == 0 && !"default".equals(to))) return "\"to\": an address/prefix or default";
        if (dev == null && via == null) return "needs \"dev\" and/or \"via\"";
        if (dev != null && !"hotspot".equals(dev) && !dev.matches(IFACE.replace("\\+?", ""))) return "\"dev\": an interface name or \"hotspot\"";
        if (via != null) {
            int vv = family(via);
            if (vv == 0 || via.contains("/") || (v != 0 && vv != v)) return "\"via\": an address of the same IP version";
            v = vv;
        }
        if (v == 0) v = 4;
        if (!table.matches("main|[0-9]{1,3}") || (!"main".equals(table) && (Integer.parseInt(table) < 1 || Integer.parseInt(table) > 252))) return "\"table\": main or 1-252";
        String metric = r.containsKey("metric") ? s(r, "metric").replaceFirst("\\.0$", "") : null;
        if (metric != null && !metric.matches("[0-9]{1,5}")) return "\"metric\": a number";
        Item it = new Item();
        it.mod = a.mod;
        it.when = s(r, "when");
        if (it.when != null && !it.when.matches("!?settings\\.[A-Za-z0-9_]+(=[A-Za-z0-9_.-]+)?")) return "when must be settings.<key>, !settings.<key> or settings.<key>=<value>";
        String d = "hotspot".equals(dev) ? HOTSPOT_IF : dev;
        it.lines.add("route " + v + " " + table + " " + to + (via != null ? " via " + via : "") + (d != null ? " dev " + d : "") + (metric != null ? " metric " + metric : ""));
        it.what = "route " + to + (via != null ? " via " + via : "") + (dev != null ? " dev " + dev : "") + ("main".equals(table) ? "" : " (table " + table + ")");
        it.risky = "changes where the unit's traffic goes";
        a.items.add(it);
        return null;
    }

    static String routeRule(Ask a, Map<String, Object> r) {
        for (String k : r.keySet()) if (!k.matches("priority|fwmark|from|to|iif|table|ip|when|comment")) return "unknown \"" + k + "\"";
        String prio = s(r, "priority") == null ? null : s(r, "priority").replaceFirst("\\.0$", "");
        if (prio == null || !prio.matches("[0-9]{3,5}") || Integer.parseInt(prio) < 100 || Integer.parseInt(prio) > 32765) return "\"priority\": 100-32765 (each rule its own)";
        String table = s(r, "table") == null ? null : s(r, "table").replaceFirst("\\.0$", "");
        if (table == null || !table.matches("main|[0-9]{1,3}")) return "\"table\": main or 1-252";
        String mark = s(r, "fwmark") == null ? null : s(r, "fwmark").replaceFirst("\\.0$", ""), from = s(r, "from"), to = s(r, "to"), iif = s(r, "iif");
        if (mark != null && !mark.matches("[0-9]{1,10}|0x[0-9a-fA-F]{1,8}")) return "\"fwmark\": a number";
        if (iif != null && !"hotspot".equals(iif) && !iif.matches(IFACE.replace("\\+?", ""))) return "\"iif\": an interface name or \"hotspot\"";
        int v = r.containsKey("ip") ? ("6".equals(s(r, "ip").replace(".0", "")) ? 6 : 4) : 4;
        for (String x : new String[] { from, to }) {
            if (x == null) continue;
            if (family(x) == 0) return "address " + x + ": an address/prefix";
            v = family(x);
        }
        if (mark == null && from == null && to == null && iif == null) return "needs fwmark, from, to or iif";
        Item it = new Item();
        it.mod = a.mod;
        it.when = s(r, "when");
        if (it.when != null && !it.when.matches("!?settings\\.[A-Za-z0-9_]+(=[A-Za-z0-9_.-]+)?")) return "when must be settings.<key>, !settings.<key> or settings.<key>=<value>";
        StringBuilder b = new StringBuilder("rule ").append(v).append(' ').append(prio);
        if (from != null) b.append(" from ").append(from);
        if (to != null) b.append(" to ").append(to);
        if (mark != null) b.append(" fwmark ").append(mark);
        if (iif != null) b.append(" iif ").append("hotspot".equals(iif) ? HOTSPOT_IF : iif);
        b.append(" table ").append(table);
        it.lines.add(b.toString());
        it.what = "routing rule " + prio + ": " + b.substring(b.indexOf(prio) + prio.length() + 1);
        it.risky = "changes where the unit's traffic goes";
        a.items.add(it);
        return null;
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String x : l) b.append(b.length() == 0 ? "" : ",").append(x);
        return b.toString();
    }

    // ------------------------------------------------------------------ every mod's asks

    static final class Settled {
        final List<Item> kept = new ArrayList<Item>();
        final List<String> notes = new ArrayList<String>(), lines = new ArrayList<String>();
    }

    /** the enabled mods' asks, in order, risky ones left out unless allowed, conflicts noted */
    @SuppressWarnings("unchecked")
    static Settled settle(Engine e, Map<String, Map<String, Object>> json, Set<String> on) {
        Settled st = new Settled();
        Map<String, Object> ov = e.hmgapi != null && e.hmgapi.get("overrides") instanceof Map ? (Map<String, Object>) e.hmgapi.get("overrides") : new LinkedHashMap<String, Object>();
        List<String> order = new ArrayList<String>();
        if (ov.get("network_order") instanceof List) for (Object o : (List<Object>) ov.get("network_order")) if (on.contains(String.valueOf(o))) order.add(String.valueOf(o));
        for (String id : on) if (!order.contains(id)) order.add(id);
        List<Object> allow = ov.get("allow_network") instanceof List ? (List<Object>) ov.get("allow_network") : new ArrayList<Object>();
        Map<String, String> matchOwner = new LinkedHashMap<String, String>(), sysctl = new LinkedHashMap<String, String>();
        Map<String, String> sysctlOwner = new LinkedHashMap<String, String>();
        Set<String> prios = new LinkedHashSet<String>();
        for (String id : order) {
            Map<String, Object> mj = json.get(id);
            if (mj == null || mj.get("network") == null) continue;
            Ask a = of(id, mj.get("network"), new ArrayList<String>());
            Map<String, Object> settings;
            try {
                settings = Engine.settingsOf(new File(e.data, "mods/" + id), e.data, id);
            } catch (Exception x) {
                settings = new LinkedHashMap<String, Object>();
            }
            for (Item it : a.items) {
                if (!Engine.when(it.when, settings)) continue;
                if (it.risky != null && !allow.contains(id)) {
                    st.notes.add("WARNING: " + id + ": network " + it.what + " left out: " + it.risky
                            + "; it can reach the car's own network. To allow it at your own risk, add \"" + id + "\" to overrides.allow_network");
                    continue;
                }
                if (it.sysctlKey != null) {
                    if (sysctl.containsKey(it.sysctlKey) && !sysctl.get(it.sysctlKey).equals(it.sysctlValue)) {
                        st.notes.add(id + ": network sysctl " + it.sysctlKey + " = " + it.sysctlValue + " left out: " + sysctlOwner.get(it.sysctlKey)
                                + " sets " + sysctl.get(it.sysctlKey) + " (overrides.network_order picks who goes first)");
                        continue;
                    }
                    if (sysctl.containsKey(it.sysctlKey)) continue;
                    sysctl.put(it.sysctlKey, it.sysctlValue);
                    sysctlOwner.put(it.sysctlKey, id);
                }
                if (it.lines.get(0).startsWith("rule ")) {
                    String p = it.lines.get(0).split(" ")[2];
                    if (!prios.add(p)) {
                        st.notes.add(id + ": network routing rule " + p + " left out: another mod's rule has that priority");
                        continue;
                    }
                }
                for (String l : it.lines) {
                    if (!l.startsWith("ipt ")) continue;
                    int j = l.lastIndexOf(" -j ");
                    String match = l.substring(0, j), was = matchOwner.get(match);
                    if (was == null) matchOwner.put(match, id + "\t" + l.substring(j));
                    else if (!was.startsWith(id + "\t") && !was.endsWith(l.substring(j))) {
                        String note = "network: " + was.substring(0, was.indexOf('\t')) + " and " + id + " handle the same traffic differently; "
                                + was.substring(0, was.indexOf('\t')) + "'s rule comes first (overrides.network_order)";
                        if (!st.notes.contains(note)) st.notes.add(note);
                    }
                }
                if (st.lines.size() + it.lines.size() > MAX_LINES) {
                    st.notes.add(id + ": network " + it.what + " left out: more than " + MAX_LINES + " network rules in all");
                    continue;
                }
                st.kept.add(it);
                st.lines.addAll(it.lines);
            }
        }
        return st;
    }

    /** net.rules: "chain <v> <table> <chain> <count>" lines first (the keeper checks them), then the rest */
    static String rulesFile(List<String> lines) {
        Map<String, Integer> count = new LinkedHashMap<String, Integer>();
        for (String l : lines) {
            if (!l.startsWith("ipt ")) continue;
            String[] w = l.split(" ");
            String k = w[1] + " " + w[2] + " " + w[3];
            count.put(k, count.containsKey(k) ? count.get(k) + 1 : 1);
        }
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> c : count.entrySet()) b.append("chain ").append(c.getKey()).append(' ').append(c.getValue()).append('\n');
        for (String l : lines) b.append(l).append('\n');
        return b.toString();
    }

    /** state/net.rules and net.sh; the keeper's services.txt line, or null when there's nothing to keep */
    static String publish(Engine e, Map<String, Map<String, Object>> json, Set<String> on) throws Exception {
        Settled st = settle(e, json, on);
        for (String n : st.notes) e.out.add(n);
        File rules = new File(e.stateDir, "net.rules"), script = new File(e.stateDir, "net.sh");
        String text = rulesFile(st.lines);
        boolean applied = new File(e.stateDir, "net.applied").isFile();
        boolean mods = HomeWifi.publish(e, json, on);   // mods off cellular: the keeper runs for that too
        if (text.isEmpty() && !applied && !mods) {
            rules.delete();
            return null;
        }
        ModRuntime.writeIfChanged(rules, text);
        if (!(script.isFile() && Engine.read(script).equals(SCRIPT))) {   // a new file: a running keeper keeps reading its own
            File tmp = new File(e.stateDir, ".net.sh.tmp");
            Engine.write(tmp, SCRIPT);
            if (!tmp.renameTo(script)) throw new java.io.IOException("can't write net.sh");
        }
        return "hmgapi net root boot yes script " + SCRIPT_PATH + " - " + Integer.toHexString(SCRIPT.hashCode());
    }

    /** the plan's lines for one mod */
    static List<String> describe(String id, Ask a, boolean allowed) {
        List<String> out = new ArrayList<String>();
        if (a == null) return out;
        for (Item it : a.items) {
            out.add("network  " + it.what + (it.when != null ? " (when " + it.when + ")" : "")
                    + (it.risky != null ? allowed ? " (allowed: " + it.risky + ")" : " (left out unless allowed: " + it.risky + ")" : "") + " [" + id + "]");
        }
        return out;
    }

    /**
     * HMG-API's network keeper, run by hmgapid as root ("hmgapi net"); mksh and the unit's
     * toolbox only. net.rules is what's wanted, net.applied what's in; net-stock/ keeps the
     * stock value of every sysctl it changed.
     */
    static final String SCRIPT = lines(
        "# HMG-API's network keeper (written by the engine: hmgapi.engine.Network). hmgapid runs it",
        "# as root (service \"hmgapi net\"): it puts the enabled mods' network asks (net.rules) in,",
        "# checks them every 10 s, puts back what the stock firewall or a Wi-Fi restart took away,",
        "# and takes everything out when it's stopped or nothing is wanted any more.",
        "#   sh net.sh        keep (the service)",
        "#   sh net.sh down   take everything out (the uninstaller)",
        "#   sh net.sh once   one check, then exit (by hand: hmgapid sh 'sh /data/hmgapi/state/net.sh once')",
        "set -f",
        "S=${0%/*}",
        "R=$S/net.rules A=$S/net.applied K=$S/net-stock P=${HMGAPI_PROCSYS:-/proc/sys}",
        "nl='",
        "'",
        "J=' -j '",
        "say() { echo \"$(date '+%m-%d %H:%M:%S') $*\"; }",
        "ipt() { if [ \"$1\" = 6 ]; then shift; ip6tables \"$@\"; else shift; iptables \"$@\"; fi; }",
        "stock_of() {",
        "    case $1 in",
        "        HMGAPI_IN) echo INPUT ;;",
        "        HMGAPI_FWD) echo FORWARD ;;",
        "        *OUT) echo OUTPUT ;;",
        "        *PRE) echo PREROUTING ;;",
        "        *POST) echo POSTROUTING ;;",
        "    esac",
        "}",
        "# every HMG-API chain out, whatever was in it",
        "fw_down() {",
        "    for v in 4 6; do",
        "        for tc in filter:HMGAPI_IN filter:HMGAPI_OUT filter:HMGAPI_FWD nat:HMGAPI_PRE nat:HMGAPI_POST nat:HMGAPI_NOUT \\",
        "                  mangle:HMGAPI_MPRE mangle:HMGAPI_MOUT mangle:HMGAPI_MPOST; do",
        "            t=${tc%%:*} c=${tc#*:}",
        "            [ $v = 6 ] && [ $t = nat ] && continue",
        "            ipt $v -t $t -S $c >/dev/null 2>&1 || continue",
        "            b=$(stock_of $c)",
        "            for _ in 1 2 3 4 5 6 7 8; do ipt $v -t $t -D $b -j $c 2>/dev/null || break; done",
        "            ipt $v -t $t -F $c; ipt $v -t $t -X $c",
        "        done",
        "    done",
        "}",
        "# the wanted chains, filled, each jumped to first from its stock chain",
        "fw_up() {",
        "    while read -r k v t c n; do",
        "        [ \"$k\" = chain ] || continue",
        "        ipt $v -t $t -N $c 2>/dev/null; ipt $v -t $t -F $c",
        "        ipt $v -t $t -I $(stock_of $c) 1 -j $c",
        "    done < \"$A\"",
        "    while read -r k v t c rest; do",
        "        [ \"$k\" = ipt ] || continue",
        "        ipt $v -t $t -A $c $rest || say \"refused by iptables: IPv$v $t $c $rest\"",
        "    done < \"$A\"",
        "}",
        "# a rule ahead of HMG-API's jump that can't drop a packet (accept, return, log, mark, or a jump to a",
        "# chain of only those): HMG-API's rules still see all it lets past, so another tool may stay first",
        "harmless() {",
        "    case $3 in *\" -j HMGAPI_CELL\") return 0 ;; esac   # HMG-API's own: it only refuses mods on cellular",
        "    case $3 in *\" -j \"*) ;; *) return 1 ;; esac",
        "    case $3 in *\" -j ACCEPT\"|*\" -j RETURN\"|*\" -j LOG \"*|*\" -j NFLOG\"*|*\" -j MARK \"*) return 0 ;; esac",
        "    j=${3##*\"$J\"}; j=${j%% *}",
        "    case $j in DROP|REJECT) return 1 ;; esac",
        "    sub=$(ipt $1 -t $2 -S $j 2>/dev/null) || return 1",
        "    for r in $sub; do",
        "        case $r in \"-A \"*\" -j \"*) ;; *) continue ;; esac",
        "        case $r in *\" -j ACCEPT\"|*\" -j RETURN\"|*\" -j LOG \"*|*\" -j NFLOG\"*|*\" -j MARK \"*) ;; *) return 1 ;; esac",
        "    done",
        "    return 0",
        "}",
        "# each wanted chain: ahead of anything in its stock chain that could drop, and every rule there",
        "fw_ok() {",
        "    while read -r k v t c n; do",
        "        [ \"$k\" = chain ] || continue",
        "        b=$(stock_of $c) ours= have=0",
        "        out=$(ipt $v -t $t -S $b 2>/dev/null) || return 1",
        "        o=$IFS; IFS=$nl",
        "        for l in $out; do",
        "            case $l in \"-A $b -j $c\") ours=1; break ;; \"-A \"*) ;; *) continue ;; esac",
        "            harmless $v $t \"$l\" || break",
        "        done",
        "        out=$(ipt $v -t $t -S $c 2>/dev/null) || { IFS=$o; return 1; }",
        "        for l in $out; do case $l in \"-A \"*) have=$((have + 1)) ;; esac; done",
        "        IFS=$o",
        "        [ -n \"$ours\" ] && [ $have = $n ] || return 1",
        "    done < \"$A\"",
        "    return 0",
        "}",
        "# routes, routing rules, sysctls: put in (again) what's missing; quiet when all is there",
        "others_up() {",
        "    while read -r k v a b rest; do",
        "        case $k in",
        "            route) [ -n \"$(ip -$v route show $b table $a 2>/dev/null)\" ] || { ip -$v route replace $b $rest table $a && say \"route $b $rest (table $a) in\"; } ;;",
        "            rule)",
        "                out=$(ip -$v rule show 2>/dev/null) has=",
        "                o=$IFS; IFS=$nl",
        "                for l in $out; do case $l in \"$a:\"*) has=1; break ;; esac; done",
        "                IFS=$o",
        "                [ -n \"$has\" ] || { ip -$v rule add priority $a $b $rest && say \"routing rule $a in\"; } ;;",
        "            sysctl)",
        "                f=$P/$v w=${a//,/ }",
        "                cur=$(cat $f 2>/dev/null) || continue",
        "                [ \"$(echo $cur)\" = \"$w\" ] && continue",
        "                mkdir -p $K; s=$K/${v//\\//:}",
        "                [ -f $s ] || echo \"$cur\" > $s",
        "                echo \"$w\" > $f && say \"sysctl $v = $w\" ;;",
        "        esac",
        "    done < \"$A\"",
        "}",
        "others_down() {",
        "    [ -f \"$A\" ] && while read -r k v a b rest; do",
        "        case $k in",
        "            route) ip -$v route del $b $rest table $a 2>/dev/null ;;",
        "            rule) ip -$v rule del priority $a 2>/dev/null ;;",
        "        esac",
        "    done < \"$A\"",
        "    set +f",
        "    for s in $K/*; do",
        "        [ -f \"$s\" ] || continue",
        "        f=${s##*/}; f=$P/${f//://}",
        "        cat $s > $f 2>/dev/null; rm -f $s",
        "    done",
        "    set -f",
        "}",
        "# ---- mods off the car's cellular link; the ones that asked through home Wi-Fi (hmgapi.engine.HomeWifi)",
        "# mods-net.txt: \"block|home|allow <mod>\" and \"homewifi on\"; apps.txt gives each mod's packages,",
        "# packages.list their uids (they change when an app is reinstalled, so they're looked up each time).",
        "MN=$S/mods-net.txt MA=$S/mods-net.applied PL=${HMGAPI_PACKAGES:-/data/system/packages.list}",
        "uid_of() { while read -r p u rest; do [ \"$p\" = \"$1\" ] && { echo $u; return; }; done < $PL; }",
        "mods_want() {",
        "    [ -s \"$MN\" ] || return",
        "    echo base",
        "    while read -r mode id; do",
        "        case $mode in block|home) ;; homewifi) echo homewifi; continue ;; *) continue ;; esac",
        "        [ -f $S/apps.txt ] && while read -r pkg owner; do",
        "            [ \"$owner\" = \"$id\" ] || continue",
        "            u=$(uid_of $pkg); [ -n \"$u\" ] && echo \"$mode $u\"",
        "        done < $S/apps.txt",
        "    done < \"$MN\"",
        "}",
        "mods_fw_down() {",
        "    for v in 4 6; do for _ in 1 2 3 4 5 6 7 8; do ipt $v -D OUTPUT -j HMGAPI_CELL 2>/dev/null || break; done; ipt $v -F HMGAPI_CELL 2>/dev/null; ipt $v -X HMGAPI_CELL 2>/dev/null; done",
        "    for _ in 1 2 3 4 5 6 7 8; do iptables -t mangle -D OUTPUT -j HMGAPI_HOME 2>/dev/null || break; done",
        "    iptables -t mangle -F HMGAPI_HOME 2>/dev/null; iptables -t mangle -X HMGAPI_HOME 2>/dev/null",
        "    for _ in 1 2 3 4 5 6 7 8; do iptables -t nat -D POSTROUTING -j HMGAPI_HOMEOUT 2>/dev/null || break; done",
        "    iptables -t nat -F HMGAPI_HOMEOUT 2>/dev/null; iptables -t nat -X HMGAPI_HOMEOUT 2>/dev/null",
        "    for _ in 1 2 3 4 5 6 7 8; do ip rule del priority " + HomeWifi.PRIORITY + " 2>/dev/null || break; done",
        "}",
        "mods_dns() {   # mods' apps look names up through wlan0 in netd: its servers when home Wi-Fi is up, none otherwise",
        "    while read -r mode u; do",
        "        case $mode in block|home) ndc resolver setifaceforuidrange " + HomeWifi.IFACE + " $u $u >/dev/null 2>&1 ;; esac",
        "    done < \"$MA\"",
        "}",
        "mods_up() {",
        "    for v in 4 6; do",
        "        ipt $v -N HMGAPI_CELL 2>/dev/null; ipt $v -F HMGAPI_CELL; ipt $v -I OUTPUT 1 -j HMGAPI_CELL",
        "        for g in " + HomeWifi.SERVICE_GID + " " + HomeWifi.HOME_GID + "; do ipt $v -A HMGAPI_CELL -o rmnet+ -m owner --gid-owner $g -j REJECT; done",
        "    done",
        "    home=",
        "    while read -r mode u; do",
        "        case $mode in",
        "            block|home) for v in 4 6; do ipt $v -A HMGAPI_CELL -o rmnet+ -m owner --uid-owner $u -j REJECT; done ;;",
        "            homewifi) home=1 ;;",
        "        esac",
        "    done < \"$MA\"",
        "    mods_dns",
        "    [ -n \"$home\" ] || return 0",
        "    iptables -t mangle -N HMGAPI_HOME 2>/dev/null; iptables -t mangle -F HMGAPI_HOME; iptables -t mangle -I OUTPUT 1 -j HMGAPI_HOME",
        "    iptables -t mangle -A HMGAPI_HOME -m owner --gid-owner " + HomeWifi.HOME_GID + " -j MARK --set-mark " + HomeWifi.MARK,
        "    while read -r mode u; do",
        "        [ \"$mode\" = home ] && iptables -t mangle -A HMGAPI_HOME -m owner --uid-owner $u -j MARK --set-mark " + HomeWifi.MARK,
        "    done < \"$MA\"",
        "    iptables -t nat -N HMGAPI_HOMEOUT 2>/dev/null; iptables -t nat -F HMGAPI_HOMEOUT; iptables -t nat -I POSTROUTING 1 -j HMGAPI_HOMEOUT",
        "    iptables -t nat -A HMGAPI_HOMEOUT -o " + HomeWifi.IFACE + " -m mark --mark " + HomeWifi.MARK + " -j MASQUERADE",
        "    ip rule add priority " + HomeWifi.PRIORITY + " fwmark " + HomeWifi.MARK + " table " + HomeWifi.TABLE,
        "}",
        "# in place, first in OUTPUT (nothing ahead of it may accept a mod's packet for cellular), all its rules there",
        "mods_ok() {",
        "    for v in 4 6; do",
        "        first=$(ipt $v -S OUTPUT 2>/dev/null | while read -r a b rest; do [ \"$a\" = -A ] && { echo \"$rest\"; break; }; done)",
        "        [ \"$first\" = '-j HMGAPI_CELL' ] || return 1",
        "        have=0; for l in $(ipt $v -S HMGAPI_CELL 2>/dev/null | while read -r a rest; do [ \"$a\" = -A ] && echo x; done); do have=$((have + 1)); done",
        "        want=2; while read -r mode u; do case $mode in block|home) want=$((want + 1)) ;; esac; done < \"$MA\"",
        "        [ $have = $want ] || return 1",
        "    done",
        "    grep -q '^homewifi' \"$MA\" || return 0",
        "    iptables -t mangle -C OUTPUT -j HMGAPI_HOME 2>/dev/null && iptables -t nat -C POSTROUTING -j HMGAPI_HOMEOUT 2>/dev/null || return 1",
        "    out=$(ip rule show 2>/dev/null); case $out in *\"" + HomeWifi.PRIORITY + ":\"*) ;; *) return 1 ;; esac",
        "}",
        "mods_down() {",
        "    [ -f \"$MA\" ] && while read -r mode u; do",
        "        case $mode in block|home) ndc resolver clearifaceforuidrange " + HomeWifi.IFACE + " $u $u >/dev/null 2>&1 ;; esac",
        "    done < \"$MA\"",
        "    mods_fw_down; rm -f \"$MA\"",
        "}",
        "mods_keep() {",
        "    want=$(mods_want)",
        "    if [ \"$want\" != \"$(cat \"$MA\" 2>/dev/null)\" ]; then",
        "        [ -f \"$MA\" ] && mods_down",
        "        [ -n \"$want\" ] || return",
        "        echo \"$want\" > \"$MA\"; mods_up",
        "        say \"mods kept off cellular: $(grep -c -e '^block ' -e '^home ' \"$MA\") app(s) and every mod service$(grep -q '^homewifi' \"$MA\" && echo ', home Wi-Fi routing in')\"",
        "        return",
        "    fi",
        "    [ -f \"$MA\" ] || return",
        "    mods_ok || { mods_fw_down; mods_up; say \"mods' cellular block put back (moved or removed since)\"; }",
        "    tick=$((tick + 1)); [ $((tick % 6)) = 0 ] && mods_dns   # netd forgets it when it restarts",
        "}",
        "tick=0",
        "down() { fw_down; others_down; mods_down; rm -f \"$A\"; }",
        "keep() {",
        "    case $(getprop init.svc.iptrules) in running|restarting) return ;; esac   # the stock firewall loading",
        "    mods_keep",
        "    if [ \"$(cat \"$R\" 2>/dev/null)\" != \"$(cat \"$A\" 2>/dev/null)\" ]; then",
        "        [ -f \"$A\" ] && { fw_down; others_down; rm -f \"$A\"; say \"the mods' network asks changed: everything out\"; }",
        "        [ -s \"$R\" ] || return",
        "        cat \"$R\" > \"$A\"",
        "        fw_up; others_up",
        "        say \"in: $(grep -c '^ipt ' \"$A\") firewall rule(s), $(grep -c '^route ' \"$A\") route(s), $(grep -c '^rule ' \"$A\") routing rule(s), $(grep -c '^sysctl ' \"$A\") setting(s)\"",
        "        return",
        "    fi",
        "    [ -s \"$A\" ] || return",
        "    fw_ok || { fw_down; fw_up; say \"firewall rules put back (moved or removed since)\"; }",
        "    others_up",
        "}",
        "umask 077",
        "if [ \"$1\" = down ]; then down; exit 0; fi",
        "if [ \"$1\" = once ]; then keep; exit 0; fi",
        "trap 'down; say stopped: everything out; exit 0' TERM",
        "sleep 6   # a keeper being replaced takes its rules out first",
        "while :; do",
        "    keep",
        "    sleep 10",
        "done");

    static String lines(String... ls) {
        StringBuilder b = new StringBuilder();
        for (String l : ls) b.append(l).append('\n');
        return b.toString();
    }
}

package hmgapi.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Car Report's "network": what a maintainer needs to see why projection or a
 * mod's network asks don't get through on a car (the Ioniq 6's firewall let wireless
 * CarPlay find the car, then dropped it):
 *
 *   firewall_services  init services that load a firewall (an iptables script), their
 *                      state and the stock script (from the car's own image)
 *   iptables           the rules (iptables/ip6tables -S, filter, nat, mangle) and the
 *                      counters (-L -n -v -x), model-level, from the stock image
 *   interfaces, routes, route_rules, sysctl
 *   drops              the kernel's firewall log lines (NFLOG, LOG), the last 100
 *   hmgapi             HMG-API's own rules in (net.applied) and its keeper's log
 *
 * Addresses can be personal (a phone's link-local IPv6 comes from its MAC; the modem's
 * address is the car's), so outside the rules: MACs removed, IPv6 interface ids
 * removed, public IPv4 cut to its first two numbers. Commands come from PATH (hmgapid
 * sets the unit's), so a test can stand in for them.
 */
final class NetReport {
    private NetReport() {}

    static Map<String, Object> collect(Engine e) {
        Map<String, Object> n = new LinkedHashMap<String, Object>();
        n.put("firewall_services", services(e));
        Map<String, Object> ipt = new LinkedHashMap<String, Object>();
        for (String v : new String[] { "4", "6" }) {
            for (String t : new String[] { "filter", "nat", "mangle" }) {
                if ("6".equals(v) && "nat".equals(t)) continue;
                ipt.put(v + " " + t, macs(run(tool(v), "-t", t, "-S")));
            }
            ipt.put(v + " counters", macs(run(tool(v), "-L", "-n", "-v", "-x")));
        }
        n.put("iptables", ipt);
        n.put("interfaces", scrub(run("ip", "-o", "addr", "show")));
        n.put("links", scrub(run("ip", "-o", "link", "show")));
        Map<String, Object> routes = new LinkedHashMap<String, Object>();
        routes.put("4", scrub(run("ip", "-4", "route", "show", "table", "all")));
        routes.put("6", scrub(run("ip", "-6", "route", "show", "table", "all")));
        n.put("routes", routes);
        Map<String, Object> rr = new LinkedHashMap<String, Object>();
        rr.put("4", scrub(run("ip", "-4", "rule", "show")));
        rr.put("6", scrub(run("ip", "-6", "rule", "show")));
        n.put("route_rules", rr);
        Map<String, Object> sc = new LinkedHashMap<String, Object>();
        for (String k : new String[] { "net/ipv4/ip_forward", "net/ipv6/conf/all/forwarding", "net/ipv6/conf/wlan0/disable_ipv6",
                "net/ipv6/conf/wlan0/accept_ra", "net/ipv4/conf/wlan0/rp_filter", "net/ipv4/conf/all/rp_filter" }) {
            try {
                File f = new File(e.root + "/proc/sys/" + k);
                if (f.isFile()) sc.put(k.replace('/', '.'), Engine.read(f).trim());
            } catch (Exception x) {
                // unreadable: left out
            }
        }
        n.put("sysctl", sc);
        List<Object> drops = new ArrayList<Object>();
        String[] kl = run("dmesg").split("\n");
        for (String l : kl) if (l.contains("NFLOG") || (l.contains(" IN=") && l.contains(" OUT="))) drops.add(scrub(l));
        n.put("drops", drops.size() > 100 ? new ArrayList<Object>(drops.subList(drops.size() - 100, drops.size())) : drops);
        Map<String, Object> own = new LinkedHashMap<String, Object>();
        File applied = new File(e.stateDir, "net.applied");
        int rules = 0;
        try {
            if (applied.isFile()) for (String l : Engine.read(applied).split("\n")) if (l.startsWith("ipt ")) rules++;
        } catch (Exception x) {
            // left at 0
        }
        own.put("rules_in", (long) rules);
        own.put("keeper_log", tail(new File(e.data, "logs/hmgapi/net.log"), 40));
        n.put("hmgapi", own);
        return n;
    }

    static String tool(String v) {
        return "6".equals(v) ? "ip6tables" : "iptables";
    }

    static String run(String... cmd) {
        try {
            Engine.Run r = Engine.exec(cmd);
            String t = r.text.length() > 64 * 1024 ? r.text.substring(0, 64 * 1024) + "\n[cut at 64 KB]" : r.text;
            return r.code == 0 ? t : t + (t.endsWith("\n") || t.isEmpty() ? "" : "\n") + "[exit " + r.code + "]";
        } catch (Exception x) {
            return "[" + cmd[0] + ": " + x.getMessage() + "]";
        }
    }

    /** init services that run a script calling iptables, or are named like a firewall */
    static List<Object> services(Engine e) {
        List<Object> out = new ArrayList<Object>();
        File[] rcs = new File(e.root + "/").listFiles();
        if (rcs == null) return out;
        java.util.Arrays.sort(rcs);
        for (File rc : rcs) {
            if (!rc.getName().startsWith("init") || !rc.getName().endsWith(".rc") || !rc.isFile()) continue;
            String text;
            try {
                text = Engine.read(rc);
            } catch (Exception x) {
                continue;
            }
            for (String l : text.split("\n")) {
                String[] w = l.trim().split("\\s+");
                if (w.length < 3 || !"service".equals(w[0])) continue;
                String script = w[2].endsWith(".sh") ? w[2] : w[2].endsWith("/sh") && w.length > 3 ? w[3] : null, body = null;
                if (script != null && script.startsWith("/system/")) {
                    try {
                        File f = new File(e.root + script);
                        if (f.isFile() && f.length() < 64 * 1024) body = Engine.read(f);
                    } catch (Exception x) {
                        // unreadable
                    }
                }
                boolean named = w[1].matches("(?i).*(ipt|firewall|netfilter|fw).*");
                if (!named && (body == null || !body.contains("iptables"))) continue;
                Map<String, Object> s = new LinkedHashMap<String, Object>();
                s.put("service", w[1]);
                s.put("rc", rc.getName());
                s.put("command", l.trim().substring(l.trim().indexOf(w[2])));
                s.put("state", e.getprop("init.svc." + w[1]));
                if (body != null) s.put("script", macs(body));
                out.add(s);
            }
        }
        return out;
    }

    static final Pattern MAC_FIELD = Pattern.compile("MAC=\\S*");
    static final Pattern V4 = Pattern.compile("(?<![\\d.])(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?![\\d.])");
    static final Pattern V6 = Pattern.compile("(?i)(?<![0-9a-f:])([0-9a-f]{1,4}(?::[0-9a-f]{1,4}){7}|[0-9a-f]{0,4}(?::[0-9a-f]{0,4})*::(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4})*)?)(?![0-9a-f:])");

    /** MAC addresses out (rules and scripts: the rest is the stock image's) */
    static String macs(String s) {
        return Report.SECRETS[0].matcher(MAC_FIELD.matcher(s).replaceAll("MAC=[removed]")).replaceAll("[removed]");
    }

    /** MACs, IPv6 interface ids and public IPv4 out (interfaces, routes, logged packets) */
    static String scrub(String s) {
        s = macs(s);
        Matcher m = V6.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String a = m.group(1).toLowerCase(), r;
            if (a.equals("::") || a.equals("::1") || a.startsWith("ff")) r = a;   // unspecified, loopback, multicast
            else if (a.startsWith("fe80")) r = "fe80::[removed]";
            else r = a.split(":")[0] + ":[removed]";
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        m = V4.matcher(b.toString());
        b = new StringBuffer();
        while (m.find()) {
            int a = Integer.parseInt(m.group(1)), c = Integer.parseInt(m.group(2));
            boolean keep = a == 10 || a == 127 || a == 0 || a >= 224 || (a == 192 && c == 168) || (a == 172 && c >= 16 && c <= 31) || (a == 169 && c == 254);
            m.appendReplacement(b, keep ? m.group() : m.group(1) + "." + m.group(2) + ".x.x");
        }
        m.appendTail(b);
        return b.toString();
    }

    /** the last n lines of a log, scrubbed like everything else outside the rules */
    static List<Object> tail(File f, int n) {
        List<Object> out = new ArrayList<Object>();
        try {
            if (!f.isFile()) return out;
            String[] ls = Engine.read(f).split("\n");
            for (int i = Math.max(0, ls.length - n); i < ls.length; i++) out.add(scrub(ls[i]));
        } catch (Exception x) {
            // unreadable: empty
        }
        return out;
    }

    /** one line for the owner and the issue: the firewall, its policies, HMG-API's rules */
    @SuppressWarnings("unchecked")
    static String summary(Map<String, Object> n) {
        StringBuilder b = new StringBuilder();
        List<Object> svcs = (List<Object>) n.get("firewall_services");
        if (svcs.isEmpty()) b.append("no firewall service found");
        else {
            for (Object o : svcs) {
                Map<String, Object> s = (Map<String, Object>) o;
                b.append(b.length() == 0 ? "firewall " : ", ").append(s.get("service")).append(" (").append(s.get("state")).append(")");
            }
        }
        Map<String, Object> ipt = (Map<String, Object>) n.get("iptables");
        for (String v : new String[] { "4", "6" }) {
            String f = String.valueOf(ipt.get(v + " filter"));
            Matcher m = Pattern.compile("-P INPUT (\\S+)").matcher(f);
            int chains = 0;
            for (String l : f.split("\n")) if (l.startsWith("-N ")) chains++;
            b.append("; IPv").append(v).append(" input ").append(m.find() ? m.group(1) : "?").append(", ").append(chains).append(" chains");
        }
        b.append("; HMG-API rules in: ").append(((Map<String, Object>) n.get("hmgapi")).get("rules_in"));
        b.append("; drops logged: ").append(((List<Object>) n.get("drops")).size());
        return b.toString();
    }
}

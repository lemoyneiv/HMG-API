package hmgapi.wifi;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Split-tunnel routing for Home Wi-Fi on wlan0:
 *   - Removes Android's main-table default route on wlan0 so the car's own telemetry stays on rmnet0.
 *   - Installs default and subnet routes on wlan0 into policy routing table 95 (fwmark 0x2951).
 *   - Keeps netd's default resolver on rmnet0 while setting wlan0 DNS + HMGAPI_HOMEDNS DNAT for gid 2951.
 */
public final class HomeWifiRouter {
    public static final String IFACE = "wlan0";
    public static final int TABLE = 95;
    public static final int HOME_GID = 2951;

    private String activeLeaseSignature = "";
    private final Set<String> activeDnsHostRoutes = new LinkedHashSet<String>();

    public synchronized boolean routeUp(String ip, String gatewayHint, String dns1Hint, String dns2Hint) {
        String gw = validIpv4(gatewayHint) ? gatewayHint : null;
        String routesRaw = execOut("ip", "route", "show", "dev", IFACE);
        List<String> subnets = new ArrayList<String>();
        for (String line : routesRaw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+");
            if (parts.length >= 3 && "default".equals(parts[0]) && "via".equals(parts[1]) && validIpv4(parts[2])) {
                gw = parts[2];
            } else if (parts[0].indexOf('/') > 0) {
                subnets.add(parts[0]);
            }
        }
        if (gw == null || !validIpv4(gw)) {
            return false;
        }

        // Remove Android's default route on wlan0 in main table so stock head-unit traffic stays on rmnet0
        for (int i = 0; i < 8; i++) {
            if (execCode("ip", "route", "del", "default", "dev", IFACE) != 0) break;
        }

        execCode("ip", "route", "replace", "default", "via", gw, "dev", IFACE, "table", String.valueOf(TABLE));
        for (String subnet : subnets) {
            execCode("ip", "route", "replace", subnet, "dev", IFACE, "table", String.valueOf(TABLE));
        }

        String d1 = validIpv4(dns1Hint) ? dns1Hint : validProp("dhcp." + IFACE + ".dns1");
        String d2 = validIpv4(dns2Hint) ? dns2Hint : validProp("dhcp." + IFACE + ".dns2");
        if (d1 == null) d1 = gw;

        execCode("ndc", "resolver", "setdefaultif", "rmnet0");
        if (d2 != null && !d2.isEmpty()) {
            execCode("ndc", "resolver", "setifdns", IFACE, "", d1, d2);
        } else {
            execCode("ndc", "resolver", "setifdns", IFACE, "", d1);
        }

        writeSystemLeaseQuietly(gw, d1, d2, ip);

        String sig = gw + "|" + d1 + "|" + (d2 == null ? "" : d2);
        if (!sig.equals(activeLeaseSignature)) {
            clearDnsHostRoutes();
            for (String d : new String[] { d1, d2 }) {
                if (d == null || d.isEmpty() || d.equals(gw)) continue;
                String hostRoute = d + "/32";
                if (execCode("ip", "route", "replace", hostRoute, "via", gw, "dev", IFACE, "metric", "1000") == 0) {
                    activeDnsHostRoutes.add(hostRoute);
                }
            }
            execCode("iptables", "-t", "nat", "-N", "HMGAPI_HOMEDNS");
            execCode("iptables", "-t", "nat", "-F", "HMGAPI_HOMEDNS");
            if (execCode("iptables", "-t", "nat", "-C", "OUTPUT", "-j", "HMGAPI_HOMEDNS") != 0) {
                execCode("iptables", "-t", "nat", "-I", "OUTPUT", "1", "-j", "HMGAPI_HOMEDNS");
            }
            for (String proto : new String[] { "udp", "tcp" }) {
                execCode("iptables", "-t", "nat", "-A", "HMGAPI_HOMEDNS",
                        "-m", "owner", "--gid-owner", String.valueOf(HOME_GID),
                        "-p", proto, "--dport", "53", "-j", "DNAT", "--to-destination", d1);
            }
            activeLeaseSignature = sig;
        }
        return true;
    }

    public synchronized void routeDown() {
        execCode("ip", "route", "flush", "table", String.valueOf(TABLE));
        execCode("iptables", "-t", "nat", "-F", "HMGAPI_HOMEDNS");
        clearDnsHostRoutes();
        activeLeaseSignature = "";
        java.io.File lf = new java.io.File("/data/hmgapi/wifi/lease");
        if (lf.isFile()) lf.delete();
    }

    private static void writeSystemLeaseQuietly(String gw, String d1, String d2, String ip) {
        try {
            java.io.File dir = new java.io.File("/data/hmgapi/wifi");
            if (!dir.isDirectory()) return;
            java.io.File tmp = new java.io.File(dir, ".lease.tmp");
            java.io.File lf = new java.io.File(dir, "lease");
            String line = (gw == null ? "" : gw) + " " + (d1 == null ? "" : d1)
                    + (d2 != null && !d2.isEmpty() ? (" " + d2) : "")
                    + (ip != null && !ip.isEmpty() ? (" " + ip) : "") + "\n";
            java.io.FileOutputStream out = new java.io.FileOutputStream(tmp);
            try {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            tmp.setReadable(false, false);
            tmp.setWritable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(true, true);
            tmp.renameTo(lf);
        } catch (Exception ignored) {
        }
    }

    private void clearDnsHostRoutes() {
        for (String r : activeDnsHostRoutes) {
            execCode("ip", "route", "del", r, "dev", IFACE);
        }
        activeDnsHostRoutes.clear();
    }

    private static String validProp(String prop) {
        String v = execOut("getprop", prop).trim();
        return validIpv4(v) ? v : null;
    }

    public static boolean validIpv4(String s) {
        if (s == null || s.isEmpty() || "0.0.0.0".equals(s)) return false;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return false;
        try {
            for (String p : parts) {
                int n = Integer.parseInt(p);
                if (n < 0 || n > 255) return false;
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static int execCode(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            drain(p.getInputStream());
            return p.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String execOut(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String s = drain(p.getInputStream());
            p.waitFor();
            return s;
        } catch (Exception e) {
            return "";
        }
    }

    private static String drain(InputStream in) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }
}

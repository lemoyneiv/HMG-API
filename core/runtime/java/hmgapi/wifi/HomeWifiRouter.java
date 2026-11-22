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
    public static final int ADB_PORT = 5555;
    public static final int ADB_SOURCE_PRIO = 906;
    public static final String CHAIN_HOMEADB = "HMGAPI_HOMEADB";

    private static final long EXEC_TIMEOUT_MS = 2500L;
    private static final long ADBD_BOUNCE_SLEEP_MS = 450L;

    private String activeLeaseSignature = "";
    private String activeAppliedSignature = "";
    private String activeAdbIp = "";
    private final Object adbdLock = new Object();
    private boolean desiredAdbTcp = false;
    private Thread pendingAdbThread = null;
    private final Set<String> activeDnsHostRoutes = new LinkedHashSet<String>();

    public synchronized boolean routeUp(String ip, String gatewayHint, String dns1Hint, String dns2Hint) {
        String gw = validIpv4(gatewayHint) ? gatewayHint : null;
        String routesRaw = execOut("ip", "route", "show", "dev", IFACE);
        List<String> subnets = new ArrayList<String>();
        boolean mainHasDefault = false;
        for (String line : routesRaw.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split("\\s+");
            if ("default".equals(parts[0])) {
                mainHasDefault = true;
                if (parts.length >= 3 && "via".equals(parts[1]) && validIpv4(parts[2])) {
                    gw = parts[2];
                }
            } else if (parts[0].indexOf('/') > 0) {
                subnets.add(parts[0]);
            }
        }
        if (gw == null || !validIpv4(gw)) {
            return false;
        }

        String d1 = validIpv4(dns1Hint) ? dns1Hint : validProp("dhcp." + IFACE + ".dns1");
        String d2 = validIpv4(dns2Hint) ? dns2Hint : validProp("dhcp." + IFACE + ".dns2");
        if (d1 == null) d1 = gw;

        String appliedSig = (ip == null ? "" : ip) + "|" + gw + "|" + d1 + "|" + (d2 == null ? "" : d2);
        if (!mainHasDefault && appliedSig.equals(activeAppliedSignature)) {
            String t95 = execOut("ip", "route", "show", "table", String.valueOf(TABLE));
            if (t95.contains("default")) {
                return true;
            }
        }

        // Remove Android's default route on wlan0 in main table so stock head-unit traffic stays on rmnet0
        if (mainHasDefault) {
            for (int i = 0; i < 8; i++) {
                if (execCode("ip", "route", "del", "default", "dev", IFACE) != 0) break;
            }
        }

        execCode("ip", "route", "replace", "default", "via", gw, "dev", IFACE, "table", String.valueOf(TABLE));
        for (String subnet : subnets) {
            execCode("ip", "route", "replace", subnet, "dev", IFACE, "table", String.valueOf(TABLE));
        }

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
        activeAppliedSignature = appliedSig;
        return true;
    }

    public synchronized void routeDown() {
        setAdbOverWifi(false, "", false);
        execCode("ip", "route", "flush", "table", String.valueOf(TABLE));
        execCode("iptables", "-t", "nat", "-F", "HMGAPI_HOMEDNS");
        clearDnsHostRoutes();
        activeLeaseSignature = "";
        activeAppliedSignature = "";
        java.io.File lf = new java.io.File("/data/hmgapi/wifi/lease");
        if (lf.isFile()) lf.delete();
    }

    /**
     * Enables or disables ADB over TCP (:5555) on Home Wi-Fi (wlan0):
     *   1. Never touches persist.service.adb.tcp.enable (which breaks CarPlay/AA eth0 DHCP on Gen5W)
     *      or persist.adb.tcp.port; uses only volatile service.adb.tcp.port=5555.
     *   2. Installs HMGAPI_HOMEADB (-i wlan0 -p tcp --dport 5555 -j ACCEPT) at the top of INPUT,
     *      satisfying net.sh's harmless() check so net.sh never removes or fights with it.
     *   3. Installs ip rule priority 906 from <wlanIp>/32 table 95 so adbd replies reach clients on
     *      other home VLANs/VPNs via wlan0's gateway without affecting unbound rmnet0 sockets.
     */
    public synchronized boolean setAdbOverWifi(boolean enable, String wlanIp, boolean deferTeardown) {
        if (enable && validIpv4(wlanIp)) {
            synchronized (adbdLock) {
                desiredAdbTcp = true;
            }
            String portStr = String.valueOf(ADB_PORT);
            String prioStr = String.valueOf(ADB_SOURCE_PRIO);
            if (!wlanIp.equals(activeAdbIp) || !hasIpRulePriority(ADB_SOURCE_PRIO)) {
                for (int i = 0; i < 4; i++) {
                    if (execCode("ip", "rule", "del", "priority", prioStr) != 0) break;
                }
                execCode("ip", "rule", "add", "priority", prioStr,
                        "from", wlanIp + "/32", "table", String.valueOf(TABLE));
            }
            if (!wlanIp.equals(activeAdbIp)
                    || execCode("iptables", "-C", CHAIN_HOMEADB,
                            "-i", IFACE, "-p", "tcp", "--dport", portStr, "-j", "ACCEPT") != 0) {
                execCode("iptables", "-N", CHAIN_HOMEADB);
                execCode("iptables", "-F", CHAIN_HOMEADB);
                execCode("iptables", "-A", CHAIN_HOMEADB,
                        "-i", IFACE, "-p", "tcp", "--dport", portStr, "-j", "ACCEPT");
            }
            if (!isHomeAdbJumpHealthy()) {
                for (int i = 0; i < 4; i++) {
                    if (execCode("iptables", "-D", "INPUT", "-j", CHAIN_HOMEADB) != 0) break;
                }
                execCode("iptables", "-I", "INPUT", "1", "-j", CHAIN_HOMEADB);
            }
            String svcPort = execOut("getprop", "service.adb.tcp.port").trim();
            String persistPort = execOut("getprop", "persist.adb.tcp.port").trim();
            boolean alreadyListening = portStr.equals(svcPort)
                    || (svcPort.isEmpty() && portStr.equals(persistPort));
            if (!alreadyListening) {
                execCode("setprop", "service.adb.tcp.port", portStr);
                scheduleAdbdTransition(true, 0L);
            }
            activeAdbIp = wlanIp;
            return true;
        }

        synchronized (adbdLock) {
            desiredAdbTcp = false;
        }
        activeAdbIp = "";
        if (deferTeardown && !isHostTestMode()) {
            scheduleAdbdTransition(false, 220L);
            return false;
        }
        removeHomeAdbFirewallAndRule();
        teardownVolatileAdbPropertyAndBounce(true);
        return false;
    }

    public void awaitPendingAdbTransition(long timeoutMs) {
        Thread t;
        synchronized (adbdLock) {
            t = pendingAdbThread;
        }
        if (t != null && t.isAlive()) {
            try {
                t.join(Math.max(100L, timeoutMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void removeHomeAdbFirewallAndRule() {
        if (execCode("iptables", "-S", CHAIN_HOMEADB) == 0) {
            for (int i = 0; i < 4; i++) {
                if (execCode("iptables", "-D", "INPUT", "-j", CHAIN_HOMEADB) != 0) break;
            }
            execCode("iptables", "-F", CHAIN_HOMEADB);
            execCode("iptables", "-X", CHAIN_HOMEADB);
        }
        String prioStr = String.valueOf(ADB_SOURCE_PRIO);
        for (int i = 0; i < 4; i++) {
            if (execCode("ip", "rule", "del", "priority", prioStr) != 0) break;
        }
    }

    private void teardownVolatileAdbPropertyAndBounce(boolean asyncBounce) {
        String portStr = String.valueOf(ADB_PORT);
        String svcPort = execOut("getprop", "service.adb.tcp.port").trim();
        if (!portStr.equals(svcPort)) {
            return;
        }
        String persistPort = execOut("getprop", "persist.adb.tcp.port").trim();
        if (portStr.equals(persistPort)) {
            // Preserve permanent hotspot ADB configured by Open-HMG/hmgapi-adb without dropping adbd
            execCode("setprop", "service.adb.tcp.port", "");
            return;
        }
        execCode("setprop", "service.adb.tcp.port", "-1");
        if (asyncBounce && !isHostTestMode()) {
            scheduleAdbdTransition(false, 0L);
        } else {
            bounceAdbdNow(false);
        }
    }

    private void scheduleAdbdTransition(final boolean targetTcpOn, final long initialDelayMs) {
        if (isHostTestMode()) {
            if (!targetTcpOn && initialDelayMs > 0L) {
                removeHomeAdbFirewallAndRule();
                teardownVolatileAdbPropertyAndBounce(false);
            } else {
                bounceAdbdNow(targetTcpOn);
            }
            return;
        }
        Thread worker = new Thread("homewifi-adbd") {
            @Override
            public void run() {
                if (initialDelayMs > 0L) {
                    try {
                        Thread.sleep(initialDelayMs);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    synchronized (adbdLock) {
                        if (desiredAdbTcp != targetTcpOn) return;
                    }
                    if (!targetTcpOn) {
                        synchronized (HomeWifiRouter.this) {
                            synchronized (adbdLock) {
                                if (desiredAdbTcp) return;
                            }
                            removeHomeAdbFirewallAndRule();
                        }
                        String portStr = String.valueOf(ADB_PORT);
                        String svcPort = execOut("getprop", "service.adb.tcp.port").trim();
                        if (!portStr.equals(svcPort)) return;
                        String persistPort = execOut("getprop", "persist.adb.tcp.port").trim();
                        if (portStr.equals(persistPort)) {
                            execCode("setprop", "service.adb.tcp.port", "");
                            return;
                        }
                        execCode("setprop", "service.adb.tcp.port", "-1");
                    }
                }
                bounceAdbdNow(targetTcpOn);
            }
        };
        worker.setDaemon(true);
        synchronized (adbdLock) {
            pendingAdbThread = worker;
        }
        worker.start();
    }

    private void bounceAdbdNow(boolean expectTcpOn) {
        synchronized (adbdLock) {
            if (desiredAdbTcp != expectTcpOn) return;
            execCode("setprop", "ctl.stop", "adbd");
            if (!isHostTestMode()) {
                try {
                    Thread.sleep(ADBD_BOUNCE_SLEEP_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // Ensure service.adb.tcp.port matches the latest desired state before starting adbd
            if (desiredAdbTcp) {
                execCode("setprop", "service.adb.tcp.port", String.valueOf(ADB_PORT));
            }
            execCode("setprop", "ctl.start", "adbd");
        }
    }

    private static boolean isHostTestMode() {
        String fw = System.getenv("FW");
        return fw != null && !fw.isEmpty();
    }

    private static boolean hasIpRulePriority(int prio) {
        String prefix = prio + ":";
        String out = execOut("ip", "rule", "show");
        for (String line : out.split("\n")) {
            if (line.trim().startsWith(prefix)) return true;
        }
        return false;
    }

    private static boolean isHomeAdbJumpHealthy() {
        ProcResult r = runBounded("iptables", "-S", "INPUT");
        if (r.code != 0) return false;
        for (String line : r.output.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("-A INPUT ")) continue;
            if (("-A INPUT -j " + CHAIN_HOMEADB).equals(t)) {
                return true;
            }
            if (t.startsWith("-A INPUT -j HMGAPI_")) {
                continue;
            }
            return false;
        }
        return false;
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

    private static final class ProcResult {
        final int code;
        final String output;
        ProcResult(int code, String output) {
            this.code = code;
            this.output = output;
        }
    }

    private static ProcResult runBounded(String... cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream b = new ByteArrayOutputStream();
            Thread reader = new Thread("homewifi-exec") {
                @Override
                public void run() {
                    byte[] buf = new byte[512];
                    try {
                        for (int n; (n = in.read(buf)) > 0; ) {
                            b.write(buf, 0, n);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                    }
                }
            };
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + EXEC_TIMEOUT_MS;
            Integer exitCode = null;
            while (System.currentTimeMillis() < deadline) {
                try {
                    exitCode = Integer.valueOf(p.exitValue());
                    break;
                } catch (IllegalThreadStateException running) {
                    Thread.sleep(20L);
                }
            }
            if (exitCode == null) {
                p.destroy();
                reader.join(150L);
                return new ProcResult(-1, "");
            }
            reader.join(300L);
            return new ProcResult(exitCode.intValue(), b.toString(StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
            }
            return new ProcResult(-1, "");
        }
    }

    private static int execCode(String... cmd) {
        return runBounded(cmd).code;
    }

    private static String execOut(String... cmd) {
        return runBounded(cmd).output;
    }
}

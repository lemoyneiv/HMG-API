package hmgapi.wifi;

import android.content.Context;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.Looper;

import hmgapi.engine.WifiAp;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * HMG-API Home Wi-Fi Keeper service (run by hmgapid as root via state/wifi.sh exec app_process).
 *
 *   1. Service mode (no "cli" arg): runs AndroidWifiPlatform + HomeWifiController on the main
 *      Looper and listens on root-only abstract socket @hmgapi-wifi for <1ms hmgapid wifi ops.
 *   2. Offline CLI fallback ("cli" <verb> ...): handles down / saved / seen / join / use / forget /
 *      status cleanly when invoked before @hmgapi-wifi is up or during teardown.
 */
public final class HomeWifiDaemon {
    public static final String SOCKET_NAME = "hmgapi-wifi";

    private static volatile boolean shuttingDown = false;

    private HomeWifiDaemon() {}

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 0 && "cli".equals(args[0])) {
            System.exit(runOfflineCli(args));
            return;
        }
        runDaemon();
    }

    private static void runDaemon() throws Exception {
        Looper.prepareMainLooper();
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        Context sysCtx = obtainSystemContext();

        File rootDir = resolveRootDir();
        final HomeWifiStore store = new HomeWifiStore(rootDir);
        store.importLegacyWpaSupplicantIfNeeded(new File(rootDir, "data/misc/wifi/wpa_supplicant.conf"));

        final AndroidWifiPlatform platform = new AndroidWifiPlatform(sysCtx, mainHandler);
        platform.setFlightLog(store.flightLog());
        final HomeWifiController controller = new HomeWifiController(store, platform);

        final LocalServerSocket server = new LocalServerSocket(SOCKET_NAME);

        Runtime.getRuntime().addShutdownHook(new Thread("homewifi-shutdown") {
            @Override
            public void run() {
                shuttingDown = true;
                try { server.close(); } catch (Exception ignored) {}
                controller.shutdown("SIGTERM");
                platform.close();
            }
        });

        Thread ipcThread = new Thread("homewifi-ipc") {
            @Override
            public void run() {
                int consecutiveErrors = 0;
                while (!shuttingDown) {
                    LocalSocket client = null;
                    try {
                        client = server.accept();
                        consecutiveErrors = 0;
                    } catch (Throwable t) {
                        if (shuttingDown) break;
                        consecutiveErrors++;
                        android.util.Log.w("HMG-API", "wifi: @hmgapi-wifi accept error (#" + consecutiveErrors + "): " + t);
                        if (consecutiveErrors >= 20) {
                            android.util.Log.e("HMG-API", "wifi: @hmgapi-wifi unrecoverable accept failure; exiting for hmgapid restart");
                            System.exit(1);
                            return;
                        }
                        try { Thread.sleep(100L); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    handleClient(client, controller);
                }
            }
        };
        ipcThread.setDaemon(true);
        ipcThread.start();

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                controller.start();
            }
        });

        Looper.loop();
    }

    /**
     * Closes an accepted socket's underlying OS fd. On Gen5W Android 4.4 (KitKat, AOSP Bug 36917),
     * LocalServerSocket.accept() leaves fdCreatedInternally == false, so LocalSocket.close() drops
     * the Java wrapper without closing the kernel fd — leaking 1 fd per @hmgapi-wifi request until
     * EMFILE wedges server.accept(). Matches Hub.close() and BleHooks.closeAccepted().
     */
    static void closeAccepted(LocalSocket s) {
        if (s == null) return;
        java.io.FileDescriptor fd = null;
        try { fd = s.getFileDescriptor(); } catch (Throwable ignored) {}
        try { s.shutdownOutput(); } catch (Throwable ignored) {}
        try { s.shutdownInput(); } catch (Throwable ignored) {}
        try {
            if (fd != null && fd.valid()) libcore.io.Libcore.os.close(fd);
        } catch (Throwable ignored) {}
        try { s.close(); } catch (Exception ignored) {}
    }

    private static void handleClient(LocalSocket client, HomeWifiController controller) {
        try {
            client.setSoTimeout(5000);
            Credentials cred = client.getPeerCredentials();
            if (cred == null || cred.getUid() != 0) {
                writeResponse(client.getOutputStream(), 13, "not allowed\n");
                return;
            }
            DataInputStream in = new DataInputStream(client.getInputStream());
            byte[] hdr = new byte[8];
            in.readFully(hdr);
            if (hdr[0] != 'L' || hdr[1] != 'F' || hdr[2] != 'D' || hdr[3] != '1') {
                return;
            }
            int argc = readLe32(hdr, 4);
            if (argc <= 0 || argc > 8) {
                writeResponse(client.getOutputStream(), 2, "bad request\n");
                return;
            }
            List<String> args = new ArrayList<String>(argc);
            byte[] lenb = new byte[4];
            for (int i = 0; i < argc; i++) {
                in.readFully(lenb);
                int len = readLe32(lenb, 0);
                if (len < 0 || len > 65536) {
                    writeResponse(client.getOutputStream(), 2, "argument too large\n");
                    return;
                }
                byte[] data = new byte[len];
                if (len > 0) in.readFully(data);
                args.add(new String(data, StandardCharsets.UTF_8));
            }
            HomeWifiController.CmdResult res = controller.handleCommand(args);
            writeResponse(client.getOutputStream(), res.code, res.output);
        } catch (Exception ignored) {
        } finally {
            closeAccepted(client);
        }
    }

    private static void writeResponse(OutputStream out, int code, String text) throws IOException {
        byte[] body = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        byte[] hdr = new byte[8];
        writeLe32(hdr, 0, code);
        writeLe32(hdr, 4, body.length);
        out.write(hdr);
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static Context obtainSystemContext() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("systemMain").invoke(null);
            if (at != null) {
                Object ctx = atClass.getMethod("getSystemContext").invoke(at);
                if (ctx instanceof Context) return (Context) ctx;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static File resolveRootDir() {
        String testRoot = System.getenv("HMGAPI_TEST_ROOT");
        if (testRoot != null && !testRoot.trim().isEmpty()) {
            return new File(testRoot.trim());
        }
        return new File("/");
    }

    private static boolean isHomeWifiAppliedInState(File rootDir) {
        File txt = new File(rootDir, "data/hmgapi/state/wifi.txt");
        if (txt.isFile()) {
            try {
                for (String line : HomeWifiStore.readText(txt).split("\n")) {
                    if ("home".equals(line.trim())) return true;
                }
                return false;
            } catch (Exception ignored) {
                return false;
            }
        }
        return new File(rootDir, "data/hmgapi/state/wifi.sh").isFile();
    }

    // ---- Offline CLI fallback (when @hmgapi-wifi isn't running yet or during teardown) ----

    private static int runOfflineCli(String[] rawArgs) {
        File rootDir = resolveRootDir();
        HomeWifiStore store = new HomeWifiStore(rootDir);
        store.importLegacyWpaSupplicantIfNeeded(new File(rootDir, "data/misc/wifi/wpa_supplicant.conf"));
        String verb = rawArgs.length >= 2 ? rawArgs[1] : "";
        String arg1 = rawArgs.length >= 3 ? rawArgs[2] : "";
        String arg2 = rawArgs.length >= 4 ? rawArgs[3] : "";
        String envPsk = System.getenv("HMGAPI_WIFI_PSK");
        boolean hasPsk = "1".equals(System.getenv("HMGAPI_WIFI_HAS_PSK"))
                || (envPsk != null && !envPsk.isEmpty());

        if ("down".equals(verb)) {
            HomeWifiRouter r = new HomeWifiRouter();
            r.routeDown();
            r.awaitPendingAdbTransition(1500L);
            String prevBoot = store.loadActiveBootId();
            if (!prevBoot.isEmpty()) {
                int apMode = store.loadApMode();
                try { WifiAp.runCommand(new String[] { "client", "off" }); } catch (Throwable ignored) {}
                try { WifiAp.runCommand(new String[] { "start", String.valueOf(apMode) }); } catch (Throwable ignored) {}
                store.clearActiveBootId();
            }
            store.writePublicStatus("off", 0, 0);
            return 0;
        }

        boolean offlineConfigOrLog = "keepawake".equals(verb) || "adb".equals(verb)
                || "log".equals(verb) || "log-usb".equals(verb) || "log-clear".equals(verb);
        if (!offlineConfigOrLog && !isHomeWifiAppliedInState(rootDir)) {
            boolean switchedOn = new File(rootDir, "data/hmgapi/settings/allow/home_wifi").isFile();
            System.out.println(switchedOn
                    ? "Home Wi-Fi isn't applied yet (tap Apply changes, then restart)"
                    : "Home Wi-Fi is off (HMG-API settings)");
            return 1;
        }

        if ("saved".equals(verb)) {
            for (HomeWifiStore.Profile p : store.listProfiles()) {
                System.out.println(p.ssid);
            }
            return 0;
        }
        if ("seen".equals(verb)) {
            for (HomeWifiStore.SeenNetwork s : store.loadSeenNetworks()) {
                System.out.println(s.freqMhz + "\t" + s.rssiDbm + "\t" + s.capabilities + "\t" + s.ssid);
            }
            return 0;
        }
        if ("join".equals(verb) || "connect".equals(verb)) {
            try {
                store.upsertOwnerProfile(arg1, envPsk == null ? "" : envPsk, hasPsk || "join".equals(verb), null, true);
                System.out.println("Saved \"" + WifiSecurity.cleanSsid(arg1) + "\" (Home Wi-Fi service is starting up).");
                return 0;
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return 2;
            }
        }
        if ("use".equals(verb)) {
            try {
                boolean use = "on".equals(arg2);
                store.setUseForHomeWifi(arg1, use);
                System.out.println(use
                        ? ("\"" + WifiSecurity.cleanSsid(arg1) + "\" is now enabled for Home Wi-Fi.")
                        : ("\"" + WifiSecurity.cleanSsid(arg1) + "\" will be ignored by Home Wi-Fi."));
                return 0;
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return 1;
            }
        }
        if ("forget".equals(verb)) {
            try {
                store.removeProfile(arg1);
                System.out.println("Removed \"" + WifiSecurity.cleanSsid(arg1) + "\".");
                return 0;
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return 1;
            }
        }
        if ("keepawake".equals(verb)) {
            if (!HomeWifiKeepAwake.isValidUnpluggedCode(arg1) || !HomeWifiKeepAwake.isValidPluggedCode(arg2)) {
                System.out.println("Invalid keep-awake duration.");
                return 2;
            }
            try {
                store.setKeepAwakePolicy(arg1, arg2);
                System.out.println("Updated Home Wi-Fi keep-awake (" + HomeWifiKeepAwake.labelForCode(arg1)
                        + " unplugged / " + HomeWifiKeepAwake.labelForCode(arg2) + " plugged in).");
                return 0;
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return 1;
            }
        }
        if ("adb".equals(verb)) {
            if (!"on".equals(arg1) && !"off".equals(arg1)) {
                System.out.println("usage: wifi adb on|off");
                return 2;
            }
            try {
                boolean on = "on".equals(arg1);
                store.setAdbOverWifi(on);
                if (!on) {
                    HomeWifiRouter r = new HomeWifiRouter();
                    r.setAdbOverWifi(false, "", false);
                    r.awaitPendingAdbTransition(1500L);
                }
                System.out.println(on
                        ? "ADB over TCP will turn on automatically (port 5555) whenever Home Wi-Fi connects."
                        : "Disabled ADB over TCP on Home Wi-Fi.");
                return 0;
            } catch (Exception e) {
                System.out.println(e.getMessage());
                return 1;
            }
        }
        if ("status".equals(verb)) {
            HomeWifiController offline = new HomeWifiController(store, new InertPlatform(store));
            System.out.println(offline.statusJson());
            return 0;
        }
        if ("log".equals(verb)) {
            String tail = store.flightLog().readOwnerTail(48 * 1024);
            if (tail.trim().isEmpty()) {
                System.out.println("(No parked Home Wi-Fi session events recorded yet.)");
            } else {
                System.out.print(tail);
            }
            return 0;
        }
        if ("log-usb".equals(verb)) {
            try {
                List<String> saved = store.flightLog().savePublicToUsb(System.currentTimeMillis() / 1000L);
                if (saved.isEmpty()) {
                    System.out.println("No USB stick detected. Insert a USB drive and try again.");
                    return 1;
                }
                System.out.println("Saved scrubbed session log to " + saved.get(0));
                return 0;
            } catch (Exception e) {
                System.out.println("Could not write to USB stick: " + e.getMessage());
                return 1;
            }
        }
        if ("log-clear".equals(verb)) {
            store.flightLog().clear();
            System.out.println("Cleared Home Wi-Fi session log.");
            return 0;
        }
        System.out.println("Home Wi-Fi service is still starting up. Try again in a few seconds.");
        return 1;
    }

    private static final class InertPlatform implements WifiPlatform {
        private final HomeWifiStore store;
        InertPlatform(HomeWifiStore store) { this.store = store; }
        @Override public void setEventListener(Events listener) {}
        @Override public long elapsedRealtimeMs() { return 0; }
        @Override public long uptimeMillis() { return 0; }
        @Override public long wallEpochSec() { return System.currentTimeMillis() / 1000L; }
        @Override public String bootId() { return "offline"; }
        @Override public VehicleSnapshot readVehicle() { return new VehicleSnapshot("on", false, false); }
        @Override public boolean isSoftApActiveOrEnabling() { return true; }
        @Override public int getSoftApState() { return AP_STATE_ENABLED; }
        @Override public int getSoftApMode() { return 2; }
        @Override public void captureSoftApConfig() {}
        @Override public boolean disableSoftAp(int currentMode) { return false; }
        @Override public boolean restoreSoftAp(int mode) { return false; }
        @Override public void stopClientWifiOnly() {}
        @Override public void setSessionLocks(boolean holdWifiLock, boolean holdParkedPower) {}
        @Override public int getWifiState() { return WIFI_STATE_DISABLED; }
        @Override public boolean setWifiEnabled(boolean enabled) { return false; }
        @Override public boolean startScan() { return false; }
        @Override public List<HomeWifiStore.SeenNetwork> getScanResults() { return store.loadSeenNetworks(); }
        @Override public List<ConfiguredNet> getConfiguredNetworks() { return Collections.emptyList(); }
        @Override public int upsertWifiNetwork(String s, String p, boolean o, String c, boolean f) { return -1; }
        @Override public boolean enableOnlyNetwork(int netId) { return false; }
        @Override public boolean enableNetwork(int netId, boolean disableOthers) { return false; }
        @Override public boolean disableNetworkInRam(int netId) { return false; }
        @Override public boolean removeWifiNetwork(int netId) { return false; }
        @Override public boolean reconnect() { return false; }
        @Override public boolean reassociate() { return false; }
        @Override public boolean disconnect() { return false; }
        @Override public ConnectionSnapshot getConnectionSnapshot() {
            return new ConnectionSnapshot(SupState.DISCONNECTED, -1, "", "", 0, 0, 0, 0, 0, 0, 0, 0);
        }
        @Override public boolean routeUp(String ip, String gateway, String dns1, String dns2) { return false; }
        @Override public void routeDown() {}
        @Override public boolean setAdbOverWifi(boolean enable, String wlanIp, boolean deferTeardown) { return false; }
        @Override public void scheduleDelayed(String tag, long delayMs, Runnable task) {}
        @Override public void cancelScheduled(String tag) {}
        @Override public void log(String line) {}
    }
}

package hmgapi.wifi;

import hmgapi.engine.Json;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persistent storage for Home Wi-Fi profiles (/data/hmgapi/wifi/profiles.json, 0600 root-only),
 * cached scan results (/data/hmgapi/wifi/seen), SoftAP mode backup (/data/hmgapi/wifi/apmode),
 * and the zero-SSID public status file (/data/hmgapi/public/wifi.json, 0644).
 *
 * Never writes SSIDs or Wi-Fi keys to /data/hmgapi/public/ or logs.
 */
public final class HomeWifiStore {
    public static final class Profile {
        public final String ssid;
        public String psk;               // null if imported from Android WifiManager without raw key; "" if open
        public String capabilities;      // last known capabilities (e.g. "[WPA2-PSK-CCMP][ESS]" or "[ESS]")
        public boolean open;
        public boolean useForHomeWifi;   // false for dashcam / user-excluded networks
        public boolean dashcamDetected;
        public long lastConnectedSec;

        public Profile(String ssid) {
            this.ssid = WifiSecurity.cleanSsid(ssid);
            this.dashcamDetected = WifiSecurity.isLikelyDashcamSsid(this.ssid);
            this.useForHomeWifi = !this.dashcamDetected;
        }

        public boolean hasExplicitCredentials() {
            return open || (psk != null && !psk.isEmpty());
        }
    }

    public static final class SeenNetwork {
        public final String ssid;
        public final String bssid;
        public final int freqMhz;
        public final int rssiDbm;
        public final String capabilities;

        public SeenNetwork(String ssid, String bssid, int freqMhz, int rssiDbm, String capabilities) {
            this.ssid = WifiSecurity.cleanSsid(ssid);
            this.bssid = bssid == null ? "" : bssid;
            this.freqMhz = freqMhz;
            this.rssiDbm = rssiDbm;
            this.capabilities = capabilities == null ? "" : capabilities;
        }
    }

    private final File wifiDir;
    private final File publicWifiJson;
    private final File publicKeepAwakeJson;
    private final File peerOpenLinkKeepAwakeJson;
    private final File profilesFile;
    private final File leaseFile;
    private final File seenFile;
    private final File apModeFile;
    private final File modeBootFile;
    private final HomeWifiFlightLog flightLog;
    private final Map<String, Profile> profilesByLower = new LinkedHashMap<String, Profile>();
    private String keepAwakeUnplugged = HomeWifiKeepAwake.DEFAULT_UNPLUGGED;
    private String keepAwakePlugged = HomeWifiKeepAwake.DEFAULT_PLUGGED;
    private boolean adbOverWifi = false;

    public HomeWifiStore(File rootDir) {
        File base = rootDir == null ? new File("/") : rootDir;
        File hmgDir = new File(base, "data/hmgapi");
        this.wifiDir = new File(hmgDir, "wifi");
        this.publicWifiJson = new File(hmgDir, "public/wifi.json");
        this.publicKeepAwakeJson = new File(hmgDir, "public/keepawake.json");
        this.peerOpenLinkKeepAwakeJson = new File(hmgDir, "public/openlink-keepawake.json");
        this.profilesFile = new File(wifiDir, "profiles.json");
        this.leaseFile = new File(wifiDir, "lease");
        this.seenFile = new File(wifiDir, "seen");
        this.apModeFile = new File(wifiDir, "apmode");
        this.modeBootFile = new File(wifiDir, "mode");
        this.flightLog = new HomeWifiFlightLog(base);
        loadProfiles();
    }

    public File wifiDir() { return wifiDir; }

    public HomeWifiFlightLog flightLog() { return flightLog; }

    public synchronized String getKeepAwakeUnplugged() {
        return keepAwakeUnplugged;
    }

    public synchronized String getKeepAwakePlugged() {
        return keepAwakePlugged;
    }

    public synchronized void setKeepAwakePolicy(String unpluggedCode, String pluggedCode) throws IOException {
        String u = HomeWifiKeepAwake.normalizeUnpluggedCode(unpluggedCode);
        String p = HomeWifiKeepAwake.normalizePluggedCode(pluggedCode);
        if (!u.equals(keepAwakeUnplugged) || !p.equals(keepAwakePlugged)) {
            keepAwakeUnplugged = u;
            keepAwakePlugged = p;
            saveProfiles();
        }
    }

    public synchronized boolean isAdbOverWifi() {
        return adbOverWifi;
    }

    public synchronized void setAdbOverWifi(boolean enabled) throws IOException {
        if (adbOverWifi != enabled) {
            adbOverWifi = enabled;
            saveProfiles();
        }
    }

    public synchronized List<Profile> listProfiles() {
        return new ArrayList<Profile>(profilesByLower.values());
    }

    public synchronized Profile findProfile(String ssid) {
        String clean = WifiSecurity.cleanSsid(ssid);
        if (clean.isEmpty()) return null;
        return profilesByLower.get(clean.toLowerCase(Locale.US));
    }

    public synchronized boolean hasUsableHomeProfile() {
        for (Profile p : profilesByLower.values()) {
            if (p.useForHomeWifi) return true;
        }
        return false;
    }

    /**
     * Imports any SSIDs already present in Android's WifiManager (or legacy wpa_supplicant.conf)
     * without overwriting owner-configured settings. Dashcam SSIDs default to useForHomeWifi=false.
     */
    public synchronized boolean mergeSystemNetworks(List<WifiPlatform.ConfiguredNet> systemNets) {
        if (systemNets == null || systemNets.isEmpty()) return false;
        boolean changed = false;
        for (WifiPlatform.ConfiguredNet net : systemNets) {
            if (net == null) continue;
            String clean = WifiSecurity.cleanSsid(net.ssid);
            if (clean.isEmpty() || WifiSecurity.isPlaceholderSsid(clean)) continue;
            String key = clean.toLowerCase(Locale.US);
            Profile existing = profilesByLower.get(key);
            if (existing == null) {
                Profile p = new Profile(clean);
                p.open = net.open;
                p.psk = net.open ? "" : null;
                p.capabilities = net.open ? "[ESS]" : "[WPA2-PSK-CCMP][ESS]";
                profilesByLower.put(key, p);
                changed = true;
            }
        }
        if (changed) saveProfilesQuietly();
        return changed;
    }

    /**
     * Imports network={ssid="..."} entries from Android's /data/misc/wifi/wpa_supplicant.conf on
     * first upgrade from older HMG-API releases so previously saved networks are preserved.
     */
    public synchronized void importLegacyWpaSupplicantIfNeeded(File wpaConf) {
        if (!profilesByLower.isEmpty() || wpaConf == null || !wpaConf.isFile()) return;
        try {
            String raw = readText(wpaConf);
            String[] lines = raw.split("\n");
            boolean inNet = false;
            String ssid = null;
            String psk = null;
            boolean open = false;
            boolean changed = false;
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith("network={") || "network={".equals(t)) {
                    inNet = true;
                    ssid = null;
                    psk = null;
                    open = false;
                } else if (inNet && "}".equals(t)) {
                    if (ssid != null && !ssid.isEmpty()) {
                        String key = ssid.toLowerCase(Locale.US);
                        if (!profilesByLower.containsKey(key)) {
                            Profile p = new Profile(ssid);
                            p.open = open;
                            p.psk = open ? "" : psk;
                            p.capabilities = open ? "[ESS]" : "[WPA2-PSK-CCMP][ESS]";
                            profilesByLower.put(key, p);
                            changed = true;
                        }
                    }
                    inNet = false;
                } else if (inNet) {
                    if (t.startsWith("ssid=\"") && t.endsWith("\"") && t.length() >= 7) {
                        ssid = WifiSecurity.cleanSsid(t.substring(5));
                    } else if (t.startsWith("psk=\"") && t.endsWith("\"") && t.length() >= 6) {
                        psk = WifiSecurity.cleanSsid(t.substring(4));
                    } else if (t.startsWith("psk=") && t.length() == 68) {
                        psk = t.substring(4);
                    } else if (t.contains("key_mgmt=NONE")) {
                        open = true;
                    }
                }
            }
            if (changed) saveProfilesQuietly();
        } catch (Exception ignored) {
        }
    }

    public synchronized Profile upsertOwnerProfile(String ssid, String psk, boolean hasExplicitPsk,
                                                   String capabilities, boolean forceUseForHomeWifi) throws IOException {
        String clean = WifiSecurity.cleanSsid(ssid);
        if (!WifiSecurity.isValidSsid(clean)) {
            throw new IllegalArgumentException("Invalid network name (must be 1 to 32 bytes).");
        }
        String key = clean.toLowerCase(Locale.US);
        Profile p = profilesByLower.get(key);
        boolean isNew = (p == null);
        if (isNew) {
            p = new Profile(clean);
        }
        if (capabilities != null && !capabilities.isEmpty()) {
            p.capabilities = capabilities;
        }
        if (hasExplicitPsk) {
            boolean isOpen = (psk == null || psk.isEmpty());
            if (!isOpen && !WifiSecurity.isValidPassword(psk, WifiSecurity.SEC_WPA2_CCMP)) {
                throw new IllegalArgumentException("Wi-Fi password must be 8 to 63 characters (or 64 hex digits).");
            }
            p.open = isOpen;
            p.psk = isOpen ? "" : psk;
            if (p.capabilities == null || p.capabilities.isEmpty()) {
                p.capabilities = isOpen ? "[ESS]" : "[WPA2-PSK-CCMP][ESS]";
            }
        } else if (isNew) {
            int sec = WifiSecurity.classifySecurityMode(capabilities);
            if (sec == WifiSecurity.SEC_OPEN || capabilities == null || capabilities.isEmpty()) {
                p.open = true;
                p.psk = "";
                p.capabilities = "[ESS]";
            }
        }
        if (forceUseForHomeWifi || isNew) {
            p.useForHomeWifi = true;
        }
        profilesByLower.put(key, p);
        saveProfiles();
        return p;
    }

    public synchronized boolean setUseForHomeWifi(String ssid, boolean use) throws IOException {
        String clean = WifiSecurity.cleanSsid(ssid);
        if (clean.isEmpty()) return false;
        String key = clean.toLowerCase(Locale.US);
        Profile p = profilesByLower.get(key);
        if (p == null) {
            p = new Profile(clean);
            profilesByLower.put(key, p);
        }
        if (p.useForHomeWifi != use) {
            p.useForHomeWifi = use;
            saveProfiles();
        }
        return true;
    }

    public synchronized boolean removeProfile(String ssid) throws IOException {
        String clean = WifiSecurity.cleanSsid(ssid);
        if (clean.isEmpty()) return false;
        Profile removed = profilesByLower.remove(clean.toLowerCase(Locale.US));
        if (removed != null) {
            saveProfiles();
            return true;
        }
        return false;
    }

    public synchronized void recordConnectedNow(String ssid, long nowEpochSec) {
        Profile p = findProfile(ssid);
        if (p == null) return;
        // Avoid flash churn on brief flaps: persist only if >1 hour since last stamp
        if (Math.abs(nowEpochSec - p.lastConnectedSec) >= 3600L) {
            p.lastConnectedSec = nowEpochSec;
            saveProfilesQuietly();
        } else {
            p.lastConnectedSec = nowEpochSec;
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized void loadProfiles() {
        profilesByLower.clear();
        keepAwakeUnplugged = HomeWifiKeepAwake.DEFAULT_UNPLUGGED;
        keepAwakePlugged = HomeWifiKeepAwake.DEFAULT_PLUGGED;
        adbOverWifi = false;
        if (!profilesFile.isFile()) return;
        try {
            Map<String, Object> root = Json.object(readText(profilesFile));
            Object ku = root.get("keepAwakeUnplugged");
            if (ku != null) {
                keepAwakeUnplugged = HomeWifiKeepAwake.normalizeUnpluggedCode(String.valueOf(ku));
            }
            Object kp = root.get("keepAwakePlugged");
            if (kp != null) {
                keepAwakePlugged = HomeWifiKeepAwake.normalizePluggedCode(String.valueOf(kp));
            }
            adbOverWifi = Boolean.TRUE.equals(root.get("adbOverWifi"));
            Object listObj = root.get("profiles");
            if (!(listObj instanceof List)) return;
            for (Object item : (List<Object>) listObj) {
                if (!(item instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) item;
                Object sObj = m.get("ssid");
                if (sObj == null) continue;
                String clean = WifiSecurity.cleanSsid(String.valueOf(sObj));
                if (clean.isEmpty()) continue;
                Profile p = new Profile(clean);
                p.open = Boolean.TRUE.equals(m.get("open"));
                Object pskObj = m.get("psk");
                p.psk = pskObj == null ? (p.open ? "" : null) : String.valueOf(pskObj);
                Object capObj = m.get("capabilities");
                p.capabilities = capObj == null ? "" : String.valueOf(capObj);
                Object useObj = m.get("useForHomeWifi");
                if (useObj instanceof Boolean) {
                    p.useForHomeWifi = ((Boolean) useObj).booleanValue();
                }
                Object tsObj = m.get("lastConnectedSec");
                if (tsObj instanceof Number) {
                    p.lastConnectedSec = ((Number) tsObj).longValue();
                }
                profilesByLower.put(clean.toLowerCase(Locale.US), p);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveProfilesQuietly() {
        try {
            saveProfiles();
        } catch (IOException ignored) {
        }
    }

    public synchronized void saveProfiles() throws IOException {
        wifiDir.mkdirs();
        setOwnerOnlyDir(wifiDir);
        List<Object> arr = new ArrayList<Object>(profilesByLower.size());
        for (Profile p : profilesByLower.values()) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("ssid", p.ssid);
            m.put("open", Boolean.valueOf(p.open));
            if (p.psk != null) m.put("psk", p.psk);
            if (p.capabilities != null && !p.capabilities.isEmpty()) m.put("capabilities", p.capabilities);
            m.put("useForHomeWifi", Boolean.valueOf(p.useForHomeWifi));
            if (p.lastConnectedSec > 0) m.put("lastConnectedSec", Long.valueOf(p.lastConnectedSec));
            arr.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("keepAwakeUnplugged", keepAwakeUnplugged);
        root.put("keepAwakePlugged", keepAwakePlugged);
        root.put("adbOverWifi", Boolean.valueOf(adbOverWifi));
        root.put("profiles", arr);
        writeAtomic(profilesFile, Json.write(root) + "\n", false);
    }

    /** Deduplicates to strongest BSSID per SSID, sorts non-dashcams first then strongest RSSI. */
    public static List<SeenNetwork> deduplicateAndSortScans(List<SeenNetwork> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        Map<String, SeenNetwork> bestByLower = new LinkedHashMap<String, SeenNetwork>();
        for (SeenNetwork s : raw) {
            if (s == null || WifiSecurity.isPlaceholderSsid(s.ssid)) continue;
            String key = s.ssid.toLowerCase(Locale.US);
            SeenNetwork cur = bestByLower.get(key);
            if (cur == null || s.rssiDbm > cur.rssiDbm) {
                bestByLower.put(key, s);
            }
        }
        List<SeenNetwork> out = new ArrayList<SeenNetwork>(bestByLower.values());
        Collections.sort(out, new Comparator<SeenNetwork>() {
            @Override
            public int compare(SeenNetwork a, SeenNetwork b) {
                boolean aCam = WifiSecurity.isLikelyDashcamSsid(a.ssid);
                boolean bCam = WifiSecurity.isLikelyDashcamSsid(b.ssid);
                if (aCam != bCam) return aCam ? 1 : -1;
                return Integer.compare(b.rssiDbm, a.rssiDbm);
            }
        });
        return out;
    }

    public synchronized void saveSeenNetworks(List<SeenNetwork> deduped) {
        if (deduped == null || deduped.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (SeenNetwork s : deduped) {
            if (s.ssid.isEmpty()) continue;
            sb.append(s.freqMhz).append('\t')
              .append(s.rssiDbm).append('\t')
              .append(s.capabilities.replace('\t', ' ').replace('\n', ' ')).append('\t')
              .append(s.ssid).append('\n');
        }
        try {
            wifiDir.mkdirs();
            writeAtomic(seenFile, sb.toString(), false);
        } catch (IOException ignored) {
        }
    }

    public synchronized List<SeenNetwork> loadSeenNetworks() {
        List<SeenNetwork> list = new ArrayList<SeenNetwork>();
        if (!seenFile.isFile()) return list;
        try {
            for (String line : readText(seenFile).split("\n")) {
                String[] f = line.split("\t", 4);
                if (f.length < 4 || f[3].trim().isEmpty()) continue;
                int freq = Integer.parseInt(f[0].trim());
                int rssi = Integer.parseInt(f[1].trim());
                list.add(new SeenNetwork(f[3], "", freq, rssi, f[2]));
            }
        } catch (Exception ignored) {
        }
        return deduplicateAndSortScans(list);
    }

    /** Writes /data/hmgapi/wifi/lease (0600 root-only) BEFORE public/wifi.json so root daemons have gw/dns immediately. */
    public synchronized void saveLease(String gw, String dns1, String dns2, String ip) {
        try {
            wifiDir.mkdirs();
            setOwnerOnlyDir(wifiDir);
            String d1 = (dns1 != null && !dns1.isEmpty()) ? dns1 : (gw == null ? "" : gw);
            String line = (gw == null ? "" : gw) + " " + d1
                    + (dns2 != null && !dns2.isEmpty() ? (" " + dns2) : "")
                    + (ip != null && !ip.isEmpty() ? (" " + ip) : "") + "\n";
            if (leaseFile.isFile() && line.equals(readText(leaseFile))) {
                return;
            }
            writeAtomic(leaseFile, line, false);
        } catch (IOException ignored) {
        }
    }

    public synchronized void clearLease() {
        leaseFile.delete();
    }

    /** Writes /data/hmgapi/public/wifi.json (0644, strictly zero SSID: mod SDK contract). */
    public synchronized void writePublicStatus(String state, int rssi, int freq) {
        try {
            File pubDir = publicWifiJson.getParentFile();
            if (pubDir != null) pubDir.mkdirs();
            String json = "{\"state\": \"" + state + "\", \"rssi\": " + rssi + ", \"freq\": " + freq + "}\n";
            if (publicWifiJson.isFile() && json.equals(readText(publicWifiJson))) {
                return;
            }
            writeAtomic(publicWifiJson, json, true);
        } catch (IOException ignored) {
        }
    }

    /** Writes /data/hmgapi/public/keepawake.json (0644) so mods like OpenLink coordinate parked power cleanly. */
    public synchronized void writeKeepAwakeLease(String bootId,
                                                 HomeWifiKeepAwake.Verdict verdict,
                                                 WifiPlatform.VehicleSnapshot veh,
                                                 long nowElapsedMs) {
        try {
            File pubDir = publicKeepAwakeJson.getParentFile();
            if (pubDir != null) pubDir.mkdirs();
            String json = HomeWifiKeepAwake.formatLeaseJson(bootId, verdict, veh, nowElapsedMs);
            writeAtomic(publicKeepAwakeJson, json, true);
        } catch (IOException ignored) {
        }
    }

    public synchronized void clearKeepAwakeLease() {
        publicKeepAwakeJson.delete();
    }

    public synchronized HomeWifiKeepAwake.PeerLease readPeerOpenLinkLease() {
        try {
            if (!peerOpenLinkKeepAwakeJson.isFile()) return null;
            return HomeWifiKeepAwake.parsePeerLeaseJson(readText(peerOpenLinkKeepAwakeJson));
        } catch (Exception ignored) {
            return null;
        }
    }

    public synchronized void saveApMode(int apMode) {
        try {
            wifiDir.mkdirs();
            writeAtomic(apModeFile, (apMode == 1 ? "1" : "2") + "\n", false);
        } catch (IOException ignored) {
        }
    }

    public synchronized int loadApMode() {
        try {
            if (apModeFile.isFile()) {
                String s = readText(apModeFile).trim();
                if ("1".equals(s)) return 1;
            }
        } catch (Exception ignored) {
        }
        return 2;
    }

    public synchronized void saveActiveBootId(String bootId) {
        if (bootId == null || bootId.isEmpty()) return;
        try {
            wifiDir.mkdirs();
            writeAtomic(modeBootFile, bootId.trim() + "\n", false);
        } catch (IOException ignored) {
        }
    }

    public synchronized String loadActiveBootId() {
        try {
            if (modeBootFile.isFile()) return readText(modeBootFile).trim();
        } catch (Exception ignored) {
        }
        return "";
    }

    public synchronized void clearActiveBootId() {
        modeBootFile.delete();
    }

    private static void setOwnerOnlyDir(File d) {
        d.setReadable(false, false);
        d.setWritable(false, false);
        d.setExecutable(false, false);
        d.setReadable(true, true);
        d.setWritable(true, true);
        d.setExecutable(true, true);
    }

    private static void writeAtomic(File target, String content, boolean worldReadable) throws IOException {
        File tmp = new File(target.getParentFile(), "." + target.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
        tmp.setReadable(false, false);
        tmp.setWritable(false, false);
        tmp.setExecutable(false, false);
        if (worldReadable) {
            tmp.setReadable(true, false);
            tmp.setWritable(true, true);
        } else {
            tmp.setReadable(true, true);
            tmp.setWritable(true, true);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Failed to write " + target.getAbsolutePath());
        }
    }

    public static String readText(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}

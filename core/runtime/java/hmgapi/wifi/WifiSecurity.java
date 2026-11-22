package hmgapi.wifi;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure Wi-Fi SSID, WPA/WPA2 cipher classification, passphrase validation, and dashcam SSID
 * auto-detection helpers (no Android SDK imports so host unit tests run in --fast mode).
 */
public final class WifiSecurity {
    private WifiSecurity() {}

    public static final int SEC_UNSUPPORTED = -1;
    public static final int SEC_WPA2_CCMP = 0;
    public static final int SEC_WPA_MIXED = 1;
    public static final int SEC_OPEN = 2;

    private static final Pattern DASHCAM_SSID_PATTERN = Pattern.compile(
            "(?i).*(CAR_CAM|FITCAMX|CARDV|DASHCAM|NOVATEK|VIOFO|70MAI|BLACKVUE|THINKWARE|VANTRUE|DDPAI|NEXTBASE|GARMIN_DC|REXING|ROVE_R2).*");

    public static String cleanSsid(String ssid) {
        if (ssid == null) return "";
        String s = ssid;
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
            s = s.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s.replace("\r", "").replace("\n", "").replace("\0", "");
    }

    public static boolean isPlaceholderSsid(String ssid) {
        String c = cleanSsid(ssid).trim();
        return c.isEmpty() || "<unknown ssid>".equalsIgnoreCase(c) || "0x".equalsIgnoreCase(c);
    }

    public static boolean isValidSsid(String ssid) {
        if (ssid == null) return false;
        String c = cleanSsid(ssid);
        if (c.isEmpty() || c.indexOf('\t') >= 0) return false;
        for (int i = 0; i < c.length(); i++) {
            if (c.charAt(i) < 0x20) return false;
        }
        int utf8Len = c.getBytes(StandardCharsets.UTF_8).length;
        return utf8Len >= 1 && utf8Len <= 32;
    }

    public static String quoteSsid(String ssid) {
        String escaped = cleanSsid(ssid).replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    /**
     * Detects common dashcam Wi-Fi SSIDs (e.g., FITCAMX / CAR_CAM_*) so pre-existing dashcam
     * profiles in Android's Wi-Fi store default to Use for Home Wi-Fi = OFF automatically.
     */
    public static boolean isLikelyDashcamSsid(String ssid) {
        String c = cleanSsid(ssid);
        return !c.isEmpty() && DASHCAM_SSID_PATTERN.matcher(c).matches();
    }

    /**
     * Classifies AP security from ScanResult.capabilities so WPA2-CCMP routers get exact RSN/CCMP
     * negotiation without legacy WPA1/TKIP cipher fallback glitches on Broadcom bcmdhd.
     */
    public static int classifySecurityMode(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty()) {
            return SEC_WPA_MIXED;
        }
        String upper = capabilities.toUpperCase(Locale.US);
        boolean hasWpa2 = upper.contains("WPA2") || upper.contains("RSN");
        boolean hasWpa1 = upper.contains("WPA-PSK") || upper.contains("[WPA-");
        boolean hasPsk = upper.contains("PSK");
        boolean hasEap = upper.contains("EAP");
        boolean hasWep = upper.contains("WEP");

        if (hasEap && !hasPsk) return SEC_UNSUPPORTED;
        if (hasWep && !hasWpa2 && !hasWpa1 && !hasPsk) return SEC_UNSUPPORTED;
        if (!hasWpa2 && !hasWpa1 && !hasPsk) return SEC_OPEN;
        if (hasWpa2 && upper.contains("CCMP") && !upper.contains("TKIP") && !hasWpa1) {
            return SEC_WPA2_CCMP;
        }
        return SEC_WPA_MIXED;
    }

    public static String securityLabel(String capabilities) {
        if (capabilities == null || capabilities.trim().isEmpty()) return "WPA2";
        String upper = capabilities.toUpperCase(Locale.US);
        int mode = classifySecurityMode(capabilities);
        if (mode == SEC_UNSUPPORTED) {
            return upper.contains("EAP") ? "Enterprise (Unsupported)" : "WEP (Unsupported)";
        }
        if (mode == SEC_OPEN) return "Open";
        if (mode == SEC_WPA2_CCMP) return "WPA2";
        return "WPA/WPA2";
    }

    public static boolean isValidPassword(String password, int securityMode) {
        if (securityMode == SEC_OPEN) return true;
        if (password == null) return false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c == '\r' || c == '\n' || c == '\0') return false;
        }
        int len = password.length();
        if (len >= 8 && len <= 63) return true;
        if (len == 64) return isHex64(password);
        return false;
    }

    public static boolean isHex64(String s) {
        if (s == null || s.length() != 64) return false;
        for (int i = 0; i < 64; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    public static String formatPreSharedKey(String password) {
        if (password == null) return "\"\"";
        if (isHex64(password)) return password;
        String escaped = password.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    public static boolean shouldTriggerAuthFallback(int explicitAuthErrors, int handshakeDrops,
                                                    int exhaustedAssocRetries, int maxAssocRetries) {
        return explicitAuthErrors >= 2
                || handshakeDrops >= 2
                || (maxAssocRetries > 0 && exhaustedAssocRetries >= maxAssocRetries);
    }

    public static String intToIp(int ip) {
        if (ip == 0) return "0.0.0.0";
        return (ip & 0xFF) + "."
                + ((ip >> 8) & 0xFF) + "."
                + ((ip >> 16) & 0xFF) + "."
                + ((ip >> 24) & 0xFF);
    }

    public static String bandLabel(int freqMhz) {
        if (freqMhz <= 0) return "";
        return freqMhz < 3000 ? "2.4 GHz" : "5 GHz";
    }
}

package hmgapi.wifi;

import hmgapi.engine.Json;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java parked keep-awake policy and state tracker for Home Wi-Fi (and peer OpenLink
 * coordination via /data/hmgapi/public/openlink-keepawake.json).
 *
 * Supports separate durations for Unplugged vs. Plugged-In, mid-session plug/unplug transitions
 * with a 60s unplug sync grace window, and 2-sample debounced 12V Battery Guardian protection.
 */
public final class HomeWifiKeepAwake {
    public static final String DEFAULT_UNPLUGGED = "30m";
    public static final String DEFAULT_PLUGGED = "plugged";

    public static final long UNLIMITED_MS = -1L;
    public static final long UNPLUG_GRACE_MS = 60000L;
    public static final long PEER_FRESH_MS = 25000L;

    public static final int MIN_12V_UNPLUGGED = 65;
    public static final int MIN_12V_PLUGGED_IDLE = 55;
    public static final int MIN_12V_CHARGING_EMERGENCY = 50;
    public static final int LOW_12V_DEBOUNCE_SAMPLES = 2;

    public static final String[] UNPLUGGED_CODES = new String[] {
            "off", "5m", "15m", "30m", "1h", "2h", "4h", "8h"
    };

    public static final String[] PLUGGED_CODES = new String[] {
            "off", "5m", "15m", "30m", "1h", "2h", "4h", "8h", "plugged"
    };

    public static final class PeerLease {
        public final String bootId;
        public final boolean active;
        public final boolean unlimited;
        public final boolean cutBy12v;
        public final long expiresAtElapsedMs;
        public final int remainingSec;
        public final long updatedAtElapsedMs;

        public PeerLease(String bootId, boolean active, boolean unlimited, boolean cutBy12v,
                         long expiresAtElapsedMs, int remainingSec, long updatedAtElapsedMs) {
            this.bootId = bootId == null ? "" : bootId.trim();
            this.active = active;
            this.unlimited = unlimited;
            this.cutBy12v = cutBy12v;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
            this.remainingSec = remainingSec;
            this.updatedAtElapsedMs = updatedAtElapsedMs;
        }

        public boolean isFreshAndActive(String curBootId, long nowElapsedMs) {
            if (!active || cutBy12v) return false;
            if (curBootId != null && !curBootId.isEmpty() && !bootId.isEmpty() && !curBootId.equals(bootId)) {
                return false;
            }
            if (updatedAtElapsedMs > 0L && Math.abs(nowElapsedMs - updatedAtElapsedMs) > PEER_FRESH_MS) {
                return false;
            }
            if (!unlimited && expiresAtElapsedMs > 0L && nowElapsedMs >= expiresAtElapsedMs) {
                return false;
            }
            return true;
        }
    }

    public static final class Verdict {
        public final boolean holdPower;
        public final boolean shouldStayInClientMode;
        public final boolean unlimited;
        public final boolean cutBy12v;
        public final boolean extendedByPeer;
        public final String activeCode;
        public final int remainingSec; // -1 = unlimited, 0 = inactive/expired
        public final long expiresAtElapsedMs;
        public final boolean ownHoldPower;
        public final boolean ownUnlimited;
        public final long ownExpiresAtElapsedMs;

        public Verdict(boolean holdPower, boolean shouldStayInClientMode, boolean unlimited,
                       boolean cutBy12v, boolean extendedByPeer, String activeCode,
                       int remainingSec, long expiresAtElapsedMs) {
            this(holdPower, shouldStayInClientMode, unlimited, cutBy12v, extendedByPeer,
                    activeCode, remainingSec, expiresAtElapsedMs,
                    holdPower && !extendedByPeer, unlimited && !extendedByPeer, expiresAtElapsedMs);
        }

        public Verdict(boolean holdPower, boolean shouldStayInClientMode, boolean unlimited,
                       boolean cutBy12v, boolean extendedByPeer, String activeCode,
                       int remainingSec, long expiresAtElapsedMs,
                       boolean ownHoldPower, boolean ownUnlimited, long ownExpiresAtElapsedMs) {
            this.holdPower = holdPower;
            this.shouldStayInClientMode = shouldStayInClientMode;
            this.unlimited = unlimited;
            this.cutBy12v = cutBy12v;
            this.extendedByPeer = extendedByPeer;
            this.activeCode = activeCode == null ? "off" : activeCode;
            this.remainingSec = remainingSec;
            this.expiresAtElapsedMs = expiresAtElapsedMs;
            this.ownHoldPower = ownHoldPower;
            this.ownUnlimited = ownUnlimited;
            this.ownExpiresAtElapsedMs = ownExpiresAtElapsedMs;
        }
    }

    private long parkedStartMs = -1L;
    private long unplugGraceUntilMs = 0L;
    private boolean wasPluggedIn = false;
    private int low12vSamples = 0;
    private boolean cutBy12v = false;
    private boolean timerExpiredThisPark = false;

    public void reset() {
        parkedStartMs = -1L;
        unplugGraceUntilMs = 0L;
        wasPluggedIn = false;
        low12vSamples = 0;
        cutBy12v = false;
        timerExpiredThisPark = false;
    }

    public boolean isCutBy12v() {
        return cutBy12v;
    }

    public boolean isTimerExpiredThisPark() {
        return timerExpiredThisPark;
    }

    public Verdict evaluate(WifiPlatform.VehicleSnapshot veh,
                            long nowElapsedMs,
                            String curBootId,
                            String rawUnpluggedCode,
                            String rawPluggedCode,
                            PeerLease peer) {
        if (veh == null || veh.isPowerOn()) {
            reset();
            return new Verdict(false, false, false, false, false, "off", 0, 0L);
        }

        if (parkedStartMs < 0L) {
            parkedStartMs = nowElapsedMs;
            wasPluggedIn = veh.pluggedIn;
        } else if (wasPluggedIn && !veh.pluggedIn) {
            unplugGraceUntilMs = nowElapsedMs + UNPLUG_GRACE_MS;
            timerExpiredThisPark = false;
            wasPluggedIn = false;
        } else if (!wasPluggedIn && veh.pluggedIn) {
            // Newly plugged in while parked: clear any prior unplugged timer expiration!
            timerExpiredThisPark = false;
            wasPluggedIn = true;
        }

        // 12V Battery Guardian evaluation (2-sample debounce)
        if (veh.charging) {
            if (veh.bat12vSoc >= 0 && veh.bat12vSoc < MIN_12V_CHARGING_EMERGENCY) {
                low12vSamples++;
                if (low12vSamples >= LOW_12V_DEBOUNCE_SAMPLES) cutBy12v = true;
            } else {
                low12vSamples = 0;
                cutBy12v = false; // active HV charging restores 12V bus via ICCU/LDC
            }
        } else {
            int floor = veh.pluggedIn ? MIN_12V_PLUGGED_IDLE : MIN_12V_UNPLUGGED;
            boolean low = veh.saver12v || (veh.bat12vSoc >= 0 && veh.bat12vSoc < floor);
            if (low) {
                low12vSamples++;
                if (low12vSamples >= LOW_12V_DEBOUNCE_SAMPLES) {
                    cutBy12v = true;
                }
            } else {
                low12vSamples = 0;
            }
        }

        boolean peerCut12v = peer != null && peer.cutBy12v
                && (peer.updatedAtElapsedMs <= 0L || Math.abs(nowElapsedMs - peer.updatedAtElapsedMs) <= PEER_FRESH_MS);
        if (cutBy12v || peerCut12v) {
            return new Verdict(false, false, false, true, false,
                    veh.pluggedIn ? normalizePluggedCode(rawPluggedCode) : normalizeUnpluggedCode(rawUnpluggedCode),
                    0, 0L);
        }

        String uCode = normalizeUnpluggedCode(rawUnpluggedCode);
        String pCode = normalizePluggedCode(rawPluggedCode);
        String chosenCode = veh.pluggedIn ? pCode : uCode;
        long ownDurMs = durationMsForCode(chosenCode, veh.pluggedIn);

        boolean ownConfigured = !"off".equals(chosenCode);
        boolean ownUnlimited = (ownDurMs == UNLIMITED_MS);
        long ownExpiresAt = 0L;
        boolean ownActive = false;

        if (ownUnlimited) {
            ownActive = true;
        } else if (ownDurMs > 0L) {
            ownExpiresAt = Math.max(parkedStartMs + ownDurMs, unplugGraceUntilMs);
            ownActive = nowElapsedMs < ownExpiresAt;
        } else if (unplugGraceUntilMs > 0L && nowElapsedMs < unplugGraceUntilMs) {
            ownExpiresAt = unplugGraceUntilMs;
            ownActive = true;
        }

        boolean peerActive = peer != null && peer.isFreshAndActive(curBootId, nowElapsedMs);
        boolean peerUnlimited = peerActive && peer.unlimited;
        long peerExpiresAt = (peerActive && !peerUnlimited) ? peer.expiresAtElapsedMs : 0L;

        boolean effectiveHoldPower = ownActive || peerActive;
        boolean effectiveUnlimited = ownUnlimited || peerUnlimited;
        long effectiveExpiresAt = 0L;
        if (!effectiveUnlimited && effectiveHoldPower) {
            effectiveExpiresAt = Math.max(ownExpiresAt, peerExpiresAt);
        }

        int remSec = 0;
        if (effectiveUnlimited) {
            remSec = -1;
        } else if (effectiveHoldPower && effectiveExpiresAt > nowElapsedMs) {
            remSec = (int) ((effectiveExpiresAt - nowElapsedMs + 999L) / 1000L);
        }

        if (ownConfigured && !effectiveHoldPower) {
            timerExpiredThisPark = true;
        }

        // Stay in client Wi-Fi if:
        //  - Effective keep-awake (own or OpenLink peer) is currently active, OR
        //  - Own keep-awake is "off" (opportunistic Wi-Fi during the car's stock ~5 min ADM window)
        boolean stayClient = effectiveHoldPower || (!ownConfigured && !timerExpiredThisPark);
        boolean extendedByPeer = !ownActive && peerActive;

        return new Verdict(
                effectiveHoldPower,
                stayClient,
                effectiveUnlimited,
                false,
                extendedByPeer,
                chosenCode,
                remSec,
                effectiveExpiresAt,
                ownActive,
                ownUnlimited,
                ownExpiresAt);
    }

    public static String normalizeUnpluggedCode(String raw) {
        if (raw == null) return DEFAULT_UNPLUGGED;
        String c = raw.trim().toLowerCase(Locale.US);
        for (String valid : UNPLUGGED_CODES) {
            if (valid.equals(c)) return valid;
        }
        return DEFAULT_UNPLUGGED;
    }

    public static String normalizePluggedCode(String raw) {
        if (raw == null) return DEFAULT_PLUGGED;
        String c = raw.trim().toLowerCase(Locale.US);
        for (String valid : PLUGGED_CODES) {
            if (valid.equals(c)) return valid;
        }
        return DEFAULT_PLUGGED;
    }

    public static boolean isValidUnpluggedCode(String raw) {
        if (raw == null) return false;
        String c = raw.trim().toLowerCase(Locale.US);
        for (String valid : UNPLUGGED_CODES) {
            if (valid.equals(c)) return true;
        }
        return false;
    }

    public static boolean isValidPluggedCode(String raw) {
        if (raw == null) return false;
        String c = raw.trim().toLowerCase(Locale.US);
        for (String valid : PLUGGED_CODES) {
            if (valid.equals(c)) return true;
        }
        return false;
    }

    public static long durationMsForCode(String code, boolean pluggedIn) {
        if (code == null) return 30L * 60000L;
        String c = code.trim().toLowerCase(Locale.US);
        if ("off".equals(c)) return 0L;
        if ("5m".equals(c)) return 5L * 60000L;
        if ("15m".equals(c)) return 15L * 60000L;
        if ("30m".equals(c)) return 30L * 60000L;
        if ("1h".equals(c)) return 60L * 60000L;
        if ("2h".equals(c)) return 120L * 60000L;
        if ("4h".equals(c)) return 240L * 60000L;
        if ("8h".equals(c)) return 480L * 60000L;
        if ("plugged".equals(c)) return pluggedIn ? UNLIMITED_MS : (30L * 60000L);
        return 30L * 60000L;
    }

    public static String labelForCode(String code) {
        if (code == null) return "30 minutes";
        String c = code.trim().toLowerCase(Locale.US);
        if ("off".equals(c)) return "Off (car's default ~5 min)";
        if ("5m".equals(c)) return "5 minutes";
        if ("15m".equals(c)) return "15 minutes";
        if ("30m".equals(c)) return "30 minutes";
        if ("1h".equals(c)) return "1 hour";
        if ("2h".equals(c)) return "2 hours";
        if ("4h".equals(c)) return "4 hours";
        if ("8h".equals(c)) return "8 hours";
        if ("plugged".equals(c)) return "While plugged in (no time limit)";
        return c;
    }

    public static String formatLeaseJson(String bootId,
                                         Verdict v,
                                         WifiPlatform.VehicleSnapshot veh,
                                         long nowElapsedMs) {
        int ownRemSec = 0;
        if (v != null && v.ownUnlimited) {
            ownRemSec = -1;
        } else if (v != null && v.ownHoldPower && v.ownExpiresAtElapsedMs > nowElapsedMs) {
            ownRemSec = (int) ((v.ownExpiresAtElapsedMs - nowElapsedMs + 999L) / 1000L);
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("bootId", bootId == null ? "" : bootId);
        m.put("active", Boolean.valueOf(v != null && v.ownHoldPower));
        m.put("unlimited", Boolean.valueOf(v != null && v.ownUnlimited));
        m.put("cutBy12vFloor", Boolean.valueOf(v != null && v.cutBy12v));
        m.put("pluggedIn", Boolean.valueOf(veh != null && veh.pluggedIn));
        m.put("charging", Boolean.valueOf(veh != null && veh.charging));
        m.put("bat12vSoc", Integer.valueOf(veh != null ? veh.bat12vSoc : -1));
        m.put("configuredCode", v != null ? v.activeCode : "off");
        m.put("expiresAtElapsedMs", Long.valueOf(v != null ? v.ownExpiresAtElapsedMs : 0L));
        m.put("remainingSec", Integer.valueOf(ownRemSec));
        m.put("updatedAtElapsedMs", Long.valueOf(nowElapsedMs));
        return Json.write(m) + "\n";
    }

    public static PeerLease parsePeerLeaseJson(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            Map<String, Object> m = Json.object(raw);
            String boot = m.get("bootId") == null ? "" : String.valueOf(m.get("bootId"));
            boolean active = Boolean.TRUE.equals(m.get("active"));
            boolean unlimited = Boolean.TRUE.equals(m.get("unlimited"));
            boolean cut = Boolean.TRUE.equals(m.get("cutBy12vFloor"));
            long exp = m.get("expiresAtElapsedMs") instanceof Number
                    ? ((Number) m.get("expiresAtElapsedMs")).longValue() : 0L;
            int rem = m.get("remainingSec") instanceof Number
                    ? ((Number) m.get("remainingSec")).intValue() : 0;
            long upd = m.get("updatedAtElapsedMs") instanceof Number
                    ? ((Number) m.get("updatedAtElapsedMs")).longValue() : 0L;
            return new PeerLease(boot, active, unlimited, cut, exp, rem, upd);
        } catch (Exception e) {
            return null;
        }
    }
}

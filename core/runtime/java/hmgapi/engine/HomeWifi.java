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

    /** enabled mods asking for mod.json "wifi": {"projection": true} */
    @SuppressWarnings("unchecked")
    static List<String> projection(Map<String, Map<String, Object>> json, Set<String> on) {
        List<String> out = new ArrayList<String>();
        if (json == null || on == null) return out;
        for (String id : on) {
            Map<String, Object> mj = json.get(id);
            Object w = mj == null ? null : mj.get("wifi");
            if (w instanceof Map && Boolean.TRUE.equals(((Map<String, Object>) w).get("projection"))) out.add(id);
        }
        return out;
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

    /** the Wi-Fi keeper's services.txt line (state/wifi.txt written), or null when nothing is asked */
    static String service(Engine e, Map<String, Map<String, Object>> json, Set<String> on) throws Exception {
        File script = new File(e.stateDir, "wifi.sh");
        boolean home = on(e);
        List<String> proj = projection(json, on);
        StringBuilder asks = new StringBuilder();
        if (home) asks.append("home\n");
        for (String id : proj) asks.append("projection ").append(id).append('\n');
        ModRuntime.writeIfChanged(new File(e.stateDir, "wifi.txt"), asks.toString());
        if (!home) {
            new File(e.data, "public/wifi.json").delete();
            new File(e.data, "public/keepawake.json").delete();
        }
        if (asks.length() == 0) {
            script.delete();
            return null;
        }
        if (!(script.isFile() && Engine.read(script).equals(SCRIPT))) {
            File tmp = new File(e.stateDir, ".wifi.sh.tmp");
            Engine.write(tmp, SCRIPT);
            if (!tmp.renameTo(script)) throw new java.io.IOException("can't write wifi.sh");
        }
        String stage = proj.isEmpty() ? "android" : "boot";
        return "hmgapi wifi root " + stage + " yes script " + SCRIPT_PATH + " - " + Integer.toHexString(SCRIPT.hashCode());
    }

    private static boolean appliedHome(Engine e) {
        File txt = new File(e.stateDir, "wifi.txt");
        if (txt.isFile()) {
            try {
                for (String line : Engine.read(txt).split("\n")) {
                    if ("home".equals(line.trim())) return true;
                }
                return false;
            } catch (Exception ignored) {
                return false;
            }
        }
        return new File(e.stateDir, "wifi.sh").isFile();
    }

    /** the plan's lines */
    static List<String> describe(Engine e, Map<String, Map<String, Object>> json, Set<String> on) {
        List<String> out = new ArrayList<String>();
        boolean home = on(e);
        if (home != appliedHome(e)) {
            out.add("change   home Wi-Fi -> " + (home ? "on" : "off") + " [hmgapi]");
        }
        out.add("home Wi-Fi  " + (home ? "on: while the car is off, the hotspot off and the car's Wi-Fi client on the saved networks" : "off"));
        List<String> proj = projection(json, on);
        for (String id : proj) out.add("wifi     the projection AP up from boot, once each boot, unless a Wi-Fi client is on [" + id + "]");
        for (Map.Entry<String, String> m : modes(e, json, on).entrySet()) {
            String mode = m.getValue();
            out.add("network  " + ("home".equals(mode) ? "home Wi-Fi only (no cellular)" : "allow".equals(mode)
                    ? "may use cellular (overrides.allow_cellular, at your own risk)" : "no cellular" + (asks(json.get(m.getKey())) ? " (home Wi-Fi is off)" : ""))
                    + " [" + m.getKey() + "]");
        }
        return out;
    }

    static final String SCRIPT = Network.lines(
        "# HMG-API's Wi-Fi keeper wrapper (written by hmgapi.engine.HomeWifi).",
        "#   sh wifi.sh [hash]          service mode: ensure projection AP if asked, then HomeWifiDaemon or QuickBoot watch",
        "#   sh wifi.sh <verb> [args]   offline CLI fallback when @hmgapi-wifi isn't listening yet",
        "set -f",
        "S=${0%/*}",
        "JAR=/system/framework/hmgapi-hook.jar",
        "ASKS=$S/wifi.txt",
        "W=$S/wifi",
        "case $1 in",
        "    status|scan|saved|seen|join|connect|use|adb|keepawake|forget|now|back|down|log|log-usb|log-clear)",
        "        CLASSPATH=$JAR exec app_process /system/bin hmgapi.wifi.HomeWifiDaemon cli \"$@\"",
        "        ;;",
        "esac",
        "wants() { [ -f $ASKS ] && while read -r w rest; do [ \"$w\" = \"$1\" ] && { echo \"${rest:-$w}\"; }; done < $ASKS; }",
        "boot() { cat /proc/sys/kernel/random/boot_id 2>/dev/null; }",
        "now() { u=0; [ -r /proc/uptime ] && read -r u rest < /proc/uptime; u=${u%.*}; echo ${u:-0}; }",
        "while ! service check wifi 2>/dev/null | grep -q ': found'; do sleep 2; done",
        "if [ -n \"$(wants projection)\" ]; then",
        "    mkdir -p $W",
        "    if [ \"$(cat $W/ap.boot 2>/dev/null)\" != \"$(boot)\" ]; then",
        "        if CLASSPATH=$JAR app_process /system/bin hmgapi.engine.WifiAp ensure-projection >/dev/null 2>&1; then",
        "            boot > $W/ap.boot",
        "        fi",
        "    fi",
        "fi",
        "if [ -n \"$(wants home)\" ]; then",
        "    CLASSPATH=$JAR exec app_process /system/bin --nice-name=hmgapi.wifi hmgapi.wifi.HomeWifiDaemon",
        "fi",
        "while :; do",
        "    t0=$(now); sleep 30; d=$(($(now) - t0))",
        "    if [ $d -gt 90 ] && [ -n \"$(wants projection)\" ]; then",
        "        rm -f $W/ap.boot",
        "        if CLASSPATH=$JAR app_process /system/bin hmgapi.engine.WifiAp ensure-projection >/dev/null 2>&1; then",
        "            boot > $W/ap.boot",
        "        fi",
        "    fi",
        "done");
}

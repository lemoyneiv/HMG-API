package hmgapi.ble;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Caller authentication for {@code @hmgapi-ble} connections inside {@code com.android.bluetooth}.
 *
 * Pure Java (no {@code android.*} imports) so all authorization rules can be tested
 * directly on a host JDK in {@code core/runtime/ble-test.sh}.
 *
 * An app or service may open a BLE session only if its mod is enabled and declared
 * {@code "ble": {"central": true}} in {@code mod.json}:
 *   - a mod's service (or child process it spawned) is matched by its kernel session id
 *     from {@code /proc/<pid>/stat} against {@code /data/hmgapi/public/services.txt}
 *     (written by {@code hmgapid}, which runs every service in its own {@code setsid()}
 *     session); even a {@code "user": "root"} service of a mod without BLE is refused;
 *   - standalone {@code root} (uid 0) and {@code shell} (uid 2000, {@code adb}) are
 *     allowed as {@code "root"};
 *   - an app uid ({@code >= 10000}) is matched strictly by {@code PackageManager}
 *     {@code getPackagesForUid(uid)} against {@code /data/hmgapi/public/state.json}.
 *
 * Process {@code cmdline} is never consulted, as any process can rewrite its own arguments.
 */
public final class BleAuth {
    private BleAuth() {}

    public static final int ROOT_UID = 0;
    public static final int SHELL_UID = 2000;
    private static final File PUBLIC_SERVICES = new File("/data/hmgapi/public/services.txt");

    public interface EnvReader {
        String readProcStat(int pid);
        String readServices();
    }

    public static final EnvReader SYSTEM_ENV = new EnvReader() {
        @Override
        public String readProcStat(int pid) {
            return readFileBounded(new File("/proc/" + pid + "/stat"), 512);
        }

        @Override
        public String readServices() {
            return readFileBounded(PUBLIC_SERVICES, 16384);
        }
    };

    static String readFileBounded(File f, int maxBytes) {
        FileInputStream in = null;
        try {
            if (!f.isFile()) return null;
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            byte[] buf = new byte[256];
            int n;
            while (out.size() < maxBytes && (n = in.read(buf, 0, Math.min(buf.length, maxBytes - out.size()))) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
    }

    public static final class State {
        public static final State EMPTY = new State(
                Collections.<String>emptySet(),
                Collections.<String, String>emptyMap(),
                Collections.<String>emptySet());

        public final Set<String> enabledBleMods;
        public final Map<String, String> appToMod;
        public final Set<String> allMods;

        public State(Set<String> enabledBleMods, Map<String, String> appToMod, Set<String> allMods) {
            this.enabledBleMods = Collections.unmodifiableSet(new HashSet<String>(enabledBleMods));
            this.appToMod = Collections.unmodifiableMap(new HashMap<String, String>(appToMod));
            this.allMods = Collections.unmodifiableSet(new HashSet<String>(allMods));
        }

        /** Builds a snapshot from the {@code "mods"} object in {@code /data/hmgapi/public/state.json}. */
        @SuppressWarnings("unchecked")
        public static State fromModsMap(Map<String, Object> mods) {
            if (mods == null || mods.isEmpty()) return EMPTY;
            Set<String> enabledBle = new HashSet<String>();
            Map<String, String> apps = new HashMap<String, String>();
            Set<String> all = new HashSet<String>();
            for (Map.Entry<String, Object> e : mods.entrySet()) {
                String id = e.getKey();
                if (id == null) continue;
                all.add(id);
                if (!(e.getValue() instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) e.getValue();
                boolean on = Boolean.TRUE.equals(m.get("enabled"));
                boolean ble = Boolean.TRUE.equals(m.get("ble"));
                if (on && ble) enabledBle.add(id);
                Object pkgList = m.get("apps");
                if (pkgList instanceof List) {
                    for (Object p : (List<Object>) pkgList) {
                        if (p != null) apps.put(String.valueOf(p), id);
                    }
                }
            }
            return new State(enabledBle, apps, all);
        }
    }

    /**
     * Returns the owning mod id (or {@code "root"} for standalone root/adb), or {@code null}
     * when the caller is not allowed to use BLE.
     */
    public static String authorize(int uid, int pid, String[] uidPackages, State state, EnvReader env) {
        if (state == null) state = State.EMPTY;
        String svcMod = modOfService(pid, env);
        if (svcMod != null) {
            return state.enabledBleMods.contains(svcMod) ? svcMod : null;
        }
        if (uid == ROOT_UID || uid == SHELL_UID) {
            return "root";
        }
        if (uid >= 10000 && uidPackages != null) {
            for (String pkg : uidPackages) {
                String mod = state.appToMod.get(pkg);
                if (mod != null && state.enabledBleMods.contains(mod)) {
                    return mod;
                }
            }
        }
        return null;
    }

    static String modOfService(int pid, EnvReader env) {
        if (pid <= 0 || env == null) return null;
        Map<Integer, String> byPid = parseServices(env.readServices());
        if (byPid.isEmpty()) return null;
        String direct = byPid.get(Integer.valueOf(pid));
        if (direct != null) return direct;
        int sid = sessionLeader(env.readProcStat(pid));
        if (sid > 0 && sid != pid) {
            return byPid.get(Integer.valueOf(sid));
        }
        return null;
    }

    static Map<Integer, String> parseServices(String raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<Integer, String> out = new HashMap<Integer, String>();
        int start = 0;
        int len = raw.length();
        while (start < len) {
            int end = raw.indexOf('\n', start);
            if (end < 0) end = len;
            String line = raw.substring(start, end).trim();
            start = end + 1;
            if (line.isEmpty()) continue;
            int sp = line.indexOf(' ');
            if (sp <= 0 || sp + 1 >= line.length()) continue;
            try {
                int svcPid = Integer.parseInt(line.substring(0, sp));
                String mod = line.substring(sp + 1).trim();
                if (svcPid > 0 && !mod.isEmpty() && mod.indexOf(' ') < 0) {
                    out.put(Integer.valueOf(svcPid), mod);
                }
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    static int sessionLeader(String stat) {
        if (stat == null) return -1;
        int rparen = stat.lastIndexOf(')');
        if (rparen < 0 || rparen + 2 >= stat.length()) return -1;
        String rest = stat.substring(rparen + 1).trim();
        String[] fields = rest.split(" +");
        // after "(comm)": [0]=state [1]=ppid [2]=pgrp [3]=session
        if (fields.length < 4) return -1;
        try {
            return Integer.parseInt(fields[3]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

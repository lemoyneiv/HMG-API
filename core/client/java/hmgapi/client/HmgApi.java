package hmgapi.client;

import android.content.Context;
import android.content.Intent;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HMG-API for a mod's own app and services (hmgapi-client.jar: compile against it
 * and bundle it; plain Java, Android 4.4).
 *
 * It talks to hmgapid, HMG-API's root daemon, which knows who is asking: an app
 * listed in an enabled mod's "apps", or a process of one of its "services". Each
 * gets its own mod's settings, services, log, USB folders and the vehicle readings
 * its mod.json lists ("vehicle": {"read": [...]}), nothing else;
 * NEMESIS root or `service call` numbers aren't needed for these.
 *
 *   HmgApi api = HmgApi.connect();
 *   String mod = api.mod();                       // "dashcam", or null: not a mod's app
 *   JSONObject s = api.settings();                 // the owner's values over the defaults
 *   for (String path : api.usbFolders()) ...       // "/storage/usb0/Dashcam"
 *   Map<String, String> v = api.vehicle("speed", "gear.park");   // {"speed": "42.5", "gear.park": "0"}
 *   api.restartService("recorder");
 *   HmgApi.toast(context, "Recording");            // the car's own notice
 *
 * Calls block (up to 10 s): keep them off the UI thread. They throw HmgApiException
 * when hmgapid says no (code 13: this app isn't an enabled mod's) or isn't running.
 * Broadcasts: ACTION_USB_FOLDER (to the mod's apps; extras folder, path, mounted)
 * and ACTION_STATE_CHANGED (any mod on/off or settings saved).
 */
public final class HmgApi {
    public static final String ACTION_USB_FOLDER = "hmgapi.intent.action.USB_FOLDER";
    public static final String ACTION_STATE_CHANGED = "hmgapi.intent.action.STATE_CHANGED";
    /** phone projection events (mod.json "projection": {"events": true}): extras "event", "value" or "type" + "data" */
    public static final String ACTION_PROJECTION = "hmgapi.intent.action.PROJECTION";
    /**
     * car events (mod.json "car_events"): extra EXTRA_EVENT, one of EVENT_*. An ordered broadcast:
     * HMG-API waits for the receiver (goAsync() and finish() for longer work; a shutdown leaves a
     * few seconds). Declare the receiver with android:permission="android.permission.DEVICE_POWER"
     * so only HMG-API (or the system) can send it.
     */
    public static final String ACTION_CAR_EVENT = "hmgapi.intent.action.CAR_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EVENT_PARKED = "parked", EVENT_UNPARKED = "unparked", EVENT_SHUTDOWN = "shutdown", EVENT_WAKE = "wake";
    public static final String EXTRA_FOLDER = "folder", EXTRA_PATH = "path", EXTRA_MOUNTED = "mounted";

    /** hmgapid's answer that isn't a success */
    public static final class HmgApiException extends IOException {
        public final int code;

        HmgApiException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** One service of this mod: name, state (waiting, running, exited, failed, stopped), pid. */
    public static final class Service {
        public final String name, state;
        public final int pid, fails;

        Service(String name, String state, int pid, int fails) {
            this.name = name;
            this.state = state;
            this.pid = pid;
            this.fails = fails;
        }
    }

    private int timeoutMs = 10000;

    private HmgApi() {}

    public static HmgApi connect() {
        return new HmgApi();
    }

    /** true if HMG-API's daemon answers (HMG-API is installed and running) */
    public boolean available() {
        try {
            return call("ping").code == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** this app's (or service's) mod id, or null if it isn't one an enabled mod declared */
    public String mod() throws IOException {
        Result r = call("whoami");
        return r.code == 0 && !"root".equals(r.text.trim()) ? r.text.trim() : null;
    }

    /** the mod's settings (settings.json defaults, the owner's values over them) */
    public JSONObject settings() throws IOException {
        String t = ok(call("setting-get")).trim();
        try {
            return new JSONObject(t.isEmpty() ? "{}" : t);
        } catch (JSONException e) {
            throw new IOException("settings aren't JSON: " + e.getMessage());
        }
    }

    /** saves the owner's values (all of them: this replaces the file); mods' hooks see them at once */
    public void saveSettings(JSONObject values) throws IOException {
        ok(call("setting-set", values.toString()));
    }

    public List<Service> services() throws IOException {
        List<Service> out = new ArrayList<Service>();
        for (String l : ok(call("services")).split("\n")) {
            String[] f = l.trim().split(" ");
            if (f.length == 5) out.add(new Service(f[1], f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4])));
        }
        return Collections.unmodifiableList(out);
    }

    public void restartService(String name) throws IOException { ok(call("service-restart", name)); }

    /** stopped until started again (or the head unit restarts) */
    public void stopService(String name) throws IOException { ok(call("service-stop", name)); }

    public void startService(String name) throws IOException { ok(call("service-start", name)); }

    /** the end of the service's output (64 KB at most) */
    public String serviceLog(String name) throws IOException { return ok(call("service-log", name)); }

    /** the mod's USB folders on the sticks in now ("/storage/usb0/Dashcam"), checked as root */
    public List<String> usbFolders() throws IOException {
        List<String> out = new ArrayList<String>();
        for (String l : ok(call("usb")).split("\n")) if (!l.trim().isEmpty()) out.add(l.trim());
        return out;
    }

    /**
     * Vehicle readings by name (HMG-API's list: speed, gear.park, steering.angle,
     * hv.soc, battery12v.soc, ...), each one the mod declared. Values are numbers as
     * text ("42.5"; flags "0"/"1"), or "-" when the car doesn't answer. A value is at
     * most its reading's freshness window old (100 ms for steering, 30 s for the 12V
     * battery): asking faster gives the same value, and costs the car nothing.
     */
    public Map<String, String> vehicle(String... names) throws IOException {
        String[] args = new String[names.length + 1];
        args[0] = "vehicle";
        System.arraycopy(names, 0, args, 1, names.length);
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (String l : ok(call(args)).split("\n")) {
            int sp = l.indexOf(' ');
            if (sp > 0) out.put(l.substring(0, sp), l.substring(sp + 1).trim());
        }
        return out;
    }

    /**
     * A vehicle command the mod declared ("vehicle": {"commands": [...]}):
     * command("charge_door", "open"), command("seat_heat", "driver", "low"),
     * command("conditioning", "start"), command("conditioning", "auto-start", "37", "25"),
     * command("conditioning", "route", "30", "20"), command("ambient_light", "color", "ff8000"),
     * command("guide_lines", "off"). Each has the guard its review gave it (park only,
     * one holder ...); a refusal throws HmgApiException with the reason. Returns the
     * car's "ok ..." line.
     */
    public String command(String name, String... args) throws IOException {
        String[] a = new String[args.length + 2];
        a[0] = "vehicle-do";
        a[1] = name;
        System.arraycopy(args, 0, a, 2, args.length);
        return ok(call(a)).trim();
    }

    /** the car's own on-screen notice (shown over any app, CarPlay and Android Auto too) */
    /**
     * Home Wi-Fi as this mod sees it (mod.json "wifi": {"home": true}): {"state": "up", "rssi": -61,
     * "freq": 2437}. state: up, waiting (the car isn't parked: home Wi-Fi is only on while it is),
     * searching, nonetworks, stock (the car's own Wi-Fi settings have the Wi-Fi), or off. Mods never use
     * the car's mobile data, so without home Wi-Fi up a mod has no internet; check this before a
     * download (the car events' "parked" is the moment). No network name: that's the owner's.
     */
    public static JSONObject homeWifi() {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream("/data/hmgapi/public/wifi.json");
            try {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return new JSONObject(b.toString("UTF-8"));
            } finally {
                in.close();
            }
        } catch (Exception e) {
            JSONObject off = new JSONObject();
            try {
                off.put("state", "off");
            } catch (JSONException x) {
                // can't happen
            }
            return off;
        }
    }

    /** true when home Wi-Fi is up: this mod's traffic (if it asked for it) has somewhere to go */
    public static boolean homeWifiUp() {
        return "up".equals(homeWifi().optString("state"));
    }

    public static void toast(Context ctx, String text) {
        ctx.sendBroadcast(new Intent("com.hkmc.intent.action.TOAST")
                .putExtra("com.hkmc.intent.extra.TOAST_MESSAGE", text)
                .putExtra("com.hkmc.intent.extra.TOAST_POPUPSHOWING", true));
    }

    public HmgApi timeout(int ms) {
        timeoutMs = ms;
        return this;
    }

    // ---- the wire: "LFD1", u32 argc, argc x (u32 len, bytes); back 'O' frames, then 'X' u32 code

    public static final class Result {
        public int code = -1;
        public String text = "";
    }

    static String ok(Result r) throws HmgApiException {
        if (r.code != 0) throw new HmgApiException(r.code, r.text.trim().isEmpty() ? "hmgapid: code " + r.code : r.text.trim());
        return r.text;
    }

    /** one raw hmgapid op */
    public Result call(String... args) throws IOException {
        int[] backoffMs = { 80, 160, 320 };
        Result r = callOnce(args);
        for (int attempt = 0; r.code == 75 && attempt < backoffMs.length; attempt++) {
            try {
                Thread.sleep(backoffMs[attempt]);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            r = callOnce(args);
        }
        return r;
    }

    private Result callOnce(String... args) throws IOException {
        LocalSocket s = new LocalSocket();
        try {
            try {
                s.connect(new LocalSocketAddress("hmgapid", LocalSocketAddress.Namespace.ABSTRACT));
            } catch (IOException e) {
                throw new HmgApiException(-1, "HMG-API isn't running (hmgapid)");
            }
            s.setSoTimeout(timeoutMs);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[] { 'L', 'F', 'D', '1' });
            u32(req, args.length);
            for (String a : args) {
                byte[] b = a.getBytes("UTF-8");
                u32(req, b.length);
                req.write(b);
            }
            OutputStream out = s.getOutputStream();
            out.write(req.toByteArray());
            out.flush();
            DataInputStream in = new DataInputStream(s.getInputStream());
            Result r = new Result();
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            while (true) {
                int type = in.read();
                if (type < 0) break;
                byte[] n = new byte[4];
                in.readFully(n);
                int len = (n[0] & 0xff) | (n[1] & 0xff) << 8 | (n[2] & 0xff) << 16 | (n[3] & 0xff) << 24;
                if (len < 0 || len > (16 << 20)) throw new IOException("hmgapid: bad frame");
                byte[] data = new byte[len];
                in.readFully(data);
                if (type == 'O') text.write(data);
                else if (type == 'X' && len == 4) {
                    r.code = (data[0] & 0xff) | (data[1] & 0xff) << 8 | (data[2] & 0xff) << 16 | (data[3] & 0xff) << 24;
                    break;
                }
            }
            r.text = text.toString("UTF-8");
            return r;
        } finally {
            s.close();
        }
    }

    private static void u32(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write(v >> 8 & 0xff);
        o.write(v >> 16 & 0xff);
        o.write(v >> 24 & 0xff);
    }
}

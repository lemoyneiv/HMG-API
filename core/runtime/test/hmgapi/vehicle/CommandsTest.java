package hmgapi.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The command guards against a fake car: what reaches the car, what's refused. */
public final class CommandsTest {
    static final class FakeCar implements Commands.Car {
        boolean park = true, allowed;
        long now = 100000;
        int lines = 2;
        int[] light;
        final List<String> calls = new ArrayList<String>();

        public boolean park() { return park; }
        public void chargeDoor(boolean close) { calls.add("door " + close); }
        public void seatStep(String seat, int step) { calls.add(seat + " " + step); }
        public int seatOption(String seat) { return seat.startsWith("rear") ? 1 : 6; }
        public void wheelHeat(int on) { calls.add("wheel " + on); }
        public void conditioningRoute(int km) { calls.add("route " + km); }
        public void conditioningTime(int minutes) { calls.add("time " + minutes); }
        public void conditioningStation(int a, int b) { calls.add("station " + a + "," + b); }
        int ready = 1, setting = 2, indicator = 0, packC = Integer.MIN_VALUE;
        String model = "CVEV";
        public int ready() { return ready; }
        public int conditioningSetting() { return setting; }
        public int conditioningIndicator() { return indicator; }
        public int packTempMin() { return packC; }
        public String model() { return model; }
        public void lightBrightness(int level) { calls.add("bright " + level); }
        public void lightColor(int r, int g, int b) { calls.add("rgb " + r + "," + g + "," + b); }
        public void lightStatus() { calls.add("light status"); }
        public int[] lightState() { return light; }
        public int guideLines() { return lines; }
        public void setGuideLines(int state) { calls.add("lines " + state); lines = state; }
        public void hideStartupNotice() { calls.add("notice hidden"); }
        public boolean noticeAllowed() { return allowed; }
        boolean lightBlocked;
        public boolean lightBlocked() { return lightBlocked; }
        boolean askFirst;
        final Map<String, String> answers = new HashMap<String, String>();
        public boolean askFirst() { return askFirst; }
        public String answer(String mod, String cmd) { return answers.get(mod + " " + cmd); }
        public void ask(String mod, String cmd) { calls.add("ask " + mod + " " + cmd); }
        public long now() { return now; }
        public void pause(long ms) { now += ms; }
        public void log(String line) {}
    }

    static int fail;

    static void check(String what, boolean ok, boolean got) {
        check(what, String.valueOf(ok), String.valueOf(got));
    }

    static void check(String what, String want, String got) {
        if (want.equals(got)) System.out.println("ok    " + what);
        else {
            System.out.println("FAIL  " + what + ": wanted [" + want + "], got [" + got + "]");
            fail = 1;
        }
    }

    static String calls(FakeCar c) {
        String s = String.join(" | ", c.calls);
        c.calls.clear();
        return s;
    }

    static Map<String, Set<String>> declared(String... modCmd) {
        Map<String, Set<String>> m = new HashMap<String, Set<String>>();
        for (String mc : modCmd) {
            String[] w = mc.split(" ");
            if (!m.containsKey(w[0])) m.put(w[0], new HashSet<String>());
            m.get(w[0]).add(w[1]);
        }
        return m;
    }

    public static void main(String[] a) {
        FakeCar car = new FakeCar();
        Commands c = new Commands(car);
        check("charge door opens (false, as Charge Port Button)", "ok charge door open", c.handle("port", "charge_door", new String[] { "open" }));
        check("... the car got it", "door false", calls(car));
        check("once every 5 s", "refused: once every 5 s", c.handle("port", "charge_door", new String[] { "close" }));
        car.now += 5000;
        car.park = false;
        check("not in P: refused", "refused: the car isn't in P", c.handle("port", "charge_door", new String[] { "close" }));
        check("... nothing reached the car", "", calls(car));
        car.park = true;

        check("seat heat: named steps", "ok driver seat heat medium", c.handle("heat", "seat_heat", new String[] { "driver", "medium" }));
        check("... as the car's step 7", "driver 7", calls(car));
        check("seat heat: odd step refused", "refused: off, low, medium or high", c.handle("heat", "seat_heat", new String[] { "passenger", "max" }));
        check("rear seats too", "ok rear_left seat heat high", c.handle("heat", "seat_heat", new String[] { "rear_left", "high" }));
        check("... step 8 to the rear left seat", "rear_left 8", calls(car));
        check("a two-level seat: no medium", "refused: this seat has two levels (low, high)", c.handle("heat", "seat_heat", new String[] { "rear_right", "medium" }));
        check("no ventilation in the rear", "refused: the rear seats have no ventilation", c.handle("heat", "seat_vent", new String[] { "rear_left", "low" }));
        check("an unknown seat refused", true, c.handle("heat", "seat_heat", new String[] { "middle", "low" }).startsWith("refused"));
        check("seat ventilation: named steps", "ok passenger seat ventilation high", c.handle("heat", "seat_vent", new String[] { "passenger", "high" }));
        check("... as the car's step 5", "passenger 5", calls(car));
        check("... off is the seat's off step", "ok driver seat ventilation off", c.handle("heat", "seat_vent", new String[] { "driver", "off" }));
        check("... step 2", "driver 2", calls(car));
        check("wheel heat", "ok wheel heat on", c.handle("heat", "wheel_heat", new String[] { "on" }));
        check("... 1 to the car", "wheel 1", calls(car));

        check("conditioning start", "ok conditioning started", c.handle("pad", "conditioning", new String[] { "start" }));
        check("... route data, then the station event (Launchpad's order)", "route 9 | time 18 | station 2,3", calls(car));
        check("a second start doesn't start again", "ok already conditioning (pad)", c.handle("egmp", "conditioning", new String[] { "auto-start" }));
        car.now += 5000;
        c.tick(declared("pad conditioning"));
        check("kept up every 5 s: the route data only, the station event (the start) isn't repeated", "route 9 | time 18", calls(car));
        check("stop", "ok conditioning stopped", c.handle("pad", "conditioning", new String[] { "stop" }));
        check("... cleared as Launchpad does", "route 2047 | time 1023 | station 0,0", calls(car));
        check("an automatic start after a stop waits", "refused: stopped by hand; automatic starts wait for a manual start", c.handle("egmp", "conditioning", new String[] { "auto-start" }));
        check("a manual start is fine", "ok conditioning started", c.handle("pad", "conditioning", new String[] { "start" }));
        calls(car);
        check("... and automatic starts are allowed again", "ok already conditioning (pad)", c.handle("egmp", "conditioning", new String[] { "auto-start" }));
        c.tick(declared("egmp conditioning"));
        check("the mod that started it is turned off: stopped", "route 2047 | time 1023 | station 0,0", calls(car));

        // route values: an automatic request to a real charger (EV routing)
        Commands r = new Commands(car);
        check("auto-start with route values", "ok conditioning started", r.handle("egmp", "conditioning", new String[] { "auto-start", "37", "25" }));
        check("... sent as the route", "route 37 | time 25 | station 2,3", calls(car));
        check("the holder updates them", "ok conditioning route 30 km 20 min", r.handle("egmp", "conditioning", new String[] { "route", "30", "20" }));
        check("... at once", "route 30 | time 20", calls(car));
        car.now += 5000;
        r.tick(declared("egmp conditioning"));
        check("kept up with the current values (the route data only)", "route 30 | time 20", calls(car));
        check("another mod can't change them", "refused: only the mod conditioning changes its route (egmp)", r.handle("pad", "conditioning", new String[] { "route", "1", "1" }));
        check("values past what the navigation sends", "refused: route values are <km 0-2046> <minutes 0-1022>", r.handle("egmp", "conditioning", new String[] { "route", "2047", "20" }));
        check("not numbers", true, r.handle("egmp", "conditioning", new String[] { "route", "ten", "20" }).startsWith("refused: route values"));
        check("route needs both values", true, r.handle("egmp", "conditioning", new String[] { "route" }).startsWith("refused"));
        check("an unknown subcommand is named as one, values or not", "refused: conditioning start|auto-start|stop|route|status", r.handle("egmp", "conditioning", new String[] { "foo", "1" }));
        calls(car);
        check("the holder ends its own automatic request (arrived)", "ok conditioning stopped", r.handle("egmp", "conditioning", new String[] { "stop" }));
        calls(car);
        check("... which isn't a manual stop: the next automatic start goes", "ok conditioning started", r.handle("egmp", "conditioning", new String[] { "auto-start", "12", "9" }));
        check("... with its own values", "route 12 | time 9 | station 2,3", calls(car));
        check("the owner stops it", "ok conditioning stopped", r.handle("-", "conditioning", new String[] { "stop" }));
        calls(car);
        check("... a manual stop: automatic starts wait", "refused: stopped by hand; automatic starts wait for a manual start", r.handle("egmp", "conditioning", new String[] { "auto-start", "12", "9" }));
        check("route without conditioning", "refused: only the mod conditioning changes its route (not conditioning)", r.handle("egmp", "conditioning", new String[] { "route", "1", "1" }));
        check("a manual start without values: the defaults", "ok conditioning started", r.handle("pad", "conditioning", new String[] { "start" }));
        check("... 9 km, 18 min", "route 9 | time 18 | station 2,3", calls(car));
        check("a manual start's own stop still counts as manual", "ok conditioning stopped", r.handle("pad", "conditioning", new String[] { "stop" }));
        calls(car);
        check("... automatic starts wait", "refused: stopped by hand; automatic starts wait for a manual start", r.handle("egmp", "conditioning", new String[] { "auto-start" }));

        // what the car makes of it (Launchpad's checks, activation timeout and state)
        FakeCar k = new FakeCar();
        Commands b = new Commands(k);
        k.ready = 0;
        check("not READY: refused", "refused: the car isn't READY", b.handle("pad", "conditioning", new String[] { "start" }));
        k.ready = 1;
        k.setting = 1;
        check("the EV setting off: refused", "refused: Battery Conditioning is off in the car's EV settings", b.handle("pad", "conditioning", new String[] { "start" }));
        k.setting = 2;
        k.indicator = 1;
        check("the car already conditioning: refused", "refused: the car is already conditioning (controlled by the car)", b.handle("pad", "conditioning", new String[] { "start" }));
        check("... nothing reached the car", "", calls(k));
        k.indicator = 0;
        k.packC = 22;
        check("a warm pack (EV6, 20 C): refused, with the way past it",
                "refused: the pack is warm (22 °C / 72 °F; this car usually won't heat at 20 °C / 68 °F or warmer); add \"warm\" to send it anyway",
                b.handle("pad", "conditioning", new String[] { "start" }));
        k.model = "NEEV";
        k.packC = 20;
        check("Ioniq 5's limit is 21 C: 20 C goes", "ok conditioning started", b.handle("pad", "conditioning", new String[] { "start" }));
        b.handle("pad", "conditioning", new String[] { "stop" });
        calls(k);
        k.model = "CVEV";
        check("... the EV6's is 20 C: the owner's override", "ok conditioning started", b.handle("-", "conditioning", new String[] { "start", "37", "25", "warm" }));
        check("... with its route values", "route 37 | time 25 | station 2,3", calls(k));
        check("waiting for the indicator", "ok waiting (-)", b.handle("pad", "conditioning", new String[] { "status" }));
        k.now += 4 * 60000;
        b.tick(declared());
        check("EV6: still waiting after 4 min (5 min deadline)", "waiting", b.conditioningState);
        k.indicator = 1;
        b.tick(declared());
        check("indicator on: active", "active", b.conditioningState);
        k.now += 30 * 60000;
        calls(k);
        b.tick(declared());
        check("active: no deadline", "active", b.conditioningState);
        k.indicator = 0;
        calls(k);
        b.tick(declared());
        check("the car ended it: stopped, reported", "ended", b.conditioningState);
        check("... the stop went to the car", "route 2047 | time 1023 | station 0,0", calls(k));
        check("... nobody holds it", "ok ended", b.handle("pad", "conditioning", new String[] { "status" }));
        k.packC = Integer.MIN_VALUE;
        k.model = "NEEV";
        check("an unknown temperature doesn't block", "ok conditioning started", b.handle("egmp", "conditioning", new String[] { "auto-start" }));
        calls(k);
        k.now += 2 * 60000;
        b.tick(declared("egmp conditioning"));
        check("Ioniq 5: no indicator in 2 min: timed out", "timed_out", b.conditioningState);
        check("... stopped", true, calls(k).endsWith("route 2047 | time 1023 | station 0,0"));
        check("... automatic starts wait for a manual one", "refused: the car didn't start conditioning last time; automatic starts wait for a manual start", b.handle("egmp", "conditioning", new String[] { "auto-start" }));
        check("a manual start goes", "ok conditioning started", b.handle("pad", "conditioning", new String[] { "start" }));
        k.indicator = 1;
        b.tick(declared("pad conditioning"));
        calls(k);
        check("a stop while the indicator is on: stopping", "ok conditioning stopped", b.handle("pad", "conditioning", new String[] { "stop" }));
        check("... stopping until it's off", "stopping", b.conditioningState);
        k.indicator = 0;
        b.tick(declared());
        check("... then off", "off", b.conditioningState);
        check("... a manual stop: automatic starts wait", true, b.handle("egmp", "conditioning", new String[] { "auto-start" }).startsWith("refused: stopped by hand"));
        k.indicator = -1;
        check("an indicator that can't be read doesn't block", "ok conditioning started", b.handle("pad", "conditioning", new String[] { "start" }));
        check("... requested (can't confirm)", "requested", b.conditioningState);
        k.now += 60 * 60000;
        b.tick(declared("pad conditioning"));
        check("... and no deadline", "requested", b.conditioningState);

        check("ambient light: first mod holds it; sent as the car's nearest colour", "ok color ff6930 (the car's nearest to ff8000)", c.handle("amb", "ambient_light", new String[] { "color", "ff8000" }));
        check("... the car asked for its light first (not heard yet), then {0,r},{0,g},{0,b}", "light status | rgb 255,105,48", calls(car));
        car.now += 200;
        check("a palette colour goes as it is", "ok color ff321d", c.handle("amb", "ambient_light", new String[] { "color", "ff321d" }));
        calls(car);
        car.now += 200;
        check("another mod is refused while held", "refused: amb holds the ambient light", c.handle("other", "ambient_light", new String[] { "brightness", "3" }));
        check("10 changes a second at most", "refused: 10 changes a second at most",
                c.handle("amb", "ambient_light", new String[] { "brightness", "3" }).equals("ok brightness 3")
                        ? c.handle("amb", "ambient_light", new String[] { "brightness", "4" }) : "first one failed");
        calls(car);
        car.now += 200;
        c.tick(declared("other ambient_light"));
        check("holder turned off: released, the car asked for its own light", "light status", calls(car));
        check("then another mod may", "ok brightness 5", c.handle("other", "ambient_light", new String[] { "brightness", "5" }));
        calls(car);
        check("release with nothing heard says so", "ok released (not restored: the car hadn't reported its light yet)", c.handle("other", "ambient_light", new String[] { "release" }));
        calls(car);

        car.light = new int[] { 0x17f, 0x19a, 0x17a, 10 };   // a theme, as the car reports it
        car.now += 200;
        c.handle("amb", "ambient_light", new String[] { "color", "ff321d" });
        car.light = new int[] { 0xff, 0x32, 0x1d, 10 };   // the car reports our change
        car.now += 200;
        c.handle("amb", "ambient_light", new String[] { "brightness", "3" });
        check("brightness level 3 is the car's 4 (1 is off)", "rgb 255,50,29 | bright 4", calls(car));
        check("release puts back the owner's theme and brightness, from before the first change", "ok released (restored 17f 19a 17a, brightness 9)", c.handle("amb", "ambient_light", new String[] { "release" }));
        check("... raw values and brightness", "rgb 383,410,378 | bright 10", calls(car));

        // the owner changes the light in the menu while a mod holds it: by default the mod keeps it
        car.light = new int[] { 0x17f, 0x19a, 0x17a, 10 };
        car.now += 200;
        c.handle("amb", "ambient_light", new String[] { "color", "ff321d" });
        c.lightReported(new int[] { 0xff, 0x32, 0x1d, 10 });
        c.lightReported(new int[] { 0x132, 0x19a, 0x19e, 4 });   // the owner picks a theme and brightness 3
        calls(car);
        car.now += 200;
        check("owner's menu change: the mod keeps the light (default)", "ok color ff321d", c.handle("amb", "ambient_light", new String[] { "color", "ff321d" }));
        check("... and release restores the owner's new choice", "ok released (restored 132 19a 19e, brightness 3)", c.handle("amb", "ambient_light", new String[] { "release" }));
        check("... sent", "rgb 255,50,29 | rgb 306,410,414 | bright 4", calls(car));

        check("owner_change release", "ok owner_change release (a change in the car's menu ends your hold)", c.handle("amb", "ambient_light", new String[] { "owner_change", "release" }));
        car.light = new int[] { 0x17f, 0x19a, 0x17a, 10 };
        car.now += 200;
        c.handle("amb", "ambient_light", new String[] { "color", "ff321d" });
        c.lightReported(new int[] { 0xff, 0x32, 0x1d, 10 });
        check("our own change reported back: still held", "refused: amb holds the ambient light", c.handle("other", "ambient_light", new String[] { "color", "ff321d" }));
        c.lightReported(new int[] { 0x10, 0x59, 0xf8, 10 });
        calls(car);
        check("... a colour we didn't send is the owner's: the mod lost it and is told", "refused: the owner changed the light in the car's menu; release, then ask again",
                c.handle("amb", "ambient_light", new String[] { "color", "ff321d" }));
        check("... nothing sent over the owner's choice", "", calls(car));
        car.now += 200;
        check("... another mod may take it", "ok color 5dde69 (the car's nearest to 00ff00)", c.handle("other", "ambient_light", new String[] { "color", "00ff00" }));
        c.handle("other", "ambient_light", new String[] { "release" });
        check("... the mod that lost it releases", "ok released", c.handle("amb", "ambient_light", new String[] { "release" }));
        c.handle("amb", "ambient_light", new String[] { "owner_change", "keep" });
        calls(car);

        // the owner's switch: no mod light control
        car.light = new int[] { 0x17f, 0x19a, 0x17a, 10 };
        car.now += 200;
        c.handle("amb", "ambient_light", new String[] { "color", "ff321d" });
        calls(car);
        car.lightBlocked = true;
        c.tick(declared("amb ambient_light"));
        check("owner turns off mod light control: the holder is released, the owner's light restored", "rgb 383,410,378 | bright 10", calls(car));
        check("... changes refused", "refused: the owner turned off mod light control (HMG-API app)", c.handle("amb", "ambient_light", new String[] { "color", "ff321d" }));
        check("... release still answers", "ok not holding the light", c.handle("amb", "ambient_light", new String[] { "release" }));
        car.lightBlocked = false;
        calls(car);
        car.light = null;

        car.lines = 2;
        check("guide lines off", "ok guide lines off", c.handle("cam", "guide_lines", new String[] { "off" }));
        check("another mod waits for the restore", "refused: cam changed them; it restores them first", c.handle("x", "guide_lines", new String[] { "on" }));
        check("restore puts back what was there", "ok restored", c.handle("cam", "guide_lines", new String[] { "restore" }));
        check("... on (2)", "lines 1 | lines 2", calls(car));
        c.handle("cam", "guide_lines", new String[] { "off" });
        c.tick(declared("other ambient_light"));
        check("turned off: restored", "lines 1 | lines 2", calls(car));

        car.lines = 0;
        check("guide lines on a 360-view car: refused with the reason", "refused: this car's guide lines aren't the rear camera's (a 360 view draws its own)", c.handle("cam", "guide_lines", new String[] { "off" }));
        calls(car);
        check("startup notice: off unless the owner allowed it", "refused: the owner hasn't allowed it (HMG-API settings: startup notice)", c.handle("f82", "startup_notice", new String[] { "dismiss" }));
        car.allowed = true;
        car.park = false;
        check("... only in P", "refused: the car isn't in P", c.handle("f82", "startup_notice", new String[] { "dismiss" }));
        car.park = true;
        check("... allowed", "ok startup notice dismissed", c.handle("f82", "startup_notice", new String[] { "dismiss" }));
        check("... once per start", "refused: once per start", c.handle("f82", "startup_notice", new String[] { "dismiss" }));
        check("... the car got one call", "notice hidden", calls(car));
        check("rear camera: the first mod takes it", true, c.handle("cam", "rear_camera", new String[] { "take" }).startsWith("ok rear camera is yours"));
        check("... another mod is told who has it", "refused: cam has the rear camera", c.handle("rec", "rear_camera", new String[] { "take" }));
        c.tick(declared("rec rear_camera"));
        check("... free again when the holder is turned off", true, c.handle("rec", "rear_camera", new String[] { "take" }).startsWith("ok"));
        check("... released by its holder", "ok released", c.handle("rec", "rear_camera", new String[] { "release" }));
        check("MICOM watch: numbers kept", "0a1b2c", Scrub.hex(new byte[] { 0x0a, 0x1b, 0x2c }));
        check("MICOM watch: a VIN-like run blanked", "00" + "__________________________________" + "ff", Scrub.hex(("\0" + "KNDC4DLC5N5012345" + "\u00ff").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        check("MICOM watch: short text kept (codes, units)", "4b4d4800", Scrub.hex("KMH\0".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
        // the owner's OK before a mod's first command
        FakeCar o = new FakeCar();
        o.askFirst = true;
        Commands oc = new Commands(o);
        check("first command: refused, the owner is asked", "refused: waiting for the owner to allow wheel_heat for heat (a question is on the screen)",
                oc.handle("heat", "wheel_heat", new String[] { "on" }));
        check("... the question goes on screen once", "ask heat wheel_heat", calls(o));
        oc.handle("heat", "wheel_heat", new String[] { "on" });
        check("... not again within 30 s", "", calls(o));
        check("... undoing never waits", "ok wheel heat off", oc.handle("heat", "wheel_heat", new String[] { "off" }));
        calls(o);
        o.answers.put("heat wheel_heat", "yes");
        check("allowed: it runs", "ok wheel heat on", oc.handle("heat", "wheel_heat", new String[] { "on" }));
        check("... per command: another one asks", true, oc.handle("heat", "seat_heat", new String[] { "driver", "low" }).startsWith("refused: waiting"));
        o.answers.put("heat seat_heat", "no");
        check("not allowed: refused, says where to change it", "refused: the owner didn't allow seat_heat for heat (HMG-API app: Settings, Vehicle)",
                oc.handle("heat", "seat_heat", new String[] { "driver", "low" }));
        calls(o);
        check("the owner's own command (\"-\": adb, the app): no question, it runs", "ok wheel heat on", oc.handle("-", "wheel_heat", new String[] { "on" }));
        check("... nothing asked on screen", false, calls(o).contains("ask "));
        check("guide_lines off changes the owner's guide lines (not an undo): it waits for the owner like on",
                "refused: waiting for the owner to allow guide_lines for cam (a question is on the screen)", oc.handle("cam", "guide_lines", new String[] { "off" }));
        check("... the question goes on screen, nothing sent to the car", "ask cam guide_lines", calls(o));
        check("... its undo (restore) never waits", "ok nothing to restore", oc.handle("cam", "guide_lines", new String[] { "restore" }));
        o.askFirst = false;
        check("asking off (trust_commands): runs", true, oc.handle("other", "wheel_heat", new String[] { "on" }).startsWith("ok"));
        System.exit(fail);
    }
}

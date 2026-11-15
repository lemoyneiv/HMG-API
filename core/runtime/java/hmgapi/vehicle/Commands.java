package hmgapi.vehicle;

import java.util.Map;
import java.util.Set;

/**
 * The vehicle commands (hmgapi.engine.Vehicle.COMMANDS) with the guards their reviews
 * gave them (docs/vehicle-commands.md). Plain Java: Hub gives it the car, the tests
 * a fake one. hmgapid has already checked that the mod declared the command; mod "-"
 * is the owner (root, HMG-API's app).
 *
 * Answers start with "ok" or "refused:"; every call is logged with the mod.
 */
final class Commands {
    /** the car's side: Hub calls the car's own services through the hook map */
    interface Car {
        boolean park();

        void chargeDoor(boolean close);

        /** a seat's climate step (driver, passenger, rear_left, rear_right): 2 off, 3-5 ventilation, 6-8 heat */
        void seatStep(String seat, int step);

        /** what the seat has (body control's option): 1 two heat levels, 6 three heat levels and ventilation; -1 unknown */
        int seatOption(String seat);

        void wheelHeat(int on);

        void conditioningRoute(int km);

        void conditioningTime(int minutes);

        void conditioningStation(int a, int b);

        /** EV ready (HEV status EV_RDY): 1 ready; -1 unknown */
        int ready();

        /** the owner's Battery Conditioning setting (EV settings): 1 off, 2 on; -1 unknown */
        int conditioningSetting();

        /** the car's conditioning indicator (body control getBattCondIndSta): 1 conditioning, 0 not; -1 unknown */
        int conditioningIndicator();

        /** the pack's minimum temperature, whole °C (cluster battery info); Integer.MIN_VALUE unknown */
        int packTempMin();

        /** model code, the first field of ro.build.product (CVEV, NEEV, ...); "" unknown */
        String model();

        /** the car's raw value: 1 off, 2-11 levels 1-10 */
        void lightBrightness(int raw);

        /** raw channel values: 0-255 a colour; above 255 one of the car's themes (as its status reports them) */
        void lightColor(int r, int g, int b);

        /** the light as the car last reported it: r, g, b (raw), brightness (raw: 1 off, 2-11 levels 1-10); null: not heard yet */
        int[] lightState();

        /** asks the car to report its ambient light */
        void lightStatus();

        int guideLines();

        void setGuideLines(int state);

        void hideStartupNotice();

        /** the owner's switch (HMG-API app, or hmgapid allow startup_notice on) */
        boolean noticeAllowed();

        /** the owner's switch that keeps every mod off the ambient light (HMG-API app, or hmgapid allow block_ambient_light on) */
        boolean lightBlocked();

        /** ask the owner before a mod's first use of a command (default; hmgapid allow trust_commands on: don't) */
        boolean askFirst();

        /** the owner's answer for a mod and command: "yes", "no", or null (not asked yet) */
        String answer(String mod, String cmd);

        /** puts the question on the screen (the HMG-API app's dialog) */
        void ask(String mod, String cmd);

        long now();

        void pause(long ms);

        void log(String line);
    }

    static final int SEAT_OFF = 2, SEAT_LOW = 6, SEAT_MEDIUM = 7, SEAT_HIGH = 8;   // the car's steps (Cold-start Heat, on a car)
    static final int VENT_LOW = 3, VENT_MEDIUM = 4, VENT_HIGH = 5;   // the same step's ventilation values (BCMConstants *SeatHeatState)
    static final int TWO_STEP = 1;   // body control's seat option HEATER_ONLY_2STEP
    static final java.util.List<String> SEATS = java.util.Arrays.asList("driver", "passenger", "rear_left", "rear_right");
    static final int LINES_OFF = 1, LINES_ON = 2;
    static final long DOOR_GAP_MS = 5000, LIGHT_GAP_MS = 100, KEEP_MS = 5000;
    /**
     * Conditioning's route values when a mod gives none (Launchpad's), and the most the
     * car's navigation sends (2047 km and 1023 min mean "no route"): the BMS times the
     * heating from them, so a mod routing to a real charger sends the real ones.
     */
    static final int ROUTE_KM = 9, ROUTE_MIN = 18, KM_MAX = 2046, MIN_MAX = 1022;
    static final int READY = 1, SETTING_ON = 2;
    /**
     * Per model (Launchpad's PreconditioningProfile): the minimum pack temperature at or
     * above which the car usually won't heat (°C; a Kia bulletin and owners' reports, not
     * BMS calibration), and how long the indicator may take to come on (the EV6 up to
     * 5 minutes, field-tested; the Ioniq 5 about 60-70 s, so 2 minutes for the others).
     */
    static int warmC(String model) {
        return model.startsWith("NEEV") ? 21 : 20;   // Ioniq 5 21 °C / 70 °F; EV6, Ioniq 6 (CEEV) 20 °C / 68 °F
    }

    static long activationMs(String model) {
        return "CVEV".equals(model) ? 5 * 60000 : 2 * 60000;
    }
    /**
     * The ambient light's custom colours (the vehicle settings app's 64-colour picker,
     * EV6 260507): the car ignores any other colour, so a colour is sent as the nearest one
     */
    static final int[] PALETTE = {
        0x1043f4, 0x102ef2, 0x1020ef, 0x211cef, 0x2f1cef, 0x401cef, 0x521cef, 0x641cef,
        0x781cef, 0x881def, 0x9b21ef, 0xa923ef, 0xb723ea, 0xc121e0, 0xcc1fd2, 0xd51bc0,
        0xdd17ae, 0xe41399, 0xeb0e99, 0xef0a74, 0xf60660, 0xfa0451, 0xfe0145, 0xff321d,
        0xff4423, 0xff5027, 0xff6930, 0xff7835, 0xff8f3e, 0xffa646, 0xffc04f, 0xffd356,
        0xffe55c, 0xfff362, 0xfefd66, 0xffffff, 0xf4fd66, 0xe8fd66, 0xd9fd66, 0xcbfd63,
        0xb7f962, 0xa8f362, 0x95eb62, 0x84e562, 0x75df62, 0x68dd62, 0x5dde69, 0x51e176,
        0x47e785, 0x3eee98, 0x35f5a9, 0x2cfabd, 0x25facf, 0x1efae1, 0x19faed, 0x14faf9,
        0x10f4fc, 0x10e6fc, 0x10d2fc, 0x10bbfc, 0x10a3fc, 0x108afc, 0x106ffc, 0x1059f8,
    };

    final Car car;
    long doorAt = -DOOR_GAP_MS, lightAt = -LIGHT_GAP_MS, keptAt;
    String conditioningBy, lightBy, linesBy, cameraBy;
    boolean manualStop, noticeDone;
    /** the conditioning request in force: an automatic one (auto-start), and its route values */
    boolean conditioningAuto;
    int conditioningKm = ROUTE_KM, conditioningMin = ROUTE_MIN;
    /**
     * What the car made of it (the indicator, read every tick): off; requested (sent, the
     * indicator can't be read); waiting (sent, not on yet); active; stopping (stopped, the
     * indicator still on); ended (the car turned it off while requested); timed_out (it
     * never came on in time: stopped). ended and timed_out stay until the next start.
     */
    String conditioningState = "off";
    long conditioningAt;
    boolean timedOut;   // automatic starts wait for a manual one after a timeout (no retry loop)
    int linesBefore;
    int[] lightSaved;   // the owner's light, taken when a mod first changes it
    /** mods that let go of the light when the owner changes it in the menu (default: they keep it) */
    final Set<String> lightYield = new java.util.HashSet<String>();
    String lightLostBy;   // lost it to the owner's menu: refused until it releases
    /** what we sent lately (time, kind, value): a report of one of these is ours, not the owner's */
    final java.util.List<long[]> lightSent = new java.util.ArrayList<long[]>();
    static final long SENT_MS = 3000;

    Commands(Car car) {
        this.car = car;
    }

    /** when each (mod, command) question last went on screen: asked again after ASK_MS */
    final Map<String, Long> asked = new java.util.HashMap<String, Long>();
    static final long ASK_MS = 30000;

    /**
     * The owner's OK for a mod's commands: the first time a mod uses one, it's refused
     * and the owner is asked (Allow / Don't allow, kept per mod and command). Undoing
     * (release, stop, restore, off) never waits: a mod can always give back what it took.
     * Not guide_lines off: that turns the owner's guide lines off, a change like "on"
     * (its undo is restore).
     */
    String owner(String mod, String cmd, String a0) {
        // "-" is the owner (root over adb, HMG-API's app): their own request needs no OK from themselves
        if (!car.askFirst() || mod == null || mod.isEmpty() || "hmgapi".equals(mod) || "-".equals(mod)) return null;
        if ("release".equals(a0) || "stop".equals(a0) || "restore".equals(a0)) return null;
        if ("off".equals(a0) && !"guide_lines".equals(cmd)) return null;
        String a = car.answer(mod, cmd);
        if ("yes".equals(a)) return null;
        if ("no".equals(a)) return "refused: the owner didn't allow " + cmd + " for " + mod + " (HMG-API app: Settings, Vehicle)";
        String key = mod + " " + cmd;
        Long at = asked.get(key);
        if (at == null || car.now() - at >= ASK_MS) {
            asked.put(key, car.now());
            car.ask(mod, cmd);
        }
        return "refused: waiting for the owner to allow " + cmd + " for " + mod + " (a question is on the screen)";
    }

    synchronized String handle(String mod, String cmd, String[] args) {
        String a0 = args.length > 0 ? args[0] : "", a1 = args.length > 1 ? args[1] : "", a2 = args.length > 2 ? args[2] : "";
        String r;
        try {
            r = owner(mod, cmd, a0);
            if (r == null) r = "conditioning".equals(cmd) ? conditioning(mod, args) : run(mod, cmd, a0, a1);
        } catch (RuntimeException e) {
            r = "refused: the car's service failed (" + e + ")";
        }
        car.log(mod + " " + cmd + " " + a0 + (a1.isEmpty() ? "" : " " + a1) + (a2.isEmpty() ? "" : " " + a2) + " -> " + r);
        return r;
    }

    private String run(String mod, String cmd, String a0, String a1) {
        if ("charge_door".equals(cmd)) {
            if (!"open".equals(a0) && !"close".equals(a0)) return "refused: charge_door open|close";
            if (!car.park()) return "refused: the car isn't in P";
            if (car.now() - doorAt < DOOR_GAP_MS) return "refused: once every 5 s";
            doorAt = car.now();
            car.chargeDoor("close".equals(a0));
            return "ok charge door " + a0;
        }
        if ("seat_heat".equals(cmd) || "seat_vent".equals(cmd)) {
            // one climate step per seat, as the climate app sets it: 2 off, 3-5 ventilation, 6-8 heat
            boolean heat = "seat_heat".equals(cmd);
            if (!SEATS.contains(a0)) return "refused: " + cmd + " driver|passenger|rear_left|rear_right off|low|medium|high";
            if (!heat && a0.startsWith("rear")) return "refused: the rear seats have no ventilation";
            int level = "off".equals(a1) ? 0 : "low".equals(a1) ? 1 : "medium".equals(a1) ? 2 : "high".equals(a1) ? 3 : -1;
            if (level < 0) return "refused: off, low, medium or high";
            boolean twoStep = car.seatOption(a0) == TWO_STEP;   // the rear seats: low and high only
            if (twoStep && level == 2) return "refused: this seat has two levels (low, high)";
            car.seatStep(a0, level == 0 ? SEAT_OFF : (heat ? SEAT_LOW : VENT_LOW) + level - 1);
            return "ok " + a0 + " seat " + (heat ? "heat " : "ventilation ") + a1;
        }
        if ("wheel_heat".equals(cmd)) {
            if (!"on".equals(a0) && !"off".equals(a0)) return "refused: wheel_heat on|off";
            car.wheelHeat("on".equals(a0) ? 1 : 0);
            return "ok wheel heat " + a0;
        }
        if ("ambient_light".equals(cmd)) {
            if (car.lightBlocked() && !"release".equals(a0)) return "refused: the owner turned off mod light control (HMG-API app)";
            if ("owner_change".equals(a0)) {
                if ("keep".equals(a1)) lightYield.remove(mod);
                else if ("release".equals(a1)) lightYield.add(mod);
                else return "refused: owner_change release|keep";
                return "ok owner_change " + a1 + ("keep".equals(a1)
                        ? " (a change in the car's menu becomes the light restored on release)" : " (a change in the car's menu ends your hold)");
            }
            if (mod.equals(lightLostBy)) {
                if ("release".equals(a0)) {
                    lightLostBy = null;
                    return "ok released";
                }
                return "refused: the owner changed the light in the car's menu; release, then ask again";
            }
            if ("release".equals(a0)) {
                if (!mod.equals(lightBy)) return "ok not holding the light";
                return "ok released" + releaseLight();
            }
            if (lightBy != null && !lightBy.equals(mod)) return "refused: " + lightBy + " holds the ambient light";
            if (car.now() - lightAt < LIGHT_GAP_MS) return "refused: 10 changes a second at most";
            if ("color".equals(a0)) {
                if (!a1.matches("[0-9a-f]{6}")) return "refused: color rrggbb (hex)";
                int rgb = nearest(Integer.parseInt(a1, 16));
                String sent = String.format("%06x", rgb);
                takeLight(mod);
                lightAt = car.now();
                sent(0, rgb);
                car.lightColor(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255);
                return "ok color " + sent + (sent.equals(a1) ? "" : " (the car's nearest to " + a1 + ")");
            }
            if ("brightness".equals(a0)) {
                int b = a1.matches("[0-9]{1,2}") ? Integer.parseInt(a1) : -1;
                if (b < 0 || b > 10) return "refused: brightness 0-10 (0: off)";
                takeLight(mod);
                lightAt = car.now();
                sent(1, b + 1);
                car.lightBrightness(b + 1);   // the car's value: 1 off, 2-11 the settings app's levels 1-10
                return "ok brightness " + b;
            }
            return "refused: ambient_light color <rrggbb>|brightness <0-10>|release|owner_change release|keep";
        }
        if ("guide_lines".equals(cmd)) {
            if ("restore".equals(a0)) {
                if (!mod.equals(linesBy)) return "ok nothing to restore";
                restoreLines();
                return "ok restored";
            }
            if (!"on".equals(a0) && !"off".equals(a0)) return "refused: guide_lines on|off|restore";
            if (linesBy != null && !linesBy.equals(mod)) return "refused: " + linesBy + " changed them; it restores them first";
            if (linesBy == null) {
                int now = car.guideLines();
                // a 360-view car (the EV6 GT-Line) draws its own guide lines: the rear camera's state isn't on/off there
                if (now != LINES_ON && now != LINES_OFF) return "refused: this car's guide lines aren't the rear camera's (a 360 view draws its own)";
                linesBefore = now;
                linesBy = mod;
            }
            car.setGuideLines("on".equals(a0) ? LINES_ON : LINES_OFF);
            return "ok guide lines " + a0;
        }
        if ("rear_camera".equals(cmd)) {
            // coordination only: mods open the camera through the car's own camera service; this says whose turn it is
            if ("release".equals(a0)) {
                if (!mod.equals(cameraBy)) return "ok not holding the rear camera";
                cameraBy = null;
                return "ok released";
            }
            if (!"take".equals(a0)) return "refused: rear_camera take|release";
            if (cameraBy != null && !cameraBy.equals(mod)) return "refused: " + cameraBy + " has the rear camera";
            cameraBy = mod;
            return "ok rear camera is yours (the car's reverse view still comes first: let go on reverse or CAMERA_APP_START)";
        }
        if ("startup_notice".equals(cmd)) {
            if (!"dismiss".equals(a0)) return "refused: startup_notice dismiss";
            if (!car.noticeAllowed()) return "refused: the owner hasn't allowed it (HMG-API settings: startup notice)";
            if (noticeDone) return "refused: once per start";
            if (!car.park()) return "refused: the car isn't in P";
            noticeDone = true;
            car.hideStartupNotice();
            return "ok startup notice dismissed";
        }
        return "refused: unknown command " + cmd;
    }

    /**
     * Battery conditioning: start, auto-start (a mod's own decision, e.g. routing to a
     * charger), both with optional route values "<km> <minutes>"; route (the mod
     * conditioning keeps its values current); stop. A stop blocks automatic starts until
     * the next manual start, except the stop of the mod whose automatic request it is
     * (that's the request ending, e.g. at the charger, not the driver saying no).
     *
     * A start is refused, as Launchpad's PreconditioningController does, when the car
     * isn't READY, Battery Conditioning is off in the EV settings, or the car is already
     * conditioning on its own; a warm pack is refused with a way past it (a last word
     * "warm"). A value that can't be read doesn't block. status: the state (see
     * conditioningState) and who holds it.
     */
    private String conditioning(String mod, String[] args) {
        java.util.List<String> w = new java.util.ArrayList<String>(java.util.Arrays.asList(args));
        boolean warm = !w.isEmpty() && "warm".equals(w.get(w.size() - 1));
        if (warm) w.remove(w.size() - 1);
        String a0 = w.size() > 0 ? w.get(0) : "", a1 = w.size() > 1 ? w.get(1) : "", a2 = w.size() > 2 ? w.get(2) : "";
        if ("status".equals(a0)) return "ok " + conditioningState + (conditioningBy == null ? "" : " (" + conditioningBy + ")");
        if ("stop".equals(a0)) {
            if (conditioningBy == null) return "ok not conditioning";
            boolean ownAuto = conditioningAuto && conditioningBy.equals(mod);
            stopConditioning();
            if (!ownAuto) manualStop = true;   // a stop is the driver's (or another mod's) call: automatic starts wait for a manual one
            return "ok conditioning stopped";
        }
        boolean auto = "auto-start".equals(a0);
        if (!auto && !"start".equals(a0) && !"route".equals(a0)) return "refused: conditioning start|auto-start|stop|route|status";
        if ("route".equals(a0) && a1.isEmpty()) return "refused: conditioning route <km> <minutes>";
        int[] route = a1.isEmpty() && a2.isEmpty() ? new int[] { ROUTE_KM, ROUTE_MIN } : route(a1, a2);
        if (route == null) return "refused: route values are <km 0-" + KM_MAX + "> <minutes 0-" + MIN_MAX + ">";
        if ("route".equals(a0)) {
            if (conditioningBy == null || !conditioningBy.equals(mod)) {
                return "refused: only the mod conditioning changes its route (" + (conditioningBy == null ? "not conditioning" : conditioningBy) + ")";
            }
            conditioningKm = route[0];
            conditioningMin = route[1];
            car.conditioningRoute(conditioningKm);
            car.conditioningTime(conditioningMin);
            keptAt = car.now();
            return "ok conditioning route " + conditioningKm + " km " + conditioningMin + " min";
        }
        if (auto && manualStop) return "refused: stopped by hand; automatic starts wait for a manual start";
        if (auto && timedOut) return "refused: the car didn't start conditioning last time; automatic starts wait for a manual start";
        if (conditioningBy != null) return "ok already conditioning (" + conditioningBy + ")";
        String why = startRefusal(warm);
        if (why != null) return "refused: " + why;
        if (!auto) {
            manualStop = false;
            timedOut = false;
        }
        conditioningBy = mod;
        conditioningAuto = auto;
        conditioningKm = route[0];
        conditioningMin = route[1];
        startConditioning();
        conditioningAt = car.now();
        conditioningState = car.conditioningIndicator() < 0 ? "requested" : "waiting";
        return "ok conditioning started";
    }

    /** why the car won't take a start now, or null */
    String startRefusal(boolean warm) {
        int ready = car.ready(), setting = car.conditioningSetting(), ind = car.conditioningIndicator();
        if (ready >= 0 && ready != READY) return "the car isn't READY";
        if (setting >= 0 && setting != SETTING_ON) return "Battery Conditioning is off in the car's EV settings";
        if (ind == 1) return "the car is already conditioning (controlled by the car)";
        int t = car.packTempMin(), limit = warmC(car.model());
        if (!warm && t != Integer.MIN_VALUE && t >= limit) {
            return "the pack is warm (" + t + " °C / " + Math.round(t * 9 / 5f + 32) + " °F; this car usually won't heat at "
                    + limit + " °C / " + Math.round(limit * 9 / 5f + 32) + " °F or warmer); add \"warm\" to send it anyway";
        }
        return null;
    }

    /** "<km> <minutes>" within what the car's navigation sends, or null */
    static int[] route(String km, String min) {
        try {
            int k = Integer.parseInt(km), m = Integer.parseInt(min);
            return k < 0 || k > KM_MAX || m < 0 || m > MIN_MAX ? null : new int[] { k, m };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** as the car's navigation does: route metadata, then "routed to a station" (Launchpad's order and timing) */
    void startConditioning() {
        car.conditioningRoute(conditioningKm);
        car.conditioningTime(conditioningMin);
        car.pause(10);
        car.conditioningStation(2, 3);
        car.pause(130);
        keptAt = car.now();
    }

    void stopConditioning() {
        conditioningBy = null;
        conditioningAuto = false;
        conditioningKm = ROUTE_KM;
        conditioningMin = ROUTE_MIN;
        car.conditioningRoute(2047);
        car.conditioningTime(1023);
        car.conditioningStation(0, 0);
        car.pause(130);
        conditioningState = car.conditioningIndicator() == 1 ? "stopping" : "off";   // stopped once the indicator is off
    }

    /** the palette colour closest to rgb */
    static int nearest(int rgb) {
        int best = PALETTE[0];
        long bestD = Long.MAX_VALUE;
        for (int c : PALETTE) {
            int dr = (c >> 16 & 255) - (rgb >> 16 & 255), dg = (c >> 8 & 255) - (rgb >> 8 & 255), db = (c & 255) - (rgb & 255);
            long d = (long) dr * dr + dg * dg + db * db;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    void takeLight(String mod) {
        if (lightBy == null) {
            lightSaved = car.lightState();
            if (lightSaved == null) {   // not heard yet: ask, and wait for the answer a little
                car.lightStatus();
                for (int i = 0; i < 10 && lightSaved == null; i++) {
                    car.pause(50);
                    lightSaved = car.lightState();
                }
            }
        }
        lightBy = mod;
    }

    void sent(long kind, int value) {
        long now = car.now();
        for (java.util.Iterator<long[]> i = lightSent.iterator(); i.hasNext();) if (now - i.next()[0] > SENT_MS) i.remove();
        lightSent.add(new long[] { now, kind, value });
    }

    boolean ours(long kind, int value) {
        for (long[] s : lightSent) if (s[1] == kind && s[2] == value && car.now() - s[0] <= SENT_MS) return true;
        return false;
    }

    /**
     * The car reported its light (after every change, whoever made it): r, g, b raw,
     * brightness raw. A colour or brightness we didn't send while a mod holds the light
     * is the owner's, from the menu: by default the mod keeps the light and the owner's
     * choice becomes what its release restores; a mod that asked for owner_change
     * release loses the light instead (nothing is restored over the owner's choice).
     */
    synchronized void lightReported(int[] st) {
        if (lightBy == null || lightSaved == null) return;
        int rgb = st[0] < 256 && st[1] < 256 && st[2] < 256 ? st[0] << 16 | st[1] << 8 | st[2] : -1;
        boolean colorOwner = !(rgb >= 0 && ours(0, rgb)) && !(st[0] == lightSaved[0] && st[1] == lightSaved[1] && st[2] == lightSaved[2]);
        boolean brightOwner = !ours(1, st[3]) && st[3] != lightSaved[3];
        if (!colorOwner && !brightOwner) return;
        if (!lightYield.contains(lightBy)) {
            if (colorOwner) {
                lightSaved[0] = st[0];
                lightSaved[1] = st[1];
                lightSaved[2] = st[2];
            }
            if (brightOwner) lightSaved[3] = st[3];
            car.log("ambient light: the owner changed it in the menu; " + lightBy + " keeps it, and the owner's choice is restored on release");
            return;
        }
        car.log("ambient light: the owner changed it in the menu; " + lightBy + " no longer holds it");
        lightLostBy = lightBy;
        lightBy = null;
        lightSaved = null;
        lightSent.clear();
    }

    /** puts back the owner's colour or theme and brightness; says what it did */
    String releaseLight() {
        lightBy = null;
        int[] s = lightSaved;
        lightSaved = null;
        lightSent.clear();
        if (s == null) {
            car.log("ambient light: the owner's light wasn't reported before the change; not restored");
            car.lightStatus();
            return " (not restored: the car hadn't reported its light yet)";
        }
        car.lightColor(s[0], s[1], s[2]);
        car.lightBrightness(s[3]);
        return String.format(" (restored %03x %03x %03x, brightness %d)", s[0], s[1], s[2], s[3] - 1);
    }

    void restoreLines() {
        String by = linesBy;
        linesBy = null;
        if (linesBefore == LINES_ON || linesBefore == LINES_OFF) car.setGuideLines(linesBefore);
        car.log("guide lines back to " + linesBefore + " (" + by + ")");
    }

    /**
     * Every few seconds: conditioning's route metadata refreshed (never a second
     * start), and what a mod that's off (or lost the command) held is let go.
     * declared: mod -> its commands, from state/commands.txt.
     */
    synchronized void tick(Map<String, Set<String>> declared) {
        if (conditioningBy != null && !has(declared, conditioningBy, "conditioning")) {
            car.log("conditioning: " + conditioningBy + " is off; stopping");
            stopConditioning();
        } else if (conditioningBy != null && car.now() - keptAt >= KEEP_MS) {
            // the route data only, never the station event again: that event is the start
            // (HEVService puts the station back to Invalid about 120 ms after any set, so it's
            // never held), and repeating it every 5 s kept the BMS from switching on (US EV6
            // 260507: indicator BCM 109 stayed 0). Launchpad's maintain does the same.
            car.conditioningRoute(conditioningKm);
            car.conditioningTime(conditioningMin);
            keptAt = car.now();
        }
        watchConditioning();
        if (lightBy != null && !has(declared, lightBy, "ambient_light")) {
            car.log("ambient light: " + lightBy + " is off; released");
            releaseLight();
        }
        if (lightBy != null && car.lightBlocked()) {
            car.log("ambient light: the owner turned off mod light control; " + lightBy + " released");
            releaseLight();
        }
        if (lightLostBy != null && !has(declared, lightLostBy, "ambient_light")) lightLostBy = null;
        for (java.util.Iterator<String> i = lightYield.iterator(); i.hasNext();) if (!has(declared, i.next(), "ambient_light")) i.remove();
        if (linesBy != null && !has(declared, linesBy, "guide_lines")) restoreLines();
        if (cameraBy != null && !has(declared, cameraBy, "rear_camera")) {
            car.log("rear camera: " + cameraBy + " is off; free again");
            cameraBy = null;
        }
    }

    /**
     * The car's indicator after a start or stop (Launchpad's activation check): on in time,
     * or stopped and reported as timed out; once on, no deadline, and going off while
     * requested is the car ending it (stopped, reported). A stop is done once it's off.
     */
    void watchConditioning() {
        if ("stopping".equals(conditioningState)) {
            if (car.conditioningIndicator() != 1) conditioningState = "off";
            return;
        }
        if (conditioningBy == null) return;
        int ind = car.conditioningIndicator();
        if (ind < 0) return;   // can't tell: no deadline
        if (ind == 1 && !"active".equals(conditioningState)) {
            car.log("conditioning: the car's indicator is on, " + (car.now() - conditioningAt) / 1000 + " s after the start (" + conditioningBy + ")");
            conditioningState = "active";
        } else if (ind == 0 && "active".equals(conditioningState)) {
            car.log("conditioning: the car ended it (indicator off); stopping (" + conditioningBy + ")");
            stopConditioning();
            conditioningState = "ended";
        } else if (ind == 0 && car.now() - conditioningAt >= activationMs(car.model())) {
            car.log("conditioning: the car's indicator didn't come on within " + activationMs(car.model()) / 60000 + " min; stopping (" + conditioningBy + ")");
            stopConditioning();
            conditioningState = "timed_out";
            timedOut = true;
        }
    }

    static boolean has(Map<String, Set<String>> declared, String mod, String cmd) {
        if ("-".equals(mod)) return true;   // the owner's own requests
        Set<String> s = declared.get(mod);
        return s != null && s.contains(cmd);
    }
}

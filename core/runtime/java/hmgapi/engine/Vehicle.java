package hmgapi.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The vehicle readings HMG-API offers, by name. A mod lists the ones it uses in
 * mod.json ("vehicle": {"read": ["speed", "gear.park"]}); the owner sees them in the
 * plan, and hmgapid answers a mod only for those. The vehicle service
 * (hmgapi.vehicle.Hub) reads each through the car's own service interfaces, by
 * method name from the hook map, never by transaction number, and keeps the value
 * for its freshness window, so ten mods asking cost the car one read.
 *
 * Per-car identity (VIN, serials) is never a reading. Commands (COMMANDS) go through
 * the car's own services too, each with the guard its review gave it.
 */
public final class Vehicle {
    private Vehicle() {}

    public static final class Reading {
        public final String name, unit, about;
        public final long freshMs;

        Reading(String name, String unit, long freshMs, String about) {
            this.name = name;
            this.unit = unit;
            this.freshMs = freshMs;
            this.about = about;
        }
    }

    public static final Map<String, Reading> READINGS;

    static {
        Map<String, Reading> m = new LinkedHashMap<String, Reading>();
        add(m, "gear.park", "0/1", 500, "gear in P");
        add(m, "parking_brake", "0/1", 1000, "electronic parking brake engaged");
        add(m, "speed", "km/h", 100, "vehicle speed");
        add(m, "moving", "0/1", 100, "the car is moving");
        add(m, "driving.restricted", "0/1", 500, "the car's own driving restriction is on (what stock video apps follow)");
        add(m, "steering.angle", "degrees", 100, "steering wheel angle, left negative");
        add(m, "pedal.accel", "raw", 100, "accelerator pedal");
        add(m, "brake.pressure", "raw", 100, "brake cylinder pressure");
        add(m, "battery12v.soc", "%", 30000, "12V battery state of charge");
        // the 12V battery sensor's own flags and the saver option, raw until their values are understood
        // (asked for by Battery Watchdog, which logs them next to the charge)
        add(m, "battery12v.sensor_state", "raw", 30000, "12V battery sensor state");
        add(m, "battery12v.sensor_invalid", "raw", 30000, "12V battery sensor's invalid flag");
        add(m, "battery12v.sensor_error", "raw", 30000, "12V battery sensor's error flag");
        add(m, "battery12v.saver", "raw", 60000, "the Auxiliary Battery Saver option");
        add(m, "hv.soc", "%", 5000, "high-voltage battery state of charge, as shown (0.5 steps)");
        add(m, "hv.health", "raw", 60000, "high-voltage battery health index");
        add(m, "hv.ready", "raw", 1000, "EV ready");
        add(m, "hv.conditioning", "raw", 5000, "the owner's Battery Conditioning setting (EV settings): 1 off, 2 on; not whether it's running");
        add(m, "hv.conditioning_active", "0/1", 2000, "the car's battery conditioning indicator: conditioning now (body control)");
        add(m, "hv.battery_temp_min", "C", 5000, "high-voltage battery's minimum cell temperature, whole degrees (cluster battery info)");
        add(m, "hv.conditioning_failure", "raw", 5000, "battery conditioning failure popup flag (BMS), as the car sends it; meaning of values not known yet");
        add(m, "conditioning.state", "text", 1000, "HMG-API's conditioning request: off, requested, waiting, active, stopping, ended (by the car), timed_out");
        add(m, "charging", "raw", 5000, "charging status");
        add(m, "odometer", "km", 60000, "odometer");
        for (String seat : new String[] { "driver", "passenger", "rear_left", "rear_right" }) {
            add(m, "seat_heat." + seat, "0-3", 1000, seat + " seat heat level (0 off; a two-level seat, the EV6's rear: 0-2)");
            if (!seat.startsWith("rear")) add(m, "seat_vent." + seat, "0-3", 1000, seat + " seat ventilation level (0 off)");   // the rear seats have none (EV6)
            add(m, "seat_state." + seat, "raw", 1000, seat + " seat climate step as body control reports it: 2 off, 3-5 ventilation, 6-8 heat");
        }
        add(m, "wheel_heat", "0/1", 200, "steering wheel heat on (body control, pushed)");
        add(m, "defogger", "0/1", 200, "rear defogger on (body control, pushed)");
        add(m, "outside_temp", "C", 1000, "outside temperature, Celsius (climate control's; converted on a car that shows Fahrenheit)");
        add(m, "outside_temp_f", "F", 1000, "outside temperature, Fahrenheit (climate control's, what the cluster shows)");
        add(m, "guide_lines", "raw", 1000, "rear camera guide lines (1 off, 2 on)");
        READINGS = Collections.unmodifiableMap(m);
    }

    /** a command a mod may ask for (mod.json "vehicle": {"commands": [...]}), each reviewed on its own (docs/vehicle-commands.md) */
    public static final class Command {
        public final String name, usage, guard;

        Command(String name, String usage, String guard) {
            this.name = name;
            this.usage = usage;
            this.guard = guard;
        }
    }

    public static final Map<String, Command> COMMANDS;

    static {
        Map<String, Command> m = new LinkedHashMap<String, Command>();
        cmd(m, "charge_door", "open|close", "gear in P; once every 5 s");
        cmd(m, "seat_heat", "driver|passenger|rear_left|rear_right off|low|medium|high", "the seat's climate setting, as the climate app sets it");
        cmd(m, "seat_vent", "driver|passenger off|low|medium|high", "the seat's ventilation through the same seat setting the climate app uses (owner's request, 2026-10-31)");
        cmd(m, "wheel_heat", "on|off", "on or off only");
        cmd(m, "conditioning", "start|auto-start [<km> <minutes>] [warm]|route <km> <minutes>|stop|status", "refused unless READY with Battery Conditioning on in the EV settings and the car not conditioning on its own; a warm pack refused unless \"warm\" is added; stopped if the indicator isn't on in time (EV6 5 min, others 2 min) or when the car ends it, and automatic starts wait for a manual one after a timeout; one state for the unit; route values as the car's navigation sends them (0-2046 km, 0-1022 min; default 9 km, 18 min), changed only by the mod conditioning; a manual stop blocks automatic starts until the next manual start (the mod whose automatic request it is ending it doesn't); stopped when the mod that started it is turned off");
        cmd(m, "ambient_light", "color <rrggbb>|brightness <0-10>|release|owner_change release|keep", "one mod at a time (the first to ask holds it until it releases or is turned off); the owner's light is put back on release, including a change made in the car's menu meanwhile (owner_change release: such a change ends the hold instead); the owner's switch block_ambient_light keeps every mod off it; 10 changes a second at most");
        cmd(m, "guide_lines", "on|off|restore", "the state before the first change comes back when the mod restores, releases or is turned off");
        cmd(m, "rear_camera", "take|release", "one mod at a time (coordination: the car's reverse view always comes first); free again when the mod is turned off");
        cmd(m, "startup_notice", "dismiss", "only if the owner allowed it (HMG-API app, or hmgapid allow startup_notice on); gear in P; once per start; skips the notice the driver is meant to accept");
        COMMANDS = Collections.unmodifiableMap(m);
    }

    private static void cmd(Map<String, Command> m, String name, String usage, String guard) {
        m.put(name, new Command(name, usage, guard));
    }

    private static void add(Map<String, Reading> m, String name, String unit, long freshMs, String about) {
        m.put(name, new Reading(name, unit, freshMs, about));
    }
}

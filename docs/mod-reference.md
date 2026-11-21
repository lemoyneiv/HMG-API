# Writing a mod: the mod.json reference

A mod is a folder with a `mod.json`. Everything it does is declared there, shown to
the owner in the plan before it applies, and undone when the mod is turned off or
removed. HMG-API runs it next to every other mod and settles what two mods both
want (a button, the rear camera, a file).

## Already have a Gen5W project?

You can offer it as an HMG-API mod as well, without changing how you ship it today.
HMG-API runs on top of NEMESIS (it installs through it and uses its root), so your
existing package and an HMG-API mod of it can live side by side, and you choose
which to offer.

```
tools/mod-draft.py path/to/your/package --out my-mod
```

reads its `NEMESIS.data` and scripts, writes a first `my-mod/mod.json` and
`my-mod/DRAFT-NOTES.txt`, and copies your files over unchanged. The notes list the
lines where an HMG-API feature below may fit (a car reading, a button, a shared
setting). It never edits your code.

## What a mod can declare

| You want | In mod.json |
|---|---|
| your own APK | `"apps": [{"apk": "…", "package": "…"}]`: installed with the signature workaround, data kept on update, turned off with the mod, removed with it |
| a tile in the app menu | `"provides": {"launcher": [{"package": "pkg", "class": ".Main"}]}`, with optional day/night pictures (`icon`, `icon_night`) and greyed-out ones (`icon_disabled`, `icon_disabled_night`); `tools/mkicon.sh <picture> <dir>` makes all four in the stock style from any shape (PNG or SVG; add a size and the shape's colour for a logo on its own background, e.g. `66 '#41ae45'`) |
| to change how the car's own apps behave | `"hooks": {"jar": "hooks.jar", "entry": "my.Entry"}`: your code runs in their processes and hooks them by stable hook point names, mapped per car; the list, and which cars have each: [hook-points.md](hook-points.md) (example: `mods/examples/hello`) |
| a background process | `"services": [{"name": "…", "script": "…", "user": "root\|system\|app"}]`, or `{"jar": "x.jar", "class": "my.Main", "args": […]}` (`BOOTCLASSPATH` already set); `"restart": true` (default) or `false`; `"start": "android"` (default), `"boot"`, or `"manual"` (only when your app or `hmgapid service-start` asks) |
| files and settings on the unit | `patches.json` changes (`add`, `lines`, `props`, `setting`, `codecs`, …): merged with other mods, put back on removal |
| a folder on a USB stick | `"usb": ["MyFolder"]`: your apps get `hmgapi.intent.action.USB_FOLDER`; services ask `hmgapid usb` |
| to read the car | `"vehicle": {"read": ["battery12v.soc", "gear.park", "hv.soc"]}`, then `hmgapid vehicle <name>…` or `api.vehicle(…)`: no root, one read shared by every mod (see Vehicle readings) |
| to send a car command | `"vehicle": {"commands": [...]}` and `hmgapid vehicle-do <command> <args>`: named, guarded, no root (see Vehicle commands) |
| to tidy up when the car is parked, or before the head unit shuts down | `"car_events": {"parked": {"script": "bin/park.sh"}, "shutdown": {}}`: a script, your apps and your hooks hear it (see Car events) |
| buttons | `"input": [{"key": "tune_enter", "press": "hold", "stock": "replace", "open": "<your app>/.Grid"}]`: one reader for every mod (see Buttons) |
| a page in the right-hand split-screen panel | `"provides": {"panel": [{"title": "Battery", "widget": "<your app>/.MyWidget"}]}`: a standard Android app widget from your app (example: `mods/examples/side-panel`) |
| Android Auto settings and events | `"projection"` (see Phone projection) |
| firewall rules, routes, network settings | `"network"` (see Network): HMG-API keeps them in place, the stock firewall can't take them away |
| Bluetooth Low Energy devices | `"ble": {"central": true}` and `hmgapi.client.Ble` (see Bluetooth Low Energy): scan and use BLE GATT peripherals alongside phone calls and audio, no root |
| your app closed while driving | `"not_while_driving": ["<your app's package>"]`: the car's own driving restriction and popup |
| feature flags, the Wi-Fi client, codecs, keyboard lists | see Files and settings that several mods share |
| another mod, or never to run next to one | `"requires"`, `"conflicts"` (see Other mods) |

Scripts run in the head unit's own shell: **mksh with Android 4.4's toolbox**. There's
no `sed`, `tr`, `awk`, `head`, `tail`, `wc`, `cut`, `sort`, `find`, `xargs`, `expr`,
`basename`, `printf` or busybox. There is `grep`, `cat`, `cp`, `mv`, `rm`, `ls`, `md5`,
`date`, `sleep`, `getprop`/`setprop`, `am`/`pm`, `logcat`, `hmgapid`, and the shell's own
`test`, `[[ ]]`, `read`, `case`, `$((...))` and `${var#x}` / `${var%x}` / `${var/a/b}`.
The full list is `core/runtime/unit-commands.txt`; `tools/check-scripts.sh <your mod>`
checks a mod's scripts against it, and HMG-API warns at install about any command the
unit doesn't have. Anything more is easier in a Java service (`"jar"`).

A service's environment: `HMGAPI_MOD`, `HMGAPI_MOD_DIR` (the mod's folder),
`HMGAPI_DATA` (the service's own folder; its output goes to
`/data/hmgapi/logs/<id>/<name>.log`), `HMGAPI_SERVICE`, init's (`BOOTCLASSPATH`,
`ANDROID_ROOT` …), the stock `PATH`. Manage services with `hmgapid
service-start|stop|restart <name>` and read their output with `hmgapid service-log
<name>`.

### Points mapped on the car itself

A point newer than HMG-API's map for a car is listed as missing there until a maintainer
adds it. Until then the unit maps it itself, after each HMG-API install or update, or
from Settings ("Map the points HMG-API's map lacks"). The engine command is `hmgapid
engine map-car --local`. It does this only for:
- points found **by name** (not by shape or smali pattern);
- apps whose stock files are the ones HMG-API's map was made from;
- each point resolving in the car's own apps (the `verify-map` check) before it's kept.

They're in `/data/hmgapi/state/local-map.json` and go in the Car Report (`mapped_here`).

For a mod:
- `Points.has()` and `Points.complete()` count these points.
- Field reads and **observe** hooks use them.
- A **change** or **replace** hook on one, and any call or constructor through it, needs the
  point in HMG-API's map, or the owner's "Try unchecked maps". Until then the plan says
  why and the mod's hooks don't load.

The owner can turn local points off ("Use points mapped on this car"), and
`map-car --forget` deletes them.

## Talking to HMG-API from your app or service

Compile against `hmgapi-client.jar` (`./build.sh client`) and bundle it:

```java
HmgApi api = HmgApi.connect();
if (api.available() && api.mod() != null) {
    JSONObject s = api.settings();          // your settings.json defaults + the owner's values
    List<String> sticks = api.usbFolders(); // "/storage/usb0/MyFolder"
    api.restartService("recorder");
}
HmgApi.toast(context, "Saved");             // the car's own notice
```

From a script: `hmgapid whoami`, `hmgapid setting-get`, `hmgapid usb`. `hmgapid`
knows your app by its uid and your service by its session, and only answers for
your own mod. Your settings page comes from `settings.json`; the owner changes it in
the HMG-API app, and hooks see new values at once.

## Other mods

```json
"requires": ["hello-api ^1.0", {"id": "wireless-carplay-aa", "version": ">=2.2"}],
"conflicts": ["egmp-aa-ev-standalone"]
```

Each entry is `"<id>"`, `"<id> <range>"` or `{"id": …, "version": <range>}`; ranges as for
`"hmgapi"` (`>=2.0`, `^1.2`, `~1.2.3`, `>=2.1 <3`, nothing for any version).

- **`requires`**: your mod loads after those mods, may use their API
  (`ModContext.use`, their `"api"` packages), and is off while one of them isn't
  installed and turned on, isn't in your range, or isn't loaded itself. The HMG-API app
  shows it in red with the reason; `engine plan` says `hooks <id> won't load: requires …`.
- **`conflicts`**: your mod and that one never run together. Of two mods that can't
  run together (either one lists the other, with the other's version in its range), the
  first in load order loads: mods they require come first, then by id. Which of the two
  lists the other doesn't matter, so two builds of one mod that list each other always
  settle the same way. The other is shown in yellow ("Conflict"), with which mod to turn
  off to use it instead. A mod turned on while apps run (`"live"`) doesn't load next to one
  it conflicts with that those apps loaded, even one turned off since; the next restart
  settles it by the order.
- These hold for every mod, with or without `patches.json`. A mod HMG-API's engine
  applies (`patches.json`, `feature_flags`, `probe`, `wifi`) that is missing a
  requirement or conflicts with another such mod also stops the apply until it's settled.
  A mod's apps and services (`"apps"`, `"services"`) stop while a mod it requires is off,
  but don't follow `conflicts` yet.

## Vehicle readings

| Name | Unit | From the car's |
|---|---|---|
| `gear.park` | 0/1 | system service, gear P |
| `speed` | km/h | telematics CAN service (`getCarSpeed`) |
| `moving` | 0/1 | telematics CAN service |
| `driving.restricted` | 0/1 | driving restriction (what stock video apps follow) |
| `steering.angle` | degrees, left negative | telematics CAN service (`getSasAngleVal`) |
| `pedal.accel`, `brake.pressure` | raw | telematics CAN service |
| `battery12v.soc` | % | telematics CAN service (`getBatSoc`) |
| `battery12v.sensor_state`, `battery12v.sensor_invalid`, `battery12v.sensor_error` | raw | the 12V battery sensor's flags, telematics CAN service (`getBATSNSRState`, `getBATSNSRInvalid`, `getBATSNSRError`); meanings not worked out yet |
| `battery12v.saver` | raw | the Auxiliary Battery Saver option, telematics CAN service (`get_BAT_AuxBattOpt`) |
| `hv.soc` | %, 0.5 steps | HEV service, `DISPLAY_SOC` |
| `hv.health`, `hv.ready`, `hv.conditioning`, `charging` | raw | telematics CAN / HEV service |
| `hv.conditioning_active` | 0/1 | the car's battery conditioning indicator (body control `getBattCondIndSta`): conditioning now |
| `hv.battery_temp_min` | °C, whole | the pack's minimum temperature (cluster battery info, asked for on each read; `-` outside -40..60 °C) |
| `hv.conditioning_failure` | raw | the BMS's conditioning failure popup flag (HEV `BMS_BATTCONDFAILPOPUP`); meaning of the values not known yet |
| `conditioning.state` | text | HMG-API's conditioning request: `off`, `requested` (the indicator can't be read), `waiting`, `active`, `stopping`, `ended` (by the car), `timed_out` |
| `odometer` | km | HEV service, `ODOMETER` |
| `seat_heat.<seat>` (driver, passenger, rear_left, rear_right), `seat_vent.<seat>` (driver, passenger: the rear seats have none) | level 0-3 | body control's seat climate step (one value per seat: off, ventilation 1-3 or heat 1-3) |
| `wheel_heat`, `defogger` | raw | body control's heating status: steering wheel heat (0 off), rear defogger (1 on, 0 off); pushed by the car, `-` until it has said |
| `seat_state.<seat>` | raw | that step: 2 off, 3-5 ventilation, 6-8 heat |
| `outside_temp`, `outside_temp_f` | °C, °F | climate control's outside temperature (pushed by the car; converted on a car that shows the other unit) |
| `parking_brake` | 0/1 | the electronic parking brake engaged |
| `power` | text | the power service's status: `on`, `logic_on` (the car is off, the head unit still up), `adm`, `system_ready`, `delay_mode`, `preparing_off`, `off` |
| `doors.closed` | 0/1 | all four doors closed (body control, pushed; the trunk and hood don't count); `-` until the car has said |
| `parked` | 0/1 | HMG-API's parked state, by the owner's rule (the `parked` / `unparked` car events) |

Each value is read through the car's own interface, by method name from the car's
hook map, and kept for a short time (100 ms for steering and pedals, up to 60 s
for battery health), so many mods asking cost the car one read. Per-car identity (VIN, serials) is never a
reading.

## Buttons

```json
"input": [
  {"key": "custom", "press": "hold", "open": "dev.example.app/.Grid"},
  {"key": "ptt", "press": "short", "stock": "replace", "broadcast": "dev.example.app.ASSISTANT"},
  {"key": "mute", "press": "short", "broadcast": "dev.example.app.MUTED"},
  {"key": "tune_up", "press": "down", "broadcast": "dev.example.app.KNOB"}
]
```

- `press`:
  - `hold` (0.8 s): the way to give a button a shortcut. It always takes the car's
    own hold for that button; a shorter press still reaches the car, when it's
    released. On a star key (`custom`, `wheel_custom`), the short press stays what
    the owner picks in the car's star-key menu (mods can offer entries there), and
    the hold is a mod's.
  - `short`: one press, seen when it's released; the car still gets it unless
    `"stock": "replace"`. A replaced short press leaves the button's long press to
    the car: held 0.8 s, it goes on to the car (the voice button's long press is
    Android Auto's and CarPlay's assistant).
  - `down`: every press and repeat at once, watching only (knobs).
  - No double or triple presses: telling them apart from single presses would delay
    every press of the button, or the car would act on each one.
- `stock` (`short` only): `keep` (default: the car still does what the button
  does) or `replace` (the car doesn't, for that press).
- `send` (optional): `"<key> short|hold"`, a press HMG-API makes the car see instead
  (a remap; the binding takes its own press from the car). `release` (hold bindings
  only): a press sent when the hold ends. Car Check's voice choices use them: a short
  press sends the voice button's long press (the phone's assistant, which stays open);
  a hold that sends it and then a short press on release (push-to-talk: a short press
  ends the phone's assistant). Sent presses come from the pressed button's device.
- `when` (optional): the binding counts only while the mod's setting holds, the same
  rule as parts: `settings.<key>`, `!settings.<key>`, or `settings.<key>=<value>` for
  a choice. Let the owner pick which press you take, e.g. the voice button's short
  press or its hold (Car Check's `voice` setting); a change counts at the next Apply.
- What happens: `broadcast` (an action sent to your apps, extras `hmgapi.key`,
  `hmgapi.press`) and/or `open` (one of your activities).
- Two mods keeping the same press both get it. Two mods replacing the same press:
  one wins, the plan says which, and the owner can pick
  (`hmgapi.json` `overrides.input` `{"tune_enter hold": "<mod>"}`).
- `sos` and `power` can be watched, never replaced.

| Key | Where |
|---|---|
| `power` | head unit |
| `home` | head unit |
| `map` | head unit |
| `navi` | head unit |
| `radio` | head unit |
| `media` | head unit |
| `custom` | head unit (star) |
| `setup` | head unit |
| `phone` | head unit |
| `seek_up` | head unit |
| `seek_down` | head unit |
| `volume_up` | head unit and steering wheel |
| `volume_down` | head unit and steering wheel |
| `volume_knob_up`, `volume_knob_down` | head unit volume knob turned (EV6: its own codes; the wheel's are `volume_up`/`volume_down`) |
| `tune_up` | head unit (TUNE knob turned right) |
| `tune_down` | head unit (TUNE knob turned left) |
| `tune_enter` | head unit (TUNE knob pressed) |
| `apps` | head unit |
| `mymenu` | head unit |
| `info` | head unit |
| `dest` | head unit |
| `fmam` | head unit |
| `fm` | head unit |
| `am` | head unit |
| `xm` | head unit |
| `dmb` | head unit |
| `ev` | head unit |
| `nmode` | steering wheel (N models) |
| `mood_lamp` | head unit |
| `performance_gauge` | head unit |
| `channel_up` | steering wheel (seek up) |
| `channel_down` | steering wheel (seek down) |
| `mode` | steering wheel |
| `mute` | steering wheel |
| `ptt` | steering wheel (voice) |
| `ptt_rear` | rear seat |
| `wheel_custom` | steering wheel (star) |
| `call` | steering wheel |
| `connect` | overhead console (Kia Connect / Bluelink) |
| `poi` | overhead console |
| `sos` | overhead console (emergency call: watched only) |

A car without a button ignores bindings to it. The key names are the car's own
(`KeyEvent.KEYCODE_SHORTCUT_MAP` …), looked up on each car.

## Phone projection

```json
"projection": {
  "aa_density": 200,
  "aa_sensors": {"remove": ["speed"], "add": [23]},
  "aa_fuel_types": [10], "aa_ev_connectors": [4, 1],
  "events": true
}
```

- `aa_density`: the DPI Android Auto lays out at (60-480; stock 240 on the wide
  screens), for every video setting, as AA DPI and aa-proxy-rs set it. One value
  for the unit: two mods asking differently is a conflict the owner settles
  (`overrides.projection {"aa_density": "<mod>"}`).
- `aa_sensors`: `remove: ["speed"]` doesn't offer the phone the speed sensor (no
  tap/scroll limit while moving; for every mod, the plan says so); `add` offers more
  sensor types (23: EV data). Library changes a new type needs stay the mod's own
  native patches (`hooks.native`).
- `aa_fuel_types`, `aa_ev_connectors`: instead of GPM's "unknown".
- `events`: your apps hear `hmgapi.intent.action.PROJECTION` with `event`
  `aa_connected`, `aa_disconnected`, `aa_navigation_focus` (value: who navigates),
  `aa_guidance` (1 active, 2 inactive, 3 rerouting) and `aa_navigation_message`
  (`type` 0x8006 destinations and charging stops, 0x8007 next turn distance and
  time; `data`: the message). Navigation messages come from the turn-by-turn
  library patch (the Wireless CarPlay/AA mod): require it to get them.

Settings apply through hooks when GPM starts, and every mod's asks are combined.

## Network

```json
"network": {
  "rules": [
    {"chain": "input", "in": "hotspot", "proto": "tcp+udp",
     "ports": ["5000-5001", 5020, "6000-6003", 6011, 6020, 6030, 7000], "both_ways": true, "action": "accept"},
    {"chain": "input", "in": "hotspot", "proto": "icmpv6", "action": "accept"}
  ],
  "routes": [{"to": "10.8.0.0/24", "dev": "tun0", "table": 100}],
  "route_rules": [{"priority": 1000, "fwmark": 16, "table": 100}],
  "sysctl": {"net.ipv4.ip_forward": "1"}
}
```

Some cars run a whitelist firewall (the Ioniq 6: wireless CarPlay finds the car on the
hotspot, then every CarPlay connection is dropped, because the CarPlay ports are only
allowed on the wired link). A mod never runs `iptables` itself: it says what it needs,
and HMG-API's network keeper (`hmgapi net`, run by hmgapid as root) puts it in, checks
it every 10 s, puts back what the stock firewall or a Wi-Fi restart took away, and
takes it all out when the mod is turned off or removed (the stock value of every
sysctl comes back).

- **rules**: `table` filter (default), nat (IPv4 only) or mangle; `chain` input,
  output, forward (filter), prerouting, postrouting, output (nat and mangle); `in` /
  `out` an interface (`rmnet+` for all of them) or `hotspot` (wlan0, from/to the
  hotspot's own addresses only: 192.168.0.0/24 and link-local fe80::/10; iPhones use
  IPv6 link-local for CarPlay); `from` / `to` `hotspot` or an address/prefix; `ip` 4, 6
  or both (default); `proto` tcp, udp, tcp+udp, icmp, icmpv6 or all; `ports`
  (destination) and `source_ports`, numbers or `"a-b"`; `both_ways` (the ports as
  source too, as the stock wired CarPlay rules do); `icmp_type`; `state` (new,
  established, related, invalid); `action` accept, drop, reject, return,
  `log:<prefix>`, `mark:<n>` (mangle), masquerade, `snat:<address>` (nat postrouting),
  `dnat:<address[:port]>`, `redirect:<port>` (nat prerouting or output); `when`
  (`settings.<key>`, as for parts). Every field is checked: there is no free-form
  iptables text.
- Each rule goes in HMG-API's own chain (`HMGAPI_IN`, `HMGAPI_OUT`, `HMGAPI_FWD`,
  `HMGAPI_PRE`, `HMGAPI_POST`, ...), which the stock chain jumps to **first**, so a mod's
  rule is checked before the car's own.
- **routes**: `to` (or `default`), `dev` (or `hotspot`), `via`, `metric`, `table` (main
  or 1-252). **route_rules**: `priority` (100-32765, one per rule), `fwmark`, `from`,
  `to`, `iif`, `table`. **sysctl**: `net.*` keys, whole numbers.
- **What needs the owner's yes:** accept/drop rules on the hotspot (input from it,
  output to it) go in as they are. Anything else (another interface, forward, nat,
  mangle, routes, sysctls) can reach the car's own network (the modem, the internal
  link), so the plan shows it as left out with a warning until the owner adds the mod
  to `overrides.allow_network` in `hmgapi.json`.
- Mods' rules go in mod order; `overrides.network_order: ["<mod>", ...]` puts some
  first. Where two mods match the same traffic differently, the plan says whose rule
  comes first.
- The Car Report includes the unit's firewall (the stock service and script, every
  rule and counter), routes and the packets the firewall logged as dropped, so a car
  that blocks something can be fixed from its report.
- By hand: `hmgapid sh 'sh /data/hmgapi/state/net.sh once'` checks once;
  `.../net.sh down` takes everything out. The keeper's log:
  `/data/hmgapi/logs/hmgapi/net.log`.

## Internet: home Wi-Fi, never the car's mobile data

A mod never uses the car's cellular link (`rmnet`), DNS lookups included. Its apps (by
uid) and its services (hmgapid runs them with HMG-API's group 2950 or 2951 as their
effective group) are refused there by the network keeper, and their lookups can't reach
the car's DNS servers: an app's go through `wlan0` in netd (no servers there unless home Wi-Fi is up), a service's run in its own
process (`ANDROID_DNS_MODE=local`). The owner can let one mod use mobile data anyway, at
their own risk: `"overrides": {"allow_cellular": ["<mod>"]}` in `hmgapi.json` (the plan
warns).

For internet, a mod asks for **home Wi-Fi**:

```json
"wifi": {"home": true}
```

With the owner's switch on (HMG-API app, Settings > Home Wi-Fi; it turns the car's own Wi-Fi
client feature on: one file, a restart), the head unit joins the owner's home network **while
the car is off**: switched off for 30 s (its screen goes dark with it, and the stock apps end
projection then), or `parked` (car events), whichever comes first. Then the hotspot wireless
CarPlay and Android Auto use goes off and the car's own Wi-Fi client joins; the moment the car
is switched on again, the client goes off and the hotspot comes back as it was. (A client
next to the hotspot, on a second interface, didn't hold up on these units' Wi-Fi driver.)
While joined, the traffic of the mods that ask (their apps, and their services) goes through
home Wi-Fi; the car's own stays on mobile data. A mod that asks while the switch is off has no
internet (the plan says so). IPv4 only for now. This is also how HMG-API itself is meant to
check for updates.

- Check before a download: `HmgApi.homeWifiUp()`, or `HmgApi.homeWifi()`
  (`{"state": "up", "rssi": -61, "freq": 2437}`; `state` up, waiting (the car is on),
  searching, nonetworks, stock (the car's own Wi-Fi settings have the Wi-Fi), off). It never
  gives the network's name. Car events' `parked` is a good moment to start one; switching the
  car on ends it (the client goes off).
- On request: the HMG-API app's "Connect now" (`hmgapid wifi now [minutes]`, `wifi back`)
  switches the same way with the car on, in P only (wireless CarPlay and Android Auto are off
  meanwhile); it ends on "back", out of P, a restart, or after the minutes (30 by default, at
  most 240; `0`: no time limit, until out of P).
  It's how HMG-API's updater is meant to get online later.
- The networks: in Android's own Wi-Fi config, added and forgotten in the HMG-API app (typing
  a key needs P; the page lists what home Wi-Fi saw while parked). Keys are never in a log or
  Car Report.
- The keeper: `hmgapi wifi` (`/data/hmgapi/state/wifi.sh`), its log
  `/data/hmgapi/logs/hmgapi/wifi.log`; routing table 95, rule priority 900, mark 0x2951.

## Bluetooth Low Energy

```json
"ble": {"central": true}
```

Gen5W's Bluetooth app ships with Android's `BluetoothGattService` turned off in its
resources, so `BluetoothAdapter.startLeScan` and `BluetoothDevice.connectGatt` do
nothing even though the Broadcom controller supports BLE 4.2 alongside Classic
Bluetooth. While at least one enabled mod declares `"ble": {"central": true}`,
HMG-API opens `@hmgapi-ble` inside `com.android.bluetooth` directly on the Bluedroid
HCI/ACL transport:

```java
Ble ble = Ble.connect(new Ble.SimpleListener() {
    @Override public void onAdvertisement(Ble.AdvRecord adv) {
        if ("BVM02".equals(adv.localName)) {
            ble.stopScan();
            ble.connectDevice(adv.mac, adv.addrType, "fff0");
        }
    }
    @Override public void onGattReady(String mac, int mtu) {
        ble.enableNotifications(mac, "fff1");
    }
    @Override public void onCharacteristicChanged(String mac, String uuid, byte[] value) {
        // notification or indication payload
    }
});
ble.startScan();
```

- **Coexistence:** only LE events and tracked LE connection handles are handled by
  HMG-API; Classic Bluetooth handles, L2CAP packet credits and command credits pass
  through to stock Bluedroid, so HFP calls, A2DP music and CarPlay/Android Auto
  pairing aren't disturbed. Controller `LE_Host_Support` is enabled only while an
  open BLE session is active and restored to its prior setting when the last session
  closes, and the native filter returns to a pass-through. Turning Bluetooth off closes
  open `Ble` clients with `closeReason() == "BT_OFF"` and re-attaches cleanly on the
  next session once Bluetooth is back on.
- **Shared across mods:** several mods can scan and hold connections at the same time
  (up to four peripherals at once on controllers with dedicated LE ACL buffers;
  active scan uses a 33% duty cycle for audio coexistence and pauses briefly while a
  connection is opening).
- **GATT operations** (`hmgapi.client.Ble`, bundled from `hmgapi-client.jar`):
  16-bit and 128-bit service/characteristic/CCCD discovery (`connectDevice`, optional
  service UUID filter), `requestMtu` (23–517 bytes, fragmented over 27-byte LE ACL
  frames), `readCharacteristic`, `readCharacteristicBlob`, `readLongCharacteristic`,
  `writeCharacteristic` (with or without response), `protectCharacteristic` (refuses
  writes to a read-only attribute on that link), `enableNotifications`,
  `enableIndications` and `disableNotifications`.
- **Who may connect:** your mod's own apps (`"apps"`, matched by UID) and
  `"services"` (matched by `hmgapid` session ID), while the mod is enabled. Turning
  the mod off closes its BLE sessions (`closeReason() == "MOD_DISABLED"`) and
  disconnects its peripherals at once without touching other mods.

## Files and settings that several mods share

- **Feature flags:** `"feature_flags": ["com.hkmc.software.connectivity.carplay_wireless"]`.
  HMG-API writes the same permissions file for every mod that asks and comments out
  its `featurexml.cfg` line; it stays while any mod wants it.
- **Wi-Fi client:** `"wifi": {"client": true}` (the `android.hardware.wifi` feature
  and `persist.wifi.state=3`, as Tilepad's toggle does); any number of mods can ask. The
  stock client and the hotspot don't run together (the stock Wi-Fi stack ignores the
  client switch while the hotspot is up); for internet, ask for home Wi-Fi instead (above),
  which switches between them while parked.
- **Wi-Fi hotspot:** `"wifi": {"hotspot": true}`: the stock hotspot feature
  (`com.hkmc.software.wifihotspot`), without which the unit never powers its Wi-Fi chip.
  Korean units and some US trims ship without it, or delete it at boot from
  `featurexml.cfg`. Where the stock file is there, HMG-API only comments out its
  `featurexml.cfg` line; where it isn't, HMG-API's own file goes in. Shared by every mod
  that asks, and back to stock when none does. Both asks can go together.
  A mod that asks for the hotspot and lists `"features": ["hmgapi.wifi"]` in `"system"`
  also runs on a unit where the chip hasn't been seen yet but its driver, firmware and
  `com.hkmc.software.wifi.module` feature are there (`hmgapi.wifi.unpowered`): the mod
  turns it on. Once the chip has shown on SDIO, it's `hmgapi.wifi` for every mod. The
  Car Report's `unit.wifi` says which: `seen`, `stock hotspot`, `unpowered` or `none`.
- **`media_codecs.xml` by codec name** (patches.json):
  `{"type": "codecs", "path": "/system/etc/media_codecs.xml", "stock": "any",
  "remove": ["OMX.google.h264.decoder"], "add": [{"name": "OMX.google.vp9.decoder",
  "type": "video/x-vnd.on2.vp9", "kind": "decoder"}]}`. Added codecs go last
  (lowest priority); comments are never touched; one mod adding what another
  removes is a conflict, with the reason.
- **List settings** (enabled keyboards): `{"type": "setting", "namespace": "secure",
  "key": "enabled_input_methods", "add": ["org.pocketworkstation.pckeyboard/.LatinIME"]}`
  (separator `:` by default). Every mod's entries on top of the stock list; the stock
  list comes back when none wants any.
- **Decided on the unit:** `"probe": {"script": "probe.sh"}` (or a jar and class)
  prints `{"changes": [...]}` (`codecs` changes, `add`s of the mod's own files). It
  runs once per mod version and firmware, against the unit as it was, and its
  answer is kept; `engine probe <mod>` runs it again. This is Codec Patch's
  "probe, then decide".
- **Rear camera turns:** `"vehicle": {"commands": ["rear_camera"]}`, then
  `vehicle-do rear_camera take|release` before and after opening the camera; one mod
  at a time, freed when it's turned off. The car's reverse view always comes first
  (`com.hkmc.intent.action.CAMERA_APP_START`, the gear).

## Vehicle commands

`"vehicle": {"commands": ["charge_door", "seat_heat"]}`, then `hmgapid vehicle-do
charge_door open` or `api.command("seat_heat", "driver", "low")`, without root. Each command keeps the guard its
review gave it (park only, one holder of the ambient light, conditioning stopped
when its mod is turned off ...): the list, arguments and guards are in
`vehicle-commands.md`.

## Car events

A mod that keeps something running (a phone session, a recording, a connection) can put
it away when the driver parks and leaves, and before the head unit shuts down, instead of
being cut off mid-way:

```json
"car_events": {
  "parked":   {"script": "bin/park.sh"},
  "unparked": {},
  "shutdown": {"script": "bin/off.sh", "user": "system"},
  "wake":     {}
}
```

| Event | When |
|---|---|
| `parked` | the car is off, in P, and by the owner's rule: a door opened and then all four closed (the default: the driver got out), all four are closed, or they've been closed for a minute or five (Settings > Vehicle > "When the car is parked"; `hmgapid park-rule door\|closed\|<10-1800 s>`). Once per stop. No door status from the car: no `parked` |
| `unparked` | the car is switched on again after `parked`, before the head unit shut down: bring back what you put away |
| `shutdown` | the head unit is about to shut down (the car's own system off). Scripts get 6 s; the car goes on after 10 s whatever happens |
| `wake` | the head unit came back from a QuickBoot suspend: apps and services kept running through the off, so bring back what `parked` or `shutdown` put away |

Each event reaches the mod three ways, all at once:

- **its script**, with the event as `$1`: a service of the mod's named `on-<event>` (start
  `manual`, no restart), so it runs as its `"user"` (root by default; `system`, `app`),
  with the services' environment, and its output is in `hmgapid service-log on-<event>`.
  It gets 60 s (6 s for `shutdown`), then it's stopped. The mod can't have a service of
  its own by that name.
- **its apps**: an ordered broadcast `hmgapi.intent.action.CAR_EVENT`, extra `event`
  (`HmgApi.ACTION_CAR_EVENT`, `EXTRA_EVENT`, `EVENT_*` in the client). Declare the receiver
  with `android:permission="android.permission.DEVICE_POWER"` so only HMG-API can send it,
  and use `goAsync()` for anything longer than a moment.
- **its hooks**, in each app process they run in: `mod.onCarEvent(app, listener)` in
  `appLoaded`. The listener runs off the app's main thread (post to the app's own thread
  for its objects), only for the events the mod asked for and only while it's on; HMG-API
  waits up to 5 s per process.

HMG-API waits for all three to finish (or their time to run out) before it sends the
next event (`shutdown` doesn't wait for a `parked` still running), and at `shutdown`
before it tells the car it's done. It only holds a shutdown up while an
enabled mod asks for `shutdown`. The vehicle service runs while any enabled mod asks for
an event. `hmgapid service-log hmgapi vehicle` says what it saw (power, doors) and what
each event did.

## Packaging

```
tools/mkmodpkg.sh my-mod <your key>
```

A mod that clashes with a NEMESIS package (both change the same files or settings) lists
it: `"nemesis_conflicts": ["wireless.projection"]`. The mod's NEMESIS package
(`tools/mknemesismod.sh`) then refuses to install while that package is there, and the
other way round. A mod installed through HMG-API gets the same refusal when HMG-API lists
it in NEMESIS (engine `nemesis-sync`). The engine's own install ignores the field.

A mod that needs another lists it in `"requires"` (`"wireless-carplay-aa >=2.1.0"`, or
`{"id": "wireless-carplay-aa", "version": ">=2.1.0"}`). Its NEMESIS package then depends on
that mod's package, from the lowest version the range lets in
(`hmgapi.wireless-carplay-aa@>=2.1.0`; a pre-release floor like `^2.2.0-rc.1` as its
package-version, `2.1.99.1`), so NEMESIS won't install it without that mod. An optional
entry (`"optional": true`, a mod yours runs without) stays out of it.
NEMESIS checks only that floor: the rest of a range (`^`, `~`, an exact version, an upper
bound) is HMG-API's own check, and `mknemesismod.sh` prints a note when it leaves part of
a range out. A `"requires"` entry that isn't a mod id, or a mod both required and
refused in `"nemesis_conflicts"`, stops the build (`install/nemesis/README.md`).

Your key (`java -cp out/engine-host hmgapi.engine.Signing keygen <file>`) signs every
version. The mod's id comes with a UUID made from it, and only packages signed
with the same key update it. Owners install from a USB stick (`HMGAPI/updates/`)
or adb.

HMG-API ships a trusted author list (`maps/authors.json`, by author key UUID). A
mod from a key that isn't listed still installs, with a warning to the owner. To
be listed, add your mod to the community catalogue (`catalog/`, `tools/catalog.py
add`; see the wiki's Making mods page): the same pull request lists your key. An
optional `"repo": "https://…"` in `mod.json` is the link the catalogue shows for
your source. Keep your key private and backed up: a lost key
means a new UUID, and owners would have to remove the old mod before installing
yours again.

## Building a mod outside this repo

A mod in its own repository builds with the HMG-API mod SDK, `hmgapi-mod-sdk-<version>.zip`
on each HMG-API release from 1.1.0, its betas and release candidates included (or `./build.sh sdk` here, into `out/dist`). Put the
mod in a folder named by its id (`my-mod/mod.json`), with `README.md`, `LICENSE` and
`.github/` at the repository root: everything in the mod folder but `build.sh`,
`test.sh`, `src`, `tools` and `native` goes into the package.

Pin the SDK in `hmgapi-sdk.json` at the root, and call HMG-API's mod workflow from
`.github/workflows/ci.yml` (`jobs: mod: uses: Open-HMG/HMG-API/.github/workflows/mod.yml@<same pin>`):

| Pin | `hmgapi-sdk.json` | Workflow at |
|---|---|---|
| a commit (between releases) | `{"ref": "<HMG-API commit SHA>"}`: built from that commit | `@<that SHA>` |
| a release (from 1.1.0-beta.1) | `{"version": "1.1.0", "sha256": "<the zip's, from SHA256SUMS>"}` | `@v1.1.0` |

The workflow checks the mod's `"hmgapi"` floor (at or below the SDK; every
`hooks.points` entry in that release's points), builds it, runs its `test.sh` and
`tools/*/test.sh`, checks its scripts, and packages it with a throwaway key. Your
release workflow signs with your own key. A new hook point is a HMG-API pull request
first (`docs/mod-repos.md`, section 4).

A mod whose `build.sh` compiles native code can ask for an Android NDK with
`with: ndk: "<version>"`, as `sdkmanager` names it (hmgapi-adbd: `21.4.7075529`, r21e).
The workflow installs it before the build, with `NDK` and `ANDROID_NDK_HOME` pointing at it.

On your machine, with the SDK unpacked in `$HMGAPI_SDK` and the Android SDK in `ANDROID_HOME`:

```
$HMGAPI_SDK/bin/build-mod.sh my-mod out/build/my-mod
HMGAPI_SDK=$HMGAPI_SDK HMGAPI_MOD_BUILD=out/build/my-mod sh my-mod/test.sh
$HMGAPI_SDK/bin/check-scripts.sh my-mod
$HMGAPI_SDK/bin/mkmodpkg.sh my-mod <your key>
```

A `test.sh` sources `$HMGAPI_SDK/test/lib.sh` for `check`, `has`, `eng` (the engine on
your machine, on a fake head unit in `$R`) and `tree`. `mods/examples/hello/test.sh` is
the template. The README in the SDK lists the rest.

A test that needs a car's stock files gets them from `car_tree` (and `car_prop`), and
skips without them; never a path of your own. `tools/cars.py test --mods <your repo>`
in an HMG-API checkout then runs your tests on every stock tree you have
([car-trees.md](car-trees.md)), one car at a time, with that car in `HMGAPI_CAR`:

```
S=$(car_tree) || { echo "no stock tree (skipped)"; exit 0; }
PRODUCT=$(car_prop ro.build.product)
```

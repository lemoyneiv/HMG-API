# Car test session: HMG-API 1.0.0

Everything since the hook runtime has been tested off the car only. This session
checks it on a real unit, in an order where each step unblocks the next. Everything
is installed and driven over adb from the laptop; the head unit's screens are only
looked at (and touched where a step tests the car's own settings screen). For each
step: what to do, what should happen, and what to write down. Mark each one
**pass**, **fail** (with what happened) or **skipped**.

Car: US Kia EV6, 260507. Park somewhere you can sit for an hour or two, in P with
the parking brake on. Everything is done parked; nothing here needs driving. Never
press SOS.

## 0. Before you go (at the computer)

1. Build and package:
   ```
   ./build.sh
   ```
   ```
   tools/mkinstallers.sh
   ```
   ```
   for m in mods/examples/car-check mods/examples/side-panel mods/examples/hello-service; do tools/mkmodpkg.sh $m ~/hmg-private/keys/mods/vyssecodes.key; done
   ```
   ```
   tools/catalog.py fetch out/packages wireless-carplay-aa
   ```
   The wireless mod comes from its release (its catalogue entry, SHA-256 and signature
   checked); to test a change to it, package it in its own repo instead.
   The mod packages are in `out/packages/`, the installers in `out/dist/`.
2. Laptop with adb over USB (the `adb` mod; adb over Wi-Fi is its own switch). Never
   the car's own ADB TCP switch (Engineering Mode, or NEMESIS's Enable ADB): it puts
   the internal `eth0` on `192.168.0.123`, in the hotspot's range, and wireless
   CarPlay/Android Auto then never get an address (the plan warns). Keep
   USB drives out of the port all session (a drive can stall Android Auto).
3. Start a log capture before the first step and keep it running all session:
   ```
   ~/hmg-private/tools/carlog.sh
   ```
   (or `adb logcat -v time > session.log`). Most results below are confirmed in it:
   HMG-API's lines carry the tag `HMG-API`, the test mod's `CarCheck`, the vehicle
   service writes to `/data/hmgapi/logs/hmgapi/vehicle.log`.

## 1. Install

1. The unit still has the old Leafia dev build; HMG-API's installer refuses until
   it's gone:
   ```
   adb shell "cp /system/xbin/nemesis /data/local/tmp/nemesis-d && chmod 755 /data/local/tmp/nemesis-d"
   ```
   ```
   adb shell "/data/local/tmp/nemesis-d root -c 'sh /data/leafia/uninstall.sh'"
   ```
   ```
   adb reboot
   ```
2. Install HMG-API over adb: unzip `out/dist/hmgapi-1.0.0-adb.zip`, then:
   ```
   ./hmgapi-adb.sh install
   ```
   ```
   adb reboot
   ```
   - **Expect:** the HMG-API tile in the app menu (or open it with `adb shell am start
     -n hmgapi.app/.MainActivity`). The map is only *mapped* until it's checked on
     this car, so the app says HMG-API is off and why. That's expected here.
3. Check the hook map against this car (each of the 280 points resolved in the car's
   own code; this also checks the two framework libraries outside the boot
   classpath, fixed just before this session):
   ```
   adb shell hmgapid engine verify-map
   ```
   - **Expect:** "280 of 280 hook points resolve", "HMG-API runs with it from the
     next start". **Note:** any FAILED line (copy the output). Then `adb shell hmgapid
     reboot`.
   - After the restart the app says the hook runtime is running, with the map
     checked on this car.
4. Points newer than the map (only on a car whose map lists some as missing:
   `adb shell hmgapid engine map` says how many aren't mapped):
   ```
   adb shell hmgapid engine map-car --local
   ```
   - **Expect:** "N point(s) HMG-API's map … lacks, mapped and checked on this car", no
     "didn't resolve" line for a by-name point. After a restart, `engine map` says "N
     point(s) mapped on this car (reads and observe hooks)". **Note:** the output.

## 2. Mods in, the app's controls

1. Push the four packages and install them with your key's UUID (from step 0):
   ```
   adb push out/packages/. /data/local/tmp/hmgapi-packages
   ```
   ```
   adb shell 'for z in /data/local/tmp/hmgapi-packages/*.zip; do hmgapid engine install $z; done'
   ```
   - **Expect:** `installed <id> 1.0.0 (author key <uuid>)` for each, with `author
     VysseCodes (... on HMG-API's trusted author list)`.
2. Switch them on and look at the plan:
   ```
   adb shell 'for m in car-check side-panel hello-service wireless-carplay-aa; do hmgapid enable $m; done'
   ```
   ```
   adb shell hmgapid engine plan
   ```
   - **Expect:** apps, services, readings, commands, buttons, projection and the
     panel page listed, and no `problem` lines. **Note:** any that appear.
3. Apply and restart:
   ```
   adb shell hmgapid engine apply
   ```
   ```
   adb shell hmgapid reboot
   ```
   - **Expect:** the HMG-API app (look only) lists the four mods, switched on.
4. Apps installed:
   ```
   adb shell pm list packages dev.hmgapi
   ```
   - **Expect:** `dev.hmgapi.example.carcheck` and `dev.hmgapi.example.panel`.
5. Services running:
   ```
   adb shell hmgapid services
   ```
   - **Expect:** `hello-service hello running` and `hmgapi vehicle running`.

## 3. Readings (parked)

1. ```
   adb shell hmgapid vehicle gear.park parking_brake speed moving driving.restricted steering.angle pedal.accel brake.pressure
   ```
   - **Expect:** `gear.park 1`, `speed 0`, `moving 0`, `driving.restricted 0`.
   - Turn the wheel full left, then right, and ask for `steering.angle` again:
     negative left, positive right, a few hundred degrees at full lock. **Note** the
     values.
   - Press the brake pedal and ask for `brake.pressure`; press the accelerator
     (in P) and ask for `pedal.accel`. **Note** whether they move.
2. ```
   adb shell hmgapid vehicle battery12v.soc hv.soc hv.health hv.ready hv.conditioning charging odometer seat_heat.driver seat_heat.passenger guide_lines
   ```
   - **Expect:** `hv.soc` matches the cluster's battery % (within 0.5), `odometer`
     matches the cluster (km), `battery12v.soc` a plausible %. **Note** all values and
     what the cluster shows.
3. Ask for a reading twice within a second: same value (shared within its
   freshness window), no error.

## 4. Vehicle commands (parked, one at a time)

Run each as the owner (`adb shell hmgapid vehicle-do ...`). Each answers `ok ...` or
`refused: ...`.

1. Charge door: `vehicle-do charge_door open`. **Expect:** the door opens. Wait 5
   s, `vehicle-do charge_door close`. **Note:** does `close` close it, open it, or do
   nothing? (Not yet seen on a car.)
2. Seat heat: `vehicle-do seat_heat driver low`, then `medium`, `high`, `off`.
   **Expect:** the seat heat lights follow; `vehicle seat_heat.driver` reads 6, 7,
   8, 2. Same for `passenger`.
3. Wheel heat: `vehicle-do wheel_heat on`, then `off`. **Expect:** the wheel heat
   light follows.
4. Conditioning: car in READY, Battery Conditioning on in the EV settings
   (`vehicle hv.conditioning` reads 2). `vehicle hv.battery_temp_min
   hv.conditioning_active` (**note** the temperature). `vehicle-do conditioning start`
   (a warm pack is refused: add `warm` to send it anyway). **Expect:** within 5 minutes
   on the EV6 (about a minute on the Ioniq 5) `vehicle hv.conditioning_active` reads 1
   and `vehicle conditioning.state` reads `active`; if not, the state goes to
   `timed_out` and the stop is sent (the log says so). Then `vehicle-do conditioning
   stop`: `conditioning.state` `stopping`, then `off` once the indicator is. **Note**
   how long each took, and the temperature.
   Then `vehicle-do conditioning auto-start`: **expect** refused (a manual stop
   blocks automatic starts).
5. Ambient light: select *Custom colour* in the car's light settings (a theme such as
   Peaceful Forest overrides it) and note your colour. `vehicle-do ambient_light color
   ff0000` (answers `ok color ff321d`, the car's nearest red), then `brightness 3`, then `release`. **Note:** does your colour come back
   on release? (Unknown: the car has no getter for it.)
6. Guide lines: `vehicle-do guide_lines off`, shift to R and look at the camera view
   (lines gone?), back to P, `vehicle-do guide_lines restore`, R again (lines back?).
7. Rear camera turns: `vehicle-do rear_camera take` → ok; a second take by another
   mod would be refused (the owner always can); `release`.
8. Startup notice (optional; it has no known undo): `adb shell hmgapid allow
   startup_notice on` (the app's switch shows it on), then at the next start, while
   the notice shows, `vehicle-do startup_notice dismiss`. **Note** whether it went
   away. Then `adb shell hmgapid allow startup_notice off`.

## 5. Buttons (Car Check shows a notice for each)

Each binding below should show the car's notice "Button: <key> <press>".

| Press | Expect | The car's own action |
|---|---|---|
| MAP held 1 s | "map hold" | nothing else |
| MAP pressed once | no notice | the map opens at release |
| TUNE knob held 1 s | "tune_enter hold" | nothing else |
| TUNE knob pressed once | no notice | the car's normal TUNE press, at release |
| Steering wheel voice (PTT) pressed once | "ptt short" | the car's voice assistant doesn't start |
| Steering wheel voice held 1.5 s (phone projected) | no notice | Android Auto's / CarPlay's assistant, as usual |
| Head unit seek up held 1 s | "seek_up hold" | no fast seek |
| Head unit ★ held 1 s | "custom hold" | nothing else |
| Head unit ★ pressed once | no notice | the ★ action you picked, at release |
| Steering wheel ★ held 1 s | the side panel page refreshes (Side Panel's binding) | nothing else |
| Steering wheel mute / call once | "mute short" / "call short" | mute / call as usual |
| TUNE knob turned right a few clicks | "tune_up down" per click | the knob as usual |

**Note** for each: pass/fail, and anything the car did that it shouldn't (a double
action, a missed press, a delay that feels wrong). `adb logcat -s HMG-API` shows
`input: <key> <press> -> <mod>` for every binding that fired.

## 6. Side panel

1. Open the car's split-screen settings (Settings > Display or Home screen >
   split screen, where you pick the right-hand panel's pages). **Expect** an entry
   named "Car" (Side Panel's page). Turn it on, save.
2. Show the split screen with that page. **Expect:** Car's widget fills the panel:
   HV charge %, 12V %, "Park". Tap it: refreshes.
3. Steering wheel ★ twice: refreshes (the button binding reaching the widget).
4. **Note:** the page's size and look, whether other pages still work, whether the
   editor kept your choice after a restart.

## 7. Phone projection

1. Android Auto, wireless (the phone on the car's Wi-Fi, as before):
   - Car Check shows "Projection: aa_connected", then "aa_navigation_focus".
   - The AA screen is laid out at 200 DPI: visibly smaller text and icons than stock.
     **Note** with a photo if you can.
   - Start a navigation in Maps: "aa_guidance 1", and with the wireless mod's
     turn-by-turn on, "aa_navigation_message type 0x8006 / 0x8007" notices; the
     cluster shows the turns as before.
   - Disconnect: "aa_disconnected".
2. CarPlay, wireless: "cp_connected 1" on connect, "cp_disconnected" on disconnect;
   turn-by-turn in the cluster as before.
3. Tap limit: can't be checked parked (it applies while moving). Skip, or check
   later as a passenger.

## 8. Not while driving (needs the car moving: passenger only, or skip)

With SmartTube (or any app a mod marks `not_while_driving`) open, start moving
slowly: the app closes with the car's "not available while driving" popup and
won't reopen until the car stops. Only test this with someone else driving.

## 9. MICOM recording

1. ```
   adb shell hmgapid micom-watch all
   ```
2. Do these one at a time, 10 s apart, noting the clock time of each: seat heat on,
   seat heat off, wheel heat on, off, a door opened and closed, the charge door
   opened (charge_door command), conditioning on and off, headlights on and off.
3. ```
   adb shell hmgapid micom-log > micom-session.log
   ```
   ```
   adb shell hmgapid micom-watch off
   ```
4. At home: `tools/micom-decode.py micom-session.log ~/hmg-private/wiki/private/micom/ev6-usa-260507.json --changes`.

## 10. Restart and removal

1. `adb shell hmgapid reboot`: everything above still works (bindings, panel page,
   services: `adb shell hmgapid services`).
2. Remove Car Check:
   ```
   adb shell hmgapid engine remove car-check
   ```
   ```
   adb shell hmgapid engine apply
   ```
   - **Expect:** its app is gone (`pm list packages dev.hmgapi`), the buttons are the
     car's again, Android Auto back to stock DPI on the next connection. The app's
     Remove button does the same; tap it for one of the other mods if you want to
     see it work.
3. Optional, at the very end: `./hmgapi-adb.sh uninstall`, then `adb reboot`: the
   unit is stock (the menu has no HMG-API tile; CarPlay/AA wired as stock).

## Results

Copy this and fill it in:

```
Step  Result  Notes
1.2
1.3
2.1
2.2
2.3
2.4
2.5
3.1
3.2
4.1   (close did: ...)
4.2
4.3
4.4   (indicator on after ... s, off after ... s)
4.5   (colour back: yes/no)
4.6
4.7
5     (one line per row)
6
7.1
7.2
9     (log saved: yes/no)
10
```

## Findings: first session (US EV6 260507, 2026-10-31)

Fixed on the car during the session: the adb installer didn't copy the hook maps;
verify-map didn't load framework libraries or apps' libraries; hmgapid's clock
(time(2) is OABI only) kept services and app installs waiting. Remapped from what
the car answered: steering angle (`navi_getSasAngleVal`), parking brake (the EPB:
2 engaged, 1 released), seats (body control's seat step; rear seats two levels),
outside temperature (climate control's status; the system service sends 0).

| Area | Result |
|---|---|
| Install, verify-map, mods, apply, services, apps | pass (after the fixes above) |
| Readings: gear, speed, moving, pedals, brake, steering, parking brake | pass |
| Readings: seat heat and ventilation (front and rear), outside temperature | pass |
| Readings: wheel heat, rear defogger | pushed values arrive: wheel heat 1 on, 0 off; defogger 1 on, 0 off |
| Charge door open, close | pass |
| Seat heat (front, rear two levels), seat ventilation (front) | pass |
| Buttons (key path) | on 260507 keys reach `ModeKeyDispatcher.interceptKeyFromAndroid` (not `ModeManagerService.interceptKeyBefore*`, never called); hook moved there. Every head unit and wheel button arrives once as a key, codes as in the button table; the head unit volume knob is `VOLUME_UP_DUMMY`/`VOLUME_DOWN_DUMMY` (added: `volume_knob_up`/`down`), its press `POWER_AVN` (`power`, watch-only); no end-call key |
| Button presses | multi-press (x2-x4) dropped: it delays every press or the car acts on each; `hold` is the shortcut and always takes the car's hold. Confirmed: MAP, TUNE, head unit seek and star, wheel star holds fire; their short presses reach the car at release (put back with `InputManager.injectInputEvent` from `WindowManagerService.interceptKey`; calling the car's dispatcher again did nothing); voice short press taken, long press to the phone. A keeping short binding fired on a long press too: fixed (not short past 0.8 s). Voice: owner's choice in Car Check's settings (`when`) |
| Voice `phone` (Android Auto) | a short press opens Android Auto's assistant: HMG-API sends a voice hold, and the long press repeat Android adds to an injected key at 500 ms (`FLAG_LONG_PRESS`) now passes to the car (it was taken as a new press, so the car saw a short press and started its own assistant). With the assistant open (`GpmManager.isGpmVR`, as the car checks; Siri: `DiPOMode.getSpeech`) the car gets a plain short press, as without HMG-API. Android Auto sends every press to the phone as `KEYCODE_SEARCH` (84) and the phone decides: stock, a short press with Gemini open sometimes ends it, sometimes restarts it; a hold restarts it. Not ours to change. `phone_ptt` (hold = push-to-talk, ends on release): the release ends Android Auto's stream and cuts off the voice command; owner prefers the phone deciding when to stop. Voice remaps parked: Car Check's `voice` defaults to `off` (the car keeps both presses). Wanted later: a press opens the assistant and the phone ends it, with a second press forcing a stop |
| Side panel page | pass after two fixes: the split-screen editor (ThemeSettings) titles saved pages by string id (`getString(0)` for slot 22 crashed it: `themesettings.split_items.string_id` now answers a made-up id), and its own availability check dropped 22 and saved the lists without it (`themesettings.split_items.available`). "Car" listed, shown and refreshed by a wheel ★ hold |
| Projection (Android Auto) | density: GPM offers the phone `density: 200, realdensity: 200` at every resolution (`VideoSink : resolution: ...` at session start); hard to see by eye against stock 240. Events reach Car Check (`aa_connected`, `aa_navigation_focus`, `aa_guidance`) after two fixes: a mod's app never opened is "stopped" and Android dropped the broadcasts (now `FLAG_INCLUDE_STOPPED_PACKAGES` for events, button bindings and ★ actions), and a phone that's just gone (Wi-Fi off: ping timeout) ends the session through `GalReceiverService.onDisconnected`, not `GalReceiver.destroy` (both hooked, one `aa_disconnected` per session) |
| Turn-by-turn (Android Auto) | first drive: nothing on the cluster. The wireless mod had never loaded: the zygote checks `hmgapi.wifi` at 04:30:04, before the chip is on SDIO (`wlan0` appears seconds later), so "no Wi-Fi chip"; wireless AA kept working on the mod's installed files. Now remembered once seen (`/data/hmgapi/public/wifi-chip`, written by the engine). Loading it then showed the DiPO part failing: Dalvik refuses `ClassLoader(null)` (`AppPartLoader`; the desktop JVM allows it). After both: AA library patch applied, navigation yields, CarPlay listener and bridge up; parked with a Maps route the cluster and HUD show the turn (`0d04` type 0x41), road, destination, remaining distance and arrival time |
| MICOM recording (parked, 100 s) | seat heat (driver, already a reading through USM): `USM_Front_Seat_Heat_SET_M_RESP` byte 3, mirrored in BODY `0xc419` byte 0 and VR `0xd520`: 08 → 07 → 06 → 02 as the owner pressed 3 → 2 → 1 → off. Wheel heat: `USM_SWRC_SET_M_RESP` (`0x9127`) byte 3 / BODY `0xc40d`, 01 on, 00 off, sent only on a change (second recording: on at 2.8 s, off 22 s later). `HEV_EV_HEV_PHEV_DATA_M` (`8c02 57 00 xx`) ramps 0f → 14 → 00 on a ~30 s cycle by itself: not the heater. Headlights: `SYS_RHEOSTAT_M` 09 → 08 (the display dims). Charge door opened: BODY `0xc449` 01 → 02 (its closing fell after the recording). The driver's door was open throughout, so doors weren't tested. `micom-log` returns only the end of the log; pull `/data/hmgapi/logs/hmgapi/micom.log` for a whole session |
| Lane follow assist (wheel) | not a key, and no MICOM frame to the head unit changes with it (4 presses: only `88 8c02 57..`, which cycles every ~15 s on its own). The car handles it outside the head unit |
| Battery conditioning | our distance and time reach HEVService every 5-6 s. `hv.conditioning` is the owner's *Battery Conditioning* setting in EV settings (1 off, 2 on), not whether it runs. Turning that setting on made the car's navigation (no route of its own) send "no station" (`setBattCondEvStation 0 0`), which undid ours: the keep-alive now re-sends the station too. With the setting on: our station/distance/time go out every 6 s (HEVService sends the station as a 120 ms pulse, then "to Invalid" = no change: normal); the EV page's nearby fast charger is its stock nearest-DC-charger lookup (EV app asks the navigation every 3 s, 10 km radius), shown with or without conditioning or a route: not something HMG-API sets. No route needed (navigation: `Route::IsExist=0`). Heater power (`getCO2Rdc`, HEV 47: `BMS_HEATPWRMONCON_W`) and BCM `getBattCondIndSta` (109) read 0 running and stopped at 73 F: unproven. Retest on a cold morning, reading HEV 47 before and while running |
| Ambient light colour | works with *Custom colour* selected in the car's settings (not a theme such as Peaceful Forest). The car takes only its 64 picker colours and ignores any other RGB without answering; `color` now sends the nearest one (`ff0000` → `ff321d`). Brightness and release: still to check |
| Guide lines | accepted, no effect; the RVM guide-line state reads 0: a 360-view car draws them through the surround-view system (Rear Dashcam: doesn't work on EV6 GT-Line); this EV6 has the 360 view |
| Steering wheel lane following assist button | not a head-unit key in the key layout; record it with the MICOM watch to see if it reaches the head unit |

Wheel heat command: pass. Still to run: buttons, side panel, projection, MICOM recording.

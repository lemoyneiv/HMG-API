# Changelog

## Unreleased

### Cars

- **Hook maps from Car Reports** (made on owners' cars by HMG-API's own mapper, not yet
  checked on a car): Kia Sportage, Telluride, Niro EV and Carnival (US 260507), Hyundai
  Palisade (US 260903). The Niro EV, Carnival and Palisade reports came from an HMG-API
  older than 1.1.0, so the 8 newest hook points (four for wireless CarPlay, four for the
  12 V battery) are listed missing until a newer Car Report.
- **`tools/report-map.py`:** `--make` and `--name` for a report from a car HMG-API had no
  name for, and catalogue points newer than the report are listed missing (with why).

### Fixes

- **Vehicle readings answer at once, with code 0** (#145). Every `hmgapid vehicle` read
  took 3 s and came back with code 1, the values right: on these units an accepted
  `LocalSocket`'s `close()` doesn't close its fd, so the vehicle service never ended its
  answer, and it leaked an fd per read, command and light status. It now closes the fd
  itself, and hmgapid takes a whole answer as success even if the connection stays open.

### For mod authors

- **Bluetooth Low Energy scanner and GATT central** (`"ble": {"central": true}` in
  `mod.json`, `hmgapi.client.Ble` in `hmgapi-client.jar`): scan for BLE peripherals,
  connect (up to four at once, shared across mods), negotiate ATT MTU and read, write or
  subscribe to 16-bit and 128-bit GATT characteristics without root, alongside Classic
  Bluetooth phone calls and audio. Shown in the plan, and disconnected at once when a
  mod is turned off. See "Bluetooth Low Energy" in `docs/mod-reference.md`.

## 1.1.0-beta.1 (2026-11-08)

### For mod authors

- **The mod SDK** (`hmgapi-mod-sdk-<version>.zip` on each release from 1.0.3, or
  `./build.sh sdk`): build, test and package a mod in its own repository, with HMG-API's
  reusable mod workflow (`.github/workflows/mod.yml`) as its CI. See "Building a mod outside
  this repo" in `docs/mod-reference.md`.
- **`"nemesis_conflicts"` in mod.json:** the NEMESIS packages a mod's own NEMESIS package
  refuses to be installed with. HMG-API's NEMESIS sync uses it too, when it lists a mod
  installed through HMG-API in NEMESIS.
- **The mods HMG-API ships have their own repos:**
  [Wireless CarPlay + Android Auto](https://github.com/Open-HMG/hmgapi-wireless-cp-aa),
  [ADB](https://github.com/Open-HMG/hmgapi-adb), [adbd](https://github.com/Open-HMG/hmgapi-adbd)
  and [NEMESIS in the app menu](https://github.com/Open-HMG/hmgapi-nemesis-shortcut). A
  HMG-API release still carries the wireless, ADB and NEMESIS-menu mods, taken from their
  catalogue entries (same files, signature checked). A HMG-API pre-release may instead pin
  a mod's pre-release in `tools/release-mods.txt`, with the same checks.
- **`mknemesismod.sh`, `mkmodpkg.sh`, the mod SDK and HMG-API's downloads give the same zip
  bytes on any machine.** Outside UTC and the C locale, the times inside the zip and the
  order of its files changed.
- **Versions: alpha, beta, rc and final** ([docs/releasing.md](docs/releasing.md)).
  Pre-releases sort by semver (`rc.9` < `rc.10`, alpha < beta < rc < final), in the engine,
  the catalogue and the mod checks. A mod's `"hmgapi"` may name a core pre-release
  (`">=1.1.0-rc.1"`, for a mod's own pre-release), checked against the full core version.
  NEMESIS package-versions: `.97.N` alpha, `.98.N` beta, `.99.N` rc. The catalogue lists
  final releases only.
- **The mod workflow can install an Android NDK** (`with: ndk: "21.4.7075529"`), for a mod
  whose `build.sh` compiles native code.
- **`tools/test-all.sh --fast`:** the off-car tests that need no build and no ARM
  toolchain, in under a minute on Linux or macOS. The full run is several tests at once
  (`-j`), and a test that skipped everything shows as `skip`.

### Changes

- **Wireless CarPlay + Android Auto 2.2.0-beta.1** comes with this beta (ADB 1.0.0 and NEMESIS
  in the app menu 1.1.0 as before): CarPlay turns for more maneuvers (#15), lanes only from
  the phone and Android Auto turns in the car's own format, a setting (wireless #4), no
  loading spinner at roundabouts (wireless #11).
- **The US Ioniq 5 (2023 on) 260903 map has every hook point** (351 of 351): the 26 it
  lacked, made on the car by HMG-API's own mapper. Thanks to joelsmith for the Car Report.
  Mods that need them (the 12V battery readings, the iAP2 service points, the cluster and
  CarPlay points) now run there too. Still waiting for a check on a car.
- **"Support the project" in the app's Settings** now shows the wiki's
  [Supporting HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Support) page, which lists
  each maintainer's own donation links, instead of one Ko-fi link.

### Fixes

- **The head unit no longer restarts at car start when a car sends Digital Key data.** The
  vehicle service answered the car's `out` array callbacks without the array, which crashed
  system_server. This could happen with any mod that reads the car (US Niro EV 260507).

### For mod authors

- **Readings for the 12V battery sensor:** `battery12v.sensor_state`,
  `battery12v.sensor_invalid` and `battery12v.sensor_error` (the sensor's flags) and
  `battery12v.saver` (the Auxiliary Battery Saver option), raw until their values are
  understood, and read unsigned as the car sends them (0-255; the getters return a byte).
  On the US and Canada EV6 and Ioniq 5; `-` on cars whose map lacks them.
- **The script check reads more shell forms:** continued lines, `a|b)` case patterns and
  nested parentheses in `$(( ))` no longer show up as missing commands.

## 1.0.2 (2026-11-04)

### Fixes

- **NEMESIS starts on time, for real this time.** 1.0.1's fix didn't work on the cars:
  the head unit's shell keeps copies of a job's output above fd 9 while it sets up the
  job's redirections, so the boot hook's background NEMESIS sync still held `hmgapid sh`,
  and NEMESIS still started about two minutes late (US EV6). `hmgapid sh` now finishes
  when its command exits, whatever a background job still holds.
- **HMG-API never holds up NEMESIS's start.** HMG-API's boot hook is now one line,
  `hmgapid boot`, which returns at once: it starts HMG-API if the zygote hasn't already,
  and runs its boot steps (`/data/hmgapi/boot.sh`) detached, alongside NEMESIS instead of
  before it, once per boot. So no slow boot step (an `engine recover` after a cut-off
  apply, say) can make NEMESIS wait either.
- **HMG-API starts with NEMESIS too.** HMG-API's NEMESIS package has a boot service
  that runs the same `hmgapid boot`, so HMG-API starts even when a NEMESIS install or
  update has rewritten `install-recovery.sh` without HMG-API's block. Whichever comes
  first starts HMG-API; the other does nothing. Cars without NEMESIS start from the boot hook alone, as before.

## 1.0.1 (2026-11-03)

### Fixes

- **NEMESIS starts on time again.** With 1.0.0, NEMESIS showed no packages and "daemon
  not running" for about two minutes after every start: the boot hook's background
  NEMESIS sync (`(sleep 120; hmgapid engine nemesis-sync) &`) inherited `hmgapid sh`'s
  output pipe, so the boot script waited on that line, above NEMESIS's own start.
  `hmgapid` now hands a command only fds 0-2.

## 1.0.0 (2026-11-03)

HMG-API replaces HMG Leafia Plus 1.x. Leafia was one NEMESIS package for wireless
CarPlay and Android Auto; HMG-API is a mod manager. Any number of mods can be
installed together, none of them overwrites another, and removing one puts back
exactly what it changed.

**Tested on:** a US Kia EV6 260507 (2026-10-31, `docs/car-test.md`): the hook runtime,
mods, the app, buttons, the side panel, Android Auto turn-by-turn and projection events,
the MICOM recording, the Car Report, wireless CarPlay and Android Auto. HMG-API's
hook map verified on the US EV6 260507 (331 of 331 points); US Ioniq 5 260507 and Canadian
EV6 and Ioniq 5 260206 mapped from their stock files, to check on a car once. Not yet:
Ioniq 5 260903 (being checked), installs without adb, the conditioning checks below.
HMG-API runs on any Gen5W navigation head unit. A car's hook map (US Kia EV6 260507
today) turns on the parts that change the car's own apps; without one, or for points a
map lacks, only those parts stay off and the app says why.

### For owners

- **Install** from a USB stick (with or without NEMESIS), as a NEMESIS package, or
  over adb. Uninstalling puts every file and setting back to the stock bytes.
- **Mods as NEMESIS packages** (`<mod>-<version>-nemesis.zip`): install, update and
  uninstall a mod from NEMESIS Manager or a stick. HMG-API's engine does the work, with
  the same checks as the app; uninstalling puts the mod's files back to stock. Authors
  make one with `tools/mknemesismod.sh`.
- **The HMG-API app** (its own menu tile and ★ choice): a status bar (hooks on, changes
  not applied, restart needed) with *Apply changes* (a plan of what will change before
  anything does), *Check USB updates* and *Restart unit*; tabs for **Mods** (each mod's
  state in colour, switch, settings, log), **Settings** (vehicle, updates, phone
  projection, hook map, about), **Car Report** and **Logs** (HMG-API's own log, kept
  since the start).
- **Car Report:** checks how HMG-API fits the car and maps it from its own software on
  the spot; saved to a USB stick, with a QR code that opens a filled-in GitHub issue.
  Model-level facts only, logs scrubbed. Since beta 2 it also holds the head unit's
  network: the stock firewall (its service, script, rules and counters), routes,
  and the packets the firewall dropped (device addresses removed).
- **Asked first:** the first time a mod uses a car command, HMG-API asks you on screen
  (once per mod and command; giving back never asks).
- **Mods are signed by their author.** Updates must come from the same author key
  as the installed mod, so nobody can install over someone else's mod. A mod from
  an author not on HMG-API's trusted author list installs with a warning.
- **Nothing is hidden.** The plan lists, per mod, the apps it installs, the services
  it runs, the car readings and commands it uses, the buttons it binds, its side
  panel pages and its Android Auto settings. When two mods want the same thing (a
  button press, Android Auto's density), the plan says which one has it, and you can
  pick the other.
- **Wireless CarPlay and Android Auto** are now a mod, with the same features as
  Leafia 1.x (turn-by-turn in the cluster and HUD, the CarPlay Bluetooth bridge,
  extra audio formats), and no app or library file replaced: the changes are made in
  memory as the apps load.
- **OTA updates** stay blocked by default while HMG-API is installed (USB and dealer
  updates still work), so an update can't silently remove it.
- **Startup notice:** a switch in the app (off by default) lets a mod dismiss the
  notice shown at start. It says what it skips.
- **With a NEMESIS package that does the same** (since beta 3): files a package
  changed and then put back when it was uninstalled no longer stop HMG-API ("changed
  outside HMG-API"): a mod's line edits go on the file as found, and an exact file
  that is stock again is built on again; a file no mod wants any more is let go. A
  file a mod adds that a package already added names the package to uninstall
  first. NEMESIS packages' own backup names (`featurexml.<onebin>.cfg`, `perm.*`,
  `*.absent`) are recognized.
- **The NEMESIS download** (since beta 3) is laid out like NEMESIS's own packages:
  unzipped onto a stick, its `NEMESIS.data` (`type=install`) installs
  `packages/hmgapi.zip`. Before, it was the bare package, which NEMESIS's USB handler
  reports as invalid when it's unzipped onto a stick, and doesn't see as a zip copied
  onto one.
- **NEMESIS descriptor 1.1** (since beta 4): `version=1.1` (the descriptor's),
  `type`, `package-version` (dotted numbers: a beta of 1.0.0 is 0.99.<beta>),
  `short-desc`, `detailed-desc`, `os` (`any`: every Gen5W car), `models`, `depends`, as
  NEMESIS's Package Store takes them. `hmgapi-<v>-nemesis-package.zip` is the package
  itself, for the store and `nemesis install`.
- **Opening the app without a tile** (since beta 5): NEMESIS Manager's **Launch** for
  HMG-API (and `hmgapid sh 'sh /data/hmgapi/open.sh'` over adb) opens the app,
  installing it first from the copy HMG-API keeps if it's missing. The tile and ★
  choice come from the hooks, so on a car without a hook map this was the missing way
  in (and to its Car Report). An app install that fails with Android up is tried again
  at the next restart, with a message on screen, instead of only a line in the log.
- **Install and uninstall scripts** (since beta 4): the installer checks the hook maps
  with the rest of the payload, before anything changes (it used to find them
  missing after `/system` was already changed); NEMESIS's preuninstall only takes an
  update for one when an HMG-API package is staged and the command isn't an
  uninstall (a stage left by an interrupted install made an uninstall do nothing);
  the uninstaller starts hmgapid if it isn't running, since restoring the mods'
  changes goes through it.
- **Hook maps lost when unzipping** (since beta 4): the payload had a file `MAPS` next
  to the folder `maps`. On FAT/exFAT sticks, Windows and macOS those are the same name,
  so one was lost when the download was unzipped there, and HMG-API installed without
  its hook maps (hooks off) or stopped on the MANIFEST check. The file is now
  `MAPS.md5`; the installer stops with a clear message if the maps folder is missing;
  every download is checked for names that clash without case or that FAT can't hold.
- **The network keeper** leaves another tool's rules ahead of HMG-API's if they can't
  drop anything (accept, return, log, mark), instead of moving itself back to the top
  every 10 s.
- **Hook maps for four more cars** (since beta 6): US Hyundai Ioniq 5 260507 (all
  trims) and 260903 (2023 on), Canadian Kia EV6 260206 and Hyundai Ioniq 5 260206.
  Made from official update files and owners' Car Reports. Until a map is marked
  verified (`tools/mark-verified.py` records an on-car `engine verify-map` in its
  `car.json`), hooks on that car wait for the owner's *Check the map on this car*.
  The US EV6 260507 is verified; the others are mapped; 260903 isn't confirmed yet. In NEMESIS, HMG-API shows as fully compatible on every Gen5W car.
- **The NEMESIS download is a Package Store list** (`type=list`, NEMESIS 1.16 or
  newer): HMG-API and each mod's NEMESIS package, picked on screen. Mod packages need
  HMG-API 1.0 or newer (`depends="hmgapi@>=1.0"`), so NEMESIS installs HMG-API first.
- **HMG-API and NEMESIS show the same thing** (`engine nemesis-sync`): HMG-API and every
  mod installed by any route are listed in NEMESIS 1.16 or newer, and taken off when
  they're removed. HMG-API does every install and uninstall; the sync runs after each
  one, at boot and by hand, and fixes what doesn't match. Uninstalling a listed package
  in NEMESIS Manager uninstalls through HMG-API. HMG-API's NEMESIS uninstall no longer
  runs a second time after HMG-API was uninstalled another way.
- **Battery conditioning starts once:** the `conditioning` command's 5-second keep-alive
  resent the station event (the start) with the route data, so the car was asked to
  start again every 5 s; on a US EV6 its conditioning indicator stayed off. It now
  resends the route data only, as Launchpad does.
- **Battery conditioning checks the car** ([#68](https://github.com/Open-HMG/HMG-API/issues/68),
  Launchpad's checks): a start is refused when the car isn't READY, Battery Conditioning
  is off in the EV settings, or the car is already conditioning; a warm pack (EV6 and
  Ioniq 6 20 °C / 68 °F, Ioniq 5 21 °C / 70 °F) is refused unless the request ends with
  `warm`. If the car's indicator isn't on within 5 minutes (EV6) or 2 (others), the
  request is stopped and reported. New readings `hv.conditioning_active`,
  `hv.battery_temp_min`, `hv.conditioning_failure`, `conditioning.state`; 12 new hook
  points, mapped on every car with stock files. Not yet tested on a car.
- **Boot hooks kept after a NEMESIS update:** NEMESIS's install and its updates write
  `/system/etc/install-recovery.sh` as a whole new file, which dropped one of HMG-API's
  two boot hooks (the one in `qb_runtime.sh` still started it). At each start
  `hook-check.sh` puts a missing hook back and says so in `install.log`.
- **Incompatible NEMESIS packages** (`install/nemesis/conflicts.txt`, `!<id>` in
  `depends`): HMG-API with HMG Leafia Plus (`wireless.carplay.aa`), and with
  `carplay.session` and `voicehandler.vr-gates` until we know they don't clash; the wireless mod with
  `wireless.projection` and `projection.tbt`; the adb mod with `adb.tcp`. NEMESIS
  refuses to install one while the other is installed.
- **Install anyway** (since beta 6): a mod whose author didn't list your car or
  software can be installed at your own risk, with a warning first (`--any-car` over
  adb); every file it changes is still backed up and put back on removal.
- **"No Wi-Fi chip found"** (fixed in beta 6, #10): the chip is only powered once Wi-Fi
  or the hotspot has been on, so HMG-API now also recognises it from its driver and
  remembers it once seen.
- **Two mods with code** (fixed in beta 6) could stop the head unit from starting: both
  `hooks.jar` files shared one cache, and the boot guard then turned hooks off. Each
  mod has its own cache now.
- **The app's menu tile** is HMG-API's logo in the launcher's own style, day and night,
  with a greyed-out version.

### For mod authors

A mod is a folder with a `mod.json`; everything below is declared there, shown to the
owner in the plan, and undone when the mod is turned off or removed. Reference:
`docs/mod-reference.md`. An existing package can be offered as a mod as well, side by
side with it: `tools/mod-draft.py` drafts its `mod.json`.

- **Hooks:** Java methods in any app, by stable hook point names mapped per car
  (`core/hookpoints`, `maps/`), never by an app's obfuscated names. Native library
  patches in memory (`hooks.native`).
- **Your own APK** (`apps`): installed, turned off and removed with the mod.
- **Services:** scripts or `app_process` jars, run as root, system or your app's own
  user; restarted with backoff, logged, stopped when the mod is turned off.
- **The client library** (`hmgapi-client.jar`): your app and services ask HMG-API
  for their settings, services, logs, USB folders, readings and commands, without
  root.
- **Vehicle readings** (22, by name: gear, speed, steering angle, pedals, 12V and HV
  battery, conditioning and its indicator, pack temperature, charging, odometer, seat heat, guide lines), read through
  the car's own services and shared between mods.
- **Vehicle commands**, each reviewed and guarded: charge door (park only), seat
  heat, wheel heat, battery conditioning, ambient light (one mod at a time), parking
  guide lines, rear camera turns, startup notice (owner's switch).
- **Buttons** (`input`): 40 head unit, steering wheel and overhead buttons. A held
  button is a mod's shortcut (the car's hold gives way; a short press still reaches
  the car); a short press can be watched or taken; knobs report every click. Star
  keys: the short press stays the owner's pick in the car's menu, the hold a mod's.
- **Side panel pages:** a standard Android app widget from your app, in the
  right-hand split screen.
- **Phone projection:** Android Auto density, sensors (no tap limit, EV sensor
  types), fuel and connector types; events for Android Auto and CarPlay sessions,
  guidance and navigation messages.
- **Network** (since beta 2): firewall rules (filter, nat, mangle; any chain),
  routes, routing rules and `net.*` sysctls, declared in `mod.json` and kept in place
  by HMG-API's network keeper even when the stock firewall reloads. Rules go in
  HMG-API's own chains, checked before the car's; hotspot-only rules need nothing
  more, anything that can reach the car's own network needs the owner's
  `overrides.allow_network`. Found on the Ioniq 6, whose firewall allows the CarPlay
  ports only on the wired link.
- **Not while driving:** your app follows the car's own driving restriction and
  popup.
- **USB folders, tiles with day and night icons, the car's notices.** Tile pictures
  in the stock style from any PNG, JPEG or SVG: `tools/mkicon.sh`.
- **iAP2 (CarPlay) hook points** (since beta 6): the service starting and stopping, and
  the vehicle information and vehicle status switches, for mods such as EV routing.
  A mod's code file can now have any name, not only `hooks.jar`.
- **Shared files and settings:** feature flags, the Wi-Fi client, `media_codecs.xml`
  by codec name, list settings (enabled keyboards), and an install-time probe for
  decisions made on the unit.
- **MICOM watch** (owners and developers): records the frames the head unit gets
  from the car's MICOM, for mapping new readings with the community CAN projects
  (`docs/micom-map.md`). Listening only; identity never reaches the log.
- **Community mod catalogue:** one pull request (`tools/catalog.py add`) lists your mod
  on the wiki's Community mods page, linking your repository and your own signed
  release zip, and puts your author key on the trusted list. CI downloads the zip and
  checks its SHA-256 and signature; an entry's author key can't change.

### Compared with Leafia 1.x

- One mod per feature, installable together, instead of one package for all.
- No replaced app or library files: hooks and in-memory patches.
- Settings change without reinstalling; most changes apply without a restart.
- `stepofspec` is never changed.

# Changelog

## 1.1.0-rc.3 (2026-11-16)

The third release candidate: hook maps for many more cars (one map per system build), the
Wi-Fi hotspot for cars without one, and a mapper that can map a car with no map from its own
files. It comes with Wireless CarPlay + Android Auto 2.2.0-rc.4 and ADB 1.0.1-rc.1, and
NEMESIS in the app menu.

- **`check-points.py`: a floor that is the SDK's own version uses its own points.** A mod
  and the core release it needs can now be built before that release is tagged (wireless
  2.2.0-rc.4's `">=1.1.0-rc.3"` on the rc.3 SDK); before, it fetched the floor's points
  from a release that wasn't there yet.
- **`"wifi": {"hotspot": true}`: HMG-API turns on the Wi-Fi hotspot feature** for mods
  that need the Wi-Fi chip. Without the stock hotspot feature the unit never powers its
  chip, so it was never seen and HMG-API said "no Wi-Fi chip" (Korean units, and US cars
  such as the 2023 Santa Fe, #112, #170). Where the stock file is there, only its
  `featurexml.cfg` line is commented out; otherwise HMG-API's own file goes in, shared by
  every mod that asks, back to stock when none does. The older Hyundai and Kia builds and
  the Korean ones mapped in this release have no stock hotspot file.
- **A chip that isn't powered yet counts for the mod that powers it.** The driver,
  firmware and `wifi.module` feature without the stock hotspot are now
  `hmgapi.wifi.unpowered`; for a mod that asks for the hotspot, that meets `hmgapi.wifi`.
  HMG-API's own hotspot file doesn't count as the car's, so `hmgapi.wifi` still means a
  chip that showed on SDIO or a car whose stock software has the hotspot. The Car Report
  says which (`unit.wifi`).
- **The repo keeps one base map and each build's differences** (#236). The names a hook point
  resolves to hardly change between builds, so `maps/base/<app>.json` holds each point's
  entry on most builds, and a build's `car.json` lists only what's its own: `"names"` (0 to
  3 per build today), `"unconfirmed"` (methods mapped before code signatures) and
  `"missing"`. 326 map files become 40. `build.sh` expands them into the same per-app files
  as before (byte for byte), so the unit, the mod SDK and local maps read what they read;
  `map-car.py`, `report-map.py`, `check-maps.py`, `mkhookdoc.py` and `probe-test.py` read
  and write through `tools/maplib.py`.
- **A unit maps the points HMG-API's map lacks only because the map is older than them.**
  This happens after each HMG-API install or update (a boot step), or from Settings ("Map the
  points HMG-API's map lacks"). Only points found by name are mapped, in apps whose stock
  files are the ones the map was made from. Each is checked in the car's own apps before
  it's kept. They're kept in `/data/hmgapi/state/local-map.json`, which an update doesn't
  replace, and go in the Car Report for a maintainer to make official. Readings and mods'
  observe hooks use them. HMG-API's own hooks, calls, constructors and mods' change or
  replace hooks need the point in HMG-API's map, or "Try unchecked maps". An app whose
  stock files change, or a broken file, leaves HMG-API's map as it is. The owner can turn
  them off in Settings ("Use points mapped on this car"). Every point missing from a map
  today is a by-name one, so units can fill each of them.
- **"Map this car from its files" survives updates, and works on a car whose map doesn't
  fit.** `map-car --write` keeps the car's own map in `state/local-map.json`. It used to
  write into `maps/`, which every HMG-API update replaces. It's now also used when HMG-API's
  map for the car doesn't fit its framework (`mismatch`): before, `--write` refused there.
  With HMG-API's map fitting, `--write` now says to use `--local`; before, it overwrote a
  shipped `probed` map in place.
- Add a Canadian Ioniq 6 (CEEV) 260206 map with 351 hook points, from a Car Report
  and matching stock Bluetooth settings files. Its projection helper is `iO`, not
  the `iP` used by the other maps. All 351 points passed verification on the car.
- Recognize CEEV as Hyundai Ioniq 6. Report import accepts an explicit make/name for
  older reports and preserves their unresolved points and absent apps.
- The on-car mapper checks a reference class's members together, so an unrelated
  class reusing another build's name does not hide the correct projection helper.
- **The on-car mapper finds every hook point on a car with no map.** The 3 methods it left
  for a Car Report share their code with others (25 identical flag getters for the panel's
  test switch), so the code signature alone couldn't pick one. A catalogue `"site"` now
  says how the smali pattern calls each, in terms the dex still shows after odex
  quickening: the calling class and the instructions after an `invoke-static`, the method
  called next, or that the call is virtual (`core/hookpoints/README.md`). On every stock
  tree `engine map-car` now matches `map-car.py` on all 351 points, Korea included (the
  test switch is `pB` there, not the US `py`).
- **The on-car mapper takes another car's method name only for the same code.** For the
  methods only a smali pattern finds, it used to keep another map's name if this car had a
  method of that name and signature. On the Korean 260902 builds that picks the wrong
  method twice: `py()Z` is the test gate elsewhere but reads `ro.product.model` there, and
  `gl()I` is another field's getter; `verify-map` passes both. Maps now keep a code
  signature (`"sig"`, `tools/dexsig.py`) on those 9 methods, and `engine map-car` takes the
  one method whose code matches, whatever its name; when several share that code, the point
  is left for the car's own map. The maps made from stock trees gain `sig`; the runtime
  ignores it. The seven maps made on cars (Car Reports) have none yet: their smali-only
  methods were taken by name. `tools/check-maps.py` lists them as unconfirmed until a
  Car Report from a newer HMG-API confirms them (one per build: the Carnival, Sportage,
  Telluride and Niro EV maps are one build).
### Cars

- **Six more maps from Car Reports** (status probed: hooks run after `verify-map` passes on
  the car, or with "Try unchecked maps"):
  - `hyundai/ceev/usa/260903`: the Ioniq 6 on 260903, in the USA **and** Canada. The US
    (#208) and Canadian (#234) reports have the same files and points (347);
  - `kia/ev6/kor/260902` (#9, 319 points) and `kia/bdpe/kor/260902`, the K3 (#17, 323);
  - `genesis/ikfl/can/260903`: the Canadian G70 (#231, 278 points: three apps weren't in
    the report);
  - `hyundai/ceev/sgp/260907`: the Ioniq 6 in Singapore (#228, 306 points);
  - `hyundai/su2ifl/ind/260430`: the Creta in India (#191, 339 points).
  On the EV6 and K3 Korea and Singapore maps the panel's test switch is left out: there the
  borrowed `split_item.type` didn't fit either, as on the Korean builds read from stock
  files, where the borrowed `py` is another method. `SystemCheck` knows `SU2IFL` (Creta).
- **The newer Kia build in the US covers more cars.** The Sportage map
  (`kia/nq5/usa/260507`) now also lists the Sportage Hybrid and Plug-in Hybrid, Forte,
  2022 Carnival, Niro Hybrid and Plug-in Hybrid, Soul and 2024 Seltos: the US packages
  that install the same build as the Sportage, Telluride, Carnival and Niro EV maps (their
  Car Reports have the same files). Before, those cars had no map.
- **The older Hyundai and Kia builds in Canada (260206), from stock files** (351 of 351
  hook points each, status mapped: waiting for a check on a car):
  - `hyundai/hmc/can/260206`: Elantra, Sonata, Santa Fe, Kona, Ioniq, Venue, Veloster N
    and the 2020-22 Palisade, with their hybrids and EVs (25 products);
  - `kia/kmc/can/260206`: K5, Sorento, Stinger, Soul, the 2021 Seltos, the 2020-21 Niro
    and Telluride (23 products).
  Every `onebin` folder in these builds is a product: no Canadian package of another build
  has one there. The Kia build's names differ from its US build in the same two fields as
  the Canadian Ioniq 5's (`homesetting.edit_list.items`, `dipo.iap2_client.manager`), and
  the Hyundai build's projection helper is the EV6's `iP`, not its US build's `iO`. Neither
  build has the Wi-Fi hotspot feature file.
- **Four maps from Car Reports** (status probed: hooks run after `verify-map` passes on
  the car, or with "Try unchecked maps"):
  - `hyundai/hmc/kor/260902`: the Korean Sonata, Sonata Hybrid, Santa Fe and Palisade, 341
    points. Five reports from three of them (#89, #91, #160) have the same files and points;
  - `kia/sg2ev/can/260206`: the newer Kia build in Canada, 347 points, from a Niro EV's
    report (#205). Its products are the Canadian packages that install that build: Niro,
    Sportage, Carnival, Telluride, Forte, Soul and the 2024 Seltos. On the US build, the
    products its Car Reports show (`NQ5`, `ONPE`, `SG2EV`, `KA423`) are all among the codes
    that rule gives;
  - `genesis/ikfl/usa/260507` (#2, 325 points) and `genesis/ikfl/kor/260902` (#93, 341
    points): the G70. The points newer than each report are listed as missing.
  These reports predate #233, so their smali-only methods were borrowed by name;
  `check-maps.py` lists them as unconfirmed. On the two Korean maps the panel's test switch
  (`panel.test_gate.on`) is left out: its borrowed name `py` is another method on the
  Korean builds read from stock files (the switch is `pB` there). `SystemCheck` knows the new model codes.
- **The 2022 Ioniq 5 (`NEEV`) on US 260903 uses the 260903 map**, as the 2023-on
  (`NEEV23`) does. Its 18 mapped files are the same bytes, and its owner's check found all
  351 hook points (#192; also #72, #186). Before, it had no map there: hooks were off
  unless the owner made one on the car.
- **Four maps from stock files, for the older Hyundai and Kia builds and Korea** (351 of
  351 hook points each, status mapped: waiting for a check on a car). One map per build,
  so each covers every model on it:
  - `hyundai/hmc/usa/260507`: Elantra, Sonata, Santa Fe, Kona, Ioniq, Venue, Veloster N
    and the 2020-22 Palisade, with their hybrids and EVs (25 products);
  - `kia/kmc/usa/260507`: K5, Sorento, Stinger, Soul, the 2021 Seltos, the 2020-22 Niro,
    Cadenza and the 2020-21 Telluride (24 products);
  - `kia/kmc/kor/260902`: the Korean K5, Sorento, Stinger, Seltos, Soul, Niro, Mohave,
    K7, Morning and Ray (33 products). The K5 Hybrid owner's files from a Car Report are
    the same bytes;
  - `hyundai/neev/kor/260902`: the Korean Ioniq 5.
  A map lists the cars whose `onebin` folder is in that build, less those known to
  install another build (the Korean 2021 Mohave and the Carnival). For a car on another
  build, the stock-file md5s give `mismatch`, never another build's names.
  `SystemCheck` knows the new model codes. None of the four builds has the Wi-Fi hotspot
  feature file, so the wireless mods can't use the hotspot there yet.
### Fixes

- **The USB install puts the HMG-API app in right away** (#187). With Android up, the
  package manager couldn't read the app where the USB installer had copied it (root only),
  so it refused it (`INSTALL_FAILED_INVALID_URI`) and the head unit came back without the
  app. It now installs from the copy in `/data/hmgapi/public/`, which it can read, as the
  retry at the next restart already did.
- **A mod's NEMESIS package depends on the mods it requires.** `tools/mknemesismod.sh`
  (and the mod SDK's copy) wrote only `hmgapi@>=1.0` and the refusals, so NEMESIS installed
  a mod without the mods in its `"requires"`; HMG-API's install only noted the missing mod.
  EGMP Android Auto EV, which requires `wireless-carplay-aa >=2.1.0`, got
  `depends="hmgapi@>=1.0,!hmgapi.egmp-aa-ev-standalone"`. Each required mod is now
  `hmgapi.<id>@>=<lowest version its range allows>`, a pre-release floor as its NEMESIS
  package-version (`^2.2.0-rc.1` is `>=2.1.99.1`); an optional one (`"optional": true`)
  stays out. NEMESIS checks only that floor; the
  rest of a range (`^`, `~`, upper bounds) stays HMG-API's check, and the build says so.
  The build refuses a `"requires"` entry that isn't a mod id, and a mod that requires a mod
  whose package it refuses. Since NEMESIS won't unlist a package another one needs, the
  NEMESIS sync and HMG-API's uninstall now unlist mod packages in rounds, so a required
  mod's comes off after the one needing it instead of staying listed (and keeping
  HMG-API's own package listed with it).
- **A mod's `"conflicts"` hold for mods that only hook.** The list was checked only for
  mods with files or settings to apply (`patches.json` and the like), so two hook-only
  mods that said they can't run together both loaded, and `engine plan` said nothing
  (two builds of egmp-aa-ev, with and without the Wireless mod, list each other). The mod
  loader and the plan now load the first of the two in load order (mods they require
  first, then by id), whichever of them lists the other; the other is shown as a
  conflict in the HMG-API app, with which mod to turn off to use it instead. A mod turned
  on while apps run doesn't load next to one it conflicts with that those apps loaded
  (even one turned off since); the next restart settles it.
- **A mod whose `"requires"` aren't met isn't loaded.** One whose required mod was off
  or missing still loaded at start (its code ran, then it was switched off), and one
  outside the version range it asked for ran; the HMG-API app showed it as on. It now
  isn't loaded, and the app shows why. This also keeps such a mod from winning a
  conflict it then can't use.

## 1.1.0-rc.2 (2026-11-15)

The second release candidate: fixes from a review of rc.1, nothing new in HMG-API itself.

- **Wireless CarPlay + Android Auto 2.2.0-rc.3** comes with it: wireless CarPlay no longer
  drops after about a minute on some phones, because the phone's Bluetooth link stays up
  during the session (wireless #5, #2), and the US Ioniq 5 (2023 on) on 260903 is in its
  car list. It needs HMG-API 1.1.0. ADB 1.0.1-rc.1 and NEMESIS
  in the app menu 1.1.0 are as in rc.1.

### Fixes

- **The Car Report's network keeper log is scrubbed like the rest.** Its last 40 lines
  went into the report as they were, so an address in one (a mod's route, a phone on the
  hotspot) could end up in a public issue. MAC addresses, IPv6 interface ids and public
  IPv4 addresses now come out of them, as they do from the interfaces, routes and logged
  drops.
- **Uninstalling gives the boot scripts their stock bytes back.** HMG-API's block was
  taken out of `install-recovery.sh` and `qb_runtime.sh` by writing every other line
  again through the shell, which is only close to stock: a last line with no newline
  gained one, and a shell's `echo` may expand a backslash (the EV6's `qb_runtime.sh` has
  two). The uninstall now puts the install's backup back byte for byte when nothing else
  changed the file since; when something did (a NEMESIS install or update rewrites
  `install-recovery.sh`), only HMG-API's block comes out, as before.
- **No restart at every start after a rollback that failed part-way.** At boot, an apply
  that was cut off is rolled back and the head unit restarts once. The restart went by
  the words "rolled back" in the log, which a rollback that then fails (no space left,
  say) prints too while keeping its journal, so the unit would have restarted at each
  start. It now restarts only when the rollback finished; one that didn't is tried again
  at the next start, and `boot.log` says so.
- **A mod's native patch recipes are read from its own folder only.** A recipe path that
  led out of the mod's folder (`../`, an absolute path, a symlink) made that file readable
  by every app: the owner's settings, another mod's private files. Such paths are left
  alone now, as they already were for a mod's code JAR.
- **hmgapid never sets up a service's folders through a symlink.** A service that doesn't
  run as root owns its folder, and a link put where its `dalvik-cache` goes had hmgapid
  hand it whatever the link pointed at, at the service's next start.
- **`mkmodpkg.sh` refuses a mod folder with a signing key in it.** Everything in the
  folder goes into the signed package, which is public: a key file (`.key`, `.p12`,
  `.pem`, `.jks`, a PEM private key, or a copy of the author key under any name) now
  stops the packaging, with the files named.
- **The app says when over-the-air updates aren't blocked.** The block is one of HMG-API's
  hooks, so on a car where the hooks don't run (no hook map that fits it, or the boot
  guard turned them off) the car can still download and install its own updates, while
  the Updates page said HMG-API blocks them. The page now says they aren't blocked there
  and points at the car's own switch (Settings > General > Install Infotainment System
  Updates Automatically). Nothing else changes: HMG-API doesn't touch that switch without
  its hooks.
- **Removing or updating a mod never deletes what a symlink in its folder points at.** The
  engine's delete went into a linked folder (one a mod's script made, to a USB stick or
  another place under `/data`) and emptied it. A link is now removed as the link it is.
- **`guide_lines off` waits for the owner's OK like `on`.** A mod's first use of a car
  command waits for the owner, except when it gives back what it took (release, stop,
  restore, off). For the rear camera's guide lines, `off` isn't giving back: it turns
  the owner's lines off, and `restore` is the undo. It ran without asking.
- **Cars mapped from Car Reports have their make and name.** The Sportage, Telluride,
  Niro EV, Carnival, Palisade and the Ioniq 5's `NEEV23SE` had a hook map but no line in
  the engine's model table, so a mod whose `"system"` names a make or a name refused
  them, and their Car Reports were titled "Car Report: null …". A car HMG-API has no name
  for yet now goes by its model code there.

### For mod authors

- **`check-points.py` knows the first release with an SDK is 1.1.0-beta.1.** It took the
  name this release had while it was planned (1.0.3), so a floor between the two ended
  in a traceback; a floor that isn't a release now fails in one line. The docs say
  "from 1.1.0" where they said "from 1.0.3".

## 1.1.0-rc.1 (2026-11-13)

The release candidate: feature freeze. 1.1.0 will be this code unless a blocker turns up.

- **Wireless CarPlay + Android Auto 2.2.0-rc.1** and **ADB 1.0.1-rc.1** come with it
  (NEMESIS in the app menu 1.1.0 as before). Wireless: Android Auto roundabouts the right
  way round (wireless #19, #20), "Recalculating…" on a reroute (wireless #21), CarPlay
  survives a short Wi-Fi stall (wireless #25, #26), Android Auto's maneuvers in the log
  (wireless #22, #27). ADB: port 5555 opened on the hotspot for adb over it, on cars whose
  stock firewall drops it (adb #3).

### Cars

- **Hook maps from Car Reports** (made on owners' cars by HMG-API's own mapper, not yet
  checked on a car): Kia Sportage, Telluride, Niro EV and Carnival (US 260507), Hyundai
  Palisade (US 260903). The Niro EV, Carnival and Palisade reports came from an HMG-API
  older than 1.1.0, so the 8 newest hook points (four for wireless CarPlay, four for the
  12 V battery) are listed missing until a newer Car Report.
- **`tools/report-map.py`:** `--make` and `--name` for a report from a car HMG-API had no
  name for, and catalogue points newer than the report are listed missing (with why).

### Fixes

- **The right split-screen panel keeps the Mobis font with hooks on** (#174). HMG-API
  made its panel page's views as soon as the panel app started, even with no mod page;
  that loaded the process's fonts before the Mobis font's fallback was in place, so
  Korean text in the whole panel showed in NanumGothic (seen on an Avante CN7 KOR
  260902). The views are now made only when the panel first shows HMG-API's page.
- **Vehicle readings answer at once, with code 0** (#145). Every `hmgapid vehicle` read
  took 3 s and came back with code 1, the values right: on these units an accepted
  `LocalSocket`'s `close()` doesn't close its fd, so the vehicle service never ended its
  answer, and it leaked an fd per read, command and light status. It now closes the fd
  itself, and hmgapid takes a whole answer as success even if the connection stays open.
- **After a firmware update, installing HMG-API again works, and so does uninstalling it**
  (#166). An update puts the system files back to stock and removes HMG-API's own, but
  `/data/hmgapi` stays. The uninstall failed (NEMESIS: "Operation failed"), and could have
  put the old firmware's `app_process` back. Now the install finds the update, drops its
  backups of the old firmware (`engine firmware-reset`) and starts from the new firmware's
  files; the uninstall leaves those as they are. The app says what happened instead of
  "Can't reach hmgapid". Units on 1.0.2 or 1.1.0-beta.1 get out of it by installing this
  release over the old one.

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

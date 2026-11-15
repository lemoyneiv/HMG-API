# Security

## Reporting a problem

Please report security problems **privately**: on GitHub, the repository's
**Security** tab → **Report a vulnerability**. If that isn't available, email
openhmg@gmail.com and ask for a private channel before sharing details. Please don't
open a public issue for a security problem.

Supported: the newest release (including betas). Fixes go into the next one.

## What HMG-API changes, and who can do what

- **hmgapid runs as root** and offers a fixed set of operations, chosen by the
  caller's uid (`SO_PEERCRED`): root and shell (adb) get everything; the HMG-API app
  gets HMG-API's own operations (mods on and off, settings, installs, the Car Report,
  the owner's switches); a mod's own app or service gets its own mod's settings,
  services, log, USB folder and the vehicle readings and commands its `mod.json`
  declares; any other app only `ping` and `version`.
- **adb is root on these units.** The head unit's stock adb checks no keys: anyone
  with a USB cable has a shell, and so can ask hmgapid for root. With adb over the
  network on, the same goes for anyone on the car's hotspot. HMG-API's `adb` mod keeps
  network adb off by default; the `adbd` mod puts back Android's own adbd, which asks
  before a new computer gets in.
- **A mod is code that runs as root.** Installing one is trusting its author:
  - packages are signed by their author; an update must come from the same key as the
    installed mod, so nobody can install over someone else's mod;
  - a mod whose author key isn't on HMG-API's trusted author list (`maps/authors.json`)
    installs with a warning; an unsigned package is refused unless the owner sets
    `overrides.allow_unsigned` and forces it;
  - the plan shown before anything changes lists every app, service, car reading,
    car command, button and file a mod uses;
  - a mod's first use of each car command waits for the owner's OK on screen, and
    vehicle commands only go through the car's own services, each with its own limits
    (no CAN frames of our own);
  - network rules a mod declares go in HMG-API's own chains, built from checked fields
    (no free-form iptables text); only rules on the Wi-Fi hotspot go in without the
    owner's `overrides.allow_network`, since anything else can reach the car's own
    network (the modem, the internal link);
  - HMG-API warns at install when a mod's scripts call commands the head unit lacks.
- **Files:** everything under `/data/hmgapi` is root-only, except
  `/data/hmgapi/public` (HMG-API's published state and Car Reports, readable by apps).
  Anything HMG-API or a mod writes to `/system` keeps a stock checksum check, a backup,
  and an uninstall that restores the exact stock bytes.
- **Car Reports** hold model-level facts only (model, region, software version, hook
  point names, mods). Never a VIN, serials, phone numbers, IMEI, account details or
  the owner's data; log lines are scrubbed before they go in.
- **Updates:** HMG-API releases are signed with HMG-API's release key and checked
  before they install. The car's own over-the-air updates are blocked unless the owner
  turns them on, while HMG-API's hooks run: on a car where they don't (no hook map that
  fits it, or the boot guard turned them off), nothing is blocked, and the app's Updates
  page says so.

## In this repository

No manufacturer apps or libraries (patch sets, checksums and names only), no signing
keys (release builds sign in GitHub Actions with keys held as encrypted repository
secrets, never available to pull requests), no per-car data.

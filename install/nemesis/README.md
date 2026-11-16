# HMG-API as a NEMESIS package

`tools/mkinstallers.sh` builds the package, `hmgapi.zip`: this folder's
`NEMESIS.data` (from `NEMESIS.data.in`) and `scripts/`, plus HMG-API's payload in
`payload/`. NEMESIS's USB handler only reads a `NEMESIS.data` at a stick's root, with
a `type=` and a relative `path=` (`native/usb.c`). The download
`hmgapi-<version>-nemesis.zip` is a NEMESIS 1.16 Package Store list (`type=list`,
04-Packages.md §5.3, `StoreList.parseList`: `[package.0]`, `[package.1]`, … with no
gaps): `packages/hmgapi.zip` first, then each released mod's NEMESIS package as
`packages/hmgapi.<id>.zip`, and `README.txt` (for owners), unzipped onto a stick. The
USB download carries the same package as `HMGAPI/install/hmgapi.zip`, with
`type=install` (it also has to work without NEMESIS 1.16). From
a shell: `nemesis install [--update] <hmgapi.zip>`. `install/usb/test.sh` checks both
layouts against those rules.

- install / update: `postinstall` runs `payload/install.sh`, which works out
  whether it's a first install or an update.
- boot: a `[service.0]` (`name=boot`, `kind=sh`, `mode=background`, `bin/start.sh`).
  NEMESIS's svcmgr starts it at boot (`once` services it skips) and after an install;
  it runs `hmgapid boot`, as the boot hook does. Only the first of the two starts
  HMG-API (`core/hmgapid/README.md`), so HMG-API starts with NEMESIS even after a
  NEMESIS install or update rewrote `install-recovery.sh` without HMG-API's block.
- uninstall: `preuninstall` runs `/data/hmgapi/uninstall.sh` (every mod change and
  HMG-API's own files back to stock; `/data/hmgapi` is kept).
- NEMESIS updates a package by uninstalling the old one first. `preuninstall`
  sees the new package staged and does nothing, so an update keeps HMG-API's mods.
- Installed with Android down (a stick with `NEMESIS.emergency`; NEMESIS otherwise
  waits for Android), the HMG-API app goes in by itself once Android is up.

## NEMESIS lists what HMG-API installed (engine nemesis-sync)

HMG-API does every install and uninstall; NEMESIS (1.16 or newer) shows the same
thing in My Packages. `hmgapid engine nemesis-sync` (`NemesisSync.java`) compares the
two and fixes what differs: HMG-API or a mod installed but not listed (or at another
version) is listed, a `hmgapi.<id>` whose mod isn't installed is unlisted. It runs
after `engine install` and `engine remove`, at the end of `install.sh`, two minutes
after each boot (`boot.sh`), and by hand; `uninstall.sh` unlists the mods and then
HMG-API itself (the engine is gone by then). A failed listing never fails an install:
it says why, and the next sync tries again.

- A package the sync lists is a small one: `NEMESIS.data` (`os="any"`, `depends` as
  the full packages), these scripts, `bin/open.sh` (and for HMG-API, `bin/start.sh` with its boot service), a `registered` marker, no payload
  or mod zip. The scripts and `conflicts.txt` are in the payload (`nemesis/`) and kept
  in `/data/hmgapi/nemesis`.
- The sync runs NEMESIS with `HMGAPI_NEMESIS_SYNC=1`: every script here then does
  nothing (`nemesis` as root runs locally and passes the environment to the hooks), so
  listing and unlisting never install or remove. Before NEMESIS runs an installed
  package's scripts for that, the sync puts the current scripts over the package's
  own copies (an older one would uninstall instead).
- Uninstalling a listed package in NEMESIS Manager runs the scripts without it, so
  HMG-API uninstalls (or removes the mod and applies) as usual. Our scripts pass
  `--from-nemesis` to `engine install`/`remove` and set `HMGAPI_FROM_NEMESIS=1` for
  `install.sh`/`uninstall.sh`, so nothing calls NEMESIS from inside NEMESIS (it has no
  lock of its own; the sync takes `state/nemesis-sync.lock`).
- HMG-API's own `preuninstall` uninstalls only if HMG-API is installed
  (`installed.txt`): `uninstall.sh` stays in `/data/hmgapi` after an uninstall.

`sync-test.sh` runs it all off-car with the real engine and a stand-in NEMESIS;
`install/common/install-test.sh` checks the unlisting in `uninstall.sh`.

## Mods as NEMESIS packages

`tools/mknemesismod.sh <signed mod zip>` wraps a mod (tools/mkmodpkg.sh's zip, as it
is, in `mod/package.zip`) in a NEMESIS package from the template in `mod/`:
`<id>-<v>-nemesis.zip` (stick) and `<id>-<v>-nemesis-package.zip` (the package). The
release builds one for each mod in `tools/release-mods.txt` (fetched from its catalogue entry). `mod-test.sh` runs it all
off-car with the real engine.

- id `hmgapi.<mod id>` (mod ids are at most 57 characters for this),
  `depends="hmgapi@>=1.0"`, then the mods it requires (next item), then its
  incompatibilities (below; NEMESIS then also refuses to uninstall HMG-API while a mod
  package is installed),
  `os` from the mod's `"system"` (a firmware range like `>=260206` becomes `*`),
  `package-version` from the mod's version (`tools/nemesis-version.py`).
- **Required mods:** each `mod.json` `"requires"` entry becomes `hmgapi.<id>@>=<floor>`,
  the floor being the lowest version its range lets in, as a package-version:
  `"wireless-carplay-aa >=2.1.0"` is `hmgapi.wireless-carplay-aa@>=2.1.0`, `"base
  ^2.2.0-rc.1"` is `hmgapi.base@>=2.1.99.1`, and a range with no lower bound (none, `*`,
  `<3`) is plain `hmgapi.<id>`. An optional entry (`{"id": ..., "optional": true}`: the mod
  runs without it) is left out. NEMESIS then won't install the mod until those are
  installed and new enough (exit 22), and won't uninstall them while it's there (exit 4).
  NEMESIS checks only the floor. The rest of a range (`^`, `~`, an exact version, `>`
  taken as `>=`, an upper bound) stays HMG-API's check, as for a mod installed from the
  HMG-API app. The build prints a note when it leaves part of a range out. An upper bound in NEMESIS would also block every update of
  the required mod's package past it (exit 21). The build refuses an entry that isn't a
  mod id, a mod that requires itself, a range with no package-version
  (`tools/nemesis-version.py`'s forms), a mod that requires a mod and refuses its
  package, and a `depends` over NEMESIS's 511 characters. HMG-API's NEMESIS sync
  (NEMESIS 1.16 or newer) lists mods installed from the HMG-API app as `hmgapi.<id>` too,
  so a required mod counts however it was installed. Unlisting goes round until nothing
  more comes off (the sync, and HMG-API's uninstall before `hmgapi` itself), since NEMESIS
  won't unlist a package another one needs; a required mod removed in the HMG-API app
  stays listed in NEMESIS, and the sync says why, until the mod needing it is removed.
- `preinstall`: refuses before NEMESIS copies anything if HMG-API isn't installed
  (`/system/bin/hmgapid` and `/data/hmgapi/uninstall.sh`).
- `postinstall`: `hmgapid engine install mod/package.zip` (plus `--any-car` with
  `HMGAPI/ANY-CAR` on a stick), then `engine apply`. If the apply fails on a first
  install, `engine remove` again; an update stays in HMG-API (the app shows the
  problem). Either failure exits 1; NEMESIS keeps the package registered, so it's
  uninstalled as usual.
- `preuninstall`: skipped on a NEMESIS update (the same stage rule as HMG-API's own).
  Otherwise `engine remove`, then `engine apply`. The mod already gone from HMG-API:
  nothing to do. HMG-API gone (its uninstall already restored `/system`): drop
  `/data/hmgapi/mods/<id>` so a reinstall doesn't bring it back. A failed remove keeps
  the package (exit 1); a failed apply after it only warns (the mod is out of HMG-API
  either way; Apply changes in the app finishes it).
- Launch (`[app.0]`, `bin/open.sh`): opens the HMG-API app, as HMG-API's package does.

**Descriptor (1.1, 04-Packages.md §2; 07-Submit-a-Package.md for the Package Store).**
`version=1.1` is the descriptor format, not HMG-API's version. `package-version` is
HMG-API's version as dotted numbers only (NEMESIS refuses anything else); a beta
comes before its release, so it's the version before it plus `.99.<beta>`
(`1.0.0-beta.4` is `0.99.4`, below `1.0.0`; `tools/mkinstallers.sh`, which stops on a
version form it has no rule for). `os="any"` and `models="any"`: HMG-API runs on every
Gen5W navigation unit (on a car without a hook map only the hook parts stay off, and
the app says why), so NEMESIS shows it as fully compatible everywhere. A mod's
package keeps the `os` from its `mod.json` `"system"`.

**Incompatibilities** (`conflicts.txt`): NEMESIS packages that change files or
settings HMG-API or a mod changes too go in `depends` as `!<id>`
(`tools/nemesis-meta.py depends`). NEMESIS refuses to install either one while the
other is installed (exit 23), so the owner uninstalls one first. HMG-API's own: HMG
Leafia Plus (`wireless.carplay.aa`), and `carplay.session` and
`voicehandler.vr-gates`, assumed to clash until someone checks what they change; the
wireless mod's: `wireless.projection`,
`projection.tbt`; the adb mod's: `adb.tcp`. Mods installed from the HMG-API app
instead of NEMESIS aren't covered (the engine's own stock checks are). `short-desc` at most 64 characters, `detailed-desc` at most 256.
`install/usb/test.sh` checks all of it.

**For the Package Store** (portal.xwtk.cloud/nemesis): upload
`hmgapi-<version>-nemesis-package.zip`, the package itself (the same bytes as
`packages/hmgapi.zip` in the USB stick download). An update needs a higher
`package-version`.

The package's one `[app.N]` is `kind=sh` (`bin/open.sh`), with no `package` or
`component`: Manager's **Launch** opens the HMG-API app (installing it first from
`/data/hmgapi/HmgApi.apk` if it's missing; `install/common/app-open.sh`, installed as
`/data/hmgapi/open.sh`), and NEMESIS never runs `pm uninstall` on the app (it would on
an update, for an entry naming the app's package). The app stays HMG-API's to install
and remove. Launch needs no hooks, so it's also the way into the app on a car without
a hook map, where the launcher tile and the star key choice aren't added.

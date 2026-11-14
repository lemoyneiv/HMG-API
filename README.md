<img src="docs/img/hmg-api.png" width="120" align="right" alt="HMG-API logo">

# HMG-API

[![Support HMG-API](https://img.shields.io/badge/Support_HMG--API-%E2%9D%A4-bf3989?style=for-the-badge&logo=githubsponsors&logoColor=white)](https://github.com/Open-HMG/HMG-API/wiki/Support)

> **HMG-API is free**, made by volunteers. If it saved you a dealer visit or made your drives nicer, please consider [supporting the people who build it](https://github.com/Open-HMG/HMG-API/wiki/Support): each maintainer has their own links there. It keeps the testing and new-car ports going.

A mod manager for Hyundai, Kia and Genesis **Gen5W** navigation head units.

Mods add features to the head unit (wireless CarPlay and Android Auto with
turn-by-turn on the cluster and HUD, buttons that do more, side panel pages, and
more) without patching its apps. HMG-API hooks the car's own software while it runs,
gives every mod a switch, settings and a log, and puts back exactly what a mod
changed when it's removed.

![The HMG-API app on a Kia EV6](docs/img/screenshots/mods.png)

| Settings | Car Report |
|---|---|
| ![Settings](docs/img/screenshots/settings.png) | ![Car Report](docs/img/screenshots/report.png) |

## Install

Download the zip for your route from [Releases](https://github.com/Open-HMG/HMG-API/releases):
a **USB stick** (no computer), **NEMESIS**, or **adb**. Step by step:
[Installing HMG-API](https://github.com/Open-HMG/HMG-API/wiki/Installing-HMG-API).

HMG-API runs on any Gen5W navigation head unit. Cars with a hook map get everything;
on others, mods still install and run, and the app's **Car Report** sends what's
needed to add your car ([Supported cars](https://github.com/Open-HMG/HMG-API/wiki/Supported-cars)).

After a firmware update, install HMG-API again: the update removes its system files,
and the install starts again from the new firmware's
([Troubleshooting](https://github.com/Open-HMG/HMG-API/wiki/Troubleshooting#after-a-firmware-update-hmg-apis-system-files-are-gone-or-uninstall-fails)).

| Car | Region | Software | Hook map |
|---|---|---|---|
| Kia EV6 | USA | 260507 | checked on a car |
| Kia EV6, Hyundai Ioniq 5 | Canada | 260206 | waiting for a Car Report |
| Hyundai Ioniq 5 | USA | 260507 | waiting for a Car Report |
| Hyundai Ioniq 5 (2023 on) | USA | 260903 | waiting for a check on a car |
| Kia Sportage, Telluride, Niro EV, Carnival | USA | 260507 | from Car Reports, waiting for a check on a car |
| Hyundai Palisade | USA | 260903 | from a Car Report, waiting for a check on a car |

## Help and requests

[Open an issue](https://github.com/Open-HMG/HMG-API/issues/new/choose): something
doesn't work, car or firmware support (the Car Report's QR code fills it in), a new
hook point, or an idea. Guides are in the [wiki](https://github.com/Open-HMG/HMG-API/wiki).

> **Status: 1.0.2; [1.1.0-beta.1](https://github.com/Open-HMG/HMG-API/releases/tag/v1.1.0-beta.1) is out for testing.** Tested on a US Kia EV6 (260507): the hook runtime,
> mods, the app, buttons, the side panel, Android Auto turn-by-turn and projection
> events, the Car Report. CarPlay on HMG-API, the installers on a car without adb, and
> other cars aren't tested yet. Leafia 1.x (HMG Leafia Plus) is the previous project.

---

## For developers

### Parts

| Folder | What |
|---|---|
| `core/hmgapid` | root daemon and client (static ARM, no libc) |
| `core/zygote` | `app_process` wrapper: preloads the hook runtime into the zygote, boot guard |
| `core/loader` | `libhmgapi_hook.so`: Dalvik method hooks, loads the runtime jar |
| `core/runtime` | `hmgapi-hook.jar`: hook API, mod loader, launcher and ★-key extension points |
| `core/app` | the HMG-API app (`hmgapi.app`) |
| `core/client` | `hmgapi-client.jar`: for mods' own apps and services (settings, services, USB folders, notices) |
| `install/` | installers: `adb/`, `nemesis/`, `usb/` (DSP OTA), shared `common/` |
| `core/hookpoints`, `maps/` | the hook point catalogue (stable names; every point and the cars that have it: [docs/hook-points.md](docs/hook-points.md)) and each car's map (`maps/coverage.json`: which points every car has) |
| `mods/examples` | API examples and test fixtures: `hello`, `hello-service`, `side-panel`, `car-check` and more. The mods HMG-API ships live in their own repos: [Wireless CarPlay + Android Auto](https://github.com/Open-HMG/hmgapi-wireless-cp-aa), [ADB](https://github.com/Open-HMG/hmgapi-adb), [adbd](https://github.com/Open-HMG/hmgapi-adbd), [NEMESIS in the app menu](https://github.com/Open-HMG/hmgapi-nemesis-shortcut) ([docs/mod-repos.md](docs/mod-repos.md)) |
| `docs/` | `mod-reference.md` (the `mod.json` reference; `tools/mod-draft.py` drafts one from an existing package), `api-plan.md` (what each community project uses), `vehicle-commands.md`, `micom-map.md`, `car-test.md` (the on-car test session) |
| `wiki/` | the owners' wiki (GitHub wiki pages: installing, mods, the app, troubleshooting) |
| `tools/` | `lpatch`, patch makers, build settings |

### Installers

`tools/mkinstallers.sh` makes three downloads in `out/dist/`, all carrying the same payload and ending in `install/common/install.sh`:

| Download | For |
|---|---|
| `hmgapi-<v>-usb.zip` | a USB stick. Nothing installed yet: Engineering Mode > USB Copy > DSP > Update OTA. NEMESIS installed: its USB install. HMG-API installed: the app's updates (`HMGAPI/updates`, with the mods). An empty `HMGAPI/UNINSTALL` file uninstalls through the DSP route. |
| `hmgapi-<v>-nemesis.zip` | for NEMESIS: unzipped onto a stick, like NEMESIS's own packages (`NEMESIS.data` installs `packages/hmgapi.zip`; an update keeps HMG-API and its mods) |
| `hmgapi-<v>-nemesis-package.zip` | the NEMESIS package on its own, for NEMESIS's Package Store and `nemesis install` |
| `hmgapi-<v>-adb.zip` | `hmgapi-adb.sh install \| uninstall \| status` over adb (first install: root from NEMESIS) |

Uninstalling puts every file and setting HMG-API or a mod changed back to the stock bytes.

### Build and test

```
./build.sh
```

Everything goes to `out/`. Needs clang + lld, a JDK and the Android SDK (build-tools, a platform `android.jar`). No manufacturer files: the loader links against stand-ins for the head unit's libraries (`core/loader/syslib-stubs.sh`, the same bytes as linking the unit's own). Without HMG-API's signing key (`HMGAPI_KEY`) the app is signed with a throwaway key.

GitHub Actions: `ci.yml` builds and runs the off-car tests a push or pull request touches (`tools/test-select.py`; every one weekly), and the fast tier on macOS; `release.yml` turns a version tag (`v1.0.0-beta.1`) into a signed draft release (keys in the `release` environment; the bundled mods, by catalogue id in `tools/release-mods.txt`, come from their catalogue entries' downloads, SHA-256 and signature checked); `wiki.yml` publishes `wiki/` to the GitHub wiki.

```
tools/test-all.sh
```

Runs the off-car tests, several at once (`-j N`; ARM code under qemu-arm).
`tools/test-all.sh --fast` runs the ones that need no build and no ARM toolchain,
on Linux or macOS. With stock car files in `~/hmg-cars` (private,
[docs/car-trees.md](docs/car-trees.md)), `tools/cars.py test` runs every test that needs
them on every car you have, and `--report` gives the results to paste in a PR.

## Licence

AGPL-3.0 (see `LICENSE` and `NOTICE.md`).

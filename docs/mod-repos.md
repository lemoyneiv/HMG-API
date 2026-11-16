# Mods in their own repositories

The mods HMG-API ships (`tools/release-mods.txt`: `wireless-carplay-aa`, `adb`,
`nemesis-shortcut`) and `adbd` live in their own repositories in the Open-HMG org, one
repo per mod (section 1). The other `mods/examples` stay in core as API examples and
test fixtures. Hook points and maps stay in core. Sections 2 to 11 are the plan the split
followed.

**The release this page calls 1.0.3 came out as 1.1.0** (it adds features: `docs/releasing.md`).
The mod SDK has been a release asset since 1.1.0-beta.1, so wherever this page says "from
1.0.3", read "from 1.1.0", and a mod's `"hmgapi"` floor is never `">=1.0.3"`: there is no
such release (`">=1.1.0"`). "Before 1.0.3" below is how it was done until then.

## Decided (2026-11-07)

| # | Question | Decision |
|---|---|---|
| A | `nemesis-shortcut`, and repos shared by mods | Every mod gets its own repo, `nemesis-shortcut` included; `adb` and `adbd` don't share. Core then signs no mod and holds no mod key. |
| B | Signing key for the moved mods | Same author key as now (`7251ce71-…`), as `MOD_KEY` in each mod repo's `release` environment. A new key would strand every installed car (5). |
| C | Who updates `catalog/` on a mod release | A maintainer, by PR (`tools/catalog.py add`, the command printed by the mod's release run). |
| D | Korean cars (#22, #108) | Not confirmed for now: no Korean car in the wireless mod's `system` list. #22's and #108's car entries wait (10). |
| E | New regions | On hold: USA and Canada only for now. #22 waits whole, its map included; new firmware for a supported region is fine. |
| F | Releases | None, core or mod, until the split and the cleanup after it are done; then 1.0.3 is the first release through this setup. Until then, see "Before 1.0.3". |

## Before 1.0.3 (decision F)

No release carries an SDK until 1.0.3, so until then:

| | Before 1.0.3 | From 1.0.3 |
|---|---|---|
| SDK pin (`hmgapi-sdk.json`) | `{"ref": "<HMG-API commit SHA>"}`: the workflow checks out HMG-API at that commit and runs `./build.sh sdk` | `{"version": "1.0.3", "sha256": "…"}` (3) |
| Reusable workflow | `uses: Open-HMG/HMG-API/.github/workflows/mod.yml@<same SHA>` | `@v1.0.3` |
| `"hmgapi"` points check (4) | a floor before 1.0.3 has no SDK: the workflow reads `core/hookpoints/*.json` at that release's tag | the floor's `points.txt` |
| Mod releases | release workflows run to a draft; none is published | published, then the catalogue PR (C) |
| Catalogue `download` | the v1.0.2 assets (same bytes as the moved mods) | each mod's own release (7) |
| Core's bundled mods | from the catalogue, so the v1.0.2 assets | from the mods' releases |

A mod change that needs a new point (4) waits for 1.0.3 to merge (#111). After 1.0.3, each
mod repo moves its pin from the SHA to the release in one small PR.

## 1. Which mods move, to which repos

| Mod | Repo | Notes |
|---|---|---|
| `wireless-carplay-aa` | `Open-HMG/hmgapi-wireless-cp-aa` | most of the mod PRs. Renamed from `hmgapi-wireless-carplay-aa` on 2026-11-08 (the old URL redirects); the mod's id stays `wireless-carplay-aa` |
| `adb` | `Open-HMG/hmgapi-adb` | |
| `adbd` | `Open-HMG/hmgapi-adbd` | its build needs `third_party/` (AOSP 4.4.4 adbd, Apache-2.0, used only here), which moves with it. Still not released or listed. |
| `nemesis-shortcut` | `Open-HMG/hmgapi-nemesis-shortcut` | `mod.json` only, no code |
| `mods/examples/*` (others) | stay | API examples, test fixtures (`hello` is used by signing, catalogue and NEMESIS tests) |

Layout in every mod repo: the mod in a folder named by its id (`wireless-carplay-aa/`…),
with `README.md`, `LICENSE`, `.github/` and `docs/` at the root. `mkmodpkg.sh` packages
everything in a mod folder except `build.sh`, `test.sh`, `src`, `tools`, `native`, so a
mod at the repo root would ship the README and workflows.

Tags: `v<version>`; the release workflow refuses a tag that isn't the mod's `mod.json` version.

## 2. History (git filter-repo)

On main, the moving paths have one commit each: the squashed first commit (`81ea696`).
Filter-repo keeps that commit and its author. Older history is in the archived Leafia
repo and isn't imported. `docs/cluster-turns.md` only exists in #75, so it arrives with
that PR's rebase; the path is listed so a later import still catches it.

| Repo | filter-repo arguments (scratch clone only) |
|---|---|
| `hmgapi-wireless-cp-aa` | `--path mods/wireless-carplay-aa/ --path docs/cluster-turns.md --path-rename mods/wireless-carplay-aa/:wireless-carplay-aa/` |
| `hmgapi-adb` | `--path mods/adb/ --path-rename mods/adb/:adb/` |
| `hmgapi-adbd` | `--path mods/adbd/ --path third_party/ --path-rename mods/adbd/:adbd/` (`adbd/build.sh` then finds `third_party/` one level up, not two) |
| `hmgapi-nemesis-shortcut` | `--path mods/examples/nemesis-shortcut/ --path-rename mods/examples/nemesis-shortcut/:nemesis-shortcut/` |

Stays in core: `catalog/*.json` (the index), `wiki/` pages about the mods (owner docs),
`docs/api-plan.md`, `docs/car-test.md` (it installs mods from their releases after job 5).

## 3. The mod SDK

Each core release attaches `hmgapi-mod-sdk-<version>.zip` (in `SHA256SUMS` too).
Built by `./build.sh sdk`. No manufacturer files, no Android SDK files (the mod's CI
installs `android.jar` and `d8` as core's CI does).

| In the SDK | From | Used for |
|---|---|---|
| `lib/hmgapi-runtime.jar` | `out/classes` (runtime: `hmgapi.hook.*`, `ModEntry`, `Points`, `Car`, `Cluster`…) | compile `src/` (compile only, never packaged) |
| `lib/hmgapi-runtime-stubs.jar` | `out/runtime-stubs` | compile only, where runtime signatures name car classes |
| `lib/hmgapi-client.jar` | `./build.sh client` | mods' own apps and services (bundled, as now) |
| `lib/hmgapi-engine.jar` | `core/runtime/java/hmgapi/engine` | host tests (`Engine` with `HMGAPI_HOST=1`), `Signing`, `ScriptCheck` |
| `bin/lpatch-host` + `src/lpatch/` | `tools/lpatch` | host tests of patches and recipes; source to build it on macOS |
| `bin/build-mod.sh` | `build.sh mods()`, one mod | `hooks.jar` and app APK from a mod folder |
| `bin/mkmodpkg.sh`, `bin/mknemesismod.sh`, `bin/nemesis-meta.py`, `bin/nemesis-version.py`, `nemesis/mod/` | `tools/`, `install/nemesis/mod/`, `install/nemesis/bin/open.sh` | signed package, NEMESIS package |
| `bin/check-scripts.sh`, `unit-commands.txt` | `tools/check-scripts.sh`, `core/runtime/unit-commands.txt` | unit-only commands in mod scripts |
| `bin/mkrecipe.py`, `bin/mkdelta.py`, `bin/mkmemrecipe.py`, `bin/mkicon.sh`, `bin/mod-draft.py` | `tools/` | recipes, delta sets, tile pictures, new mod drafts |
| `test/lib.sh` | the `check`/`has`/`eng` helpers every mod `test.sh` copies today | mod tests |
| `points.txt`, `catalogue/*.json` | `core/hookpoints` | check the mod's `hooks.points` exist |
| `VERSION` | `Engine.HMGAPI_VERSION` | |

Version rule: the SDK's version is the core release's version; nothing else. An SDK
works for a mod whose `"hmgapi"` floor is at or below it.

Pin: each mod repo has `hmgapi-sdk.json`, `{"version": "1.0.3", "sha256": "…"}`. CI
fetches that release asset and refuses a sha256 mismatch. Bumping the SDK is a PR
changing that file.

## 4. Hook points stay in core

A mod that needs a new point:

| Step | Repo | PR | Merged when |
|---|---|---|---|
| 1 | HMG-API | catalogue entry (`core/hookpoints/*.json`), mapped on every car in `maps/`, `docs/hook-points.md` | maps checked (`tools/test-hooks.py`) |
| 2 | HMG-API | release carrying it (the SDK's `points.txt` lists it) | |
| 3 | mod | uses the point in `mod.json` `hooks.points`, `"hmgapi": ">=<that release>"`, SDK pin bumped | mod CI passes |

The mod's PR can be open (draft) during 1 and 2; its CI fails until the SDK has the point.

`"hmgapi"` rule, checked by the mod workflow: the floor is at or below the pinned SDK,
and every point in `hooks.points` is in the floor release's `points.txt` (the workflow
fetches that SDK too). A mod that raises its floor says so in its release notes.
Pre-releases ([releasing.md](releasing.md)): a mod's final names a final floor; a final
floor is met by that release's rc SDK (its points the SDK's own); a mod pre-release may
name a core pre-release (`">=1.1.0-rc.1"`).

## 5. Signing

| | Now | After |
|---|---|---|
| Mod key | `HMGAPI_MOD_KEY`/`_PUB` in core's `release` environment | `MOD_KEY`/`MOD_KEY_PUB` in each mod repo's `release` environment (`v*` tags only), same key |
| Core's mod key | signs three mods | deleted from core (decision A) |
| Examples in core | throwaway keys in tests | unchanged |

Why the same key (decision B): the engine refuses an update signed by a key other than
the one the mod was installed with, and `tools/catalog.py check --base` refuses an entry
whose author key changed. A new key strands every installed car. Cost: the key lives in
four repo environments; each is `v*`-tag-only with Admin bypass.

`maps/authors.json`: the key's `repos` gets the new repo URLs (the catalogue checks
an entry's `repo` against them).

## 6. Core release bundling

`tools/release-mods.txt` becomes a list of catalogue ids (`wireless-carplay-aa`, `adb`,
`nemesis-shortcut`). The release workflow, for each id: fetch `catalog/<id>.json`'s
`download`, check its `sha256` and signature (`tools/catalog.py`'s fetch and verify),
put it in `out/packages/`. `mkinstallers.sh` runs unchanged from there: `HMGAPI/updates`
on the USB stick, the NEMESIS stick and `mods/*-nemesis*.zip` come out as now.

So the catalogue entry is the one pin: bumping a bundled mod in the next core release is
the catalogue PR from 7. No second list of versions and hashes.

Done in `chore/remove-bundled-mods`: `tools/catalog.py fetch out/packages <id>...` (the
same download, SHA-256, signature and id/version checks as `check --download`), called by
`release.yml` with the ids in `tools/release-mods.txt`. Until 1.0.3 the entries' downloads
are the v1.0.2 assets, so a release carries the same mod bytes as v1.0.2.

Pre-releases: the catalogue lists finals only, so a HMG-API pre-release that bundles a mod's
pre-release pins it in `tools/release-mods.txt` itself, as `<id> <version> <url> <sha256>`
(`release.yml` runs `catalog.py fetch out/packages --list tools/release-mods.txt`). The id
must have a catalogue entry; the url must be a release asset of that entry's `repo`; the
version a pre-release newer than the entry's; and the zip gets the entry's checks (SHA-256,
signature with the entry's author key, id and version). A final HMG-API release refuses
such a line, so a final ships catalogue entries only: the mod's final goes in the catalogue
first, and the line goes back to its id.

NEMESIS conflicts: the mod rows of `install/nemesis/conflicts.txt` (`hmgapi.wireless-carplay-aa`,
`hmgapi.adb`) move into each mod's `mod.json` as `"nemesis_conflicts": [...]`. That's inside
the signed package, so `mknemesismod.sh` in core and in the mod repo make the same
NEMESIS package from the same zip. Core keeps the `hmgapi` row. (Job 2 checks the engine
ignores the new field.)

Engine `nemesis-sync` on the unit (a mod installed through HMG-API, listed in NEMESIS) reads
the installed mod's `"nemesis_conflicts"` too, after `conflicts.txt`'s row, as
`nemesis-meta.py` does. The wireless and adb rows stay in `conflicts.txt` for the packages
from before the field (v1.0.2's `wireless-carplay-aa` 2.1.0 and `adb` 1.0.0, the bundled
and catalogue versions until their next releases); each goes once no version without the
field is in use.

## 7. Catalogue

| Field | After |
|---|---|
| `repo` | `https://github.com/Open-HMG/hmgapi-<id>` (`adb`, `nemesis-shortcut`; `adbd` gets an entry only when it's released), and `hmgapi-wireless-cp-aa` for `wireless-carplay-aa` |
| `download` | the mod repo's release asset; the current v1.0.2 links stay until each mod's next release |
| `sha256`, `version` | from `tools/catalog.py add`, as now |

On a mod release (decision C), the mod's release run prints the
`tools/catalog.py add <zip> --download <url> --repo <url>` command; a maintainer opens the
HMG-API PR; `catalog.yml` downloads and checks it. The wiki's Community mods page updates
on merge.

## 8. Core tests that use the moving mods

| Test | Uses | After |
|---|---|---|
| `core/runtime/native-test.sh` | `mods/wireless-carplay-aa/recipes` on private stock libraries (skipped in CI) | synthetic part stays; the real-library part moves to the mod repo's tests (skips without stock trees, as now). CI coverage unchanged |
| `core/runtime/mod-dex-test.sh` | the id string `wireless-carplay-aa` | any id (`example-a`) |
| `install/nemesis/mod-test.sh` | real `conflicts.txt` rows for wireless and adb; `mods/adb/mod.json` as a "not a zip" input | `nemesis_conflicts` from a test mod's `mod.json`; `mods/examples/hello/mod.json` as the input |
| `mods/wireless-carplay-aa/test.sh`, `tools/aaturns`, `tools/cpturns`, `mods/adb/test.sh` | | move with the mods; off `tools/test-all.sh` |
| `tools/test-select.py` | `mods/*` rule, wireless special case | rule for `mods/examples/*` only |
| `tools/check-scripts.sh` | default `mods/*/` | `mods/examples/*/`, the installer |
| `engine-test`, `safety-test`, `overlap-test`, `net-test` | wireless-shaped synthetic mods, no files from `mods/` | unchanged |
| `signing-test`, `catalog-test`, `sync-test`, `usb-test` | `mods/examples/hello`, `!wireless.carplay.aa` (Leafia's NEMESIS package) | unchanged |

## 9. Mod repo CI

Recommended: a reusable workflow in HMG-API, `.github/workflows/mod.yml` (`on:
workflow_call`), called by each mod repo pinned to a core tag
(`uses: Open-HMG/HMG-API/.github/workflows/mod.yml@v1.0.3`). Inputs: `mods` (folders,
default all with a `mod.json`), `ndk` (an NDK for a native `build.sh`, hmgapi-adbd's r21e). Steps: fetch the pinned SDK (sha256), the `"hmgapi"` and
points check (4), build, each mod's `test.sh`, `check-scripts`, package with a throwaway
key. A second reusable workflow, `mod-release.yml`, signs with `MOD_KEY` and drafts the
release. Why: one place to fix CI for every mod; a template copies drift. Mod repos
still own their `release.yml` caller and secrets.

## 10. Open PRs touching `mods/`

| PR | Mod part | Plan |
|---|---|---|
| #15 CarPlay turns, left-hand traffic | wireless | merge in core before the freeze |
| #111 Bluetooth kept up | wireless | drop its catalogue/map edits (in #120), `"hmgapi": ">=1.0.3"`; merge in core before the freeze, or re-open in the mod repo; ships with 1.0.3 (F) |
| #22 Niro Hybrid KOR | map + wireless car entry | waits whole, map included (decisions D, E); after the hold, the car entry re-opens in the mod repo |
| #100 CarPlay picture freeze (draft) | wireless | re-open in the mod repo; its points are in core (#120) |
| #75 AA lanes, car-format turns | wireless + `docs/cluster-turns.md` | rebase onto the mod repo (the author is a maintainer) |
| #108 Korean K5 (draft) | core + wireless | split: core PR (Wi-Fi probe, install, `SystemCheck`); its by-name hook becomes a hook point; the K5 car entry waits (decision D) |
| #104 USB Wi-Fi adapter (draft) | new mod + wireless entry | if accepted: its own repo by its author, catalogue entry; small wireless `mod.json` change in the mod repo |

Freeze: once the wireless extraction starts, nothing more merges into
`mods/wireless-carplay-aa` in core. Moving a PR keeps its commits and authors: from the
PR branch, `git format-patch --stdout <base>..HEAD -- mods/wireless-carplay-aa` piped into
`git am -p2` in the new repo (`-p2` drops `a/mods/`, leaving `wireless-carplay-aa/…`);
mod docs such as `docs/cluster-turns.md` the same way with `-p1`. Its core parts stay a
core PR.

## 11. Jobs after this doc

| Job | Branch / repo | Acceptance |
|---|---|---|
| SDK + reusable CI | `feat/mod-sdk`, HMG-API | `./build.sh sdk` makes the zip listed in 3; `mod.yml` runs green on `mods/examples/hello` in core CI; the tests in 8 off the wireless fixture with the same checks; `"nemesis_conflicts"` read by `mknemesismod.sh`, ignored by the engine; `tools/test-all.sh` passes |
| Release the SDK | 1.0.3, after the cleanup (F) | `hmgapi-mod-sdk-1.0.3.zip` on the release, in `SHA256SUMS`; mod repos' pins move from the SHA to it |
| Extract wireless | `import`, `hmgapi-wireless-cp-aa` | filter-repo history as in 2; CI green on the pinned SDK (a commit SHA before 1.0.3); a tag drafts a release whose signed zip installs over v1.0.2's `wireless-carplay-aa-2.1.0` on the mock tester (same key, no warning); stock-tree tests pass locally |
| Extract adb, adbd, nemesis-shortcut (one job, three repos) | `import` in `hmgapi-adb`, `hmgapi-adbd`, `hmgapi-nemesis-shortcut` | same, per repo; `adbd` builds from `third_party/` with the NDK |
| Remove from core | `chore/remove-bundled-mods`, HMG-API | moved folders and `third_party/` gone; `release-mods.txt` is ids; local dry run of the release steps gives the same zip listings as v1.0.2 (differences explained); catalogue `repo` points at the mod repos and `download` stays on the v1.0.2 assets until 1.0.3 (F), `tools/catalog.py check --download` passes; no hit for the moved ids/paths outside `catalog/`, `wiki/`, `CHANGELOG.md`, release notes; core's `HMGAPI_MOD_KEY` deleted |

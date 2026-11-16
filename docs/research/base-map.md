# Research: one base map, per-build differences

Status: section 1 (the base map) is built: `tools/maplib.py`, `maps/base/`. Maintainers' answers (#236):
one base map, yes; verify per build and per car; teach the on-car mapper the shared-code methods (the
catalogue's `"site"`). Sections 2 and 3 are still proposals. It builds on two merged changes:
- #232, which lets a unit map the points its shipped map lacks;
- #233, which makes the on-car mapper take another car's method name only when the code matches.

It also builds on the research in #223 (`docs/research/self-mapping.md` there).

## The finding

The names hook points resolve to hardly change between system builds. Each map below is
compared with the US EV6 260507 map (`kia/ev6/usa/260507`, verified), point by point:

| Map | Made from | Points | Names that differ from the EV6 |
|---|---|---|---|
| `kia/ev6/can/260206` | stock files | 351 | 0 |
| `hyundai/neev/usa/260507` | stock files | 351 | 0 |
| `hyundai/neev/can/260206` | stock files | 351 | 2 (`homesetting.edit_list.items`, `dipo.iap2_client.manager`) |
| `hyundai/hmc/usa/260507` | stock files | 351 | 1 (`btsettings.projection_helper`) |
| `kia/kmc/usa/260507` | stock files | 351 | 0 |
| `kia/kmc/kor/260902` | stock files | 351 | 2 (`panel.test_gate.on`, `themesettings.split_item.type`) |
| `hyundai/neev/kor/260902` | stock files | 351 | 2 (the same 2) |
| `hyundai/ceev/can/260206` | Car Report + stock BtSettings, verified | 351 | 3 (the Canadian Ioniq 5's 2 + `btsettings.projection_helper`) |
| `hyundai/neev23/usa/260903` | Car Reports | 351 | 0 * |
| `kia/nq5`, `onpe`, `ka423`, `sg2ev` (one build) | Car Reports | 343-351 | 0 * |
| `hyundai/lx2pe/usa/260903` | Car Report | 343 | 0 * |

\* The Car Report maps were made before #233. Their smali-only methods (9) were borrowed by
name on the car, so their agreement on those points is the EV6's names coming back. It isn't
evidence. Their other points (about 340 each) were found on the car and do count.
`tools/check-maps.py` lists those methods as unconfirmed until a newer Car Report comes in.

**Over 8 builds read from stock files:**
- 4 platforms (HMC, KMC, ECOHMC, ECOKMC), 3 regions, 3 firmwares;
- **5 of 351 points** ever differ, and no build differs in more than 2;
- all 209 framework points are the same everywhere;
- both Korean builds differ in the same 2 points, so the Korean apps look like one build for both makes.

The Car Report maps for the newer US builds agree on every point that was found on the car.

So a map is mostly a list of names we already know. A new car or firmware mostly needs those
names confirmed, not found.

## What the merged changes already do

- **#233, the code check.** The on-car mapper (`MapProbe`) can't run smali patterns. It used to keep another map's method name if this car had a method of that name and signature. On the Korean builds that picked the wrong method twice, and `verify-map` passed both:
  - `py()Z` there reads `ro.product.model`; the gate is `pB`.
  - `gl()I` there is another field's getter; the point is `gk`.

  Now maps keep a code signature (`sig`) on the 9 smali-only methods, and the mapper takes the one method whose code matches. When several methods share that code, the point is left for the car's own map. On every stock tree that's 3 points.
- **#232, local points.** A unit maps the by-name points its shipped map lacks because the map is older, checks them with `MapCheck`, and uses them. It also keeps a whole local map (`--write`) for a car with no map, or whose map doesn't fit its framework.

## Which cars a map covers

A build's `onebin/` has one folder per car it can configure, and `build_product_list.txt`
lists even more. Neither proves which cars install that build:
- the Canadian Ioniq 6 has a folder in the Ioniq 5's 260206 build, but runs another build (`hyundai/ceev/can/260206`: even `core.odex` differs);
- the Korean Kia build has `hm21_kr` and `ka4_kr` folders, but the 2021 Mohave and the Carnival install the other Korean Kia build.

`CarMap.load`'s md5 check keeps this safe: a car on another build gets `mismatch` and its
hooks stay off, never another build's names. The maps from stock files list every folder in
the build except the cars known to install another one. The stock-file md5s are what say two
cars run the same build: a Car Report carries them.

## Proposal

### 1. One base map, and per-build differences

- **`maps/base/<app>.json`:** the names every build agrees on (346 of 351 today).
- **Each build's `car.json`** keeps what's per build:
  - what it already has: `products`, `files` (md5s), `status`, `verified`, `missing`;
  - a new `"names"`, the entries that differ from base (0 to 3 today).
- **The per-app files** go away, or stay generated for older HMG-API versions.
- **`CarMap.load`** = base + `names` - `missing`. Same md5 gate, same statuses, then #232's local points on top, as today.
- **Tools:**
  - `map-car.py` and `report-map.py` write only the differences.
  - `check-maps.py --rebuild` checks that base + differences equals a full `map-car.py` run.
  - A point whose name changes in a new build becomes a difference there; base doesn't change.

What it saves:
- **Smaller map PRs.** A new build's map is a `car.json` of a few lines, so a map PR is reviewable at a glance.
- **New points mapped once.** A new catalogue point (#212, #214) is mapped once, in base. Today it's listed as missing in every map until each one is redone: that's 141 missing entries over 9 maps on `feat/ev-hook-points`, nearly all of them because the maps are older than the points. #232 fills these on each car; base fills them upstream, for every car at once.
- **Changes stand out.** A changed name shows as a difference instead of hiding among 351 lines.

### 2. On the car: confirm base names before searching

For a car with no map, or with `mismatch` (#232's `--write`), the on-car mapper starts
from base. It keeps each point only if it holds:
- **a by-name point:** the class and member exist, as today;
- **a by-shape point:** base's name is the unique match of the point's shape; otherwise the shape search runs, as today;
- **a smali-only method:** base's `sig` (or any map's) is unique among the class's methods of that shape (#233);
- **a smali-only class:** 5290e41's members check and the shape check.

MapProbe already does each of these except starting from base. The gain is speed, and fewer
misses on big apps: a shape search over every class becomes a check of one name. After a
firmware update (#223's idea 2), a whole map should take seconds, with only the few changed
names needing a search.

### 3. One verify per build, not per car

A map made from stock files is `mapped` until `verify-map` passes on one car of that build.
Then every car whose `files` md5s match could treat it as verified. The md5s, not the model,
say whether two cars run the same build. This changes what "verified" means, so it needs the
maintainers' agreement.

### 4. Confirm the Car Report maps

One Car Report per build from HMG-API with #233 confirms the unconfirmed methods. Since the
Carnival, Sportage, Telluride and Niro EV maps share one build, that's 4 reports:
- one car of those four;
- a Palisade 2023;
- an Ioniq 5 on 260903;
- an Ioniq 6 in Canada.

`report-map.py` already keeps `sig`.

## Open questions

- **Format:** are generated per-app files kept for HMG-API 1.0.x and 1.1 readers, or does the format change in one release?
- **Shared code:** should a smali-only method whose code several methods share be resolved another way? For example, by who calls it: `invoke-static` survives odex quickening, which would settle `panel.test_gate.on`. Or does it stay a Car Report point?
- **Verified per build:** section 3 needs the maintainers' agreement.

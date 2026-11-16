# Making mods

A mod is a folder with a `mod.json`, signed with your own key. Everything a mod does
is declared there, shown to the owner before it applies, and undone when it's turned
off or removed. In the repository:

- `docs/mod-reference.md`: the full `mod.json` reference by topic. Already have a
  Gen5W package? `tools/mod-draft.py` drafts a `mod.json` from it so you can offer a
  mod alongside it.
- `mods/examples/`: working examples (hooks, a background service, a side panel
  page with readings, a test mod using buttons, projection and commands).
- `docs/vehicle-commands.md`: the car commands and their limits.
- `docs/micom-map.md`: mapping new car data.
- `tools/mkicon.sh <picture> <folder>`: app-menu tile pictures that match the car's
  own (the stock gradient, day and night, and the greyed-out look) from any shape.
- `docs/api-plan.md`: what each community project uses and what's planned.
- In your own repository: the mod SDK (`hmgapi-mod-sdk-<version>.zip`, on each HMG-API
  release from 1.1.0) and HMG-API's reusable mod workflow as its CI ("Building a mod
  outside this repo" in `docs/mod-reference.md`). The mods HMG-API ships are built
  that way, each in its own repo in the [Open-HMG](https://github.com/Open-HMG) org.

Packaging: `tools/mkmodpkg.sh <mod folder> <your key>` (make a key once with
`java -cp out/engine-host hmgapi.engine.Signing keygen <file>`). Keep the key safe:
only packages signed with it update your mod.

### As a NEMESIS package

Owners with NEMESIS can install your mod from NEMESIS Manager, a USB stick or the
Package Store. One command wraps your signed zip:

```
tools/mknemesismod.sh out/packages/my-mod-1.0.0.zip
```

It makes `out/dist/my-mod-1.0.0-nemesis.zip` (unzipped onto a stick, NEMESIS
installs it) and `my-mod-1.0.0-nemesis-package.zip` (the package itself, for the
Package Store or `nemesis install`). The package's id is `hmgapi.<your mod id>`, it
needs HMG-API 1.0 or newer (`depends="hmgapi@>=1.0"`), and its `os` comes from your
`mod.json` `"system"`. Each mod in your `"requires"` goes in `depends` too, from the
lowest version its range allows (`"wireless-carplay-aa >=2.1.0"` becomes
`hmgapi.wireless-carplay-aa@>=2.1.0`), so NEMESIS won't install yours without it.
NEMESIS checks only that lowest version: the rest of a range (`^`, `~`, an upper bound)
is HMG-API's own check, and the build prints a note saying so. If your mod changes files another NEMESIS package changes too,
list that package against `hmgapi.<your mod id>` in a file like
`install/nemesis/conflicts.txt` and point `HMGAPI_NEMESIS_CONFLICTS` at it: they go in
`depends` as `!<id>`, and NEMESIS won't install the two together. It carries your zip unchanged; its scripts (the template in
`install/nemesis/mod/`) do only what the HMG-API app does, through `hmgapid`:

- install and update: `engine install` (same signature, author key and car checks),
  then `engine apply`. A first install whose apply fails comes out again.
- uninstall: `engine remove`, then `engine apply`: everything the mod changed goes
  back to stock, its settings stay. A NEMESIS update skips this, so the mod and its
  settings stay while the new version goes in.

Don't add your own install steps to these scripts: put every change in
`patches.json`, so HMG-API backs it up and removing the mod puts it back.

## Listing your mod

The [Community mods](Community-mods) page lists mods from their authors'
own repositories. One pull request to HMG-API adds yours and puts your author key
on the trusted list, so owners install it without the unknown-author warning (once
a HMG-API release carries the list).

1. Publish your signed zip (`tools/mkmodpkg.sh`) where it stays put, such as a
   GitHub release asset. Don't replace a published file: each version gets its own.
2. In your fork of HMG-API, one command:

   ```
   tools/catalog.py add my-mod-1.0.0.zip --download https://github.com/you/my-mod/releases/download/v1.0.0/my-mod-1.0.0.zip --repo https://github.com/you/my-mod
   ```

   It checks the zip's signature and writes `catalog/<id>.json` (name, description,
   cars and version from your `mod.json`, the zip's SHA-256, your author key) and
   your entry in `maps/authors.json`. `--repo` can come from `"repo"` in `mod.json`.
3. Open the pull request with both files. CI downloads the zip and checks that its
   SHA-256, signature, id and version match the entry.

A new version is the same steps with the new zip: the entry's author key can't
change (only your key updates your mod), and its version can't go back. A pull
request changes only its own author's entry in the trusted list. Listing means the
package is whole and signed by you; the maintainer reviews each new author before
merging.

## Scripts on the head unit

The head unit's shell is mksh with a small toolbox: no `sed`, `tr`, `awk`, `head`,
`tail` or busybox. See the mod reference for what there is, and run
`tools/check-scripts.sh <your mod>` before packaging. HMG-API warns at install when a
script calls a command the unit doesn't have.

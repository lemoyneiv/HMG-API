# Installing and removing mods

## Installing

1. Put the mod's `.zip` (as its author released it; [Community mods](Community-mods)
   lists them) in `HMGAPI/updates/` on a USB stick.
2. Plug the stick in, open the HMG-API app > **Check USB updates**.
3. Each zip is listed with what it is. Tap the mod to install it.
   - **A mod whose author isn't on HMG-API's trusted author list** shows a warning
     first: its code runs inside the head unit's apps, so install mods only from
     authors you trust. Authors on the list install without the warning.
   - Every later update of a mod must be signed with the same author key as the
     version you installed, so nobody else can replace it.
4. Go back to the main screen and tap **Apply changes** (see
   [Using the HMG-API app](Using-the-HMG-API-app)).

A mod that doesn't list your car or software says so and isn't installed. If you know
it suits your car anyway (its author or a Car Report says so), tap **Install anyway…**
under it. It installs with a warning, at your own risk: HMG-API still backs up every
file it changes, and removing the mod puts them back. Over adb, add `--any-car` to
`engine install`.

### With NEMESIS

Some mods also come as a NEMESIS package (`<mod>-<version>-nemesis.zip`; HMG-API's
releases carry one for each of its mods). HMG-API must be installed first; NEMESIS
Manager says so if it isn't.

1. Unzip it onto the top level of a USB stick, plug it in, and confirm the install.
2. Restart the head unit if it says so.

HMG-API installs the mod and applies it, with the same checks as the app (signature,
author key, car). A mod that doesn't list your car isn't installed; to install it
anyway, at your own risk, add an empty file `HMGAPI/ANY-CAR` to the stick.
Uninstalling the package in NEMESIS Manager removes the mod and puts its files back
to stock. While a mod package is installed, NEMESIS won't uninstall HMG-API:
uninstall the mod packages first. A mod that needs another mod (one that builds on
the Wireless CarPlay + Android Auto mod, say) works the same way: NEMESIS won't install it
until the mod it needs is installed and new enough, and won't uninstall that one while
it's there.

### Over adb, from a computer

With HMG-API installed and the head unit on adb, one command per step:

```
adb push wireless-carplay-aa-2.1.0.zip /data/local/tmp/
```
```
adb shell hmgapid engine install /data/local/tmp/wireless-carplay-aa-2.1.0.zip
```
```
adb shell hmgapid engine plan
```
```
adb shell hmgapid engine apply
```

`install` checks the package and its signature but changes nothing yet; `plan` shows
what will change (and any problem); `apply` does it. Restart the head unit if the plan
says so. Remove a mod with `adb shell hmgapid engine remove <id>`, then `apply`.

## A NEMESIS package that does the same thing

Mods never fight a NEMESIS package that already changed the same apps (an older
wireless CarPlay/Android Auto package, for example):

- A mod's hooks stay off for any app the package replaced: the package keeps working
  as it is. The plan says which hook points are off and why.
- Files both change: the mod's change goes on top of the package's (a line in a
  settings file), or starts from the stock copy NEMESIS keeps.
- A file the package added that the mod adds too: nothing changes, and the plan names
  the package to uninstall first.

**Best order:** uninstall the old package in NEMESIS Manager first, restart, then turn
the mod on. **If you already installed the mod first:** uninstall the package anyway,
restart, and tap **Apply changes**. HMG-API sees that the package put the stock files
back, builds the mod's changes on them, and the mod's hooks turn on at the next restart.

## Updating

The same way: put the new zip on the stick and install it over the old one. Your
settings for the mod are kept.

## Removing

In the HMG-API app, tap **Remove** under the mod, confirm, then **Apply changes**.
Everything the mod changed goes back to stock. Its settings are kept in case you
install it again.

To turn a mod off for a while instead, use its switch: it stays installed.

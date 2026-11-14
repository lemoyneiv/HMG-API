# Troubleshooting

## The HMG-API app isn't there, or there's no tile for it

- **No tile, no ★ choice:** those come from HMG-API's hooks, which need your car's hook
  map. Open the app from **NEMESIS Manager → My Packages → HMG-API → Launch**, or over
  adb: `adb shell "hmgapid sh 'sh /data/hmgapi/open.sh'"`.
- **The app didn't install** (a message says so; `/data/hmgapi/install.log` has the
  reason): restart the head unit and HMG-API tries again, or use **Launch** above,
  which installs it first and then opens it.

## After a firmware update: "HMG-API's system files are gone", or uninstall fails

A firmware update (Navigation Updater, or an update over the air) puts the head unit's
system files back to stock. HMG-API's own files and the mods' changes to system files
go with it; HMG-API's data (mods, settings) stays. If NEMESIS is gone too, install it
again first, or use the USB stick route.

- **To keep using HMG-API:** install it again, any route. It notices the update, drops
  its backups of the old firmware and starts from the new firmware's files; your mods
  apply again on them (each one's own checks say if it supports the new firmware).
- **To remove it:** uninstall it as usual ([Uninstalling](Uninstalling)).

1.0.2 and 1.1.0-beta.1 can't uninstall after a firmware update (NEMESIS says
"Operation failed"): install HMG-API 1.1.0 or newer over it first, then uninstall
it if you want to.

## The top bar says "Hooks off for this car"

HMG-API still works; only the parts of mods that change the car's own apps wait.
- **No hook map for this car or software:** make a [Car Report](Car-Report), or try
  Settings → Hook map → "Map this car from its files".
- **The map isn't checked on this car yet:** Settings → Hook map → "Check the map on
  this car", then restart.

## A mod is red

It doesn't run on your car; the Mods tab says why. Usually a hook point your car's
map doesn't have yet: make a [Car Report](Car-Report).

## A mod's car command does nothing

The first time a mod uses a car command, HMG-API asks you on screen. If you said
"Don't allow", change it in Settings → Vehicle → "Forget my answers".

## The head unit restarted over and over

After three failed starts in a row, HMG-API turns its hooks off by itself so the
head unit starts normally, and the app says the hooks aren't running. Turn off or
remove the mod you added last, then reset the counter and restart:

```
adb shell "hmgapid sh 'echo 0 > /data/hmgapi/hook/starts'"
```

## A mod isn't doing anything

- Is it switched on, and did you **Apply changes**?
- Look at the plan for a `problem` line about it.
- Its log: the Mods tab, or the Logs tab.

## Android Auto won't connect wirelessly with a USB stick in

A stick in the port can make the head unit start wired Android Auto and refuse
wireless until it times out. Take the stick out; if it stays stuck, restart the head
unit.

## NEMESIS Manager shows hmgapi/boot as "stopped"

That's normal. `hmgapi/boot` (Services) starts HMG-API when NEMESIS starts, then
exits; HMG-API keeps running in its own daemon. Start and Stop there change
nothing: HMG-API starts once per restart, from whichever comes first, this or its
own boot hook.

## NEMESIS says "daemon not running" for a minute or two after a restart

That was HMG-API 1.0.0 and 1.0.1 holding up NEMESIS's start. Update HMG-API to
1.0.2 or newer.

## Sending a report

Make a [Car Report](Car-Report) and open an issue ([Getting help](Getting-help)).

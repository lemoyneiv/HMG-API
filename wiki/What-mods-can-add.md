# What mods can add

## Buttons

Mods can use the car's buttons: the head unit's (MAP, NAV, ★, SETUP, the TUNE
knob...), the steering wheel's (voice, ★, mute, call, seek...) and the overhead
console's. A mod can use a single press, a long press (held about a second), two to
four quick presses, or every press.

- If the mod **keeps** the car's action, the button does both.
- If it **replaces** it, the car doesn't do its own action for that kind of press;
  other presses still work as before. A button with a replaced press reaches the car
  a moment later (after you let go, or about half a second later if the mod uses
  multiple presses on it).
- **SOS and power** can never be taken from the car.

The plan lists every button a mod uses.

## Side panel pages

The right-hand panel of the split screen (where the map or the current song
usually shows) can show pages from mods. Mods' pages share one entry in the panel's
list, named after the page (or "HMG-API" if there are several; arrows switch
between them). Turn it on where you choose the panel's pages in the car's settings.

## Car readings and commands

Mods can read things like the gear, speed, battery charge, the 12V battery and
its sensor, and seat heat, and send a few reviewed commands: open the charge door (only in Park), seat
and steering wheel heat, battery conditioning, ambient light colour, parking guide
lines. Each command has a limit (Park only, one mod at a time, and so on), shown in
the plan.

## Android Auto settings

A mod can change how Android Auto looks (its density: smaller text and icons, more
map) and what the phone is told about the car (for example, no tap limit while
moving, or EV data so Maps plans charging stops).

## Network rules

Some cars' head units have a firewall that blocks what a mod needs (on the Ioniq 6 it
blocks wireless CarPlay on the car's Wi-Fi). A mod can ask HMG-API to open ports,
add routes or change network settings; HMG-API keeps them in place, also after the
car's own firewall reloads, and takes them out when the mod is off or removed.
Rules for the car's Wi-Fi hotspot go in as they are. Anything that could reach the
car's own network (the mobile data modem, the internal link) is left out with a
warning until you allow that mod (`overrides.allow_network`). The plan lists every
rule.

## Bluetooth Low Energy

The head unit's Bluetooth chip supports Bluetooth Low Energy, though the stock
software leaves Android's BLE interface off. Mods that ask for BLE can scan for and
talk to nearby sensors and accessories (such as a 12V battery monitor, tyre sensors
or an OBD adapter) while phone calls and Bluetooth music keep working as usual. The
plan shows when a mod uses BLE, and turning the mod off disconnects its devices at
once.

## Not while driving

A mod can mark its own app as "not while driving": the car closes it with its own
popup when the driving restriction starts, as it does for its own video apps.

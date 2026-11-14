# Supported cars

HMG-API installs and runs on any Gen5W navigation head unit. What depends on your car
is its **hook map**: where each place HMG-API and mods hook is in your car's exact
software. With a map, everything works. Without one, mods still install and run, but
the parts that change the car's own apps stay off, and the app says which.

| Car | Region | Head unit software | Hook map |
|---|---|---|---|
| Kia EV6 | USA | 260507 | ✅ checked on a car (325 of 325 hook points) |
| Kia EV6 | Canada | 260206 | 325 of 325 hook points found in stock files; waiting for a check on a car |
| Hyundai Ioniq 5 | Canada | 260206 | 325 of 325 hook points found in stock files; waiting for a check on a car |
| Hyundai Ioniq 5 (all trims, incl. SE) | USA | 260507 | 325 of 325 hook points found in stock files, same files as 3 Car Reports; waiting for a check on a car |
| Hyundai Ioniq 5 (2023 on) | USA | 260903 | made on owners' cars by HMG-API's own mapper (351 of 351 hook points); waiting for a check on a car |
| Kia Sportage | USA | 260507 | made on an owner's car by HMG-API's own mapper (351 of 351 hook points); waiting for a check on a car |
| Kia Telluride | USA | 260507 | made on an owner's car by HMG-API's own mapper (351 of 351 hook points); waiting for a check on a car |
| Kia Niro EV | USA | 260507 | made on 3 owners' cars by HMG-API's own mapper (343 of 351 hook points: the 8 newest need a Car Report from 1.1.0 or newer); waiting for a check on a car |
| Kia Carnival | USA | 260507 | made on an owner's car by HMG-API's own mapper (343 of 351 hook points: the 8 newest need a Car Report from 1.1.0 or newer); waiting for a check on a car |
| Hyundai Palisade | USA | 260903 | made on an owner's car by HMG-API's own mapper (343 of 351 hook points: the 8 newest need a Car Report from 1.1.0 or newer); waiting for a check on a car |

**Your car isn't listed, or says "waiting"?** Install HMG-API and make a
[Car Report](Car-Report): HMG-API maps your car from its own software on the spot,
and the report is everything needed to add it. You can also try that map yourself
(Settings → Hook map → "Map this car from its files").

**Only the newest software for each car and region is supported.** If your head unit
has an older version, update it first (the manufacturer's update site or the dealer).

**Not supported:** head units without navigation ("Display Audio") are a different
system. No Korean car is listed yet: their head units have the Wi-Fi chip, but it stays
off on cars without the Wi-Fi hotspot feature. New regions are on hold for now (USA
and Canada only); reports are still welcome.

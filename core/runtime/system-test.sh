#!/bin/sh
# Off-car test of mod system requirements (hmgapi.engine.SystemCheck, and the
# engine refusing or leaving out parts) on a desktop JVM, against a fake US EV6.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d)
sh tools/engine-classes.sh "$CP"
T=$(mktemp -d)
trap 'rm -rf "$T" "$CP"' EXIT
R=$T/root
fail=0
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
sc() { set +e; out=$(HMGAPI_ROOT=$R java -cp "$CP" hmgapi.engine.SystemCheck "$@" 2>&1); rc=$?; set -e; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
mod() {   # mod <id> <system json> [parts json]
    mkdir -p $R/data/hmgapi/mods/$1
    printf '{"id": "%s", "version": "1.0.0", "system": %s%s}\n' "$1" "$2" "${3:+, \"parts\": $3}" > $R/data/hmgapi/mods/$1/mod.json
    printf '{"changes": []}\n' > $R/data/hmgapi/mods/$1/patches.json
}
ok() { sc $R/data/hmgapi/mods/$1; has "$2" "ok: $1" "$out"; rm -r $R/data/hmgapi/mods/$1; }
no() { sc $R/data/hmgapi/mods/$1; has "$2" "$3" "$out"; [ $rc = 1 ] || { echo "FAIL  $2: exit $rc"; fail=1; }; rm -r $R/data/hmgapi/mods/$1; }

mkdir -p $R/system/etc/permissions $R/sys/bus/sdio/devices/mmc1:0001:1 $R/data/hmgapi/mods $R/data/hmgapi/settings
printf '%s\n' ro.build.product=CVEV.USA.0000.V015.012.260507 ro.board.platform=tcc803x ro.build.version.release=4.4.2 \
    ro.mobis.onebindir=cvev_us ro.product.opt.stepofspec=105 > $R/props.txt
printf '<permissions>\n    <feature name="com.hkmc.hardware.ev" />\n</permissions>\n' > $R/system/etc/permissions/ev.xml
printf '<permissions><feature name="com.hkmc.software.wifi.module"/></permissions>\n' > $R/system/etc/permissions/wifi.xml

sc
has "facts from the props" "model CVEV, region USA, firmware 260507, make kia, name EV6, platform tcc803x" "$out"
has "Wi-Fi chip seen on SDIO" "Wi-Fi chip found" "$out"
out=$(HMGAPI_PRODUCT=CEEV.CAN.0000.V015.011.260206 HMGAPI_ROOT=$R java -cp "$CP" hmgapi.engine.SystemCheck 2>&1)
has "Canadian Ioniq 6 identity" "model CEEV, region CAN, firmware 260206, make hyundai, name Ioniq 6, platform tcc803x" "$out"

mod a '{"make": ["kia", "hyundai"], "region": ["USA", "CAN"], "firmware": ">=260206"}'; ok a "lists and a range"
mod a '{"make": "all", "model": "*", "region": ["all"]}'; ok a "all and *"
mod a '{"model": ["!NEEV"]}'; ok a "exclusion of another model"
mod a '{"product": ["NEEV.CAN.", "cvev.usa."]}'; ok a "product prefixes, any case"
mod a '{"features": ["com.hkmc.hardware.ev", "hmgapi.wifi", "!com.hkmc.hardware.ice"]}'; ok a "features present and absent"
mod a '{"props": {"ro.product.opt.stepofspec": ">=100"}, "android": "4.4.2", "platform": ["tcc803x"]}'; ok a "props range, android, platform"
mod a '[{"model": "NEEV", "region": "CAN"}, {"model": "CVEV", "region": "USA", "firmware": "260507"}]'; ok a "any one of several setups"
mod a '{"region": ["CAN"]}'; no a "wrong region" "needs region CAN (this unit: USA)"
mod a '{"firmware": [">260507"]}'; no a "firmware too old" "needs firmware >260507"
mod a '{"model": ["!CVEV"]}'; no a "excluded model" "not for model CVEV"
mod a '{"features": ["!com.hkmc.hardware.ev"]}'; no a "excluded feature" "not for units with com.hkmc.hardware.ev"
mod a '{"year": ">=2023"}'; no a "unknown year says what to set" "set the car's model year in HMG-API's settings (car.year)"
mod a '[{"region": "CAN"}, {"make": "hyundai"}]'; no a "none of the setups" "matches none of its 2 supported setups"
mod a '{"colour": "red"}'; no a "unknown key refused" 'unknown system requirement "colour"'

printf '{"car": {"year": 2023}}\n' > $R/data/hmgapi/settings/hmgapi.json
mod a '{"year": ">=2022 <2025", "name": "EV6"}'; ok a "owner's year and the model table's name"
rmdir $R/sys/bus/sdio/devices/mmc1:0001:1
mod a '{"features": ["hmgapi.wifi"]}'; no a "no Wi-Fi chip" "no Wi-Fi chip or driver found on this unit"
# a chip nobody has powered yet (Wi-Fi never on): its driver and firmware stand for it on a car with the hotspot
mkdir -p $R/system/wifi; : > $R/system/wifi/bcmdhd.ko; : > $R/system/wifi/fw_bcmdhd.bin
mod a '{"features": ["hmgapi.wifi"]}'; no a "driver without the stock hotspot: not a chip seen (unpowered)" "this car's hotspot feature is off"
printf '<permissions><feature name="com.hkmc.software.wifihotspot"/></permissions>\n' > $R/system/etc/permissions/hotspot.xml
mod a '{"features": ["hmgapi.wifi"]}'; ok a "driver, firmware and the stock hotspot: Wi-Fi chip"
rm $R/system/wifi/fw_bcmdhd.bin
mod a '{"features": ["hmgapi.wifi"]}'; no a "driver without its firmware: no" "no Wi-Fi chip or driver found on this unit"
rm $R/system/etc/permissions/hotspot.xml
# a chip the unit never powers: driver, firmware and wifi.module, but no stock hotspot (Korean units, some US trims)
: > $R/system/wifi/fw_bcmdhd.bin
mod a '{"features": ["hmgapi.wifi"]}'; no a "unpowered chip, a mod that doesn't power it: no, and says why" "this car's hotspot feature is off, so the unit never powers it"
hot() {   # a mod that asks HMG-API for the hotspot
    mkdir -p $R/data/hmgapi/mods/$1
    printf '{"id": "%s", "version": "1.0.0", "wifi": {"hotspot": true}, "system": %s}\n' "$1" "$2" > $R/data/hmgapi/mods/$1/mod.json
}
hot a '{"features": ["hmgapi.wifi"]}'; ok a "unpowered chip, a mod that asks for the hotspot: yes"
hot a '[{"region": "CAN"}, {"features": ["hmgapi.wifi"]}]'; ok a "... in a list of setups too"
sc; has "the facts say unpowered" "Wi-Fi chip not seen: the hotspot feature is off" "$out"
# HMG-API's own hotspot file says nothing about a chip: only the chip showing on SDIO does
printf '<permissions>\n    <feature name="com.hkmc.software.wifihotspot" />\n</permissions>\n' > $R/system/etc/permissions/com.hkmc.software.wifihotspot.xml
mod a '{"features": ["hmgapi.wifi"]}'; no a "HMG-API's hotspot file isn't the stock hotspot" "this car's hotspot feature is off, so the unit never powers it"
hot a '{"features": ["hmgapi.wifi"]}'; ok a "... still fine for the mod that powers it"
mkdir -p $R/sys/bus/sdio/devices/mmc1:0001:1
mod a '{"features": ["hmgapi.wifi"]}'; ok a "the chip shows on SDIO: Wi-Fi chip for every mod"
rmdir $R/sys/bus/sdio/devices/mmc1:0001:1
rm -f $R/data/hmgapi/public/wifi-chip
rm $R/system/etc/permissions/com.hkmc.software.wifihotspot.xml
rm $R/system/etc/permissions/wifi.xml
mod a '{"features": ["hmgapi.wifi"]}'; no a "driver without the wifi.module feature: no chip" "no Wi-Fi chip or driver found on this unit"
hot a '{"features": ["hmgapi.wifi"]}'; no a "... even for a mod that asks for the hotspot" "no Wi-Fi chip or driver found on this unit"
printf '<permissions><feature name="com.hkmc.software.wifi.module"/></permissions>\n' > $R/system/etc/permissions/wifi.xml
rm -r $R/system/wifi

# the engine: refuses the mod, leaves out a part, override at the owner's risk
mod b '{"region": "KOR"}'
eng plan; has "engine refuses an unsupported mod" "b: needs region KOR" "$out"
printf '{"car": {"year": 2023}, "overrides": {"ignore_system": ["b"]}}\n' > $R/data/hmgapi/settings/hmgapi.json
eng plan; has "override goes on with a warning" "WARNING: b: needs region KOR" "$out"
rm -r $R/data/hmgapi/mods/b
mod c '"all"' '[{"id": "base"}, {"id": "wifi", "system": {"features": ["hmgapi.wifi"]}}]'
eng plan; has "a part the unit can't run is left out" "c: part wifi left out: needs hmgapi.wifi" "$out"

# a car from a Car Report's map has a make and a name too (a mod's "system" may name them)
set +e; out=$(HMGAPI_ROOT=$R HMGAPI_PRODUCT=NQ5.USA.0000.V015.012.260507 java -cp "$CP" hmgapi.engine.SystemCheck 2>&1); set -e
has "a mapped car's make and name (Sportage)" "model NQ5, region USA, firmware 260507, make kia, name Sportage" "$out"
# ... and every model with a hook map is in the table, with its map's make
o=$(python3 - <<'PY'
import glob, json, re
src = open("core/runtime/java/hmgapi/engine/SystemCheck.java").read()
table = {m[0]: m[1] for m in re.findall(r'\{"([A-Z0-9]+)", "([a-z]+)", "[^"]+"\}', src.split("MODELS = {")[1].split("};")[0])}
bad = []
for f in sorted(glob.glob("maps/*/*/*/*/car.json")):
    car = json.load(open(f))
    for p in car["products"]:
        code = p.split(".")[0]
        if table.get(code) != car["make"]:
            bad.append("%s (%s): %s in SystemCheck.MODELS" % (code, f, table.get(code, "not")))
print("; ".join(bad))
PY
)
if [ -z "$o" ]; then echo "ok    every model with a hook map has its make and name"; else echo "FAIL  models with a map but no line in SystemCheck.MODELS: $o"; fail=1; fi
exit $fail

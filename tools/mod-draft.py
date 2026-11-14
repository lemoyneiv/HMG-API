#!/usr/bin/env python3
"""Draft an HMG-API mod of an existing Gen5W package, for authors who want to offer
their project as a mod as well (the package itself keeps working as it does).

    tools/mod-draft.py <package dir>              print the mod.json draft and the notes
    tools/mod-draft.py <package dir> --out <dir>  also write a mod folder: the package's files + mod.json + DRAFT-NOTES.txt

Reads the package's NEMESIS.data ([nemesis], [app.N], [service.N]) and its scripts.
It changes none of the author's code: the notes list lines where an HMG-API feature
may fit (paths, car service calls, /system edits, install scripts). Nothing here
runs anything from the package.
"""
import json
import os
import re
import shutil
import sys


def ini(path):
    secs, cur = {}, None
    for raw in open(path, encoding="utf-8", errors="replace"):
        line = raw.strip()
        if not line or line[0] in "#;":
            continue
        m = re.match(r"\[(.+)\]$", line)
        if m:
            cur = secs.setdefault(m.group(1).strip(), {})
        elif "=" in line and cur is not None:
            k, v = line.split("=", 1)
            cur[k.strip()] = v.strip()
    return secs


def numbered(secs, prefix):
    out = []
    for name, sec in secs.items():
        m = re.match(re.escape(prefix) + r"\.(\d+)$", name)
        if m:
            out.append((int(m.group(1)), sec))
    return [s for _, s in sorted(out, key=lambda x: x[0])]


def mod_id(nid):
    i = re.sub(r"[^a-z0-9-]+", "-", nid.lower()).strip("-")
    return (i or "mod")[:64]


def version(v):
    if not v or "@" in v:
        return "0.1.0", "version: NEMESIS.data has \"%s\" (filled in by its build); set the real one" % v
    parts = re.findall(r"\d+", v)[:3]
    while len(parts) < 3:
        parts.append("0")
    return ".".join(parts), None


# what a script line does -> the HMG-API feature that may fit (checked per line, first match wins)
HINTS = [
    (r"/data/nemesis/apps/[A-Za-z0-9._-]+", "the package folder: in a mod, $HMGAPI_MOD_DIR (/data/hmgapi/mods/<id>), or the script's own folder (${0%/*})"),
    (r"/data/nemesis/(state|logs?)\b|/data/nemesis/[A-Za-z0-9._-]+\.log", "state and logs: $HMGAPI_DATA is the service's own folder; its output already goes to /data/hmgapi/logs/<id>/<name>.log"),
    (r"/data/nemesis/bin", "in a mod, services get the stock PATH; /system changes go in patches.json"),
    (r"\bnemesis\s+root\b", "in a mod, services run as their \"user\" (root, system or the app's own); HMG-API's daemon: hmgapid"),
    (r"\bnemesis\s+remount\b|mount\s+-o\s+remount", "/system changes: patches.json (merged with other mods, put back on removal)"),
    (r"\bnemesis\s+(log|service|pkg-launch)\b", "hmgapid services / service-log / service-restart (or the client library)"),
    (r"\bservice\s+call\s+com\.hkmc\.(micom\.(bcm|usm|pgs)|vr|factory)\b|\bmicom\.(system|hev)\s+(77|78|62|65|66)\b", "a car command: mod.json \"vehicle\": {\"commands\": [...]} and `hmgapid vehicle-do <command> <args>` (charge_door, seat_heat, wheel_heat, conditioning, ambient_light, guide_lines, startup_notice: docs/vehicle-commands.md)"),
    (r"\btm_hscan_service\b|\bmicom\.(hev|system)\b", "a car reading: mod.json \"vehicle\": {\"read\": [...]} and `hmgapid vehicle <name>` (speed, gear.park, steering.angle, hv.soc, battery12v.soc, ...: docs/mod-reference.md); if it's a command, see docs/vehicle-commands.md"),
    (r"\bservice\s+call\s+[A-Za-z0-9_.]+\s+\d+", "a car service call: see the vehicle readings and commands (docs/mod-reference.md, docs/vehicle-commands.md)"),
    (r"\bpm\s+(install|uninstall|enable|disable)\b", "the mod's \"apps\": HMG-API installs, turns off and removes them with the mod"),
    (r"HKMC_GPM|nearestVideoConfiguration|SENSOR_TYPE_SPEED|NAVDUMP", "Android Auto: mod.json \"projection\" (aa_density, aa_sensors remove/add, aa_fuel_types, aa_ev_connectors, events): applied through hooks, combined with other mods"),
    (r"/dev/input|MAP_SHORTCUT|ModeKeyDispatcher|KEYCODE_", "buttons: mod.json \"input\" bindings (key, press short/hold/x2-x4/down, keep or replace; docs/mod-reference.md Buttons): one reader for every mod, no root"),
    (r"media_codecs\.xml", "media_codecs.xml: \"codecs\" changes by codec name (merged with other mods); decisions made on the unit go in a \"probe\""),
    (r"enabled_input_methods", "a list setting: {\"type\": \"setting\", ..., \"add\": [entries]} (merged per entry with other mods)"),
    (r"persist\.wifi\.state|android\.hardware\.wifi", "Wi-Fi client: mod.json \"wifi\": {\"client\": true}"),
    (r"bplus\.default\.so|BluetoothGatt|startLeScan|BluetoothLeScanner", "BLE scanner and GATT central: mod.json \"ble\": {\"central\": true} and hmgapi.client.Ble"),
    (r"/system/etc/permissions/com\.hkmc", "feature flags: mod.json \"feature_flags\": [...] (shared with other mods)"),
    (r"\bsettings\s+put\b", "Android settings: a \"setting\" change in patches.json (put back on removal)"),
    (r"com\.hkmc\.intent\.action\.TOAST", "fine as is; the client library also has HmgApi.toast(context, text)"),
    (r"/storage/usb\d|/mnt/media_rw/usb", "USB sticks: mod.json \"usb\" folders; the mod's apps hear USB_FOLDER, services ask hmgapid usb"),
]


def scan(pkg_dir, files):
    notes = []
    for rel in files:
        p = os.path.join(pkg_dir, rel)
        try:
            text = open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        for n, line in enumerate(text.splitlines(), 1):
            s = line.strip()
            if s.startswith("#"):
                continue
            for pat, hint in HINTS:
                if re.search(pat, s):
                    notes.append("%s:%d: %s\n      -> %s" % (rel, n, s[:110], hint))
                    break
    return notes


def scripts(pkg_dir):
    out = []
    for root, dirs, files in os.walk(pkg_dir):
        dirs[:] = [d for d in dirs if not d.startswith(".")]
        for f in files:
            if f.endswith(".sh"):
                out.append(os.path.relpath(os.path.join(root, f), pkg_dir))
    return sorted(out)


def convert(pkg_dir):
    data = os.path.join(pkg_dir, "NEMESIS.data")
    if not os.path.isfile(data):
        sys.exit("no NEMESIS.data in " + pkg_dir)
    secs = ini(data)
    head = secs.get("nemesis", {})
    look = []
    mid = mod_id(head.get("id", os.path.basename(os.path.abspath(pkg_dir))))
    if mid != head.get("id"):
        look.append("id: NEMESIS id \"%s\" becomes \"%s\" (HMG-API ids: a-z 0-9 -)" % (head.get("id"), mid))
    ver, why = version(head.get("version"))
    if why:
        look.append(why)
    mod = {"id": mid, "name": head.get("name", mid), "version": ver, "hmgapi": ">=1.0.0",
           "description": "", "live": True}
    apps, launcher, services = [], [], []
    for a in numbered(secs, "app"):
        kind = a.get("kind", "sh")
        comp = a.get("component")
        pkg = a.get("package") or (comp.split("/")[0] if comp else None)
        if kind == "apk":
            if not pkg:
                look.append("app %s: no package name; add \"package\" to its apps entry" % a.get("path"))
                pkg = "TODO.package.name"
            apps.append({"apk": a.get("path"), "package": pkg})
        elif kind == "sh":
            look.append("app script %s (NEMESIS Manager's launch button): HMG-API launches activities;"
                        " a script can become a star-key broadcast (provides.star_key) or a service" % a.get("path"))
        if comp:
            cpkg, cls = comp.split("/", 1)
            if cls.startswith("."):
                cls = cpkg + cls
            launcher.append({"package": cpkg, "class": cls, "title": head.get("name", mid)})
    for s in numbered(secs, "service"):
        kind = s.get("kind", "sh")
        name = re.sub(r"[^a-z0-9_-]+", "-", s.get("name", "svc").lower())[:32]
        if kind == "instance":
            look.append("service %s: kind=instance (am start/startservice %s) isn't a HMG-API service; start it from the"
                        " mod's app or a script service with `am startservice`" % (name, s.get("path")))
            continue
        mode = s.get("mode", "background")
        sv = {"name": name, "script": s.get("path"), "user": s.get("uid", "root"), "start": "boot",
              "restart": mode == "restart"}
        if mode == "once":
            look.append("service %s: mode=once (NEMESIS waits for it): here it runs once at start, nothing waits" % name)
        services.append(sv)
    if apps:
        mod["apps"] = apps
    if services:
        mod["services"] = services
    if launcher:
        mod["provides"] = {"launcher": launcher}
    for k in ("preinstall", "postinstall", "preuninstall", "postuninstall"):
        if head.get(k):
            look.append("%s=%s: HMG-API mods have no install scripts. What they do usually maps to: patches.json"
                        " (files, settings, props; undone on removal), \"apps\", \"services\", or an install check"
                        " (planned). Read it and move each step." % (k, head[k]))
    look += scan(pkg_dir, scripts(pkg_dir))
    return mod, look


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__.strip())
        return 0 if args else 2
    pkg_dir = args[0]
    out = args[args.index("--out") + 1] if "--out" in args else None
    mod, look = convert(pkg_dir)
    text = json.dumps(mod, indent=2) + "\n"
    report = "%s as an HMG-API mod (draft)\n\nmod.json:\n%s\nNotes (%d):\n%s\n" % (
        mod["id"], text, len(look), "\n".join("  - " + l for l in look) if look else "  nothing")
    if out:
        if os.path.exists(out) and os.listdir(out):
            sys.exit(out + " isn't empty")
        shutil.copytree(pkg_dir, out, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("NEMESIS.data", ".git", "scripts"))
        open(os.path.join(out, "mod.json"), "w").write(text)
        open(os.path.join(out, "DRAFT-NOTES.txt"), "w").write(report)
        print("wrote %s (mod.json, DRAFT-NOTES.txt; NEMESIS.data and install scripts left out)" % out)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())

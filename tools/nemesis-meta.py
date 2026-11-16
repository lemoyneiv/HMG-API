#!/usr/bin/env python3
"""Values for HMG-API's NEMESIS descriptors (04-Packages.md §2).

  tools/nemesis-meta.py depends <package id> [needs] [--mod <mod.json>]
      depends=: the needs (e.g. "hmgapi@>=1.0"), then hmgapi.<id> for each mod the
      mod's "requires" names, then "!<id>" for each package that
      install/nemesis/conflicts.txt (or $HMGAPI_NEMESIS_CONFLICTS) lists for this one,
      then each one in the mod's "nemesis_conflicts". In the mod SDK there's no
      conflicts.txt: only the mod's list.
"""
import json, os, re, subprocess, sys

ID = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}")
MOD_ID = re.compile(r"[a-z0-9][a-z0-9-]{0,63}")   # tools/mknemesismod.sh, the engine's


def conflicts(pkg, mod=None):
    path = os.environ.get("HMGAPI_NEMESIS_CONFLICTS") or "install/nemesis/conflicts.txt"
    out = []
    lines = open(path) if os.environ.get("HMGAPI_NEMESIS_CONFLICTS") or os.path.isfile(path) else []
    for n, line in enumerate(lines, 1):
        line = line.split("#", 1)[0].split()
        if not line:
            continue
        if len(line) != 2:
            sys.exit("%s:%d: wanted <package id> <ids>" % (path, n))
        ids = line[1].split(",")
        for i in [line[0]] + ids:
            if not ID.fullmatch(i) or ".." in i:
                sys.exit("%s:%d: %r isn't a NEMESIS package id" % (path, n, i))
        if line[0] == pkg:
            out += [i for i in ids if i not in out]
    if mod:
        ids = json.load(open(mod)).get("nemesis_conflicts", [])
        if not isinstance(ids, list):
            sys.exit('%s: "nemesis_conflicts" is a list of NEMESIS package ids' % mod)
        for i in ids:
            if not isinstance(i, str) or not ID.fullmatch(i) or ".." in i:
                sys.exit('%s: "nemesis_conflicts": %r isn\'t a NEMESIS package id' % (mod, i))
            if i not in out:
                out.append(i)
    if pkg in out:
        sys.exit("%s: %s can't refuse itself" % (mod or path, pkg))
    return out


def nemesis_version(v):
    """tools/nemesis-version.py (beside this file, here and in the mod SDK's bin/)"""
    r = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "nemesis-version.py"), v],
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    if r.returncode:
        raise ValueError(r.stderr.strip() or "no NEMESIS package-version for %s" % v)
    return r.stdout.strip()


def floor(rng):
    """The lowest version a range (hmgapi.engine.Version) lets in, as x.y.z[-tag], or None
    when it has no lower bound; and whether NEMESIS's ">=" says all of the range."""
    low, whole = None, True
    for part in (rng or "").split():
        if part == "*":
            continue
        op = next((o for o in (">=", "<=", ">", "<", "=", "^", "~") if part.startswith(o)), "")
        v = part[len(op):].strip()
        v = v[1:] if v.startswith("v") else v
        v = v.split("+", 1)[0]
        num, dash, tag = v.partition("-")
        n = num.split(".")[:3]
        if not all(re.fullmatch(r"\d+", x) for x in n):
            raise ValueError("%r isn't a version range" % rng)
        v = ".".join(str(int(x)) for x in n + ["0"] * (3 - len(n))) + (dash + tag if tag else "")
        if op != ">=":
            whole = False   # an exact version, ^, ~, > (as >=), an upper bound
        if op in ("<", "<="):
            continue
        if low is None or vkey(nemesis_version(v)) > vkey(nemesis_version(low)):
            low = v
    return low, whole


def vkey(pv):   # native/meta.c ver_cmp: dotted numbers, missing parts 0
    k = [int(x) for x in pv.split(".")]
    while k and not k[-1]:
        k.pop()
    return k


def requires(pkg, mod):
    """hmgapi.<id>@>=<floor> for each mod in the mod's "requires" (each id once, the highest floor),
    but for an optional one ({"id": ..., "optional": true}): the mod runs without it"""
    m = json.load(open(mod))
    reqs = m.get("requires", [])
    if not isinstance(reqs, list):
        sys.exit('mod.json "requires" is a list of mod ids, each with an optional version range')
    floors = {}
    for d in reqs:
        if isinstance(d, dict):   # as hmgapi.engine.Version.deps
            rid, rng = str(d.get("id")), d.get("version")
            rng = None if rng is None else str(rng)
        else:
            rid, _, rng = str(d).strip().partition(" ")
        if rid == "hmgapi":
            sys.exit('mod.json "requires": hmgapi is HMG-API itself: its version range goes in "hmgapi"')
        if not MOD_ID.fullmatch(rid) or len("hmgapi." + rid) > 64:
            sys.exit('mod.json "requires": %r isn\'t a mod id (a-z, 0-9 and -, at most 57 characters)' % rid)
        if "hmgapi." + rid == pkg:
            sys.exit('mod.json "requires": %s can\'t require itself' % rid)
        if isinstance(d, dict) and d.get("optional") is True:   # as Version.deps: true only
            continue
        try:
            low, whole = floor(rng)
            pv = nemesis_version(low) if low else None
        except ValueError as x:
            sys.exit('mod.json "requires": "%s %s": %s' % (rid, (rng or "").strip(), x))
        # NEMESIS's depends only checks a floor here ("id@>=x"): what else the range says
        # (^, ~, an exact version, an upper bound) stays HMG-API's own check, as for a mod
        # installed from the app. An upper bound in NEMESIS would also refuse every update of
        # the required mod's package past it (exit 21).
        if not whole:
            print("note: %s requires %s %s; its NEMESIS package checks only %s, HMG-API checks the rest"
                  % (pkg, rid, rng.strip(), "hmgapi.%s@>=%s" % (rid, pv) if pv else "that hmgapi.%s is installed" % rid),
                  file=sys.stderr)
        if rid not in floors or pv and (floors[rid] is None or vkey(pv) > vkey(floors[rid])):
            floors[rid] = pv
    return ["hmgapi.%s@>=%s" % (r, pv) if pv else "hmgapi." + r for r, pv in floors.items()]


def depends(pkg, needs="", mod=None):
    items = [x for x in needs.split(",") if x]
    req = [x for x in (requires(pkg, mod) if mod else []) if x.split("@")[0] not in {i.split("@")[0] for i in items}]
    items += req
    need_ids = {x.split("@")[0] for x in items}
    refused = conflicts(pkg, mod)
    bad = need_ids & set(refused)
    if bad:   # NEMESIS refuses such a package too (05-Packages.md section 2)
        why = ""
        if bad & {x.split("@")[0] for x in req}:
            why = ': it\'s in "requires", and "nemesis_conflicts" or conflicts.txt refuses its package'
        sys.exit("%s both needs and refuses %s%s" % (pkg, ", ".join(sorted(bad)), why))
    d = ",".join(items + ["!" + i for i in refused])
    if len(d) > 511:   # native/meta.c: depends[512]
        sys.exit("depends= is %d characters, NEMESIS takes at most 511" % len(d))
    return d



if __name__ == "__main__":
    a = sys.argv[1:]
    mod = None
    if len(a) >= 2 and a[-2] == "--mod":
        mod, a = a[-1], a[:-2]
    if a[:1] == ["depends"] and len(a) in (2, 3):
        print(depends(a[1], a[2] if len(a) == 3 else "", mod))
    else:
        sys.exit(__doc__)

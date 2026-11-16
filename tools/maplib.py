#!/usr/bin/env python3
"""Hook maps as the repo keeps them: one base map plus each build's differences.

The names a hook point resolves to hardly change between system builds (docs/research/base-map.md),
so the repo keeps them once:

  maps/base/<app>.json        the entry each point has on most builds
  maps/<make>/<model>/<region>/<firmware>/car.json
      "names":       {point: entry}  where this build's entry differs from base
      "unconfirmed": [point, ...]    smali-only methods this build has no code signature for
                                     (mapped before #233): base's entry without its "sig"
      "missing":     {point: why}    as before: not in this build's map
      everything else as before (products, files, status, apps, app_files, ...)

A build's full map, the per-app files HMG-API reads on the car, is base + names - missing,
for the apps in "apps". build.sh writes those files into the payload (expand), so the unit,
the mod SDK and #232's local maps read exactly what they read before.

usage: maplib.py expand <out dir> [<maps dir>]   full per-app files for every build
       maplib.py compact [<maps dir>]            per-app files -> base + differences (in place)
       maplib.py check [<maps dir>]              compact -> expand gives the same maps
"""
import collections, copy, glob, json, os, shutil, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
MAPS = os.path.join(REPO, 'maps')
CARS = '*/*/*/*/car.json'


def dump(obj, path):
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')


def folders(maps=MAPS):
    """every build's map folder, sorted"""
    return sorted(os.path.dirname(p) for p in glob.glob(os.path.join(maps, CARS)) if '/base/' not in p)


def app_of(pid):
    return pid.split('.')[0]


def base(maps=MAPS):
    out = {}
    for f in glob.glob(os.path.join(maps, 'base', '*.json')):
        out[os.path.basename(f)[:-5]] = json.load(open(f))
    return out


def compact_form(folder):
    """True when the folder keeps only car.json (base + names), not per-app files"""
    car = json.load(open(os.path.join(folder, 'car.json')))
    return 'names' in car or not any(os.path.isfile(os.path.join(folder, a + '.json')) for a in car.get('apps', []))


def load(folder, maps=MAPS, b=None):
    """(car.json without names/unconfirmed, {app: {point: entry}}): a build's full map, either form"""
    car = json.load(open(os.path.join(folder, 'car.json')))
    apps = {}
    if not compact_form(folder):
        for a in car.get('apps', []):
            p = os.path.join(folder, a + '.json')
            apps[a] = json.load(open(p)) if os.path.isfile(p) else {}
        return car, apps
    b = base(maps) if b is None else b
    names, unconfirmed = car.pop('names', {}), set(car.pop('unconfirmed', []))
    missing = car.get('missing', {})
    for a in car.get('apps', []):
        entries = {}
        for pid, e in b.get(a, {}).items():
            if pid in missing:
                continue
            e = copy.deepcopy(names.get(pid, e))
            if pid in unconfirmed:
                e.pop('sig', None)
            entries[pid] = e
        for pid, e in names.items():
            if app_of(pid) == a and pid not in entries and pid not in missing:
                entries[pid] = copy.deepcopy(e)
        apps[a] = dict(sorted(entries.items()))
    return car, apps


def entries(folder, maps=MAPS):
    """{point: entry} over all of a build's apps"""
    out = {}
    for e in load(folder, maps)[1].values():
        out.update(e)
    return out


def expand(out, maps=MAPS):
    """every build as today's per-app files under out/ (plus maps/ files that aren't builds)"""
    b = base(maps)
    for folder in folders(maps):
        car, apps = load(folder, maps, b)
        d = os.path.join(out, os.path.relpath(folder, maps))
        os.makedirs(d, exist_ok=True)
        for a, e in apps.items():
            dump(e, os.path.join(d, a + '.json'))
        dump(car, os.path.join(d, 'car.json'))
    for f in glob.glob(os.path.join(maps, '*.json')):   # authors.json, coverage.json
        shutil.copyfile(f, os.path.join(out, os.path.basename(f)))


def strip(e):
    return {k: v for k, v in e.items() if k != 'sig'}


def choose_base(full):
    """{app: {point: entry}} from {folder: (car, apps)}: per point, the entry most builds have,
    preferring builds made from stock files (status mapped/verified with md5s from a tree) and
    entries with a code signature"""
    votes = collections.defaultdict(collections.Counter)
    best = {}
    for folder, (car, apps) in sorted(full.items()):
        for a, es in apps.items():
            for pid, e in es.items():
                k = json.dumps(strip(e), sort_keys=True)
                votes[(a, pid)][k] += 1
                if 'sig' in e or (a, pid, k) not in best:
                    best[(a, pid, k)] = e
    out = collections.defaultdict(dict)
    for (a, pid), c in votes.items():
        k = sorted(c.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
        out[a][pid] = best[(a, pid, k)]
    return {a: dict(sorted(es.items())) for a, es in out.items()}


def differences(apps, b):
    """(names, unconfirmed) of a build's full map against base"""
    names, unconfirmed = {}, []
    for a, es in apps.items():
        for pid, e in es.items():
            be = b.get(a, {}).get(pid)
            if be is None or strip(be) != strip(e):
                names[pid] = e
            elif 'sig' in be and 'sig' not in e:
                unconfirmed.append(pid)
            elif 'sig' in e and e.get('sig') != be.get('sig'):
                names[pid] = e
    return dict(sorted(names.items())), sorted(unconfirmed)


def write_compact(folder, car, apps, b):
    """a build's map in the compact form: car.json with names/unconfirmed, no per-app files"""
    names, unconfirmed = differences(apps, b)
    car = {k: v for k, v in car.items() if k not in ('names', 'unconfirmed')}
    if names:
        car['names'] = names
    if unconfirmed:
        car['unconfirmed'] = unconfirmed
    for a in apps:
        p = os.path.join(folder, a + '.json')
        if os.path.isfile(p):
            os.remove(p)
    dump(car, os.path.join(folder, 'car.json'))


def compact(maps=MAPS):
    full = {f: load(f, maps) for f in folders(maps)}
    b = choose_base(full)
    os.makedirs(os.path.join(maps, 'base'), exist_ok=True)
    for a, es in b.items():
        dump(es, os.path.join(maps, 'base', a + '.json'))
    for folder, (car, apps) in full.items():
        write_compact(folder, car, apps, b)
    return full


def check(maps=MAPS):
    """expand(compact(maps)) == maps, on a copy: True or the first difference"""
    with tempfile.TemporaryDirectory() as t:
        src = os.path.join(t, 'maps')
        shutil.copytree(maps, src)
        before = {os.path.relpath(f, src): load(f, src) for f in folders(src)}
        compact(src)
        out = os.path.join(t, 'out')
        expand(out, src)
        for rel, (car, apps) in before.items():
            c2, a2 = load(os.path.join(out, rel), out)
            if c2 != car:
                return '%s: car.json differs' % rel
            if a2 != apps:
                for a in set(apps) | set(a2):
                    if apps.get(a) != a2.get(a):
                        return '%s: %s.json differs' % (rel, a)
    return True


if __name__ == '__main__':
    a = sys.argv[1:]
    if a[:1] == ['expand'] and len(a) in (2, 3):
        expand(a[1], a[2] if len(a) > 2 else MAPS)
    elif a[:1] == ['compact']:
        compact(a[1] if len(a) > 1 else MAPS)
    elif a[:1] == ['check']:
        r = check(a[1] if len(a) > 1 else MAPS)
        print('ok    compact + expand gives the same maps' if r is True else 'FAIL  ' + r)
        sys.exit(0 if r is True else 1)
    else:
        sys.exit(__doc__)

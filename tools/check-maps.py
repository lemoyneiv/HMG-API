#!/usr/bin/env python3
"""Check the hook point catalogue and every car's map.

usage: check-maps.py [--rebuild [--only CAR[,CAR]]]

- point ids are unique; a member point's "of" is a class point
- every map (maps/<make>/<model>/<region>/<firmware>/) has every catalogue point,
  with an entry of the right kind, or lists it in car.json "missing" (with why), and
  a valid car.json
- --rebuild: maps with a stock tree on this machine ($HMGAPI_CARS, docs/car-trees.md)
  are made again with map-car.py and must come out identical; trees whose files aren't
  exactly the map's (tools/cars.py list) are skipped
- a method only a smali pattern finds carries its code signature ("sig", tools/dexsig.py).
  One without it was mapped before code signatures (often borrowed by name from another
  map on the car): listed as unconfirmed, not a failure; a Car Report or the stock files
  confirm it
Exit 1 on any problem: a new hook point has to be mapped for every car.
"""
import glob, json, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import cartrees
import maplib
problems = []
unconfirmed = {}
points = {}
for f in sorted(glob.glob(os.path.join(REPO, 'core', 'hookpoints', '*.json'))):
    cat = json.load(open(f))
    for p in cat['points']:
        if p['id'] in points:
            problems.append('point %s is in the catalogue twice' % p['id'])
        points[p['id']] = p
for p in points.values():
    if p['kind'] != 'class':
        o = points.get(p.get('of'))
        if o is None or o['kind'] != 'class':
            problems.append('%s: "of" %s isn\'t a class point' % (p['id'], p.get('of')))
KEY = {'class': 'class', 'method': 'method', 'ctor': 'ctor', 'field': 'field'}
maps = [os.path.join(f, 'car.json') for f in maplib.folders()]
for cj in maps:
    d = os.path.dirname(cj)
    rel = os.path.relpath(d, os.path.join(REPO, 'maps'))
    car = json.load(open(cj))
    for k in ('make', 'model', 'region', 'firmware', 'products', 'status', 'files', 'apps', 'app_files'):
        if k not in car:
            problems.append('%s: car.json has no %s' % (rel, k))
    if car.get('status') not in ('mapped', 'probed', 'verified'):
        problems.append('%s: status %r (mapped, probed or verified)' % (rel, car.get('status')))
    if car.get('status') == 'verified' and not car.get('verified'):
        problems.append('%s: verified, but no verified record (who, when, report)' % rel)
    entries = maplib.entries(d)
    for pid in car.get('names', {}):
        if pid not in points:
            problems.append('%s: names has %s, which isn\'t in the catalogue' % (rel, pid))
    missing = car.get('missing', {})
    for pid, p in points.items():
        e = entries.get(pid)
        if e is None and pid not in missing:
            problems.append('%s: no %s (map it, or run map-car.py again to list it as missing)' % (rel, pid))
        elif e is not None and pid in missing:
            problems.append('%s: %s is both mapped and missing' % (rel, pid))
        elif e is None:
            continue
        elif KEY[p['kind']] not in e:
            problems.append('%s: %s should be a %s' % (rel, pid, p['kind']))
        elif p['kind'] == 'method' and ('pattern' in p['find'] or 'code' in p['find']):
            if 'sig' not in e:
                unconfirmed.setdefault(rel, []).append(pid)
            elif not re.fullmatch(r'[0-9a-f]{16}', str(e['sig'])):
                problems.append('%s: %s has a malformed sig %r' % (rel, pid, e['sig']))
    for pid in list(entries) + list(missing):
        if pid not in points:
            problems.append('%s: %s isn\'t in the catalogue' % (rel, pid))
if '--rebuild' in sys.argv:
    only = sys.argv[sys.argv.index('--only') + 1] if '--only' in sys.argv[:-1] else None
    for t in cartrees.pick(only):
        folder, tree, car = t.id, t.path, t.map
        if car is None:
            continue
        m = cartrees.match(t)[0]
        if m != 'exact':
            print('skip  %s: %s tree (tools/cars.py list)' % (folder, m))
            continue
        with tempfile.TemporaryDirectory() as t:
            mk, mo, rg, fw = folder.split('/')
            r = subprocess.run([sys.executable, os.path.join(HERE, 'map-car.py'), tree, '--make', mk, '--model', mo,
                                '--region', rg, '--firmware', fw, '--products', ','.join(car['products']),
                                '--name', car['name'], '--maps', t, '--work', os.path.join(t, 'work')],
                               capture_output=True, text=True)
            if r.returncode:
                problems.append('%s: map-car.py failed: %s' % (folder, r.stderr.strip()[-200:]))
                continue
            ca, aa = maplib.load(os.path.join(t, folder), t)
            cb, ab = maplib.load(os.path.join(REPO, 'maps', folder))
            ca = {k: v for k, v in ca.items() if k not in ('status', 'verified')}
            cb = {k: v for k, v in cb.items() if k not in ('status', 'verified')}
            if ca != cb:
                problems.append('%s: car.json rebuilt differently from the stock tree' % folder)
            for app in sorted(set(aa) | set(ab)):
                if aa.get(app) != ab.get(app):
                    problems.append('%s: %s rebuilt differently from the stock tree' % (folder, app))
            print('ok    rebuilt %s from %s' % (folder, tree))
for rel, ids in sorted(unconfirmed.items()):
    print('note  %s: %d smali-only method(s) mapped before code signatures, unconfirmed (a Car Report or the stock'
          ' files confirm them): %s' % (rel, len(ids), ', '.join(sorted(ids))))
for p in problems:
    print('FAIL  ' + p)
print('%d hook points, %d car map(s)%s' % (len(points), len(maps), '' if problems else ': all complete'))
sys.exit(1 if problems else 0)

#!/usr/bin/env python3
"""Make a car's hook map from Car Reports (the map the car made of itself).

usage: report-map.py <car-report.json>... [--maps DIR] [--status probed]
                     [--make hyundai] [--name "Ioniq 6"]

Each report holds the on-car mapper's result ("map_car": points, the md5 of every
file the names came from, what it didn't find). Give one or more reports from the
same model, region and firmware: their points must agree, and every file md5 must be
the same in a majority of them (a car whose apps were changed by another package
reports other md5s; it's outvoted, and named). Writes
maps/<make>/<model>/<region>/<firmware>/ in map-car.py's format, status "probed".
Use --make when an older HMG-API did not know the model's make; --name can supply
its display name. Names and md5s only: nothing else from the reports is kept.
"""
import argparse, collections, glob, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import maplib  # noqa: E402


def mapping(value, label):
    if not isinstance(value, dict):
        sys.exit('%s: expected an object' % label)
    return value


def identity(unit, make, label):
    make = make or unit.get('make')
    if not isinstance(make, str) or make.lower() not in ('hyundai', 'kia', 'genesis'):
        sys.exit('%s: unknown make %r; supply --make hyundai, kia or genesis' % (label, make))
    result = [make.lower()]
    for key in ('model', 'region', 'firmware'):
        value = unit.get(key)
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]*', value):
            sys.exit('%s: invalid or missing unit.%s' % (label, key))
        result.append(value)
    return tuple(result)


def missing_points(mc, label):
    # Car Reports use not_found; older mapper-shaped reports used missing.
    missing = {}
    for key in ('missing', 'not_found'):
        for pid, reason in mapping(mc.get(key, {}), '%s: map_car.%s' % (label, key)).items():
            if not isinstance(reason, str) or not reason:
                sys.exit('%s: no reason for missing point %s' % (label, pid))
            missing[pid] = reason
    # Report.java groups missing stock files by app, rather than repeating the
    # reason for each point. Restore those entries for car.json/check-maps.py.
    absent = mapping(mc.get('apps_absent', {}), '%s: map_car.apps_absent' % label)
    if absent:
        catalogue = {}
        for f in glob.glob(os.path.join(REPO, 'core', 'hookpoints', '*.json')):
            cat = json.load(open(f))
            catalogue[cat['app']] = [p['id'] for p in cat['points']]
        for app, reason in absent.items():
            if app not in catalogue or app not in mc['app_files']:
                sys.exit('%s: unknown absent app %s' % (label, app))
            if not isinstance(reason, str) or not reason:
                sys.exit('%s: no reason for absent app %s' % (label, app))
            for pid in catalogue[app]:
                missing[pid] = reason
    overlap = set(missing).intersection(mc['points'])
    if overlap:
        sys.exit('%s: points are both found and missing: %s' % (label, ', '.join(sorted(overlap))))
    return missing


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('reports', nargs='+')
    ap.add_argument('--maps', default=os.path.join(REPO, 'maps'))
    ap.add_argument('--status', default='probed', choices=('probed', 'mapped'))
    ap.add_argument('--make', choices=('hyundai', 'kia', 'genesis'), help='override an unknown report make')
    ap.add_argument('--name', help='override the model display name')
    a = ap.parse_args()
    reps = []
    for f in a.reports:
        try:
            r = mapping(json.load(open(f)), f)
        except (OSError, ValueError) as e:
            sys.exit('%s: %s' % (f, e))
        if r.get('report') != 'hmgapi car report 1' or 'map_car' not in r:
            sys.exit('%s: not a Car Report with an on-car map' % f)
        unit = mapping(r.get('unit'), '%s: unit' % f)
        identity(unit, a.make, f)
        mc = mapping(r['map_car'], '%s: map_car' % f)
        for key in ('points', 'files', 'app_files'):
            mapping(mc.get(key), '%s: map_car.%s' % (f, key))
        for pid in mc['points']:
            if pid.split('.')[0] not in mc['app_files']:
                sys.exit('%s: point %s has no app_files entry' % (f, pid))
        for path, md5 in mc['files'].items():
            if not isinstance(md5, str) or not re.fullmatch(r'[0-9a-fA-F]{32}', md5):
                sys.exit('%s: invalid file md5 for %s' % (f, path))
        missing_points(mc, f)
        reps.append((os.path.basename(f), r))
    u = reps[0][1]['unit']
    key = lambda r: identity(r['unit'], a.make, 'unit')
    for name, r in reps:
        if key(r) != key(reps[0][1]):
            sys.exit('%s is %s, not %s' % (name, '.'.join(key(r)), '.'.join(key(reps[0][1]))))
    mc = reps[0][1]['map_car']
    for name, r in reps[1:]:
        if r['map_car']['points'] != mc['points']:
            sys.exit('%s: its points differ from %s; map them separately' % (name, reps[0][0]))
    files = {}
    for path in sorted(set().union(*[r['map_car']['files'] for _, r in reps])):
        votes = collections.Counter(r['map_car']['files'].get(path) for _, r in reps)
        md5, n = votes.most_common(1)[0]
        if md5 is None or n * 2 <= len(reps):
            sys.exit('%s: no majority md5 (%s)' % (path, dict(votes)))
        files[path] = md5
        for name, r in reps:
            if r['map_car']['files'].get(path) != md5:
                print('outvoted: %s %s is %s' % (name, path, r['map_car']['files'].get(path)))
    by_app = {app: {} for app in mc['app_files']}
    for pid, e in mc['points'].items():
        by_app[pid.split('.')[0]][pid] = e
    make, model, region, firmware = key(reps[0][1])
    missing = missing_points(mc, reps[0][0])
    # catalogue points newer than the HMG-API that made the reports: listed, not guessed
    for f in sorted(glob.glob(os.path.join(REPO, 'core', 'hookpoints', '*.json'))):
        for p in json.load(open(f))['points']:
            if p['id'] not in mc['points'] and p['id'] not in missing:
                missing[p['id']] = ('not in the Car Report: newer than the HMG-API that made it '
                                    '(a Car Report from a newer HMG-API maps it)')
    d = os.path.join(a.maps, make, model.lower(), region.lower(), firmware)
    os.makedirs(d, exist_ok=True)
    car = {
        'make': make, 'model': u['model'].lower(), 'name': a.name or u.get('name') or u['model'],
        'region': u['region'].lower(), 'firmware': u['firmware'],
        'products': ['%s.%s.' % (u['model'], u['region'])],
        'status': a.status, 'verified': None,
        'files': files,
        'apps': sorted(by_app),
        'app_files': mc['app_files'],
    }
    if missing:
        car['missing'] = dict(sorted(missing.items()))
    if mc.get('by_reference'):
        car['by_reference'] = sorted(mc['by_reference'])
    base = maplib.base(a.maps)
    if base:   # the repo: base + this build's differences (tools/maplib.py)
        maplib.write_compact(d, car, {app: dict(sorted(e.items())) for app, e in by_app.items()}, base)
    else:
        for app, entries in by_app.items():
            maplib.dump(dict(sorted(entries.items())), os.path.join(d, app + '.json'))
        maplib.dump(car, os.path.join(d, 'car.json'))
    print('%s: %d points, %d missing, from %d report(s)' % (os.path.relpath(d, REPO), len(mc['points']), len(missing), len(reps)))


if __name__ == '__main__':
    main()

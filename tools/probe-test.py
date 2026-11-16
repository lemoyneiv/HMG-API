#!/usr/bin/env python3
"""The on-car mapper (hmgapi.engine.MapProbe, `engine map-car`) against tools/map-car.py,
on every stock tree in $HMGAPI_CARS (docs/car-trees.md): both must find the same points with the same
names. MapProbe reads the dex in each .odex instead of baksmali's output; points that
only a smali pattern finds, it takes "by reference" from the committed maps of other
cars (the car's own map is left out, so that path is really tested).

usage: probe-test.py [--only CAR[,CAR]]
"""
import argparse, glob, json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from importlib import import_module
th = import_module('test-hooks')
import maplib  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--only')
    a = ap.parse_args()
    cars = th.trees()
    if a.only:
        cars = [c for c in cars if c[0] in a.only.split(',')]
    if not cars:
        print('ok    no stock trees (docs/car-trees.md): the on-car mapper isn\'t compared')
        return
    problems = []
    with tempfile.TemporaryDirectory(prefix='hmgapi-probe-') as t:
        cp = os.path.join(t, 'cp')
        os.makedirs(cp)
        subprocess.run(['javac', '--release', '8', '-Xlint:-options', '-nowarn', '-d', cp]
                       + glob.glob(os.path.join(REPO, 'core', 'runtime', 'java', 'hmgapi', 'engine', '*.java')),
                       check=True, stderr=subprocess.DEVNULL)
        for label, tree in cars:
            # map-car.py's answer: a partial map in a scratch maps dir
            ref = os.path.join(t, 'ref-' + label)
            r = subprocess.run([sys.executable, os.path.join(HERE, 'map-car.py'), tree, '--make', 'x', '--model', 'x',
                                '--region', 'x', '--firmware', 'x', '--products', 'X.', '--maps', ref],
                               capture_output=True, text=True)
            if r.returncode:
                problems.append('%s: map-car.py: %s' % (label, r.stderr.strip()[-200:]))
                continue
            d = os.path.join(ref, 'x', 'x', 'x', 'x')
            want, want_missing = {}, json.load(open(os.path.join(d, 'car.json'))).get('missing', {})
            for f in glob.glob(os.path.join(d, '*.json')):
                if not f.endswith('car.json'):
                    want.update(json.load(open(f)))
            # the committed maps of other cars, as references
            maps = os.path.join(t, 'maps-' + label)
            maplib.expand(maps)   # the per-app files the unit reads (the repo keeps base + differences)
            if os.path.isfile(os.path.join(maps, 'coverage.json')):
                os.remove(os.path.join(maps, 'coverage.json'))
            if os.path.isfile(os.path.join(maps, label, 'car.json')):   # the car's own map
                shutil.rmtree(os.path.join(maps, label))
            r = subprocess.run(['java', '-cp', cp, 'hmgapi.engine.MapProbe', tree, os.path.join(REPO, 'core', 'hookpoints'), maps],
                               capture_output=True, text=True)
            if r.returncode:
                problems.append('%s: MapProbe: %s' % (label, r.stderr.strip()[-300:]))
                continue
            got = json.loads(r.stdout)
            pts, miss, byref = got['points'], got['missing'], got['by_reference']
            diff = 0
            # smali-only points with no other car's map to borrow names from, or whose code several
            # methods share: missing, as expected
            noref = {pid for pid, why in miss.items() if pid in want and ('no other car' in why or why.startswith('needs ')
                                                                           or "needs this car's map" in why)}
            for pid in sorted(set(want) | set(pts)):
                if pid in noref:
                    continue
                if want.get(pid) != pts.get(pid):
                    diff += 1
                    if diff <= 8:
                        problems.append('%s: %s: map-car %s, MapProbe %s' % (label, pid, want.get(pid) or 'missing (%s)' % want_missing.get(pid),
                                                                           pts.get(pid) or 'missing (%s)' % miss.get(pid)))
            print('%s    %s: %d points the same as map-car.py, %d by reference, %d need another car\'s map, %d missing in both' % (
                'ok  ' if not diff else 'FAIL', label, len(pts) - diff, len(byref), len(noref), len(set(miss) & set(want_missing))))
    for p in problems:
        print('FAIL  ' + p)
    sys.exit(1 if problems else 0)


if __name__ == '__main__':
    main()

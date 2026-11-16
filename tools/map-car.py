#!/usr/bin/env python3
"""Map every HMG-API hook point for one car, from its stock files.

usage: map-car.py <stock tree> --make kia --model ev6 --region usa --firmware 260507 \\
                  --products CVEV.USA. [--name "Kia EV6"] [--maps DIR] [--work DIR] [--strict]
       map-car.py <stock tree> --matrix          one line per point: found, missing (why); writes nothing

  <stock tree>  the car's stock /system: app/*.odex and framework/*.odex (a full kit,
                or part of one: apps that aren't in it are left out)

Reads the catalogue (core/hookpoints/*.json), decompiles each app it names (and the
framework jars) with baksmali, and finds every point by its shape: it must match
exactly once. A point that doesn't is left out of the map with the reason
(car.json "missing"), and so is every point of an app whose stock files aren't in
the tree; HMG-API then runs without the features and mods that need them. --strict:
any missing point is an error and nothing is written.
Writes maps/<make>/<model>/<region>/<firmware>/: car.json (status "mapped", the md5
of every stock file the names came from) and one <app>.json per catalogue file.
Names only: no app code is copied. A map that comes out the same as before keeps its
status ("verified" stays verified); a changed one goes back to "mapped".

Needs java with baksmali 2.5.2 (~/.local/share/smali or BAKSMALI).
"""
import argparse, glob, hashlib, json, os, re, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import dexsig  # noqa: E402
import maplib  # noqa: E402
BAKSMALI = os.environ.get('BAKSMALI', os.path.expanduser('~/.local/share/smali/baksmali-2.5.2.jar'))
BCP = ['core', 'conscrypt', 'okhttp', 'core-junit', 'bouncycastle', 'ext', 'framework', 'framework2',
       'telephony-common', 'voip-common', 'mms-common', 'android.policy', 'services', 'apache-xml',
       'webviewchromium', 'automotive']
PRIM = {'V': 'void', 'Z': 'boolean', 'B': 'byte', 'S': 'short', 'C': 'char', 'I': 'int', 'J': 'long', 'F': 'float', 'D': 'double'}


class MapError(Exception):
    pass


def jtype(d):
    """smali descriptor -> Java type name"""
    n = 0
    while d.startswith('['):
        n += 1
        d = d[1:]
    t = PRIM[d] if d in PRIM else d[1:-1].replace('/', '.')
    return t + '[]' * n


def split_params(s):
    out, i = [], 0
    while i < len(s):
        j = i
        while s[j] == '[':
            j += 1
        if s[j] == 'L':
            j = s.index(';', j)
        out.append(jtype(s[i:j + 1]))
        i = j + 1
    return out


class Cls:
    def __init__(self, name):
        self.name, self.sup, self.fields, self.methods, self.strings, self.code = name, None, [], [], set(), ''


def parse(root):
    """all classes under a baksmali output dir: {java name: Cls}"""
    classes = {}
    for p in glob.glob(os.path.join(root, '**', '*.smali'), recursive=True):
        c = None
        code = []
        mcode = None
        for line in open(p, encoding='utf-8', errors='replace'):
            s = line.strip()
            if s.startswith('.class '):
                c = Cls(jtype(s.split()[-1]))
            elif s.startswith('.super '):
                c.sup = jtype(s.split()[-1])
            elif s.startswith('.field '):
                w = s.split(' = ')[0].split()
                name, typ = w[-1].split(':', 1)
                c.fields.append({'name': name, 'type': jtype(typ), 'static': 'static' in w})
            elif s.startswith('.method '):
                w = s.split()
                m = re.match(r'(.+)\((.*)\)(.+)', w[-1])
                c.methods.append({'name': m.group(1), 'params': split_params(m.group(2)), 'ret': jtype(m.group(3)),
                                  'static': 'static' in w, 'code': ''})
                mcode = []
            elif s.startswith('.end method'):
                if c.methods and mcode is not None:
                    c.methods[-1]['code'] = '\n'.join(mcode) + '\n'
                mcode = None
            elif s.startswith('const-string'):
                m = re.search(r'"(.*)"$', s)
                if m:
                    c.strings.add(m.group(1))
            if s and not s.startswith('.line') and not s.startswith('#'):
                code.append(s)
                if mcode is not None:
                    mcode.append(s)
        if c:
            c.code = '\n'.join(code) + '\n'
            classes[c.name] = c
    return classes


def deodex(odex, fw, out):
    if not os.path.isdir(out):
        if odex.endswith('.jar'):   # a library that isn't odexed: its jar holds a plain classes.dex
            cmd = ['java', '-jar', BAKSMALI, 'd', '-o', out, odex]
        else:
            cmd = ['java', '-jar', BAKSMALI, 'x', '-a', '18', '-d', fw, '-b', ':'.join(j + '.odex' for j in BCP), '-o', out, odex]
        subprocess.run(cmd, check=True, stderr=subprocess.DEVNULL)
    return out


class Mapper:
    def __init__(self, tree, work):
        self.tree, self.work = tree, work
        self.sigs = {}
        self.fw = os.path.join(tree, 'framework')
        self.framework = {}
        for j in BCP:
            o = os.path.join(self.fw, j + '.odex')
            if os.path.isfile(o):
                self.framework.update(parse(deodex(o, self.fw, os.path.join(work, 'fw-' + j))))
        self.found = {}   # point id -> resolved (class name, or member dict)

    def local(self, path):
        """/system/app/X.odex -> <tree>/app/X.odex"""
        return os.path.join(self.tree, path[len('/system/'):])

    def resolve_type(self, t):
        if t.startswith('@'):
            base, arr = t[1:], ''
            while base.endswith('[]'):
                base, arr = base[:-2], arr + '[]'
            if base not in self.found:
                raise MapError('needs %s first' % base)
            return self.found[base] + arr
        return t

    def classes_for(self, cat):
        cls = dict(self.framework)
        for f in cat['files']:
            if cat['package'] is None and os.path.basename(f)[:-len('.odex')] in BCP:
                if not os.path.isfile(self.local(f)):
                    raise MapError('no stock %s in the tree' % f)
                continue   # already in self.framework; the rest are framework libraries (com.hkmc.ext.hcan)
            local = self.local(f)
            if not os.path.isfile(local):
                raise MapError('no stock %s in the tree' % f)
            cls.update(parse(deodex(local, self.fw, os.path.join(self.work, os.path.basename(f)))))
        return cls

    def class_matches(self, c, f):
        if 'name' in f and c.name != f['name']:
            return False
        if 'package' in f and not c.name.startswith(f['package'] + '.'):
            return False
        if 'super' in f and c.sup != f['super']:
            return False
        for ct in f.get('ctors', []):
            want = [self.resolve_type(t) for t in ct]
            if not any(m['name'] == '<init>' and m['params'] == want for m in c.methods):
                return False
        for fs in f.get('fields', []):
            if not any(self.field_ok(x, fs) for x in c.fields):
                return False
        for ms in f.get('methods', []):
            if not any(self.method_ok(x, ms) for x in c.methods):
                return False
        for s in f.get('strings', []):
            if s not in c.strings:
                return False
        return True

    def field_ok(self, x, fs):
        if 'name' in fs and x['name'] != fs['name']:
            return False
        if 'type' in fs and x['type'] != self.resolve_type(fs['type']):
            return False
        if 'static' in fs and x['static'] != fs['static']:
            return False
        return True

    def method_ok(self, x, ms):
        if x['name'] in ('<init>', '<clinit>'):
            return False
        if 'name' in ms and x['name'] != ms['name']:
            return False
        if 'params' in ms and x['params'] != [self.resolve_type(t) for t in ms['params']]:
            return False
        if 'ret' in ms and x['ret'] != self.resolve_type(ms['ret']):
            return False
        if 'static' in ms and x['static'] != ms['static']:
            return False
        for st in ms.get('strings', []):   # const-strings in the method's own code
            if 'const-string' not in x['code'] or ('"%s"' % st) not in x['code']:
                return False
        if 'code' in ms and not re.search(ms['code'], x['code']):
            return False
        return True

    def pattern(self, cat, classes, name, cache={}):
        key = (cat['app'], name)
        if key not in cache:
            p = cat['patterns'][name]
            c = classes.get(self.resolve_type(p['in']))   # a class name, or @<class point>
            if c is None:
                raise MapError('pattern %s: no class %s' % (name, p['in']))
            m = list(re.finditer(p['regex'], c.code))
            if len(m) != 1:
                raise MapError('pattern %s: matched %d times in %s (must be once)' % (name, len(m), p['in']))
            cache[key] = m[0].groupdict()
        return cache[key]

    def point(self, cat, classes, pt):
        f, kind = pt['find'], pt['kind']
        if kind == 'class':
            if 'pattern' in f:
                g = self.pattern(cat, classes, f['pattern'])[f['class_group']].replace('/', '.')   # may be a sub-package path (n/v)
                name = f['package'] + '.' + g
                if name not in classes:
                    raise MapError('%s: %s not found' % (pt['id'], name))
                return name
            hits = [c.name for c in classes.values() if self.class_matches(c, f)]
            if len(hits) != 1:
                raise MapError('%s: %d classes match (%s)' % (pt['id'], len(hits), ', '.join(sorted(hits)[:5])))
            return hits[0]
        owner = self.found.get(pt['of'])
        if owner is None:
            raise MapError('%s: its class %s isn\'t mapped' % (pt['id'], pt['of']))
        c = classes[owner]
        if kind == 'field':
            spec = dict(f)
            if 'pattern' in f:
                spec['name'] = self.pattern(cat, classes, f['pattern'])[f['group']]
            hits = [x for x in c.fields if self.field_ok(x, spec)]
            if len(hits) != 1:
                raise MapError('%s: %d fields match in %s' % (pt['id'], len(hits), owner))
            return {'field': hits[0]['name'], 'type': hits[0]['type'], 'static': hits[0]['static']}
        if kind == 'ctor':
            want = [self.resolve_type(t) for t in f['params']]
            if not any(m['name'] == '<init>' and m['params'] == want for m in c.methods):
                raise MapError('%s: no such constructor in %s' % (pt['id'], owner))
            return {'ctor': want}
        if kind == 'method':
            spec = dict(f)
            if 'pattern' in f:
                spec['name'] = self.pattern(cat, classes, f['pattern'])[f['group']]
            hits = [x for x in c.methods if self.method_ok(x, spec)]
            if len(hits) != 1:
                raise MapError('%s: %d methods match in %s' % (pt['id'], len(hits), owner))
            if f.get('only_caller'):
                ref = '%s->%s(' % ('L' + owner.replace('.', '/') + ';', hits[0]['name'])
                callers = [k for k, v in classes.items() if ref in v.code and k != owner]
                if callers != [cat['patterns'][f['pattern']]['in']]:
                    raise MapError('%s: called from %s, not only the menu' % (pt['id'], callers))
            h = hits[0]
            return {'method': h['name'], 'params': h['params'], 'ret': h['ret'], 'static': h['static']}
        raise MapError('%s: unknown kind %s' % (pt['id'], kind))

    def sig(self, cat, cls, r):
        """a method's code signature (tools/dexsig.py), kept on smali-only method points: the on-car
        mapper borrows another car's name for one only when the code is the same"""
        key = (cls, r['method'], tuple(r['params']))
        for f in cat['files']:
            if not os.path.isfile(self.local(f)):
                continue
            if f not in self.sigs:
                self.sigs[f] = dexsig.sigs(self.local(f))
            if key in self.sigs[f]:
                return self.sigs[f][key]
        raise MapError('%s.%s: no code in %s' % (cls, r['method'], ', '.join(cat['files'])))

    def map_catalogue(self, cat, missing=None):
        """the catalogue's points found in the tree. missing: a dict to collect {point: why}
        for the ones that aren't (partial); None: the first one is an error (strict)"""
        try:
            classes = self.classes_for(cat)
        except MapError as e:
            if missing is None:
                raise MapError('%s: %s' % (cat['app'], e))
            for pt in cat['points']:
                missing[pt['id']] = str(e)
            return {}
        out = {}
        pending = list(cat['points'])
        while pending:
            progress = False
            for pt in list(pending):
                try:
                    r = self.point(cat, classes, pt)
                except MapError as e:
                    if 'needs ' in str(e) or "isn't mapped" in str(e):
                        continue
                    if missing is None:
                        raise
                    missing[pt['id']] = str(e).split(': ', 1)[-1]
                    pending.remove(pt)
                    progress = True
                    continue
                self.found[pt['id']] = r
                out[pt['id']] = {'class': r} if pt['kind'] == 'class' else r
                if pt['kind'] == 'method' and ('pattern' in pt['find'] or 'code' in pt['find']):
                    r['sig'] = self.sig(cat, self.found[pt['of']], r)
                pending.remove(pt)
                progress = True
            if not progress:
                if missing is None:
                    raise MapError('can\'t resolve: ' + ', '.join(p['id'] for p in pending))
                for pt in pending:   # their class (or a type they name) is missing
                    missing[pt['id']] = 'needs ' + (pt.get('of') or 'a point that is missing')
                break
        return out


def md5(p):
    return hashlib.md5(open(p, 'rb').read()).hexdigest()


def catalogue(root=None):
    cats = []
    for p in sorted(glob.glob(os.path.join(root or os.path.join(REPO, 'core', 'hookpoints'), '*.json'))):
        cats.append(json.load(open(p)))
    return cats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('tree')
    ap.add_argument('--check', help='only these catalogue apps (comma-separated), from a partial tree: print, write nothing')
    for k in ('make', 'model', 'region', 'firmware'):
        ap.add_argument('--' + k, required=False)
    ap.add_argument('--products', help='comma-separated ro.build.product prefixes, e.g. CVEV.USA.')
    ap.add_argument('--name', default=None)
    ap.add_argument('--maps', default=os.path.join(REPO, 'maps'))
    ap.add_argument('--work')
    ap.add_argument('--strict', action='store_true', help='every point must be found, or nothing is written')
    ap.add_argument('--matrix', action='store_true', help='print every point: found or missing (why); write nothing')
    a = ap.parse_args()
    if a.work:
        run(a, a.work)
    else:
        with tempfile.TemporaryDirectory(prefix='hmgapi-map-') as work:   # decompiled code: hundreds of MB
            run(a, work)


def run(a, work):
    m = Mapper(a.tree, work)
    if a.matrix:
        missing = {}
        for cat in catalogue():
            for pid in m.map_catalogue(cat, missing):
                print('found    %s' % pid)
        for pid, why in missing.items():
            print('missing  %s  (%s)' % (pid, why))
        return
    if a.check:
        want = a.check.split(',')
        ok = True
        for cat in catalogue():
            if cat['app'] not in want and cat['app'] != 'framework':
                continue
            try:
                for pid, e in m.map_catalogue(cat).items():
                    if cat['app'] in want:
                        print('%-60s %s' % (pid, e.get('class') or e.get('method') or e.get('field') or e.get('ctor')))
            except MapError as e:
                print('not mapped: %s' % e)
                ok = False
        sys.exit(0 if ok else 1)
    for k in ('make', 'model', 'region', 'firmware', 'products'):
        if not getattr(a, k):
            sys.exit('--%s is needed (or use --check)' % k)
    apps, files, app_files = {}, {}, {}
    missing = None if a.strict else {}
    try:
        for cat in catalogue():
            apps[cat['app']] = m.map_catalogue(cat, missing)
            app_files[cat['app']] = {'package': cat['package'], 'files': cat['files']}
            for f in cat['files']:
                if os.path.isfile(m.local(f)):
                    files[f] = md5(m.local(f))
    except MapError as e:
        sys.exit('not mapped: %s' % e)
    if not any(apps.values()):
        sys.exit('not mapped: no hook point found in %s' % a.tree)
    d = os.path.join(a.maps, a.make, a.model, a.region, a.firmware)
    os.makedirs(d, exist_ok=True)
    cj = os.path.join(d, 'car.json')
    prev, old = maplib.load(d, a.maps) if os.path.isfile(cj) else ({}, {})   # either form (tools/maplib.py)
    old = {app: old.get(app) for app in apps}
    # a map that only gains code signatures ("sig") is the same map: it keeps its status
    nosig = lambda m: {k: {x: y for x, y in v.items() if x != 'sig'} for k, v in (m or {}).items()}
    same = all(nosig(old[app]) == nosig(apps[app]) for app in apps) and prev.get('files') == files and prev.get('missing', {}) == (missing or {})
    car = {
        'make': a.make, 'model': a.model, 'name': a.name or prev.get('name') or a.model, 'region': a.region,
        'firmware': a.firmware, 'products': [p for p in a.products.split(',') if p],
        'status': prev.get('status', 'mapped') if same else 'mapped',
        'verified': prev.get('verified') if same else None,
        'files': files,
        'apps': sorted(apps),
        'app_files': app_files,
    }
    if missing:
        car['missing'] = dict(sorted(missing.items()))
    base = maplib.base(a.maps)
    if base:   # the repo: base + this build's differences
        maplib.write_compact(d, car, {app: dict(sorted(e.items())) for app, e in apps.items()}, base)
    else:      # a maps dir without a base (a scratch dir, a test): full per-app files
        for app, entries in apps.items():
            maplib.dump(dict(sorted(entries.items())), os.path.join(d, app + '.json'))
        maplib.dump(car, cj)
    n = sum(len(v) for v in apps.values())
    print('%s: %d points in %d apps, status %s%s' % (os.path.relpath(d, REPO), n, len(apps), car['status'],
                                                    ', %d missing' % len(missing) if missing else ''))


if __name__ == '__main__':
    main()

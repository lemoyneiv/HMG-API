#!/usr/bin/env python3
"""Write docs/hook-points.md: every hook point, by app, and the cars that have it.

usage: mkhookdoc.py [--check]

Made from the catalogue (core/hookpoints/*.json) and the car maps (maps/), so it
says only what they say. --check: exit 1 if docs/hook-points.md isn't what this
would write (core/hookpoints/test.sh runs it: change a point, run this again).
"""
import glob, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maplib  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
OUT = os.path.join(REPO, 'docs', 'hook-points.md')
STATUS = {'verified': 'checked on a car', 'mapped': 'mapped from stock files', 'probed': 'mapped on cars by HMG-API\'s own mapper'}


def cars():
    """[(label, status, set of mapped point ids)], in maps/ order"""
    out = []
    for d in maplib.folders():
        car = json.load(open(os.path.join(d, 'car.json')))
        mapped = set(maplib.entries(d))
        name = car.get('name') or car['model']
        if not name.lower().startswith(car['make'].lower()):
            name = car['make'].capitalize() + ' ' + name
        label = '%s %s %s' % (name, car['region'].upper(), car['firmware'])
        out.append((label, car['status'], mapped))
    return out


def jtype(t):
    return t[1:] if t.startswith('@') else t.rsplit('.', 1)[-1] if t.startswith('java.lang.') else t


def shape(p):
    f = p.get('find', {})
    if p['kind'] == 'class':
        if 'name' in f:
            return '`%s`' % f['name']
        return 'a class in `%s` (no fixed name)' % f['package'] if 'package' in f else 'a class (no fixed name)'
    if p['kind'] == 'field':
        return '`%s%s`: %s' % ('static ' if f.get('static') else '', f.get('name', '?'), jtype(f.get('type', '?')))
    params = ', '.join(jtype(t) for t in f.get('params', []))
    if p['kind'] == 'ctor':
        return '`new (%s)`' % params
    ret = jtype(f['ret']) if 'ret' in f else '?'
    return '`%s%s(%s)` → %s' % ('static ' if f.get('static') else '', f.get('name', '?'), params, ret)


def cell(s):
    return s.replace('|', '\\|').replace('\n', ' ')


def render():
    cs = cars()
    n = sum(len(json.load(open(f))['points']) for f in glob.glob(os.path.join(REPO, 'core', 'hookpoints', '*.json')))
    L = ['# Hook points', '',
         '<!-- Made by tools/mkhookdoc.py from core/hookpoints/*.json and maps/. Don\'t edit by hand: change',
         '     the catalogue, then run tools/mkhookdoc.py. -->', '',
         'A hook point is a stable name for a class, method, constructor or field in the car\'s',
         'own software. A mod uses the name, never the app\'s own (often obfuscated, per-build)',
         'one, and each car\'s map says what the name is on that car. In a mod\'s hooks:',
         '`ctx.hook(cl, "<point>", new MethodHook() {...})` to hook a method, and',
         '`hmgapi.hook.Points` (`call`, `get`, `cls`, `method`, `field`, `newInstance`, `has`) to',
         'use one. A point a car\'s map lacks is off on that car, and the HMG-API app says so.', '',
         'How points are described and found: [core/hookpoints/README.md](../core/hookpoints/README.md).',
         'Need one that isn\'t here? Open a "New hook point" issue.', '',
         'Every point and map lives here, in HMG-API, including the ones only a mod in its own repo',
         'uses (the wireless mod\'s, for one): a mod gets new points through an HMG-API release and its',
         'mod SDK ([mod-repos.md](mod-repos.md), section 4).', '',
         '%d hook points. **Cars** (`maps/`):' % n, '',
         '| Car | Map | Points |', '|---|---|---|']
    for label, status, mapped in cs:
        L.append('| %s | %s | %d of %d |' % (label, STATUS.get(status, status), len(mapped), n))
    L += ['', 'In the tables, **Cars** is "all" when every car above has the point, otherwise the cars without it.', '']
    for f in sorted(glob.glob(os.path.join(REPO, 'core', 'hookpoints', '*.json')), key=lambda f: (os.path.basename(f) != 'framework.json', f)):
        cat = json.load(open(f))
        L += ['## %s' % cat['app'], '']
        L.append('Android framework and the car\'s service interfaces (any process).' if cat['package'] is None
                 else 'App `%s`.' % cat['package'])
        L += ['', '| Point | Kind | Looks for | About | Cars |', '|---|---|---|---|---|']
        for p in cat['points']:
            without = [label for label, _, mapped in cs if p['id'] not in mapped]
            where = 'all' if not without else 'not on ' + ', '.join(without)
            L.append('| `%s` | %s | %s | %s | %s |' % (p['id'], p['kind'], cell(shape(p)), cell(p.get('about', '')), where))
        L.append('')
    return '\n'.join(L)


def main():
    text = render()
    if '--check' in sys.argv[1:]:
        if not os.path.isfile(OUT) or open(OUT, encoding='utf-8').read() != text:
            print('FAIL  docs/hook-points.md is out of date: run tools/mkhookdoc.py')
            sys.exit(1)
        print('ok    docs/hook-points.md matches the catalogue and maps')
        return
    open(OUT, 'w', encoding='utf-8').write(text)
    print('wrote docs/hook-points.md')


if __name__ == '__main__':
    main()

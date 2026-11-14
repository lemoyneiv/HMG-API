#!/usr/bin/env python3
"""Which off-car tests a change needs: changed paths (one per line, stdin) in, the
test scripts to run out (one per line, in tools/test-all.sh's order), or nothing
when only documentation changed. A path no rule knows runs everything, so a new
folder is tested until it gets a rule.

usage: git diff --name-only BASE HEAD | tools/test-select.py
"""
import fnmatch, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

ALL = 'ALL'
RUNTIME = ['core/runtime/*-test.sh']
# the tests that compile hmgapi.engine (tools/engine-classes.sh): the rest of core/runtime's
# Java (hook, vehicle) is only compiled by the build and the SDK, and its own tests
ENGINE = ['core/runtime/engine-test.sh', 'core/runtime/signing-test.sh', 'core/runtime/system-test.sh', 'core/runtime/safety-test.sh',
          'core/runtime/packages-test.sh', 'core/runtime/map-test.sh', 'core/runtime/usb-test.sh', 'core/runtime/hooks-test.sh',
          'core/runtime/live-test.sh', 'core/runtime/native-test.sh', 'core/runtime/runtime-test.sh', 'core/runtime/net-test.sh',
          'core/runtime/overlap-test.sh', 'core/runtime/scriptcheck-test.sh', 'install/nemesis/mod-test.sh', 'install/nemesis/sync-test.sh',
          'tools/catalog-test.sh', 'tools/version-test.sh', 'tools/check-scripts.sh']
INSTALL = ['install/common/test.sh', 'install/common/install-test.sh', 'install/usb/test.sh', 'install/nemesis/mod-test.sh', 'install/nemesis/sync-test.sh']
MODS = ['tools/check-scripts.sh']   # the example mods' scripts (their tests run in sdk-test)
HOOKS = ['core/hookpoints/test.sh']
SDK = ['tools/sdk-test.sh']

# (path pattern, tests): every rule a path matches adds its tests; first match of DOCS ends it
DOCS = ['docs/*', 'wiki/*', '*.md', '.github/ISSUE_TEMPLATE/*', '.github/release-notes/*', '.github/pull_request_template.md',
        'LICENSE', 'NOTICE.md', '.gitignore', 'docs/img/*']
RULES = [
    # the build itself, the test runner, the CI: everything
    (['build.sh', 'tools/env.sh', 'tools/test-all.sh', 'tools/test-select.py', 'tools/engine-classes.sh', '.github/workflows/ci.yml',
      'core/loader/*', 'core/client/*'], ALL),
    (['core/hmgapid/*'], ['core/hmgapid/test.sh'] + INSTALL),
    (['core/zygote/*'], ['core/zygote/test.sh'] + INSTALL),
    (['tools/lpatch/*'], ['tools/lpatch/test.sh', 'core/runtime/native-test.sh', 'core/runtime/engine-test.sh'] + INSTALL + SDK),
    (['core/runtime/unit-commands.txt'], ['tools/check-scripts.sh', 'core/runtime/scriptcheck-test.sh'] + SDK),
    # the engine also gives the version the installers and the SDK are named by (Engine.java)
    (['core/runtime/java/hmgapi/engine/*', 'core/runtime/test/hmgapi/engine/*'], ENGINE + INSTALL + SDK),
    # hooks and the vehicle service: their own tests, and the SDK (mods compile against them)
    (['core/runtime/java/hmgapi/hook/*', 'core/runtime/test/hmgapi/hook/*'], ['core/runtime/keys-test.sh', 'core/runtime/mod-dex-test.sh', 'core/runtime/ble-test.sh'] + SDK),
    (['core/runtime/java/hmgapi/vehicle/*', 'core/runtime/test/hmgapi/vehicle/*'], ['core/runtime/commands-test.sh'] + SDK),
    (['core/runtime/java/hmgapi/ble/*', 'core/runtime/test/hmgapi/ble/*', 'core/runtime/test/ble_filter_test.c'], ['core/runtime/ble-test.sh'] + SDK),
    (['core/runtime/stubs/*'], SDK),
    (['core/runtime/*-test.sh'], []),   # a test itself: it runs (select() adds every changed test)
    (['core/hookpoints/*'], SDK),   # the SDK's points.txt and catalogue
    (['core/hookpoints/*', 'maps/*', 'tools/map-car.py', 'tools/check-maps.py', 'tools/mkhookdoc.py', 'docs/hook-points.md', 'tools/test-hooks.py', 'tools/probe-test.py'], HOOKS),
    # the stock car trees: the finder every suite above uses, and cars.py
    (['tools/cartrees.py', 'tools/cars.py', 'tools/cars-test.sh'], ['tools/cars-test.sh'] + HOOKS),
    (['core/app/*'], ['core/app/qr-test.sh']),
    (['mods/examples/*'], ['core/runtime/signing-test.sh', 'install/nemesis/mod-test.sh', 'tools/check-scripts.sh'] + SDK),   # hello: its test.sh runs in sdk-test
    (['install/*'], INSTALL + ['core/runtime/packages-test.sh']),
    (['install/nemesis/mod/*', 'install/nemesis/bin/open.sh'], SDK),
    (['tools/mkdelta.py', 'tools/mkrecipe.py', 'tools/mkmemrecipe.py'], ['core/runtime/engine-test.sh', 'core/runtime/safety-test.sh', 'tools/lpatch/test.sh']),
    (['tools/mkinstallers.sh', 'tools/mkrelease.sh', 'tools/mkmodpkg.sh', 'tools/mknemesismod.sh', 'tools/nemesis-version.py', 'tools/nemesis-meta.py', 'tools/release-mods.txt'], INSTALL + ['core/runtime/signing-test.sh', 'core/runtime/usb-test.sh'] + SDK + MODS),
    # the mod SDK and the reusable mod workflow
    (['tools/build-mod.sh', 'tools/check-points.py', 'tools/sdk/*', 'tools/sdk-test.sh', 'tools/mkrecipe.py', 'tools/mkdelta.py', 'tools/mkmemrecipe.py', 'tools/mkicon.sh', 'tools/mod-draft.py', 'tools/check-scripts.sh', '.github/workflows/mod.yml'], SDK),
    (['tools/mod-draft*'], ['tools/mod-draft-test.sh']),
    # every tool that orders versions, against Version (docs/releasing.md)
    (['tools/nemesis-version.py', 'tools/check-points.py', 'tools/catalog.py', 'tools/version-test.sh'], ['tools/version-test.sh']),
    (['tools/mkicon.sh', 'tools/mkicon-hmgapi.sh', 'tools/mkicon-test.sh'], ['tools/mkicon-test.sh']),
    (['tools/mark-verified.py', 'tools/report-map.py'], HOOKS),   # they write or read the maps
    (['catalog/*', 'tools/catalog*', 'maps/authors.json', '.github/workflows/catalog.yml'], ['tools/catalog-test.sh']),
    (['tools/micom-*'], ['tools/micom-decode-test.sh']),
    (['tools/check-scripts.sh'], ['tools/check-scripts.sh']),
    (['tools/requirements.txt'], ALL),
    (['.github/workflows/release.yml', '.github/workflows/wiki.yml', '.github/CODEOWNERS', '.github/FUNDING.yml', '.github/dependabot.yml'], []),
]


def every_test():
    """tools/test-all.sh's list, in its order"""
    t = open(os.path.join(HERE, 'test-all.sh')).read()
    m = re.search(r'^ALL="(.*?)"$', t, re.M)
    return m.group(1).split()


def match(path, pats):
    return any(fnmatch.fnmatch(path, p) or (p.endswith('/*') and path.startswith(p[:-1])) for p in pats)


def select(paths):
    tests = every_test()
    want = set()
    for p in paths:
        p = p.strip()
        if not p or match(p, DOCS) and not match(p, [r for rule, _ in RULES for r in rule]):
            continue
        hit = p in tests   # a test that changed runs
        if hit:
            want.add(p)
        if p.startswith('mods/examples/'):   # an example mod: its own tests, if test-all.sh lists any
            d = '/'.join(p.split('/')[:3]) + '/'
            want.update(t for t in tests if t.startswith(d))
        for pats, got in RULES:
            if match(p, pats):
                hit = True
                if got == ALL:
                    return tests
                for g in got:
                    want.update(t for t in tests if fnmatch.fnmatch(t, g))
        if not hit:
            return tests   # unknown: test everything
    return [t for t in tests if t in want]


if __name__ == '__main__':
    for t in select(sys.stdin.read().split('\n')):
        print(t)

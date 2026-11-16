#!/bin/sh
# The hook point catalogue and every car map (tools/check-maps.py; --rebuild where a stock tree is known),
# docs/hook-points.md made from them (tools/mkhookdoc.py),
# then, for the stock trees on this machine ($HMGAPI_CARS, docs/car-trees.md): every point on every car (tools/test-hooks.py) and the
# on-car mapper against map-car.py (tools/probe-test.py).
cd "$(dirname "$0")/../.."
python3 tools/report-map-test.py || exit 1
echo "ok    Car Reports preserve missing points and explicit car identities"
python3 tools/maplib.py check || exit 1
python3 tools/check-maps.py --rebuild || exit 1
python3 tools/mkhookdoc.py --check || exit 1
[ -n "$(python3 tools/cars.py ids)" ] || { echo "skip  coverage and the on-car mapper (no stock trees, docs/car-trees.md)"; exit 0; }
python3 tools/test-hooks.py || exit 1
echo "ok    hook coverage on every car"
python3 tools/probe-test.py || exit 1
echo "ok    on-car mapper finds what map-car.py finds"

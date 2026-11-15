#!/bin/sh
# Off-car test of the live state (engine publish-state): what running apps may read
# about the mods, and that private settings and anything else stay root-only.
set -e
cd "$(dirname "$0")/../.."
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
eng() { set +e; out=$(HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_TEST_ANY_CAR=1 java -cp "$CP" hmgapi.engine.Engine "$@" 2>&1); rc=$?; set -e; }
q() { python3 -c "import json,sys; d=json.load(open('$R/data/hmgapi/public/state.json')); print(eval(sys.argv[1]))" "$1"; }
mkdir -p $R/data/hmgapi/settings
mod() {   # mod <id> <extra mod.json>
    mkdir -p $R/data/hmgapi/mods/$1
    printf '{"id": "%s", "version": "1.0.0"%s}\n' "$1" "$2" > $R/data/hmgapi/mods/$1/mod.json
    echo jar > $R/data/hmgapi/mods/$1/hooks.jar; echo secret > $R/data/hmgapi/mods/$1/private.bin
    chmod 700 $R/data/hmgapi/mods/$1; chmod 600 $R/data/hmgapi/mods/$1/*
}
mod base ', "live": true'
mod user ', "requires": ["base ^1.0"], "live": true'
mod other ''
printf '{"settings": [{"key": "colour", "type": "text", "default": "green"}, {"key": "token", "type": "text", "default": "", "private": true}]}\n' > $R/data/hmgapi/mods/base/settings.json
printf '{"colour": "blue", "token": "s3cret"}\n' > $R/data/hmgapi/settings/base.json
chmod 700 $R/data/hmgapi $R/data/hmgapi/mods

eng publish-state; check "publish" 0 $rc
has "says how many are on" "state published: 3 of 3 mods on" "$out"
check "owner's value over the default" blue "$(q "d['mods']['base']['settings']['colour']")"
check "private setting left out" False "$(q "'token' in d['mods']['base']['settings']")"
check "live flag" "True False" "$(q "str(d['mods']['user']['live'])+' '+str(d['mods']['other']['live'])")"
has "running apps told" "broadcast hmgapi.intent.action.STATE_CHANGED" "$(cat $R/actions.log)"
check "state.json readable by apps" "-rw-r--r--" "$(ls -l $R/data/hmgapi/public/state.json | cut -c1-10)"
check "mods folder: traverse only" "drwx--x--x" "$(ls -ld $R/data/hmgapi/mods | cut -c1-10)"
check "a mod's hooks.jar readable" "-rw-r--r--" "$(ls -l $R/data/hmgapi/mods/base/hooks.jar | cut -c1-10)"
check "its other files not" "-rw-------" "$(ls -l $R/data/hmgapi/mods/base/private.bin | cut -c1-10)"
check "settings stay root-only" "-rw-------" "$(chmod 600 $R/data/hmgapi/settings/base.json; ls -l $R/data/hmgapi/settings/base.json | cut -c1-10)"

: > $R/data/hmgapi/mods/base/.disabled
eng publish-state
check "off" False "$(q "d['mods']['base']['enabled']")"
check "a mod whose requirement is off is off too" False "$(q "d['mods']['user']['enabled']")"
check "others unaffected" True "$(q "d['mods']['other']['enabled']")"
s1=$(q "d['serial']"); sleep 0.01; eng publish-state
check "every publish has a new serial" yes "$([ "$(q "d['serial']")" != "$s1" ] && echo yes)"
eng apply; has "apply publishes too" "state published" "$out"

# A custom/nested code JAR must be readable to the app-part loader too.
mod custom ', "hooks": {"jar": "code/ev.jar", "entry": "example.Ev"}'
mkdir -p "$R/data/hmgapi/mods/custom/code"
echo jar > "$R/data/hmgapi/mods/custom/code/ev.jar"
chmod 700 "$R/data/hmgapi/mods/custom/code"
chmod 600 "$R/data/hmgapi/mods/custom/code/ev.jar"
eng publish-state; check "publish custom hook JAR" 0 $rc
check "declared JAR readable" "-rw-r--r--" "$(ls -l "$R/data/hmgapi/mods/custom/code/ev.jar" | cut -c1-10)"
check "declared JAR parent traversable only" "drwx--x--x" "$(ls -ld "$R/data/hmgapi/mods/custom/code" | cut -c1-10)"
check "unused default JAR stays private" "-rw-------" "$(ls -l "$R/data/hmgapi/mods/custom/hooks.jar" | cut -c1-10)"
check "custom mod's private payload stays private" "-rw-------" "$(ls -l "$R/data/hmgapi/mods/custom/private.bin" | cut -c1-10)"

# Declared paths and symlinks must not disclose files outside the mod.
echo private > "$R/data/hmgapi/mods/private.jar"
chmod 600 "$R/data/hmgapi/mods/private.jar"
mod escape ', "hooks": {"jar": "../private.jar", "entry": "example.Bad"}'
mod link ', "hooks": {"jar": "linked.jar", "entry": "example.Bad"}'
ln -s "$R/data/hmgapi/mods/private.jar" "$R/data/hmgapi/mods/link/linked.jar"
mod absolute ", \"hooks\": {\"jar\": \"$R/data/hmgapi/mods/private.jar\", \"entry\": \"example.Bad\"}"
eng publish-state
check "path escapes and symlinks do not expose private files" "-rw-------" "$(ls -l "$R/data/hmgapi/mods/private.jar" | cut -c1-10)"

# The same for native patch recipes (hooks.native): the one in the mod's folder readable,
# nothing outside it (the owner's settings, another mod's files), by path or by symlink.
chmod 700 "$R/data/hmgapi/settings"; chmod 600 "$R/data/hmgapi/settings/base.json"
mod recipes ", \"hooks\": {\"jar\": \"hooks.jar\", \"entry\": \"example.Ev\", \"native\": {\"libx.so\": [\"recipes/x.recipe\", \"../../settings/base.json\", \"link.recipe\", \"$R/data/hmgapi/mods/private.jar\"]}}"
mkdir -p "$R/data/hmgapi/mods/recipes/recipes"
echo recipe > "$R/data/hmgapi/mods/recipes/recipes/x.recipe"
chmod 700 "$R/data/hmgapi/mods/recipes/recipes"; chmod 600 "$R/data/hmgapi/mods/recipes/recipes/x.recipe"
ln -s "$R/data/hmgapi/mods/base/private.bin" "$R/data/hmgapi/mods/recipes/link.recipe"
eng publish-state; check "publish with native recipes" 0 $rc
check "a mod's own recipe readable" "-rw-r--r--" "$(ls -l "$R/data/hmgapi/mods/recipes/recipes/x.recipe" | cut -c1-10)"
check "... its folder traversable only" "drwx--x--x" "$(ls -ld "$R/data/hmgapi/mods/recipes/recipes" | cut -c1-10)"
check "a recipe path out of the mod: the owner's settings stay private" "-rw-------" "$(ls -l "$R/data/hmgapi/settings/base.json" | cut -c1-10)"
check "... and the settings folder closed" "drwx------" "$(ls -ld "$R/data/hmgapi/settings" | cut -c1-10)"
check "a recipe symlink to another mod's file: stays private" "-rw-------" "$(ls -l "$R/data/hmgapi/mods/base/private.bin" | cut -c1-10)"
check "an absolute recipe path: stays private" "-rw-------" "$(ls -l "$R/data/hmgapi/mods/private.jar" | cut -c1-10)"
exit $fail

#!/bin/sh
# Off-car test of engine nemesis-sync (NemesisSync.java) and HMG-API's NEMESIS scripts
# with it: NEMESIS lists what HMG-API has installed, HMG-API does every install and
# uninstall. A fake head unit with hmgapid running the real engine on this JVM, and a
# fake NEMESIS that runs a package's hooks as native/pkg.c does (with the caller's env).
set -e
cd "$(dirname "$0")/../.."
SH=${UNIT_SH:-sh}
CP=$(mktemp -d); T=$(mktemp -d); trap 'rm -rf "$T" "$CP"' EXIT
sh tools/engine-classes.sh "$CP" 2>/dev/null
R=$T/root
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: wanted [$2], got [$3]"; fail=1; fi; }
has() { case "$3" in *"$2"*) echo "ok    $1" ;; *) echo "FAIL  $1: wanted [$2] in [$3]"; fail=1 ;; esac; }
hasnt() { case "$3" in *"$2"*) echo "FAIL  $1: [$2] in [$3]"; fail=1 ;; *) echo "ok    $1" ;; esac; }
sig() { java -cp "$CP" hmgapi.engine.Signing "$@"; }
listed() { ls $R/data/nemesis/apps 2>/dev/null | tr '\n' ' '; }
pv() { grep '^package-version=' $R/data/nemesis/apps/$1/NEMESIS.data | cut -d= -f2; }
mods() { ls $R/data/hmgapi/mods 2>/dev/null | grep -v '^\.' | tr '\n' ' '; }

# ---- the unit: HMG-API installed (installed.txt, its NEMESIS scripts as install.sh keeps them)
mkdir -p $R/system/bin $R/system/xbin $R/system/etc/permissions $R/data/hmgapi/mods $R/data/hmgapi/settings $R/data/local/tmp/nemesis/stage $R/data/nemesis/apps
printf 'ro.build.product=CVEV.USA.0000.V015.012.260507\npersist.demo=stock\n' > $R/system/build.prop
: > $R/data/hmgapi/uninstall.sh
V=$(sed -n 's/.*HMGAPI_VERSION = "\(.*\)";/\1/p' core/runtime/java/hmgapi/engine/Engine.java)   # HMG-API's own version
PV=$(tools/nemesis-version.py "$V")   # and NEMESIS's listing of it (dotted numbers: a pre-release is x.y.97-99.N)
echo "version $V" > $R/data/hmgapi/installed.txt
N=$R/data/hmgapi/nemesis
mkdir -p $N/mod $N/bin
cp -r install/nemesis/scripts $N/scripts; cp -r install/nemesis/mod/scripts $N/mod/scripts
cp install/nemesis/bin/open.sh install/nemesis/bin/start.sh $N/bin/; cp install/nemesis/conflicts.txt $N/conflicts.txt
cat > $R/system/bin/hmgapid <<EOF
#!/bin/sh
case \$1 in
    ping|daemon) exit 0 ;;
    engine) shift; echo "engine \$*" >> $T/calls
        exec env HMGAPI_ROOT=$R HMGAPI_HOST=1 HMGAPI_PRODUCT=CVEV.USA.0000.V015.012.260507 java -cp $CP hmgapi.engine.Engine "\$@" ;;
esac
echo "hmgapid stub: \$*"; exit 2
EOF
chmod 755 $R/system/bin/hmgapid
mkdir -p $T/bin
printf '#!/bin/sh\necho 0\n' > $T/bin/getprop
printf '#!/bin/sh\nexit 0\n' > $T/bin/am
chmod 755 $T/bin/*
eng() { set +e; out=$(env PATH="$T/bin:$PATH" $R/system/bin/hmgapid engine "$@" 2>&1); rc=$?; set -e; }

# NEMESIS: version, install [--update] <zip>, uninstall <id>; hooks as native/pkg.c runs them
# (cwd the package, the caller's environment); every call goes to nemesis.log
cat > $T/nemesis <<EOF
#!/bin/sh
echo "nemesis \$* sync=\${HMGAPI_NEMESIS_SYNC:-}" >> $T/nemesis.log
[ "\$1" = version ] && { cat $T/nemesis.version; exit 0; }
A=$R/data/nemesis/apps
export ROOT=$R PATH=$T/bin:\$PATH
hook() { [ -f "\$2/\$1" ] || return 0; (cd "\$2" && $SH "\$1"); }
case \$1 in
    install)
        [ "\$2" = --update ] && { up=1; shift; }
        id=\$(unzip -p "\$2" NEMESIS.data | grep '^id=' | cut -d= -f2)
        st=$R/data/local/tmp/nemesis/stage/\$id
        [ -d "\$A/\$id" ] && [ -z "\$up" ] && { echo "already installed: \$id (pass --update)"; exit 3; }
        rm -rf "\$st"; mkdir -p "\$st"; unzip -q "\$2" -d "\$st"
        if [ -d "\$A/\$id" ]; then hook scripts/preuninstall.sh "\$A/\$id" || { echo "update failed: old version could not be removed"; rm -rf "\$st"; exit 5; }; rm -rf "\$A/\$id"; fi
        hook scripts/preinstall.sh "\$st" || { rc=\$?; echo "preinstall failed rc=\$rc"; rm -rf "\$st"; exit \$rc; }
        mkdir -p "\$A/\$id"; cp -r "\$st/." "\$A/\$id/"
        hook scripts/postinstall.sh "\$A/\$id"; rc=\$?
        rm -rf "\$st"; exit \$rc ;;
    uninstall)
        [ -d "\$A/\$2" ] || { echo "not installed: \$2"; exit 3; }
        for o in \$A/*/NEMESIS.data; do   # another package depends on it: exit 4
            case ",\$(sed -n 's/^depends="\(.*\)"\$/\1/p' "\$o")," in *",\$2,"*|*",\$2@"*) echo "dependent \${o%/NEMESIS.data}"; exit 4 ;; esac
        done
        hook scripts/preuninstall.sh "\$A/\$2" || { echo "preuninstall failed: package stays"; exit 5; }
        rm -rf "\$A/\$2" ;;
esac
EOF
chmod 755 $T/nemesis
echo 1.16.5 > $T/nemesis.version
nlog() { cat $T/nemesis.log 2>/dev/null; : > $T/nemesis.log; }
ecalls() { cat $T/calls 2>/dev/null; : > $T/calls; }

# a signed mod
sig keygen $T/author.key > /dev/null
mk() {   # mk <version> [more mod.json fields, each ending in ", "]: $T/demo.zip
    rm -rf $T/m; mkdir -p $T/m/files
    printf '{"id": "demo", "name": "Demo mod", %s"version": "%s", "hmgapi": ">=0.1.0", "system": [{"model": ["CVEV"], "region": "USA", "firmware": "260507"}]}\n' "${2:-}" "$1" > $T/m/mod.json
    printf '{"changes": [{"type": "props", "path": "/system/build.prop", "set": {"persist.demo": "on"}, "stock": "any"}]}\n' > $T/m/patches.json
    sig sign $T/m $T/author.key > /dev/null
    rm -f $T/demo.zip; (cd $T/m && zip -q -r -X $T/demo.zip .)
}

# ---- no NEMESIS, or an older one: nothing
eng nemesis-sync
check "no NEMESIS on the unit: nothing to do" "0 " "$rc $(listed)"
ln -s $T/nemesis $R/system/xbin/nemesis
echo 1.15 > $T/nemesis.version
eng nemesis-sync
has "NEMESIS 1.15: says it's too old" "older than 1.16" "$out"
check "... lists nothing" "" "$(listed)"
echo 1.16.5 > $T/nemesis.version
nlog > /dev/null; ecalls > /dev/null

# ---- HMG-API listed
eng nemesis-sync
check "HMG-API installed, not in NEMESIS: listed" "0 hmgapi " "$rc $(listed)"
check "... at its package-version" "$PV" "$(pv hmgapi)"
D=$R/data/nemesis/apps/hmgapi
check "... a small package: no payload, a registered marker" "no yes" "$([ -d $D/payload ] && echo yes || echo no) $([ -f $D/registered ] && echo yes || echo no)"
has "... refusing the packages conflicts.txt lists" 'depends="!wireless.carplay.aa,!carplay.session,!voicehandler.vr-gates"' "$(cat $D/NEMESIS.data)"
has "... os any" 'os="any"' "$(cat $D/NEMESIS.data)"
check "... a boot service, so NEMESIS's svcmgr starts HMG-API too" "name=boot kind=sh path=bin/start.sh uid=root mode=background" "$(echo $(grep -A5 '^\[service.0\]' $D/NEMESIS.data | grep -v '^\['))"
check "... its script: hmgapid boot" "exec /system/bin/hmgapid boot" "$(grep '^exec' $D/bin/start.sh 2>/dev/null)"
has "... NEMESIS ran its scripts in list-only mode" "install $R/data/hmgapi/state/nemesis-sync/hmgapi.zip sync=1" "$(nlog)"
check "... and they called no engine" "nemesis-sync" "$(ecalls | sed 's/^engine //')"
eng nemesis-sync
check "a second sync: nothing to do (NEMESIS not called but for its version)" "nemesis version sync=" "$(nlog)"
ecalls > /dev/null

# ---- a mod installed in HMG-API: listed too
mk 1.0.0
eng install $T/demo.zip
check "engine install: the mod in HMG-API" "demo " "$(mods)"
has "... and listed in NEMESIS" "listed hmgapi.demo in NEMESIS" "$out"
check "... as hmgapi.demo, at the mod's version" "hmgapi hmgapi.demo 1.0.0" "$(listed)$(pv hmgapi.demo)"
D=$R/data/nemesis/apps/hmgapi.demo
check "... a mod gets no boot service" "" "$(grep '^\[service' $D/NEMESIS.data; ls $D/bin/start.sh 2>/dev/null)"
has "... needing HMG-API 1.0 or newer" 'depends="hmgapi@>=1.0"' "$(cat $D/NEMESIS.data)"
check "... no mod zip in it, its id and the marker" "no demo yes" "$([ -f $D/mod/package.zip ] && echo yes || echo no) $(cat $D/mod/id) $([ -f $D/registered ] && echo yes || echo no)"
check "... the package's scripts called no engine (no second install)" "install $T/demo.zip" "$(ecalls | sed 's/^engine //')"

# an update in HMG-API: listed at the new version (NEMESIS's update runs the old package's preuninstall: list only)
mk 1.1.0
eng install $T/demo.zip
check "engine install of 1.1.0: listed at 1.1.0" 1.1.0 "$(pv hmgapi.demo)"
check "... still in HMG-API" "demo " "$(mods)"
has "... through NEMESIS's update" "install --update $R/data/hmgapi/state/nemesis-sync/hmgapi.demo.zip sync=1" "$(nlog)"
ecalls > /dev/null

# ---- removed in HMG-API: unlisted
eng remove demo
check "engine remove: gone from HMG-API" "" "$(mods)"
has "... unlisted from NEMESIS" "isn't installed in HMG-API any more: unlisted it" "$out"
check "... NEMESIS lists only HMG-API" "hmgapi " "$(listed)"
check "... its preuninstall called no engine (no second remove)" "remove demo" "$(ecalls | sed 's/^engine //')"
nlog > /dev/null

# ---- a stale NEMESIS entry (a full mod package whose mod was removed in HMG-API before this release)
mk 1.0.0
tools/mknemesismod.sh $T/demo.zip $T/dist > /dev/null
env PATH="$T/bin:$PATH" $R/system/xbin/nemesis install $T/dist/demo-1.0.0-nemesis-package.zip > /dev/null 2>&1
check "a NEMESIS mod package installs the mod (setup)" "demo " "$(mods)"
rm -rf $R/data/hmgapi/mods/demo   # gone from HMG-API some other way
printf '#!/system/bin/sh\necho "old script: would remove the mod"\nexit 1\n' > $R/data/nemesis/apps/hmgapi.demo/scripts/preuninstall.sh   # an older package's copy
ecalls > /dev/null
eng nemesis-sync
check "stale hmgapi.demo: unlisted" "0 hmgapi " "$rc $(listed)"
check "... with HMG-API's current scripts (the old copy refreshed first), no engine" "nemesis-sync" "$(ecalls | sed 's/^engine //')"

# ---- stale entries where one depends on the other (a mod package's "requires"), the needed one first
for id in base user; do
    mkdir -p $R/data/nemesis/apps/hmgapi.$id/scripts $R/data/nemesis/apps/hmgapi.$id/mod
    echo $id > $R/data/nemesis/apps/hmgapi.$id/mod/id
    printf '[nemesis]\nid=hmgapi.%s\npackage-version=1.0.0\ndepends="hmgapi@>=1.0"\n' $id > $R/data/nemesis/apps/hmgapi.$id/NEMESIS.data
done
sed 's/^depends=.*/depends="hmgapi@>=1.0,hmgapi.base@>=1.0.0"/' $R/data/nemesis/apps/hmgapi.user/NEMESIS.data > $T/user.data && mv $T/user.data $R/data/nemesis/apps/hmgapi.user/NEMESIS.data
nlog > /dev/null
eng nemesis-sync
check "a stale package another one depends on: unlisted once that one is" "0 hmgapi " "$rc $(listed)"
check "... tried again after it" "nemesis uninstall hmgapi.base|nemesis uninstall hmgapi.user|nemesis uninstall hmgapi.base" \
    "$(nlog | grep uninstall | sed 's/ sync=.*//' | tr '\n' '|' | sed 's/|$//')"
has "... no failure said" "NEMESIS lists HMG-API and its" "$out"

# ---- uninstalled in NEMESIS Manager by the owner: HMG-API removes the mod (the real work)
mk 1.0.0
eng install $T/demo.zip
check "listed again after an install (setup)" "hmgapi hmgapi.demo " "$(listed)"
eng apply
check "... applied (setup)" on "$(grep '^persist.demo=' $R/system/build.prop | cut -d= -f2)"
ecalls > /dev/null
set +e; o=$(env -u HMGAPI_NEMESIS_SYNC PATH="$T/bin:$PATH" $R/system/xbin/nemesis uninstall hmgapi.demo 2>&1); r=$?; set -e
check "NEMESIS uninstall of a listed mod: done" 0 $r
check "... HMG-API removed the mod" "" "$(mods)"
check "... and put its change back" stock "$(grep '^persist.demo=' $R/system/build.prop | cut -d= -f2)"
check "... through engine remove --from-nemesis and apply (no sync from inside NEMESIS)" "remove demo --from-nemesis|apply" "$(ecalls | sed 's/^engine //' | tr '\n' '|' | sed 's/|$//')"
check "... NEMESIS lists only HMG-API" "hmgapi " "$(listed)"

# ---- HMG-API's own listing: another version is re-listed; a NEMESIS uninstall uninstalls only if installed
f=$R/data/nemesis/apps/hmgapi/NEMESIS.data; sed 's/^package-version=.*/package-version=0.99.6/' $f > $f.new && mv $f.new $f   # no sed -i: BSD sed's differs
eng nemesis-sync
check "HMG-API listed at a beta's version: re-listed at its own" "$PV" "$(pv hmgapi)"
D=$R/data/nemesis/apps/hmgapi
o=$(cd $D && env HMGAPI_NEMESIS_SYNC=1 ROOT=$R $SH scripts/preuninstall.sh 2>&1)
has "HMG-API's preuninstall, list-only: does nothing" "list only, nothing uninstalled" "$o"
rm $R/data/hmgapi/installed.txt
o=$(cd $D && env ROOT=$R $SH scripts/preuninstall.sh uninstall 2>&1)
has "... uninstalled already (uninstall.sh left in /data/hmgapi): nothing to do" "HMG-API isn't installed" "$o"
set +e; o=$(cd $D && env ROOT=$R $SH scripts/postinstall.sh 2>&1); r=$?; set -e
has "a listing package without HMG-API installed: says to install HMG-API" "isn't installed: install HMG-API's own download" "$o"
check "... and fails" 1 $r
echo "version $V" > $R/data/hmgapi/installed.txt
o=$(cd $D && env ROOT=$R $SH scripts/postinstall.sh 2>&1)
has "... with HMG-API installed: only lists it" "HMG-API is installed: listed" "$o"

# ---- a mod's own list (mod.json "nemesis_conflicts", as tools/mknemesismod.sh reads it), with conflicts.txt's row
cp $N/conflicts.txt $T/conflicts.keep
printf 'hmgapi.demo  row.pkg,mine.pkg\n' >> $N/conflicts.txt
mk 1.2.0 '"nemesis_conflicts": ["mine.pkg", "other.pkg", "hmgapi", "hmgapi.demo", "not an id"], '
eng install $T/demo.zip
check "a mod with nemesis_conflicts: listed" "hmgapi hmgapi.demo 1.2.0" "$(listed)$(pv hmgapi.demo)"
has "... refusing conflicts.txt's row, then its own (each once)" 'depends="hmgapi@>=1.0,!row.pkg,!mine.pkg,!other.pkg"' "$(cat $R/data/nemesis/apps/hmgapi.demo/NEMESIS.data)"
has "... HMG-API itself left out, said" '"nemesis_conflicts": hmgapi left out' "$out"
has "... the mod's own package left out" '"nemesis_conflicts": hmgapi.demo left out' "$out"
has "... a bad id left out" '"nemesis_conflicts": not an id left out' "$out"
cp $T/conflicts.keep $N/conflicts.txt
mk 1.3.0 '"nemesis_conflicts": "mine.pkg", '
eng install $T/demo.zip
check "nemesis_conflicts not a list: still listed" "0 1.3.0" "$rc $(pv hmgapi.demo)"
has "... with only the needs" 'depends="hmgapi@>=1.0"' "$(cat $R/data/nemesis/apps/hmgapi.demo/NEMESIS.data)"
has "... and says why" "\"nemesis_conflicts\" isn't a list, left out" "$out"
mk 1.4.0 "\"nemesis_conflicts\": [$(python3 -c 'print(", ".join("\"pkg%03d.long-package-name\"" % i for i in range(30)))')], "
eng install $T/demo.zip
check "depends= longer than NEMESIS takes: still listed" "0 1.4.0" "$rc $(pv hmgapi.demo)"
has "... without the mod's own list" 'depends="hmgapi@>=1.0"' "$(cat $R/data/nemesis/apps/hmgapi.demo/NEMESIS.data)"
has "... and says why" "longer than NEMESIS takes, left out" "$out"
eng remove demo
mk 1.0.0

# ---- a clash: the listing fails, the mod stays in HMG-API, and says why
printf 'hmgapi.demo  other.pkg\n' >> $N/conflicts.txt
cat > $T/nemesis.wrap <<EOF
#!/bin/sh
case "\$*" in *hmgapi.demo.zip*) echo "conflict other.pkg|Other"; exit 23 ;; esac
exec $T/nemesis "\$@"
EOF
chmod 755 $T/nemesis.wrap; rm $R/system/xbin/nemesis; ln -s $T/nemesis.wrap $R/system/xbin/nemesis
eng install $T/demo.zip
check "a clashing NEMESIS package: the mod still installs in HMG-API" "0 demo " "$rc $(mods)"
has "... and the sync says why it isn't listed" "a NEMESIS package that can't be installed with it is installed (conflict other.pkg|Other)" "$out"
check "... not listed" "hmgapi " "$(listed)"

exit $fail

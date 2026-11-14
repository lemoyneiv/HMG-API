#!/bin/sh
# Off-car checks for the cache paths used by boot, late-load and app-part loaders.
set -e
cd "$(dirname "$0")/../.."
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/src/hmgapi/hook" "$T/classes"
cat > "$T/src/hmgapi/hook/ModDexCacheTest.java" <<'J'
package hmgapi.hook;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public final class ModDexCacheTest {
    static void check(boolean b, String s) { if (!b) throw new AssertionError(s); System.out.println("ok    " + s); }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        for (String scope : new String[] {"zygote", "app"}) {
            File base = new File(root, scope);
            File a = ModDexCache.directory(base, "example-a");
            File b = ModDexCache.directory(base, "ev-routing");
            File first = new File(a, "hooks.dex"), second = new File(b, "hooks.dex");
            Files.write(first.toPath(), new byte[] {1,2,3});
            Files.write(second.toPath(), new byte[] {4});
            check(!first.getCanonicalPath().equals(second.getCanonicalPath()), scope + ": same JAR basename has separate cache files");
            check(Files.readAllBytes(first.toPath()).length == 3, scope + ": second mod cannot overwrite first cache");
            check(ModDexCache.directory(base, "ev-routing").equals(b), scope + ": repeated load reuses its own directory");
        }
        for (String id : new String[] {"..", "../escape", "/absolute", "a/b", ""}) {
            try { ModDexCache.directory(root, id); throw new AssertionError("accepted " + id); }
            catch (IOException expected) { System.out.println("ok    unsafe cache id refused: " + id); }
        }
        File blocked = new File(root, "blocked"); Files.write(blocked.toPath(), new byte[] {0});
        try { ModDexCache.directory(blocked, "mod"); throw new AssertionError("accepted file as parent"); }
        catch (IOException expected) { System.out.println("ok    cache creation failure is explicit"); }

        File app = new File(root, "app-cleanup"); app.mkdirs();
        Files.write(new File(app, "hooks.dex").toPath(), new byte[] {1});
        File kept = ModDexCache.directory(app, "installed-mod");
        File stale = ModDexCache.directory(app, "removed-mod");
        Files.write(new File(kept, "hooks.dex").toPath(), new byte[] {2});
        Files.write(new File(stale, "hooks.dex").toPath(), new byte[] {3});
        File malformed = new File(app, ".bad"); malformed.mkdirs();
        Set<String> installed = new HashSet<String>(); installed.add("installed-mod");
        hmgapi.engine.DexCacheCleanup.reconcile(app, installed);
        check(!new File(app, "hooks.dex").exists(), "app: obsolete shared hooks.dex removed");
        check(kept.isDirectory(), "app: installed mod cache preserved");
        check(!stale.exists(), "app: removed mod cache deleted");
        check(malformed.isDirectory(), "app: malformed cache directory name is ignored");

        File outside = new File(root, "outside"); outside.mkdirs();
        Files.write(new File(outside, "keep").toPath(), new byte[] {4});
        try {
            Files.createSymbolicLink(new File(app, "unsafe-link").toPath(), outside.toPath());
            hmgapi.engine.DexCacheCleanup.reconcile(app, Collections.<String>emptySet());
            check(new File(outside, "keep").isFile(), "app: symlink cannot cause deletion outside cache root");
        } catch (UnsupportedOperationException e) {
            System.out.println("ok    symlink safety test unsupported on this host");
        }

        File linkedTarget = new File(root, "linked-target"); linkedTarget.mkdirs();
        Files.write(new File(linkedTarget, "hooks.dex").toPath(), new byte[] {5});
        try {
            File linkedRoot = new File(root, "linked-root");
            Files.createSymbolicLink(linkedRoot.toPath(), linkedTarget.toPath());
            hmgapi.engine.DexCacheCleanup.reconcile(linkedRoot, Collections.<String>emptySet());
            check(new File(linkedTarget, "hooks.dex").isFile(), "app: symlinked cache root is refused");
        } catch (UnsupportedOperationException e) {
            System.out.println("ok    root symlink safety test unsupported on this host");
        }
    }
}
J
javac --release 8 -Xlint:-options -d "$T/classes" core/runtime/java/hmgapi/engine/DexCacheCleanup.java core/runtime/java/hmgapi/hook/ModDexCache.java "$T/src/hmgapi/hook/ModDexCacheTest.java"
java -cp "$T/classes" hmgapi.hook.ModDexCacheTest "$T/cache"

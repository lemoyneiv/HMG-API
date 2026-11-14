package hmgapi.engine;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/** Best-effort cleanup of obsolete, mod-scoped Dalvik cache entries. */
public final class DexCacheCleanup {
    private DexCacheCleanup() {}

    public static boolean validModId(String id) {
        return id != null && id.matches("[a-z0-9][a-z0-9-]{0,63}");
    }

    /** Removes the old shared file and valid mod directories not in {@code installed}. */
    public static void reconcile(File root, Set<String> installed) {
        try {
            if (!safeRoot(root)) return;
            removeDirectFile(root, "hooks.dex");
            File[] entries = root.listFiles();
            if (entries == null) return;
            for (File entry : entries) {
                String id = entry.getName();
                if (entry.isDirectory() && validModId(id) && !installed.contains(id)) removeMod(root, id);
            }
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    /** Removes one validated, direct child directory. The cache root itself is never removed. */
    public static void removeMod(File root, String id) {
        try {
            if (!validModId(id) || !safeRoot(root)) return;
            File canonicalRoot = root.getCanonicalFile();
            File child = new File(canonicalRoot, id).getAbsoluteFile();
            File canonicalChild = child.getCanonicalFile();
            if (!child.equals(canonicalChild) || !canonicalRoot.equals(canonicalChild.getParentFile())) return;
            removeBelow(canonicalRoot, canonicalChild);
        } catch (Exception ignored) {
            // Stale optimized code is harmless; cleanup must not disrupt startup or removal.
        }
    }

    public static void removeObsoleteShared(File root) {
        try {
            if (safeRoot(root)) removeDirectFile(root, "hooks.dex");
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    private static boolean safeRoot(File root) throws IOException {
        if (root == null || !root.isDirectory()) return false;
        File absolute = root.getAbsoluteFile();
        File parent = absolute.getParentFile();
        if (parent == null) return false;
        File expected = new File(parent.getCanonicalFile(), absolute.getName()).getAbsoluteFile();
        return expected.equals(absolute.getCanonicalFile());
    }

    private static void removeDirectFile(File root, String name) {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File file = new File(canonicalRoot, name).getAbsoluteFile();
            File canonicalFile = file.getCanonicalFile();
            if (file.equals(canonicalFile) && canonicalRoot.equals(canonicalFile.getParentFile()) && canonicalFile.isFile()) canonicalFile.delete();
        } catch (Exception ignored) {
            // Best effort.
        }
    }

    private static void removeBelow(File root, File file) throws IOException {
        String prefix = root.getPath() + File.separator;
        File absolute = file.getAbsoluteFile();
        File canonical = file.getCanonicalFile();
        if (!absolute.equals(canonical) || !canonical.getPath().startsWith(prefix)) return;
        File[] children = canonical.listFiles();
        if (children != null) {
            for (File child : children) removeBelow(root, child);
        }
        canonical.delete();
    }
}

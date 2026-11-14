package libcore.io;

/**
 * Compile-only signature of the car's libcore.io.Libcore (core.jar, on the boot
 * classpath): never in hmgapi-hook.jar; the car's own class is used at run time.
 */
public final class Libcore {
    private Libcore() {}

    public static Os os;
}

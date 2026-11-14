package libcore.io;

/** Compile-only signature of the car's libcore.io.ErrnoException. */
public final class ErrnoException extends Exception {
    public ErrnoException(String functionName, int errno) {
        super(functionName);
    }
}

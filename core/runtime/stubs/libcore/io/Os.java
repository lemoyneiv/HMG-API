package libcore.io;

/** Compile-only: the one call HMG-API uses of the car's libcore.io.Os. */
public interface Os {
    void close(java.io.FileDescriptor fd) throws ErrnoException;
}

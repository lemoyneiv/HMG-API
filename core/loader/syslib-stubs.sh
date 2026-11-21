#!/bin/sh
# Link-time stand-ins for the head unit's libc.so, liblog.so and libdl.so: the loader
# links against these (each exports, by name, the functions the loader calls), and
# on the car the dynamic linker binds them to the unit's real libraries. Our own
# code: no manufacturer library is needed to build HMG-API.
#   core/loader/syslib-stubs.sh <out dir>
set -e
D=${1:?usage: syslib-stubs.sh <out dir>}
mkdir -p "$D"
mk() {   # mk <lib> <symbol>...
    lib=$1; shift
    : > "$D/$lib.c"
    for s in "$@"; do echo "void $s(void) {}" >> "$D/$lib.c"; done
    # libc also defines these (the linker then exports the loader's own, as with the real one)
    [ "$lib" = libc ] && echo "char __dso_handle, _edata, __bss_start, _end;" >> "$D/$lib.c"
    clang --target=armv7a-linux-androideabi -mthumb -march=armv7-a -mfloat-abi=softfp -fPIC -ffreestanding -fno-builtin \
      -nostdlib -shared -fuse-ld=lld -Wl,--hash-style=sysv -Wl,--build-id=none -Wl,-soname,$lib.so \
      -o "$D/$lib.so" "$D/$lib.c"
}
mk libc cacheflush calloc close dup fcntl getenv getpid getuid memcpy memset mmap mprotect open pthread_create pthread_detach read strlen unsetenv write
mk liblog __android_log_print
mk libdl dlerror dlopen dlsym

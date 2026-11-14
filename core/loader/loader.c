/*
 * libhmgapi_hook.so: preloaded into the zygote by the app_process wrapper
 * (zygote.c), so it is mapped in every app forked from it.
 *
 * Step 3: Dalvik method hooks, the same mechanism Xposed used on 4.x.
 *   1. JNI_CreateJavaVM is interposed (libandroid_runtime imports it from
 *      libnativehelper; LD_PRELOAD wins the lookup). After the real one returns,
 *      the JNIEnv's CallStaticVoidMethodV is swapped once, so the next call --
 *      AndroidRuntime::start calling ZygoteInit.main, after the framework's
 *      natives are registered -- first loads /system/framework/hmgapi-hook.jar
 *      and runs hmgapi.hook.Bridge.init(), then puts the table back.
 *   2. Bridge.hookMethodNative makes a Java method native: the original Method
 *      struct is copied into a HookInfo, and nativeFunc points at
 *      hookedMethodCallback, which boxes the arguments and calls
 *      Bridge.handleHookedMethod. invokeOriginalMethodNative runs the copy.
 * Only the zygote does any of this; other processes that load the library
 * (the wrapper's first pass, manual LD_PRELOAD tests) just log. Test mode:
 * HMGAPI_SELFTEST=<jar> in a plain app_process run loads that jar and runs
 * Bridge.selfTest() instead of Bridge.init(), away from the zygote.
 *
 * libdvm is reached with dlopen/dlsym at VM creation (no DT_NEEDED), so the
 * library still loads into processes without Dalvik. Struct offsets are the
 * AOSP 4.4 ones, checked against the EV6's libdvm.so by disassembly
 * (dvmSlotToMethod, dvmSetNativeFunc, dvmUnboxPrimitive,
 * dvmFillStackTraceElements).
 *
 * Built against the head unit's own libc.so/liblog.so/libdl.so (no NDK), so no
 * system headers. AGPL-3.0-or-later.
 */
#include <stdarg.h>

typedef unsigned long size_t;
typedef unsigned int u4;
typedef unsigned short u2;

int __android_log_print(int prio, const char *tag, const char *fmt, ...);
unsigned getuid(void);
int getpid(void);
int open(const char *path, int flags, ...);
long read(int fd, void *buf, size_t n);
int close(int fd);
void *calloc(size_t n, size_t sz);
void *memcpy(void *d, const void *s, size_t n);
size_t strlen(const char *s);
void *dlopen(const char *name, int flags);
void *dlsym(void *h, const char *name);
char *getenv(const char *name);
int unsetenv(const char *name);
void *mmap(void *addr, size_t len, int prot, int flags, int fd, long off);
int mprotect(const void *addr, size_t len, int prot);
int cacheflush(long start, long end, long flags);

#define TAG "HMG-API"
#define LOG_INFO 4
#define LOG_ERROR 6
#define LOGI(...) __android_log_print(LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(LOG_ERROR, TAG, __VA_ARGS__)

#define JAR "/system/framework/hmgapi-hook.jar"
#define BRIDGE "hmgapi.hook.Bridge"

/* ---- Dalvik 4.4 layouts (offsets checked on the EV6 libdvm) ---- */
#define ACC_STATIC 0x0008
#define ACC_NATIVE 0x0100
#define M_ACCESS(m)  (*(u4 *)((char *)(m) + 0x04))
#define M_REGS(m)    (*(u2 *)((char *)(m) + 0x0a))
#define M_OUTS(m)    (*(u2 *)((char *)(m) + 0x0c))
#define M_INS(m)     (*(u2 *)((char *)(m) + 0x0e))
#define M_SHORTY(m)  (*(const char **)((char *)(m) + 0x1c))
#define M_INSNS(m)   (*(void **)((char *)(m) + 0x20))
#define M_NATIVE(m)  (*(void **)((char *)(m) + 0x28))
#define METHOD_SIZE  0x38
#define OBJ_CLASS(o) (*(void **)(o))
#define CLASS_PRIM(c) (*(int *)((char *)(c) + 0x44))
#define PRIM_NOT 0
#define PRIM_VOID 1
#define ARRAY_DATA(a) ((void **)((char *)(a) + 0x10))

typedef union { int i; long long j; float f; double d; void *l; } JValue;
typedef void (*BridgeFunc)(const u4 *args, JValue *res, const void *method, void *self);

static struct {
    void *(*threadSelf)(void);
    void *(*decode)(void *self, void *jobj);
    void *(*slotToMethod)(void *cls, int slot);
    void (*setNativeFunc)(void *m, BridgeFunc f, const u2 *insns);
    void *(*findDirect)(const void *cls, const char *name, const char *desc);
    void *(*findArrayClass)(const char *desc, void *loader);
    void *(*findPrim)(char c);
    void *(*allocArray)(void *cls, size_t len, int flags);
    void (*barrierArray)(const void *arr, size_t start, size_t end);
    void (*release)(void *obj, void *self);
    void *(*box)(JValue v, void *cls);
    int (*unbox)(void *obj, void *cls, JValue *out);
    void *(*boxedReturnType)(const void *m);
    void (*callMethod)(void *self, const void *m, void *obj, JValue *res, ...);
    void *(*invoke)(void *obj, const void *m, void *args, void *params, void *ret, int noAccessCheck);
    int (*checkException)(void *self);
    void (*throwNPE)(const char *msg);
    void (*throwCCE)(void *actual, void *desired);
} dvm;

static struct { const char *name; void **slot; } dvmSyms[] = {
    { "_Z13dvmThreadSelfv", (void **)&dvm.threadSelf },
    { "_Z20dvmDecodeIndirectRefP6ThreadP8_jobject", (void **)&dvm.decode },
    { "_Z15dvmSlotToMethodP11ClassObjecti", (void **)&dvm.slotToMethod },
    { "_Z16dvmSetNativeFuncP6MethodPFvPKjP6JValuePKS_P6ThreadEPKt", (void **)&dvm.setNativeFunc },
    { "_Z31dvmFindDirectMethodByDescriptorPK11ClassObjectPKcS3_", (void **)&dvm.findDirect },
    { "_Z17dvmFindArrayClassPKcP6Object", (void **)&dvm.findArrayClass },
    { "_Z21dvmFindPrimitiveClassc", (void **)&dvm.findPrim },
    { "dvmAllocArrayByClass", (void **)&dvm.allocArray },
    { "_Z20dvmWriteBarrierArrayPK11ArrayObjectjj", (void **)&dvm.barrierArray },
    { "dvmReleaseTrackedAlloc", (void **)&dvm.release },
    { "_Z15dvmBoxPrimitive6JValueP11ClassObject", (void **)&dvm.box },
    { "_Z17dvmUnboxPrimitiveP6ObjectP11ClassObjectP6JValue", (void **)&dvm.unbox },
    { "_Z21dvmGetBoxedReturnTypePK6Method", (void **)&dvm.boxedReturnType },
    { "_Z13dvmCallMethodP6ThreadPK6MethodP6ObjectP6JValuez", (void **)&dvm.callMethod },
    { "_Z15dvmInvokeMethodP6ObjectPK6MethodP11ArrayObjectS5_P11ClassObjectb", (void **)&dvm.invoke },
    { "_Z17dvmCheckExceptionP6Thread", (void **)&dvm.checkException },
    { "dvmThrowNullPointerException", (void **)&dvm.throwNPE },
    { "dvmThrowClassCastException", (void **)&dvm.throwCCE },
};

/* ---- minimal JNI: the function table by index (JNI spec numbering) ---- */
typedef void *jobject;
typedef void **JNIEnv;   /* *env is the function table */
#define JNI_TABLE_SIZE 233
#define J(env, idx, type) ((type)((*(void ***)(env))[idx]))
#define FindClass(e, n)               J(e, 6, jobject (*)(JNIEnv *, const char *))(e, n)
#define ExceptionDescribe(e)          J(e, 16, void (*)(JNIEnv *))(e)
#define ExceptionClear(e)             J(e, 17, void (*)(JNIEnv *))(e)
#define NewGlobalRef(e, o)            J(e, 21, jobject (*)(JNIEnv *, jobject))(e, o)
#define NewObject(e, c, m, ...)       J(e, 28, jobject (*)(JNIEnv *, jobject, void *, ...))(e, c, m, __VA_ARGS__)
#define GetMethodID(e, c, n, s)       J(e, 33, void *(*)(JNIEnv *, jobject, const char *, const char *))(e, c, n, s)
#define CallObjectMethod(e, o, m, ...) J(e, 34, jobject (*)(JNIEnv *, jobject, void *, ...))(e, o, m, __VA_ARGS__)
#define GetStaticMethodID(e, c, n, s) J(e, 113, void *(*)(JNIEnv *, jobject, const char *, const char *))(e, c, n, s)
#define CallStaticObjectMethod0(e, c, m) J(e, 114, jobject (*)(JNIEnv *, jobject, void *, ...))(e, c, m)
#define CallStaticVoidMethod0(e, c, m) J(e, 141, void (*)(JNIEnv *, jobject, void *, ...))(e, c, m)
#define NewStringUTF(e, s)            J(e, 167, jobject (*)(JNIEnv *, const char *))(e, s)
#define GetStringUTFChars(e, s)       J(e, 169, const char *(*)(JNIEnv *, jobject, void *))(e, s, 0)
#define ReleaseStringUTFChars(e, s, c) J(e, 170, void (*)(JNIEnv *, jobject, const char *))(e, s, c)
#define RegisterNatives(e, c, m, n)   J(e, 215, int (*)(JNIEnv *, jobject, const void *, int))(e, c, m, n)
#define GetArrayLength(e, a)          J(e, 171, int (*)(JNIEnv *, jobject))(e, a)
#define NewByteArray(e, n)            J(e, 176, jobject (*)(JNIEnv *, int))(e, n)
#define GetByteArrayRegion(e, a, o, n, b) J(e, 200, void (*)(JNIEnv *, jobject, int, int, void *))(e, a, o, n, b)
#define SetByteArrayRegion(e, a, o, n, b) J(e, 208, void (*)(JNIEnv *, jobject, int, int, const void *))(e, a, o, n, b)
#define ExceptionCheck(e)             J(e, 228, int (*)(JNIEnv *))(e)
#define IDX_CallStaticVoidMethodV 142

typedef struct { const char *name, *sig; void *fn; } JNINativeMethod;

/* ---- hooks ---- */
typedef struct {
    char original[METHOD_SIZE];   /* copy of the Method before it was hooked */
    void *reflected;              /* java.lang.reflect.Member (global ref keeps it alive) */
    void *info;                   /* Bridge's per-method hook list (global ref) */
} HookInfo;

static void *gHandleHooked;      /* Method* of Bridge.handleHookedMethod */
static void *gObjectArrayClass;  /* [Ljava/lang/Object; */

static void hookedMethodCallback(const u4 *args, JValue *res, const void *method, void *self)
{
    HookInfo *h = (HookInfo *)M_INSNS(method);
    const char *d = M_SHORTY(method) + 1;   /* [0] is the return type */
    void *thisObj = 0, *arr, *ret;
    size_t src = 0, dst = 0;
    JValue r;

    if (!(M_ACCESS(h->original) & ACC_STATIC)) thisObj = (void *)args[src++];
    arr = dvm.allocArray(gObjectArrayClass, strlen(d), 0);
    if (!arr) return;
    for (; *d; d++) {
        JValue v;
        void *o;
        switch (*d) {
        case 'Z': case 'B': case 'C': case 'S': case 'I': case 'F':
            v.j = 0; v.i = (int)args[src++];
            o = dvm.box(v, dvm.findPrim(*d));
            dvm.release(o, self);
            break;
        case 'J': case 'D':
            v.j = (long long)args[src] | ((long long)args[src + 1] << 32);
            src += 2;
            o = dvm.box(v, dvm.findPrim(*d));
            dvm.release(o, self);
            break;
        default:   /* 'L' and '[' */
            o = (void *)args[src++];
        }
        ARRAY_DATA(arr)[dst] = o;
        if (o) dvm.barrierArray(arr, dst, dst + 1);
        dst++;
    }
    r.j = 0;
    dvm.callMethod(self, gHandleHooked, 0, &r, h->reflected, (int)h, h->info, thisObj, arr);
    dvm.release(arr, self);
    if (dvm.checkException(self)) return;
    ret = dvm.boxedReturnType(method);
    if (!ret || CLASS_PRIM(ret) == PRIM_VOID) return;
    if (!r.l) {
        if (CLASS_PRIM(ret) != PRIM_NOT) dvm.throwNPE("null result when primitive expected");
        else res->l = 0;
        return;
    }
    if (!dvm.unbox(r.l, ret, res)) dvm.throwCCE(OBJ_CLASS(r.l), ret);
}

/* Bridge.invokeOriginalMethodNative(Member, int hookInfo, Class[] params,
   Class ret, Object this, Object[] args): an internal (non-JNI) native. */
static void invokeOriginal(const u4 *args, JValue *res, const void *method, void *self)
{
    HookInfo *h = (HookInfo *)args[1];
    (void)method; (void)self;
    res->l = dvm.invoke((void *)args[4], h->original, (void *)args[5], (void *)args[2], (void *)args[3], 1);
}

/* Bridge.hookMethodNative(Member, Class declaring, int slot, Object info): JNI. */
static void n_hookMethod(JNIEnv *env, jobject cls, jobject member, jobject decl, int slot, jobject info)
{
    void *self = dvm.threadSelf();
    void *m = dvm.slotToMethod(dvm.decode(self, decl), slot);
    HookInfo *h;
    (void)cls;
    if (!m) { LOGE("hook: no method for slot %d", slot); return; }
    if (M_NATIVE(m) == (void *)hookedMethodCallback) return;   /* already hooked */
    h = calloc(1, sizeof *h);
    if (!h) return;
    memcpy(h->original, m, METHOD_SIZE);
    h->reflected = dvm.decode(self, NewGlobalRef(env, member));
    h->info = dvm.decode(self, NewGlobalRef(env, info));
    M_ACCESS(m) |= ACC_NATIVE;
    M_NATIVE(m) = (void *)hookedMethodCallback;
    M_INSNS(m) = h;
    M_REGS(m) = M_INS(m);
    M_OUTS(m) = 0;
}

static void n_log(JNIEnv *env, jobject cls, int prio, jobject msg)
{
    const char *s;
    (void)cls;
    if (!msg) return;
    s = GetStringUTFChars(env, msg);
    if (!s) return;
    __android_log_print(prio, TAG, "%s", s);
    ReleaseStringUTFChars(env, msg, s);
}

/*
 * Native patches (hmgapi.hook.NativePatch): reading and writing this process's own
 * code. The Java side finds every address in /proc/self/maps and checks the bytes
 * (md5) before it writes; these only move bytes.
 */
#define PAGE 4096UL
#define PROT_RX 5   /* PROT_READ | PROT_EXEC */
#define PROT_RWX 7

/* Bridge.peekNative(long addr, int len): a copy of len bytes */
static jobject n_peek(JNIEnv *env, jobject cls, long long addr, int len)
{
    jobject a;
    (void)cls;
    if (len < 0) return 0;
    a = NewByteArray(env, len);
    if (a) SetByteArrayRegion(env, a, 0, len, (const void *)(size_t)addr);
    return a;
}

/* Bridge.pokeNative(long addr, byte[] data): writes code (pages writable for the copy, then read+exec again); 0 or -1 */
static int n_poke(JNIEnv *env, jobject cls, long long addr, jobject data)
{
    size_t a = (size_t)addr, n, start, end;
    (void)cls;
    if (!data) return -1;
    n = (size_t)GetArrayLength(env, data);
    start = a & ~(PAGE - 1);
    end = (a + n + PAGE - 1) & ~(PAGE - 1);
    if (mprotect((void *)start, end - start, PROT_RWX) != 0) {
        LOGE("native patch: mprotect %lx failed", (unsigned long)start);
        return -1;
    }
    GetByteArrayRegion(env, data, 0, (int)n, (void *)a);
    cacheflush((long)a, (long)(a + n), 0);
    mprotect((void *)start, end - start, PROT_RX);
    return 0;
}

/* Bridge.mapCodeNative(long hint, int len): an anonymous page run for code, at hint if the kernel can (0: none) */
static long long n_mapCode(JNIEnv *env, jobject cls, long long hint, int len)
{
    void *p;
    (void)env; (void)cls;
    p = mmap((void *)(size_t)hint, ((size_t)len + PAGE - 1) & ~(PAGE - 1), PROT_RWX, 0x22 /* MAP_PRIVATE | MAP_ANONYMOUS */, -1, 0);
    return p == (void *)-1 ? 0 : (long long)(size_t)p;
}

int n_bleInit(void *env, void *cls, int tx_read_fd, int rx_write_fd);
int n_bleState(void *env, void *cls, int listening, int handle_op);
int n_bleStat(void *env, void *cls, int which);
void n_bleShutdown(void *env, void *cls);

static const JNINativeMethod natives[] = {
    { "log", "(ILjava/lang/String;)V", (void *)n_log },
    { "peekNative", "(JI)[B", (void *)n_peek },
    { "pokeNative", "(J[B)I", (void *)n_poke },
    { "mapCodeNative", "(JI)J", (void *)n_mapCode },
    { "bleInitNative", "(II)I", (void *)n_bleInit },
    { "bleStateNative", "(II)I", (void *)n_bleState },
    { "bleStatNative", "(I)I", (void *)n_bleStat },
    { "bleShutdownNative", "()V", (void *)n_bleShutdown },
    { "hookMethodNative", "(Ljava/lang/reflect/Member;Ljava/lang/Class;ILjava/lang/Object;)V", (void *)n_hookMethod },
};

/* ---- start-up ---- */
static int failed(JNIEnv *env, const char *what)
{
    if (!ExceptionCheck(env)) return 0;
    LOGE("init: %s failed:", what);
    ExceptionDescribe(env);
    ExceptionClear(env);
    return 1;
}

static int load_dvm(void)
{
    void *h = dlopen("libdvm.so", 0);
    size_t i;
    if (!h) { LOGE("init: dlopen libdvm.so failed"); return 0; }
    for (i = 0; i < sizeof dvmSyms / sizeof dvmSyms[0]; i++) {
        *dvmSyms[i].slot = dlsym(h, dvmSyms[i].name);
        if (!*dvmSyms[i].slot) { LOGE("init: libdvm has no %s", dvmSyms[i].name); return 0; }
    }
    return 1;
}

static void start_bridge(JNIEnv *env, const char *jar, const char *entry)
{
    jobject clCls, parent, pcl, loader, bridge;
    void *self, *bc, *m;
    int fd = open(jar, 0);

    if (fd < 0) { LOGI("no %s, hooks off", jar); return; }
    close(fd);
    if (!load_dvm()) return;
    clCls = FindClass(env, "java/lang/ClassLoader");
    if (failed(env, "ClassLoader")) return;
    parent = CallStaticObjectMethod0(env, clCls,
        GetStaticMethodID(env, clCls, "getSystemClassLoader", "()Ljava/lang/ClassLoader;"));
    if (failed(env, "getSystemClassLoader")) return;
    pcl = FindClass(env, "dalvik/system/PathClassLoader");
    if (failed(env, "PathClassLoader")) return;
    loader = NewObject(env, pcl, GetMethodID(env, pcl, "<init>", "(Ljava/lang/String;Ljava/lang/ClassLoader;)V"),
                       NewStringUTF(env, jar), parent);
    if (failed(env, "new PathClassLoader")) return;
    bridge = CallObjectMethod(env, loader,
        GetMethodID(env, clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;"), NewStringUTF(env, BRIDGE));
    if (failed(env, "loadClass " BRIDGE) || !bridge) return;
    bridge = NewGlobalRef(env, bridge);
    if (RegisterNatives(env, bridge, natives, (int)(sizeof natives / sizeof natives[0])) != 0 || failed(env, "RegisterNatives")) return;

    self = dvm.threadSelf();
    bc = dvm.decode(self, bridge);
    m = dvm.findDirect(bc, "invokeOriginalMethodNative",
        "(Ljava/lang/reflect/Member;I[Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    gHandleHooked = dvm.findDirect(bc, "handleHookedMethod",
        "(Ljava/lang/reflect/Member;ILjava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    gObjectArrayClass = dvm.findArrayClass("[Ljava/lang/Object;", 0);
    if (!m || !gHandleHooked || !gObjectArrayClass) { LOGE("init: bridge methods not found"); return; }
    dvm.setNativeFunc(m, invokeOriginal, 0);

    m = GetStaticMethodID(env, bridge, entry, "()V");
    if (failed(env, entry) || !m) return;
    CallStaticVoidMethod0(env, bridge, m);
    if (failed(env, entry)) return;
    LOGI("bridge ready in %d (%s)", getpid(), entry);
}

static void *tableCopy[JNI_TABLE_SIZE];
static void **tableOrig;
static int started;
static const char *selftest;

typedef void (*CallStaticVoidV)(JNIEnv *, jobject, void *, va_list);

static void my_CallStaticVoidMethodV(JNIEnv *env, jobject cls, void *mid, va_list ap)
{
    *(void ***)env = tableOrig;          /* one shot: put the real table back */
    if (!started) {
        started = 1;
        if (selftest) start_bridge(env, selftest, "selfTest");
        else start_bridge(env, JAR, "init");
    }
    ((CallStaticVoidV)tableOrig[IDX_CallStaticVoidMethodV])(env, cls, mid, ap);
}

static int is_zygote(void)
{
    char b[256];
    long n, i = 0;
    int fd = open("/proc/self/cmdline", 0);
    if (fd < 0) return 0;
    n = read(fd, b, sizeof b - 1);
    close(fd);
    if (n <= 0) return 0;
    b[n] = 0;
    while (i < n) {   /* any argument equal to "--zygote" */
        const char *a = b + i, *z = "--zygote";
        while (*z && *a == *z) a++, z++;
        if (!*z && !*a) return 1;
        while (i < n && b[i]) i++;
        i++;
    }
    return 0;
}

typedef int (*CreateVM)(void **vm, JNIEnv **env, void *args);

int JNI_CreateJavaVM(void **vm, JNIEnv **env, void *args)
{
    static CreateVM real;
    int rc;
    if (!real) {
        void *h = dlopen("libnativehelper.so", 0);
        real = h ? (CreateVM)dlsym(h, "JNI_CreateJavaVM") : 0;
        if (!real || real == JNI_CreateJavaVM) { LOGE("no real JNI_CreateJavaVM"); return -1; }
    }
    rc = real(vm, env, args);
    selftest = is_zygote() ? 0 : getenv("HMGAPI_SELFTEST");
    if (rc == 0 && env && *env && (selftest || is_zygote())) {
        tableOrig = *(void ***)*env;
        memcpy(tableCopy, tableOrig, sizeof tableCopy);
        tableCopy[IDX_CallStaticVoidMethodV] = (void *)my_CallStaticVoidMethodV;
        *(void ***)*env = tableCopy;
        /* every app forks from here: don't pass the preload on to programs they
           start. (Not earlier: stock app_process re-execs itself once and needs it.) */
        if (!selftest) unsetenv("LD_PRELOAD");
        LOGI("VM created in %d (%s), bridge starts with main", getpid(), selftest ? "self-test" : "zygote");
    }
    return rc;
}

__attribute__((constructor)) static void hmgapi_init(void)
{
    LOGI("loaded in %d (uid %u)", getpid(), getuid());
}

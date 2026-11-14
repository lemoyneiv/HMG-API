/*
 * hmgapid: HMG-API's root daemon, and its client (same binary).
 *
 *   hmgapid daemon          start the daemon (needs full root)
 *   hmgapid boot            the boot hook: returns at once; detached, starts the daemon
 *                           if none runs, and the first call each boot runs ROOTDIR/boot.sh
 *   hmgapid <op> [args...]  ask the daemon; prints its output, exits with its code
 *
 * Started from the boot hook (init's flash_recovery / qb_runtime), so it has
 * full capabilities. Clients are unprivileged: they connect to the abstract
 * Unix socket "@hmgapid", and the daemon checks who they are (SO_PEERCRED):
 *
 *   uid 0 (root), 2000 (shell/adb)  every op, including sh and remount
 *   the hmgapi.app package's uid    the HMG-API ops below (not sh)
 *   anyone else                     ping, version
 *
 * HMG-API ops: mods, enable <id>, disable <id>, setting-get <id>,
 * setting-set <id> <json>, log, reboot, engine <plan|apply|keep|restore|
 * status|recover> (the patch engine, run as root with app_process and the
 * runtime jar). Root ops: sh <command>, remount rw|ro.
 *
 * Started from NEMESIS's daemon (native/su.c, util.c), with what reviewing it
 * turned up fixed:
 *   - any uid got root (sockets 0666, peer uid only logged): ops are per uid
 *   - pidfile "already running" check could match an unrelated process with
 *     the same pid and start time: the socket name is the single-instance lock
 *   - umask 0 made every file world-writable: umask 077, explicit modes
 *   - /system remounted rw at every start and left rw: only on request
 *   - output buffered until exit and silently cut at 512 KB: streamed frames
 *   - no limits: a silent client held a forked worker forever: request
 *     timeout, at most MAX_WORKERS at once
 *   - started from adb it ran with adbd's capability set (no remount, no
 *     DAC override): refuses to start without full root
 *
 * Wire format (little-endian): request "LFD1", u32 argc, then argc x
 * (u32 len, bytes). Response frames: 'O' u32 len bytes (output), then
 * 'X' u32 exit code.
 *
 * Freestanding for ARM (no libc, raw syscalls), like lpatch.
 * AGPL-3.0-or-later; parts derived from NEMESIS (AGPL-3.0, xwtk).
 */
typedef unsigned long ul;
typedef unsigned int u32;
typedef unsigned char u8;
typedef long sl;

#define VERSION "1.1.0-beta.1"

#ifndef ROOTDIR
#define ROOTDIR "/data/hmgapi"
#endif
#ifndef SH
#define SH "/system/bin/sh"
#endif
#ifndef LOGCAT
#define LOGCAT "/system/bin/logcat"
#endif
#ifndef REBOOT
#define REBOOT "/system/bin/reboot"
#endif
#ifndef PACKAGES_LIST
#define PACKAGES_LIST "/data/system/packages.list"
#endif
#ifndef SOCK_NAME
#define SOCK_NAME "hmgapid"
#endif
#ifndef GETPROP
#define GETPROP "/system/bin/getprop"
#endif
#ifndef BOOTMARK
#define BOOTMARK "/dev/.hmgapi-boot"   /* tmpfs: empty again at every boot */
#endif
#ifndef USBROOT
#define USBROOT ""
#endif
#ifndef VEHICLE_SOCK
#define VEHICLE_SOCK "hmgapi-vehicle"   /* HMG-API's vehicle service (hmgapi.vehicle.Hub) */
#endif
#ifndef SHELL_UID
#define SHELL_UID 2000
#endif
#ifndef APP_PROCESS
#define APP_PROCESS "/system/bin/app_process"
#endif
#ifndef ENGINE_JAR
#define ENGINE_JAR "/system/framework/hmgapi-hook.jar"
#endif
#define APP_PACKAGE "hmgapi.app"
#define LOGFILE ROOTDIR "/hmgapid.log"
#define MAX_ARGS 16
#define MAX_ARG (64 * 1024)
#define MAX_REQ (128 * 1024)
#define MAX_WORKERS 8
#define REQ_TIMEOUT 30

/* ---------------- syscalls ---------------- */
static sl sys6(sl n, sl a, sl b, sl c, sl d, sl e, sl f)
{
    register sl r7 asm("r7") = n; register sl r0 asm("r0") = a;
    register sl r1 asm("r1") = b; register sl r2 asm("r2") = c;
    register sl r3 asm("r3") = d; register sl r4 asm("r4") = e;
    register sl r5 asm("r5") = f;
    asm volatile("svc 0" : "+r"(r0) : "r"(r7), "r"(r1), "r"(r2), "r"(r3), "r"(r4), "r"(r5) : "memory");
    return r0;
}
#define S0(n) sys6(n, 0, 0, 0, 0, 0, 0)
#define S1(n, a) sys6(n, (sl)(a), 0, 0, 0, 0, 0)
#define S2(n, a, b) sys6(n, (sl)(a), (sl)(b), 0, 0, 0, 0)
#define S3(n, a, b, c) sys6(n, (sl)(a), (sl)(b), (sl)(c), 0, 0, 0)
#define S4(n, a, b, c, d) sys6(n, (sl)(a), (sl)(b), (sl)(c), (sl)(d), 0, 0)
#define S5(n, a, b, c, d, e) sys6(n, (sl)(a), (sl)(b), (sl)(c), (sl)(d), (sl)(e), 0)

#define x_exit(c) do { S1(248, c); for (;;) ; } while (0)   /* exit_group */
#define x_fork() S0(2)
#define x_read(fd, b, n) S3(3, fd, b, n)
#define x_write(fd, b, n) S3(4, fd, b, n)
#define x_open(p, f, m) S3(5, p, f, m)
#define x_close(fd) S1(6, fd)
#define x_unlink(p) S1(10, p)
#define x_execve(p, a, e) S3(11, p, a, e)
/* time(2) (13) is OABI only: an EABI kernel (the head units) answers -ENOSYS. clock_gettime (263) is there. */
#define x_clock_gettime(c, ts) S2(263, c, ts)
static long clock_sec(int clk)
{
    struct { sl sec, nsec; } ts = { 0, 0 };
    if (x_clock_gettime(clk, &ts) < 0) return 0;
    return ts.sec;
}
#define x_time() clock_sec(1)        /* CLOCK_MONOTONIC: timers and backoff (a clock set after boot can't stall them) */
#define x_walltime() clock_sec(0)    /* CLOCK_REALTIME: the log */
#define x_chmod(p, m) S2(15, p, m)
#define x_getpid() S0(20)
#define x_mount(s, t, f, fl, d) S5(21, s, t, f, fl, d)
#define x_alarm(s) S1(27, s)
#define x_kill(p, s) S2(37, p, s)
#define x_rename(a, b) S2(38, a, b)
#define x_mkdir(p, m) S2(39, p, m)
#define x_pipe(fds) S1(42, fds)
#define x_ioctl(fd, r, a) S3(54, fd, r, a)
#define x_umask(m) S1(60, m)
#define x_dup2(a, b) S2(63, a, b)
#define x_setsid() S0(66)
#define x_wait4(p, st, o) S4(114, p, st, o, 0)
#define x_fsync(fd) S1(118, fd)
#define x_poll(f, n, t) S3(168, f, n, t)
#define x_sigaction(s, a, o) S4(174, s, a, o, 8)
#define x_getdents64(fd, b, n) S3(217, fd, b, n)
#define x_socket(d, t, p) S3(281, d, t, p)
#define x_bind(fd, a, l) S3(282, fd, a, l)
#define x_connect(fd, a, l) S3(283, fd, a, l)
#define x_listen(fd, n) S2(284, fd, n)
#define x_accept(fd) S3(285, fd, 0, 0)
#define x_setsockopt(fd, l, o, v, n) S5(294, fd, l, o, v, n)
#define x_getsockopt(fd, l, o, v, n) S5(295, fd, l, o, v, n)
#define x_umount2(t, f) S2(52, t, f)
#define x_nanosleep(r, m) S2(162, r, m)
#define x_stat64(p, st) S2(195, p, st)
#define x_chdir(p) S1(12, p)
#define x_geteuid() S0(201)
#define x_setgroups(n, l) S2(206, n, l)
#define x_setresuid(a, b, c) S3(208, a, b, c)
#define x_setresgid(a, b, c) S3(210, a, b, c)
#define x_chown(p, u, g) S3(212, p, u, g)
#define MS_BIND 4096
#define MNT_DETACH 2

#define O_RDONLY 0
#define O_WRONLY 1
#define O_RDWR 2
#define O_CREAT 0100
#define O_EXCL 0200
#define O_TRUNC 01000
#define O_APPEND 02000
#define O_DIRECTORY 040000
#define O_CLOEXEC 02000000
#define EINTR 4
#define EADDRINUSE 98
#define ECONNREFUSED 111
#define ENOENT 2
#define SIGKILL 9
#define SIGPIPE 13
#define SIGTERM 15
#define SIGALRM 14
#define WNOHANG 1
#define POLLIN 1
#define MS_RDONLY 1
#define MS_NOSUID 2
#define MS_NODEV 4
#define MS_NOEXEC 8
#define MS_REMOUNT 32
#define BLKROSET 0x125d

/* ---------------- small helpers ---------------- */
static ul slen(const char *s) { ul n = 0; while (s[n]) n++; return n; }
static int seq(const char *a, const char *b) { while (*a && *a == *b) a++, b++; return *a == *b; }
static int spre(const char *s, const char *p) { while (*p && *s == *p) s++, p++; return !*p; }
static void mcpy(void *d, const void *s, ul n) { u8 *a = d; const u8 *b = s; while (n--) *a++ = *b++; }
static void mset(void *d, int c, ul n) { u8 *a = d; while (n--) *a++ = (u8)c; }
static const char *sstr(const char *h, const char *n)
{
    ul k = slen(n);
    for (; *h; h++) { ul i = 0; while (i < k && h[i] == n[i]) i++; if (i == k) return h; }
    return 0;
}

static int wall(int fd, const void *b, ul n)
{
    const u8 *p = b;
    while (n) {
        sl r = x_write(fd, p, n);
        if (r == -EINTR) continue;
        if (r <= 0) return -1;
        p += r; n -= (ul)r;
    }
    return 0;
}
static int rall(int fd, void *b, ul n)
{
    u8 *p = b;
    while (n) {
        sl r = x_read(fd, p, n);
        if (r == -EINTR) continue;
        if (r <= 0) return -1;
        p += r; n -= (ul)r;
    }
    return 0;
}
static void out(int fd, const char *s) { wall(fd, s, slen(s)); }

/* fixed-size line builder */
typedef struct { char b[512]; ul n; } line_t;
static void lput(line_t *l, const char *s) { while (*s && l->n < sizeof l->b - 2) l->b[l->n++] = *s++; l->b[l->n] = 0; }
static void lnum(line_t *l, sl v)
{
    char t[12]; int i = 0;
    if (v < 0) { lput(l, "-"); v = -v; }
    do t[i++] = (char)('0' + v % 10); while ((v /= 10) && i < 11);
    while (i) { char c[2] = { t[--i], 0 }; lput(l, c); }
}

/* ---------------- log ---------------- */
static void logline(const char *a, const char *b, sl n1, const char *c)
{
    line_t l; sl fd;
    l.n = 0; l.b[0] = 0;
    lnum(&l, x_walltime()); lput(&l, " ");
    lput(&l, a);
    if (b) { lput(&l, " "); lput(&l, b); }
    if (n1 != -1) { lput(&l, " "); lnum(&l, n1); }
    if (c) { lput(&l, " "); lput(&l, c); }
    lput(&l, "\n");
    fd = x_open(LOGFILE, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    if (fd < 0) return;
    wall((int)fd, l.b, l.n);
    x_close(fd);
}

/* ---------------- frames ---------------- */
static int frame(int fd, char type, const void *data, u32 n)
{
    u8 h[5];
    h[0] = (u8)type; h[1] = (u8)n; h[2] = (u8)(n >> 8); h[3] = (u8)(n >> 16); h[4] = (u8)(n >> 24);
    if (wall(fd, h, 5)) return -1;
    return n ? wall(fd, data, n) : 0;
}
static int say(int fd, const char *s) { return frame(fd, 'O', s, (u32)slen(s)); }
static int done(int fd, int code)
{
    u8 c[4] = { (u8)code, (u8)(code >> 8), (u8)(code >> 16), (u8)(code >> 24) };
    return frame(fd, 'X', c, 4);
}

/* ---------------- socket ---------------- */
struct sun { unsigned short family; char path[108]; };
static int sockaddr_of(struct sun *a, const char *name)
{
    mset(a, 0, sizeof *a);
    a->family = 1;   /* AF_UNIX, abstract: path[0] = 0 */
    mcpy(a->path + 1, name, slen(name));
    return 2 + 1 + (int)slen(name);
}

static int sockaddr(struct sun *a)
{
    return sockaddr_of(a, SOCK_NAME);
}

/* ---------------- who is asking ---------------- */
struct ucred { int pid; u32 uid; u32 gid; };

/* A package's line in packages.list ("<package> <uid> <debug> <data dir> <seinfo> <gids>"):
   its uid, and its gids (comma list or "none") into gids[] when given; -1 if it's not there. */
static int read_small(const char *p, char *buf, ul cap);
static int pkg_uid(const char *pkg, u32 *gids, int *ngids)
{
    static char buf[64 * 1024];
    sl fd = x_open(PACKAGES_LIST, O_RDONLY | O_CLOEXEC, 0), n, i = 0;
    ul k = slen(pkg);
    if (ngids) *ngids = 0;
    if (fd < 0) return -1;
    n = x_read(fd, buf, sizeof buf - 1);
    x_close(fd);
    if (n <= 0) return -1;
    buf[n] = 0;
    while (i < n) {
        char *ln = buf + i;
        sl e = i;
        while (e < n && buf[e] != '\n') e++;
        buf[e] = 0;
        if (spre(ln, pkg) && ln[k] == ' ') {
            int uid = 0, f;
            char *p = ln + k + 1;
            while (*p >= '0' && *p <= '9') uid = uid * 10 + (*p++ - '0');
            for (f = 0; f < 4 && *p; f++) { while (*p == ' ') p++; while (*p && *p != ' ') p++; }   /* to the gids */
            while (*p == ' ') p++;
            while (gids && ngids && *p >= '0' && *p <= '9' && *ngids < 16) {
                u32 g = 0;
                while (*p >= '0' && *p <= '9') g = g * 10 + (u32)(*p++ - '0');
                gids[(*ngids)++] = g;
                if (*p == ',') p++;
            }
            return uid > 0 ? uid : -1;
        }
        i = e + 1;
    }
    return -1;
}

static int app_uid(void) { return pkg_uid(APP_PACKAGE, 0, 0); }

/* The mod whose app (state/apps.txt: "<package> <mod>", enabled mods only) runs as uid. */
static char g_mod[72];
static int mod_of_uid(u32 uid)
{
    static char apps[8192];
    char *p;
    if (read_small(ROOTDIR "/state/apps.txt", apps, sizeof apps) <= 0) return 0;
    for (p = apps; *p; ) {
        char *e = p, *sp = 0;
        while (*e && *e != '\n') { if (*e == ' ' && !sp) sp = e; e++; }
        if (sp) {
            char pkg[160];
            ul n = (ul)(sp - p);
            if (n < sizeof pkg) {
                mcpy(pkg, p, n); pkg[n] = 0;
                if (pkg_uid(pkg, 0, 0) == (int)uid) {
                    ul m = (ul)(e - sp - 1);
                    if (m && m < sizeof g_mod) { mcpy(g_mod, sp + 1, m); g_mod[m] = 0; return 1; }
                }
            }
        }
        p = *e ? e + 1 : e;
    }
    return 0;
}

/* A mod's service (or a process it started): every service runs in its own session
   (setsid), so the caller's session id is a running service's pid (state/services.status). */
static int mod_of_pid(int pid)
{
    static char st[8192];
    char p[48], stat[512], *q, *r;
    int k, sid = 0;
    line_t l;
    l.n = 0; l.b[0] = 0;
    lput(&l, "/proc/"); lnum(&l, pid); lput(&l, "/stat");
    mcpy(p, l.b, l.n + 1);
    if (read_small(p, stat, sizeof stat) <= 0) return 0;
    q = stat; r = 0;
    for (; *q; q++) if (*q == ')') r = q;   /* after "pid (comm)": comm may hold spaces */
    if (!r) return 0;
    q = r + 1;
    for (k = 0; k < 3 && *q; k++) { while (*q == ' ') q++; while (*q && *q != ' ') q++; }   /* past state ppid pgrp, to the session */
    while (*q == ' ') q++;
    while (*q >= '0' && *q <= '9') sid = sid * 10 + (*q++ - '0');
    if (sid <= 0 || read_small(ROOTDIR "/state/services.status", st, sizeof st) <= 0) return 0;
    for (q = st; *q; ) {   /* "<mod> <name> <state> <pid> <fails>" */
        char *e = q, *f[5];
        int n = 0, v = 0;
        while (*e && *e != '\n') e++;
        if (*e) *e++ = 0;
        while (n < 5 && *q) { f[n++] = q; while (*q && *q != ' ') q++; if (*q) *q++ = 0; }
        if (n == 5) {
            char *d = f[3];
            while (*d >= '0' && *d <= '9') v = v * 10 + (*d++ - '0');
            if (v == sid && seq(f[2], "running") && slen(f[0]) < sizeof g_mod) { mcpy(g_mod, f[0], slen(f[0]) + 1); return 1; }
        }
        q = e;
    }
    return 0;
}

enum { WHO_OTHER, WHO_MOD, WHO_APP, WHO_ROOT };
static int who(u32 uid, int pid)
{
    int a;
    g_mod[0] = 0;
    if (uid == 0 || uid == SHELL_UID) { mod_of_pid(pid); return WHO_ROOT; }   /* a root service: g_mod for whoami */
    if (mod_of_pid(pid)) return WHO_MOD;
    a = app_uid();
    if (a >= 0 && (u32)a == uid) return WHO_APP;
    return mod_of_uid(uid) ? WHO_MOD : WHO_OTHER;
}

/* ---------------- ops ---------------- */
static int valid_id(const char *id)
{
    ul n = slen(id), i;
    if (n == 0 || n > 64 || id[0] == '.' || seq(id, "hmgapi")) return 0;
    for (i = 0; i < n; i++) {
        char c = id[i];
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) return 0;
    }
    return 1;
}

static void path3(char *dst, ul cap, const char *a, const char *b, const char *c)
{
    ul n = 0;
    const char *parts[3] = { a, b, c };
    int k;
    for (k = 0; k < 3; k++) {
        const char *s = parts[k];
        while (s && *s && n < cap - 1) dst[n++] = *s++;
    }
    dst[n] = 0;
}

static int exists(const char *p) { sl fd = x_open(p, O_RDONLY | O_CLOEXEC, 0); if (fd < 0) return 0; x_close(fd); return 1; }
static int isdir(const char *p) { sl fd = x_open(p, O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0); if (fd < 0) return 0; x_close(fd); return 1; }

static void publish_state(void);

static int op_mods(int fd)
{
    static char buf[8192];
    sl d = x_open(ROOTDIR "/mods", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0), n;
    if (d < 0) { say(fd, ""); return 0; }
    while ((n = x_getdents64(d, buf, sizeof buf)) > 0) {
        sl off = 0;
        while (off < n) {
            /* linux_dirent64: u64 ino, s64 off, u16 reclen, u8 type, char name[] */
            unsigned short rec = (unsigned short)((u8)buf[off + 16] | ((u8)buf[off + 17] << 8));
            char *name = buf + off + 19;
            if (name[0] != '.') {
                char p[256], q[256];
                line_t l;
                path3(p, sizeof p, ROOTDIR "/mods/", name, "");
                path3(q, sizeof q, ROOTDIR "/mods/", name, "/.disabled");
                if (isdir(p)) {
                    l.n = 0; l.b[0] = 0;
                    lput(&l, name); lput(&l, exists(q) ? "\tdisabled\n" : "\tenabled\n");
                    say(fd, l.b);
                }
            }
            off += rec;
        }
    }
    x_close(d);
    return 0;
}

static int op_toggle(int fd, const char *id, int enable)
{
    char p[256], q[256];
    if (!valid_id(id)) { say(fd, "bad mod id\n"); return 2; }
    path3(p, sizeof p, ROOTDIR "/mods/", id, "");
    if (!isdir(p)) { say(fd, "no such mod\n"); return 1; }
    path3(q, sizeof q, ROOTDIR "/mods/", id, "/.disabled");
    if (enable) {
        sl r = x_unlink(q);
        if (r < 0 && r != -ENOENT) { say(fd, "can't enable\n"); return 1; }
    } else {
        sl f = x_open(q, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (f < 0) { say(fd, "can't disable\n"); return 1; }
        x_close(f);
    }
    publish_state();
    say(fd, enable ? "enabled (apply to change files; live mods change now, others at the next start)\n"
                   : "disabled (apply to change files; its hooks stop now)\n");
    return 0;
}

/* mod-file <id> mod.json|settings.json: read-only, for the app (/data/hmgapi is root-only) */
static int op_mod_file(int fd, const char *id, const char *name)
{
    static char buf[MAX_ARG];
    char p[256];
    sl f, n;
    if (!valid_id(id)) { say(fd, "bad mod id\n"); return 2; }
    if (!seq(name, "mod.json") && !seq(name, "settings.json")) { say(fd, "only mod.json or settings.json\n"); return 2; }
    path3(p, sizeof p, ROOTDIR "/mods/", id, "/");
    {
        ul k = slen(p), j = 0;
        while (name[j] && k < sizeof p - 1) p[k++] = name[j++];
        p[k] = 0;
    }
    f = x_open(p, O_RDONLY | O_CLOEXEC, 0);
    if (f < 0) { say(fd, ""); return 1; }
    while ((n = x_read(f, buf, sizeof buf)) > 0) if (frame(fd, 'O', buf, (u32)n)) break;
    x_close(f);
    return 0;
}

static int op_setting_get(int fd, const char *id)
{
    static char buf[MAX_ARG];
    char p[256];
    sl f, n;
    if (!valid_id(id) && !seq(id, "hmgapi")) { say(fd, "bad id\n"); return 2; }
    path3(p, sizeof p, ROOTDIR "/settings/", id, ".json");
    f = x_open(p, O_RDONLY | O_CLOEXEC, 0);
    if (f < 0) { say(fd, "{}"); return 0; }
    n = x_read(f, buf, sizeof buf);
    x_close(f);
    if (n > 0) frame(fd, 'O', buf, (u32)n);
    return 0;
}

static int op_setting_set(int fd, const char *id, const char *json)
{
    char p[256], t[256];
    ul n = slen(json);
    sl f;
    if (!valid_id(id) && !seq(id, "hmgapi")) { say(fd, "bad id\n"); return 2; }
    while (n && (json[n - 1] == '\n' || json[n - 1] == ' ')) n--;
    if (n < 2 || json[0] != '{' || json[n - 1] != '}') { say(fd, "settings must be a JSON object\n"); return 2; }
    x_mkdir(ROOTDIR "/settings", 0700);
    path3(p, sizeof p, ROOTDIR "/settings/", id, ".json");
    path3(t, sizeof t, ROOTDIR "/settings/.", id, ".json.tmp");
    f = x_open(t, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (f < 0) { say(fd, "can't write settings\n"); return 1; }
    if (wall((int)f, json, n) || x_fsync(f) < 0) { x_close(f); x_unlink(t); say(fd, "can't write settings\n"); return 1; }
    x_close(f);
    if (x_rename(t, p) < 0) { x_unlink(t); say(fd, "can't save settings\n"); return 1; }
    publish_state();
    say(fd, "saved (live settings change now; apply for file changes)\n");
    return 0;
}

/* The daemon's own environment (from init: BOOTCLASSPATH, ANDROID_ROOT, ...),
   which Dalvik needs to run the engine; set in main. */
static char **g_env;

/* Runs argv with stdout+stderr streamed to the client as frames. The child gets
   the daemon's environment (minus any LD_PRELOAD), a fixed PATH and
   LD_LIBRARY_PATH, plus `extra` (NULL-terminated, may be 0). */
static int run_env(int fd, char *const argv[], char *const extra[], int finish)
{
    int pfd[2];
    static char *envp[96];
    int ne = 0, i;
    envp[ne++] = "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin";
    envp[ne++] = "LD_LIBRARY_PATH=/vendor/lib:/system/lib";
    for (i = 0; extra && extra[i] && ne < 90; i++) envp[ne++] = extra[i];
    for (i = 0; g_env && g_env[i] && ne < 94; i++) {
        if (spre(g_env[i], "PATH=") || spre(g_env[i], "LD_LIBRARY_PATH=") || spre(g_env[i], "LD_PRELOAD=")) continue;
        envp[ne++] = g_env[i];
    }
    envp[ne] = 0;
    sl pid, n;
    int st = 0, exited = 0;
    char buf[4096];
    if (x_pipe(pfd) < 0) { say(fd, "pipe failed\n"); return 1; }
    pid = x_fork();
    if (pid < 0) { x_close(pfd[0]); x_close(pfd[1]); say(fd, "fork failed\n"); return 1; }
    if (pid == 0) {
        sl nul = x_open("/dev/null", O_RDONLY, 0);
        if (nul >= 0) x_dup2(nul, 0);
        x_dup2(pfd[1], 1);
        x_dup2(pfd[1], 2);
        /* only 0-2 go to the command: the pipe's own fd, the client's socket and the
         * services' pipes stay here */
        for (i = 3; i < 1024; i++) x_close(i);
        x_execve(argv[0], argv, envp);
        x_exit(127);
    }
    x_close(pfd[1]);
    /* Done when the command exits, not when the pipe closes: a background job it started
     * can still hold the pipe. The unit's shell (mksh) keeps copies of fds 1 and 2 above
     * 9 while it applies a job's ">file 2>&1", and the job inherits them, so the boot
     * hook's "(sleep 120; ...) >log 2>&1 &" held hmgapid sh, and the boot script with it
     * (NEMESIS two minutes late, 1.0.0 and 1.0.1). What's left in the pipe once the command
     * has exited is still sent. */
    for (;;) {
        struct { int fd; short ev, rev; } p = { (int)pfd[0], POLLIN, 0 };
        sl pr;
        if (!exited && x_wait4(pid, &st, WNOHANG) == pid) exited = 1;
        pr = x_poll(&p, 1, exited ? 0 : 200);
        if (pr == -EINTR) continue;
        if (pr <= 0) { if (exited) break; continue; }
        n = x_read(pfd[0], buf, sizeof buf);
        if (n == -EINTR) continue;
        if (n <= 0) break;
        if (fd < 0) continue;   /* client gone, finishing: drop the output */
        if (frame(fd, 'O', buf, (u32)n)) {   /* client gone */
            if (!finish) { x_kill(pid, SIGKILL); break; }
            fd = -1;   /* an engine apply must not stop halfway because adb dropped */
        }
    }
    x_close(pfd[0]);
    if (!exited) while (x_wait4(pid, &st, 0) == -EINTR) ;
    if ((st & 0x7f) == 0) return (st >> 8) & 0xff;
    return 128 + (st & 0x7f);
}

static int run(int fd, char *const argv[]) { return run_env(fd, argv, 0, 0); }

/* engine publish-state in the background (state.json + STATE_CHANGED broadcast for running
 * apps): the op that changed something returns at once */
static void publish_state(void)
{
    static char *a[] = { APP_PROCESS, "/system/bin", "hmgapi.engine.Engine", "publish-state", 0 };
    static char *extra[] = { "CLASSPATH=" ENGINE_JAR, "ANDROID_ROOT=/system", "ANDROID_DATA=/data", 0 };
    sl pid = x_fork();
    if (pid != 0) {   /* parent: reap the middle child; the grandchild is init's */
        if (pid > 0) while (x_wait4(pid, 0, 0) == -EINTR) ;
        return;
    }
    if (x_fork() != 0) x_exit(0);
    x_setsid();
    x_exit(run_env(-1, a, extra, 1));
}

/* The patch engine (Java): app_process /system/bin hmgapi.engine.Engine <args> */
static int op_engine(int fd, int argc, char **argv)
{
    char *a[MAX_ARGS + 4];
    static char *extra[] = { "CLASSPATH=" ENGINE_JAR, "ANDROID_ROOT=/system", "ANDROID_DATA=/data", 0 };
    int i, n = 0;
    const char *sub = argc > 1 ? argv[1] : "";
    if (!(seq(sub, "plan") || seq(sub, "apply") || seq(sub, "keep") || seq(sub, "restore") || seq(sub, "status")
          || seq(sub, "recover") || seq(sub, "install") || seq(sub, "remove") || seq(sub, "map") || seq(sub, "verify-map")
          || seq(sub, "usb-scan") || seq(sub, "update-hmgapi") || seq(sub, "apps") || seq(sub, "probe")
          || seq(sub, "map-car") || seq(sub, "report") || seq(sub, "nemesis-sync"))) {
        say(fd, "usage: engine plan | apply [--trial] [--force] | keep | restore | status | recover | install <zip> | remove <id>"
                " | nemesis-sync | map | map-car [--write] | verify-map | report [--usb]\n");
        return 2;
    }
    a[n++] = APP_PROCESS;
    a[n++] = "/system/bin";
    a[n++] = "hmgapi.engine.Engine";
    for (i = 1; i < argc && n < MAX_ARGS + 3; i++) a[n++] = argv[i];
    a[n] = 0;
    return run_env(fd, a, extra, 1);
}

/* /system remount, keeping the mount's nosuid/nodev/noexec flags (clearing
   MS_NOSUID on these locked mounts makes the kernel refuse the remount). */
static int op_remount(int fd, const char *mode)
{
    static char buf[16384];
    int rw = seq(mode, "rw");
    sl f, n, i = 0;
    int ok = 0;
    if (!rw && !seq(mode, "ro")) { say(fd, "usage: remount rw|ro\n"); return 2; }
    f = x_open("/proc/mounts", O_RDONLY | O_CLOEXEC, 0);
    if (f < 0) { say(fd, "no /proc/mounts\n"); return 1; }
    n = x_read(f, buf, sizeof buf - 1);
    x_close(f);
    if (n <= 0) return 1;
    buf[n] = 0;
    while (i < n) {
        char *fld[4]; int k = 0; sl e = i;
        while (e < n && buf[e] != '\n') e++;
        buf[e] = 0;
        {
            char *p = buf + i;
            while (k < 4 && *p) {
                fld[k++] = p;
                while (*p && *p != ' ') p++;
                if (*p) *p++ = 0;
            }
        }
        if (k == 4 && seq(fld[1], "/system")) {
            ul fl = MS_REMOUNT | (rw ? 0 : MS_RDONLY);
            int ro = 0;
            sl d, r;
            if (sstr(fld[3], "nosuid")) fl |= MS_NOSUID;
            if (sstr(fld[3], "nodev")) fl |= MS_NODEV;
            if (sstr(fld[3], "noexec")) fl |= MS_NOEXEC;
            if (rw && spre(fld[0], "/dev/")) {
                d = x_open(fld[0], O_RDONLY | O_CLOEXEC, 0);
                if (d >= 0) { x_ioctl(d, BLKROSET, &ro); x_close(d); }
            }
            r = x_mount(fld[0], "/system", fld[2], fl, 0);
            if (r == 0) ok = 1;
            else {
                line_t l; l.n = 0; l.b[0] = 0;
                lput(&l, "remount failed, errno "); lnum(&l, -r); lput(&l, r == -16 ? " (busy: a file is open for writing)\n" : "\n");
                say(fd, l.b);
            }
        }
        i = e + 1;
    }
    if (ok) say(fd, rw ? "/system is rw\n" : "/system is ro\n");
    return ok ? 0 : 1;
}

/* ---- binds: a mod's binary bind-mounted over a stock one (e.g. our adbd over
 * /sbin/adbd on the read-only rootfs), its init service restarted, and checked:
 * the service must come up running our file and stay up, or the mount is
 * dropped and the stock service restarted. Nothing on disk changes; a restart
 * of the head unit always brings the stock binary back.
 *   ROOTDIR/state/binds.txt     wanted, "src target service" per line (the engine writes it)
 *   ROOTDIR/state/binds.active  what's mounted now
 *   ROOTDIR/state/binds.failed  lines that failed their check: not tried again
 *                               until the engine writes binds.txt anew */

static void sleep_ms(long ms)
{
    long ts[2];
    ts[0] = ms / 1000; ts[1] = (ms % 1000) * 1000000L;
    x_nanosleep(ts, 0);
}

/* dev and inode of a path (stat64 on ARM EABI: st_dev at 0, st_ino at 96) */
static int file_id(const char *p, unsigned long long id[2])
{
    unsigned long long st[16];
    if (x_stat64(p, st) < 0) return -1;
    id[0] = st[0];
    id[1] = st[12];
    return 0;
}

static int read_small(const char *p, char *buf, ul cap)
{
    sl f = x_open(p, O_RDONLY | O_CLOEXEC, 0), n, t = 0;
    if (f < 0) return -1;
    while (t < (sl)cap - 1 && (n = x_read(f, buf + t, cap - 1 - t)) > 0) t += n;
    x_close(f);
    buf[t < 0 ? 0 : t] = 0;
    return (int)t;
}

static int write_mode(const char *p, const char *s, int mode)
{
    char t[256];
    sl f;
    path3(t, sizeof t, p, ".tmp", "");
    f = x_open(t, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, mode);
    if (f < 0) return -1;
    x_chmod(t, mode);
    if (wall((int)f, s, slen(s)) || x_fsync(f) < 0) { x_close(f); x_unlink(t); return -1; }
    x_close(f);
    return x_rename(t, p) < 0 ? -1 : 0;
}

static int write_small(const char *p, const char *s)
{
    return write_mode(p, s, 0600);
}

/* pid whose argv[0] is target and whose executable is src (by dev+inode), or 0 */
static sl service_pid(const char *target, unsigned long long src[2])
{
    static char buf[4096];
    sl d = x_open("/proc", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0), n, found = 0;
    if (d < 0) return 0;
    while (!found && (n = x_getdents64(d, buf, sizeof buf)) > 0) {
        sl off = 0;
        while (off < n && !found) {
            unsigned short rec = (unsigned short)((u8)buf[off + 16] | ((u8)buf[off + 17] << 8));
            char *name = buf + off + 19, p[64], cmd[256];
            unsigned long long id[2];
            if (name[0] >= '1' && name[0] <= '9') {
                path3(p, sizeof p, "/proc/", name, "/cmdline");
                if (read_small(p, cmd, sizeof cmd) > 0 && seq(cmd, target)) {
                    path3(p, sizeof p, "/proc/", name, "/exe");
                    if (file_id(p, id) == 0 && id[0] == src[0] && id[1] == src[1]) {
                        sl v = 0; char *c = name;
                        while (*c >= '0' && *c <= '9') v = v * 10 + (*c++ - '0');
                        found = v;
                    }
                }
            }
            off += rec;
        }
    }
    x_close(d);
    return found;
}

/* fd -1: setprop's output is dropped, and it's never killed if the client goes
 * away (a stop without its start would leave the service down) */
static void svc_restart(const char *service)
{
    char *stop[] = { "/system/bin/setprop", "ctl.stop", (char *)service, 0 };
    char *start[] = { "/system/bin/setprop", "ctl.start", (char *)service, 0 };
    run(-1, stop);
    sleep_ms(1000);
    run(-1, start);
}

static int is_mounted(const char *target)
{
    static char buf[16384];
    char pat[260];
    if (read_small("/proc/mounts", buf, sizeof buf) < 0) return 0;
    path3(pat, sizeof pat, " ", target, " ");
    return sstr(buf, pat) != 0;
}

static int valid_bind(const char *src, const char *target, const char *service)
{
    const char *c;
    if (!spre(src, ROOTDIR "/mods/") || sstr(src, "..")) return 0;   /* a file in a mod's own folder */
    if (!(spre(target, "/sbin/") || spre(target, "/system/bin/")) || sstr(target, "..")) return 0;
    if (!*service) return 0;
    for (c = service; *c; c++) if (!((*c >= 'a' && *c <= 'z') || (*c >= '0' && *c <= '9') || *c == '_')) return 0;
    return 1;
}

/* one wanted bind: mount, restart, check; 0 = running our binary */
static int bind_one(int fd, const char *src, const char *target, const char *service)
{
    unsigned long long sid[2], tid[2];
    int tries;
    line_t l;
    if (!valid_bind(src, target, service)) { say(fd, "bind refused (paths or service name)\n"); return 2; }
    if (file_id(src, sid) < 0) { say(fd, "bind: no "); say(fd, src); say(fd, "\n"); return 1; }
    if (file_id(target, tid) < 0) { say(fd, "bind: no "); say(fd, target); say(fd, "\n"); return 1; }
    if (!(tid[0] == sid[0] && tid[1] == sid[1])) {
        if (x_mount(src, target, 0, MS_BIND, 0) < 0) { say(fd, "bind: mount failed\n"); return 1; }
    }
    svc_restart(service);
    for (tries = 0; tries < 12; tries++) {
        sl pid;
        sleep_ms(1000);
        pid = service_pid(target, sid);
        if (pid > 0) {
            int alive = 1, k;
            for (k = 0; k < 3; k++) {
                sleep_ms(1000);
                if (service_pid(target, sid) != pid) { alive = 0; break; }
            }
            if (alive) {
                l.n = 0; l.b[0] = 0;
                lput(&l, "bind: "); lput(&l, service); lput(&l, " runs "); lput(&l, src); lput(&l, "\n");
                say(fd, l.b);
                return 0;
            }
        }
    }
    /* didn't come up as ours: stock back */
    x_umount2(target, MNT_DETACH);
    svc_restart(service);
    l.n = 0; l.b[0] = 0;
    lput(&l, "bind: "); lput(&l, service); lput(&l, " didn't stay up with "); lput(&l, src);
    lput(&l, "; stock "); lput(&l, target); lput(&l, " restarted\n");
    say(fd, l.b);
    logline("bind failed, stock restored", 0, 0, 0);
    return 1;
}

/* append "line\n" to a NUL-terminated buffer, if it fits */
static void apnd(char *buf, ul cap, const char *line)
{
    ul n = slen(buf), m = slen(line);
    if (n + m + 2 > cap) return;
    mcpy(buf + n, line, m);
    buf[n + m] = '\n';
    buf[n + m + 1] = 0;
}

static int has_line(const char *text, const char *line)
{
    ul n = slen(line);
    const char *p = text;
    while (*p) {
        const char *e = p;
        while (*e && *e != '\n') e++;
        if ((ul)(e - p) == n) {
            ul i = 0;
            while (i < n && p[i] == line[i]) i++;
            if (i == n) return 1;
        }
        p = *e ? e + 1 : e;
    }
    return 0;
}

static int op_binds(int fd)
{
    static char want[4096], active[4096], failed[4096], next[4096], nfail[4096], line[512];
    char *p;
    int rc = 0;
    if (read_small(ROOTDIR "/state/binds.txt", want, sizeof want) < 0) want[0] = 0;
    if (read_small(ROOTDIR "/state/binds.active", active, sizeof active) < 0) active[0] = 0;
    if (read_small(ROOTDIR "/state/binds.failed", failed, sizeof failed) < 0) failed[0] = 0;
    next[0] = 0; nfail[0] = 0;
    /* no longer wanted: unmount, stock service back */
    for (p = active; *p; ) {
        char *e = p, *f[3]; int k = 0; ul n;
        while (*e && *e != '\n') e++;
        n = (ul)(e - p);
        if (n && n < sizeof line) {
            mcpy(line, p, n); line[n] = 0;
            if (!has_line(want, line)) {
                char *q = line;
                while (k < 3 && *q) { f[k++] = q; while (*q && *q != ' ') q++; if (*q) *q++ = 0; }
                if (k == 3 && is_mounted(f[1])) {
                    x_umount2(f[1], MNT_DETACH);
                    svc_restart(f[2]);
                    say(fd, "unbind: "); say(fd, f[1]); say(fd, " stock again\n");
                }
            }
        }
        p = *e ? e + 1 : e;
    }
    /* wanted: bind (again after a restart), unless it failed before */
    for (p = want; *p; ) {
        char *e = p, *f[3]; int k = 0; ul n;
        while (*e && *e != '\n') e++;
        n = (ul)(e - p);
        if (n && n < sizeof line) {
            mcpy(line, p, n); line[n] = 0;
            unsigned long long a[2], b[2];
            char copy0[512], *g[3], *q0;
            int k0 = 0;
            mcpy(copy0, line, n + 1);
            q0 = copy0;
            while (k0 < 3 && *q0) { g[k0++] = q0; while (*q0 && *q0 != ' ') q0++; if (*q0) *q0++ = 0; }
            if (k0 == 3 && has_line(active, line) && file_id(g[0], a) == 0 && file_id(g[1], b) == 0
                && a[0] == b[0] && a[1] == b[1]) {
                apnd(next, sizeof next, line);   /* still mounted (QuickBoot resume): leave it running */
            } else if (has_line(failed, line)) {
                say(fd, "skip (failed before): "); say(fd, line); say(fd, "\n");
                apnd(nfail, sizeof nfail, line);
            } else {
                char copy[512], *q;
                mcpy(copy, line, n + 1);
                q = copy;
                while (k < 3 && *q) { f[k++] = q; while (*q && *q != ' ') q++; if (*q) *q++ = 0; }
                if (k == 3 && bind_one(fd, f[0], f[1], f[2]) == 0) apnd(next, sizeof next, line);
                else { apnd(nfail, sizeof nfail, line); rc = 1; }
            }
        }
        p = *e ? e + 1 : e;
    }
    write_small(ROOTDIR "/state/binds.active", next);
    write_small(ROOTDIR "/state/binds.failed", nfail);
    if (!want[0] && !active[0]) say(fd, "no binds\n");
    return rc;
}

/* ---- services: mods' background processes, started and watched here, so a
 * mod's daemon needs no NEMESIS service and no boot script of its own.
 *   ROOTDIR/state/services.txt  wanted (the engine writes it for the enabled mods), per line:
 *     <mod> <name> <user> <start> <restart> <kind> <path> <class|-> [args...]
 *     user root | system | app:<package>; start boot | android; restart yes | no;
 *     kind jar (app_process with CLASSPATH=<path>, main <class>) | script (sh <path>)
 *   ROOTDIR/state/services.status  what runs now: <mod> <name> <state> <pid> <fails>
 *   ROOTDIR/state/ctl/  requests from ops (workers are forked: they can't touch this
 *     table): "restart|stop|start <mod> <name>"
 *   ROOTDIR/logs/<mod>/<name>.log  its output (256 KB, then .1)
 *   ROOTDIR/run/<mod>/<name>/      its own folder (HMGAPI_DATA, ANDROID_DATA for its
 *     dalvik-cache), owned by its user
 * A mod turned off is dropped from services.txt by the engine: stopped here (TERM,
 * then KILL after 5 s); turned on: started. A service that keeps exiting within a
 * minute is started again after 1, 2, 4 ... 300 s, and given up after 10 tries. */
#define MAX_SVC 32
#define SVC_LINE 512
#define SVC_FIELDS 24
enum { S_WAIT, S_RUN, S_EXITED, S_FAILED, S_STOPPED };
static const char *const s_names[] = { "waiting", "running", "exited", "failed", "stopped" };
struct svc {
    char line[SVC_LINE], buf[SVC_LINE];
    char *f[SVC_FIELDS + 1];
    int nf, state, fails, gone, stopping;
    sl pid;
    long next, started, kill_at;
};
static struct svc svcs[MAX_SVC];
static int nsvc, android_up, status_dirty;
static long last_android_check;
static char svc_text[16384], apps_synced[8192];
static int apps_sync_pending = 1;

static int svc_name_ok(const char *s)
{
    ul n = slen(s);
    if (n == 0 || n > 32) return 0;
    for (; *s; s++) if (!((*s >= 'a' && *s <= 'z') || (*s >= '0' && *s <= '9') || *s == '-' || *s == '_')) return 0;
    return 1;
}

static void svc_parse(struct svc *v)
{
    char *q = v->buf;
    mcpy(v->buf, v->line, slen(v->line) + 1);
    v->nf = 0;
    while (*q && v->nf < SVC_FIELDS) {
        while (*q == ' ') *q++ = 0;
        if (!*q) break;
        v->f[v->nf++] = q;
        while (*q && *q != ' ') q++;
    }
    v->f[v->nf] = 0;
}

/* run a small command, its output into buf (getprop) */
static int capture(char *const argv[], char *buf, ul cap)
{
    int pfd[2], st = 0;
    sl pid, n, t = 0;
    static char *envp[] = { "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin", "LD_LIBRARY_PATH=/vendor/lib:/system/lib", 0 };
    buf[0] = 0;
    if (x_pipe(pfd) < 0) return -1;
    pid = x_fork();
    if (pid < 0) { x_close(pfd[0]); x_close(pfd[1]); return -1; }
    if (pid == 0) {
        int i;
        x_dup2(pfd[1], 1);
        for (i = 3; i < 1024; i++) x_close(i);
        x_execve(argv[0], argv, envp);
        x_exit(127);
    }
    x_close(pfd[1]);
    while (t < (sl)cap - 1 && (n = x_read(pfd[0], buf + t, cap - 1 - t)) != 0) {
        if (n == -EINTR) continue;
        if (n < 0) break;
        t += n;
    }
    buf[t] = 0;
    x_close(pfd[0]);
    while (x_wait4(pid, &st, 0) == -EINTR) ;
    return (int)t;
}

static void svc_log(const char *what, struct svc *v, sl n)
{
    line_t l; l.n = 0; l.b[0] = 0;
    lput(&l, v->f[0]); lput(&l, "/"); lput(&l, v->f[1]);
    logline(what, l.b, n, 0);
}

static void mkdir_owned(const char *p, int mode, int uid, int gid)
{
    x_mkdir(p, mode);
    x_chmod(p, mode);
    if (uid >= 0) x_chown(p, uid, gid);
}

static void write_status(void)
{
    static char st[8192], pub[4096];
    int i;
    st[0] = 0; pub[0] = 0;
    for (i = 0; i < nsvc; i++) {
        line_t l; l.n = 0; l.b[0] = 0;
        if (svcs[i].gone) continue;
        lput(&l, svcs[i].f[0]); lput(&l, " "); lput(&l, svcs[i].f[1]); lput(&l, " ");
        lput(&l, s_names[svcs[i].state]); lput(&l, " "); lnum(&l, svcs[i].pid); lput(&l, " "); lnum(&l, svcs[i].fails);
        apnd(st, sizeof st, l.b);
        if (svcs[i].state == S_RUN && svcs[i].pid > 0 && !seq(svcs[i].f[0], "hmgapi")) {
            l.n = 0; l.b[0] = 0;
            lnum(&l, svcs[i].pid); lput(&l, " "); lput(&l, svcs[i].f[0]);
            apnd(pub, sizeof pub, l.b);
        }
    }
    write_small(ROOTDIR "/state/services.status", st);
    mkdir_owned(ROOTDIR "/public", 0755, -1, -1);
    write_mode(ROOTDIR "/public/services.txt", pub, 0644);
    status_dirty = 0;
}

/* the user a service runs as: uid, gid, groups; -1 when its app isn't installed */
static int svc_user(const char *user, u32 *gids, int *ng)
{
    static const u32 sys_groups[] = { 1000, 1007, 1015, 1023, 1028, 3001, 3002, 3003 };   /* system, log, sdcard_rw, media_rw, sdcard_r, net_bt_admin, net_bt, inet */
    int i;
    *ng = 0;
    if (seq(user, "root")) return 0;
    if (seq(user, "system")) {
        for (i = 0; i < (int)(sizeof sys_groups / sizeof sys_groups[0]); i++) gids[(*ng)++] = sys_groups[i];
        return 1000;
    }
    if (spre(user, "app:")) return pkg_uid(user + 4, gids, ng);
    return -1;
}

static void svc_start(struct svc *v, long now)
{
    char path[256], dir[256], data[256], cache[256], logp[256], old[260], envb[6][300];
    static char *envp[110];
    char *argv[SVC_FIELDS + 6];
    u32 gids[20];
    int ng, uid, ne = 0, na = 0, i, go[2];
    unsigned long long st[16];
    sl pid, lf;
    const char *mod = v->f[0], *name = v->f[1], *kind = v->f[5], *rel = v->f[6], *cls = v->f[7];
    uid = svc_user(v->f[2], gids, &ng);
    if (uid < 0) {   /* its app isn't installed (yet): try again later */
        if (v->fails == 0) svc_log("service waits for its app", v, -1);
        v->fails = 0;
        v->next = now + 30;
        return;
    }
    if (rel[0] == '/') path3(path, sizeof path, rel, "", "");   /* HMG-API's own (svc_valid) */
    else {
        path3(path, sizeof path, ROOTDIR "/mods/", mod, "/");
        path3(path, sizeof path, path, rel, "");
    }
    path3(dir, sizeof dir, ROOTDIR "/run/", mod, "");
    path3(data, sizeof data, dir, "/", name);
    path3(cache, sizeof cache, data, "/dalvik-cache", "");
    mkdir_owned(ROOTDIR "/run", 0711, -1, -1);
    mkdir_owned(dir, 0711, -1, -1);
    mkdir_owned(data, 0700, uid, uid);
    mkdir_owned(cache, 0700, uid, uid);
    path3(logp, sizeof logp, ROOTDIR "/logs/", mod, "");
    mkdir_owned(ROOTDIR "/logs", 0700, -1, -1);
    mkdir_owned(logp, 0700, -1, -1);
    path3(logp, sizeof logp, logp, "/", name);
    path3(logp, sizeof logp, logp, ".log", "");
    if (x_stat64(logp, st) == 0 && (long long)st[6] > 256 * 1024) {   /* st_size at 48 */
        path3(old, sizeof old, logp, ".1", "");
        x_rename(logp, old);
    }
    lf = x_open(logp, O_WRONLY | O_CREAT | O_APPEND, 0600);

    envp[ne++] = "PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin";
    envp[ne++] = "LD_LIBRARY_PATH=/vendor/lib:/system/lib";
    path3(envb[0], sizeof envb[0], "ANDROID_DATA=", data, "");
    path3(envb[1], sizeof envb[1], "HMGAPI_DATA=", data, "");
    path3(envb[2], sizeof envb[2], "HMGAPI_MOD=", mod, "");
    path3(envb[3], sizeof envb[3], "HMGAPI_MOD_DIR=" ROOTDIR "/mods/", mod, "");
    path3(envb[4], sizeof envb[4], "HMGAPI_SERVICE=", name, "");
    path3(envb[5], sizeof envb[5], "CLASSPATH=", path, "");
    for (i = 0; i < 6; i++) if (i < 5 || seq(kind, "jar")) envp[ne++] = envb[i];
    for (i = 0; g_env && g_env[i] && ne < 108; i++) {
        if (spre(g_env[i], "PATH=") || spre(g_env[i], "LD_LIBRARY_PATH=") || spre(g_env[i], "LD_PRELOAD=")
            || spre(g_env[i], "ANDROID_DATA=") || spre(g_env[i], "CLASSPATH=")) continue;
        envp[ne++] = g_env[i];
    }
    envp[ne] = 0;
    if (seq(kind, "jar")) {
        static char nice[96];
        path3(nice, sizeof nice, "--nice-name=", mod, ".");
        path3(nice, sizeof nice, nice, name, "");
        argv[na++] = APP_PROCESS; argv[na++] = "/system/bin"; argv[na++] = nice; argv[na++] = (char *)cls;
    } else {
        argv[na++] = SH; argv[na++] = path;
    }
    for (i = 8; i < v->nf && na < SVC_FIELDS + 4; i++) argv[na++] = v->f[i];
    argv[na] = 0;

    if (x_pipe(go) < 0) { go[0] = go[1] = -1; }
    pid = x_fork();
    if (pid < 0) { if (lf >= 0) x_close(lf); if (go[0] >= 0) { x_close(go[0]); x_close(go[1]); } v->next = now + 5; return; }
    if (pid == 0) {
        sl nul = x_open("/dev/null", O_RDONLY, 0);
        char c;
        x_setsid();
        if (go[0] >= 0) {   /* wait until its pid is in services.status: it may ask hmgapid who it is at once */
            x_close(go[1]);
            while (x_read(go[0], &c, 1) == -EINTR) ;
            x_close(go[0]);
        }
        if (nul >= 0) x_dup2(nul, 0);
        if (lf >= 0) { x_dup2(lf, 1); x_dup2(lf, 2); }
        x_chdir(data);
        if (uid != 0 && x_geteuid() == 0) {   /* off-car tests run unprivileged: no switch */
            if (x_setgroups(ng, gids) < 0 || x_setresgid(uid, uid, uid) < 0 || x_setresuid(uid, uid, uid) < 0) x_exit(126);
        }
        x_execve(argv[0], argv, envp);
        x_exit(127);
    }
    if (lf >= 0) x_close(lf);
    v->pid = pid;
    v->state = S_RUN;
    v->started = now;
    v->next = 0;
    svc_log("service started, pid", v, pid);
    write_status();
    if (go[0] >= 0) { x_close(go[0]); x_write(go[1], "1", 1); x_close(go[1]); }
}

static void svc_stop(struct svc *v, long now)
{
    if (v->pid > 0) {
        x_kill(-v->pid, SIGTERM);   /* its whole process group (setsid) */
        x_kill(v->pid, SIGTERM);
        v->kill_at = now + 5;
        v->stopping = 1;
    }
}

/* a child exited: a service's (1) or a worker's (0) */
static int svc_exited(sl pid, int st, long now)
{
    int i;
    for (i = 0; i < nsvc; i++) {
        struct svc *v = &svcs[i];
        int code = (st & 0x7f) == 0 ? (st >> 8) & 0xff : 128 + (st & 0x7f);
        if (v->pid != pid) continue;
        v->pid = 0;
        v->kill_at = 0;
        status_dirty = 1;
        svc_log("service exited, code", v, code);
        if (v->gone) return 1;
        if (v->stopping) { v->stopping = 0; if (v->state != S_WAIT) v->state = S_STOPPED; return 1; }
        if (seq(v->f[4], "yes")) {
            if (now - v->started >= 60) v->fails = 0;
            else v->fails++;
            if (v->fails >= 10) { v->state = S_FAILED; svc_log("service keeps exiting, given up", v, -1); return 1; }
            v->state = S_WAIT;
            v->next = now + (v->fails > 8 ? 300 : 1L << v->fails);
        } else {
            v->state = S_EXITED;
        }
        return 1;
    }
    return 0;
}

/* a service's mod: a mod id, or hmgapi for HMG-API's own (the vehicle service) */
static int svc_mod_ok(const char *mod)
{
    return valid_id(mod) || seq(mod, "hmgapi");
}

static int svc_valid(struct svc *v)
{
    const char *k;
    if (v->nf < 8 || !svc_mod_ok(v->f[0]) || !svc_name_ok(v->f[1])) return 0;
    if (!(seq(v->f[2], "root") || seq(v->f[2], "system") || spre(v->f[2], "app:"))) return 0;
    if (!(seq(v->f[3], "boot") || seq(v->f[3], "android") || seq(v->f[3], "manual")) || !(seq(v->f[4], "yes") || seq(v->f[4], "no"))) return 0;
    if (!(seq(v->f[5], "jar") || seq(v->f[5], "script"))) return 0;
    k = v->f[6];
    /* HMG-API's own services: only its jar, and the network keeper the engine writes (Network.java) */
    if (seq(v->f[0], "hmgapi")) return seq(v->f[5], "jar") ? seq(k, ENGINE_JAR) : seq(k, ROOTDIR "/state/net.sh");
    if (k[0] == '/' || sstr(k, "..")) return 0;
    return 1;
}

static struct svc *svc_find(const char *mod, const char *name)
{
    int i;
    for (i = 0; i < nsvc; i++) if (!svcs[i].gone && seq(svcs[i].f[0], mod) && seq(svcs[i].f[1], name)) return &svcs[i];
    return 0;
}

static void svc_reload(long now)
{
    static char want[16384], line[SVC_LINE];
    char *p;
    int i;
    if (read_small(ROOTDIR "/state/services.txt", want, sizeof want) < 0) want[0] = 0;
    if (seq(want, svc_text)) return;
    mcpy(svc_text, want, slen(want) + 1);
    for (i = 0; i < nsvc; i++) {   /* no longer wanted (or changed): stop */
        if (!svcs[i].gone && !has_line(want, svcs[i].line)) {
            svcs[i].gone = 1;
            svc_stop(&svcs[i], now);
            svc_log("service no longer wanted", &svcs[i], -1);
        }
    }
    for (p = want; *p; ) {
        char *e = p;
        ul n;
        int dup = 0;
        while (*e && *e != '\n') e++;
        n = (ul)(e - p);
        if (n && n < SVC_LINE) {
            mcpy(line, p, n); line[n] = 0;
            for (i = 0; i < nsvc; i++) if (!svcs[i].gone && seq(svcs[i].line, line)) dup = 1;
            if (!dup && nsvc < MAX_SVC) {
                struct svc *v = &svcs[nsvc];
                mset(v, 0, sizeof *v);
                mcpy(v->line, line, n + 1);
                svc_parse(v);
                if (svc_valid(v) && !svc_find(v->f[0], v->f[1])) {
                    v->state = seq(v->f[3], "manual") ? S_STOPPED : S_WAIT;   /* manual: only when asked (service-start) */
                    v->next = seq(v->f[3], "manual") ? 0 : now;
                    nsvc++;
                } else {
                    logline("service line refused:", line, -1, 0);
                }
            }
        }
        p = *e ? e + 1 : e;
    }
    status_dirty = 1;
}

/* requests from ops: ROOTDIR/state/ctl/<file> = "restart|stop|start <mod> <name>" */
static void svc_ctl(long now)
{
    static char buf[2048], req[256];
    sl d = x_open(ROOTDIR "/state/ctl", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0), n;
    if (d < 0) return;
    while ((n = x_getdents64(d, buf, sizeof buf)) > 0) {
        sl off = 0;
        while (off < n) {
            unsigned short rec = (unsigned short)((u8)buf[off + 16] | ((u8)buf[off + 17] << 8));
            char *name = buf + off + 19, p[256];
            off += rec;
            if (name[0] == '.' || sstr(name, ".tmp")) continue;
            path3(p, sizeof p, ROOTDIR "/state/ctl/", name, "");
            if (read_small(p, req, sizeof req) > 0) {
                char *f[3], *q = req;
                int k = 0;
                struct svc *v;
                while (k < 3 && *q) { f[k++] = q; while (*q && *q != ' ' && *q != '\n') q++; if (*q) *q++ = 0; }
                if (k == 3 && (v = svc_find(f[1], f[2])) != 0) {
                    if (seq(f[0], "stop")) { v->state = S_STOPPED; v->next = 0; svc_stop(v, now); }
                    else if (seq(f[0], "start") || seq(f[0], "restart")) {
                        v->fails = 0;
                        v->state = S_WAIT;
                        v->next = now;
                        if (v->pid > 0) svc_stop(v, now);   /* started again once it has exited */
                    }
                    svc_log(f[0], v, -1);
                    status_dirty = 1;
                }
            }
            x_unlink(p);
        }
    }
    x_close(d);
}

/* engine apps: install, turn on/off, remove the mods' own apps (needs Android up) */
static void apps_sync(void)
{
    static char *a[] = { APP_PROCESS, "/system/bin", "hmgapi.engine.Engine", "apps", 0 };
    static char *extra[] = { "CLASSPATH=" ENGINE_JAR, "ANDROID_ROOT=/system", "ANDROID_DATA=/data", 0 };
    sl pid = x_fork();
    if (pid != 0) {
        if (pid > 0) while (x_wait4(pid, 0, 0) == -EINTR) ;
        return;
    }
    if (x_fork() != 0) x_exit(0);
    x_setsid();
    x_exit(run_env(-1, a, extra, 1));
}

static void svc_tick(void)
{
    static long last;
    static char apps[8192];
    long now = x_time();
    int i;
    if (now == last) return;
    last = now;
    if (!android_up && now - last_android_check >= 5) {
        char v[16];
        char *a[] = { GETPROP, "sys.boot_completed", 0 };
        last_android_check = now;
        if (capture(a, v, sizeof v) > 0 && v[0] == '1') {
            android_up = 1;
            logline("Android is up", 0, -1, 0);
        }
    }
    svc_reload(now);
    svc_ctl(now);
    /* the mods' apps, when the list changes (or first after Android is up) */
    if (android_up) {
        if (read_small(ROOTDIR "/state/apps.txt", apps, sizeof apps) < 0) apps[0] = 0;
        if (apps_sync_pending || !seq(apps, apps_synced)) {
            if (apps[0] || exists(ROOTDIR "/state/apps.json")) apps_sync();
            mcpy(apps_synced, apps, slen(apps) + 1);
            apps_sync_pending = 0;
        }
    }
    for (i = 0; i < nsvc; i++) {
        struct svc *v = &svcs[i];
        if (v->pid > 0 && v->kill_at && now >= v->kill_at) { x_kill(-v->pid, SIGKILL); x_kill(v->pid, SIGKILL); v->kill_at = 0; }
        if (v->gone && v->pid == 0) {   /* drop it */
            --nsvc;
            if (i != nsvc) mcpy(&svcs[i], &svcs[nsvc], sizeof svcs[i]);
            svc_parse(&svcs[i]);   /* field pointers point into its own buf: rebuild after the copy */
            i--;
            status_dirty = 1;
            continue;
        }
        if (v->state == S_WAIT && v->pid == 0 && v->next && now >= v->next && (seq(v->f[3], "boot") || android_up)) svc_start(v, now);
    }
    if (status_dirty) write_status();
}

/* ops on services, from workers: a request file the daemon picks up within a second */
static int op_svc_ctl(int fd, const char *verb, const char *mod, const char *name)
{
    char p[128], line[200];
    line_t l;
    if (!svc_mod_ok(mod) || !svc_name_ok(name)) { say(fd, "bad mod or service name\n"); return 2; }
    x_mkdir(ROOTDIR "/state/ctl", 0700);
    l.n = 0; l.b[0] = 0;
    lput(&l, ROOTDIR "/state/ctl/"); lnum(&l, x_getpid());
    mcpy(p, l.b, l.n + 1);
    path3(line, sizeof line, verb, " ", mod);
    path3(line, sizeof line, line, " ", name);
    if (write_small(p, line) < 0) { say(fd, "can't ask the supervisor\n"); return 1; }
    say(fd, verb); say(fd, " "); say(fd, mod); say(fd, "/"); say(fd, name); say(fd, ": asked\n");
    return 0;
}

/* services status, all or one mod's */
static int op_services(int fd, const char *mod)
{
    static char st[8192];
    char *p;
    ul k = mod ? slen(mod) : 0;
    if (read_small(ROOTDIR "/state/services.status", st, sizeof st) < 0) st[0] = 0;
    for (p = st; *p; ) {
        char *e = p;
        while (*e && *e != '\n') e++;
        if (!mod || (spre(p, mod) && p[k] == ' ')) frame(fd, 'O', p, (u32)(e - p + (*e ? 1 : 0)));
        p = *e ? e + 1 : e;
    }
    return 0;
}

/* the end of a service's log (64 KB at most) */
static int op_svc_log(int fd, const char *mod, const char *name)
{
    static char buf[64 * 1024];
    char p[256];
    unsigned long long st[16];
    sl f, n;
    if (!svc_mod_ok(mod) || !svc_name_ok(name)) { say(fd, "bad mod or service name\n"); return 2; }
    path3(p, sizeof p, ROOTDIR "/logs/", mod, "/");
    path3(p, sizeof p, p, name, ".log");
    f = x_open(p, O_RDONLY | O_CLOEXEC, 0);
    if (f < 0) { say(fd, "no log yet\n"); return 1; }
    if (x_stat64(p, st) == 0 && (long long)st[6] > (long long)sizeof buf) {
        long long off = (long long)st[6] - (long long)sizeof buf;
        S5(140, f, (sl)(off >> 32), (sl)(off & 0xffffffff), (sl)&off, 0);   /* _llseek */
    }
    while ((n = x_read(f, buf, sizeof buf)) > 0) if (frame(fd, 'O', buf, (u32)n)) break;
    x_close(f);
    return 0;
}

/* USB sticks that hold a folder a mod named (state/usb.txt: "<mod> <folder>") */
static int op_usb(int fd, const char *mod)
{
    static const char *const mounts[] = { "/storage/usb0", "/storage/usb1", "/storage/usb2", 0 };
    static char want[4096];
    char *p;
    int m;
    ul k = mod ? slen(mod) : 0;
    if (read_small(ROOTDIR "/state/usb.txt", want, sizeof want) < 0) want[0] = 0;
    for (p = want; *p; ) {
        char *e = p, *sp = 0;
        while (*e && *e != '\n') { if (*e == ' ' && !sp) sp = e; e++; }
        if (sp && (!mod || ((ul)(sp - p) == k && spre(p, mod)))) {
            char folder[128];
            ul n = (ul)(e - sp - 1);
            if (n && n < sizeof folder) {
                mcpy(folder, sp + 1, n); folder[n] = 0;
                for (m = 0; mounts[m]; m++) {
                    char d[256];
                    path3(d, sizeof d, USBROOT, mounts[m], "/");
                    path3(d, sizeof d, d, folder, "");
                    if (isdir(d)) {
                        line_t l; l.n = 0; l.b[0] = 0;
                        if (!mod) { lput(&l, p); l.n = (ul)(sp - p); l.b[l.n] = 0; lput(&l, " "); }
                        lput(&l, mounts[m]); lput(&l, "/"); lput(&l, folder); lput(&l, "\n");
                        say(fd, l.b);
                    }
                }
            }
        }
        p = *e ? e + 1 : e;
    }
    return 0;
}

/* Vehicle readings (hmgapi.engine.Vehicle), from HMG-API's vehicle service. A mod gets
   only the ones it declared (state/vehicle.txt: "<mod> <reading>"); mod 0: any. */
static int op_vehicle(int fd, const char *mod, int argc, char **argv)
{
    static char want[8192], req[1024], buf[4096];
    struct sun a;
    int alen = sockaddr_of(&a, VEHICLE_SOCK), i, got;
    sl s, n;
    char last = 0;
    struct { sl sec, usec; } tv = { 3, 0 };
    path3(req, sizeof req, "read", "", "");
    if (mod && read_small(ROOTDIR "/state/vehicle.txt", want, sizeof want) < 0) want[0] = 0;
    for (i = 1; i < argc; i++) {
        const char *c;
        for (c = argv[i]; *c; c++) if (!((*c >= 'a' && *c <= 'z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '_')) break;
        if (*c || !argv[i][0] || slen(argv[i]) > 40) { say(fd, "bad reading name\n"); return 2; }
        if (mod) {
            char l[128];
            path3(l, sizeof l, mod, " ", argv[i]);
            if (!has_line(want, l)) {
                say(fd, "not declared in mod.json (vehicle read): ");
                say(fd, argv[i]);
                say(fd, "\n");
                return 13;
            }
        }
        if (slen(req) + slen(argv[i]) + 3 >= sizeof req) { say(fd, "too many readings\n"); return 2; }
        path3(req, sizeof req, req, " ", argv[i]);
    }
    path3(req, sizeof req, req, "\n", "");
    s = x_socket(1, 1 | 02000000, 0);
    if (s < 0) return 1;
    x_setsockopt((int)s, 1 /* SOL_SOCKET */, 20 /* SO_RCVTIMEO */, &tv, sizeof tv);
    if (x_connect(s, &a, alen) < 0) { x_close(s); say(fd, "the vehicle service isn't running (yet)\n"); return 69; }
    if (wall((int)s, req, slen(req))) { x_close(s); return 1; }
    got = 0;
    while ((n = x_read((int)s, buf, sizeof buf)) > 0) {
        last = buf[n - 1];
        got = 1;
        if (frame(fd, 'O', buf, (u32)n)) break;
    }
    x_close(s);
    /* the answer is whole lines in one write: a timeout after a whole answer isn't a failure
       (#145: the service's close didn't reach us, so every read ended on SO_RCVTIMEO) */
    if (n < 0 && got && last == '\n') return 0;
    return n < 0 ? 1 : 0;
}

/* A vehicle command (hmgapi.engine.Vehicle.COMMANDS) through HMG-API's vehicle service,
   which applies its guard. A mod only the ones it declared (state/commands.txt:
   "<mod> <command>"); mod 0 (root, HMG-API's app): any, as "-". */
static int op_vehicle_do(int fd, const char *mod, int argc, char **argv)
{
    static char want[4096], req[512], buf[1024];
    struct sun a;
    int alen = sockaddr_of(&a, VEHICLE_SOCK), i;
    sl s, n;
    struct { sl sec, usec; } tv = { 10, 0 };
    for (i = 1; i < argc; i++) {
        const char *c;
        for (c = argv[i]; *c; c++) if (!((*c >= 'a' && *c <= 'z') || (*c >= '0' && *c <= '9') || *c == '_' || *c == '-')) break;
        if (*c || !argv[i][0] || slen(argv[i]) > 32) { say(fd, "bad command or argument\n"); return 2; }
    }
    if (mod) {
        char l[128];
        if (read_small(ROOTDIR "/state/commands.txt", want, sizeof want) < 0) want[0] = 0;
        path3(l, sizeof l, mod, " ", argv[1]);
        if (!has_line(want, l)) {
            say(fd, "not declared in mod.json (vehicle commands): ");
            say(fd, argv[1]);
            say(fd, "\n");
            return 13;
        }
    }
    path3(req, sizeof req, "do ", mod ? mod : "-", "");
    for (i = 1; i < argc; i++) path3(req, sizeof req, req, " ", argv[i]);
    path3(req, sizeof req, req, "\n", "");
    s = x_socket(1, 1 | 02000000, 0);
    if (s < 0) return 1;
    x_setsockopt((int)s, 1, 20 /* SO_RCVTIMEO */, &tv, sizeof tv);
    if (x_connect(s, &a, alen) < 0) { x_close(s); say(fd, "the vehicle service isn't running (yet)\n"); return 69; }
    if (wall((int)s, req, slen(req))) { x_close(s); return 1; }
    n = x_read((int)s, buf, sizeof buf - 1);
    x_close(s);
    if (n <= 0) { say(fd, "no answer from the vehicle service\n"); return 1; }
    buf[n] = 0;
    say(fd, buf);
    return spre(buf, "ok") ? 0 : 1;
}

/* The MICOM recorder (hmgapi.vehicle.MicomWatch, in the vehicle service): "all", or
   category ids in hex; "off". No argument: what it watches. Frames go to
   logs/hmgapi/micom.log (micom-log). Listening only. */
static int op_micom_watch(int fd, int argc, char **argv)
{
    static char cur[256];
    char want[256];
    int i;
    if (argc == 1) {
        if (read_small(ROOTDIR "/state/micom-watch.txt", cur, sizeof cur) < 0) say(fd, "off\n");
        else { say(fd, cur); say(fd, "\n"); }
        return 0;
    }
    if (seq(argv[1], "off")) {
        x_unlink(ROOTDIR "/state/micom-watch.txt");
        publish_state();
        say(fd, "off\n");
        return 0;
    }
    want[0] = 0;
    for (i = 1; i < argc; i++) {
        const char *c = argv[i];
        int hex = !seq(c, "all");
        if (hex) {
            if (spre(c, "0x")) c += 2;
            if (!*c || slen(c) > 2) { say(fd, "micom-watch all | <category id in hex>... | off\n"); return 2; }
            for (; *c; c++) if (!((*c >= '0' && *c <= '9') || (*c >= 'a' && *c <= 'f'))) { say(fd, "categories are hex ids (88 = HEV)\n"); return 2; }
            if (seq(argv[i], "c5") || seq(argv[i], "0xc5")) { say(fd, "c5 (telematics: identity, account, phone numbers) is never watched\n"); return 13; }
        }
        path3(want, sizeof want, want, i > 1 ? " " : "", argv[i]);
    }
    if (write_small(ROOTDIR "/state/micom-watch.txt", want) < 0) { say(fd, "can't save it\n"); return 1; }
    publish_state();   // the vehicle service runs while the watch is on
    say(fd, want);
    say(fd, "\n");
    return 0;
}

/* The owner's switches, read by the vehicle service (settings/allow/<what>): startup_notice
   (mods may dismiss the startup notice), block_ambient_light (no mod may change the
   ambient light). Both off unless the owner turns them on. No argument: whether it's on. */
/* the owner's answer for a mod's command: cmd.<mod>.<command> (allowed), nocmd.<mod>.<command> (not) */
static int answer_name(const char *n)
{
    if (!(spre(n, "cmd.") || spre(n, "nocmd."))) return 0;
    return valid_id(n) && slen(n) < 100;
}

/* allow commands_reset on: forget every answer (the owner is asked again) */
static int forget_answers(int fd)
{
    static char buf[4096];
    sl d = x_open(ROOTDIR "/settings/allow", O_RDONLY | O_DIRECTORY | O_CLOEXEC, 0), n;
    if (d >= 0) {
        while ((n = x_getdents64(d, buf, sizeof buf)) > 0) {
            sl off = 0;
            while (off < n) {
                unsigned short rec = (unsigned short)((u8)buf[off + 16] | ((u8)buf[off + 17] << 8));
                char *name = buf + off + 19, p[160];
                off += rec;
                if (!answer_name(name)) continue;
                path3(p, sizeof p, ROOTDIR "/settings/allow/", name, "");
                x_unlink(p);
            }
        }
        x_close(d);
    }
    logline("owner reset", "command answers", -1, 0);
    say(fd, "forgotten\n");
    return 0;
}

static int op_allow(int fd, int argc, char **argv)
{
    char p[160];
    unsigned long long st[16];
    if (seq(argv[1], "commands_reset") && argc == 3 && seq(argv[2], "on")) return forget_answers(fd);
    if (!seq(argv[1], "startup_notice") && !seq(argv[1], "block_ambient_light") && !seq(argv[1], "trust_commands")
        && !seq(argv[1], "ota_updates") && !seq(argv[1], "testing_maps") && !answer_name(argv[1])) {
        say(fd, "unknown switch (startup_notice, block_ambient_light, trust_commands, ota_updates, testing_maps, cmd.<mod>.<command>, "
                "nocmd.<mod>.<command>, commands_reset)\n");
        return 2;
    }
    path3(p, sizeof p, ROOTDIR "/settings/allow/", argv[1], "");
    if (argc == 2) { say(fd, x_stat64(p, st) == 0 ? "on\n" : "off\n"); return 0; }
    if (seq(argv[2], "on")) {
        sl f;
        x_mkdir(ROOTDIR "/settings", 0700);
        x_mkdir(ROOTDIR "/settings/allow", 0700);
        f = x_open(p, O_WRONLY | O_CREAT | O_CLOEXEC, 0600);
        if (f < 0) { say(fd, "can't save it\n"); return 1; }
        x_close(f);
        logline("owner allowed", argv[1], -1, 0);
    } else if (seq(argv[2], "off")) {
        x_unlink(p);
        logline("owner disallowed", argv[1], -1, 0);
    } else {
        say(fd, "allow <what> on|off\n");
        return 2;
    }
    say(fd, argv[2]);
    say(fd, "\n");
    return 0;
}

/* What a mod's own app may ask (its peer uid is the app of an enabled mod: g_mod). */
static int handle_mod(int fd, int argc, char **argv)
{
    const char *op = argv[0];
    if (seq(op, "whoami") && argc == 1) { say(fd, g_mod); say(fd, "\n"); return 0; }
    if (seq(op, "setting-get") && (argc == 1 || (argc == 2 && seq(argv[1], g_mod)))) return op_setting_get(fd, g_mod);
    if (seq(op, "setting-set") && argc == 2) return op_setting_set(fd, g_mod, argv[1]);
    if (seq(op, "services") && argc == 1) return op_services(fd, g_mod);
    if ((seq(op, "service-restart") || seq(op, "service-stop") || seq(op, "service-start")) && argc == 2)
        return op_svc_ctl(fd, op + 8, g_mod, argv[1]);
    if (seq(op, "service-log") && argc == 2) return op_svc_log(fd, g_mod, argv[1]);
    if (seq(op, "usb") && argc == 1) return op_usb(fd, g_mod);
    if (seq(op, "vehicle") && argc >= 2) return op_vehicle(fd, g_mod, argc, argv);
    if (seq(op, "vehicle-do") && argc >= 2 && argc <= 5) return op_vehicle_do(fd, g_mod, argc, argv);
    say(fd, "not allowed for a mod's app\n");
    return 13;
}

static int handle(int fd, int whoami, int argc, char **argv)
{
    const char *op = argv[0];
    if (seq(op, "ping")) { say(fd, "ok\n"); return 0; }
    if (seq(op, "version")) { say(fd, "hmgapid " VERSION "\n"); return 0; }
    if (whoami == WHO_MOD) return handle_mod(fd, argc, argv);
    if (whoami == WHO_OTHER) { say(fd, "not allowed\n"); return 13; }

    if (seq(op, "mods") && argc == 1) return op_mods(fd);
    if (seq(op, "enable") && argc == 2) return op_toggle(fd, argv[1], 1);
    if (seq(op, "disable") && argc == 2) return op_toggle(fd, argv[1], 0);
    if (seq(op, "setting-get") && argc == 2) return op_setting_get(fd, argv[1]);
    if (seq(op, "mod-file") && argc == 3) return op_mod_file(fd, argv[1], argv[2]);
    if (seq(op, "setting-set") && argc == 3) return op_setting_set(fd, argv[1], argv[2]);
    if (seq(op, "log") && argc == 1) {   /* the log keeper's file (the end of it); logcat until it has one */
        char *a[] = { LOGCAT, "-d", "-v", "time", "-s", "HMG-API:V", 0 };
        sl k = x_open(ROOTDIR "/logs/hmgapi/log.log", O_RDONLY | O_CLOEXEC, 0);
        if (k >= 0) { x_close(k); return op_svc_log(fd, "hmgapi", "log"); }
        return run(fd, a);
    }
    if (seq(op, "engine") && argc >= 2) return op_engine(fd, argc, argv);
    if (seq(op, "services") && argc <= 2) return op_services(fd, argc == 2 ? argv[1] : 0);
    if ((seq(op, "service-restart") || seq(op, "service-stop") || seq(op, "service-start")) && argc == 3)
        return op_svc_ctl(fd, op + 8, argv[1], argv[2]);
    if (seq(op, "service-log") && argc == 3) return op_svc_log(fd, argv[1], argv[2]);
    if (seq(op, "usb") && argc <= 2) return op_usb(fd, argc == 2 ? argv[1] : 0);
    if (seq(op, "vehicle") && argc >= 2) return op_vehicle(fd, 0, argc, argv);
    if (seq(op, "vehicle-do") && argc >= 2 && argc <= 5) return op_vehicle_do(fd, 0, argc, argv);
    if (seq(op, "allow") && (argc == 2 || argc == 3)) return op_allow(fd, argc, argv);
    if (seq(op, "micom-watch") && argc <= 9) return op_micom_watch(fd, argc, argv);
    if (seq(op, "micom-log") && argc == 1) return op_svc_log(fd, "hmgapi", "micom");
    if (seq(op, "reboot") && argc == 1) {
        char *a[] = { REBOOT, 0 };
        say(fd, "restarting\n");
        return run(fd, a);
    }
    if (whoami != WHO_ROOT) { say(fd, "not allowed\n"); return 13; }

    if (seq(op, "whoami") && argc == 1) { say(fd, g_mod[0] ? g_mod : "root"); say(fd, "\n"); return 0; }
    if (seq(op, "sh") && argc == 2) {
        char *a[] = { SH, "-c", argv[1], 0 };
        return run(fd, a);
    }
    if (seq(op, "remount") && argc == 2) return op_remount(fd, argv[1]);
    if (seq(op, "binds") && argc == 1) return op_binds(fd);
    say(fd, "unknown op or wrong arguments\n");
    return 2;
}

static char req[MAX_REQ + MAX_ARGS];

static void serve(int fd)
{
    struct ucred cr;
    u32 len = sizeof cr, argc, i, used = 0;
    char magic[4], *argv[MAX_ARGS + 1];
    int code, whoami;
    x_alarm(REQ_TIMEOUT);   /* a client that doesn't send its request is dropped */
    if (x_getsockopt(fd, 1 /* SOL_SOCKET */, 17 /* SO_PEERCRED */, &cr, &len) < 0) return;
    if (rall(fd, magic, 4) || magic[0] != 'L' || magic[1] != 'F' || magic[2] != 'D' || magic[3] != '1') return;
    if (rall(fd, &argc, 4) || argc == 0 || argc > MAX_ARGS) return;
    for (i = 0; i < argc; i++) {
        u32 n;
        if (rall(fd, &n, 4) || n > MAX_ARG || used + n + 1 > sizeof req) return;
        if (n && rall(fd, req + used, n)) return;
        req[used + n] = 0;
        argv[i] = req + used;
        used += n + 1;
    }
    argv[argc] = 0;
    x_alarm(0);
    whoami = who(cr.uid, cr.pid);
    code = handle(fd, whoami, (int)argc, argv);
    if (!seq(argv[0], "ping")) {   /* "<time> uid <n> (<who>) <op> -> <code>" */
        line_t l; l.n = 0; l.b[0] = 0;
        lnum(&l, cr.uid);
        lput(&l, whoami == WHO_ROOT ? " (root) " : whoami == WHO_APP ? " (app) " : " (other) ");
        lput(&l, argv[0]);
        lput(&l, " ->");
        logline("uid", l.b, code, 0);
    }
    done(fd, code);
}

/* ---------------- daemon ---------------- */
static int full_root(void)
{
    static char buf[2048];
    sl f = x_open("/proc/self/status", O_RDONLY | O_CLOEXEC, 0), n;
    const char *p;
    ul v = 0;
    if (f < 0) return 0;
    n = x_read(f, buf, sizeof buf - 1);
    x_close(f);
    if (n <= 0) return 0;
    buf[n] = 0;
    p = sstr(buf, "CapEff:");
    if (!p) return 0;
    p += 7;
    while (*p == '\t' || *p == ' ') p++;
    {   /* 16 hex digits; the low 32 bits (last 8) hold the capabilities checked */
        const char *q = p;
        while ((*q >= '0' && *q <= '9') || (*q >= 'a' && *q <= 'f')) q++;
        if (q - p > 8) p = q - 8;
        for (; p < q; p++) v = v * 16 + (ul)(*p <= '9' ? *p - '0' : *p - 'a' + 10);
    }
    return (v & 0x200003) == 0x200003;   /* CAP_SYS_ADMIN, CAP_DAC_OVERRIDE, CAP_CHOWN */
}

struct ksigaction { void *handler; ul flags; void *restorer; u32 mask[2]; };

static int daemon_main(void)
{
    struct sun a;
    int alen = sockaddr(&a), workers = 0;
    sl s, r;
    int ready[2];
#ifndef NOCAPCHECK
    if (!full_root()) {
        out(2, "hmgapid: not full root (started from adb?); start it from the boot hook\n");
        return 1;
    }
#endif
    x_umask(077);
    x_mkdir(ROOTDIR, 0700);
    /* earlier installs (NEMESIS daemon, umask 0) may have left these open to everyone */
    x_chmod(ROOTDIR, 0711);   /* traverse only: apps read public/ and mods' mod.json/hooks.jar */
    x_chmod(ROOTDIR "/mods", 0711);
    x_chmod(ROOTDIR "/settings", 0700);
    x_chmod(ROOTDIR "/stock", 0700);
    x_chmod(ROOTDIR "/hook", 0700);
    x_mkdir(ROOTDIR "/state", 0700);
    x_unlink(ROOTDIR "/state/services.status");   /* nothing runs yet */
    x_unlink(ROOTDIR "/public/services.txt");
    s = x_socket(1, 1 | 02000000 /* SOCK_STREAM | SOCK_CLOEXEC */, 0);
    if (s < 0) return 1;
    r = x_bind(s, &a, alen);
    if (r == -EADDRINUSE) { out(1, "hmgapid: already running\n"); return 0; }
    if (r < 0 || x_listen(s, 16) < 0) { out(2, "hmgapid: can't listen\n"); return 1; }

    /* detach: the boot hook's process may be killed when it returns */
    if (x_pipe(ready) < 0) return 1;
    r = x_fork();
    if (r < 0) return 1;
    if (r > 0) {   /* wait until the daemon is up, then return to the boot hook */
        char c;
        sl child = r, got;
        x_close(ready[1]);
        x_close(s);
        got = x_read(ready[0], &c, 1);
        x_wait4(child, 0, 0);
        out(1, got == 1 ? "hmgapid: started\n" : "hmgapid: start failed\n");
        return got == 1 ? 0 : 1;
    }
    x_setsid();
    r = x_fork();
    if (r != 0) x_exit(r < 0 ? 1 : 0);
    {
        struct ksigaction ign;
        sl nul = x_open("/dev/null", O_RDWR, 0);
        mset(&ign, 0, sizeof ign);
        ign.handler = (void *)1;   /* SIG_IGN: a client that hangs up mustn't kill us */
        x_sigaction(SIGPIPE, &ign, 0);
        if (nul >= 0) { x_dup2(nul, 0); x_dup2(nul, 1); x_dup2(nul, 2); if (nul > 2) x_close(nul); }
    }
    logline("hmgapid " VERSION " started, pid", 0, x_getpid(), 0);
    x_write(ready[1], "1", 1);
    x_close(ready[1]);
    x_close(ready[0]);

    for (;;) {
        struct { int fd; short ev, rev; } p = { (int)s, POLLIN, 0 };
        sl c;
        for (;;) {   /* children: services and op workers */
            int st = 0;
            sl k = x_wait4(-1, &st, WNOHANG);
            if (k <= 0) break;
            if (!svc_exited(k, st, x_time()) && workers > 0) workers--;
        }
        svc_tick();
        if (x_poll(&p, 1, 1000) <= 0) continue;
        c = x_accept(s);
        if (c < 0) continue;
        if (workers >= MAX_WORKERS) { done((int)c, 75); x_close(c); continue; }   /* busy */
        r = x_fork();
        if (r == 0) {
            x_close(s);
            serve((int)c);
            x_exit(0);
        }
        if (r > 0) workers++;
        x_close(c);
    }
}

/* The boot hook's one line (install-recovery.sh, qb_runtime.sh) and HMG-API's NEMESIS
 * service. It returns at once, so the boot script goes straight on to NEMESIS's start:
 * waiting on HMG-API's boot steps held it (two minutes in 1.0.0). A detached child (own
 * session, none of the caller's fds: init's oneshot cleanup can't take it down, and it
 * holds none of the script's pipes) starts the daemon if none runs (the zygote wrapper
 * usually started it already), then runs boot.sh if it's the first this boot: BOOTMARK,
 * made with O_EXCL on tmpfs, is the once-per-boot lock, so the other triggers stop there. */
static int boot_main(void)
{
    static char *a[] = { SH, ROOTDIR "/boot.sh", 0 };
    sl r, f;
    int i;
#ifndef NOCAPCHECK
    if (!full_root()) {
        out(2, "hmgapid: not full root (started from adb?); start it from the boot hook\n");
        return 1;
    }
#endif
    r = x_fork();
    if (r < 0) return 1;
    if (r > 0) {   /* the middle child exits at once; the grandchild is init's */
        while (x_wait4(r, 0, 0) == -EINTR) ;
        return 0;
    }
    x_setsid();
    if (x_fork() != 0) x_exit(0);
    f = x_open("/dev/null", O_RDWR, 0);
    if (f >= 0) { x_dup2(f, 0); x_dup2(f, 1); x_dup2(f, 2); }
    for (i = 3; i < 1024; i++) x_close(i);
    if (daemon_main() != 0) x_exit(0);
    f = x_open(BOOTMARK, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (f < 0) x_exit(0);   /* another trigger ran boot.sh this boot */
    x_close(f);
    f = x_open(ROOTDIR "/boot.log", O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (f >= 0) { x_dup2(f, 1); x_dup2(f, 2); if (f > 2) x_close(f); }
    logline("boot: running boot.sh", 0, -1, 0);
    x_execve(a[0], a, g_env);
    logline("boot: can't run boot.sh", 0, -1, 0);
    x_exit(127);
}

/* ---------------- client ---------------- */
static int client(int argc, char **argv)
{
    struct sun a;
    int alen = sockaddr(&a);
    sl s = x_socket(1, 1 | 02000000, 0), r;
    u32 n = (u32)argc, i;
    static char buf[65536];
    if (s < 0) return 1;
    r = x_connect(s, &a, alen);
    if (r < 0) {
        out(2, r == -ECONNREFUSED || r == -ENOENT || r == -111 ? "hmgapid: not running\n" : "hmgapid: can't connect\n");
        return 1;
    }
    if (wall((int)s, "LFD1", 4) || wall((int)s, &n, 4)) return 1;
    for (i = 0; i < n; i++) {
        u32 k = (u32)slen(argv[i]);
        if (wall((int)s, &k, 4) || wall((int)s, argv[i], k)) return 1;
    }
    for (;;) {
        u8 h[5];
        u32 k;
        if (rall((int)s, h, 5)) { out(2, "hmgapid: connection lost\n"); return 1; }
        k = (u32)h[1] | ((u32)h[2] << 8) | ((u32)h[3] << 16) | ((u32)h[4] << 24);
        if (h[0] == 'X') {
            u32 code = 0;
            if (k != 4 || rall((int)s, &code, 4)) return 1;
            return (int)code;
        }
        while (k) {
            u32 m = k > sizeof buf ? sizeof buf : k;
            if (rall((int)s, buf, m)) return 1;
            wall(1, buf, m);
            k -= m;
        }
    }
}

int main(int argc, char **argv, char **envp)
{
    g_env = envp;
    if (argc < 2) {
        out(2, "usage: hmgapid daemon | hmgapid boot | hmgapid <op> [args...]\n"
               "ops: ping, version, mods, enable <id>, disable <id>, setting-get <id>,\n"
               "     setting-set <id> <json>, mod-file <id> mod.json|settings.json, log, reboot, engine <plan|apply|keep|restore|status|recover>,\n"
               "     services [mod], service-restart|service-stop|service-start <mod> <name>, service-log <mod> <name>,\n"
               "     usb [mod], sh <command>, remount rw|ro, binds\n"
               "a mod's own app: whoami, setting-get, setting-set <json>, services, service-restart|stop|start <name>,\n"
               "     service-log <name>, usb\n");
        return 2;
    }
    if (seq(argv[1], "daemon")) return daemon_main();
    if (seq(argv[1], "boot") && argc == 2) return boot_main();
    return client(argc - 1, argv + 1);
}

__attribute__((naked, noreturn)) void _start(void)
{
    asm volatile("ldr r0,[sp]\n add r1,sp,#4\n add r2,r1,r0,lsl #2\n add r2,r2,#4\n"
                 "bl main\n mov r7,#248\n svc 0\n");
}

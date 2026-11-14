/*
 * Coexistence-safe BLE HCI/ACL filter inside com.android.bluetooth (libhmgapi_hook.so).
 *
 * Gen5W's stock Bluetooth app disables BluetoothGattService in config.xml, leaving Java
 * BLE scanner and GATT APIs non-functional while Broadcom's vendor HCI transport
 * (bplus.default.so -> BLUETOOTH_HC_LIB_INTERFACE) and Bluedroid's GKI buffer/task
 * engine remain active in-process.
 *
 * Nothing here runs in the zygote or in other apps. When an authorized BLE session starts
 * inside com.android.bluetooth, Bridge.bleInitNative resolves GKI + BLUETOOTH_HC_LIB_INTERFACE,
 * interposes bt_hc_cbacks->data_ind, and spawns one TX pump thread over a pipe pair:
 *   - Inbound 0x3E (LE Meta Event) frames are captured to Java and freed before Bluedroid's
 *     btm_ble_conn_complete can open a competing fixed-channel ATT bearer; new LE handles
 *     (subevents 0x01 / 0x0A) are tracked immediately in-callback before any 0x13 credit
 *     event or ACL RX frame can race Java.
 *   - Inbound 0x05 / 0x08 / 0x30 and 0x1100 ACL RX frames are intercepted ONLY when their
 *     12-bit connection handle is in g_le_handles; Classic BR/EDR handles (HFP, A2DP,
 *     AVRCP, PBAP, SPP) pass straight through to stock Bluedroid.
 *   - Inbound 0x13 (Number_Of_Completed_Packets) batches are inspected entry-by-entry:
 *     pure-Classic batches pass untouched, pure-LE batches are captured and freed, and
 *     mixed batches have only tracked LE handle counts zeroed in-place before forwarding
 *     so Bluedroid's Classic L2CAP credit window never drifts.
 *   - Inbound 0x0E / 0x0F command completions are copied to Java AND forwarded to stock
 *     Bluedroid so btu_hcif's command credit counter never stalls.
 *   - emit_rx_packet writes atomically (<= 1027 bytes < PIPE_BUF) to an O_NONBLOCK pipe:
 *     if the Java reader ever falls behind, frames drop immediately (g_pkt_drop_count)
 *     and Bluedroid's HCI receive thread never waits.
 *   - Outbound HCI commands go to BTU_TASK (0x1600) so btu_hcif_send_cmd serializes them
 *     with Classic traffic; outbound LE ACL frames go to transmit_buf (0x2100).
 *
 * Built freestanding against syslib-stubs.sh (no NDK headers). AGPL-3.0-or-later.
 */

typedef unsigned char  u1;
typedef unsigned short u2;
typedef unsigned int   u4;

#ifndef BLE_HOST_TEST
typedef unsigned long size_t;
typedef long          pthread_t;

int __android_log_print(int prio, const char *tag, const char *fmt, ...);
int open(const char *path, int flags, ...);
long read(int fd, void *buf, size_t n);
long write(int fd, const void *buf, size_t n);
int close(int fd);
int dup(int oldfd);
int fcntl(int fd, int cmd, ...);
void *memcpy(void *d, const void *s, size_t n);
void *memset(void *d, int c, size_t n);
size_t strlen(const char *s);
void *dlopen(const char *name, int flags);
void *dlsym(void *h, const char *name);
const char *dlerror(void);
int pthread_create(pthread_t *t, const void *attr, void *(*fn)(void *), void *arg);
int pthread_detach(pthread_t t);

#define BLE_TAG "HMG-API"
#define BLE_LOGI(...) __android_log_print(4, BLE_TAG, __VA_ARGS__)
#define BLE_LOGW(...) __android_log_print(5, BLE_TAG, __VA_ARGS__)
#define BLE_LOGE(...) __android_log_print(6, BLE_TAG, __VA_ARGS__)
#else
#define BLE_LOGI(...) ((void)0)
#define BLE_LOGW(...) ((void)0)
#define BLE_LOGE(...) ((void)0)
#endif

#define BLE_RTLD_LAZY  1
#define BLE_F_SETFD    2
#define BLE_F_GETFL    3
#define BLE_F_SETFL    4
#define BLE_FD_CLOEXEC 1
#define BLE_O_NONBLOCK 04000

/* Bluedroid HC / GKI buffer header (8 bytes on both 32-bit ARM and 64-bit host tests) */
typedef struct {
    u2 event;
    u2 len;
    u2 offset;
    u2 layer_specific;
    u1 data[];
} hc_bt_hdr_t;

#define MSG_HC_TO_STACK_HCI_ACL 0x1100
#define MSG_HC_TO_STACK_HCI_EVT 0x1000
#define MSG_STACK_TO_HC_HCI_ACL 0x2100
#define BT_EVT_TO_BTU_HCI_CMD   0x1600
#define LOCAL_BR_EDR_CTL_ID     0x0000

#define BTU_TASK    0
#define TASK_MBOX_0 0

typedef void *(*gki_getbuf_fn)(u2 size);
typedef void  (*gki_freebuf_fn)(void *p_buf);
typedef u1    (*gki_send_msg_fn)(u1 task_id, u1 mbox, void *msg);

/*
 * Both Broadcom BPlus (bplus.default.so, 10-word 0x28 table: dealloc=[6], data_ind=[7])
 * and AOSP 4.4 Bluedroid (7-word 0x1c table: dealloc=[3], data_ind=[4]) pass
 * (p_transac, p_buf, len) to data_ind and (p_transac, p_buf) to dealloc, and keep the
 * stock callback table in read-only RELRO memory behind a writable bt_hc_cbacks pointer.
 */
typedef int (*dealloc_cb)(void *p_transac, char *p_buf);
typedef int (*data_ind_cb)(void *p_transac, char *p_buf, int len);

typedef struct {
    size_t size;
    int  (*init)(const void *p_cb, const u1 *local_bdaddr);
    void (*set_power)(int state);
    int  (*lpm)(int opcode);
    void (*preload)(void *transac);
    int  (*epilog)(void *transac);
    int  (*transmit_buf)(void *p_buf, char *p_data, int len);
    int  (*logging)(int state, const char *p_path, int save_existing);
    void (*cleanup)(void);
    int  (*tx_cmd_with_cb)(void *p_buf, char *p_data, int len, void *tx_cmd_cb);
    int  (*send_int_cmd)(u2 opcode, void *p_buf, void *p_cb);
} bt_hc_interface_t;

#define PKT_TYPE_CMD 0x01
#define PKT_TYPE_ACL 0x02
#define PKT_TYPE_EVT 0x04

#define MAX_TRACKED_LE_HANDLES 8
#define MAX_CBACKS_WORDS       16

static gki_freebuf_fn           g_GKI_freebuf     = 0;
static dealloc_cb               g_orig_dealloc    = 0;
static data_ind_cb              g_orig_data_ind   = 0;
static void                  ***g_pp_bt_hc_cbacks = 0;
static void                   **g_orig_cbacks_ptr = 0;
static void                    *g_my_cbacks[MAX_CBACKS_WORDS];
static int                      g_data_ind_idx    = -1;
#ifndef BLE_HOST_TEST
static gki_getbuf_fn            g_GKI_getbuf      = 0;
static gki_send_msg_fn          g_GKI_send_msg    = 0;
static const bt_hc_interface_t *g_bt_hc_if        = 0;
#endif

static volatile int g_rx_write_fd   = -1;
static volatile int g_ble_listening = 0;
static volatile u2  g_le_handles[MAX_TRACKED_LE_HANDLES];
static volatile int g_rx_pipe_lock  = 0;

static volatile int g_pkt_rx_count     = 0;
static volatile int g_pkt_tx_count     = 0;
static volatile int g_pkt_drop_count   = 0;
static volatile int g_hook_installed   = 0;
static volatile int g_tx_thread_active = 0;

static int is_tracked_le_handle(u2 h)
{
    int i;
    if (h == 0 || h > 0x0EFF) return 0;
    for (i = 0; i < MAX_TRACKED_LE_HANDLES; i++) {
        if (g_le_handles[i] == h) return 1;
    }
    return 0;
}

static void track_le_handle(u2 h)
{
    int i;
    if (h == 0 || h > 0x0EFF) return;
    for (i = 0; i < MAX_TRACKED_LE_HANDLES; i++) {
        if (g_le_handles[i] == h) return;
    }
    for (i = 0; i < MAX_TRACKED_LE_HANDLES; i++) {
        if (g_le_handles[i] == 0) {
            g_le_handles[i] = h;
            return;
        }
    }
}

static void untrack_le_handle(u2 h)
{
    int i;
    if (h == 0) return;
    for (i = 0; i < MAX_TRACKED_LE_HANDLES; i++) {
        if (g_le_handles[i] == h) g_le_handles[i] = 0;
    }
}

static void clear_le_handles(void)
{
    int i;
    for (i = 0; i < MAX_TRACKED_LE_HANDLES; i++) g_le_handles[i] = 0;
}

static int count_le_handles(void)
{
    int i, n = 0;
    for (i = 0; i < MAX_TRACKED_LE_HANDLES; i++) {
        if (g_le_handles[i] != 0) n++;
    }
    return n;
}

static void lock_rx_pipe(void)
{
    while (__sync_lock_test_and_set(&g_rx_pipe_lock, 1)) {
        /* spin briefly: critical section is one non-blocking write() syscall */
    }
}

static void unlock_rx_pipe(void)
{
    __sync_lock_release(&g_rx_pipe_lock);
}

static int read_exact(int fd, u1 *buf, int len)
{
    int got = 0;
    while (got < len) {
        long r = read(fd, buf + got, (size_t)(len - got));
        if (r <= 0) return -1;
        got += (int)r;
    }
    return 0;
}

/*
 * Frame layout on both pipes: [u2 pkt_len BE][u1 pkt_type][payload].
 * Max frame size is 1027 bytes (< PIPE_BUF = 4096), and g_rx_write_fd is O_NONBLOCK:
 * POSIX guarantees write() writes the entire frame atomically or returns -1 (EAGAIN)
 * without partial writes or blocking Bluedroid's HCI receive thread.
 */
static void emit_rx_packet(u1 pkt_type, const u1 *payload, int len)
{
    u1 frame[1040];
    int fd, pkt_len, total_len;
    long r;
    if (len <= 0 || len > 1024) return;
    pkt_len = 1 + len;
    total_len = 2 + pkt_len;
    frame[0] = (u1)((pkt_len >> 8) & 0xFF);
    frame[1] = (u1)(pkt_len & 0xFF);
    frame[2] = pkt_type;
    memcpy(frame + 3, payload, (size_t)len);

    lock_rx_pipe();
    fd = g_rx_write_fd;
    if (fd >= 0) {
        r = write(fd, frame, (size_t)total_len);
        if (r == (long)total_len) g_pkt_rx_count++;
        else g_pkt_drop_count++;
    }
    unlock_rx_pipe();
}

static void free_rx_buf(dealloc_cb orig_dealloc, void *p_transac, char *p_buf)
{
    if (orig_dealloc) orig_dealloc(p_transac, p_buf);
    else if (g_GKI_freebuf) g_GKI_freebuf(p_transac);
}

static int my_data_ind(void *p_transac, char *p_buf, int buf_len)
{
    data_ind_cb orig_ind = g_orig_data_ind;
    dealloc_cb orig_dealloc = g_orig_dealloc;
    hc_bt_hdr_t *hdr;
    u2 evt_mask;
    u1 *raw;
    int len;

    if (!p_transac) return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
    if (!g_ble_listening) return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;

    hdr = (hc_bt_hdr_t *)p_transac;
    evt_mask = (u2)(hdr->event & 0xFF00);
    raw = hdr->data + hdr->offset;
    len = (int)hdr->len;

    if (len <= 0) return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;

    if (evt_mask == MSG_HC_TO_STACK_HCI_EVT) {
        u1 evt_code = raw[0];

        if (evt_code == 0x3E) {
            /* LE Meta Event: capture and consume so stock Bluedroid never opens a
               competing ATT channel or disconnects our LE link. */
            if (len >= 6) {
                u1 subevt = raw[2], status = raw[3];
                if ((subevt == 0x01 || subevt == 0x0A) && status == 0x00) {
                    u2 conn_h = (u2)(((raw[5] << 8) | raw[4]) & 0x0FFF);
                    track_le_handle(conn_h);
                }
            }
            emit_rx_packet(PKT_TYPE_EVT, raw, len);
            free_rx_buf(orig_dealloc, p_transac, p_buf);
            return 0;
        }

        if (evt_code == 0x05 && len >= 5) {
            u2 h = (u2)(((raw[4] << 8) | raw[3]) & 0x0FFF);
            if (is_tracked_le_handle(h)) {
                if (raw[2] == 0x00) untrack_le_handle(h);
                emit_rx_packet(PKT_TYPE_EVT, raw, len);
                free_rx_buf(orig_dealloc, p_transac, p_buf);
                return 0;
            }
            return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
        }

        if ((evt_code == 0x08 || evt_code == 0x30) && len >= 5) {
            u2 h = (u2)(((raw[4] << 8) | raw[3]) & 0x0FFF);
            if (is_tracked_le_handle(h)) {
                emit_rx_packet(PKT_TYPE_EVT, raw, len);
                free_rx_buf(orig_dealloc, p_transac, p_buf);
                return 0;
            }
            return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
        }

        if (evt_code == 0x13 && len >= 3) {
            int num_handles = (int)raw[2], any_our = 0, any_other = 0, i;
            for (i = 0; i < num_handles; i++) {
                int base = 3 + (i * 4);
                u2 h, c;
                if (base + 4 > len) break;
                h = (u2)(((raw[base + 1] << 8) | raw[base]) & 0x0FFF);
                c = (u2)((raw[base + 3] << 8) | raw[base + 2]);
                if (is_tracked_le_handle(h)) {
                    if (c > 0) any_our = 1;
                } else {
                    if (c > 0) any_other = 1;
                }
            }
            if (!any_our) return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
            emit_rx_packet(PKT_TYPE_EVT, raw, len);
            if (!any_other) {
                free_rx_buf(orig_dealloc, p_transac, p_buf);
                return 0;
            }
            /* Mixed Classic + LE batch: zero our LE counts in-place and forward */
            for (i = 0; i < num_handles; i++) {
                int base = 3 + (i * 4);
                u2 h;
                if (base + 4 > len) break;
                h = (u2)(((raw[base + 1] << 8) | raw[base]) & 0x0FFF);
                if (is_tracked_le_handle(h)) {
                    raw[base + 2] = 0;
                    raw[base + 3] = 0;
                }
            }
            return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
        }

        if (evt_code == 0x0E || evt_code == 0x0F) {
            emit_rx_packet(PKT_TYPE_EVT, raw, len);
            return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
        }

        return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
    }

    if (evt_mask == MSG_HC_TO_STACK_HCI_ACL && len >= 4) {
        u2 conn_handle = (u2)(((raw[1] << 8) | raw[0]) & 0x0FFF);
        if (is_tracked_le_handle(conn_handle)) {
            emit_rx_packet(PKT_TYPE_ACL, raw, len);
            free_rx_buf(orig_dealloc, p_transac, p_buf);
            return 0;
        }
    }

    return orig_ind ? orig_ind(p_transac, p_buf, buf_len) : 0;
}

static int resolve_cbacks_layout(void **cur, int *n_words, int *dealloc_idx, int *data_ind_idx)
{
    size_t sz;
    if (!cur) return -1;
    sz = (size_t)cur[0];
    if (sz == 10 * sizeof(void *)) {
        /* Broadcom BPlus (Gen5W bplus.default.so: 0x28 bytes = 10 words on 32-bit ARM) */
        *n_words = 10;
        *dealloc_idx = 6;
        *data_ind_idx = 7;
        return 0;
    }
    if (sz == 7 * sizeof(void *)) {
        /* Standard AOSP 4.4 Bluedroid (0x1c bytes = 7 words on 32-bit ARM) */
        *n_words = 7;
        *dealloc_idx = 3;
        *data_ind_idx = 4;
        return 0;
    }
    return -1;
}

static int install_cbacks_hook(void ***pp_cbacks)
{
    void **cur;
    int n_words = 0, dealloc_idx = -1, data_ind_idx = -1, i;

    if (!pp_cbacks || !*pp_cbacks) return -5;
    cur = *pp_cbacks;
    if (cur == g_my_cbacks && g_data_ind_idx >= 0) {
        g_pp_bt_hc_cbacks = pp_cbacks;
        g_hook_installed = 1;
        return 0;
    }
    if (resolve_cbacks_layout(cur, &n_words, &dealloc_idx, &data_ind_idx) < 0 || !cur[data_ind_idx]) {
        BLE_LOGE("ble: unexpected bt_hc_callbacks_t size=0x%lx", (unsigned long)(size_t)cur[0]);
        return -5;
    }

    for (i = 0; i < n_words; i++) {
        g_my_cbacks[i] = cur[i];
    }
    g_orig_cbacks_ptr = cur;
    g_orig_dealloc = (dealloc_cb)cur[dealloc_idx];
    g_orig_data_ind = (data_ind_cb)cur[data_ind_idx];
    g_data_ind_idx = data_ind_idx;
    g_pp_bt_hc_cbacks = pp_cbacks;
    g_my_cbacks[data_ind_idx] = (void *)my_data_ind;

    __sync_synchronize();
    *pp_cbacks = g_my_cbacks;
    __sync_synchronize();
    g_hook_installed = 1;
    return 0;
}

static int is_hook_intact(void)
{
    if (!g_hook_installed || !g_pp_bt_hc_cbacks || g_data_ind_idx < 0) return 0;
    if (*g_pp_bt_hc_cbacks != g_my_cbacks) return 0;
    if (g_my_cbacks[g_data_ind_idx] != (void *)my_data_ind) return 0;
    return 1;
}

void n_bleShutdown(void *env, void *cls);

#ifndef BLE_HOST_TEST
static void *tx_pump_thread(void *arg)
{
    int fd = (int)(long)arg;
    u1 hdr_buf[2], pkt_buf[1032];
    BLE_LOGI("ble: tx pump started on fd=%d", fd);

    for (;;) {
        int pkt_len, payload_len;
        u1 pkt_type;
        u1 *payload;

        if (read_exact(fd, hdr_buf, 2) < 0) break;
        pkt_len = ((int)hdr_buf[0] << 8) | (int)hdr_buf[1];
        if (pkt_len <= 1 || pkt_len > 1028) {
            BLE_LOGE("ble: invalid tx frame len=%d", pkt_len);
            break;
        }
        if (read_exact(fd, pkt_buf, pkt_len) < 0) break;

        pkt_type = pkt_buf[0];
        payload = pkt_buf + 1;
        payload_len = pkt_len - 1;

        if (pkt_type == PKT_TYPE_CMD && payload_len >= 3) {
            hc_bt_hdr_t *bt_hdr;
            if (!g_GKI_getbuf || !g_GKI_send_msg) continue;
            bt_hdr = (hc_bt_hdr_t *)g_GKI_getbuf((u2)(16 + payload_len));
            if (!bt_hdr) { g_pkt_drop_count++; continue; }
            bt_hdr->event = BT_EVT_TO_BTU_HCI_CMD;
            bt_hdr->len = (u2)payload_len;
            bt_hdr->offset = 0;
            bt_hdr->layer_specific = LOCAL_BR_EDR_CTL_ID;
            memcpy(bt_hdr->data, payload, (size_t)payload_len);
            g_GKI_send_msg(BTU_TASK, TASK_MBOX_0, bt_hdr);
            g_pkt_tx_count++;
        } else if (pkt_type == PKT_TYPE_ACL && payload_len >= 4) {
            hc_bt_hdr_t *bt_hdr;
            if (!g_GKI_getbuf || !g_bt_hc_if || !g_bt_hc_if->transmit_buf) continue;
            bt_hdr = (hc_bt_hdr_t *)g_GKI_getbuf((u2)(16 + payload_len));
            if (!bt_hdr) { g_pkt_drop_count++; continue; }
            bt_hdr->event = MSG_STACK_TO_HC_HCI_ACL;
            bt_hdr->len = (u2)payload_len;
            bt_hdr->offset = 0;
            bt_hdr->layer_specific = 0;
            memcpy(bt_hdr->data, payload, (size_t)payload_len);
            g_bt_hc_if->transmit_buf(bt_hdr, (char *)bt_hdr->data, payload_len);
            g_pkt_tx_count++;
        }
    }

    /* Sole owner of dup_tx: Java closes tx[1] to signal EOF here, avoiding double-close */
    close(fd);
    __sync_sub_and_fetch(&g_tx_thread_active, 1);
    BLE_LOGI("ble: tx pump exited");
    return 0;
}

static int contains_str(const char *hay, int hay_len, const char *needle)
{
    int nlen = (int)strlen(needle), i, j;
    if (nlen <= 0 || hay_len < nlen) return 0;
    for (i = 0; i <= hay_len - nlen; i++) {
        for (j = 0; j < nlen; j++) {
            if (hay[i + j] != needle[j]) break;
        }
        if (j == nlen) return 1;
    }
    return 0;
}

/*
 * Android 4.4 Bionic's dlopen rejects RTLD_NOLOAD (flag 4). Scanning /proc/self/maps
 * first ensures we only call dlopen(..., RTLD_LAZY) on libraries already loaded in
 * com.android.bluetooth.
 */
static int is_lib_mapped(const char *soname)
{
    char buf[512];
    int fd = open("/proc/self/maps", 0);
    int keep = 0;
    if (fd < 0) return 0;
    for (;;) {
        long n = read(fd, buf + keep, (size_t)(sizeof(buf) - (size_t)keep));
        int total;
        if (n <= 0) break;
        total = keep + (int)n;
        if (contains_str(buf, total, soname)) {
            close(fd);
            return 1;
        }
        keep = total > 64 ? 64 : total;
        memcpy(buf, buf + (total - keep), (size_t)keep);
    }
    close(fd);
    return 0;
}

static void *open_mapped_lib(const char *soname, const char *const *paths)
{
    int i;
    if (!is_lib_mapped(soname)) return 0;
    for (i = 0; paths[i]; i++) {
        void *h = dlopen(paths[i], BLE_RTLD_LAZY);
        if (h) return h;
    }
    return 0;
}

int n_bleInit(void *env, void *cls, int tx_read_fd, int rx_write_fd)
{
    static const char *const bplus_paths[] = {
        "/system/lib/hw/bplus.default.so",
        "bplus.default.so",
        0
    };
    static const char *const aosp_stack_paths[] = {
        "/system/lib/hw/bluetooth.default.so",
        "bluetooth.default.so",
        0
    };
    static const char *const aosp_hci_paths[] = {
        "/system/lib/libbt-hci.so",
        "libbt-hci.so",
        0
    };
    void *h_stack, *h_hc;
    const bt_hc_interface_t *hc_if;
    void ***pp_cbacks;
    int n_words = 0, dealloc_idx = -1, data_ind_idx = -1;
    int dup_tx, dup_rx, fl, old_rx, rc;
    pthread_t tid = 0;

    (void)env; (void)cls;
    dlerror();

    /* Gen5W Broadcom BPlus keeps both GKI and BLUETOOTH_HC_LIB_INTERFACE in bplus.default.so */
    h_stack = open_mapped_lib("bplus.default.so", bplus_paths);
    if (!h_stack) {
        h_stack = open_mapped_lib("bluetooth.default.so", aosp_stack_paths);
    }
    if (!h_stack) {
        BLE_LOGE("ble: neither bplus.default.so nor bluetooth.default.so mapped");
        return -1;
    }
    g_GKI_getbuf   = (gki_getbuf_fn)dlsym(h_stack, "GKI_getbuf");
    g_GKI_freebuf  = (gki_freebuf_fn)dlsym(h_stack, "GKI_freebuf");
    g_GKI_send_msg = (gki_send_msg_fn)dlsym(h_stack, "GKI_send_msg");
    if (!g_GKI_getbuf || !g_GKI_freebuf || !g_GKI_send_msg) {
        BLE_LOGE("ble: missing GKI symbols");
        return -2;
    }

    h_hc = dlsym(h_stack, "bt_hc_cbacks") ? h_stack : open_mapped_lib("libbt-hci.so", aosp_hci_paths);
    if (!h_hc) {
        BLE_LOGE("ble: libbt-hci.so / bt_hc_cbacks not mapped");
        return -3;
    }
    hc_if = (const bt_hc_interface_t *)dlsym(h_hc, "bluetoothHCLibInterface");
    if (!hc_if) {
        const bt_hc_interface_t *(*get_if)(void) =
            (const bt_hc_interface_t *(*)(void))dlsym(h_hc, "bt_hc_get_interface");
        if (get_if) hc_if = get_if();
    }
    if (!hc_if || !hc_if->transmit_buf) {
        BLE_LOGE("ble: missing BLUETOOTH_HC_LIB_INTERFACE");
        return -4;
    }
    g_bt_hc_if = hc_if;

    pp_cbacks = (void ***)dlsym(h_hc, "bt_hc_cbacks");
    if (!pp_cbacks || !*pp_cbacks) {
        BLE_LOGE("ble: bt_hc_cbacks not initialized");
        return -5;
    }
    if (*pp_cbacks != g_my_cbacks &&
        (resolve_cbacks_layout(*pp_cbacks, &n_words, &dealloc_idx, &data_ind_idx) < 0 ||
         !(*pp_cbacks)[data_ind_idx])) {
        BLE_LOGE("ble: unsupported bt_hc_callbacks_t layout");
        return -5;
    }

    dup_tx = dup(tx_read_fd);
    dup_rx = dup(rx_write_fd);
    if (dup_tx < 0 || dup_rx < 0) {
        if (dup_tx >= 0) close(dup_tx);
        if (dup_rx >= 0) close(dup_rx);
        return -6;
    }
    fcntl(dup_tx, BLE_F_SETFD, BLE_FD_CLOEXEC);
    fcntl(dup_rx, BLE_F_SETFD, BLE_FD_CLOEXEC);
    fl = fcntl(dup_rx, BLE_F_GETFL, 0);
    if (fcntl(dup_rx, BLE_F_SETFL, (fl >= 0 ? fl : 0) | BLE_O_NONBLOCK) < 0) {
        BLE_LOGE("ble: O_NONBLOCK on rx pipe failed");
        close(dup_tx);
        close(dup_rx);
        return -6;
    }

    lock_rx_pipe();
    old_rx = g_rx_write_fd;
    g_rx_write_fd = dup_rx;
    unlock_rx_pipe();
    if (old_rx >= 0) close(old_rx);

    rc = install_cbacks_hook(pp_cbacks);
    if (rc != 0) {
        close(dup_tx);
        lock_rx_pipe();
        if (g_rx_write_fd == dup_rx) g_rx_write_fd = -1;
        unlock_rx_pipe();
        close(dup_rx);
        return rc;
    }

    __sync_add_and_fetch(&g_tx_thread_active, 1);
    if (pthread_create(&tid, 0, tx_pump_thread, (void *)(long)dup_tx) != 0) {
        __sync_sub_and_fetch(&g_tx_thread_active, 1);
        BLE_LOGE("ble: pthread_create failed");
        n_bleShutdown(0, 0);
        close(dup_tx);
        return -7;
    }
    pthread_detach(tid);
    BLE_LOGI("ble: attached to Bluedroid HCI transport");
    return 0;
}
#endif

/*
 * handle_op:
 *   -1            keep tracked LE handles unchanged
 *   0x0001..0EFF  add handle to tracked LE table
 *   0x10000|h     remove handle from tracked LE table
 *   0x20000       clear all tracked LE handles
 */
int n_bleState(void *env, void *cls, int listening, int handle_op)
{
    (void)env; (void)cls;
    g_ble_listening = listening ? 1 : 0;
    if (handle_op >= 0) {
        int op = (handle_op >> 16) & 0xFF;
        u2 h = (u2)(handle_op & 0x0FFF);
        if (op == 0 && h != 0) track_le_handle(h);
        else if (op == 1 && h != 0) untrack_le_handle(h);
        else if (op == 2) clear_le_handles();
    }
    return 0;
}

int n_bleStat(void *env, void *cls, int which)
{
    (void)env; (void)cls;
    switch (which) {
    case 0: return is_hook_intact();
    case 1: return g_tx_thread_active > 0 ? 1 : 0;
    case 2: return g_pkt_rx_count;
    case 3: return g_pkt_tx_count;
    case 4: return g_pkt_drop_count;
    case 5: return g_ble_listening;
    case 6: return count_le_handles();
    default: return -1;
    }
}

void n_bleShutdown(void *env, void *cls)
{
    int old_rx;
    (void)env; (void)cls;
    g_ble_listening = 0;
    clear_le_handles();
    if (g_pp_bt_hc_cbacks && *g_pp_bt_hc_cbacks == g_my_cbacks && g_orig_cbacks_ptr) {
        *g_pp_bt_hc_cbacks = g_orig_cbacks_ptr;
        __sync_synchronize();
    }
    g_hook_installed = 0;

    lock_rx_pipe();
    old_rx = g_rx_write_fd;
    g_rx_write_fd = -1;
    unlock_rx_pipe();
#ifndef BLE_HOST_TEST
    if (old_rx >= 0) close(old_rx);
#else
    (void)old_rx;
#endif
}

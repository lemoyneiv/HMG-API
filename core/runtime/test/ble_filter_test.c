/*
 * Host unit tests for core/loader/ble.c's Bluedroid HCI/ACL coexistence filter:
 *   - install_cbacks_hook on both a PROT_READ 10-word Broadcom BPlus callback table
 *     (verifying zero writes to read-only RELRO memory) and a 7-word AOSP 4.4 table;
 *   - pass-through when listening is off;
 *   - 0x3E LE Meta Event capture + immediate LE handle tracking;
 *   - Classic vs tracked-LE ACL and Disconnection_Complete routing;
 *   - 0x0E Command_Complete copy-and-forward;
 *   - 0x13 Number_Of_Completed_Packets pure-Classic, pure-LE, and mixed zeroing;
 *   - O_NONBLOCK atomic pipe overflow: never blocks data_ind, counts drops, preserves framing;
 *   - hook health check on HCI callback replacement + clean pointer restore on n_bleShutdown.
 */
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#ifndef MAP_ANONYMOUS
#define MAP_ANONYMOUS MAP_ANON
#endif

#include "../../loader/ble.c"

static int g_forwarded = 0;
static int g_dealloc_freed = 0;
static int g_gki_freed = 0;
static int g_bad_args = 0;
static u1  g_last_forwarded[128];
static int g_last_forwarded_len = 0;

static int fake_orig_data_ind(void *p_transac, char *p_buf, int len)
{
    hc_bt_hdr_t *h = (hc_bt_hdr_t *)p_transac;
    if (!h || p_buf != (char *)(h->data + h->offset) || len != (int)h->len) {
        g_bad_args++;
    }
    g_forwarded++;
    g_last_forwarded_len = (int)h->len;
    if (h->len <= sizeof g_last_forwarded) {
        memcpy(g_last_forwarded, h->data + h->offset, h->len);
    }
    return 0;
}

static int fake_orig_dealloc(void *p_transac, char *p_buf)
{
    hc_bt_hdr_t *h = (hc_bt_hdr_t *)p_transac;
    if (!h || p_buf != (char *)(h->data + h->offset)) {
        g_bad_args++;
    }
    g_dealloc_freed++;
    return 0;
}

static void fake_gki_freebuf(void *p_buf)
{
    (void)p_buf;
    g_gki_freed++;
}

static int call_ind(hc_bt_hdr_t *b)
{
    return my_data_ind(b, (char *)(b->data + b->offset), (int)b->len);
}

static hc_bt_hdr_t *mk_pkt(u2 event, const u1 *payload, int len)
{
    hc_bt_hdr_t *h = (hc_bt_hdr_t *)calloc(1, sizeof(hc_bt_hdr_t) + (size_t)len);
    h->event = event;
    h->len = (u2)len;
    h->offset = 0;
    memcpy(h->data, payload, (size_t)len);
    return h;
}

static int read_pipe_frame(int fd, u1 *out_type, u1 *out_buf, int cap)
{
    u1 hdr[2], t;
    int pkt_len, pay_len;
    if (read_exact(fd, hdr, 2) < 0) return -1;
    pkt_len = ((int)hdr[0] << 8) | (int)hdr[1];
    if (pkt_len < 1 || pkt_len - 1 > cap) return -1;
    if (read_exact(fd, &t, 1) < 0) return -1;
    *out_type = t;
    pay_len = pkt_len - 1;
    if (pay_len > 0 && read_exact(fd, out_buf, pay_len) < 0) return -1;
    return pay_len;
}

static int fail = 0;

static void check_int(const char *name, int want, int got)
{
    if (want == got) printf("ok    %s\n", name);
    else { printf("FAIL  %s: wanted [%d], got [%d]\n", name, want, got); fail = 1; }
}

static void check_true(const char *name, int cond)
{
    if (cond) printf("ok    %s\n", name);
    else { printf("FAIL  %s\n", name); fail = 1; }
}

int main(void)
{
    int pfd[2];
    u1 rx_type = 0, rx_buf[128];
    int n, fl;
    void *ro_page;
    void **bplus_ro;
    void *other_cbacks[10];
    void *aosp_cbacks[7];
    void *bad_cbacks[5];
    void **p_cbacks;

    if (pipe(pfd) != 0) return 2;
    fl = fcntl(pfd[1], F_GETFL, 0);
    if (fcntl(pfd[1], F_SETFL, (fl >= 0 ? fl : 0) | O_NONBLOCK) < 0) return 2;

    /* Place a 10-word Broadcom BPlus callback table inside a strict PROT_READ page */
    ro_page = mmap(0, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (ro_page == MAP_FAILED) return 2;
    bplus_ro = (void **)ro_page;
    memset(bplus_ro, 0, 10 * sizeof(void *));
    bplus_ro[0] = (void *)(10 * sizeof(void *));
    bplus_ro[6] = (void *)fake_orig_dealloc;
    bplus_ro[7] = (void *)fake_orig_data_ind;
    if (mprotect(ro_page, 4096, PROT_READ) != 0) return 2;

    g_rx_write_fd = pfd[1];
    g_GKI_freebuf = fake_gki_freebuf;
    p_cbacks = bplus_ro;

    check_int("native: install_cbacks_hook succeeds on PROT_READ 10-word BPlus table",
              0, install_cbacks_hook(&p_cbacks));
    check_true("native: bt_hc_cbacks redirected to writable copy", p_cbacks == g_my_cbacks);
    check_int("native: hook initially intact", 1, n_bleStat(0, 0, 0));

    /* 1. Listening off: LE Meta Event passes straight through to stock Bluedroid */
    {
        const u1 le_conn[] = {
            0x3E, 0x13, 0x01, 0x00, 0x41, 0x00, 0x00, 0x00,
            0x33, 0x22, 0x11, 0xCC, 0xBB, 0xAA, 0x10, 0x00, 0x00, 0x00, 0x90, 0x01, 0x00
        };
        hc_bt_hdr_t *b = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, le_conn, (int)sizeof le_conn);
        n_bleState(0, 0, 0, 0x20000);
        call_ind(b);
        check_int("native: listening off forwards 0x3E untouched", 1, g_forwarded);
        check_int("native: listening off frees nothing", 0, g_dealloc_freed + g_gki_freed);
        check_int("native: listening off tracks no handle", 0, n_bleStat(0, 0, 6));
        free(b);
    }

    /* 2. Listening on: 0x3E LE_Connection_Complete is captured, freed, and handle 0x0041 tracked */
    {
        const u1 le_conn[] = {
            0x3E, 0x13, 0x01, 0x00, 0x41, 0x00, 0x00, 0x00,
            0x33, 0x22, 0x11, 0xCC, 0xBB, 0xAA, 0x10, 0x00, 0x00, 0x00, 0x90, 0x01, 0x00
        };
        hc_bt_hdr_t *b = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, le_conn, (int)sizeof le_conn);
        g_forwarded = g_dealloc_freed = g_gki_freed = 0;
        n_bleState(0, 0, 1, -1);
        call_ind(b);
        check_int("native: 0x3E consumed before stock Bluedroid", 0, g_forwarded);
        check_int("native: 0x3E buffer freed via 2-arg dealloc", 1, g_dealloc_freed);
        check_int("native: 0x3E tracks LE handle 0x0041 immediately", 1, n_bleStat(0, 0, 6));
        n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
        check_int("native: 0x3E emitted as EVT frame on pipe", PKT_TYPE_EVT, rx_type);
        check_int("native: 0x3E pipe payload length", (int)sizeof le_conn, n);
        free(b);
    }

    /* 3. Classic ACL vs tracked LE ACL */
    {
        const u1 classic_acl[] = { 0x0B, 0x20, 0x04, 0x00, 0xDE, 0xAD, 0xBE, 0xEF };
        const u1 le_acl[]      = { 0x41, 0x20, 0x04, 0x00, 0x00, 0x00, 0x04, 0x00 };
        hc_bt_hdr_t *bc = mk_pkt(MSG_HC_TO_STACK_HCI_ACL, classic_acl, (int)sizeof classic_acl);
        hc_bt_hdr_t *bl = mk_pkt(MSG_HC_TO_STACK_HCI_ACL, le_acl, (int)sizeof le_acl);
        g_forwarded = g_dealloc_freed = 0;
        call_ind(bc);
        check_int("native: Classic BR/EDR ACL (0x000B) forwarded untouched", 1, g_forwarded);
        check_int("native: Classic BR/EDR ACL not freed", 0, g_dealloc_freed);

        g_forwarded = g_dealloc_freed = 0;
        call_ind(bl);
        check_int("native: tracked LE ACL (0x0041) intercepted", 0, g_forwarded);
        check_int("native: tracked LE ACL freed", 1, g_dealloc_freed);
        n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
        check_int("native: tracked LE ACL emitted as ACL frame", PKT_TYPE_ACL, rx_type);
        free(bc);
        free(bl);
    }

    /* 4. 0x0E Command_Complete copied to Java AND forwarded to Bluedroid */
    {
        const u1 cmd_cmp[] = { 0x0E, 0x04, 0x01, 0x0C, 0x20, 0x00 };
        hc_bt_hdr_t *b = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, cmd_cmp, (int)sizeof cmd_cmp);
        g_forwarded = g_dealloc_freed = 0;
        call_ind(b);
        check_int("native: 0x0E forwarded to stock Bluedroid", 1, g_forwarded);
        check_int("native: 0x0E not freed by filter", 0, g_dealloc_freed);
        n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
        check_int("native: 0x0E also copied to Java pipe", (int)sizeof cmd_cmp, n);
        free(b);
    }

    /* 5. 0x13 Number_Of_Completed_Packets: pure Classic, pure LE, and mixed Classic+LE */
    {
        const u1 nocp_classic[] = { 0x13, 0x05, 0x01, 0x0B, 0x00, 0x03, 0x00 };
        const u1 nocp_le[]      = { 0x13, 0x05, 0x01, 0x41, 0x00, 0x02, 0x00 };
        const u1 nocp_mixed[]   = { 0x13, 0x09, 0x02, 0x0B, 0x00, 0x04, 0x00, 0x41, 0x00, 0x02, 0x00 };
        hc_bt_hdr_t *bc = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, nocp_classic, (int)sizeof nocp_classic);
        hc_bt_hdr_t *bl = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, nocp_le, (int)sizeof nocp_le);
        hc_bt_hdr_t *bm = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, nocp_mixed, (int)sizeof nocp_mixed);

        g_forwarded = g_dealloc_freed = 0;
        call_ind(bc);
        check_int("native: pure-Classic 0x13 forwarded without pipe copy", 1, g_forwarded);

        g_forwarded = g_dealloc_freed = 0;
        call_ind(bl);
        check_int("native: pure-LE 0x13 consumed before Bluedroid", 0, g_forwarded);
        check_int("native: pure-LE 0x13 freed", 1, g_dealloc_freed);
        n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
        check_int("native: pure-LE 0x13 delivered to Java", 2, rx_buf[5]);

        g_forwarded = g_dealloc_freed = 0;
        call_ind(bm);
        n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
        check_int("native: mixed 0x13 gives Java original LE credit count", 2, rx_buf[9]);
        check_int("native: mixed 0x13 still forwarded to Bluedroid", 1, g_forwarded);
        check_int("native: mixed 0x13 preserves Classic credit count (4)", 4, g_last_forwarded[5]);
        check_int("native: mixed 0x13 zeroes LE credit count for Bluedroid", 0, g_last_forwarded[9] | g_last_forwarded[10]);
        free(bc);
        free(bl);
        free(bm);
    }

    /* 6. 0x05 Disconnection_Complete: Classic passes through, tracked LE consumed & untracked */
    {
        const u1 disc_classic[] = { 0x05, 0x04, 0x00, 0x0B, 0x00, 0x13 };
        const u1 disc_le[]      = { 0x05, 0x04, 0x00, 0x41, 0x00, 0x13 };
        hc_bt_hdr_t *bc = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, disc_classic, (int)sizeof disc_classic);
        hc_bt_hdr_t *bl = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, disc_le, (int)sizeof disc_le);

        g_forwarded = g_dealloc_freed = 0;
        call_ind(bc);
        check_int("native: Classic 0x05 forwarded untouched", 1, g_forwarded);
        check_int("native: LE handle still tracked after Classic disconnect", 1, n_bleStat(0, 0, 6));

        g_forwarded = g_dealloc_freed = 0;
        call_ind(bl);
        check_int("native: tracked LE 0x05 consumed", 0, g_forwarded);
        check_int("native: LE handle untracked on 0x05", 0, n_bleStat(0, 0, 6));
        n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
        check_int("native: tracked LE 0x05 delivered to Java", 0x05, rx_buf[0]);
        free(bc);
        free(bl);
    }

    /* 7. Non-blocking full pipe: data_ind never blocks, counts drops, keeps framing intact */
    {
        u1 adv_evt[48];
        int rx_before = n_bleStat(0, 0, 2), drop_before = n_bleStat(0, 0, 4);
        int sent = 0, drained = 0, i;
        memset(adv_evt, 0xA5, sizeof adv_evt);
        adv_evt[0] = 0x3E;
        adv_evt[1] = (u1)(sizeof adv_evt - 2);
        adv_evt[2] = 0x02; /* LE Advertising Report */

        /* Push enough frames (~250 KB) to overflow any OS pipe buffer without reading */
        for (i = 0; i < 5000; i++) {
            hc_bt_hdr_t *b = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, adv_evt, (int)sizeof adv_evt);
            call_ind(b);
            free(b);
            sent++;
        }
        int accepted = n_bleStat(0, 0, 2) - rx_before;
        int dropped = n_bleStat(0, 0, 4) - drop_before;
        check_true("native: full pipe accepts initial frames before saturation", accepted > 0);
        check_true("native: full pipe drops excess frames without blocking", dropped > 100);
        check_int("native: accepted + dropped equals total frames", sent, accepted + dropped);

        /* Drain every accepted frame and verify none was torn/corrupted */
        for (i = 0; i < accepted; i++) {
            n = read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
            if (n != (int)sizeof adv_evt || rx_type != PKT_TYPE_EVT || rx_buf[0] != 0x3E) break;
            drained++;
        }
        check_int("native: every accepted frame preserved atomic framing", accepted, drained);

        /* Once drained, next frame succeeds again */
        rx_before = n_bleStat(0, 0, 2);
        hc_bt_hdr_t *b = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, adv_evt, (int)sizeof adv_evt);
        call_ind(b);
        free(b);
        check_int("native: pipe resumes accepting frames after drain", rx_before + 1, n_bleStat(0, 0, 2));
        (void)read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);
    }

    /* 8. HCI restart detection & clean n_bleShutdown restore to PROT_READ BPlus table */
    {
        memset(other_cbacks, 0, sizeof other_cbacks);
        other_cbacks[0] = (void *)(10 * sizeof(void *));
        other_cbacks[7] = (void *)fake_orig_data_ind;
        p_cbacks = other_cbacks;
        check_int("native: detects Bluedroid replacing bt_hc_cbacks pointer", 0, n_bleStat(0, 0, 0));

        p_cbacks = g_my_cbacks;
        check_int("native: hook intact when bt_hc_cbacks matches", 1, n_bleStat(0, 0, 0));

        n_bleShutdown(0, 0);
        check_int("native: n_bleShutdown clears hook_installed", 0, n_bleStat(0, 0, 0));
        check_true("native: n_bleShutdown restores original PROT_READ BPlus pointer", p_cbacks == bplus_ro);
    }

    /* 9. AOSP 4.4 7-word callback table + GKI_freebuf fallback + unknown layout rejection */
    {
        const u1 adv_evt[] = { 0x3E, 0x04, 0x02, 0x00, 0x00, 0x00 };
        hc_bt_hdr_t *b = mk_pkt(MSG_HC_TO_STACK_HCI_EVT, adv_evt, (int)sizeof adv_evt);

        memset(bad_cbacks, 0, sizeof bad_cbacks);
        bad_cbacks[0] = (void *)(5 * sizeof(void *));
        p_cbacks = bad_cbacks;
        check_int("native: rejects unexpected callback table size", -5, install_cbacks_hook(&p_cbacks));

        memset(aosp_cbacks, 0, sizeof aosp_cbacks);
        aosp_cbacks[0] = (void *)(7 * sizeof(void *));
        aosp_cbacks[3] = 0; /* test GKI_freebuf fallback when dealloc == 0 */
        aosp_cbacks[4] = (void *)fake_orig_data_ind;
        p_cbacks = aosp_cbacks;
        g_rx_write_fd = pfd[1];
        g_gki_freed = 0;

        check_int("native: install_cbacks_hook succeeds on 7-word AOSP 4.4 table",
                  0, install_cbacks_hook(&p_cbacks));
        n_bleState(0, 0, 1, -1);
        call_ind(b);
        check_int("native: falls back to GKI_freebuf when dealloc is null", 1, g_gki_freed);
        (void)read_pipe_frame(pfd[0], &rx_type, rx_buf, (int)sizeof rx_buf);

        n_bleShutdown(0, 0);
        check_true("native: n_bleShutdown restores 7-word AOSP pointer", p_cbacks == aosp_cbacks);
        free(b);
    }

    check_int("native: 3-arg data_ind and 2-arg dealloc received exact ABI args", 0, g_bad_args);

    munmap(ro_page, 4096);
    close(pfd[0]);
    close(pfd[1]);
    return fail;
}

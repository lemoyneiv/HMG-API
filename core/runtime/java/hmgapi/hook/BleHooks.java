package hmgapi.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;

import hmgapi.ble.BleAuth;
import hmgapi.ble.BleCentral;
import hmgapi.ble.BleTransport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HMG-API's BLE central service inside {@code com.android.bluetooth}.
 *
 * When no enabled mod declares {@code "ble": {"central": true}}, {@link #init} only records
 * the process {@link Context} — no {@code @hmgapi-ble} socket, threads, BroadcastReceiver,
 * or native Bluedroid hooks are installed. {@link #sync} starts {@code @hmgapi-ble} when an
 * enabled BLE mod appears in {@code public/state.json}, and tears it down again when the
 * last BLE mod is disabled.
 */
final class BleHooks {
    private BleHooks() {}

    static final String PACKAGE = "com.android.bluetooth";
    private static final String ACTION_BT_STATE_CHANGED = "android.bluetooth.adapter.action.STATE_CHANGED";
    private static final String EXTRA_BT_STATE = "android.bluetooth.adapter.extra.STATE";
    private static final int BT_STATE_OFF = 10;
    private static final int BT_STATE_TURNING_OFF = 13;

    private static volatile Context btContext;
    private static volatile BleAuth.State authState = BleAuth.State.EMPTY;
    private static BleCentral central;
    private static LocalServerSocket activeServer;
    private static BroadcastReceiver btStateReceiver;

    static void init(LoadedApp app) {
        if (app.context != null) {
            btContext = app.context;
        }
    }

    static synchronized void sync(Map<String, Object> mods) {
        Context ctx = btContext;
        if (ctx == null) return;

        BleAuth.State next = BleAuth.State.fromModsMap(mods);
        authState = next;

        if (!next.enabledBleMods.isEmpty()) {
            if (central == null) {
                central = new BleCentral(new NativeTransport());
            }
            central.retainEnabledMods(next.enabledBleMods);
            ensureServerStartedLocked(ctx, central);
        } else {
            stopServerLocked(ctx);
        }
    }

    private static void ensureServerStartedLocked(Context ctx, final BleCentral c) {
        if (activeServer != null) return;
        final LocalServerSocket srv;
        try {
            srv = new LocalServerSocket(BleCentral.SOCKET);
        } catch (IOException e) {
            HmgApi.e("ble server bind", e);
            return;
        }
        activeServer = srv;

        if (btStateReceiver == null) {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_BT_STATE_CHANGED.equals(intent.getAction())) return;
                    int state = intent.getIntExtra(EXTRA_BT_STATE, -1);
                    if (state == BT_STATE_TURNING_OFF || state == BT_STATE_OFF) {
                        Thread t = new Thread("hmgapi-ble-btstate") {
                            @Override
                            public void run() {
                                c.onAdapterDown();
                            }
                        };
                        t.setDaemon(true);
                        t.start();
                    }
                }
            };
            ctx.registerReceiver(receiver, new IntentFilter(ACTION_BT_STATE_CHANGED));
            btStateReceiver = receiver;
        }

        Thread accept = new Thread("hmgapi-ble-accept") {
            @Override
            public void run() {
                HmgApi.i("ble: listening on @" + BleCentral.SOCKET);
                try {
                    while (activeServer == srv) {
                        LocalSocket s = srv.accept();
                        dispatchClient(c, s);
                    }
                } catch (Throwable t) {
                    if (activeServer == srv) {
                        HmgApi.e("ble server", t);
                    }
                } finally {
                    try { srv.close(); } catch (IOException ignored) {}
                }
            }
        };
        accept.setDaemon(true);
        accept.start();
    }

    private static void stopServerLocked(Context ctx) {
        if (btStateReceiver != null) {
            try {
                ctx.unregisterReceiver(btStateReceiver);
            } catch (Throwable ignored) {}
            btStateReceiver = null;
        }
        LocalServerSocket srv = activeServer;
        activeServer = null;
        if (srv != null) {
            try { srv.close(); } catch (IOException ignored) {}
            HmgApi.i("ble: stopped @" + BleCentral.SOCKET + " (no enabled BLE mods)");
        }
        if (central != null) {
            central.retainEnabledMods(Collections.<String>emptySet());
        }
    }

    private static void dispatchClient(final BleCentral c, final LocalSocket s) {
        final Closeable closer = singleClose(s);
        Thread worker = new Thread("hmgapi-ble-client") {
            @Override
            public void run() {
                try {
                    Credentials cred = s.getPeerCredentials();
                    int uid = cred.getUid();
                    int pid = cred.getPid();
                    String[] pkgs = null;
                    Context ctx = btContext;
                    if (ctx != null && uid >= 10000) {
                        PackageManager pm = ctx.getPackageManager();
                        if (pm != null) pkgs = pm.getPackagesForUid(uid);
                    }
                    String owner = BleAuth.authorize(uid, pid, pkgs, authState, BleAuth.SYSTEM_ENV);
                    c.serve(s.getInputStream(), s.getOutputStream(), closer, owner);
                } catch (Throwable t) {
                    HmgApi.e("ble client", t);
                } finally {
                    try { closer.close(); } catch (IOException ignored) {}
                }
            }
        };
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Closes an accepted LocalSocket's fd at most once. On KitKat (API 19),
     * {@code LocalServerSocket.accept()} leaves the accepted fd unowned, so
     * {@code LocalSocket.close()} alone leaks the fd without sending EOF (#164).
     */
    private static Closeable singleClose(final LocalSocket s) {
        final AtomicBoolean done = new AtomicBoolean(false);
        return new Closeable() {
            @Override
            public void close() {
                if (!done.compareAndSet(false, true)) return;
                java.io.FileDescriptor fd = s.getFileDescriptor();
                try {
                    if (fd != null && fd.valid() && libcore.io.Libcore.os != null) {
                        libcore.io.Libcore.os.close(fd);
                    }
                } catch (Throwable t) {
                    HmgApi.e("ble: close socket fd", t);
                }
                try {
                    s.close();
                } catch (Exception ignored) {}
            }
        };
    }

    private static final class NativeTransport implements BleTransport {
        private volatile boolean ready;
        private volatile boolean listening;
        private OutputStream txOut;
        private InputStream rxIn;

        @Override
        public synchronized boolean ensureReady(final Receiver receiver) throws IOException {
            if (isReady() && txOut != null) return true;
            if (ready) {
                shutdown();
            }
            int lastRc = 0;
            for (int attempt = 0; attempt < 6; attempt++) {
                ParcelFileDescriptor[] tx = ParcelFileDescriptor.createPipe();
                ParcelFileDescriptor[] rx = ParcelFileDescriptor.createPipe();
                int rc = Bridge.bleInitNative(tx[0].getFd(), rx[1].getFd());
                lastRc = rc;
                try { tx[0].close(); } catch (IOException ignored) {}
                try { rx[1].close(); } catch (IOException ignored) {}
                if (rc == 0) {
                    txOut = new ParcelFileDescriptor.AutoCloseOutputStream(tx[1]);
                    rxIn = new ParcelFileDescriptor.AutoCloseInputStream(rx[0]);
                    ready = true;
                    startRxPump(rxIn, receiver);
                    return true;
                }
                try { tx[1].close(); } catch (IOException ignored) {}
                try { rx[0].close(); } catch (IOException ignored) {}
                if (attempt < 5) {
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { break; }
                }
            }
            HmgApi.i("ble: bleInitNative failed after retries: rc=" + lastRc);
            return false;
        }

        private void startRxPump(final InputStream in, final Receiver receiver) {
            Thread rx = new Thread("hmgapi-ble-rx") {
                @Override
                public void run() {
                    try {
                        while (ready) {
                            Packet p = Framing.readPacket(in);
                            if (receiver != null) receiver.onPacket(p.type, p.payload);
                        }
                    } catch (IOException e) {
                        if (ready) HmgApi.i("ble: rx pipe closed (" + e.getMessage() + ")");
                    } finally {
                        ready = false;
                    }
                }
            };
            rx.setDaemon(true);
            rx.start();
        }

        @Override
        public boolean isReady() {
            return ready
                    && Bridge.bleStatNative(STAT_HOOK_INSTALLED) == 1
                    && Bridge.bleStatNative(STAT_TX_THREAD) == 1;
        }

        @Override
        public synchronized void shutdown() {
            ready = false;
            listening = false;
            OutputStream out = txOut;
            InputStream in = rxIn;
            txOut = null;
            rxIn = null;
            if (out != null) {
                try { out.close(); } catch (IOException ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (IOException ignored) {}
            }
            Bridge.bleShutdownNative();
        }

        @Override
        public void sendCommand(int opcode, byte[] params) throws IOException {
            OutputStream out = txOut;
            if (!ready || out == null) throw new IOException("BLE native transport not ready");
            Framing.writePacket(out, PKT_CMD, Framing.buildCmdPayload(opcode, params));
        }

        @Override
        public void sendAcl(int connHandle, int pbFlag, byte[] l2capPayload) throws IOException {
            OutputStream out = txOut;
            if (!ready || out == null) throw new IOException("BLE native transport not ready");
            Framing.writePacket(out, PKT_ACL, Framing.buildAclPayload(connHandle, pbFlag, l2capPayload));
        }

        @Override
        public void setListening(boolean enable) {
            listening = enable;
            if (ready) Bridge.bleStateNative(enable ? 1 : 0, -1);
        }

        @Override
        public void trackHandle(int connHandle) {
            if (ready && connHandle > 0 && connHandle <= 0x0EFF) {
                Bridge.bleStateNative(listening ? 1 : 0, connHandle & 0x0FFF);
            }
        }

        @Override
        public void untrackHandle(int connHandle) {
            if (ready && connHandle > 0 && connHandle <= 0x0EFF) {
                Bridge.bleStateNative(listening ? 1 : 0, 0x10000 | (connHandle & 0x0FFF));
            }
        }

        @Override
        public void clearHandles() {
            if (ready) Bridge.bleStateNative(listening ? 1 : 0, 0x20000);
        }

        @Override
        public int stat(int which) {
            return ready ? Bridge.bleStatNative(which) : 0;
        }

        @Override
        public void log(int priority, String message) {
            Bridge.log(priority, message);
        }
    }
}

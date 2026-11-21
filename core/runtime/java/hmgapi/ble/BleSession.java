package hmgapi.ble;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-client streaming session on {@code @hmgapi-ble} with a bounded, non-blocking
 * outbound queue drained by its own writer thread.
 *
 * {@link #sendLine} is called from {@code hmgapi-ble-rx} (for advertisements, GATT
 * notifications, and disconnect events) as well as request worker threads, and must
 * never block on socket I/O:
 *   - {@code ADV} lines are dropped once the queue reaches {@link #ADV_HIGH_WATER},
 *     keeping headroom for GATT responses and state transitions;
 *   - if a non-{@code ADV} line arrives when the queue is at {@link #QUEUE_CAP}, the
 *     oldest queued {@code ADV} line is evicted first;
 *   - if {@link #QUEUE_CAP} non-{@code ADV} lines pile up undrained, the session's
 *     underlying socket is closed immediately and async cleanup is triggered.
 */
final class BleSession {
    static final int QUEUE_CAP = 256;
    static final int ADV_HIGH_WATER = 192;

    interface OverflowHandler {
        void onSessionOverflow(BleSession session);
    }

    final int id;
    final String owner;
    private final Closeable closeable;
    private final BufferedWriter writer;
    private final OverflowHandler overflowHandler;

    private final Object queueLock = new Object();
    private final ArrayDeque<String> queue = new ArrayDeque<String>(QUEUE_CAP);
    private boolean closingAfterDrain = false;
    private boolean drained = false;
    private long droppedAdv = 0;

    final AtomicBoolean closed = new AtomicBoolean(false);
    final AtomicBoolean overflowed = new AtomicBoolean(false);
    volatile boolean multiConnMode = false;
    volatile int preferredMaxServerMtu = BleGatt.ATT_MAX_SUPPORTED_MTU;
    final Set<String> ownedMacs = Collections.synchronizedSet(new HashSet<String>());

    BleSession(int id, String owner, Closeable closeable, BufferedWriter writer, OverflowHandler overflowHandler) {
        this.id = id;
        this.owner = owner;
        this.closeable = closeable;
        this.writer = writer;
        this.overflowHandler = overflowHandler;
    }

    void startWriter() {
        Thread out = new Thread("hmgapi-ble-out-" + id) {
            @Override
            public void run() {
                drainLoop();
            }
        };
        out.setDaemon(true);
        out.start();
    }

    private void drainLoop() {
        try {
            while (true) {
                String line;
                boolean flushNow;
                synchronized (queueLock) {
                    while (queue.isEmpty() && !closed.get() && !closingAfterDrain) {
                        try {
                            queueLock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (closed.get()) {
                        drained = true;
                        queueLock.notifyAll();
                        return;
                    }
                    if (queue.isEmpty() && closingAfterDrain) {
                        drained = true;
                        queueLock.notifyAll();
                        return;
                    }
                    line = queue.pollFirst();
                    flushNow = queue.isEmpty();
                }
                if (line != null) {
                    writer.write(line);
                    writer.write('\n');
                    if (flushNow) {
                        writer.flush();
                    }
                }
            }
        } catch (IOException ignored) {
            // Client closed or force-closed on overflow.
        } finally {
            synchronized (queueLock) {
                drained = true;
                queueLock.notifyAll();
            }
            closeImmediate();
        }
    }

    void sendLine(String line) {
        if (line == null || closed.get()) return;
        boolean isAdv = isAdvLine(line);
        boolean triggerOverflow = false;

        synchronized (queueLock) {
            if (closed.get() || closingAfterDrain) return;
            if (isAdv && queue.size() >= ADV_HIGH_WATER) {
                droppedAdv++;
                return;
            }
            if (queue.size() >= QUEUE_CAP) {
                boolean evicted = false;
                Iterator<String> it = queue.iterator();
                while (it.hasNext()) {
                    if (isAdvLine(it.next())) {
                        it.remove();
                        droppedAdv++;
                        evicted = true;
                        break;
                    }
                }
                if (!evicted) {
                    closed.set(true);
                    overflowed.set(true);
                    queue.clear();
                    queueLock.notifyAll();
                    triggerOverflow = true;
                }
            }
            if (!triggerOverflow) {
                queue.addLast(line);
                queueLock.notifyAll();
            }
        }

        if (triggerOverflow) {
            Thread drop = new Thread("hmgapi-ble-drop-" + id) {
                @Override
                public void run() {
                    closeTransportOnly();
                    if (overflowHandler != null) {
                        overflowHandler.onSessionOverflow(BleSession.this);
                    }
                }
            };
            drop.setDaemon(true);
            drop.start();
        }
    }

    void sendDeviceEvent(String mac, String eventRest) {
        if (multiConnMode && mac != null) {
            sendLine("@" + mac + " " + eventRest);
        } else {
            sendLine(eventRest);
        }
    }

    long droppedAdvCount() {
        synchronized (queueLock) {
            return droppedAdv;
        }
    }

    private static boolean isAdvLine(String line) {
        return line.startsWith("ADV ");
    }

    /**
     * Signals the writer thread to flush queued lines (such as {@code OK BYE},
     * {@code STATE MOD_DISABLED}, or {@code STATE BT_OFF}) for up to {@code maxWaitMs}
     * before closing the underlying socket.
     */
    void closeAfterDrain(long maxWaitMs) {
        if (closed.get()) return;
        long deadline = System.currentTimeMillis() + Math.max(0L, maxWaitMs);
        synchronized (queueLock) {
            if (!closed.get()) {
                closingAfterDrain = true;
                queueLock.notifyAll();
                while (!drained && !closed.get()) {
                    long rem = deadline - System.currentTimeMillis();
                    if (rem <= 0) break;
                    try {
                        queueLock.wait(rem);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        closeImmediate();
    }

    void closeImmediate() {
        boolean first = closed.compareAndSet(false, true);
        synchronized (queueLock) {
            queue.clear();
            queueLock.notifyAll();
        }
        if (first) {
            closeTransportOnly();
        }
    }

    private void closeTransportOnly() {
        if (closeable != null) {
            try { closeable.close(); } catch (Throwable ignored) {}
        }
        try { writer.close(); } catch (Throwable ignored) {}
    }
}

package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.transport.ReliableChannel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helpers for the best-effort reconnect-and-resume loop used by folder transfers
 * (Phase 4 / L1). The transfer is wrapped in a bounded retry loop: on a dropped
 * connection it reconnects (reusing the same local UDP port so the coord server
 * recognizes the peer) and re-runs the batch — already-transferred files are
 * skipped, so it resumes where it left off. When the attempt/deadline budget is
 * exhausted it aborts gracefully and the user can re-run to finish.
 */
final class BatchRunner {

    private BatchRunner() {}

    /** Exponential backoff between reconnects: 0.5s, 1s, 2s, 4s, 8s (capped). */
    static long backoffMs(int attempt) {
        return Math.min(8_000L, 500L * (1L << Math.min(attempt - 1, 4)));
    }

    /**
     * Watch for the connection dropping mid-transfer. When the PacketRouter
     * declares the peer dead, close the channel (to wake a blocked sender) and
     * interrupt the batch thread (to break blocking awaits) — unless the batch
     * already finished. Returns a started daemon thread; interrupt it on success.
     */
    static Thread startDeathWatcher(PeerConnection conn, ReliableChannel channel,
                                    Thread batchThread, AtomicBoolean done) {
        Thread w = new Thread(() -> {
            try {
                conn.awaitDisconnect();
            } catch (InterruptedException e) {
                return; // success path interrupted us
            }
            if (!done.get()) {
                channel.close();
                batchThread.interrupt();
            }
        }, "death-watch");
        w.setDaemon(true);
        w.start();
        return w;
    }
}

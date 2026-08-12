package com.alterante.p2p.net.tunnel;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The mux must fail cleanly on a corrupt frame rather than trusting its length field.
 *
 * <p>Frame lengths arrive as four raw bytes from the peer. The writer never emits more than
 * {@link StreamMux#MAX_FRAME} — DATA is chunked to it, labels are a handful of bytes — so a larger
 * value does not mean a large frame is coming; it means the frame boundary has been lost. Allocating
 * on it turns four bytes of garbage into a multi-gigabyte allocation followed by a blocking read for
 * data that will never arrive, so the failure surfaces as an OutOfMemoryError or a hang, nowhere
 * near the desync that caused it.
 *
 * <p>This layer carries the BBS and lore sessions, where a clean end beats an OOM in every case.
 */
class StreamMuxFramingTest {

    /** A pipe that replays fixed bytes inbound and discards everything written. */
    private static BytePipe pipeOf(byte[] inbound) {
        return new BytePipe() {
            private final InputStream in = new ByteArrayInputStream(inbound);
            private final OutputStream out = new ByteArrayOutputStream();

            @Override public InputStream in() { return in; }
            @Override public OutputStream out() { return out; }
            @Override public void close() { }
        };
    }

    private static byte[] frame(int type, int id, int declaredLength) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeByte(type);
        d.writeInt(id);
        d.writeInt(declaredLength);     // deliberately not followed by that many bytes
        d.flush();
        return bytes.toByteArray();
    }

    @Test
    void anAbsurdFrameLengthEndsTheMuxInsteadOfAllocating() throws Exception {
        // 2 GiB claimed, nothing behind it — the shape a desynchronised stream produces.
        StreamMux mux = new StreamMux(pipeOf(frame(2, 1, Integer.MAX_VALUE)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            mux.start();
            mux.awaitClosed();
        }, "a corrupt frame length must end the reader, not allocate or block on it");

        mux.close();
    }

    @Test
    void aNegativeFrameLengthEndsTheMux() throws Exception {
        StreamMux mux = new StreamMux(pipeOf(frame(2, 1, -12345)));

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            mux.start();
            mux.awaitClosed();
        });

        mux.close();
    }

    /**
     * Awaiting a mux that was never started must not report that the session has ended. Returning
     * quietly would tell a host loop its peer had gone the instant it asked — the same null-thread
     * shape that made a death-watcher fire immediately when started before the router.
     */
    @Test
    void awaitingAMuxThatWasNeverStartedIsARejectedCallerError() {
        StreamMux mux = new StreamMux(pipeOf(new byte[0]));

        assertThrows(IllegalStateException.class, mux::awaitClosed,
                "awaitClosed() on an unstarted mux must not pretend the session ended");

        mux.close();
    }
}

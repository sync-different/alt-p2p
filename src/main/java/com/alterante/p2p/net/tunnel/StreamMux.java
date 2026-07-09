package com.alterante.p2p.net.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Multiplexes many logical byte streams over a single {@link BytePipe}. Needed
 * because a real {@code lore} operation opens several concurrent TCP connections
 * (≥2 even with {@code --max-connections 1}), while alt-p2p gives one pipe per
 * session.
 *
 * <p>Wire frame: {@code type(1) | streamId(4 BE) | length(4 BE) | payload[length]}.
 * Types: OPEN, DATA, CLOSE. Only the initiator side calls {@link #open()} (stream
 * ids are initiator-assigned and monotonic); the acceptor side handles inbound
 * streams via {@link #onStream}. The underlying pipe is already reliable and
 * ordered, so the mux only frames and demultiplexes — no reliability of its own.
 *
 * <p>M1 has no per-stream flow control: a full carrier send window blocks the
 * single frame writer, which blocks all streams (head-of-line blocking, accepted —
 * identical to gRPC over one HTTP/2 connection).
 */
public class StreamMux implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StreamMux.class);

    private static final int OPEN = 1;
    private static final int DATA = 2;
    private static final int CLOSE = 3;

    /** Max DATA payload per frame — bounds latency and interleaves streams fairly. */
    static final int MAX_FRAME = 16384;

    private final BytePipe pipe;
    private final DataInputStream din;
    private final DataOutputStream dout;
    private final Object writeLock = new Object();
    private final Map<Integer, MuxStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile Consumer<MuxStream> onStream;
    private volatile boolean closed;
    private Thread reader;

    public StreamMux(BytePipe pipe) {
        this.pipe = pipe;
        this.din = new DataInputStream(pipe.in());
        this.dout = new DataOutputStream(pipe.out());
    }

    /** Register a handler for inbound streams opened by the remote peer. */
    public void onStream(Consumer<MuxStream> handler) {
        this.onStream = handler;
    }

    /** Start the frame reader. Call once, after {@link #onStream} is set on the acceptor. */
    public void start() {
        reader = new Thread(this::readLoop, "mux-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Block until the reader ends — i.e. the underlying pipe hit EOF (the peer went
     * away) or the mux was closed. The carrier-agnostic "session ended" signal:
     * works for the relay pipe (TCP EOF) where the direct path's router keepalive
     * detection does not apply.
     */
    public void awaitClosed() throws InterruptedException {
        Thread r = reader;
        if (r != null) r.join();
    }

    /** Open a new outbound logical stream (initiator side). */
    public MuxStream open() throws IOException {
        int id = nextId.getAndIncrement();
        MuxStream s = new MuxStream(id);
        streams.put(id, s);
        writeFrame(OPEN, id, null, 0, 0);
        return s;
    }

    @Override
    public void close() {
        closed = true;
        for (MuxStream s : streams.values()) s.localCloseQuiet();
        streams.clear();
        pipe.close();
    }

    private void writeFrame(int type, int id, byte[] b, int off, int len) throws IOException {
        synchronized (writeLock) {
            dout.writeByte(type);
            dout.writeInt(id);
            dout.writeInt(len);
            if (len > 0) dout.write(b, off, len);
            dout.flush();
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                int type = din.read();
                if (type < 0) break; // pipe EOF
                int id = din.readInt();
                int len = din.readInt();
                byte[] payload = null;
                if (len > 0) {
                    payload = new byte[len];
                    din.readFully(payload);
                }
                switch (type) {
                    case OPEN -> {
                        MuxStream s = new MuxStream(id);
                        streams.put(id, s);
                        Consumer<MuxStream> h = onStream;
                        if (h != null) h.accept(s);
                        else s.localCloseQuiet();
                    }
                    case DATA -> {
                        MuxStream s = streams.get(id);
                        if (s != null && payload != null) s.deliver(payload);
                    }
                    case CLOSE -> {
                        MuxStream s = streams.remove(id);
                        if (s != null) s.remoteClosed();
                    }
                    default -> log.debug("mux: unknown frame type {}", type);
                }
            }
        } catch (IOException e) {
            if (!closed) log.debug("mux reader ended: {}", e.getMessage());
        } finally {
            for (MuxStream s : streams.values()) s.remoteClosed();
        }
    }

    /** One logical stream over the mux; presents blocking {@code in()}/{@code out()}. */
    public final class MuxStream {
        private static final byte[] EOF = new byte[0];
        private final int id;
        private final BlockingQueue<byte[]> inQ = new LinkedBlockingQueue<>();
        private volatile boolean closed;
        private final In in = new In();
        private final Out out = new Out();

        MuxStream(int id) { this.id = id; }

        public int id() { return id; }
        public InputStream in() { return in; }
        public OutputStream out() { return out; }

        void deliver(byte[] b) { inQ.offer(b); }
        void remoteClosed() { inQ.offer(EOF); }

        /** Close this stream and notify the peer. */
        public void close() {
            if (closed) return;
            closed = true;
            streams.remove(id);
            try { writeFrame(CLOSE, id, null, 0, 0); } catch (IOException ignored) {}
            inQ.offer(EOF);
        }

        /** Close without sending a CLOSE frame (used during mux teardown). */
        void localCloseQuiet() {
            closed = true;
            inQ.offer(EOF);
        }

        private final class Out extends OutputStream {
            @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
            @Override public void write(byte[] b, int off, int len) throws IOException {
                if (closed) throw new IOException("stream closed");
                int end = off + len;
                while (off < end) {
                    int n = Math.min(MAX_FRAME, end - off);
                    writeFrame(DATA, id, b, off, n);
                    off += n;
                }
            }
        }

        private final class In extends InputStream {
            private byte[] cur;
            private int pos;

            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : (one[0] & 0xFF);
            }

            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (!ensureCurrent()) return -1;
                int n = Math.min(len, cur.length - pos);
                System.arraycopy(cur, pos, b, off, n);
                pos += n;
                if (pos >= cur.length) cur = null;
                return n;
            }

            private boolean ensureCurrent() throws IOException {
                while (cur == null || pos >= cur.length) {
                    byte[] next;
                    try {
                        next = inQ.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("interrupted while reading");
                    }
                    if (next == EOF) return false;
                    cur = next;
                    pos = 0;
                }
                return true;
            }
        }
    }
}

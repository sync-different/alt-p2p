package com.alterante.p2p.net.tunnel;

import com.alterante.p2p.transport.ReliableChannel;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * {@link BytePipe} over the direct UDP path's {@link ReliableChannel} — the primary
 * carrier. Presents the channel's ordered, reliable, congestion-controlled DATA
 * delivery as a plain byte stream: {@code out()} splits writes into DATA packets
 * (blocking on the send window for backpressure), {@code in()} concatenates the
 * in-order {@code onDataReceived} payloads. The file-oriented {@code chunkIndex} /
 * {@code byteOffset} DATA fields are unused here — we only need ordered bytes.
 *
 * <p>Full-duplex over one channel is validated by {@code FullDuplexSpikeTest}.
 * The owner creates the {@link ReliableChannel} (and starts the router); this pipe
 * does not close the channel.
 */
public class DirectBytePipe implements BytePipe {

    private static final byte[] EOF = new byte[0];

    private final ReliableChannel channel;
    private final BlockingQueue<byte[]> recvQueue = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private final Out out = new Out();
    private final In in = new In();

    public DirectBytePipe(ReliableChannel channel) {
        this.channel = channel;
        // Ordered delivery: onDataReceived fires on the router thread in sequence order.
        channel.onDataReceived(dp -> {
            byte[] d = dp.data();
            if (d != null && d.length > 0) recvQueue.offer(d);
        });
    }

    @Override public InputStream in() { return in; }
    @Override public OutputStream out() { return out; }

    @Override
    public void close() {
        closed = true;
        recvQueue.offer(EOF); // wake a blocked reader
    }

    /** Splits writes into DATA packets; sendData() blocks when the send window is full. */
    private final class Out extends OutputStream {
        private long offset;
        private int chunkIndex;

        @Override public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new IOException("pipe closed");
            final int max = channel.maxChunkData();
            final int end = off + len;
            while (off < end) {
                int n = Math.min(max, end - off);
                byte[] chunk = new byte[n];
                System.arraycopy(b, off, chunk, 0, n);
                try {
                    channel.sendData(chunkIndex++, offset, chunk);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("interrupted while sending");
                }
                offset += n;
                off += n;
            }
        }
    }

    /** Blocking reader fed by onDataReceived; concatenates in-order payloads. */
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
                    next = recvQueue.take();
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

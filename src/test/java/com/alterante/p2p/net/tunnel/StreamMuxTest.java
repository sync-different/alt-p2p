package com.alterante.p2p.net.tunnel;

import com.alterante.p2p.net.DtlsHandler;
import com.alterante.p2p.net.PacketRouter;
import com.alterante.p2p.transport.ReliableChannel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2: {@link StreamMux} — many concurrent logical streams over ONE loopback
 * {@link BytePipe}. The acceptor echoes each stream; the initiator opens N streams,
 * writes a per-stream payload, and verifies the echo byte-for-byte. Exercises frame
 * demux, concurrent interleaving, and clean per-stream close.
 */
class StreamMuxTest {

    private static final String PSK = "test-psk";
    private static final String SESSION = "mux-test";
    private static final int STREAMS = 8;
    private static final int PER_STREAM = 64 * 1024;

    private static byte[] pattern(int streamId, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((streamId * 131 + i * 31) & 0xFF);
        return b;
    }

    @Test
    void manyStreamsOverOnePipe() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            ExecutorService exec = Executors.newCachedThreadPool();
            try {
                Future<?> hA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> hB = exec.submit(() -> { dtlsB.handshake(); return null; });
                hA.get(10, TimeUnit.SECONDS);
                hB.get(10, TimeUnit.SECONDS);

                PacketRouter routerA = new PacketRouter(dtlsA);
                PacketRouter routerB = new PacketRouter(dtlsB);
                ReliableChannel channelA = new ReliableChannel(routerA, 0xCAFE);
                ReliableChannel channelB = new ReliableChannel(routerB, 0xBEEF);
                BytePipe pipeA = new DirectBytePipe(channelA);
                BytePipe pipeB = new DirectBytePipe(channelB);
                routerA.start();
                routerB.start();

                StreamMux muxA = new StreamMux(pipeA); // initiator
                StreamMux muxB = new StreamMux(pipeB); // acceptor
                AtomicReference<String> err = new AtomicReference<>(null);

                // Acceptor: echo each inbound stream (streaming copy until EOF), then close.
                muxB.onStream(s -> exec.submit(() -> {
                    try (InputStream in = s.in(); OutputStream out = s.out()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    } catch (Exception e) {
                        err.compareAndSet(null, "echo: " + e);
                    } finally {
                        s.close();
                    }
                }));

                muxB.start();
                muxA.start();

                try {
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int k = 0; k < STREAMS; k++) {
                        StreamMux.MuxStream s = muxA.open();
                        byte[] payload = pattern(s.id(), PER_STREAM);
                        tasks.add(exec.submit(() -> {
                            // Write on a sub-thread while reading the echo on this thread.
                            Future<?> writer = exec.submit(() -> {
                                s.out().write(payload);
                                s.out().flush();
                                return null;
                            });
                            byte[] got = new byte[PER_STREAM];
                            int off = 0;
                            while (off < got.length) {
                                int n = s.in().read(got, off, got.length - off);
                                if (n < 0) { err.compareAndSet(null, "stream " + s.id() + ": premature EOF at " + off); break; }
                                off += n;
                            }
                            writer.get(20, TimeUnit.SECONDS);
                            for (int i = 0; i < PER_STREAM; i++) {
                                if (got[i] != payload[i]) { err.compareAndSet(null, "stream " + s.id() + ": mismatch at " + i); break; }
                            }
                            s.close();
                            return null;
                        }));
                    }
                    for (Future<?> t : tasks) t.get(30, TimeUnit.SECONDS);
                    assertNull(err.get(), err.get());
                } finally {
                    muxA.close();
                    muxB.close();
                    channelA.close();
                    channelB.close();
                    routerA.stop();
                    routerB.stop();
                }
            } finally {
                exec.shutdownNow();
                dtlsA.close();
                dtlsB.close();
            }
        }
    }
}

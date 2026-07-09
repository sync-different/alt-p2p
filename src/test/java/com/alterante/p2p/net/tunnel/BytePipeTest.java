package com.alterante.p2p.net.tunnel;

import com.alterante.p2p.net.DtlsHandler;
import com.alterante.p2p.net.PacketRouter;
import com.alterante.p2p.transport.ReliableChannel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S1: {@link DirectBytePipe} over the direct UDP path — bulk, full-duplex byte
 * streaming through the pipe abstraction on two real {@link ReliableChannel}s
 * (loopback DTLS). Proves the ordered byte-stream adapter (chunked writes +
 * in-order reassembly) both directions with byte-exact integrity.
 */
class BytePipeTest {

    private static final String PSK = "test-psk";
    private static final String SESSION = "bytepipe-test";
    private static final int SIZE = 1_000_000; // 1 MB each direction

    private static byte[] pattern(int side, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((side * 131 + i * 31) & 0xFF);
        return b;
    }

    @Test
    void directPipeFullDuplexBulk() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            ExecutorService exec = Executors.newFixedThreadPool(6);
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

                try {
                    byte[] aToB = pattern(0, SIZE);
                    byte[] bToA = pattern(1, SIZE);
                    AtomicReference<String> err = new AtomicReference<>(null);

                    Future<?> wA = exec.submit(() -> { pipeA.out().write(aToB); pipeA.out().flush(); return null; });
                    Future<?> wB = exec.submit(() -> { pipeB.out().write(bToA); pipeB.out().flush(); return null; });
                    Future<?> rB = exec.submit(() -> { verify(pipeB.in(), aToB, "A->B", err); return null; });
                    Future<?> rA = exec.submit(() -> { verify(pipeA.in(), bToA, "B->A", err); return null; });

                    wA.get(30, TimeUnit.SECONDS);
                    wB.get(30, TimeUnit.SECONDS);
                    rB.get(30, TimeUnit.SECONDS);
                    rA.get(30, TimeUnit.SECONDS);

                    assertNull(err.get(), err.get());
                } finally {
                    pipeA.close();
                    pipeB.close();
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

    /** Read exactly expected.length bytes and compare byte-for-byte. */
    private static void verify(InputStream in, byte[] expected, String dir, AtomicReference<String> err) {
        try {
            byte[] got = new byte[expected.length];
            int off = 0;
            while (off < got.length) {
                int n = in.read(got, off, got.length - off);
                if (n < 0) { err.compareAndSet(null, dir + ": premature EOF at " + off + "/" + got.length); return; }
                off += n;
            }
            for (int i = 0; i < expected.length; i++) {
                if (got[i] != expected[i]) { err.compareAndSet(null, dir + ": mismatch at byte " + i); return; }
            }
        } catch (Exception e) {
            err.compareAndSet(null, dir + ": " + e);
        }
    }
}

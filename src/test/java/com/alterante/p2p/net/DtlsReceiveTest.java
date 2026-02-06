package com.alterante.p2p.net;

import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal test: DTLS handshake then repeated short-timeout receives.
 */
class DtlsReceiveTest {

    @Test
    void receiveWithShortTimeout() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, "test", "psk123", true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, "test", "psk123", false);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                Future<?> fA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> fB = exec.submit(() -> { dtlsB.handshake(); return null; });
                fA.get(10, TimeUnit.SECONDS);
                fB.get(10, TimeUnit.SECONDS);

                System.err.println("Handshake complete");

                // Try server first (B) to see if it's always B
                byte[] buf = new byte[1200];
                System.err.println("Attempting receive with 50ms timeout on B first...");
                int n = dtlsB.receive(buf, 0, buf.length, 50);
                System.err.println("B receive result: " + n);

                System.err.println("Attempting receive with 50ms timeout on A...");
                n = dtlsA.receive(buf, 0, buf.length, 50);
                System.err.println("A receive result: " + n);

                // Now send and receive
                byte[] msg = "Hello".getBytes(StandardCharsets.UTF_8);
                dtlsA.send(msg, 0, msg.length);
                n = dtlsB.receive(buf, 0, buf.length, 5000);
                assertTrue(n > 0);
                assertEquals("Hello", new String(buf, 0, n, StandardCharsets.UTF_8));

            } finally {
                exec.shutdownNow();
                dtlsA.close();
                dtlsB.close();
            }
        }
    }

    @Test
    void receiveWithShortTimeoutAfterDelay() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, "test2", "psk456", true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, "test2", "psk456", false);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                Future<?> fA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> fB = exec.submit(() -> { dtlsB.handshake(); return null; });
                fA.get(10, TimeUnit.SECONDS);
                fB.get(10, TimeUnit.SECONDS);

                System.err.println("Handshake complete, waiting 500ms...");
                Thread.sleep(500);

                // Try 50ms receive after delay
                byte[] buf = new byte[1200];
                System.err.println("Attempting receive with 50ms timeout on A...");
                int n = dtlsA.receive(buf, 0, buf.length, 50);
                System.err.println("A receive result: " + n);

                System.err.println("Attempting receive with 50ms timeout on B...");
                n = dtlsB.receive(buf, 0, buf.length, 50);
                System.err.println("B receive result: " + n);

                // Now send and receive
                byte[] msg = "Hello".getBytes(StandardCharsets.UTF_8);
                dtlsA.send(msg, 0, msg.length);
                n = dtlsB.receive(buf, 0, buf.length, 5000);
                assertTrue(n > 0);
                assertEquals("Hello", new String(buf, 0, n, StandardCharsets.UTF_8));

            } finally {
                exec.shutdownNow();
                dtlsA.close();
                dtlsB.close();
            }
        }
    }
}

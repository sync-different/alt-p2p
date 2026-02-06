package com.alterante.p2p.net;

import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DtlsHandler — DTLS 1.2 PSK handshake and encrypted data exchange.
 */
class DtlsHandlerTest {

    private static final String PSK = "dtls-test-secret";
    private static final String SESSION_ID = "dtls-session";

    @Test
    void handshakeAndExchangeData() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            // A = client, B = server
            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION_ID, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION_ID, PSK, false);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                // Handshake must happen concurrently
                Future<?> futA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> futB = exec.submit(() -> { dtlsB.handshake(); return null; });

                futA.get(10, TimeUnit.SECONDS);
                futB.get(10, TimeUnit.SECONDS);

                assertNotNull(dtlsA.transport(), "Client should have DTLS transport");
                assertNotNull(dtlsB.transport(), "Server should have DTLS transport");

                // Send data A → B
                byte[] message = "Hello encrypted world!".getBytes(StandardCharsets.UTF_8);
                dtlsA.send(message, 0, message.length);

                byte[] recvBuf = new byte[1024];
                int received = dtlsB.receive(recvBuf, 0, recvBuf.length, 5000);
                assertTrue(received > 0, "B should receive data");
                assertEquals("Hello encrypted world!",
                        new String(recvBuf, 0, received, StandardCharsets.UTF_8));

                // Send data B → A
                byte[] reply = "Encrypted reply!".getBytes(StandardCharsets.UTF_8);
                dtlsB.send(reply, 0, reply.length);

                received = dtlsA.receive(recvBuf, 0, recvBuf.length, 5000);
                assertTrue(received > 0, "A should receive reply");
                assertEquals("Encrypted reply!",
                        new String(recvBuf, 0, received, StandardCharsets.UTF_8));

            } finally {
                exec.shutdownNow();
                dtlsA.close();
                dtlsB.close();
            }
        }
    }

    @Test
    void fullFlowEndToEnd() throws Exception {
        String psk = "e2e-dtls-secret";
        String session = "e2e-dtls-session";
        int serverPort;

        try (DatagramSocket probe = new DatagramSocket(0)) {
            serverPort = probe.getLocalPort();
        }

        CoordServer server = new CoordServer(serverPort, psk, 60);
        Thread serverThread = new Thread(() -> {
            try { server.start(); } catch (Exception e) {
                if (server.isRunning()) throw new RuntimeException(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(300);

        try {
            InetSocketAddress serverAddr = new InetSocketAddress("127.0.0.1", serverPort);
            PeerConnection peerA = new PeerConnection(serverAddr, session, psk);
            PeerConnection peerB = new PeerConnection(serverAddr, session, psk);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                Future<?> futA = exec.submit(() -> { peerA.connect(); return null; });
                Thread.sleep(200);
                Future<?> futB = exec.submit(() -> { peerB.connect(); return null; });

                futA.get(15, TimeUnit.SECONDS);
                futB.get(15, TimeUnit.SECONDS);

                assertEquals(PeerState.CONNECTED, peerA.state());
                assertEquals(PeerState.CONNECTED, peerB.state());

                // Verify DTLS handlers and routers are established
                assertNotNull(peerA.dtls(), "Peer A should have DTLS handler");
                assertNotNull(peerB.dtls(), "Peer B should have DTLS handler");
                assertNotNull(peerA.router(), "Peer A should have PacketRouter");
                assertNotNull(peerB.router(), "Peer B should have PacketRouter");
                assertTrue(peerA.router().isRunning(), "Router A should be running");
                assertTrue(peerB.router().isRunning(), "Router B should be running");

            } finally {
                exec.shutdownNow();
                peerA.close();
                peerB.close();
            }
        } finally {
            server.stop();
            serverThread.join(3000);
        }
    }
}

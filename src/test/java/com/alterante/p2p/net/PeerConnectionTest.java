package com.alterante.p2p.net;

import org.junit.jupiter.api.*;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: two PeerConnections coordinate through a real CoordServer.
 */
class PeerConnectionTest {

    private static final String PSK = "integration-secret";
    private static final String SESSION = "peer-conn-test";

    private CoordServer server;
    private Thread serverThread;
    private int serverPort;

    @BeforeEach
    void setUp() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            serverPort = probe.getLocalPort();
        }
        server = new CoordServer(serverPort, PSK, 60);
        serverThread = new Thread(() -> {
            try { server.start(); } catch (Exception e) {
                if (server.isRunning()) throw new RuntimeException(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        serverThread.join(3000);
    }

    @Test
    void twoPeersConnectThroughServer() throws Exception {
        InetSocketAddress serverAddr = new InetSocketAddress("127.0.0.1", serverPort);

        PeerConnection peerA = new PeerConnection(serverAddr, SESSION, PSK);
        PeerConnection peerB = new PeerConnection(serverAddr, SESSION, PSK);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<?> futA = exec.submit(() -> { peerA.connect(); return null; });
            // Small delay so peer A registers first (avoids race in test)
            Thread.sleep(100);
            Future<?> futB = exec.submit(() -> { peerB.connect(); return null; });

            // Both should complete within 10 seconds
            futA.get(10, TimeUnit.SECONDS);
            futB.get(10, TimeUnit.SECONDS);

            assertEquals(PeerState.CONNECTED, peerA.state(), "Peer A should be CONNECTED");
            assertEquals(PeerState.CONNECTED, peerB.state(), "Peer B should be CONNECTED");

            // Each should know the other's endpoint
            assertNotNull(peerA.remoteEndpoint(), "Peer A should have remote endpoint");
            assertNotNull(peerB.remoteEndpoint(), "Peer B should have remote endpoint");

            // The remote endpoints should reference the other peer's socket port
            assertEquals(peerB.socket().getLocalPort(), peerA.remoteEndpoint().getPort(),
                    "Peer A's remote endpoint should be peer B's port");
            assertEquals(peerA.socket().getLocalPort(), peerB.remoteEndpoint().getPort(),
                    "Peer B's remote endpoint should be peer A's port");
        } finally {
            exec.shutdownNow();
            peerA.close();
            peerB.close();
        }
    }

    @Test
    void wrongPskFailsGracefully() throws Exception {
        InetSocketAddress serverAddr = new InetSocketAddress("127.0.0.1", serverPort);
        PeerConnection peer = new PeerConnection(serverAddr, SESSION, "wrong-psk");

        try {
            // Need a second peer to fill the session? No — it should fail at AUTH step.
            // But we need to register first. The server will give a challenge, and
            // the client will compute HMAC with wrong PSK → server sends ERROR.
            peer.connect();
            fail("Should have thrown");
        } catch (Exception e) {
            assertEquals(PeerState.ERROR, peer.state());
            assertTrue(e.getMessage().contains("Authentication failed")
                    || e.getCause().getMessage().contains("Authentication failed"),
                    "Should mention auth failure, got: " + e.getMessage());
        } finally {
            peer.close();
        }
    }
}

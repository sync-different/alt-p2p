package com.alterante.p2p.net;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketCodec;
import com.alterante.p2p.protocol.PacketType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for HolePuncher — two sockets on localhost punch through to each other.
 */
class HolePuncherTest {

    @Test
    void twoPeersPunchSuccessfully() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            int connId = 0xCAFE;

            HolePuncher puncherA = new HolePuncher(socketA, addrB, connId, 50, 5000);
            HolePuncher puncherB = new HolePuncher(socketB, addrA, connId, 50, 5000);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                Future<HolePunchResult> futA = exec.submit(puncherA::punch);
                Future<HolePunchResult> futB = exec.submit(puncherB::punch);

                HolePunchResult resultA = futA.get(10, TimeUnit.SECONDS);
                HolePunchResult resultB = futB.get(10, TimeUnit.SECONDS);

                assertTrue(resultA.success(), "Peer A hole punch should succeed");
                assertTrue(resultB.success(), "Peer B hole punch should succeed");

                assertNotNull(resultA.confirmedAddress());
                assertNotNull(resultB.confirmedAddress());

                assertEquals(socketB.getLocalPort(), resultA.confirmedAddress().getPort(),
                        "Peer A should confirm peer B's port");
                assertEquals(socketA.getLocalPort(), resultB.confirmedAddress().getPort(),
                        "Peer B should confirm peer A's port");

                assertTrue(resultA.elapsedMs() < 5000, "Should complete well before timeout");
                assertTrue(resultB.elapsedMs() < 5000, "Should complete well before timeout");
            } finally {
                exec.shutdownNow();
            }
        }
    }

    @Test
    void timeoutWhenNoPeer() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket blackHole = new DatagramSocket()) {
            // Point at blackHole's port — it exists but nobody reads from it,
            // so PUNCH packets are silently dropped.
            InetSocketAddress target = new InetSocketAddress("127.0.0.1", blackHole.getLocalPort());

            HolePuncher puncher = new HolePuncher(socketA, target, 0x1234, 100, 500);
            HolePunchResult result = puncher.punch();

            assertFalse(result.success(), "Should fail when no peer responds");
            assertTrue(result.elapsedMs() >= 400, "Should have waited near the timeout");
        }
    }

    @Test
    void adoptsPeerFromUnexpectedSourceIp() throws Exception {
        // A multi-homed/hairpin peer can send from a different IP than the coord
        // reported. The puncher must accept the validated PUNCH and adopt that source.
        // Requires the 127.0.0.2 loopback alias (present on Linux; skipped otherwise).
        DatagramSocket peerSocket;
        try {
            peerSocket = new DatagramSocket(new InetSocketAddress("127.0.0.2", 0));
        } catch (Exception e) {
            Assumptions.abort("127.0.0.2 loopback alias not available: " + e.getMessage());
            return;
        }
        try (DatagramSocket punchSocket = new DatagramSocket();
             DatagramSocket deadHole = new DatagramSocket()) {
            // Configure the puncher to expect a DIFFERENT address (127.0.0.1 dead port)
            // than the actual sender (127.0.0.2 peerSocket).
            InetSocketAddress wrongRemote = new InetSocketAddress("127.0.0.1", deadHole.getLocalPort());
            HolePuncher puncher = new HolePuncher(punchSocket, wrongRemote, 0xABCD, 100, 3000);

            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<HolePunchResult> fut = exec.submit(puncher::punch);

                byte[] data = PacketCodec.encode(new Packet(PacketType.PUNCH, (byte) 0, 0x1111, 0, null));
                InetSocketAddress dst = new InetSocketAddress("127.0.0.1", punchSocket.getLocalPort());
                for (int i = 0; i < 15 && !fut.isDone(); i++) {
                    peerSocket.send(new DatagramPacket(data, data.length, dst.getAddress(), dst.getPort()));
                    Thread.sleep(100);
                }

                HolePunchResult result = fut.get(5, TimeUnit.SECONDS);
                assertTrue(result.success(), "should accept PUNCH from an unexpected source IP");
                assertEquals("127.0.0.2", result.confirmedAddress().getAddress().getHostAddress());
                assertEquals(peerSocket.getLocalPort(), result.confirmedAddress().getPort());
            } finally {
                exec.shutdownNow();
            }
        } finally {
            peerSocket.close();
        }
    }

    @Test
    void punchesMultipleCandidatesAndUsesResponder() throws Exception {
        // Puncher is given a dead candidate + a live one (simulating public + LAN);
        // it should send to both and succeed via whichever responds.
        try (DatagramSocket punchSocket = new DatagramSocket();
             DatagramSocket deadHole = new DatagramSocket();
             DatagramSocket peerSocket = new DatagramSocket()) {

            InetSocketAddress dead = new InetSocketAddress("127.0.0.1", deadHole.getLocalPort());
            InetSocketAddress live = new InetSocketAddress("127.0.0.1", peerSocket.getLocalPort());

            HolePuncher puncher = new HolePuncher(punchSocket, java.util.List.of(dead, live), 0xBEEF, 100, 3000);
            ExecutorService exec = Executors.newSingleThreadExecutor();
            try {
                Future<HolePunchResult> fut = exec.submit(puncher::punch);

                // The live peer receives a PUNCH and replies with its own PUNCH.
                peerSocket.setSoTimeout(3000);
                byte[] buf = new byte[2048];
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                peerSocket.receive(in);
                byte[] reply = PacketCodec.encode(new Packet(PacketType.PUNCH, (byte) 0, 0x2222, 0, null));
                peerSocket.send(new DatagramPacket(reply, reply.length, in.getAddress(), in.getPort()));

                HolePunchResult r = fut.get(5, TimeUnit.SECONDS);
                assertTrue(r.success(), "should succeed via the responding candidate");
                assertEquals(peerSocket.getLocalPort(), r.confirmedAddress().getPort());
            } finally {
                exec.shutdownNow();
            }
        }
    }

    @Test
    void fullFlowWithCoordServer() throws Exception {
        String psk = "punch-test-secret";
        String session = "punch-session";
        int serverPort;

        // Find a free port
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
                assertNotNull(peerA.remoteEndpoint());
                assertNotNull(peerB.remoteEndpoint());
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

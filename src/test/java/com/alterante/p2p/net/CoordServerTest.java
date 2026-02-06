package com.alterante.p2p.net;

import com.alterante.p2p.protocol.*;
import org.junit.jupiter.api.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for CoordServer.
 * Exercises the full coordination flow: REGISTER → CHALLENGE → AUTH → OK → PEER_INFO.
 */
class CoordServerTest {

    private static final String PSK = "test-secret";
    private static final String SESSION_ID = "session-42";
    private static final int TIMEOUT_MS = 2000;

    private CoordServer server;
    private Thread serverThread;
    private int serverPort;

    @BeforeEach
    void setUp() throws Exception {
        // Use port 0 to let the OS assign a free port
        // We need a small workaround: CoordServer binds to a fixed port,
        // so we pick a random free port first.
        try (DatagramSocket probe = new DatagramSocket(0)) {
            serverPort = probe.getLocalPort();
        }

        server = new CoordServer(serverPort, PSK, 60);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                if (server.isRunning()) {
                    throw new RuntimeException(e);
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Give server time to bind
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        serverThread.join(3000);
    }

    @Test
    void fullFlowTwoPeersReceivePeerInfo() throws Exception {
        try (DatagramSocket peerA = new DatagramSocket();
             DatagramSocket peerB = new DatagramSocket()) {

            peerA.setSoTimeout(TIMEOUT_MS);
            peerB.setSoTimeout(TIMEOUT_MS);

            InetAddress localhost = InetAddress.getLoopbackAddress();

            // --- Peer A: REGISTER ---
            sendRegister(peerA, localhost, serverPort, SESSION_ID);
            Packet challengeA = receivePacket(peerA);
            assertEquals(PacketType.COORD_CHALLENGE, challengeA.type(), "Peer A should get CHALLENGE");
            assertEquals(32, challengeA.payloadLength(), "Nonce should be 32 bytes");
            byte[] nonceA = challengeA.payload();

            // --- Peer B: REGISTER ---
            sendRegister(peerB, localhost, serverPort, SESSION_ID);
            Packet challengeB = receivePacket(peerB);
            assertEquals(PacketType.COORD_CHALLENGE, challengeB.type(), "Peer B should get CHALLENGE");
            assertEquals(32, challengeB.payloadLength(), "Nonce should be 32 bytes");
            byte[] nonceB = challengeB.payload();

            // --- Peer A: AUTH ---
            byte[] hmacA = CoordServer.computeHmac(PSK, nonceA, SESSION_ID);
            sendAuth(peerA, localhost, serverPort, SESSION_ID, hmacA);
            Packet okA = receivePacket(peerA);
            assertEquals(PacketType.COORD_OK, okA.type(), "Peer A should get OK");

            // Verify OK contains peer A's own endpoint
            InetSocketAddress okEndpointA = CoordServer.decodeEndpoint(okA.payload(), 0);
            assertEquals(peerA.getLocalPort(), okEndpointA.getPort(),
                    "OK should reflect peer A's port");

            // --- Peer B: AUTH ---
            byte[] hmacB = CoordServer.computeHmac(PSK, nonceB, SESSION_ID);
            sendAuth(peerB, localhost, serverPort, SESSION_ID, hmacB);
            Packet okB = receivePacket(peerB);
            assertEquals(PacketType.COORD_OK, okB.type(), "Peer B should get OK");

            // After Peer B authenticates, both should receive PEER_INFO
            Packet peerInfoB = receivePacket(peerB);
            assertEquals(PacketType.COORD_PEER_INFO, peerInfoB.type(),
                    "Peer B should get PEER_INFO");

            Packet peerInfoA = receivePacket(peerA);
            assertEquals(PacketType.COORD_PEER_INFO, peerInfoA.type(),
                    "Peer A should get PEER_INFO");

            // Verify PEER_INFO payloads: A gets B's endpoint, B gets A's endpoint
            InetSocketAddress infoForA = CoordServer.decodeEndpoint(peerInfoA.payload(), 0);
            InetSocketAddress infoForB = CoordServer.decodeEndpoint(peerInfoB.payload(), 0);

            assertEquals(peerB.getLocalPort(), infoForA.getPort(),
                    "Peer A's PEER_INFO should contain peer B's port");
            assertEquals(peerA.getLocalPort(), infoForB.getPort(),
                    "Peer B's PEER_INFO should contain peer A's port");
        }
    }

    @Test
    void wrongPskRejected() throws Exception {
        try (DatagramSocket peer = new DatagramSocket()) {
            peer.setSoTimeout(TIMEOUT_MS);
            InetAddress localhost = InetAddress.getLoopbackAddress();

            // REGISTER
            sendRegister(peer, localhost, serverPort, SESSION_ID);
            Packet challenge = receivePacket(peer);
            assertEquals(PacketType.COORD_CHALLENGE, challenge.type());

            // AUTH with wrong PSK
            byte[] badHmac = CoordServer.computeHmac("wrong-psk", challenge.payload(), SESSION_ID);
            sendAuth(peer, localhost, serverPort, SESSION_ID, badHmac);
            Packet error = receivePacket(peer);
            assertEquals(PacketType.COORD_ERROR, error.type(), "Wrong PSK should get ERROR");
        }
    }

    @Test
    void sessionFullRejected() throws Exception {
        try (DatagramSocket peerA = new DatagramSocket();
             DatagramSocket peerB = new DatagramSocket();
             DatagramSocket peerC = new DatagramSocket()) {

            peerA.setSoTimeout(TIMEOUT_MS);
            peerB.setSoTimeout(TIMEOUT_MS);
            peerC.setSoTimeout(TIMEOUT_MS);

            InetAddress localhost = InetAddress.getLoopbackAddress();

            // Peer A registers
            sendRegister(peerA, localhost, serverPort, SESSION_ID);
            Packet chA = receivePacket(peerA);
            assertEquals(PacketType.COORD_CHALLENGE, chA.type());

            // Peer B registers
            sendRegister(peerB, localhost, serverPort, SESSION_ID);
            Packet chB = receivePacket(peerB);
            assertEquals(PacketType.COORD_CHALLENGE, chB.type());

            // Peer C tries to register — session is full
            sendRegister(peerC, localhost, serverPort, SESSION_ID);
            Packet errorC = receivePacket(peerC);
            assertEquals(PacketType.COORD_ERROR, errorC.type(), "Third peer should get ERROR (session full)");
        }
    }

    @Test
    void reRegistrationSendsChallenge() throws Exception {
        try (DatagramSocket peer = new DatagramSocket()) {
            peer.setSoTimeout(TIMEOUT_MS);
            InetAddress localhost = InetAddress.getLoopbackAddress();

            // First REGISTER
            sendRegister(peer, localhost, serverPort, SESSION_ID);
            Packet ch1 = receivePacket(peer);
            assertEquals(PacketType.COORD_CHALLENGE, ch1.type());

            // Re-REGISTER from same endpoint
            sendRegister(peer, localhost, serverPort, SESSION_ID);
            Packet ch2 = receivePacket(peer);
            assertEquals(PacketType.COORD_CHALLENGE, ch2.type(), "Re-registration should send CHALLENGE again");

            // Nonces should be the same (same slot)
            assertArrayEquals(ch1.payload(), ch2.payload(), "Same slot → same nonce");
        }
    }

    @Test
    void pingPong() throws Exception {
        try (DatagramSocket peer = new DatagramSocket()) {
            peer.setSoTimeout(TIMEOUT_MS);
            InetAddress localhost = InetAddress.getLoopbackAddress();

            Packet ping = new Packet(PacketType.COORD_PING);
            sendPacket(peer, localhost, serverPort, ping);

            Packet pong = receivePacket(peer);
            assertEquals(PacketType.COORD_PONG, pong.type(), "Server should reply with PONG");
        }
    }

    // --- Helper methods ---

    private void sendRegister(DatagramSocket socket, InetAddress host, int port, String sessionId) throws Exception {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes);

        Packet packet = new Packet(PacketType.COORD_REGISTER, payload);
        sendPacket(socket, host, port, packet);
    }

    private void sendAuth(DatagramSocket socket, InetAddress host, int port,
                          String sessionId, byte[] hmac) throws Exception {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length + 32];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes)
                .put(hmac);

        Packet packet = new Packet(PacketType.COORD_AUTH, payload);
        sendPacket(socket, host, port, packet);
    }

    private void sendPacket(DatagramSocket socket, InetAddress host, int port, Packet packet) throws Exception {
        byte[] data = PacketCodec.encode(packet);
        socket.send(new DatagramPacket(data, data.length, host, port));
    }

    private Packet receivePacket(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[Packet.MAX_DATAGRAM];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);
        socket.receive(dgram);
        return PacketCodec.decode(buf, dgram.getLength());
    }
}

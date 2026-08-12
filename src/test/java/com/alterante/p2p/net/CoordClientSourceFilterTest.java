package com.alterante.p2p.net;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketCodec;
import com.alterante.p2p.protocol.PacketType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A peer waiting for its partner must only believe the coordinator.
 *
 * <p>The coordination socket is unconnected and is the same one that hole-punches moments later, so
 * it hears from the remote peer and from anything else that probes the port. A host sits in this
 * wait for hours: one unsolicited datagram that decodes as COORD_ERROR would end that wait, and one
 * that decodes as PEER_INFO would point the entire connection wherever the sender chose.
 *
 * <p>Nothing here needs to be a deliberate attack — an early PUNCH from a peer that received
 * PEER_INFO first arrives on exactly this socket during exactly this window.
 */
class CoordClientSourceFilterTest {

    private static final String PSK = "test-psk";
    private static final String SESSION_ID = "filter-session";

    private int serverPort;
    private CoordServer server;
    private Thread serverThread;
    private InetSocketAddress serverAddr;

    @BeforeEach
    void setUp() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            serverPort = probe.getLocalPort();
        }
        serverAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), serverPort);
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
        long deadline = System.currentTimeMillis() + 5000;
        while (!server.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(50);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        serverThread.join(3000);
    }

    @Test
    void aForgedErrorFromAnotherAddressDoesNotEndTheWait() throws Exception {
        try (DatagramSocket clientSock = new DatagramSocket();
             DatagramSocket rogue = new DatagramSocket();
             DatagramSocket partner = new DatagramSocket()) {

            CoordClient client = new CoordClient(clientSock, serverAddr, SESSION_ID, PSK);
            client.setPeerWaitMs(15_000);

            CountDownLatch waiting = new CountDownLatch(1);
            client.setOnWaitingForPeer(waiting::countDown);

            AtomicReference<InetSocketAddress> result = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread clientThread = new Thread(() -> {
                try {
                    result.set(client.coordinate());
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            clientThread.setDaemon(true);
            clientThread.start();

            assertTrue(waiting.await(10, TimeUnit.SECONDS), "client never reached the wait phase");

            // Somebody else on the network tells it the session failed. Twice, to be sure a single
            // discard is not just a race that happened to swallow it.
            InetSocketAddress clientEndpoint =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), clientSock.getLocalPort());
            for (int i = 0; i < 2; i++) {
                sendRaw(rogue, clientEndpoint, error("Session not found"));
                Thread.sleep(100);
            }

            // The real partner then joins, and the coordinator pairs them for real.
            InetAddress addr = InetAddress.getLoopbackAddress();
            sendRegister(partner, addr, SESSION_ID);
            Packet challenge = receive(partner);
            assertEquals(PacketType.COORD_CHALLENGE, challenge.type());
            sendAuth(partner, addr, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));

            clientThread.join(15_000);

            assertNull(failure.get(),
                    "a datagram from a non-coordinator address must not abort coordination");
            assertNotNull(result.get(), "the client should have paired with the real partner");
        }
    }

    /**
     * The more serious half: a forged PEER_INFO must not be adopted. If it were, the peer would spend
     * its entire connection attempt — hole punching, then DTLS — against an address the sender chose,
     * and would report the honest partner as unreachable.
     */
    @Test
    void aForgedPeerInfoFromAnotherAddressIsNotAdopted() throws Exception {
        try (DatagramSocket clientSock = new DatagramSocket();
             DatagramSocket rogue = new DatagramSocket();
             DatagramSocket partner = new DatagramSocket()) {

            CoordClient client = new CoordClient(clientSock, serverAddr, SESSION_ID, PSK);
            client.setPeerWaitMs(15_000);

            CountDownLatch waiting = new CountDownLatch(1);
            client.setOnWaitingForPeer(waiting::countDown);

            AtomicReference<InetSocketAddress> result = new AtomicReference<>();
            AtomicReference<Exception> failure = new AtomicReference<>();
            Thread clientThread = new Thread(() -> {
                try {
                    result.set(client.coordinate());
                } catch (Exception e) {
                    failure.set(e);
                }
            });
            clientThread.setDaemon(true);
            clientThread.start();

            assertTrue(waiting.await(10, TimeUnit.SECONDS), "client never reached the wait phase");

            InetSocketAddress clientEndpoint =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), clientSock.getLocalPort());
            InetSocketAddress attackerChoice = new InetSocketAddress("203.0.113.7", 31337);
            sendRaw(rogue, clientEndpoint,
                    new Packet(PacketType.COORD_PEER_INFO, CoordServer.encodeEndpoint(attackerChoice)));
            Thread.sleep(200);

            InetAddress addr = InetAddress.getLoopbackAddress();
            sendRegister(partner, addr, SESSION_ID);
            Packet challenge = receive(partner);
            sendAuth(partner, addr, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));

            clientThread.join(15_000);

            assertNull(failure.get(), "coordination should have completed normally");
            assertNotNull(result.get());
            assertEquals(partner.getLocalPort(), result.get().getPort(),
                    "the peer endpoint must come from the coordinator, not from whoever spoke last");
        }
    }

    // --- helpers ---

    private Packet error(String message) {
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + msg.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).putShort((short) 1).put(msg);
        return new Packet(PacketType.COORD_ERROR, payload);
    }

    private void sendRaw(DatagramSocket s, InetSocketAddress dest, Packet p) throws Exception {
        byte[] b = PacketCodec.encode(p);
        s.send(new DatagramPacket(b, b.length, dest.getAddress(), dest.getPort()));
    }

    private void sendRegister(DatagramSocket s, InetAddress h, String id) throws Exception {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length).put(idBytes);
        sendRaw(s, serverAddr, new Packet(PacketType.COORD_REGISTER, payload));
    }

    private void sendAuth(DatagramSocket s, InetAddress h, String id, byte[] hmac) throws Exception {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length + 32];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length).put(idBytes).put(hmac);
        sendRaw(s, serverAddr, new Packet(PacketType.COORD_AUTH, payload));
    }

    private Packet receive(DatagramSocket s) throws Exception {
        s.setSoTimeout(3000);
        byte[] buf = new byte[Packet.MAX_DATAGRAM];
        DatagramPacket d = new DatagramPacket(buf, buf.length);
        s.receive(d);
        return PacketCodec.decode(buf, d.getLength());
    }
}

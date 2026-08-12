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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Reclaiming session slots from peers that never finish authenticating.
 *
 * <p>Both cases here were seen in production on 2026-08-11: a lore host became permanently unable to
 * register its own session, retrying {@code Session full} once per second, and the only way out was
 * to stop the host for five minutes. Three behaviours combined:
 *
 * <ol>
 *   <li>{@code addPeer()} claims a slot at REGISTER — <em>before</em> the peer proves it holds the
 *       PSK — so a peer that registers and dies holds that slot.</li>
 *   <li>Only a {@code bothAuthenticated()} session recycles, so a session with one live peer and one
 *       dead unauthenticated peer never does.</li>
 *   <li>{@code cleanExpiredSessions()} ran only when the receive socket timed out, so a peer
 *       retrying faster than that timeout starved the reaper — the retry waiting for expiry was what
 *       prevented it.</li>
 * </ol>
 *
 * <p>A short session timeout keeps these tests quick; the mechanism is the same at 300s.
 */
class CoordSessionReclaimTest {

    private static final String PSK = "test-psk";
    private static final String SESSION_ID = "reclaim-session";
    private static final int TIMEOUT_MS = 3000;
    /** Short enough to keep the test fast, long enough that a dead slot is not reclaimed instantly. */
    private static final int SESSION_TIMEOUT_SECONDS = 2;

    private int serverPort;
    private CoordServer server;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            serverPort = probe.getLocalPort();
        }
        server = new CoordServer(serverPort, PSK, SESSION_TIMEOUT_SECONDS);
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
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        serverThread.join(3000);
    }

    /**
     * The production shape: a <em>live, authenticated</em> peer keeps the session alive with
     * keepalives, while a second peer registered and died without authenticating. Session expiry can
     * never reclaim that dead slot — the healthy peer's keepalives refresh the whole session — so the
     * real second peer is locked out for as long as the host keeps running.
     *
     * <p>Note the first version of this test slept with the coordinator idle and <em>passed</em>: the
     * whole session expired and took the dead slot with it. A dead slot alone is survivable; it is
     * only permanent when something else keeps the session alive.
     */
    @Test
    void aDeadSlotIsReclaimedEvenWhileTheSessionIsKeptAlive() throws Exception {
        try (DatagramSocket host = new DatagramSocket();
             DatagramSocket dead = new DatagramSocket();
             DatagramSocket real = new DatagramSocket()) {
            host.setSoTimeout(TIMEOUT_MS);
            dead.setSoTimeout(TIMEOUT_MS);
            real.setSoTimeout(TIMEOUT_MS);
            InetAddress addr = InetAddress.getLoopbackAddress();

            // A genuine host registers and authenticates — slot 1, legitimately held.
            sendRegister(host, addr, serverPort, SESSION_ID);
            Packet challenge = receivePacket(host);
            assertEquals(PacketType.COORD_CHALLENGE, challenge.type());
            sendAuth(host, addr, serverPort, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receivePacket(host).type());

            // A client registers and dies before authenticating — slot 2, held by nothing.
            sendRegister(dead, addr, serverPort, SESSION_ID);
            assertEquals(PacketType.COORD_CHALLENGE, receivePacket(dead).type());

            // The host keepalives, as a long-lived host does, so the session never goes idle.
            long deadline = System.currentTimeMillis() + (SESSION_TIMEOUT_SECONDS + 3) * 1000L;
            PacketType last = null;
            while (System.currentTimeMillis() < deadline) {
                sendKeepalive(host, addr, serverPort, SESSION_ID);
                Thread.sleep(300);
                sendRegister(real, addr, serverPort, SESSION_ID);
                last = receivePacket(real).type();
                if (last == PacketType.COORD_CHALLENGE) {
                    break;
                }
                Thread.sleep(200);
            }

            assertEquals(PacketType.COORD_CHALLENGE, last,
                    "a slot held by a peer that never authenticated must be reclaimable even when "
                            + "the session is kept alive by its healthy partner");
        }
    }

    /**
     * The production failure: a peer retrying once per second kept the coordinator's socket busy, so
     * the reaper — which only ran on socket idle — never executed, and the session it was waiting on
     * could never expire.
     *
     * <p>The retry interval here is deliberately shorter than the server's 1s socket timeout.
     */
    @Test
    void aRetryingPeerDoesNotStarveTheReaper() throws Exception {
        try (DatagramSocket deadA = new DatagramSocket();
             DatagramSocket deadB = new DatagramSocket();
             DatagramSocket retrying = new DatagramSocket()) {
            deadA.setSoTimeout(TIMEOUT_MS);
            deadB.setSoTimeout(TIMEOUT_MS);
            retrying.setSoTimeout(TIMEOUT_MS);
            InetAddress host = InetAddress.getLoopbackAddress();

            sendRegister(deadA, host, serverPort, SESSION_ID);
            receivePacket(deadA);
            sendRegister(deadB, host, serverPort, SESSION_ID);
            receivePacket(deadB);

            // Poll faster than the socket timeout, exactly as a supervised service retrying on
            // failure would, for well over the session timeout.
            PacketType last = null;
            long deadline = System.currentTimeMillis() + (SESSION_TIMEOUT_SECONDS + 3) * 1000L;
            while (System.currentTimeMillis() < deadline) {
                sendRegister(retrying, host, serverPort, SESSION_ID);
                last = receivePacket(retrying).type();
                if (last == PacketType.COORD_CHALLENGE) {
                    break;      // got in — the reaper ran despite the traffic
                }
                Thread.sleep(200);
            }

            assertNotEquals(PacketType.COORD_ERROR, last,
                    "a peer polling faster than the socket timeout must not prevent session cleanup");
            assertEquals(PacketType.COORD_CHALLENGE, last);
        }
    }

    /**
     * The safety property: reclamation must only ever take slots from peers that never
     * authenticated. An authenticated peer holds its slot for as long as the session lives, however
     * long it sits idle waiting for a partner — which is precisely what a long-lived host does.
     */
    @Test
    void anAuthenticatedPeerKeepsItsSlotEvenWhenIdle() throws Exception {
        try (DatagramSocket host = new DatagramSocket();
             DatagramSocket intruder = new DatagramSocket()) {
            host.setSoTimeout(TIMEOUT_MS);
            intruder.setSoTimeout(TIMEOUT_MS);
            InetAddress addr = InetAddress.getLoopbackAddress();

            sendRegister(host, addr, serverPort, SESSION_ID);
            Packet challenge = receivePacket(host);
            sendAuth(host, addr, serverPort, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receivePacket(host).type());

            // Idle well past the grace period, keeping only the session alive.
            for (int i = 0; i < 8; i++) {
                sendKeepalive(host, addr, serverPort, SESSION_ID);
                Thread.sleep(500);
            }

            // A second peer may still join — but as the OTHER slot, not by evicting the host.
            sendRegister(intruder, addr, serverPort, SESSION_ID);
            Packet intruderChallenge = receivePacket(intruder);
            assertEquals(PacketType.COORD_CHALLENGE, intruderChallenge.type());
            sendAuth(intruder, addr, serverPort, SESSION_ID,
                    CoordServer.computeHmac(PSK, intruderChallenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receivePacket(intruder).type());

            // If the host had been evicted, it would never be told about a peer. It is still there,
            // so the pairing completes and both sides get PEER_INFO.
            assertEquals(PacketType.COORD_PEER_INFO, receivePacket(host).type(),
                    "an authenticated peer must not be reclaimed; the pairing must still complete");
        }
    }

    // --- helpers (same wire format as CoordServerTest) ---

    private void sendRegister(DatagramSocket socket, InetAddress hostAddr, int port, String sessionId)
            throws Exception {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes);
        sendPacket(socket, hostAddr, port, new Packet(PacketType.COORD_REGISTER, payload));
    }

    private void sendAuth(DatagramSocket socket, InetAddress hostAddr, int port,
                          String sessionId, byte[] hmac) throws Exception {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length + 32];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes)
                .put(hmac);
        sendPacket(socket, hostAddr, port, new Packet(PacketType.COORD_AUTH, payload));
    }

    private void sendKeepalive(DatagramSocket socket, InetAddress hostAddr, int port, String sessionId)
            throws Exception {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes);
        sendPacket(socket, hostAddr, port, new Packet(PacketType.COORD_KEEPALIVE, payload));
    }

    private void sendPacket(DatagramSocket socket, InetAddress hostAddr, int port, Packet packet)
            throws Exception {
        byte[] encoded = PacketCodec.encode(packet);
        socket.send(new DatagramPacket(encoded, encoded.length, hostAddr, port));
    }

    private Packet receivePacket(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[Packet.MAX_DATAGRAM];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);
        socket.receive(dgram);
        return PacketCodec.decode(buf, dgram.getLength());
    }
}

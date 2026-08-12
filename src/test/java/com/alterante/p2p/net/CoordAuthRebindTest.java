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
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A peer whose NAT remaps its port between REGISTER and AUTH must still be able to authenticate.
 *
 * <p>The coordinator claims a slot at REGISTER and used to look that slot up by sender address at
 * AUTH. A NAT mapping that expires or moves in that window — milliseconds normally, but seconds over
 * a slow link, and some CPE remaps aggressively under load — makes the peer unrecognisable to a
 * coordinator that is holding its registration: it answers "Not registered" while the slot the peer
 * is trying to claim sits right there, and the abandoned slot then counts against MAX_PEERS.
 *
 * <p>The HMAC is the identity that matters: it is computed over a nonce issued to one specific slot,
 * so producing a valid one proves which registration the sender owns regardless of address. A new
 * socket here is exactly what a remapped NAT port looks like to the coordinator.
 */
class CoordAuthRebindTest {

    private static final String PSK = "test-psk";
    private static final String SESSION_ID = "rebind-session";
    private static final int TIMEOUT_MS = 3000;

    private int serverPort;
    private CoordServer server;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws Exception {
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
    void aPeerRemappedBetweenRegisterAndAuthCanStillAuthenticate() throws Exception {
        InetAddress addr = InetAddress.getLoopbackAddress();
        byte[] nonce;

        // Register from one port...
        try (DatagramSocket before = new DatagramSocket()) {
            before.setSoTimeout(TIMEOUT_MS);
            sendRegister(before, addr, SESSION_ID);
            Packet challenge = receive(before);
            assertEquals(PacketType.COORD_CHALLENGE, challenge.type());
            nonce = challenge.payload();
        }

        // ...and authenticate from another. To the coordinator this is indistinguishable from a NAT
        // that moved the mapping; the HMAC over the challenge nonce is unchanged either way.
        try (DatagramSocket after = new DatagramSocket()) {
            after.setSoTimeout(TIMEOUT_MS);
            sendAuth(after, addr, SESSION_ID, CoordServer.computeHmac(PSK, nonce, SESSION_ID));

            assertEquals(PacketType.COORD_OK, receive(after).type(),
                    "a peer that proves it owns the slot must be authenticated at its new address, "
                            + "not told it never registered");
        }
    }

    /**
     * And the slot must genuinely <em>move</em>, not merely accept the AUTH: everything the
     * coordinator sends afterwards — PEER_INFO above all — goes to the slot's address. If it still
     * pointed at the pre-remap port, both peers would report a successful pairing and then never
     * hear from each other.
     */
    @Test
    void theSlotFollowsThePeerSoPeerInfoReachesIt() throws Exception {
        InetAddress addr = InetAddress.getLoopbackAddress();
        byte[] nonce;

        try (DatagramSocket before = new DatagramSocket()) {
            before.setSoTimeout(TIMEOUT_MS);
            sendRegister(before, addr, SESSION_ID);
            nonce = receive(before).payload();
        }

        try (DatagramSocket after = new DatagramSocket();
             DatagramSocket partner = new DatagramSocket()) {
            after.setSoTimeout(TIMEOUT_MS);
            partner.setSoTimeout(TIMEOUT_MS);

            sendAuth(after, addr, SESSION_ID, CoordServer.computeHmac(PSK, nonce, SESSION_ID));
            assertEquals(PacketType.COORD_OK, receive(after).type());

            // The other peer completes the pairing.
            sendRegister(partner, addr, SESSION_ID);
            Packet partnerChallenge = receive(partner);
            assertEquals(PacketType.COORD_CHALLENGE, partnerChallenge.type(),
                    "the remapped peer must occupy exactly one slot, leaving one free");
            sendAuth(partner, addr, SESSION_ID,
                    CoordServer.computeHmac(PSK, partnerChallenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receive(partner).type());

            assertEquals(PacketType.COORD_PEER_INFO, receive(after).type(),
                    "PEER_INFO must arrive at the address the peer actually speaks from");
            assertEquals(PacketType.COORD_PEER_INFO, receive(partner).type());
        }
    }

    /**
     * The security boundary: proof-of-PSK may claim a <em>pending</em> slot, never an established
     * one. Everyone in a deployment shares the PSK, so if a valid HMAC could move an authenticated
     * slot, any peer could silently redirect another's session to itself.
     */
    @Test
    void anAuthenticatedSlotCannotBeStolenByReplayingItsNonce() throws Exception {
        InetAddress addr = InetAddress.getLoopbackAddress();

        try (DatagramSocket host = new DatagramSocket();
             DatagramSocket thief = new DatagramSocket()) {
            host.setSoTimeout(TIMEOUT_MS);
            thief.setSoTimeout(1000);

            sendRegister(host, addr, SESSION_ID);
            Packet challenge = receive(host);
            byte[] nonce = challenge.payload();
            sendAuth(host, addr, SESSION_ID, CoordServer.computeHmac(PSK, nonce, SESSION_ID));
            assertEquals(PacketType.COORD_OK, receive(host).type());

            // Replay the host's exact proof from a different address.
            sendAuth(thief, addr, SESSION_ID, CoordServer.computeHmac(PSK, nonce, SESSION_ID));

            Packet reply = receiveOrNull(thief);
            org.junit.jupiter.api.Assertions.assertNotNull(reply, "the thief should be answered");
            assertEquals(PacketType.COORD_ERROR, reply.type(),
                    "a replayed nonce must not transfer an authenticated slot to a new address");

            // And the host must be untouched: it still holds its slot, so a real partner pairs with
            // it and PEER_INFO arrives at the host, not the thief.
            try (DatagramSocket partner = new DatagramSocket()) {
                partner.setSoTimeout(TIMEOUT_MS);
                sendRegister(partner, addr, SESSION_ID);
                Packet pc = receive(partner);
                assertEquals(PacketType.COORD_CHALLENGE, pc.type());
                sendAuth(partner, addr, SESSION_ID,
                        CoordServer.computeHmac(PSK, pc.payload(), SESSION_ID));
                assertEquals(PacketType.COORD_OK, receive(partner).type());

                assertEquals(PacketType.COORD_PEER_INFO, receive(host).type(),
                        "the legitimate host must still own its slot");
                assertNull(receiveOrNull(thief), "the thief must learn nothing about the session");
            }
        }
    }

    /** A wrong PSK must still be rejected — the rebind path must not become an auth bypass. */
    @Test
    void aWrongPskIsStillRejectedFromANewAddress() throws Exception {
        InetAddress addr = InetAddress.getLoopbackAddress();
        byte[] nonce;

        try (DatagramSocket before = new DatagramSocket()) {
            before.setSoTimeout(TIMEOUT_MS);
            sendRegister(before, addr, SESSION_ID);
            nonce = receive(before).payload();
        }

        try (DatagramSocket after = new DatagramSocket()) {
            after.setSoTimeout(TIMEOUT_MS);
            sendAuth(after, addr, SESSION_ID, CoordServer.computeHmac("wrong-psk", nonce, SESSION_ID));

            assertEquals(PacketType.COORD_ERROR, receive(after).type());
        }
    }

    // --- helpers ---

    private void sendRegister(DatagramSocket s, InetAddress h, String id) throws Exception {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length).put(idBytes);
        send(s, h, new Packet(PacketType.COORD_REGISTER, payload));
    }

    private void sendAuth(DatagramSocket s, InetAddress h, String id, byte[] hmac) throws Exception {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length + 32];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length).put(idBytes).put(hmac);
        send(s, h, new Packet(PacketType.COORD_AUTH, payload));
    }

    private void send(DatagramSocket s, InetAddress h, Packet p) throws Exception {
        byte[] b = PacketCodec.encode(p);
        s.send(new DatagramPacket(b, b.length, h, serverPort));
    }

    private Packet receive(DatagramSocket s) throws Exception {
        byte[] buf = new byte[Packet.MAX_DATAGRAM];
        DatagramPacket d = new DatagramPacket(buf, buf.length);
        s.receive(d);
        return PacketCodec.decode(buf, d.getLength());
    }

    private Packet receiveOrNull(DatagramSocket s) throws Exception {
        try {
            return receive(s);
        } catch (SocketTimeoutException e) {
            return null;
        }
    }
}

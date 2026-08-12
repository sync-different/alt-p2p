package com.alterante.p2p.net;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketCodec;
import com.alterante.p2p.protocol.PacketType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A host must find out when the coordinator has forgotten its registration.
 *
 * <p>A long-lived host (bbs {@code host}, lore {@code serve}) sits in {@code waitForPeerInfo()} for
 * hours, sending {@code COORD_KEEPALIVE} every 90s and expecting no reply. If the coordinator
 * restarts — or the session is dropped for any other reason — those keepalives land on a server that
 * has never heard of this peer.
 *
 * <p>Silently ignoring them strands the host: it waits <em>forever</em> (`--peer-wait 0`) on a
 * registration that no longer exists, while looking perfectly healthy in its logs. A client then
 * creates a fresh session and waits for a peer that can never arrive. Nothing in the system says so.
 *
 * <p>The fix is for the coordinator to answer an unattributable keepalive with an error, which the
 * client already treats as fatal — so the host's supervising loop re-registers.
 */
class CoordKeepaliveRecoveryTest {

    private static final String PSK = "test-psk";
    private static final String SESSION_ID = "keepalive-session";
    private static final int TIMEOUT_MS = 3000;

    private CoordServer server;
    private Thread serverThread;

    private void startServer(int port) throws Exception {
        server = new CoordServer(port, PSK, 60);
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
        // Wait for the bind, don't guess at it: UDP has no retry, so a REGISTER sent before the
        // socket exists is silently lost and the test fails as a receive timeout far from the cause.
        long deadline = System.currentTimeMillis() + 5000;
        while (!server.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        if (!server.isRunning()) {
            throw new IllegalStateException("coordinator did not start (port " + port + " in use?)");
        }
        Thread.sleep(50);   // the receive loop starts just after the flag
    }

    private void stopServer() throws Exception {
        if (server != null) {
            server.stop();
            serverThread.join(3000);
            server = null;
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        stopServer();
    }

    @Test
    void aKeepaliveIsRejectedAfterTheCoordinatorForgetsTheSession() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        startServer(port);

        try (DatagramSocket host = new DatagramSocket()) {
            host.setSoTimeout(TIMEOUT_MS);
            InetAddress addr = InetAddress.getLoopbackAddress();

            // A host registers and authenticates, then waits — the normal long-lived host state.
            sendRegister(host, addr, port, SESSION_ID);
            Packet challenge = receive(host);
            assertEquals(PacketType.COORD_CHALLENGE, challenge.type());
            sendAuth(host, addr, port, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receive(host).type());

            // While it waits, the coordinator restarts. Every session is gone; the host does not know.
            stopServer();
            startServer(port);

            // Its next keepalive must NOT vanish into silence — that is what strands it forever.
            sendKeepalive(host, addr, port, SESSION_ID);
            Packet reply = receiveOrNull(host);

            assertNotNull(reply,
                    "a keepalive for a session the coordinator does not know must be answered, "
                            + "or a waiting host never learns its registration is gone");
            assertEquals(PacketType.COORD_ERROR, reply.type(),
                    "the host must be told to re-register");
        }
    }

    /** The healthy case must stay silent: a keepalive for a live registration gets no reply. */
    @Test
    void aKeepaliveForALiveRegistrationIsNotAnswered() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        startServer(port);

        try (DatagramSocket host = new DatagramSocket()) {
            host.setSoTimeout(1000);
            InetAddress addr = InetAddress.getLoopbackAddress();

            sendRegister(host, addr, port, SESSION_ID);
            Packet challenge = receive(host);
            sendAuth(host, addr, port, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receive(host).type());

            sendKeepalive(host, addr, port, SESSION_ID);

            // No reply is the contract for a healthy keepalive — answering every one would double
            // the traffic of every idle host on the fleet for no benefit.
            org.junit.jupiter.api.Assertions.assertNull(receiveOrNull(host),
                    "a keepalive for a live registration must stay silent");
        }
    }

    /**
     * Compatibility: peers before 0.7.1 send an <em>empty</em> keepalive payload. They must still be
     * attributed — by sender endpoint — and so must still be met with silence. Getting this wrong
     * would tell every older host on the fleet to re-register every 90 seconds.
     */
    @Test
    void aKeepaliveWithNoSessionIdIsStillAttributedByEndpoint() throws Exception {
        int port;
        try (DatagramSocket probe = new DatagramSocket(0)) {
            port = probe.getLocalPort();
        }
        startServer(port);

        try (DatagramSocket host = new DatagramSocket()) {
            host.setSoTimeout(1000);
            InetAddress addr = InetAddress.getLoopbackAddress();

            sendRegister(host, addr, port, SESSION_ID);
            Packet challenge = receive(host);
            sendAuth(host, addr, port, SESSION_ID,
                    CoordServer.computeHmac(PSK, challenge.payload(), SESSION_ID));
            assertEquals(PacketType.COORD_OK, receive(host).type());

            send(host, addr, port, new Packet(PacketType.COORD_KEEPALIVE, new byte[0]));

            org.junit.jupiter.api.Assertions.assertNull(receiveOrNull(host),
                    "an old peer's empty keepalive must be attributed by endpoint, not rejected");
        }
    }

    // --- helpers ---

    private void sendRegister(DatagramSocket s, InetAddress h, int port, String id) throws Exception {
        send(s, h, port, new Packet(PacketType.COORD_REGISTER, sessionPayload(id)));
    }

    private void sendKeepalive(DatagramSocket s, InetAddress h, int port, String id) throws Exception {
        send(s, h, port, new Packet(PacketType.COORD_KEEPALIVE, sessionPayload(id)));
    }

    private void sendAuth(DatagramSocket s, InetAddress h, int port, String id, byte[] hmac)
            throws Exception {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length + 32];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length).put(idBytes).put(hmac);
        send(s, h, port, new Packet(PacketType.COORD_AUTH, payload));
    }

    private byte[] sessionPayload(String id) {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length).put(idBytes);
        return payload;
    }

    private void send(DatagramSocket s, InetAddress h, int port, Packet p) throws Exception {
        byte[] b = PacketCodec.encode(p);
        s.send(new DatagramPacket(b, b.length, h, port));
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

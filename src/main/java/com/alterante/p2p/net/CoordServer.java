package com.alterante.p2p.net;

import com.alterante.p2p.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight UDP coordination server.
 * Handles peer registration, HMAC-SHA256 authentication, and endpoint exchange.
 *
 * Protocol flow per peer:
 * 1. Peer → COORD_REGISTER(session_id)
 * 2. Server → COORD_CHALLENGE(32-byte nonce)
 * 3. Peer → COORD_AUTH(HMAC-SHA256(psk, nonce + session_id))
 * 4. Server → COORD_OK(peer's public IP:port)
 * 5. When both peers authenticated → Server → COORD_PEER_INFO to both
 */
public class CoordServer {

    private static final Logger log = LoggerFactory.getLogger(CoordServer.class);

    private final int port;
    private final String psk;
    private final int sessionTimeoutMs;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;

    public CoordServer(int port, String psk, int sessionTimeoutSeconds) {
        this.port = port;
        this.psk = psk;
        this.sessionTimeoutMs = sessionTimeoutSeconds * 1000;
    }

    public void start() throws IOException {
        socket = new DatagramSocket(port);
        socket.setSoTimeout(1000); // 1s timeout for clean shutdown checks
        running.set(true);
        log.info("Coordination server listening on UDP port {}", port);

        byte[] recvBuf = new byte[Packet.MAX_DATAGRAM];

        while (running.get()) {
            DatagramPacket dgram = new DatagramPacket(recvBuf, recvBuf.length);
            try {
                socket.receive(dgram);
            } catch (SocketTimeoutException e) {
                cleanExpiredSessions();
                continue;
            }

            InetSocketAddress sender = new InetSocketAddress(dgram.getAddress(), dgram.getPort());

            try {
                Packet packet = PacketCodec.decode(recvBuf, dgram.getLength());
                handlePacket(packet, sender);
            } catch (PacketException e) {
                log.debug("Bad packet from {}: {}", sender, e.getMessage());
            }
        }

        socket.close();
        log.info("Coordination server stopped");
    }

    public void stop() {
        running.set(false);
    }

    public boolean isRunning() {
        return running.get();
    }

    private void handlePacket(Packet packet, InetSocketAddress sender) {
        switch (packet.type()) {
            case COORD_REGISTER -> handleRegister(packet, sender);
            case COORD_AUTH -> handleAuth(packet, sender);
            case COORD_KEEPALIVE -> handleKeepalive(packet, sender);
            case COORD_PING -> handlePing(sender);
            default -> log.debug("Unexpected type {} from {}", packet.type(), sender);
        }
    }

    private void handleRegister(Packet packet, InetSocketAddress sender) {
        // Payload: 2-byte length + session_id UTF-8
        byte[] payload = packet.payload();
        if (payload.length < 2) {
            sendError(sender, (short) 0x0001, "Missing session ID");
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int idLen = Short.toUnsignedInt(buf.getShort());
        if (idLen <= 0 || idLen > payload.length - 2) {
            sendError(sender, (short) 0x0001, "Invalid session ID length");
            return;
        }
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String sessionId = new String(idBytes, StandardCharsets.UTF_8);

        log.info("REGISTER from {} for session '{}'", sender, sessionId);

        // Get or create session
        Session session = sessions.computeIfAbsent(sessionId, id -> new Session(id, psk));

        // Check if this peer is already registered
        Session.PeerSlot existing = session.findPeer(sender);
        if (existing != null) {
            // Re-registration: send challenge again
            sendChallenge(sender, existing.nonce);
            return;
        }

        // Check if session is full
        if (session.isFull()) {
            sendError(sender, (short) 0x0001, "Session full");
            return;
        }

        // Add peer and send challenge
        Session.PeerSlot slot = session.addPeer(sender);
        if (slot == null) {
            sendError(sender, (short) 0x0001, "Session full");
            return;
        }

        sendChallenge(sender, slot.nonce);
    }

    private void handleAuth(Packet packet, InetSocketAddress sender) {
        // Payload: 2-byte session_id length + session_id + 32-byte HMAC
        byte[] payload = packet.payload();
        if (payload.length < 2) {
            sendError(sender, (short) 0x0002, "Malformed auth");
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int idLen = Short.toUnsignedInt(buf.getShort());
        if (idLen <= 0 || buf.remaining() < idLen + 32) {
            sendError(sender, (short) 0x0002, "Malformed auth");
            return;
        }
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String sessionId = new String(idBytes, StandardCharsets.UTF_8);

        byte[] receivedHmac = new byte[32];
        buf.get(receivedHmac);

        Session session = sessions.get(sessionId);
        if (session == null) {
            sendError(sender, (short) 0x0001, "Session not found");
            return;
        }

        Session.PeerSlot slot = session.findPeer(sender);
        if (slot == null) {
            sendError(sender, (short) 0x0002, "Not registered");
            return;
        }

        // Verify HMAC-SHA256(psk, nonce + session_id)
        byte[] expectedHmac = computeHmac(psk, slot.nonce, sessionId);
        if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) {
            log.warn("AUTH failed from {} for session '{}'", sender, sessionId);
            sendError(sender, (short) 0x0002, "Authentication failed");
            return;
        }

        slot.authenticated = true;
        session.touch();
        log.info("AUTH success from {} for session '{}'", sender, sessionId);

        // Send OK with the peer's public endpoint
        sendOk(sender, sender);

        // If both peers are now authenticated, send PEER_INFO to both
        if (session.bothAuthenticated()) {
            Session.PeerSlot peer0 = session.getPeer(0);
            Session.PeerSlot peer1 = session.getPeer(1);
            sendPeerInfo(peer0.endpoint, peer1.endpoint);
            sendPeerInfo(peer1.endpoint, peer0.endpoint);
            log.info("Session '{}': both peers connected, sent PEER_INFO", sessionId);
        }
    }

    private void handleKeepalive(Packet packet, InetSocketAddress sender) {
        // Find which session this peer belongs to and touch it
        for (Session session : sessions.values()) {
            Session.PeerSlot slot = session.findPeer(sender);
            if (slot != null && slot.authenticated) {
                session.touch();
                break;
            }
        }
    }

    private void handlePing(InetSocketAddress sender) {
        sendPacket(sender, new Packet(PacketType.COORD_PONG));
    }

    // --- Payload builders ---

    private void sendChallenge(InetSocketAddress dest, byte[] nonce) {
        sendPacket(dest, new Packet(PacketType.COORD_CHALLENGE, nonce));
    }

    private void sendOk(InetSocketAddress dest, InetSocketAddress peerEndpoint) {
        byte[] payload = encodeEndpoint(peerEndpoint);
        sendPacket(dest, new Packet(PacketType.COORD_OK, payload));
    }

    private void sendPeerInfo(InetSocketAddress dest, InetSocketAddress peerEndpoint) {
        byte[] payload = encodeEndpoint(peerEndpoint);
        sendPacket(dest, new Packet(PacketType.COORD_PEER_INFO, payload));
    }

    private void sendError(InetSocketAddress dest, short code, String message) {
        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + msgBytes.length];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        buf.putShort(code);
        buf.put(msgBytes);
        sendPacket(dest, new Packet(PacketType.COORD_ERROR, payload));
    }

    private void sendPacket(InetSocketAddress dest, Packet packet) {
        try {
            byte[] data = PacketCodec.encode(packet);
            socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
        } catch (IOException e) {
            log.error("Failed to send to {}: {}", dest, e.getMessage());
        }
    }

    // --- Endpoint encoding: 1 byte addr_len + IP bytes + 2 byte port ---

    static byte[] encodeEndpoint(InetSocketAddress endpoint) {
        byte[] addrBytes = endpoint.getAddress().getAddress();
        byte[] out = new byte[1 + addrBytes.length + 2];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) addrBytes.length);
        buf.put(addrBytes);
        buf.putShort((short) endpoint.getPort());
        return out;
    }

    static InetSocketAddress decodeEndpoint(byte[] data, int offset) {
        ByteBuffer buf = ByteBuffer.wrap(data, offset, data.length - offset).order(ByteOrder.BIG_ENDIAN);
        int addrLen = Byte.toUnsignedInt(buf.get());
        byte[] addrBytes = new byte[addrLen];
        buf.get(addrBytes);
        int port = Short.toUnsignedInt(buf.getShort());
        try {
            InetAddress addr = InetAddress.getByAddress(addrBytes);
            return new InetSocketAddress(addr, port);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Bad address bytes", e);
        }
    }

    // --- HMAC ---

    static byte[] computeHmac(String psk, byte[] nonce, String sessionId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(psk.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(nonce);
            mac.update(sessionId.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 unavailable", e);
        }
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().lastActivity()) > sessionTimeoutMs;
            if (expired) {
                log.debug("Session '{}' expired", entry.getKey());
            }
            return expired;
        });
    }
}

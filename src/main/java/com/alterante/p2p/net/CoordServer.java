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
    private final java.util.concurrent.atomic.AtomicLong relayedPackets =
            new java.util.concurrent.atomic.AtomicLong();
    /** When the reaper last ran, so it fires on a schedule instead of only when the socket idles. */
    private volatile long lastCleanupAt = System.currentTimeMillis();
    private static final long CLEANUP_INTERVAL_MS = 1000;
    private DatagramSocket socket;

    public CoordServer(int port, String psk, int sessionTimeoutSeconds) {
        this.port = port;
        this.psk = psk;
        this.sessionTimeoutMs = sessionTimeoutSeconds * 1000;
    }

    public void start() throws IOException {
        DatagramSocket bound = new DatagramSocket(port);
        bound.setSoTimeout(1000); // 1s timeout for clean shutdown checks
        socket = bound;
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
            } catch (SocketException e) {
                // stop() closes the socket to break this receive immediately. Only a close we asked
                // for is clean — anything else is a real error and must not be swallowed.
                if (!running.get()) {
                    break;
                }
                throw e;
            }

            // Cleanup must NOT depend on the socket going idle. A peer retrying faster than the
            // socket timeout keeps this loop busy forever, and the session it is waiting on can then
            // never expire — the retry waiting for expiry is what prevents it. Seen in production:
            // a host locked out of its own session indefinitely.
            if (System.currentTimeMillis() - lastCleanupAt >= CLEANUP_INTERVAL_MS) {
                cleanExpiredSessions();
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

    /**
     * Stop the receive loop and release the port.
     *
     * <p>Closing the socket here rather than only clearing the flag matters twice: the loop wakes at
     * once instead of after up to the 1s socket timeout, and the port is free the moment this returns
     * — so a supervisor restarting the coordinator on the same port cannot lose the bind race with
     * its own predecessor. It is also the only path that releases the socket when {@code start()}
     * itself threw.
     */
    public void stop() {
        running.set(false);
        DatagramSocket s = socket;
        if (s != null) {
            s.close();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Datagrams forwarded between peers on the UDP relay path since startup.
     *
     * <p>Worth having beyond tests: this is the one number that says whether a coordinator is merely
     * introducing peers or carrying their traffic, which is the difference between negligible load
     * and saturating its uplink.
     */
    public long relayedPackets() {
        return relayedPackets.get();
    }

    private void handlePacket(Packet packet, InetSocketAddress sender) {
        switch (packet.type()) {
            case COORD_REGISTER -> handleRegister(packet, sender);
            case COORD_AUTH -> handleAuth(packet, sender);
            case COORD_KEEPALIVE -> handleKeepalive(packet, sender);
            case COORD_RELAY -> handleRelay(packet, sender);
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
            if (session.bothAuthenticated()) {
                // The previous pairing already completed — both peers received
                // PEER_INFO and are connecting directly, no longer needing the
                // coordinator. A fresh REGISTER means a new rendezvous on this
                // session id (e.g. a persistent host serving successive client
                // operations), so recycle the slots instead of rejecting.
                log.info("Session '{}' already paired; recycling for a new rendezvous", sessionId);
                session.reset();
            } else if (session.reclaimStaleUnauthenticated(unauthenticatedGraceMs()) > 0) {
                // A slot was held by a peer that registered and never authenticated. It proved
                // nothing, and session expiry can never reclaim it while a healthy partner keeps
                // the session alive — so reclaim it here rather than lock out a real peer.
                log.info("Session '{}': reclaimed a slot from a peer that never authenticated", sessionId);
            } else {
                sendError(sender, (short) 0x0001, "Session full");
                return;
            }
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
        if (slot != null) {
            // Verify HMAC-SHA256(psk, nonce + session_id)
            byte[] expectedHmac = computeHmac(psk, slot.nonce, sessionId);
            if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) {
                log.warn("AUTH failed from {} for session '{}'", sender, sessionId);
                sendError(sender, (short) 0x0002, "Authentication failed");
                return;
            }
        } else {
            // No slot at this address. Before rejecting, consider that the peer's NAT may have
            // remapped its port between REGISTER and AUTH — a mapping can expire or move mid-exchange
            // — in which case the registration is real and only its address is stale. The HMAC
            // settles it: it is computed over a nonce we issued to one specific slot, so a peer that
            // produces a valid one for that slot is the peer we issued it to, whatever address it
            // now speaks from. Only unauthenticated slots are candidates, so this can never displace
            // an established peer.
            slot = matchByProof(session, sessionId, receivedHmac);
            if (slot == null) {
                log.info("AUTH from {} for session '{}' matches no slot (registered peers: {})",
                        sender, sessionId, describePeers(session));
                sendError(sender, (short) 0x0002, "Not registered");
                return;
            }
            log.info("AUTH from {} for session '{}' proved the slot registered as {} — "
                            + "NAT remapped the port mid-handshake; following it",
                    sender, sessionId, slot.endpoint);
            slot.rebind(sender);
        }

        slot.authenticated = true;
        session.touch();

        // Optional trailing local (LAN) endpoint — lets same-NAT peers punch over the LAN.
        if (buf.hasRemaining()) {
            try {
                slot.localEndpoint = decodeEndpoint(payload, buf.position());
            } catch (RuntimeException e) {
                log.debug("Ignoring malformed local endpoint from {}: {}", sender, e.getMessage());
            }
        }
        log.info("AUTH success from {} for session '{}' (local={})", sender, sessionId, slot.localEndpoint);

        // Send OK with the peer's public endpoint
        sendOk(sender, sender);

        // If both peers are now authenticated, send PEER_INFO to both (public + local)
        if (session.bothAuthenticated()) {
            Session.PeerSlot peer0 = session.getPeer(0);
            Session.PeerSlot peer1 = session.getPeer(1);
            sendPeerInfo(peer0.endpoint, peer1.endpoint, peer1.localEndpoint);
            sendPeerInfo(peer1.endpoint, peer0.endpoint, peer0.localEndpoint);
            log.info("Session '{}': both peers connected, sent PEER_INFO", sessionId);
        }
    }

    /**
     * Refresh the session a waiting peer belongs to — or tell it that it no longer has one.
     *
     * <p>A long-lived host sits in {@code waitForPeerInfo()} for hours, keepaliving every 90s and
     * expecting silence. Silence is therefore indistinguishable from "the coordinator has never heard
     * of you": if the coordinator restarts, or the session is dropped, the host waits <em>forever</em>
     * on a registration that no longer exists while its logs look perfectly healthy. Meanwhile a
     * client registers a fresh session and waits for a peer that can never arrive.
     *
     * <p>So an unattributable keepalive is answered with an error. {@code CoordClient} already treats
     * COORD_ERROR while waiting as fatal, which unwinds to the supervising loop and re-registers. The
     * healthy case stays silent — answering every keepalive would double the idle traffic of every
     * host on the fleet for nothing.
     */
    private void handleKeepalive(Packet packet, InetSocketAddress sender) {
        // The session id is optional: peers before 0.7.1 send an empty payload, and are attributed by
        // endpoint alone.
        String sessionId = tryParseSessionId(packet.payload());

        if (sessionId != null) {
            Session session = sessions.get(sessionId);
            if (session != null && touchIfAuthenticated(session, sender)) {
                return;
            }
        } else {
            for (Session session : sessions.values()) {
                if (touchIfAuthenticated(session, sender)) {
                    return;
                }
            }
        }

        // Not an error on the peer's part — its registration is simply gone. Log at INFO: this is the
        // one line that explains an otherwise invisible "host waited forever" report.
        log.info("KEEPALIVE from {} matches no authenticated registration for {} — "
                        + "rejecting so the peer re-registers",
                sender, sessionId == null ? "any session" : "session '" + sessionId + "'");
        sendError(sender, (short) 0x0003, "Not registered — re-register");
    }

    /**
     * Find the unauthenticated slot whose nonce this HMAC was computed over, or null.
     *
     * <p>Every candidate is checked with a constant-time compare, and the loop is not short-circuited
     * on a mismatch beyond moving to the next slot — with at most two slots there is nothing to leak.
     */
    private Session.PeerSlot matchByProof(Session session, String sessionId, byte[] receivedHmac) {
        for (Session.PeerSlot candidate : session.unauthenticatedPeers()) {
            byte[] expected = computeHmac(psk, candidate.nonce, sessionId);
            if (MessageDigest.isEqual(receivedHmac, expected)) {
                return candidate;
            }
        }
        return null;
    }

    /** Registered endpoints and their auth state — the context a failed AUTH needs to be diagnosed. */
    private static String describePeers(Session session) {
        StringBuilder sb = new StringBuilder();
        for (Session.PeerSlot slot : session.peers()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(slot.endpoint).append(slot.authenticated ? " (auth)" : " (pending)");
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** Touch the session if {@code sender} holds an authenticated slot in it. */
    private boolean touchIfAuthenticated(Session session, InetSocketAddress sender) {
        Session.PeerSlot slot = session.findPeer(sender);
        if (slot != null && slot.authenticated) {
            session.touch();
            log.debug("KEEPALIVE from {} refreshed session '{}'", sender, session.sessionId());
            return true;
        }
        return false;
    }

    /** Session id from a {@code len:u16 + utf8} payload, or null if absent or malformed. */
    private static String tryParseSessionId(byte[] payload) {
        if (payload == null || payload.length < 2) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int idLen = Short.toUnsignedInt(buf.getShort());
        if (idLen <= 0 || idLen > payload.length - 2) {
            return null;
        }
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        return new String(idBytes, StandardCharsets.UTF_8);
    }

    /**
     * Relay a packet from one peer to the other in the same session.
     * The COORD_RELAY payload is the raw bytes to forward (opaque to the server).
     * The server wraps the payload in a new COORD_RELAY packet for the recipient.
     */
    private void handleRelay(Packet packet, InetSocketAddress sender) {
        byte[] payload = packet.payload();
        if (payload.length == 0) {
            log.debug("Empty relay packet from {}", sender);
            return;
        }

        // Find the session this peer belongs to
        for (Session session : sessions.values()) {
            Session.PeerSlot slot = session.findPeer(sender);
            if (slot != null && slot.authenticated) {
                Session.PeerSlot other = session.getOtherPeer(sender);
                if (other != null && other.authenticated) {
                    // Forward: wrap the same payload in a COORD_RELAY to the other peer.
                    // DEBUG, not INFO: this fires once per datagram on the relay data path — at INFO
                    // a single transfer writes millions of lines and the log becomes the bottleneck.
                    log.debug("Relay: {} -> {} ({} bytes)", sender, other.endpoint, payload.length);
                    relayedPackets.incrementAndGet();
                    sendPacket(other.endpoint, new Packet(PacketType.COORD_RELAY, payload));
                    session.touch();
                } else {
                    log.warn("Relay from {} but no authenticated peer to forward to", sender);
                }
                return;
            }
        }
        log.warn("Relay from unknown peer {}", sender);
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

    private void sendPeerInfo(InetSocketAddress dest, InetSocketAddress peerPublic, InetSocketAddress peerLocal) {
        byte[] pub = encodeEndpoint(peerPublic);
        byte[] payload = pub;
        if (peerLocal != null) {
            byte[] loc = encodeEndpoint(peerLocal);
            payload = new byte[pub.length + loc.length];
            System.arraycopy(pub, 0, payload, 0, pub.length);
            System.arraycopy(loc, 0, payload, pub.length, loc.length);
        }
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

    /** Bytes consumed by an endpoint encoded at {@code offset}: 1 (addrLen) + addr + 2 (port). */
    static int encodedEndpointLength(byte[] data, int offset) {
        return 1 + (data[offset] & 0xFF) + 2;
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

    /** A grace no longer than the session's own lifetime, so it always has a chance to fire. */
    private long unauthenticatedGraceMs() {
        return Math.min(Session.UNAUTHENTICATED_GRACE_MS, sessionTimeoutMs);
    }

    private void cleanExpiredSessions() {
        lastCleanupAt = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            boolean expired = (now - entry.getValue().lastActivity()) > sessionTimeoutMs;
            if (expired) {
                log.debug("Session '{}' expired", entry.getKey());
            }
            return expired;
        });
        // Also reclaim dead slots inside sessions that are still alive: a session kept warm by one
        // healthy peer never expires, so without this a slot lost to an unauthenticated peer would
        // be held for as long as its partner keeps running.
        long grace = unauthenticatedGraceMs();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            int freed = e.getValue().reclaimStaleUnauthenticated(grace);
            if (freed > 0) {
                log.info("Session '{}': reclaimed {} slot(s) from peers that never authenticated",
                        e.getKey(), freed);
            }
        }
    }
}

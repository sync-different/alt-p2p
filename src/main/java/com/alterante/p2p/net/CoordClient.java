package com.alterante.p2p.net;

import com.alterante.p2p.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Coordination client: registers with the server, authenticates via HMAC-SHA256,
 * and waits for the remote peer's endpoint (PEER_INFO).
 *
 * Thread model: all methods are blocking. The caller runs this on a dedicated thread
 * or uses PeerConnection which manages the lifecycle.
 */
public class CoordClient {

    private static final Logger log = LoggerFactory.getLogger(CoordClient.class);
    private static final int RECV_TIMEOUT_MS = 5000;
    private static final int MAX_RETRIES = 3;
    private static final long KEEPALIVE_INTERVAL_MS = 90_000; // keep the coord session alive while waiting (< its ~300s timeout)

    private final DatagramSocket socket;
    private final InetSocketAddress serverAddr;
    private final String sessionId;
    private final String psk;

    private Runnable onWaitingForPeer;
    private int peerWaitMs = 120_000; // how long waitForPeerInfo waits for the peer; <=0 = forever
    private InetSocketAddress myPublicEndpoint;
    private InetSocketAddress remoteEndpoint;
    private InetSocketAddress remoteLocalEndpoint;

    public CoordClient(DatagramSocket socket, InetSocketAddress serverAddr,
                       String sessionId, String psk) {
        this.socket = socket;
        this.serverAddr = serverAddr;
        this.sessionId = sessionId;
        this.psk = psk;
    }

    /** How long waitForPeerInfo() waits for the peer to join; &lt;= 0 means wait forever. */
    public void setPeerWaitMs(int ms) { this.peerWaitMs = ms; }

    /**
     * Run the full coordination flow. Blocks until PEER_INFO is received or fails.
     *
     * @return the remote peer's endpoint
     * @throws CoordException if coordination fails
     */
    public InetSocketAddress coordinate() throws CoordException {
        try {
            socket.setSoTimeout(RECV_TIMEOUT_MS);

            // Step 1: REGISTER → receive CHALLENGE
            byte[] nonce = register();

            // Step 2: AUTH → receive OK
            authenticate(nonce);

            // Step 3: Wait for PEER_INFO
            waitForPeerInfo();

            return remoteEndpoint;
        } catch (CoordException e) {
            throw e;
        } catch (Exception e) {
            throw new CoordException("Coordination failed: " + e.getMessage(), e);
        }
    }

    /** Set a callback that fires when entering the wait-for-peer phase. */
    public void setOnWaitingForPeer(Runnable callback) {
        this.onWaitingForPeer = callback;
    }

    public InetSocketAddress myPublicEndpoint() { return myPublicEndpoint; }
    public InetSocketAddress remoteEndpoint() { return remoteEndpoint; }
    /** Remote peer's LAN endpoint (a hole-punch candidate), or null if not reported. */
    public InetSocketAddress remoteLocalEndpoint() { return remoteLocalEndpoint; }

    /** Our LAN endpoint: the source IP used to reach the coord server + our local port. */
    private InetSocketAddress localEndpoint() {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(serverAddr.getAddress(), serverAddr.getPort());
            InetAddress local = probe.getLocalAddress();
            if (local != null && !local.isAnyLocalAddress() && !local.isLoopbackAddress()) {
                return new InetSocketAddress(local, socket.getLocalPort());
            }
        } catch (Exception e) {
            log.debug("Could not determine local endpoint: {}", e.getMessage());
        }
        return null;
    }

    private byte[] register() throws CoordException {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[2 + idBytes.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes);

        Packet registerPkt = new Packet(PacketType.COORD_REGISTER, payload);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                send(registerPkt);
                log.info("Sent REGISTER for session '{}' (attempt {})", sessionId, attempt);

                Packet response = receive();
                if (response.type() == PacketType.COORD_CHALLENGE) {
                    log.info("Received CHALLENGE ({} byte nonce)", response.payloadLength());
                    return response.payload();
                } else if (response.type() == PacketType.COORD_ERROR) {
                    throw new CoordException("Server rejected REGISTER: " + decodeError(response));
                } else {
                    log.warn("Unexpected response to REGISTER: {}", response.type());
                }
            } catch (SocketTimeoutException e) {
                log.warn("REGISTER timeout (attempt {})", attempt);
            }
        }
        throw new CoordException("REGISTER failed after " + MAX_RETRIES + " attempts");
    }

    private void authenticate(byte[] nonce) throws CoordException {
        byte[] hmac = CoordServer.computeHmac(psk, nonce, sessionId);

        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        InetSocketAddress local = localEndpoint();
        byte[] localEnc = (local != null) ? CoordServer.encodeEndpoint(local) : new byte[0];

        byte[] payload = new byte[2 + idBytes.length + 32 + localEnc.length];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes)
                .put(hmac)
                .put(localEnc);

        Packet authPkt = new Packet(PacketType.COORD_AUTH, payload);

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                send(authPkt);
                log.info("Sent AUTH for session '{}' (attempt {})", sessionId, attempt);

                Packet response = receive();
                if (response.type() == PacketType.COORD_OK) {
                    myPublicEndpoint = CoordServer.decodeEndpoint(response.payload(), 0);
                    log.info("Authenticated. My public endpoint: {}", myPublicEndpoint);
                    return;
                } else if (response.type() == PacketType.COORD_ERROR) {
                    throw new CoordException("Authentication failed: " + decodeError(response));
                } else if (response.type() == PacketType.COORD_PEER_INFO) {
                    // Edge case: both peers auth'd nearly simultaneously — server sent
                    // PEER_INFO before we processed OK. Handle it.
                    handlePeerInfo(response);
                    return;
                } else {
                    log.warn("Unexpected response to AUTH: {}", response.type());
                }
            } catch (SocketTimeoutException e) {
                log.warn("AUTH timeout (attempt {})", attempt);
            }
        }
        throw new CoordException("AUTH failed after " + MAX_RETRIES + " attempts");
    }

    private void waitForPeerInfo() throws CoordException {
        if (remoteEndpoint != null) {
            // Already received during auth phase
            return;
        }

        boolean forever = peerWaitMs <= 0;
        log.info("Waiting for peer to join session '{}' ({})", sessionId,
                forever ? "no timeout" : "up to " + (peerWaitMs / 1000) + "s");
        if (onWaitingForPeer != null) onWaitingForPeer.run();

        // A host/serve may sit here a long time (peerWaitMs <= 0 = forever). Send a
        // COORD_KEEPALIVE every KEEPALIVE_INTERVAL_MS so the coordinator does not expire
        // our session (its lastActivity timeout). This holds ONE socket/registration open
        // instead of tearing down and re-registering, which used to leak a DatagramSocket
        // fd per cycle on a waiting host.
        long start = System.currentTimeMillis();
        long deadline = forever ? Long.MAX_VALUE : start + peerWaitMs;
        long lastKeepalive = start;

        while (System.currentTimeMillis() < deadline) {
            try {
                Packet response = receive();
                if (response.type() == PacketType.COORD_PEER_INFO) {
                    handlePeerInfo(response);
                    return;
                } else if (response.type() == PacketType.COORD_ERROR) {
                    throw new CoordException("Server error while waiting: " + decodeError(response));
                } else {
                    log.debug("Ignoring {} while waiting for PEER_INFO", response.type());
                }
            } catch (SocketTimeoutException e) {
                // Expected (5s recv timeout): fall through to the keepalive check.
            }
            long now = System.currentTimeMillis();
            if (now - lastKeepalive >= KEEPALIVE_INTERVAL_MS) {
                try {
                    sendKeepalive();
                    log.debug("Sent COORD_KEEPALIVE for session '{}'", sessionId);
                } catch (CoordException e) {
                    log.warn("Coord keepalive send failed: {}", e.getMessage());
                }
                lastKeepalive = now;
            }
        }
        throw new CoordException("Timed out waiting for peer (" + (peerWaitMs / 1000) + "s)");
    }

    private void sendKeepalive() throws CoordException {
        send(new Packet(PacketType.COORD_KEEPALIVE, new byte[0]));
    }

    private void handlePeerInfo(Packet packet) {
        byte[] p = packet.payload();
        remoteEndpoint = CoordServer.decodeEndpoint(p, 0);
        int off = CoordServer.encodedEndpointLength(p, 0);
        if (p.length > off) {
            try {
                remoteLocalEndpoint = CoordServer.decodeEndpoint(p, off);
            } catch (RuntimeException e) {
                log.debug("Ignoring malformed remote local endpoint: {}", e.getMessage());
            }
        }
        log.info("Received PEER_INFO: remote public = {}, local = {}", remoteEndpoint, remoteLocalEndpoint);
    }

    private void send(Packet packet) throws CoordException {
        try {
            byte[] data = PacketCodec.encode(packet);
            socket.send(new DatagramPacket(data, data.length, serverAddr.getAddress(), serverAddr.getPort()));
        } catch (IOException e) {
            throw new CoordException("Send failed: " + e.getMessage(), e);
        }
    }

    private Packet receive() throws SocketTimeoutException, CoordException {
        byte[] buf = new byte[Packet.MAX_DATAGRAM];
        DatagramPacket dgram = new DatagramPacket(buf, buf.length);
        try {
            socket.receive(dgram);
            return PacketCodec.decode(buf, dgram.getLength());
        } catch (SocketTimeoutException e) {
            throw e;
        } catch (IOException | PacketException e) {
            throw new CoordException("Receive failed: " + e.getMessage(), e);
        }
    }

    private String decodeError(Packet errorPacket) {
        byte[] payload = errorPacket.payload();
        if (payload.length < 2) return "(empty error)";
        int code = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
        String msg = new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8);
        return String.format("0x%04X: %s", code, msg);
    }

    /**
     * Exception for coordination failures.
     */
    public static class CoordException extends Exception {
        public CoordException(String message) { super(message); }
        public CoordException(String message, Throwable cause) { super(message, cause); }
    }
}

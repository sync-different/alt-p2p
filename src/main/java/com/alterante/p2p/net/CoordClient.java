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

    private final DatagramSocket socket;
    private final InetSocketAddress serverAddr;
    private final String sessionId;
    private final String psk;

    private Runnable onWaitingForPeer;
    private InetSocketAddress myPublicEndpoint;
    private InetSocketAddress remoteEndpoint;

    public CoordClient(DatagramSocket socket, InetSocketAddress serverAddr,
                       String sessionId, String psk) {
        this.socket = socket;
        this.serverAddr = serverAddr;
        this.sessionId = sessionId;
        this.psk = psk;
    }

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
        byte[] payload = new byte[2 + idBytes.length + 32];
        ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                .putShort((short) idBytes.length)
                .put(idBytes)
                .put(hmac);

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

        log.info("Waiting for peer to join session '{}'...", sessionId);
        if (onWaitingForPeer != null) onWaitingForPeer.run();

        // Longer timeout for waiting: the other peer might not have connected yet.
        // We'll poll with the existing timeout, up to 120 seconds total.
        long deadline = System.currentTimeMillis() + 120_000;

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
                // Expected: just keep waiting
            }
        }
        throw new CoordException("Timed out waiting for peer (120s)");
    }

    private void handlePeerInfo(Packet packet) {
        remoteEndpoint = CoordServer.decodeEndpoint(packet.payload(), 0);
        log.info("Received PEER_INFO: remote endpoint = {}", remoteEndpoint);
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

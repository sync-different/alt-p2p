package com.alterante.p2p.net;

import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketCodec;
import com.alterante.p2p.protocol.PacketException;
import com.alterante.p2p.protocol.PacketType;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * DTLS 1.2 encryption layer using BouncyCastle with PSK (Pre-Shared Key).
 *
 * After hole punch succeeds, one peer acts as DTLS client and the other as DTLS server.
 * Role is determined deterministically: the peer whose local port is lower becomes the client.
 *
 * The PSK identity is the session ID, and the PSK value is derived from the shared secret.
 */
public class DtlsHandler {

    private static final Logger log = LoggerFactory.getLogger(DtlsHandler.class);
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteEndpoint;
    private final String sessionId;
    private final byte[] pskValue;
    private final boolean isClient;
    private final int handshakeTimeoutMs;

    private UdpDatagramTransport rawTransport;
    private DTLSTransport dtlsTransport;

    public DtlsHandler(DatagramSocket socket, InetSocketAddress remoteEndpoint,
                       String sessionId, String psk, boolean isClient) {
        this(socket, remoteEndpoint, sessionId, psk, isClient, DEFAULT_HANDSHAKE_TIMEOUT_MS);
    }

    public DtlsHandler(DatagramSocket socket, InetSocketAddress remoteEndpoint,
                       String sessionId, String psk, boolean isClient, int handshakeTimeoutMs) {
        this.socket = socket;
        this.remoteEndpoint = remoteEndpoint;
        this.sessionId = sessionId;
        this.pskValue = psk.getBytes(StandardCharsets.UTF_8);
        this.isClient = isClient;
        this.handshakeTimeoutMs = handshakeTimeoutMs;
    }

    /**
     * Perform the DTLS handshake. Blocks until complete or fails.
     */
    public void handshake() throws IOException {
        SecureRandom secureRandom = new SecureRandom();
        BcTlsCrypto crypto = new BcTlsCrypto(secureRandom);

        rawTransport = new UdpDatagramTransport(socket, remoteEndpoint, handshakeTimeoutMs);
        if (pendingRelayServer != null) {
            rawTransport.enableRelay(pendingRelayServer);
        }

        byte[] identity = sessionId.getBytes(StandardCharsets.UTF_8);

        if (isClient) {
            log.info("Starting DTLS handshake as CLIENT");
            BasicTlsPSKIdentity pskIdentity = new BasicTlsPSKIdentity(identity, pskValue);
            PSKTlsClient client = new PSKTlsClient(crypto, pskIdentity) {
                @Override
                protected ProtocolVersion[] getSupportedVersions() {
                    return ProtocolVersion.DTLSv12.only();
                }
            };
            DTLSClientProtocol clientProtocol = new DTLSClientProtocol();
            dtlsTransport = clientProtocol.connect(client, rawTransport);
        } else {
            log.info("Starting DTLS handshake as SERVER");
            SimplePskIdentityManager mgr = new SimplePskIdentityManager(sessionId, pskValue);
            PSKTlsServer server = new PSKTlsServer(crypto, mgr) {
                @Override
                protected ProtocolVersion[] getSupportedVersions() {
                    return ProtocolVersion.DTLSv12.only();
                }
            };
            DTLSServerProtocol serverProtocol = new DTLSServerProtocol();
            dtlsTransport = serverProtocol.accept(server, rawTransport);
        }

        rawTransport.clearDeadline();
        log.info("DTLS handshake complete. Encrypted channel established.");
    }

    /**
     * Send encrypted data to the remote peer.
     */
    public void send(byte[] data, int offset, int length) throws IOException {
        dtlsTransport.send(data, offset, length);
    }

    /**
     * Receive decrypted data from the remote peer.
     * @return number of bytes received, or -1 on timeout
     */
    public int receive(byte[] buf, int offset, int length, int timeoutMs) throws IOException {
        rawTransport.setWaitMillis(timeoutMs);
        try {
            return dtlsTransport.receive(buf, offset, length, timeoutMs);
        } catch (SocketTimeoutException e) {
            return -1;
        }
    }

    public void close() {
        if (dtlsTransport != null) {
            try {
                dtlsTransport.close();
            } catch (IOException e) {
                log.debug("Error closing DTLS transport: {}", e.getMessage());
            }
        }
    }

    public DTLSTransport transport() {
        return dtlsTransport;
    }

    /**
     * Enable relay mode: all DTLS traffic is wrapped in COORD_RELAY and sent
     * through the coordination server instead of directly to the peer.
     * Must be called BEFORE handshake().
     */
    public void enableRelay(InetSocketAddress coordServer) {
        if (rawTransport != null) {
            rawTransport.enableRelay(coordServer);
        } else {
            // Transport not created yet — store for later.
            // handshake() creates rawTransport, so we enable relay there.
            this.pendingRelayServer = coordServer;
        }
    }

    private InetSocketAddress pendingRelayServer;

    // --- Inner classes ---

    /**
     * Adapts a DatagramSocket to BouncyCastle's DatagramTransport interface,
     * scoped to a specific remote endpoint.
     *
     * Supports two modes:
     * - Direct mode: sends/receives UDP to/from the remote peer directly.
     * - Relay mode: wraps outbound bytes in COORD_RELAY packets sent to the coord server,
     *   and unwraps inbound COORD_RELAY packets from the coord server.
     */
    static class UdpDatagramTransport implements DatagramTransport {

        private static final Logger tlog = LoggerFactory.getLogger(UdpDatagramTransport.class);

        private final DatagramSocket socket;
        private final InetSocketAddress remote; // peer endpoint (for direct mode filtering)
        private int waitMillis;
        private long handshakeDeadline; // 0 = no deadline (post-handshake)

        // Relay mode fields
        private volatile boolean relayMode;
        private InetSocketAddress relayServer; // coord server to relay through

        UdpDatagramTransport(DatagramSocket socket, InetSocketAddress remote, int initialTimeoutMs) {
            this.socket = socket;
            this.remote = remote;
            this.waitMillis = initialTimeoutMs;
            this.handshakeDeadline = System.currentTimeMillis() + initialTimeoutMs;
        }

        /** Enable relay mode: all traffic goes through the coord server wrapped in COORD_RELAY. */
        void enableRelay(InetSocketAddress coordServer) {
            this.relayServer = coordServer;
            this.relayMode = true;
            tlog.debug("Relay mode enabled via {}", coordServer);
        }

        void setWaitMillis(int ms) {
            this.waitMillis = ms;
        }

        /** Clear the handshake deadline (call after handshake succeeds). */
        void clearDeadline() {
            this.handshakeDeadline = 0;
        }

        @Override
        public int getReceiveLimit() {
            // In relay mode, the COORD_RELAY wrapper adds 20 bytes of packet header,
            // so the effective payload for DTLS is smaller.
            return relayMode ? 1180 - Packet.HEADER_SIZE : 1200;
        }

        @Override
        public int getSendLimit() {
            return relayMode ? 1180 - Packet.HEADER_SIZE : 1200;
        }

        @Override
        public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
            // Enforce handshake deadline — BouncyCastle retries indefinitely on SocketTimeoutException,
            // so we must throw a hard IOException to abort the handshake when time is up.
            if (handshakeDeadline > 0 && System.currentTimeMillis() > handshakeDeadline) {
                throw new IOException("DTLS handshake deadline exceeded");
            }

            int effectiveTimeout = waitMillis > 0 ? waitMillis : this.waitMillis;
            try {
                socket.setSoTimeout(effectiveTimeout);
            } catch (SocketException e) {
                throw new IOException("Failed to set timeout", e);
            }

            byte[] recvBuf = new byte[Packet.MAX_DATAGRAM];
            DatagramPacket dgram = new DatagramPacket(recvBuf, recvBuf.length);

            while (true) {
                try {
                    socket.receive(dgram);
                } catch (SocketTimeoutException e) {
                    // SocketTimeoutException extends InterruptedIOException,
                    // which DTLSTransport.receive() handles gracefully (rethrows without sending fatal alert).
                    // Do NOT wrap in TlsTimeoutException — it extends IOException, and DTLSTransport's
                    // IOException catch block calls recordLayer.fail(80) which kills the connection.
                    tlog.trace("Receive timeout ({}ms)", effectiveTimeout);
                    throw e;
                }

                InetSocketAddress from = new InetSocketAddress(dgram.getAddress(), dgram.getPort());

                if (relayMode) {
                    // In relay mode, accept COORD_RELAY packets from the coord server
                    if (!from.equals(relayServer)) {
                        tlog.debug("Relay mode: ignoring packet from {} (expected relay server {})", from, relayServer);
                        continue;
                    }
                    // Unwrap COORD_RELAY
                    try {
                        Packet relayPkt = PacketCodec.decode(recvBuf, dgram.getLength());
                        if (relayPkt.type() != PacketType.COORD_RELAY) {
                            tlog.debug("Relay mode: ignoring non-relay packet type {}", relayPkt.type());
                            continue;
                        }
                        byte[] inner = relayPkt.payload();
                        int copyLen = Math.min(len, inner.length);
                        System.arraycopy(inner, 0, buf, off, copyLen);
                        tlog.debug("Relay received {} bytes (unwrapped from COORD_RELAY)", copyLen);
                        return copyLen;
                    } catch (PacketException e) {
                        tlog.debug("Relay mode: bad packet from server: {}", e.getMessage());
                        continue;
                    }
                } else {
                    // Direct mode: accept packets from the remote peer only
                    if (!from.equals(remote)) {
                        tlog.debug("Ignoring packet from {} (expected {})", from, remote);
                        continue;
                    }

                    // Filter out non-DTLS packets (stale PUNCH/PUNCH_ACK, keepalive bytes).
                    // DTLS content types: 0x14=ChangeCipherSpec, 0x15=Alert, 0x16=Handshake, 0x17=AppData
                    if (dgram.getLength() > 0) {
                        int firstByte = recvBuf[0] & 0xFF;
                        if (firstByte < 0x14 || firstByte > 0x17) {
                            tlog.debug("Filtering non-DTLS packet (first byte: 0x{}, {} bytes)",
                                    String.format("%02X", firstByte), dgram.getLength());
                            continue;
                        }
                    }

                    int copyLen = Math.min(len, dgram.getLength());
                    tlog.debug("Received {} bytes from {} (first byte: 0x{})",
                            copyLen, from, String.format("%02X", recvBuf[0] & 0xFF));
                    System.arraycopy(recvBuf, 0, buf, off, copyLen);
                    return copyLen;
                }
            }
        }

        @Override
        public void send(byte[] buf, int off, int len) throws IOException {
            if (relayMode) {
                // Wrap in COORD_RELAY and send to coord server
                byte[] inner = new byte[len];
                System.arraycopy(buf, off, inner, 0, len);
                byte[] relayData = PacketCodec.encode(new Packet(PacketType.COORD_RELAY, inner));
                tlog.debug("Relay sending {} bytes (wrapped in COORD_RELAY, {} total) to {}",
                        len, relayData.length, relayServer);
                DatagramPacket dgram = new DatagramPacket(relayData, relayData.length,
                        relayServer.getAddress(), relayServer.getPort());
                socket.send(dgram);
            } else {
                tlog.debug("Sending {} bytes to {} (first byte: 0x{})",
                        len, remote, String.format("%02X", buf[off] & 0xFF));
                DatagramPacket dgram = new DatagramPacket(buf, off, len, remote.getAddress(), remote.getPort());
                socket.send(dgram);
            }
        }

        @Override
        public void close() {
            // Don't close the socket — it's managed by PeerConnection
        }
    }

    /**
     * Simple PSK identity manager that accepts a single identity/key pair.
     */
    static class SimplePskIdentityManager implements TlsPSKIdentityManager {

        private final byte[] expectedIdentity;
        private final byte[] key;

        SimplePskIdentityManager(String identity, byte[] key) {
            this.expectedIdentity = identity.getBytes(StandardCharsets.UTF_8);
            this.key = key;
        }

        @Override
        public byte[] getHint() {
            return expectedIdentity;
        }

        @Override
        public byte[] getPSK(byte[] identity) {
            if (Arrays.equals(identity, expectedIdentity)) {
                return key;
            }
            return null;
        }
    }
}

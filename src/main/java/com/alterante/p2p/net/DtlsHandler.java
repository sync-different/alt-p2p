package com.alterante.p2p.net;

import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final int HANDSHAKE_TIMEOUT_MS = 30_000;

    private final DatagramSocket socket;
    private final InetSocketAddress remoteEndpoint;
    private final String sessionId;
    private final byte[] pskValue;
    private final boolean isClient;

    private UdpDatagramTransport rawTransport;
    private DTLSTransport dtlsTransport;

    /**
     * @param socket        the UDP socket (already hole-punched)
     * @param remoteEndpoint the remote peer's confirmed address
     * @param sessionId     used as PSK identity
     * @param psk           the pre-shared key string
     * @param isClient      true if this peer should act as DTLS client
     */
    public DtlsHandler(DatagramSocket socket, InetSocketAddress remoteEndpoint,
                       String sessionId, String psk, boolean isClient) {
        this.socket = socket;
        this.remoteEndpoint = remoteEndpoint;
        this.sessionId = sessionId;
        this.pskValue = psk.getBytes(StandardCharsets.UTF_8);
        this.isClient = isClient;
    }

    /**
     * Perform the DTLS handshake. Blocks until complete or fails.
     */
    public void handshake() throws IOException {
        SecureRandom secureRandom = new SecureRandom();
        BcTlsCrypto crypto = new BcTlsCrypto(secureRandom);

        rawTransport = new UdpDatagramTransport(socket, remoteEndpoint, HANDSHAKE_TIMEOUT_MS);

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

    // --- Inner classes ---

    /**
     * Adapts a DatagramSocket to BouncyCastle's DatagramTransport interface,
     * scoped to a specific remote endpoint.
     */
    static class UdpDatagramTransport implements DatagramTransport {

        private static final Logger tlog = LoggerFactory.getLogger(UdpDatagramTransport.class);

        private final DatagramSocket socket;
        private final InetSocketAddress remote;
        private int waitMillis;
        private long handshakeDeadline; // 0 = no deadline (post-handshake)

        UdpDatagramTransport(DatagramSocket socket, InetSocketAddress remote, int initialTimeoutMs) {
            this.socket = socket;
            this.remote = remote;
            this.waitMillis = initialTimeoutMs;
            this.handshakeDeadline = System.currentTimeMillis() + initialTimeoutMs;
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
            return 1200;
        }

        @Override
        public int getSendLimit() {
            return 1200;
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

            byte[] recvBuf = new byte[getReceiveLimit()];
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

                // Filter: only accept packets from our remote peer
                InetSocketAddress from = new InetSocketAddress(dgram.getAddress(), dgram.getPort());
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

        @Override
        public void send(byte[] buf, int off, int len) throws IOException {
            tlog.debug("Sending {} bytes to {} (first byte: 0x{})",
                    len, remote, String.format("%02X", buf[off] & 0xFF));
            DatagramPacket dgram = new DatagramPacket(buf, off, len, remote.getAddress(), remote.getPort());
            socket.send(dgram);
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

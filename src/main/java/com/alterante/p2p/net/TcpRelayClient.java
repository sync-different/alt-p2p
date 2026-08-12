package com.alterante.p2p.net;

import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Client-side TCP relay connection.
 *
 * Connects to the TcpRelayServer, authenticates with session ID + HMAC,
 * then performs a TLS-PSK handshake through the proxy for E2E encryption.
 * Returns the TLS-wrapped I/O streams for file transfer.
 */
public class TcpRelayClient {

    private static final Logger log = LoggerFactory.getLogger(TcpRelayClient.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int AUTH_TIMEOUT_MS = 30_000;
    /**
     * Cap on the TLS-PSK handshake. Generous — the handshake crosses the relay twice — but finite,
     * so a partner that authenticates and then goes silent cannot pin this thread forever.
     */
    private static final int HANDSHAKE_TIMEOUT_MS = 60_000;

    private int handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS;

    private final InetSocketAddress serverAddr;
    private final String sessionId;
    private final String psk;
    private final boolean isTlsClient;

    private Socket socket;
    private TlsClientProtocol tlsClientProtocol;
    private TlsServerProtocol tlsServerProtocol;
    private InputStream tlsInputStream;
    private OutputStream tlsOutputStream;

    /**
     * @param serverAddr TCP relay server address (host:port)
     * @param sessionId  session ID from UDP coordination
     * @param psk        pre-shared key
     * @param isTlsClient true if this peer should be the TLS client (determined by compareEndpoints)
     */
    public TcpRelayClient(InetSocketAddress serverAddr, String sessionId, String psk, boolean isTlsClient) {
        this.serverAddr = serverAddr;
        this.sessionId = sessionId;
        this.psk = psk;
        this.isTlsClient = isTlsClient;
    }

    /**
     * Connect to the TCP relay server, authenticate, and perform TLS handshake.
     * Blocks until the encrypted channel is established.
     */
    public void connect() throws IOException {
        // 1. TCP connect
        socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(serverAddr, CONNECT_TIMEOUT_MS);
            log.info("TCP relay: connected to {}", serverAddr);

            socket.setSoTimeout(AUTH_TIMEOUT_MS);
            InputStream rawIn = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();

            // 2. Send AUTH
            byte[] hmac = CoordServer.computeHmac(psk, TcpRelayServer.TCP_RELAY_NONCE, sessionId);
            byte[] authPayload = TcpRelayProtocol.encodeAuth(sessionId, hmac);
            TcpRelayProtocol.writeMessage(rawOut, TcpRelayProtocol.MSG_AUTH, authPayload);
            log.info("TCP relay: sent AUTH for session '{}'", sessionId);

            // 3. Wait for AUTH_OK
            TcpRelayProtocol.Message response = TcpRelayProtocol.readMessage(rawIn);
            if (response.type() == TcpRelayProtocol.MSG_AUTH_FAIL) {
                String error = new String(response.payload(), StandardCharsets.UTF_8);
                throw new IOException("TCP relay auth failed: " + error);
            }
            if (response.type() != TcpRelayProtocol.MSG_AUTH_OK) {
                throw new IOException("Unexpected response type: 0x" + String.format("%02X", response.type()));
            }
            log.info("TCP relay: authenticated, starting TLS handshake as {}", isTlsClient ? "CLIENT" : "SERVER");

            // 4. TLS-PSK handshake through the proxy.
            //
            // Bounded, unlike DTLS. This used to run with no timeout at all, which assumed the far
            // side always either speaks or disconnects. It does not: the relay splices us to
            // whatever else claimed this session, and a partner that connected and then went quiet
            // — a wedged process, a half-open path — leaves us blocked in a read forever. A host
            // that hangs here never returns to waiting for peers, so it is lost until restarted.
            socket.setSoTimeout(handshakeTimeoutMs);
            performTlsHandshake(rawIn, rawOut);

            // The data path is blocking by design; only the handshake is bounded.
            socket.setSoTimeout(0);
            log.info("TCP relay: TLS handshake complete. Encrypted channel established.");
        } catch (IOException | RuntimeException e) {
            // Close our own socket before unwinding. The caller never receives this object on a
            // failed connect, so nothing else can close it — and this is the retried path, so a
            // leak here accumulates one file descriptor per attempt. The same mistake on the UDP
            // side cost a waiting host 758 sockets.
            log.debug("TCP relay connect failed ({}); closing socket", e.getMessage());
            close();
            throw e;
        }
    }

    private void performTlsHandshake(InputStream rawIn, OutputStream rawOut) throws IOException {
        SecureRandom secureRandom = new SecureRandom();
        BcTlsCrypto crypto = new BcTlsCrypto(secureRandom);
        byte[] identity = sessionId.getBytes(StandardCharsets.UTF_8);
        byte[] pskValue = psk.getBytes(StandardCharsets.UTF_8);

        if (isTlsClient) {
            BasicTlsPSKIdentity pskIdentity = new BasicTlsPSKIdentity(identity, pskValue);
            PSKTlsClient client = new PSKTlsClient(crypto, pskIdentity) {
                @Override
                protected ProtocolVersion[] getSupportedVersions() {
                    return ProtocolVersion.TLSv12.only();
                }
            };
            tlsClientProtocol = new TlsClientProtocol(rawIn, rawOut);
            tlsClientProtocol.connect(client);
            tlsInputStream = tlsClientProtocol.getInputStream();
            tlsOutputStream = tlsClientProtocol.getOutputStream();
        } else {
            TlsPSKIdentityManager mgr = new TlsPSKIdentityManager() {
                @Override
                public byte[] getHint() { return identity; }
                @Override
                public byte[] getPSK(byte[] id) {
                    return Arrays.equals(id, identity) ? pskValue : null;
                }
            };
            PSKTlsServer server = new PSKTlsServer(crypto, mgr) {
                @Override
                protected ProtocolVersion[] getSupportedVersions() {
                    return ProtocolVersion.TLSv12.only();
                }
            };
            tlsServerProtocol = new TlsServerProtocol(rawIn, rawOut);
            tlsServerProtocol.accept(server);
            tlsInputStream = tlsServerProtocol.getInputStream();
            tlsOutputStream = tlsServerProtocol.getOutputStream();
        }
    }

    public InputStream inputStream() { return tlsInputStream; }
    public OutputStream outputStream() { return tlsOutputStream; }

    /** Override the TLS handshake cap. Mainly for tests that must not wait a minute to prove a bound. */
    public void setHandshakeTimeoutMs(int ms) { this.handshakeTimeoutMs = ms; }

    /** True once the underlying socket is closed (or was never opened). */
    public boolean isClosed() { return socket == null || socket.isClosed(); }

    public void close() {
        try {
            if (tlsClientProtocol != null) tlsClientProtocol.close();
            if (tlsServerProtocol != null) tlsServerProtocol.close();
        } catch (IOException e) {
            log.debug("Error closing TLS protocol: {}", e.getMessage());
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            log.debug("Error closing TCP socket: {}", e.getMessage());
        }
    }
}

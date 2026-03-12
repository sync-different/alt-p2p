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

        // 4. TLS-PSK handshake through the proxy
        socket.setSoTimeout(0); // TLS handshake manages its own timeouts
        performTlsHandshake(rawIn, rawOut);
        log.info("TCP relay: TLS handshake complete. Encrypted channel established.");
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

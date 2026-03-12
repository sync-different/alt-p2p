package com.alterante.p2p.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP relay server that pairs authenticated peers and splices their streams.
 *
 * After UDP coordination, peers that need relay connect here via TCP.
 * Each peer sends an AUTH message (session ID + HMAC). When both peers
 * in a session are connected and authenticated, the server becomes a
 * transparent bidirectional byte-copy proxy. Peers then perform a TLS-PSK
 * handshake through the proxy for end-to-end encryption.
 */
public class TcpRelayServer {

    private static final Logger log = LoggerFactory.getLogger(TcpRelayServer.class);

    /** Fixed nonce used for TCP relay HMAC (distinct from UDP coordination nonce). */
    static final byte[] TCP_RELAY_NONCE = "tcp-relay".getBytes(StandardCharsets.UTF_8);

    private static final int SPLICE_BUFFER_SIZE = 65536;
    private static final int AUTH_TIMEOUT_MS = 30_000;
    private static final int PAIR_TIMEOUT_MS = 60_000;

    private final int port;
    private final String psk;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    /** Pending sessions: sessionId → first peer's socket (waiting for second peer). */
    private final Map<String, PendingPeer> pendingPeers = new ConcurrentHashMap<>();

    private record PendingPeer(Socket socket, long connectedAt) {}

    public TcpRelayServer(int port, String psk) {
        this.port = port;
        this.psk = psk;
    }

    /**
     * Start the TCP relay server. Blocks until stopped.
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running.set(true);
        log.info("TCP relay server listening on port {}", port);

        // Background thread to clean up timed-out pending peers
        Thread cleaner = new Thread(this::cleanPendingPeers, "tcp-relay-cleaner");
        cleaner.setDaemon(true);
        cleaner.start();

        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                Thread handler = new Thread(() -> handleConnection(client), "tcp-relay-" + client.getRemoteSocketAddress());
                handler.setDaemon(true);
                handler.start();
            } catch (SocketException e) {
                if (running.get()) {
                    log.error("Accept failed: {}", e.getMessage());
                }
            }
        }

        log.info("TCP relay server stopped");
    }

    public void stop() {
        running.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log.debug("Error closing server socket: {}", e.getMessage());
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    private void handleConnection(Socket client) {
        try {
            client.setSoTimeout(AUTH_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Read AUTH message
            TcpRelayProtocol.Message msg = TcpRelayProtocol.readMessage(in);
            if (msg.type() != TcpRelayProtocol.MSG_AUTH) {
                log.warn("Expected AUTH from {}, got type 0x{}", client.getRemoteSocketAddress(),
                        String.format("%02X", msg.type()));
                TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_AUTH_FAIL,
                        "Expected AUTH message".getBytes(StandardCharsets.UTF_8));
                client.close();
                return;
            }

            // Decode AUTH
            Object[] authData = TcpRelayProtocol.decodeAuth(msg.payload());
            String sessionId = (String) authData[0];
            byte[] receivedHmac = (byte[]) authData[1];

            // Verify HMAC
            byte[] expectedHmac = CoordServer.computeHmac(psk, TCP_RELAY_NONCE, sessionId);
            if (!MessageDigest.isEqual(receivedHmac, expectedHmac)) {
                log.warn("TCP relay AUTH failed from {} for session '{}'", client.getRemoteSocketAddress(), sessionId);
                TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_AUTH_FAIL,
                        "Authentication failed".getBytes(StandardCharsets.UTF_8));
                client.close();
                return;
            }

            log.info("TCP relay AUTH success from {} for session '{}'", client.getRemoteSocketAddress(), sessionId);

            // Try to pair with an existing pending peer
            PendingPeer existing = pendingPeers.remove(sessionId);
            if (existing != null && !existing.socket.isClosed()) {
                // Both peers present — start splice
                log.info("TCP relay session '{}': both peers connected, starting splice", sessionId);
                sendAuthOk(client);
                sendAuthOk(existing.socket);

                // Clear the timeout now that we're splicing
                client.setSoTimeout(0);
                existing.socket.setSoTimeout(0);

                startSplice(sessionId, existing.socket, client);
            } else {
                // First peer — wait for second
                log.info("TCP relay session '{}': waiting for peer", sessionId);
                pendingPeers.put(sessionId, new PendingPeer(client, System.currentTimeMillis()));
            }

        } catch (Exception e) {
            log.debug("TCP relay connection error from {}: {}", client.getRemoteSocketAddress(), e.getMessage());
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void sendAuthOk(Socket socket) throws IOException {
        TcpRelayProtocol.writeMessage(socket.getOutputStream(), TcpRelayProtocol.MSG_AUTH_OK);
    }

    private void startSplice(String sessionId, Socket peerA, Socket peerB) {
        Thread aToB = new Thread(() -> splice(sessionId, "A→B", peerA, peerB), "splice-" + sessionId + "-AtoB");
        Thread bToA = new Thread(() -> splice(sessionId, "B→A", peerB, peerA), "splice-" + sessionId + "-BtoA");
        aToB.setDaemon(true);
        bToA.setDaemon(true);
        aToB.start();
        bToA.start();
    }

    private void splice(String sessionId, String direction, Socket from, Socket to) {
        byte[] buf = new byte[SPLICE_BUFFER_SIZE];
        long totalBytes = 0;
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                totalBytes += n;
            }
        } catch (IOException e) {
            // Expected when one side closes
        } finally {
            log.info("TCP relay splice {} session '{}' ended ({} bytes)", direction, sessionId, totalBytes);
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void cleanPendingPeers() {
        while (running.get()) {
            try {
                Thread.sleep(5000);
                long now = System.currentTimeMillis();
                pendingPeers.entrySet().removeIf(entry -> {
                    if (now - entry.getValue().connectedAt > PAIR_TIMEOUT_MS) {
                        log.debug("TCP relay: pending peer for session '{}' timed out", entry.getKey());
                        closeQuietly(entry.getValue().socket);
                        return true;
                    }
                    return false;
                });
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (!socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}

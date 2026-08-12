package com.alterante.p2p.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.PushbackInputStream;
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
    // How long a first-arriving peer is held waiting for its partner. Both peers normally reach the
    // relay within seconds of the coordinator's PEER_INFO, so 60s was far longer than any real gap and
    // left a wide window in which a dead socket could still be paired (observed 2026-08-10: a peer
    // parked at 19:04:40 was spliced to a live arrival 44s later, producing a 0-byte splice and a
    // handshake_failure(40) on the live side).
    private static final int PAIR_TIMEOUT_MS = 30_000;

    private final int port;
    private final String psk;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;

    /** Pending sessions: sessionId → first peer's socket (waiting for second peer). */
    private final Map<String, PendingPeer> pendingPeers = new ConcurrentHashMap<>();

    /**
     * A peer waiting for its partner. The {@link PushbackInputStream} exists so the liveness probe can
     * peek for EOF and put back anything it accidentally reads — the same stream is then handed to the
     * splice, so no byte can be lost between probing and splicing.
     */
    private record PendingPeer(Socket socket, PushbackInputStream in, long connectedAt) {}

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

            // Decide atomically whether to pair with a parked peer or become the parked peer.
            //
            // This was `remove()` followed by `put()`, which is not atomic even on a
            // ConcurrentHashMap. Two peers that authenticate before either parks both saw an empty
            // slot, so both parked — and the second put **silently overwrote** the first, leaving
            // that peer referenced by nothing: unreachable by the pairing path and invisible to the
            // reaper, which scans this map. It waited out its 30s client timeout ("Read timed out")
            // while its socket leaked here.
            //
            // Rare in production only because the failed hole punch delays arrivals by ~10s.
            // `--force-relay` skips the punch, so both peers arrive together and hit it every time:
            // scripts/loopback.sh RELAY=1 failed 3/3 before this fix and 2/2 on 0.7.0.
            //
            // compute() is atomic per key. The liveness probe runs inside it and briefly holds that
            // key's bin lock, which is acceptable at a bounded 5ms; everything slower — AUTH_OK,
            // the splice — happens after it returns.
            PendingPeer mine = new PendingPeer(client,
                    new PushbackInputStream(client.getInputStream(), 1), System.currentTimeMillis());
            PendingPeer[] partner = new PendingPeer[1];
            PendingPeer[] stale = new PendingPeer[1];

            pendingPeers.compute(sessionId, (key, parked) -> {
                if (parked == null) {
                    return mine;                    // first arrival — park and wait
                }
                if (!isPeerAlive(parked)) {
                    stale[0] = parked;              // corpse; replace it rather than splice to it
                    return mine;
                }
                partner[0] = parked;                // live partner — take it and clear the slot
                return null;
            });

            if (stale[0] != null) {
                // THE BUG THIS GUARD EXISTS FOR. `isClosed()` alone is not a liveness test -- it is
                // true only when WE closed the socket locally, so a peer whose process is gone still
                // passes it. Splicing to that corpse yields 0 bytes in both directions and the live
                // side fails with TLS handshake_failure(40) waiting for a hello nobody will send.
                log.warn("TCP relay session '{}': discarding STALE parked peer {} (parked {}ms, no "
                        + "longer alive); {} will wait for a fresh partner instead",
                        sessionId, stale[0].socket().getRemoteSocketAddress(),
                        System.currentTimeMillis() - stale[0].connectedAt(),
                        client.getRemoteSocketAddress());
                closeQuietly(stale[0].socket());
            }

            PendingPeer existing = partner[0];
            if (existing != null) {
                long parkedMs = System.currentTimeMillis() - existing.connectedAt();
                log.info("TCP relay session '{}': both peers connected, starting splice "
                        + "({} <-> {}, first peer waited {}ms)", sessionId,
                        existing.socket().getRemoteSocketAddress(),
                        client.getRemoteSocketAddress(), parkedMs);
                sendAuthOk(client);
                sendAuthOk(existing.socket());

                // Clear the timeout now that we're splicing
                client.setSoTimeout(0);
                existing.socket().setSoTimeout(0);

                // Reuse `mine.in()` rather than wrapping the stream again: the probe may have pushed
                // a byte back into that exact PushbackInputStream, and a second wrapper would not
                // see it. Handing the splice a different stream is how a byte goes missing.
                startSplice(sessionId, existing.socket(), existing.in(), client, mine.in());
            } else {
                // We are parked (compute() installed `mine`) — the partner will splice us.
                log.info("TCP relay session '{}': {} waiting for peer (held up to {}s)",
                        sessionId, client.getRemoteSocketAddress(), PAIR_TIMEOUT_MS / 1000);
            }

        } catch (Exception e) {
            log.debug("TCP relay connection error from {}: {}", client.getRemoteSocketAddress(), e.getMessage());
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Best-effort liveness probe for a parked peer, run immediately before splicing.
     *
     * <p>The cheap flags catch a locally-closed or half-shutdown socket. The urgent-data probe catches
     * the case that matters: the kernel already knows the connection is dead (it saw a RST), while
     * {@code isClosed()} still reports false. One byte of OOB data is discarded by the receiver's TCP
     * stack under the JDK default {@code SO_OOBINLINE=false} — which is what {@code TcpRelayClient}
     * uses — so this cannot corrupt the stream that is about to be spliced.
     *
     * <p><b>Limits:</b> a peer that vanished without any FIN or RST (host powered off, network
     * partition) still looks alive here. {@link #PAIR_TIMEOUT_MS} is the backstop for that, which is
     * why it was shortened rather than relying on this probe alone.
     */
    private static boolean isPeerAlive(PendingPeer p) {
        Socket s = p.socket();
        if (s == null || s.isClosed() || !s.isConnected() || s.isInputShutdown()) {
            return false;
        }
        int previousTimeout;
        try {
            previousTimeout = s.getSoTimeout();
        } catch (IOException e) {
            return false;
        }
        try {
            // A short read is the only probe that detects the case that actually happens: the peer
            // closed, we hold a half-open socket, and every cheap flag still says "fine".
            //   isClosed()        - false, WE did not close it
            //   isInputShutdown() - false, that is OUR shutdown, not the peer's
            //   sendUrgentData()  - succeeds, a half-closed socket may still SEND
            // Reading is safe here because the relay protocol makes a parked peer silent: it has sent
            // AUTH and must wait for AUTH_OK, which is only sent once paired. Anything readable would
            // be a protocol violation -- so it is pushed back rather than dropped.
            s.setSoTimeout(5);
            int b = p.in().read();
            if (b == -1) {
                return false;                 // FIN seen: the peer is gone
            }
            p.in().unread(b);
            log.debug("TCP relay: parked peer {} sent data before pairing (unexpected but harmless)",
                    s.getRemoteSocketAddress());
            return true;
        } catch (SocketTimeoutException e) {
            return true;                      // open and quiet: alive
        } catch (IOException e) {
            log.debug("TCP relay: liveness probe failed for {}: {}", s.getRemoteSocketAddress(), e.getMessage());
            return false;                     // RST and friends
        } finally {
            try { s.setSoTimeout(previousTimeout); } catch (IOException ignored) { }
        }
    }

    private void sendAuthOk(Socket socket) throws IOException {
        TcpRelayProtocol.writeMessage(socket.getOutputStream(), TcpRelayProtocol.MSG_AUTH_OK);
    }

    private void startSplice(String sessionId, Socket peerA, InputStream inA, Socket peerB, InputStream inB) {
        Thread aToB = new Thread(() -> splice(sessionId, "A→B", peerA, inA, peerB), "splice-" + sessionId + "-AtoB");
        Thread bToA = new Thread(() -> splice(sessionId, "B→A", peerB, inB, peerA), "splice-" + sessionId + "-BtoA");
        aToB.setDaemon(true);
        bToA.setDaemon(true);
        aToB.start();
        bToA.start();
    }

    private void splice(String sessionId, String direction, Socket from, InputStream in, Socket to) {
        byte[] buf = new byte[SPLICE_BUFFER_SIZE];
        long totalBytes = 0;
        try {
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
            if (totalBytes == 0) {
                // The signature of a corpse pairing: spliced, but the two sides never exchanged a byte.
                // Say so explicitly -- from the client end this shows up only as handshake_failure(40),
                // which points at PSK or TLS roles and sends you looking in the wrong place entirely.
                log.warn("TCP relay splice {} session '{}' ended with 0 bytes — the peers never exchanged "
                        + "data. Usually one side was already gone when the splice began; the live side "
                        + "sees a TLS handshake_failure. Check for a 'STALE parked peer' warning above.",
                        direction, sessionId);
            } else {
                log.info("TCP relay splice {} session '{}' ended ({} bytes)", direction, sessionId, totalBytes);
            }
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
                    if (now - entry.getValue().connectedAt() > PAIR_TIMEOUT_MS) {
                        // INFO, not DEBUG: a reaped peer explains a client that saw "waiting for peer"
                        // and then nothing. At DEBUG this was invisible in production.
                        log.info("TCP relay: reaping unpaired peer {} for session '{}' after {}ms",
                                entry.getValue().socket().getRemoteSocketAddress(), entry.getKey(),
                                now - entry.getValue().connectedAt());
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

package com.alterante.p2p.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * Top-level orchestrator for the P2P connection lifecycle.
 * Drives: coordination → hole punch → DTLS handshake → packet router.
 */
public class PeerConnection {

    private static final Logger log = LoggerFactory.getLogger(PeerConnection.class);
    private static final int DTLS_MAX_RETRIES = 3;

    private final InetSocketAddress serverAddr;
    private final String sessionId;
    private final String psk;

    private volatile PeerState state = PeerState.INIT;
    private Consumer<PeerState> stateListener;
    private DatagramSocket socket;
    private InetSocketAddress myPublicEndpoint;
    private InetSocketAddress remoteEndpoint;
    private DtlsHandler dtls;
    private PacketRouter router;

    public PeerConnection(InetSocketAddress serverAddr, String sessionId, String psk) {
        this.serverAddr = serverAddr;
        this.sessionId = sessionId;
        this.psk = psk;
    }

    /** Set a listener that is called on every state transition. */
    public void setStateListener(Consumer<PeerState> listener) {
        this.stateListener = listener;
    }

    private void setState(PeerState newState) {
        this.state = newState;
        Consumer<PeerState> l = stateListener;
        if (l != null) l.accept(newState);
    }

    /**
     * Run the full connection flow. Blocks until connected or fails.
     */
    public void connect() throws Exception {
        try {
            socket = new DatagramSocket();
            log.info("Local socket bound to port {}", socket.getLocalPort());

            // Coordination
            setState(PeerState.REGISTERING);
            CoordClient coord = new CoordClient(socket, serverAddr, sessionId, psk);
            coord.setOnWaitingForPeer(() -> setState(PeerState.WAITING_PEER));
            remoteEndpoint = coord.coordinate();
            myPublicEndpoint = coord.myPublicEndpoint();

            log.info("Coordination complete. Remote peer: {}", remoteEndpoint);

            // Hole punch
            setState(PeerState.PUNCHING);
            int connId = new java.security.SecureRandom().nextInt();
            HolePuncher puncher = new HolePuncher(socket, remoteEndpoint, connId);
            HolePunchResult result = puncher.punch();
            if (!result.success()) {
                throw new RuntimeException("Hole punch failed after " + result.elapsedMs() + "ms");
            }
            remoteEndpoint = result.confirmedAddress();
            log.info("Hole punch succeeded in {}ms", result.elapsedMs());

            // DTLS handshake with retry
            setState(PeerState.HANDSHAKE);
            // Use public endpoints (exchanged via coord server) for deterministic role assignment.
            // Both peers see the same pair of public endpoints, so comparing them yields
            // opposite roles. Using localPort vs remotePort fails when NAT remaps ports.
            boolean isClient = compareEndpoints(myPublicEndpoint, remoteEndpoint) < 0;
            log.info("DTLS role: {} (myPublic={}, remotePublic={})",
                    isClient ? "CLIENT" : "SERVER", myPublicEndpoint, remoteEndpoint);

            for (int attempt = 1; attempt <= DTLS_MAX_RETRIES; attempt++) {
                sendNatKeepalive();
                dtls = new DtlsHandler(socket, remoteEndpoint, sessionId, psk, isClient);
                try {
                    dtls.handshake();
                    break; // success
                } catch (Exception e) {
                    dtls.close();
                    dtls = null;
                    if (attempt == DTLS_MAX_RETRIES) {
                        throw new RuntimeException("DTLS handshake failed after " + DTLS_MAX_RETRIES + " attempts", e);
                    }
                    log.warn("DTLS handshake attempt {}/{} failed: {}. Retrying...",
                            attempt, DTLS_MAX_RETRIES, e.getMessage());
                    Thread.sleep(500L * attempt); // backoff: 500ms, 1s, 1.5s
                }
            }

            setState(PeerState.CONNECTED);
            log.info("Encrypted P2P link established.");

            // Start packet router (handles keepalive + dispatches all packet types)
            router = new PacketRouter(dtls);
            router.start();

        } catch (Exception e) {
            setState(PeerState.ERROR);
            log.error("Connection failed: {}", e.getMessage());
            throw e;
        }
    }

    public void close() {
        if (router != null) {
            router.stop();
        }
        if (dtls != null) {
            dtls.close();
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        this.state = PeerState.INIT;
    }

    /**
     * Block until the connection drops or is closed.
     */
    public void awaitDisconnect() throws InterruptedException {
        if (router != null) {
            router.awaitStop();
        }
    }

    /**
     * Send a few dummy UDP packets to keep the NAT mapping alive
     * during the transition from hole punch to DTLS handshake.
     */
    private void sendNatKeepalive() {
        try {
            byte[] ping = new byte[]{0x00}; // single zero byte — not a valid DTLS or Packet header
            DatagramPacket pkt = new DatagramPacket(ping, ping.length,
                    remoteEndpoint.getAddress(), remoteEndpoint.getPort());
            for (int i = 0; i < 3; i++) {
                socket.send(pkt);
            }
        } catch (Exception e) {
            log.debug("Error sending NAT keepalive: {}", e.getMessage());
        }
    }

    /**
     * Compare two endpoints deterministically: first by IP address bytes, then by port.
     * Both peers see the same pair of public endpoints, so this always yields opposite signs.
     */
    private static int compareEndpoints(InetSocketAddress a, InetSocketAddress b) {
        byte[] aAddr = a.getAddress().getAddress();
        byte[] bAddr = b.getAddress().getAddress();
        for (int i = 0; i < Math.min(aAddr.length, bAddr.length); i++) {
            int cmp = (aAddr[i] & 0xFF) - (bAddr[i] & 0xFF);
            if (cmp != 0) return cmp;
        }
        if (aAddr.length != bAddr.length) return aAddr.length - bAddr.length;
        return Integer.compare(a.getPort(), b.getPort());
    }

    public PeerState state() { return state; }
    public DatagramSocket socket() { return socket; }
    public DtlsHandler dtls() { return dtls; }
    public PacketRouter router() { return router; }
    public InetSocketAddress myPublicEndpoint() { return myPublicEndpoint; }
    public InetSocketAddress remoteEndpoint() { return remoteEndpoint; }
}

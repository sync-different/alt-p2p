package com.alterante.p2p.net;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alterante.p2p.command.TransferOptions;

import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int DEFAULT_DTLS_MAX_RETRIES = 3;

    private final InetSocketAddress serverAddr;
    private final String sessionId;
    private final String psk;

    // Tunable parameters (null = use component defaults)
    private Integer punchTimeoutMs;
    private Integer punchIntervalMs;
    private int dtlsMaxRetries = DEFAULT_DTLS_MAX_RETRIES;
    private Integer dtlsTimeoutMs;
    private Integer initialCwnd;
    private Integer keepaliveIntervalMs;
    private boolean allowRelay;
    private String relayMode = "tcp";
    private Integer relayTcpPort;

    private volatile PeerState state = PeerState.INIT;
    private Consumer<PeerState> stateListener;
    private DatagramSocket socket;
    private InetSocketAddress myPublicEndpoint;
    private InetSocketAddress remoteEndpoint;
    private DtlsHandler dtls;
    private PacketRouter router;
    private TcpRelayClient tcpRelay;

    public PeerConnection(InetSocketAddress serverAddr, String sessionId, String psk) {
        this.serverAddr = serverAddr;
        this.sessionId = sessionId;
        this.psk = psk;
    }

    /** Apply optional tuning parameters from CLI. Null fields are ignored. */
    public void applyOptions(TransferOptions opts) {
        if (opts.punchTimeoutMs != null) this.punchTimeoutMs = opts.punchTimeoutMs;
        if (opts.punchIntervalMs != null) this.punchIntervalMs = opts.punchIntervalMs;
        if (opts.dtlsRetries != null) this.dtlsMaxRetries = opts.dtlsRetries;
        if (opts.dtlsTimeoutMs != null) this.dtlsTimeoutMs = opts.dtlsTimeoutMs;
        if (opts.initialCwnd != null) this.initialCwnd = opts.initialCwnd;
        if (opts.keepaliveIntervalMs != null) this.keepaliveIntervalMs = opts.keepaliveIntervalMs;
        if (opts.allowRelay && !opts.noRelay) this.allowRelay = true;
        if (opts.relayMode != null) this.relayMode = opts.relayMode;
        if (opts.relayTcpPort != null) this.relayTcpPort = opts.relayTcpPort;
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
            boolean useRelay = false;
            int connId = new java.security.SecureRandom().nextInt();
            HolePuncher puncher = (punchIntervalMs != null || punchTimeoutMs != null)
                    ? new HolePuncher(socket, remoteEndpoint, connId,
                            punchIntervalMs != null ? punchIntervalMs : 100,
                            punchTimeoutMs != null ? punchTimeoutMs : 10_000)
                    : new HolePuncher(socket, remoteEndpoint, connId);
            HolePunchResult result = puncher.punch();
            if (!result.success()) {
                if (allowRelay) {
                    log.warn("Hole punch failed after {}ms — falling back to {} relay via {}",
                            result.elapsedMs(), relayMode.toUpperCase(), serverAddr);

                    if ("tcp".equalsIgnoreCase(relayMode)) {
                        // TCP relay path — bypasses DTLS/PacketRouter/ReliableChannel entirely
                        setState(PeerState.RELAY_TCP);
                        boolean isClient = compareEndpoints(myPublicEndpoint, remoteEndpoint) < 0;
                        int tcpPort = relayTcpPort != null ? relayTcpPort : serverAddr.getPort() + 1;
                        InetSocketAddress tcpAddr = new InetSocketAddress(serverAddr.getAddress(), tcpPort);
                        log.info("TCP relay: connecting to {} (TLS role: {})",
                                tcpAddr, isClient ? "CLIENT" : "SERVER");
                        tcpRelay = new TcpRelayClient(tcpAddr, sessionId, psk, isClient);
                        tcpRelay.connect();
                        setState(PeerState.CONNECTED);
                        log.info("TCP relay: encrypted channel established.");
                        return; // skip DTLS/router setup
                    }

                    // UDP relay path (existing)
                    setState(PeerState.RELAYING);
                } else {
                    throw new RuntimeException("Hole punch failed after " + result.elapsedMs() + "ms");
                }
            } else {
                remoteEndpoint = result.confirmedAddress();
                log.info("Hole punch succeeded in {}ms", result.elapsedMs());
            }

            // DTLS handshake with retry
            if (!useRelay) {
                setState(PeerState.HANDSHAKE);
            }
            // Use public endpoints (exchanged via coord server) for deterministic role assignment.
            // Both peers see the same pair of public endpoints, so comparing them yields
            // opposite roles. Using localPort vs remotePort fails when NAT remaps ports.
            boolean isClient = compareEndpoints(myPublicEndpoint, remoteEndpoint) < 0;
            log.info("DTLS role: {} (myPublic={}, remotePublic={})",
                    isClient ? "CLIENT" : "SERVER", myPublicEndpoint, remoteEndpoint);

            for (int attempt = 1; attempt <= dtlsMaxRetries; attempt++) {
                if (!useRelay) {
                    sendNatKeepalive();
                }
                dtls = dtlsTimeoutMs != null
                        ? new DtlsHandler(socket, remoteEndpoint, sessionId, psk, isClient, dtlsTimeoutMs)
                        : new DtlsHandler(socket, remoteEndpoint, sessionId, psk, isClient);
                if (useRelay) {
                    dtls.enableRelay(serverAddr);
                }
                try {
                    dtls.handshake();
                    break; // success
                } catch (Exception e) {
                    dtls.close();
                    dtls = null;
                    if (attempt == dtlsMaxRetries) {
                        throw new RuntimeException("DTLS handshake failed after " + dtlsMaxRetries + " attempts", e);
                    }
                    log.warn("DTLS handshake attempt {}/{} failed: {}. Retrying...",
                            attempt, dtlsMaxRetries, e.getMessage());
                    Thread.sleep(500L * attempt); // backoff: 500ms, 1s, 1.5s
                }
            }

            setState(PeerState.CONNECTED);
            log.info("Encrypted P2P link established.");

            // Create router but DON'T start yet — callers must call startRouter()
            // after registering handlers (ReliableChannel). In relay mode, the remote
            // peer's FILE_OFFER can arrive within milliseconds of handshake completion.
            // If the router starts before handlers exist, the packet is dropped.
            router = keepaliveIntervalMs != null
                    ? new PacketRouter(dtls, keepaliveIntervalMs)
                    : new PacketRouter(dtls);

        } catch (Exception e) {
            setState(PeerState.ERROR);
            log.error("Connection failed: {}", e.getMessage());
            throw e;
        }
    }

    public void close() {
        if (tcpRelay != null) {
            tcpRelay.close();
        }
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

    /** Start the packet router. Call after registering all handlers (ReliableChannel). */
    public void startRouter() {
        if (router != null && !router.isRunning()) {
            router.start();
        }
    }

    public PeerState state() { return state; }
    public DatagramSocket socket() { return socket; }
    public DtlsHandler dtls() { return dtls; }
    public PacketRouter router() { return router; }
    public InetSocketAddress myPublicEndpoint() { return myPublicEndpoint; }
    public InetSocketAddress remoteEndpoint() { return remoteEndpoint; }
    /** Returns the configured initial CWND, or null if using default. */
    public Integer initialCwnd() { return initialCwnd; }
    public boolean allowRelay() { return allowRelay; }
    public boolean isTcpRelay() { return tcpRelay != null; }
    public InputStream tcpRelayInputStream() { return tcpRelay != null ? tcpRelay.inputStream() : null; }
    public OutputStream tcpRelayOutputStream() { return tcpRelay != null ? tcpRelay.outputStream() : null; }
}

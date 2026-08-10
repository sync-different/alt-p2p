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
    private boolean forceRelay;
    private String relayMode = "tcp";
    private Integer relayTcpPort;

    private int localPort; // 0 = ephemeral; set to reuse a port across reconnects
    private int peerWaitMs = 120_000; // how long to wait for the peer at the coord; <=0 = forever
    private volatile PeerState state = PeerState.INIT;
    private Consumer<PeerState> stateListener;
    private DatagramSocket socket;
    private InetSocketAddress myPublicEndpoint;
    private InetSocketAddress remoteEndpoint;        // transport target (confirmed after punch)
    private InetSocketAddress remotePublicEndpoint;  // coord-reported public (used for DTLS role)
    private InetSocketAddress remoteLocalEndpoint;    // peer's LAN endpoint, if reported
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
        if (opts.peerWaitSeconds != null) this.peerWaitMs = opts.peerWaitSeconds <= 0 ? 0 : opts.peerWaitSeconds * 1000;
        if (opts.forceRelay) {
            this.forceRelay = true;
            this.allowRelay = true;
            this.relayMode = "tcp";
        }
    }

    /** Set a listener that is called on every state transition. */
    public void setStateListener(Consumer<PeerState> listener) {
        this.stateListener = listener;
    }

    /** Bind a specific local UDP port on the next connect() (0 = ephemeral). */
    public void setLocalPort(int port) {
        this.localPort = port;
    }

    /** The bound local UDP port (valid after connect()). Reuse it for reconnects. */
    public int localPort() {
        return localPort;
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
            // Bind the requested local port (0 = ephemeral). Reusing the same port
            // across reconnects keeps our public endpoint stable so the coord server
            // recognizes us as the same peer instead of rejecting a "third" peer.
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(localPort));
            localPort = socket.getLocalPort();
            log.info("Local socket bound to port {}", localPort);

            // Coordination
            setState(PeerState.REGISTERING);
            CoordClient coord = new CoordClient(socket, serverAddr, sessionId, psk);
            coord.setOnWaitingForPeer(() -> setState(PeerState.WAITING_PEER));
            coord.setPeerWaitMs(peerWaitMs);
            remoteEndpoint = coord.coordinate();
            myPublicEndpoint = coord.myPublicEndpoint();
            remotePublicEndpoint = remoteEndpoint;            // coord-reported public
            remoteLocalEndpoint = coord.remoteLocalEndpoint(); // peer's LAN endpoint (may be null)

            log.info("Coordination complete. Remote public: {}, local: {}",
                    remotePublicEndpoint, remoteLocalEndpoint);

            // Hole punch — try the peer's public endpoint AND its LAN endpoint (handles
            // both peers behind the same NAT, where the public endpoint can't hairpin).
            setState(PeerState.PUNCHING);
            boolean useRelay = false;
            int connId = new java.security.SecureRandom().nextInt();
            HolePunchResult result;
            if (forceRelay) {
                log.info("--force-relay: skipping hole punch, going straight to TCP relay");
                result = HolePunchResult.failed(0);
            } else {
                java.util.List<InetSocketAddress> candidates = new java.util.ArrayList<>();
                candidates.add(remotePublicEndpoint);
                if (remoteLocalEndpoint != null && !remoteLocalEndpoint.equals(remotePublicEndpoint)) {
                    candidates.add(remoteLocalEndpoint);
                }
                int interval = punchIntervalMs != null ? punchIntervalMs : 100;
                int timeout = punchTimeoutMs != null ? punchTimeoutMs : 10_000;
                result = new HolePuncher(socket, candidates, connId, interval, timeout).punch();
            }
            if (!result.success()) {
                if (allowRelay) {
                    log.warn("Hole punch failed after {}ms — falling back to {} relay via {}",
                            result.elapsedMs(), relayMode.toUpperCase(), serverAddr);

                    if ("tcp".equalsIgnoreCase(relayMode)) {
                        // TCP relay path — bypasses DTLS/PacketRouter/ReliableChannel entirely
                        setState(PeerState.RELAY_TCP);
                        boolean isClient = compareEndpoints(myPublicEndpoint, remotePublicEndpoint) < 0;
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
                java.net.InetAddress ra = remoteEndpoint.getAddress();
                boolean directLan = ra.isSiteLocalAddress() || ra.isLinkLocalAddress() || ra.isLoopbackAddress();
                log.info(directLan
                                ? "Connected directly over LAN in {}ms (peer {})"
                                : "Connected via NAT hole punch in {}ms (peer {})",
                        result.elapsedMs(), remoteEndpoint);
            }

            // DTLS handshake with retry
            if (!useRelay) {
                setState(PeerState.HANDSHAKE);
            }
            // Use the coord-reported PUBLIC endpoints for deterministic role assignment.
            // Both peers see the same pair of public endpoints, so comparing them yields
            // opposite roles. Comparing the confirmed transport address breaks when the
            // punch succeeded via LAN candidates (both peers share a public IP).
            boolean isClient = compareEndpoints(myPublicEndpoint, remotePublicEndpoint) < 0;
            log.info("DTLS role: {} (myPublic={}, remotePublic={}, transport={})",
                    isClient ? "CLIENT" : "SERVER", myPublicEndpoint, remotePublicEndpoint, remoteEndpoint);

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
            log.error("Connection failed: {}", e.getMessage());
            // Release the socket / dtls / relay bound during this failed attempt.
            // connect() throws before the caller ever receives a PeerConnection to close(),
            // so without this every failed connect — e.g. a host/serve that keeps hitting the
            // 120s "waiting for peer" timeout, or a punch-fail → relay read-timeout — orphans
            // the DatagramSocket fd and eventually exhausts them (observed: 758 leaked sockets).
            try { close(); } catch (Exception ignore) { /* don't mask the original failure */ }
            setState(PeerState.ERROR); // after close() (it resets state to INIT) so ERROR is the final state
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

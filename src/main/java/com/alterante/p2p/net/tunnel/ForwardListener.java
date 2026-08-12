package com.alterante.p2p.net.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Client side of the tunnel: listens on a local TCP port and opens a fresh mux
 * stream for each accepted connection, bridging the two. The developer points
 * {@code lore} at {@code grpc://127.0.0.1:<localPort()>/repo}; each connection
 * lore makes becomes its own multiplexed stream to the host's {@code loreserver}.
 */
public class ForwardListener implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ForwardListener.class);

    private final StreamMux mux;
    private final String target;
    private final ServerSocket server;
    private final ExecutorService exec =
            Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "fwd-listener"); t.setDaemon(true); return t; });
    private volatile boolean closed;

    public ForwardListener(StreamMux mux, String bindHost, int localPort) throws IOException {
        this(mux, bindHost, localPort, "");
    }

    /**
     * Forward to a named target on the host, rather than its default one — so several listeners can
     * share one session, each pointed at a different remote service.
     */
    public ForwardListener(StreamMux mux, String bindHost, int localPort, String target)
            throws IOException {
        this.mux = mux;
        this.target = target == null ? "" : target;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(bindHost, localPort));
    }

    /** The bound local port (valid after construction). */
    public int localPort() { return server.getLocalPort(); }

    public void start() { exec.execute(this::acceptLoop); }

    private void acceptLoop() {
        while (!closed) {
            Socket sock = null;
            try {
                sock = server.accept();
                sock.setTcpNoDelay(true);
                StreamMux.MuxStream stream = mux.open(target);
                Bridge.bridge(sock, stream, exec);
            } catch (IOException e) {
                // The accepted socket is ours until Bridge takes it. If mux.open() throws — the
                // carrier died, which is exactly when a caller keeps retrying — the connection was
                // dropped on the floor still open, costing a file descriptor every time and leaving
                // the client connected to nothing. Enough of those reach the process fd limit, at
                // which point accept() starts failing immediately and this loop spins at full CPU
                // logging at DEBUG, where nobody sees it. The two faults feed each other.
                closeQuietly(sock);
                if (!closed) {
                    log.debug("accept/open failed: {}", e.getMessage());
                    pauseAfterFailure();
                }
            }
        }
    }

    private static void closeQuietly(Socket sock) {
        if (sock != null) {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    /** Brief pause so a persistently failing accept cannot become a busy loop. */
    private void pauseAfterFailure() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closed = true;
        }
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        exec.shutdownNow();
    }
}

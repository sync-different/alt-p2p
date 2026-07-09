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
    private final ServerSocket server;
    private final ExecutorService exec =
            Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "fwd-listener"); t.setDaemon(true); return t; });
    private volatile boolean closed;

    public ForwardListener(StreamMux mux, String bindHost, int localPort) throws IOException {
        this.mux = mux;
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(bindHost, localPort));
    }

    /** The bound local port (valid after construction). */
    public int localPort() { return server.getLocalPort(); }

    public void start() { exec.execute(this::acceptLoop); }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket sock = server.accept();
                sock.setTcpNoDelay(true);
                StreamMux.MuxStream stream = mux.open();
                Bridge.bridge(sock, stream, exec);
            } catch (IOException e) {
                if (!closed) log.debug("accept failed: {}", e.getMessage());
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        exec.shutdownNow();
    }
}

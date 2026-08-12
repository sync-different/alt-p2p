package com.alterante.p2p.net.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Host side of the tunnel: for each inbound mux stream, opens a TCP connection to a
 * local target and bridges the two. Registers itself as the mux's stream handler.
 *
 * <p>A stream may name which target it wants (see {@link StreamMux#open(String)}); an
 * unnamed stream goes to the default. One session can therefore forward several
 * services — needed because a lore client must reach both {@code loreserver} and the
 * identity provider, which are separate listeners on the host.
 */
public class ForwardConnector implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ForwardConnector.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final ExecutorService exec =
            Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "fwd-connector"); t.setDaemon(true); return t; });

    /** Single-target: every inbound stream goes to {@code targetHost:targetPort}. */
    public ForwardConnector(StreamMux mux, String targetHost, int targetPort) {
        this(mux, new InetSocketAddress(targetHost, targetPort), Map.of());
    }

    /**
     * Multi-target: streams naming a label go to that target, anything else to {@code defaultTarget}.
     *
     * @param defaultTarget where unnamed streams go — keeps older peers, which never send a label, working
     * @param targets       label to address, e.g. {@code {"identity": 127.0.0.1:8443}}
     */
    public ForwardConnector(StreamMux mux, InetSocketAddress defaultTarget,
                            Map<String, InetSocketAddress> targets) {
        Map<String, InetSocketAddress> routes = Map.copyOf(targets);
        mux.onStream(stream -> exec.execute(() -> {
            String label = stream.target();
            InetSocketAddress target = label.isEmpty() ? defaultTarget : routes.get(label);
            if (target == null) {
                // Refuse rather than silently falling back: a mistyped label reaching the wrong
                // service would be far harder to diagnose than a closed stream.
                log.warn("no target registered for label '{}' (known: {})", label, routes.keySet());
                stream.close();
                return;
            }
            Socket sock = new Socket();
            try {
                sock.setTcpNoDelay(true);
                sock.connect(target, CONNECT_TIMEOUT_MS);
                Bridge.bridge(sock, stream, exec);
            } catch (IOException e) {
                log.debug("connect to {} for '{}' failed: {}", target, label, e.getMessage());
                try { sock.close(); } catch (IOException ignored) {}
                stream.close();
            }
        }));
    }

    @Override
    public void close() {
        exec.shutdownNow();
    }
}

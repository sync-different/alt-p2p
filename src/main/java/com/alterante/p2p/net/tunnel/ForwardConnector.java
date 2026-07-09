package com.alterante.p2p.net.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Host side of the tunnel: for each inbound mux stream, opens a TCP connection to
 * the local target (the {@code loreserver} gRPC port on 127.0.0.1) and bridges the
 * two. Registers itself as the mux's stream handler.
 */
public class ForwardConnector implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(ForwardConnector.class);
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final ExecutorService exec =
            Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "fwd-connector"); t.setDaemon(true); return t; });

    public ForwardConnector(StreamMux mux, String targetHost, int targetPort) {
        mux.onStream(stream -> exec.execute(() -> {
            Socket sock = new Socket();
            try {
                sock.setTcpNoDelay(true);
                sock.connect(new InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS);
                Bridge.bridge(sock, stream, exec);
            } catch (IOException e) {
                log.debug("connect to {}:{} failed: {}", targetHost, targetPort, e.getMessage());
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

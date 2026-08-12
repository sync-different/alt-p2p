package com.alterante.p2p.net.tunnel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A listener whose carrier has died must drop connections, not accumulate them.
 *
 * <p>{@code accept()} hands over a live socket that belongs to the listener until {@code Bridge}
 * takes ownership. When {@code mux.open()} fails in between — the carrier is gone, which is exactly
 * the state a client keeps retrying against — that socket used to be dropped still open: one leaked
 * file descriptor per connection attempt, and a client left connected to nothing rather than
 * refused. On a host that runs for hours this ends at the process fd limit, where {@code accept()}
 * fails immediately and the loop spins at full CPU logging at DEBUG.
 */
class ForwardListenerFailureTest {

    /** A pipe whose output is already broken, so every {@code mux.open()} throws. */
    private static BytePipe deadPipe() {
        return new BytePipe() {
            @Override public InputStream in() {
                return new InputStream() {
                    @Override public int read() { return -1; }
                };
            }
            @Override public OutputStream out() {
                return new OutputStream() {
                    @Override public void write(int b) throws IOException {
                        throw new IOException("carrier is gone");
                    }
                    @Override public void write(byte[] b, int off, int len) throws IOException {
                        throw new IOException("carrier is gone");
                    }
                };
            }
            @Override public void close() { }
        };
    }

    @Test
    void connectionsAreClosedWhenTheCarrierIsDead() throws Exception {
        StreamMux mux = new StreamMux(deadPipe());
        ForwardListener listener =
                new ForwardListener(mux, InetAddress.getLoopbackAddress().getHostAddress(), 0);
        listener.start();

        List<Socket> clients = new ArrayList<>();
        try {
            InetSocketAddress addr = new InetSocketAddress(
                    InetAddress.getLoopbackAddress(), listener.localPort());

            for (int i = 0; i < 8; i++) {
                Socket c = new Socket();
                c.connect(addr, 2000);
                clients.add(c);
            }

            // Each connection must be torn down, which the client observes as EOF. A leaked socket
            // stays open and this read blocks until the timeout instead.
            for (Socket c : clients) {
                c.setSoTimeout(3000);
                int b = c.getInputStream().read();
                assertTrue(b == -1,
                        "the listener must close a connection it cannot bridge, not hold it open");
            }
        } finally {
            for (Socket c : clients) {
                try { c.close(); } catch (IOException ignored) { }
            }
            listener.close();
            mux.close();
        }
    }
}

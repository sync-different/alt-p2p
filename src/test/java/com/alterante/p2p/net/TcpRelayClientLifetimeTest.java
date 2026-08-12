package com.alterante.p2p.net;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A failed relay connect must not cost a file descriptor.
 *
 * <p>{@code connect()} throws before the caller is ever handed the object, so nothing else is in a
 * position to close the socket — it has to close its own. This is the retried path: relay attempts
 * fail and are retried by the reconnect loop, and the coordinator's own stale-peer bug used to make
 * the first attempts fail routinely, so each failure that leaks accumulates. The identical mistake
 * on the UDP side cost a waiting host 758 sockets before it was found.
 */
class TcpRelayClientLifetimeTest {

    /** A relay that accepts a connection and then rejects the AUTH, as a real one does on bad PSK. */
    private static ServerSocket rejectingRelay(List<Socket> accepted) throws IOException {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    accepted.add(s);
                    TcpRelayProtocol.readMessage(s.getInputStream());     // the client's AUTH
                    TcpRelayProtocol.writeMessage(s.getOutputStream(),
                            TcpRelayProtocol.MSG_AUTH_FAIL,
                            "no".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception e) {
                    return;
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return server;
    }

    @Test
    void aRejectedAuthClosesTheSocketItOpened() throws Exception {
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        try (ServerSocket relay = rejectingRelay(accepted)) {
            InetSocketAddress addr =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.getLocalPort());

            List<TcpRelayClient> clients = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                TcpRelayClient client = new TcpRelayClient(addr, "leak-session", "psk", true);
                clients.add(client);
                assertThrows(IOException.class, client::connect,
                        "a rejected AUTH must surface as an exception");
            }

            // Every socket the client opened must be closed, even though the caller never got a
            // reference to any of them.
            for (TcpRelayClient client : clients) {
                assertTrue(client.isClosed(),
                        "connect() must close its own socket when it fails — the caller cannot");
            }
        }
    }

    /**
     * A partner that authenticates and then says nothing must not pin the thread forever.
     *
     * <p>The relay splices whatever claimed the session; a wedged or half-open partner leaves the
     * TLS handshake reading with nobody writing. With no socket timeout that read never returns, and
     * a long-lived host stuck there never goes back to waiting for peers — it is gone until someone
     * restarts it. Here the handshake timeout is dropped to keep the test quick; what is being
     * checked is that a bound exists at all.
     */
    @Test
    void aSilentPartnerDoesNotHangTheHandshakeForever() throws Exception {
        try (ServerSocket relay = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            List<Socket> held = new CopyOnWriteArrayList<>();
            Thread t = new Thread(() -> {
                try {
                    Socket s = relay.accept();
                    held.add(s);                       // keep it open, and stay silent
                    TcpRelayProtocol.readMessage(s.getInputStream());          // the AUTH
                    TcpRelayProtocol.writeMessage(s.getOutputStream(),
                            TcpRelayProtocol.MSG_AUTH_OK, new byte[0]);
                    Thread.sleep(60_000);              // never send a TLS hello
                } catch (Exception ignored) {
                    // closed at test end
                }
            });
            t.setDaemon(true);
            t.start();

            InetSocketAddress addr =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), relay.getLocalPort());
            TcpRelayClient client = new TcpRelayClient(addr, "silent-session", "psk", true);
            client.setHandshakeTimeoutMs(1500);

            long start = System.currentTimeMillis();
            assertThrows(IOException.class, client::connect,
                    "a silent partner must fail the handshake, not block on it");
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 10_000,
                    "the handshake must be bounded (took " + elapsed + "ms)");
            assertTrue(client.isClosed(), "the socket must be closed after a failed handshake");

            for (Socket s : held) {
                s.close();
            }
        }
    }
}

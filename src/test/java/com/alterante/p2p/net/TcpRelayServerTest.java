package com.alterante.p2p.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay's pairing logic — previously untested entirely, which is how the stale-peer bug shipped.
 *
 * <p>The relay matches two authenticated connections by session id and splices them. Its only liveness
 * test used to be {@code !socket.isClosed()}, which is true whenever WE have not closed the socket
 * locally — so a peer whose process had gone still passed. A live arrival was then spliced to that
 * corpse: 0 bytes in both directions, and on the client end a bare {@code handshake_failure(40)} that
 * points at PSK or TLS roles rather than at the relay. Observed on the live coordinator 2026-08-10.
 */
class TcpRelayServerTest {

    private static final String PSK = "test-psk";
    private static final String SESSION = "relay-test-session";

    private TcpRelayServer server;
    private Thread serverThread;
    private int port;

    private void startServer() throws Exception {
        // The server binds a fixed port, so grab a free one first. Racy in principle; fine in a test.
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        server = new TcpRelayServer(port, PSK);
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                // stop() closes the listening socket; that surfaces here and is expected
            }
        }, "relay-under-test");
        serverThread.setDaemon(true);
        serverThread.start();
        for (int i = 0; i < 50 && !server.isRunning(); i++) {
            Thread.sleep(20);
        }
        assertTrue(server.isRunning(), "relay did not start");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    /** Connect and complete the relay AUTH handshake, leaving the socket ready to be paired. */
    private Socket authenticate() throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        s.setTcpNoDelay(true);
        byte[] hmac = CoordServer.computeHmac(PSK, TcpRelayServer.TCP_RELAY_NONCE, SESSION);
        OutputStream out = s.getOutputStream();
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_AUTH,
                TcpRelayProtocol.encodeAuth(SESSION, hmac));
        out.flush();
        return s;
    }

    /** @return true if AUTH_OK arrived within the timeout — the relay only sends it once PAIRED. */
    private boolean awaitPaired(Socket s, int timeoutMs) throws Exception {
        s.setSoTimeout(timeoutMs);
        try {
            InputStream in = s.getInputStream();
            TcpRelayProtocol.Message m = TcpRelayProtocol.readMessage(in);
            return m.type() == TcpRelayProtocol.MSG_AUTH_OK;
        } catch (SocketTimeoutException e) {
            return false;   // still parked, waiting for a partner
        }
    }

    /**
     * Both peers arriving at once must still be paired.
     *
     * <p>Pairing used to be {@code remove()} then {@code put()} — not atomic, even on a
     * ConcurrentHashMap. Two peers that authenticated before either parked both saw an empty slot
     * and both parked, and the second put <em>silently overwrote</em> the first, leaving that peer
     * referenced by nothing: unreachable by the pairing path and invisible to the reaper, which
     * scans that same map. It sat until its client gave up, leaking a socket here meanwhile.
     *
     * <p>Every existing test in this class connects peers sequentially, which is why this survived:
     * the second arrival always found the first already parked. Production hid it too, because a
     * failed hole punch staggers arrivals by ~10s — but {@code --force-relay} skips the punch, and
     * {@code scripts/loopback.sh RELAY=1} then failed 3/3 against 0.7.0.
     *
     * <p>Repeated, because a race that reproduces once in ten runs is still broken.
     */
    @Test
    void peersArrivingSimultaneouslyArePaired() throws Exception {
        for (int round = 0; round < 8; round++) {
            startServer();
            Socket a = null;
            Socket b = null;
            try {
                // Release both AUTHs from the same instant, so neither can park before the other
                // has looked for a partner.
                CountDownLatch go = new CountDownLatch(1);
                java.util.concurrent.Callable<Socket> connect = () -> {
                    go.await();
                    return authenticate();
                };
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Future<Socket> fa = pool.submit(connect);
                    Future<Socket> fb = pool.submit(connect);
                    go.countDown();
                    a = fa.get(10, TimeUnit.SECONDS);
                    b = fb.get(10, TimeUnit.SECONDS);
                } finally {
                    pool.shutdownNow();
                }

                assertTrue(awaitPaired(a, 4000) && awaitPaired(b, 4000),
                        "round " + round + ": both peers authenticated simultaneously and neither "
                                + "was paired — one was dropped from the pending map");
            } finally {
                closeQuietly(a);
                closeQuietly(b);
                if (server != null) {
                    server.stop();
                    server = null;
                }
            }
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // test teardown
            }
        }
    }

    @Test
    void twoLivePeersArePairedAndSpliced() throws Exception {
        startServer();
        Socket a = authenticate();
        assertTrue(!awaitPaired(a, 300), "first peer must WAIT, not be paired with itself");

        Socket b = authenticate();
        assertTrue(awaitPaired(b, 3000), "second peer should be paired");
        assertTrue(awaitPaired(a, 3000), "first peer should be paired too");

        // And the splice actually moves bytes, in both directions.
        a.getOutputStream().write("ping".getBytes());
        a.getOutputStream().flush();
        byte[] buf = new byte[4];
        b.setSoTimeout(3000);
        assertEquals(4, b.getInputStream().read(buf));
        assertEquals("ping", new String(buf));

        b.getOutputStream().write("pong".getBytes());
        b.getOutputStream().flush();
        a.setSoTimeout(3000);
        assertEquals(4, a.getInputStream().read(buf));
        assertEquals("pong", new String(buf));

        a.close();
        b.close();
    }

    /**
     * The regression test for the bug. A parked peer that has gone away must be discarded, not spliced
     * to the next live arrival.
     *
     * <p>Before the fix this failed in the most misleading way available: the second peer WAS paired
     * (it received AUTH_OK), the splice started, and then nothing ever came through it. Here we assert
     * the opposite — the new arrival is left waiting for a real partner.
     */
    @Test
    void aStaleParkedPeerIsDiscardedRatherThanSpliced() throws Exception {
        startServer();

        Socket dead = authenticate();
        assertTrue(!awaitPaired(dead, 300), "it should be parked");
        dead.close();                       // the peer goes away; the relay does not find out
        Thread.sleep(150);                  // let the FIN/RST land

        Socket live = authenticate();
        // Must NOT be paired: the only candidate partner is a corpse.
        assertTrue(!awaitPaired(live, 1500),
                "live peer was spliced to a dead parked socket — the 0-byte-splice bug");

        // ...and it is still available to pair with a genuine partner that turns up afterwards.
        Socket partner = authenticate();
        assertTrue(awaitPaired(partner, 3000), "the real partner should pair");
        assertTrue(awaitPaired(live, 3000), "the waiting peer should pair with it");

        partner.getOutputStream().write("ok".getBytes());
        partner.getOutputStream().flush();
        byte[] buf = new byte[2];
        live.setSoTimeout(3000);
        assertEquals(2, live.getInputStream().read(buf), "the splice must carry data");

        live.close();
        partner.close();
    }

    @Test
    void aWrongPskIsRejected() throws Exception {
        startServer();
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        byte[] badHmac = CoordServer.computeHmac("wrong-psk", TcpRelayServer.TCP_RELAY_NONCE, SESSION);
        TcpRelayProtocol.writeMessage(s.getOutputStream(), TcpRelayProtocol.MSG_AUTH,
                TcpRelayProtocol.encodeAuth(SESSION, badHmac));
        s.getOutputStream().flush();

        s.setSoTimeout(3000);
        TcpRelayProtocol.Message m = TcpRelayProtocol.readMessage(s.getInputStream());
        assertEquals(TcpRelayProtocol.MSG_AUTH_FAIL, m.type());
        s.close();
    }
}

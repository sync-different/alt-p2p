package com.alterante.p2p.net;

import com.alterante.p2p.command.TransferOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code --relay-mode udp} must actually relay.
 *
 * <p>The fallback is chosen when hole punching fails, which by definition means the peers cannot
 * reach each other directly — so the DTLS handshake that follows has to be tunnelled through the
 * coordination server. That is what {@code DtlsHandler.enableRelay} is for, and it was never called:
 * the flag guarding it was initialised to false and never assigned. The mode logged "falling back to
 * UDP relay" and then handshook directly against the endpoint the punch had just failed to reach.
 *
 * <p>The bug is invisible in any test that lets the punch succeed, because then the direct path
 * works and the relay is never needed. Here the punch is given a 1ms budget so it always fails, on
 * loopback, where a direct path plainly exists — if the connection still comes up, it came up
 * through the relay.
 */
class UdpRelayFallbackTest {

    private static final String PSK = "relay-test-psk";

    private int coordPort;
    private CoordServer coord;
    private Thread coordThread;
    private InetSocketAddress coordAddr;

    @BeforeEach
    void setUp() throws Exception {
        try (DatagramSocket probe = new DatagramSocket(0)) {
            coordPort = probe.getLocalPort();
        }
        coordAddr = new InetSocketAddress(InetAddress.getLoopbackAddress(), coordPort);
        coord = new CoordServer(coordPort, PSK, 60);
        coordThread = new Thread(() -> {
            try {
                coord.start();
            } catch (Exception e) {
                if (coord.isRunning()) {
                    throw new RuntimeException(e);
                }
            }
        });
        coordThread.setDaemon(true);
        coordThread.start();
        long deadline = System.currentTimeMillis() + 5000;
        while (!coord.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        Thread.sleep(50);
    }

    @AfterEach
    void tearDown() throws Exception {
        coord.stop();
        coordThread.join(3000);
    }

    private TransferOptions relayOptions() {
        TransferOptions opts = new TransferOptions();
        opts.allowRelay = true;
        opts.relayMode = "udp";
        // A zero budget is the only value that fails deterministically: at 1ms the punch still
        // succeeded here, because on loopback the peer's PUNCH can arrive inside that millisecond.
        opts.punchTimeoutMs = 0;
        opts.punchIntervalMs = 1;
        opts.dtlsRetries = 2;
        opts.dtlsTimeoutMs = 15_000;
        return opts;
    }

    @Test
    void twoPeersConnectThroughTheCoordinatorWhenPunchingFails() throws Exception {
        String sessionId = "udp-relay-session";

        PeerConnection a = new PeerConnection(coordAddr, sessionId, PSK);
        PeerConnection b = new PeerConnection(coordAddr, sessionId, PSK);
        a.applyOptions(relayOptions());
        b.applyOptions(relayOptions());

        // Both sides must report RELAYING — the state proves which path was taken, not just that
        // something connected.
        CountDownLatch relayingA = new CountDownLatch(1);
        a.setStateListener(s -> {
            if (s == PeerState.RELAYING) relayingA.countDown();
        });

        AtomicReference<Exception> failA = new AtomicReference<>();
        AtomicReference<Exception> failB = new AtomicReference<>();

        Thread ta = new Thread(() -> {
            try {
                a.connect();
            } catch (Exception e) {
                failA.set(e);
            }
        });
        Thread tb = new Thread(() -> {
            try {
                b.connect();
            } catch (Exception e) {
                failB.set(e);
            }
        });
        ta.setDaemon(true);
        tb.setDaemon(true);

        try {
            ta.start();
            Thread.sleep(150);      // let A register first so the pairing order is deterministic
            tb.start();

            ta.join(60_000);
            tb.join(60_000);

            assertNull(failA.get(), "peer A failed to connect over the UDP relay");
            assertNull(failB.get(), "peer B failed to connect over the UDP relay");

            assertTrue(relayingA.await(1, TimeUnit.SECONDS), "peer A should have entered RELAYING");
            assertEquals(PeerState.CONNECTED, a.state());
            assertEquals(PeerState.CONNECTED, b.state());

            // The decisive assertion. Both peers are on loopback, so a direct handshake succeeds
            // here even when the relay is broken — which is exactly how this shipped unnoticed.
            // Only the coordinator's forwarding counter distinguishes the two paths.
            assertTrue(coord.relayedPackets() > 0,
                    "the handshake must have travelled through the coordinator; 0 relayed packets "
                            + "means the peers connected directly and the relay was never engaged");
        } finally {
            a.close();
            b.close();
        }
    }
}

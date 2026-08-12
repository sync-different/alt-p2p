package com.alterante.p2p.net;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketCodec;
import com.alterante.p2p.protocol.PacketType;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DTLS read must time out even while packets keep arriving.
 *
 * <p>Timeouts are not merely how a stalled connection is detected — they are how BouncyCastle learns
 * to retransmit a handshake flight. A read that never times out is a handshake that never retries.
 *
 * <p>Discarded packets used to reset the wait, so a peer that kept sending anything discardable held
 * the read open indefinitely. The peer does exactly that at the one moment it matters most: it is
 * still punching at 100ms intervals when the handshake starts, and every one of those PUNCHes is
 * filtered here as non-DTLS. Lose one handshake flight in that window and the connection hangs to
 * the 30s deadline instead of retransmitting in the next few hundred milliseconds.
 */
class DtlsReceiveTimeoutTest {

    @Test
    void aReadTimesOutEvenWhileNonDtlsPacketsKeepArriving() throws Exception {
        try (DatagramSocket local = new DatagramSocket();
             DatagramSocket peer = new DatagramSocket()) {

            InetSocketAddress peerAddr =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort());
            InetSocketAddress localAddr =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), local.getLocalPort());

            // The peer floods PUNCH packets — from the expected address, so they pass the source
            // check, and filtered as non-DTLS, so each one is discarded. Faster than the read
            // timeout, which is the whole point: naively, the wait restarts before it can expire.
            AtomicBoolean stop = new AtomicBoolean(false);
            Thread flood = new Thread(() -> {
                try {
                    byte[] punch = PacketCodec.encode(
                            new Packet(PacketType.PUNCH, (byte) 0, 0x99, 0, null));
                    while (!stop.get()) {
                        peer.send(new DatagramPacket(punch, punch.length,
                                localAddr.getAddress(), localAddr.getPort()));
                        Thread.sleep(20);
                    }
                } catch (Exception ignored) {
                    // socket closed at test end
                }
            });
            flood.setDaemon(true);
            flood.start();

            try {
                // A generous handshake deadline, so a timeout here is the per-read timeout and not
                // the deadline backstop firing.
                DtlsHandler.UdpDatagramTransport transport =
                        new DtlsHandler.UdpDatagramTransport(local, peerAddr, 60_000);
                transport.setWaitMillis(400);

                byte[] buf = new byte[2048];
                long start = System.currentTimeMillis();

                assertTimeoutPreemptively(Duration.ofSeconds(6), () ->
                        assertThrows(SocketTimeoutException.class,
                                () -> transport.receive(buf, 0, buf.length, 400),
                                "a read swamped with discardable packets must still time out, or "
                                        + "DTLS never retransmits a lost flight"));

                long elapsed = System.currentTimeMillis() - start;
                assertTrue(elapsed < 3000,
                        "the timeout must reflect the requested wait, not the arrival of noise "
                                + "(took " + elapsed + "ms for a 400ms read)");
            } finally {
                stop.set(true);
                flood.join(1000);
            }
        }
    }

    /** And a genuine DTLS record must still be delivered rather than waited past. */
    @Test
    void aRealDtlsRecordIsStillReturned() throws Exception {
        try (DatagramSocket local = new DatagramSocket();
             DatagramSocket peer = new DatagramSocket()) {

            InetSocketAddress peerAddr =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort());

            byte[] record = new byte[32];
            record[0] = 0x16;                 // handshake
            record[1] = (byte) 0xFE;
            record[2] = (byte) 0xFD;          // DTLS 1.2
            peer.send(new DatagramPacket(record, record.length,
                    InetAddress.getLoopbackAddress(), local.getLocalPort()));

            DtlsHandler.UdpDatagramTransport transport =
                    new DtlsHandler.UdpDatagramTransport(local, peerAddr, 60_000);
            transport.setWaitMillis(2000);

            byte[] buf = new byte[2048];
            int n = transport.receive(buf, 0, buf.length, 2000);

            org.junit.jupiter.api.Assertions.assertEquals(record.length, n);
            org.junit.jupiter.api.Assertions.assertEquals(0x16, buf[0] & 0xFF);
        }
    }
}

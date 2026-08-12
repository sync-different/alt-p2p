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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hole punching must survive losing the one packet that reports success.
 *
 * <p>The handshake is asymmetric in a way that is easy to miss: receiving a PUNCH is itself proof of
 * connectivity, so the peer that receives one first declares victory and moves straight to DTLS. If
 * the PUNCH_ACK it sends back is dropped — one datagram, on the lossy path the punch exists to get
 * through — the other side is left punching at a peer that will never answer again, because a peer in
 * DTLS discards non-DTLS datagrams. Both sides then fail: one times out on the punch, the other on a
 * handshake nobody is completing.
 *
 * <p>The peer's DTLS records are themselves the proof the punch was looking for, arriving from the
 * very address it needs. These tests drive the exact sequence with raw sockets.
 */
class HolePunchLostAckTest {

    private static final int CONN_ID = 0x1234ABCD;

    /** DTLS 1.2 handshake record header (type 0x16, version 0xFEFD) + a plausible body. */
    private static byte[] dtlsClientHelloRecord() {
        byte[] rec = new byte[64];
        rec[0] = 0x16;                       // handshake
        rec[1] = (byte) 0xFE;
        rec[2] = (byte) 0xFD;                // DTLS 1.2
        rec[11] = 0;
        rec[12] = 51;                        // length
        rec[13] = 1;                         // ClientHello
        return rec;
    }

    @Test
    void aPeerAlreadyInDtlsCountsAsPunched() throws Exception {
        try (DatagramSocket local = new DatagramSocket();
             DatagramSocket peer = new DatagramSocket()) {

            InetSocketAddress peerEndpoint =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort());

            // The peer behaves exactly as a peer that already succeeded: it swallows our PUNCHes and
            // sends only DTLS handshake records. No PUNCH_ACK will ever arrive.
            Thread peerThread = new Thread(() -> {
                try {
                    peer.setSoTimeout(200);
                    byte[] hello = dtlsClientHelloRecord();
                    for (int i = 0; i < 40; i++) {
                        byte[] buf = new byte[Packet.MAX_DATAGRAM];
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        try {
                            peer.receive(in);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        // Retransmit the handshake toward whoever punched us, and nothing else.
                        peer.send(new DatagramPacket(hello, hello.length,
                                in.getAddress(), in.getPort()));
                    }
                } catch (Exception ignored) {
                    // socket closed at test end
                }
            });
            peerThread.setDaemon(true);
            peerThread.start();

            HolePuncher puncher = new HolePuncher(local, List.of(peerEndpoint), CONN_ID, 100, 4000);
            HolePunchResult result = puncher.punch();

            assertTrue(result.success(),
                    "a peer that is already handshaking has proved connectivity; losing the "
                            + "PUNCH_ACK must not fail the punch");
            assertEquals(peer.getLocalPort(), result.confirmedAddress().getPort(),
                    "the punch must adopt the address the handshake came from");
        }
    }

    /** The ordinary path must be unaffected: a PUNCH still succeeds and is still acknowledged. */
    @Test
    void aNormalPunchStillSucceedsAndIsAcknowledged() throws Exception {
        try (DatagramSocket local = new DatagramSocket();
             DatagramSocket peer = new DatagramSocket()) {

            InetSocketAddress peerEndpoint =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), peer.getLocalPort());

            Thread peerThread = new Thread(() -> {
                try {
                    peer.setSoTimeout(3000);
                    byte[] buf = new byte[Packet.MAX_DATAGRAM];
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    peer.receive(in);                       // our PUNCH
                    byte[] punch = PacketCodec.encode(
                            new Packet(PacketType.PUNCH, (byte) 0, 0x5555, 0, null));
                    peer.send(new DatagramPacket(punch, punch.length, in.getAddress(), in.getPort()));
                } catch (Exception ignored) {
                    // socket closed at test end
                }
            });
            peerThread.setDaemon(true);
            peerThread.start();

            HolePunchResult result =
                    new HolePuncher(local, List.of(peerEndpoint), CONN_ID, 100, 4000).punch();

            assertTrue(result.success());
            assertEquals(peer.getLocalPort(), result.confirmedAddress().getPort());
        }
    }

    /**
     * And noise must not be mistaken for a handshake. A datagram whose first byte happens to fall in
     * the DTLS content-type range is common — the punch itself sends 0x00 keepalives nearby — so the
     * version bytes have to carry their weight.
     */
    @Test
    void aDatagramThatIsNotDtlsDoesNotEndThePunch() throws Exception {
        try (DatagramSocket local = new DatagramSocket();
             DatagramSocket noise = new DatagramSocket()) {

            InetSocketAddress unreachable =
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), noise.getLocalPort());

            Thread noiseThread = new Thread(() -> {
                try {
                    noise.setSoTimeout(200);
                    // Right content type, wrong version — and long enough to pass the length check.
                    byte[] junk = new byte[64];
                    junk[0] = 0x16;
                    junk[1] = 0x03;
                    junk[2] = 0x03;       // TLS 1.2, not DTLS
                    for (int i = 0; i < 20; i++) {
                        byte[] buf = new byte[Packet.MAX_DATAGRAM];
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        try {
                            noise.receive(in);
                        } catch (SocketTimeoutException e) {
                            continue;
                        }
                        noise.send(new DatagramPacket(junk, junk.length, in.getAddress(), in.getPort()));
                    }
                } catch (Exception ignored) {
                    // socket closed at test end
                }
            });
            noiseThread.setDaemon(true);
            noiseThread.start();

            HolePunchResult result =
                    new HolePuncher(local, List.of(unreachable), CONN_ID, 100, 1500).punch();

            assertFalse(result.success(),
                    "a non-DTLS datagram must not be accepted as proof the peer is handshaking");
        }
    }
}

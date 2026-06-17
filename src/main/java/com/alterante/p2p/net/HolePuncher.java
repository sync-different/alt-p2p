package com.alterante.p2p.net;

import com.alterante.p2p.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;

/**
 * UDP hole puncher.
 *
 * Both peers run this simultaneously after receiving PEER_INFO. The algorithm:
 * 1. Send PUNCH packets to the remote endpoint every {@code punchIntervalMs}.
 * 2. Listen for incoming packets on the same socket.
 * 3. When a PUNCH is received from the remote peer, reply with PUNCH_ACK.
 * 4. When a PUNCH_ACK is received, the hole is punched — return success.
 * 5. If no PUNCH_ACK arrives within {@code timeoutMs}, return failure.
 *
 * NAT / multi-homing handling: accepts a validated PUNCH/PUNCH_ACK from any
 * source address (not just the coordination-reported one) and adopts the observed
 * endpoint. This covers symmetric NAT port remapping, CGNAT, and multi-homed or
 * hairpin peers whose send IP differs from what the coord server saw. The packet
 * is validated (magic + CRC) before its source is trusted, and the subsequent
 * DTLS-PSK handshake authenticates the peer.
 */
public class HolePuncher {

    private static final Logger log = LoggerFactory.getLogger(HolePuncher.class);

    private static final int DEFAULT_PUNCH_INTERVAL_MS = 100;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final DatagramSocket socket;
    /** All endpoints we send PUNCH to (e.g. the peer's public + LAN candidates). */
    private final java.util.List<InetSocketAddress> candidates;
    private final int connectionId;
    private final int punchIntervalMs;
    private final int timeoutMs;

    public HolePuncher(DatagramSocket socket, InetSocketAddress remoteEndpoint, int connectionId) {
        this(socket, remoteEndpoint, connectionId, DEFAULT_PUNCH_INTERVAL_MS, DEFAULT_TIMEOUT_MS);
    }

    public HolePuncher(DatagramSocket socket, InetSocketAddress remoteEndpoint,
                       int connectionId, int punchIntervalMs, int timeoutMs) {
        this(socket, java.util.List.of(remoteEndpoint), connectionId, punchIntervalMs, timeoutMs);
    }

    /** Punch toward multiple candidate endpoints (public + LAN); whichever responds wins. */
    public HolePuncher(DatagramSocket socket, java.util.List<InetSocketAddress> candidates,
                       int connectionId, int punchIntervalMs, int timeoutMs) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one candidate endpoint required");
        }
        this.socket = socket;
        this.candidates = java.util.List.copyOf(candidates);
        this.connectionId = connectionId;
        this.punchIntervalMs = punchIntervalMs;
        this.timeoutMs = timeoutMs;
    }

    /**
     * Run the hole-punch procedure. Blocks until success or timeout.
     */
    public HolePunchResult punch() {
        long startTime = System.currentTimeMillis();
        long deadline = startTime + timeoutMs;

        log.info("Probing UDP connectivity to {} (connId=0x{}, interval={}ms, timeout={}ms)",
                candidates, Integer.toHexString(connectionId), punchIntervalMs, timeoutMs);

        try {
            socket.setSoTimeout(punchIntervalMs);

            byte[] recvBuf = new byte[Packet.MAX_DATAGRAM];
            long nextPunchTime = 0; // send immediately on first iteration
            int punchesSent = 0;
            int packetsReceived = 0;
            int timeouts = 0;

            while (System.currentTimeMillis() < deadline) {
                // Send PUNCH if it's time
                long now = System.currentTimeMillis();
                if (now >= nextPunchTime) {
                    sendPunch();
                    punchesSent++;
                    nextPunchTime = now + punchIntervalMs;
                }

                // Try to receive
                DatagramPacket dgram = new DatagramPacket(recvBuf, recvBuf.length);
                try {
                    socket.receive(dgram);
                } catch (SocketTimeoutException e) {
                    timeouts++;
                    // Log progress periodically
                    if (timeouts % 20 == 0) {
                        log.info("Connectivity probe: sent={}, received={}, timeouts={}, elapsed={}ms",
                                punchesSent, packetsReceived, timeouts,
                                System.currentTimeMillis() - startTime);
                    }
                    continue;
                }

                InetSocketAddress from = new InetSocketAddress(dgram.getAddress(), dgram.getPort());
                packetsReceived++;
                log.debug("Received packet from {} ({} bytes)", from, dgram.getLength());

                // Validate the packet (magic + CRC) BEFORE trusting the source. A stray
                // datagram (NAT keepalive byte, coord chatter, random noise) won't decode.
                Packet packet;
                try {
                    packet = PacketCodec.decode(recvBuf, dgram.getLength());
                } catch (PacketException e) {
                    log.debug("Ignoring bad packet from {} during hole punch: {}", from, e.getMessage());
                    continue;
                }

                if (packet.type() == PacketType.PUNCH || packet.type() == PacketType.PUNCH_ACK) {
                    // Accept a valid PUNCH/PUNCH_ACK from ANY source address. NAT can remap
                    // the port (symmetric NAT) and a multi-homed/hairpin peer can even send
                    // from a different IP than the coordination server observed. We trust the
                    // validated packet and adopt the actual observed endpoint — the DTLS-PSK
                    // handshake that follows authenticates the peer, so a spoofed PUNCH can at
                    // worst cause a failed handshake, never a connection.
                    if (!candidates.contains(from)) {
                        log.info("Adopting peer endpoint {} from {} (candidates were {})",
                                from, packet.type(), candidates);
                    }

                    // Receiving a PUNCH proves bidirectional connectivity; reply with a
                    // PUNCH_ACK to the actual responder but treat either packet as success
                    // (don't wait for an ACK that may be lost once the peer moves to DTLS).
                    if (packet.type() == PacketType.PUNCH) {
                        sendPunchAck(packet.connectionId(), from);
                    }
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (isDirectLan(from.getAddress())) {
                        log.info("Direct LAN connection established in {}ms (via {})", elapsed, from);
                    } else {
                        log.info("Hole punch succeeded in {}ms (NAT traversal via {})", elapsed, from);
                    }
                    return HolePunchResult.succeeded(from, elapsed);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("UDP connectivity probe timed out after {}ms (sent={}, received={}, timeouts={})",
                    elapsed, punchesSent, packetsReceived, timeouts);
            return HolePunchResult.failed(elapsed);

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Hole punch I/O error: {}", e.getMessage());
            return HolePunchResult.failed(elapsed);
        }
    }

    /** A private/loopback/link-local address means no NAT was crossed — a direct LAN/local path, not a hole punch. */
    private static boolean isDirectLan(InetAddress addr) {
        return addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isLoopbackAddress();
    }

    private void sendPunch() throws IOException {
        Packet punch = new Packet(PacketType.PUNCH, (byte) 0, connectionId, 0, null);
        byte[] data = PacketCodec.encode(punch);
        for (InetSocketAddress c : candidates) {
            socket.send(new DatagramPacket(data, data.length, c.getAddress(), c.getPort()));
        }
    }

    private void sendPunchAck(int echoConnId, InetSocketAddress dest) throws IOException {
        Packet ack = new Packet(PacketType.PUNCH_ACK, (byte) 0, echoConnId, 0, null);
        byte[] data = PacketCodec.encode(ack);
        socket.send(new DatagramPacket(data, data.length, dest.getAddress(), dest.getPort()));
    }
}

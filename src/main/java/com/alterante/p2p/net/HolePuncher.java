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
 * Symmetric NAT handling: accepts PUNCH from the expected IP but any port,
 * updating the remote endpoint if the port differs (common with CGNAT).
 */
public class HolePuncher {

    private static final Logger log = LoggerFactory.getLogger(HolePuncher.class);

    private static final int DEFAULT_PUNCH_INTERVAL_MS = 100;
    private static final int DEFAULT_TIMEOUT_MS = 10_000;

    private final DatagramSocket socket;
    private InetSocketAddress remoteEndpoint;
    private final int connectionId;
    private final int punchIntervalMs;
    private final int timeoutMs;

    public HolePuncher(DatagramSocket socket, InetSocketAddress remoteEndpoint, int connectionId) {
        this(socket, remoteEndpoint, connectionId, DEFAULT_PUNCH_INTERVAL_MS, DEFAULT_TIMEOUT_MS);
    }

    public HolePuncher(DatagramSocket socket, InetSocketAddress remoteEndpoint,
                       int connectionId, int punchIntervalMs, int timeoutMs) {
        this.socket = socket;
        this.remoteEndpoint = remoteEndpoint;
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

        log.info("Starting hole punch to {} (connId=0x{}, interval={}ms, timeout={}ms)",
                remoteEndpoint, Integer.toHexString(connectionId), punchIntervalMs, timeoutMs);

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
                        log.info("Hole punch: sent={}, received={}, timeouts={}, elapsed={}ms",
                                punchesSent, packetsReceived, timeouts,
                                System.currentTimeMillis() - startTime);
                    }
                    continue;
                }

                InetSocketAddress from = new InetSocketAddress(dgram.getAddress(), dgram.getPort());
                packetsReceived++;
                log.info("Received packet from {} ({} bytes)", from, dgram.getLength());

                // Accept packets from expected IP (any port) to handle symmetric NAT
                if (!from.getAddress().equals(remoteEndpoint.getAddress())) {
                    log.info("Ignoring packet from unexpected IP {} (expected {})",
                            from, remoteEndpoint.getAddress());
                    continue;
                }

                // Decode
                Packet packet;
                try {
                    packet = PacketCodec.decode(recvBuf, dgram.getLength());
                } catch (PacketException e) {
                    log.debug("Ignoring bad packet during hole punch: {}", e.getMessage());
                    continue;
                }

                if (packet.type() == PacketType.PUNCH) {
                    // If port differs from expected, we're behind a symmetric NAT
                    if (from.getPort() != remoteEndpoint.getPort()) {
                        log.info("Symmetric NAT detected: expected port {}, got {}. Updating remote endpoint.",
                                remoteEndpoint.getPort(), from.getPort());
                        remoteEndpoint = from;
                    }
                    // Reply with PUNCH_ACK (echo their connection ID)
                    sendPunchAck(packet.connectionId());

                    // Receiving a PUNCH from the peer proves bidirectional connectivity:
                    // - Our outbound PUNCH opened a NAT mapping for them to reach us
                    // - Their PUNCH arrived, proving they can send to us
                    // Treat this as success (don't wait for PUNCH_ACK which may be lost
                    // if the other side completes first and moves on to DTLS).
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("Hole punch succeeded in {}ms (received PUNCH from {})", elapsed, from);
                    return HolePunchResult.succeeded(from, elapsed);
                }

                if (packet.type() == PacketType.PUNCH_ACK) {
                    // Update remote endpoint if port changed
                    if (from.getPort() != remoteEndpoint.getPort()) {
                        log.info("Symmetric NAT detected on PUNCH_ACK: updating remote to {}", from);
                        remoteEndpoint = from;
                    }
                    long elapsed = System.currentTimeMillis() - startTime;
                    log.info("Hole punch succeeded in {}ms (received PUNCH_ACK from {})", elapsed, from);
                    return HolePunchResult.succeeded(from, elapsed);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.warn("Hole punch timed out after {}ms (sent={}, received={}, timeouts={})",
                    elapsed, punchesSent, packetsReceived, timeouts);
            return HolePunchResult.failed(elapsed);

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Hole punch I/O error: {}", e.getMessage());
            return HolePunchResult.failed(elapsed);
        }
    }

    private void sendPunch() throws IOException {
        Packet punch = new Packet(PacketType.PUNCH, (byte) 0, connectionId, 0, null);
        byte[] data = PacketCodec.encode(punch);
        socket.send(new DatagramPacket(data, data.length, remoteEndpoint.getAddress(), remoteEndpoint.getPort()));
    }

    private void sendPunchAck(int echoConnId) throws IOException {
        Packet ack = new Packet(PacketType.PUNCH_ACK, (byte) 0, echoConnId, 0, null);
        byte[] data = PacketCodec.encode(ack);
        socket.send(new DatagramPacket(data, data.length, remoteEndpoint.getAddress(), remoteEndpoint.getPort()));
    }
}

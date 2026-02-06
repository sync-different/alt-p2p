package com.alterante.p2p.transport;

import com.alterante.p2p.net.PacketRouter;
import com.alterante.p2p.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Reliable transport layer over the encrypted DTLS channel.
 *
 * Provides reliable, ordered delivery of data packets with:
 * - Sequence numbers and sliding window
 * - Selective acknowledgments (SACK)
 * - RTT estimation and retransmission
 * - AIMD congestion control
 *
 * Sits between PacketRouter (network I/O) and the file transfer layer.
 */
public class ReliableChannel {

    private static final Logger log = LoggerFactory.getLogger(ReliableChannel.class);

    /** DATA header size: 4 bytes chunk index + 8 bytes byte offset */
    private static final int DATA_HEADER_SIZE = 12;

    /** Default max chunk data (conservative, works with any DTLS cipher suite). */
    public static final int MAX_CHUNK_DATA = 1100;

    private final PacketRouter router;
    private final int connectionId;
    private final int maxChunkData;

    // Transport components
    private final RttEstimator rttEstimator = new RttEstimator();
    private final CongestionControl congestion = new CongestionControl();
    private final SlidingWindow sendWindow;
    private volatile ReceiveBuffer recvBuffer;

    // Receiver's advertised window (updated from SACKs)
    private volatile int receiverWindow = 256;

    // Backpressure: sender blocks when window is full
    private final ReentrantLock windowLock = new ReentrantLock();
    private final Condition windowAvailable = windowLock.newCondition();

    // Callbacks
    private Consumer<DataPayload> onDataReceived;
    private Consumer<Packet> onControlPacket;
    private Runnable onAllAcked;

    // State
    private volatile boolean closed;
    private boolean recvBufferInitialized;
    private long totalPacketsSent;
    private long totalPacketsReceived;
    private long totalRetransmissions;
    private long totalSacksReceived;
    private long totalTicks;

    /** Parsed DATA packet payload. */
    public record DataPayload(int chunkIndex, long byteOffset, byte[] data) {}

    /**
     * @param router       the packet router for network I/O
     * @param connectionId connection identifier
     * @param dtlsSendLimit  max bytes the DTLS transport can send per datagram (from DTLSTransport.getSendLimit())
     */
    public ReliableChannel(PacketRouter router, int connectionId, int dtlsSendLimit) {
        this.router = router;
        this.connectionId = connectionId;
        this.maxChunkData = dtlsSendLimit - Packet.HEADER_SIZE - DATA_HEADER_SIZE;

        int initialSeq = new SecureRandom().nextInt();
        this.sendWindow = new SlidingWindow(initialSeq);
        this.recvBuffer = new ReceiveBuffer(0); // re-initialized on first DATA

        log.debug("ReliableChannel created: dtlsSendLimit={}, maxChunkData={}", dtlsSendLimit, maxChunkData);

        // Register packet handlers
        router.addHandler(PacketType.DATA, this::handleData);
        router.addHandler(PacketType.SACK, this::handleSack);

        // Register tick callback for retransmission checks
        router.setTickCallback(this::onTick);
    }

    /** Convenience constructor using the default conservative chunk size. */
    public ReliableChannel(PacketRouter router, int connectionId) {
        this(router, connectionId, MAX_CHUNK_DATA + Packet.HEADER_SIZE + DATA_HEADER_SIZE);
    }

    /** Set callback for received data. */
    public void onDataReceived(Consumer<DataPayload> handler) {
        this.onDataReceived = handler;
    }

    /** Set callback for control packets (FILE_OFFER, FILE_ACCEPT, COMPLETE, VERIFIED, CANCEL). */
    public void onControlPacket(Consumer<Packet> handler) {
        this.onControlPacket = handler;
        for (PacketType type : new PacketType[]{
                PacketType.FILE_OFFER, PacketType.FILE_ACCEPT, PacketType.FILE_REJECT,
                PacketType.COMPLETE, PacketType.VERIFIED, PacketType.CANCEL}) {
            router.addHandler(type, this::dispatchControl);
        }
    }

    /** Set callback for when all in-flight packets are acknowledged. */
    public void onAllAcked(Runnable handler) {
        this.onAllAcked = handler;
    }

    /**
     * Send a DATA packet. Blocks if the send window is full (backpressure).
     *
     * @param chunkIndex packet-level chunk index
     * @param byteOffset absolute byte offset in the file
     * @param data       file data (up to MAX_CHUNK_DATA bytes)
     */
    public void sendData(int chunkIndex, long byteOffset, byte[] data) throws IOException, InterruptedException {
        if (closed) throw new IOException("Channel is closed");

        // Build DATA payload: chunk_index(4) + byte_offset(8) + data
        byte[] payload = new byte[12 + data.length];
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(chunkIndex);
        buf.putLong(byteOffset);
        buf.put(data);

        // Wait for window space
        windowLock.lock();
        try {
            while (!sendWindow.canSend(congestion.effectiveWindow(receiverWindow))) {
                if (closed) throw new IOException("Channel closed while waiting for window");
                windowAvailable.await();
            }

            // Assign sequence and track
            long nowMs = System.currentTimeMillis();
            Packet pkt = new Packet(PacketType.DATA, (byte) 0, connectionId,
                    sendWindow.nextSeq(), payload);
            byte[] encoded = PacketCodec.encode(pkt);
            sendWindow.track(encoded, nowMs);
            router.send(encoded, 0, encoded.length);
            totalPacketsSent++;
        } finally {
            windowLock.unlock();
        }
    }

    /**
     * Send a control packet (FILE_OFFER, COMPLETE, etc.) — not windowed.
     */
    public void sendControl(Packet packet) throws IOException {
        if (closed) throw new IOException("Channel is closed");
        router.sendPacket(packet);
    }

    /** Close the channel and unregister handlers. */
    public void close() {
        closed = true;
        router.removeHandler(PacketType.DATA);
        router.removeHandler(PacketType.SACK);
        router.removeHandler(PacketType.FILE_OFFER);
        router.removeHandler(PacketType.FILE_ACCEPT);
        router.removeHandler(PacketType.FILE_REJECT);
        router.removeHandler(PacketType.COMPLETE);
        router.removeHandler(PacketType.VERIFIED);
        router.removeHandler(PacketType.CANCEL);
        router.setTickCallback(null);

        // Wake any blocked senders
        windowLock.lock();
        try {
            windowAvailable.signalAll();
        } finally {
            windowLock.unlock();
        }
    }

    // --- Incoming packet handlers ---

    private void handleData(Packet pkt) {
        totalPacketsReceived++;
        int seq = pkt.sequence();
        byte[] payload = pkt.payload();

        // Auto-initialize receive buffer from the first DATA packet's sequence
        if (!recvBufferInitialized) {
            recvBuffer = new ReceiveBuffer(seq);
            recvBufferInitialized = true;
            log.debug("Receive buffer initialized with first seq={}", seq);
        }

        List<ReceiveBuffer.DeliveredPacket> delivered = recvBuffer.deliver(seq, payload);

        // Deliver data to the file transfer layer
        if (onDataReceived != null) {
            for (ReceiveBuffer.DeliveredPacket dp : delivered) {
                DataPayload parsed = parseDataPayload(dp.data());
                if (parsed != null) {
                    onDataReceived.accept(parsed);
                }
            }
        }

        // Send SACK if needed
        long now = System.currentTimeMillis();
        if (recvBuffer.shouldSendAck(now)) {
            sendSack();
            recvBuffer.ackSent(now);
        }
    }

    private void handleSack(Packet pkt) {
        totalSacksReceived++;
        byte[] payload = pkt.payload();
        SackInfo sack = SackInfo.decode(payload);

        windowLock.lock();
        try {
            log.debug("SACK received: cumAck={} window={} ranges={} inflight={}",
                    sack.cumulativeAck(), sack.receiverWindow(), sack.ranges().size(), sendWindow.inflightCount());
            int oldBase = sendWindow.baseSeq();
            List<Integer> lost = sendWindow.processSack(sack);
            int newBase = sendWindow.baseSeq();
            log.debug("SACK processed: oldBase={} newBase={} inflight={} mapSize={} remaining={}",
                    oldBase, newBase, sendWindow.inflightCount(), sendWindow.mapSize(), sendWindow.debugInflight());

            // Update receiver window
            receiverWindow = sack.receiverWindow();

            // RTT sample from cumulative ACK advancement (non-retransmitted packets)
            if (newBase != oldBase) {
                // Use the newest acknowledged packet for RTT sample
                int ackedSeq = sack.cumulativeAck();
                if (!sendWindow.wasRetransmitted(ackedSeq)) {
                    long sendTime = sendWindow.getSendTime(ackedSeq);
                    if (sendTime > 0) {
                        rttEstimator.addSample(System.currentTimeMillis() - sendTime);
                    }
                }
                congestion.onAck();
            } else if (!sack.ranges().isEmpty()) {
                // Duplicate ACK (cumulative didn't advance but we got SACK ranges)
                if (congestion.onDuplicateAck()) {
                    // Fast retransmit triggered
                    retransmitLost(lost);
                }
            }

            // Check if all sent data is acknowledged
            if (sendWindow.inflightCount() == 0 && onAllAcked != null) {
                onAllAcked.run();
            }

            // Wake blocked senders
            windowAvailable.signalAll();
        } finally {
            windowLock.unlock();
        }
    }

    private void dispatchControl(Packet pkt) {
        if (onControlPacket != null) {
            onControlPacket.accept(pkt);
        }
    }

    // --- Periodic tick (called every 50ms by PacketRouter) ---

    private void onTick() {
        totalTicks++;
        if (closed) return;

        // Check for RTO-expired packets
        windowLock.lock();
        try {
            long now = System.currentTimeMillis();
            List<SlidingWindow.SentPacket> expired = sendWindow.getRetransmittable(now, rttEstimator.rto());
            for (SlidingWindow.SentPacket pkt : expired) {
                try {
                    router.send(pkt.data, 0, pkt.data.length);
                    sendWindow.markRetransmitted(pkt.sequence, now);
                    rttEstimator.backoff();
                    congestion.onLoss();
                    totalRetransmissions++;
                    log.debug("RTO retransmit seq={}", pkt.sequence);
                } catch (IOException e) {
                    log.warn("Failed to retransmit seq={}: {}", pkt.sequence, e.getMessage());
                }
            }
        } finally {
            windowLock.unlock();
        }

        // Send SACK if timer-based ACK is due
        long now = System.currentTimeMillis();
        if (recvBuffer.shouldSendAck(now)) {
            sendSack();
            recvBuffer.ackSent(now);
        }
    }

    private void retransmitLost(List<Integer> lostSeqs) {
        // Already under windowLock
        long now = System.currentTimeMillis();
        for (int seq : lostSeqs) {
            // Look up the SentPacket to get the encoded data
            List<SlidingWindow.SentPacket> all = sendWindow.getRetransmittable(0, 0);
            for (SlidingWindow.SentPacket sp : all) {
                if (sp.sequence == seq) {
                    try {
                        router.send(sp.data, 0, sp.data.length);
                        sendWindow.markRetransmitted(seq, now);
                        totalRetransmissions++;
                        log.debug("Fast retransmit seq={}", seq);
                    } catch (IOException e) {
                        log.warn("Failed to retransmit seq={}: {}", seq, e.getMessage());
                    }
                    break;
                }
            }
        }
    }

    private void sendSack() {
        try {
            SackInfo sack = recvBuffer.generateSack();
            byte[] payload = sack.encode();
            Packet pkt = new Packet(PacketType.SACK, payload);
            router.sendPacket(pkt);
        } catch (IOException e) {
            log.debug("Failed to send SACK: {}", e.getMessage());
        }
    }

    private DataPayload parseDataPayload(byte[] payload) {
        if (payload.length < 12) {
            log.debug("DATA payload too short: {}", payload.length);
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int chunkIndex = buf.getInt();
        long byteOffset = buf.getLong();
        byte[] data = new byte[payload.length - 12];
        buf.get(data);
        return new DataPayload(chunkIndex, byteOffset, data);
    }

    // --- Stats and configuration ---

    /** Max file data bytes per DATA packet (accounts for DTLS overhead). */
    public int maxChunkData() { return maxChunkData; }
    public long totalPacketsSent() { return totalPacketsSent; }
    public long totalPacketsReceived() { return totalPacketsReceived; }
    public long totalRetransmissions() { return totalRetransmissions; }
    public long totalSacksReceived() { return totalSacksReceived; }
    public long totalTicks() { return totalTicks; }
    public int inflightCount() { return sendWindow.inflightCount(); }
    public int cwnd() { return congestion.windowSize(); }
    public long rto() { return rttEstimator.rto(); }
}

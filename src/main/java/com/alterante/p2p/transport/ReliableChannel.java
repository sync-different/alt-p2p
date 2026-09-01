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
    private final CongestionControl congestion;
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

    // Buffer for control packets that arrive before the handler is registered
    private final java.util.Queue<Packet> pendingControlPackets = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // State
    private volatile boolean closed;
    private boolean relayMode;
    private boolean recvBufferInitialized;

    // NewReno-style fast recovery: reduce the congestion window at most once per
    // loss episode (per RTT), not once per lost packet. inRecovery is cleared when
    // the cumulative ACK advances past the sequence in flight when the loss occurred.
    private boolean inRecovery;
    private int recoveryPoint;
    private long totalPacketsSent;
    private long totalPacketsReceived;
    private long totalRetransmissions;
    private long totalSacksReceived;
    private long totalSacksSent;
    private long totalTicks;

    // Data-progress watchdog (distinct from the PacketRouter's packet-level keepalive check).
    // A wedged reliable stream (an unrecoverable receive gap, or un-acked in-flight data that the
    // peer never ACKs) still exchanges keepalives, so the packet-level dead check never fires and
    // the transfer hangs indefinitely. We track the last forward progress (in-order delivery OR a
    // cumulative-ACK advance) and, if there is pending work but no progress for STALL_TIMEOUT_MS,
    // declare the stream stalled: log the full transport state and tear the connection down so the
    // caller fails fast and can reconnect, instead of hanging forever. See alt-p2p #119 / #120.
    private static final long DEFAULT_STALL_TIMEOUT_MS = 30_000;
    private volatile long stallTimeoutMs = DEFAULT_STALL_TIMEOUT_MS;
    private volatile long lastProgressMs = System.currentTimeMillis();
    private volatile boolean stalled;
    private volatile Runnable onStall;

    /** Parsed DATA packet payload. */
    public record DataPayload(int chunkIndex, long byteOffset, byte[] data) {}

    /**
     * @param router       the packet router for network I/O
     * @param connectionId connection identifier
     * @param dtlsSendLimit  max bytes the DTLS transport can send per datagram (from DTLSTransport.getSendLimit())
     */
    public ReliableChannel(PacketRouter router, int connectionId, int dtlsSendLimit) {
        this(router, connectionId, dtlsSendLimit, null);
    }

    public ReliableChannel(PacketRouter router, int connectionId, int dtlsSendLimit, Integer initialCwnd) {
        this.router = router;
        this.connectionId = connectionId;
        this.maxChunkData = dtlsSendLimit - Packet.HEADER_SIZE - DATA_HEADER_SIZE;
        this.congestion = initialCwnd != null ? new CongestionControl(initialCwnd) : new CongestionControl();

        int initialSeq = new SecureRandom().nextInt();
        this.sendWindow = new SlidingWindow(initialSeq);
        this.recvBuffer = new ReceiveBuffer(0); // re-initialized on first DATA

        log.debug("ReliableChannel created: dtlsSendLimit={}, maxChunkData={}", dtlsSendLimit, maxChunkData);

        // Register packet handlers
        router.addHandler(PacketType.DATA, this::handleData);
        router.addHandler(PacketType.SACK, this::handleSack);

        // Register control packet handlers eagerly so they're buffered if they arrive
        // before the consumer calls onControlPacket()
        for (PacketType type : new PacketType[]{
                PacketType.FILE_OFFER, PacketType.FILE_ACCEPT, PacketType.FILE_REJECT,
                PacketType.COMPLETE, PacketType.VERIFIED, PacketType.CANCEL,
                PacketType.MANIFEST, PacketType.DIR_ENTRY, PacketType.SESSION_COMPLETE}) {
            router.addHandler(type, this::dispatchControl);
        }

        // Register tick callback for retransmission checks
        router.setTickCallback(this::onTick);
    }

    /** Convenience constructor using the default conservative chunk size. */
    public ReliableChannel(PacketRouter router, int connectionId) {
        this(router, connectionId, MAX_CHUNK_DATA + Packet.HEADER_SIZE + DATA_HEADER_SIZE);
    }

    /** Enable relay mode: uses a fixed large congestion window, no aggressive backoff. */
    public void setRelayMode(boolean relay) {
        this.relayMode = relay;
        congestion.setRelayMode(relay);
        if (relay) {
            log.info("Relay mode enabled: using fixed congestion window");
        }
    }

    /** Set callback for received data. */
    public void onDataReceived(Consumer<DataPayload> handler) {
        this.onDataReceived = handler;
    }

    /** Set callback for control packets (FILE_OFFER, FILE_ACCEPT, COMPLETE, VERIFIED, CANCEL). */
    public void onControlPacket(Consumer<Packet> handler) {
        this.onControlPacket = handler;
        // Replay any control packets that arrived before the handler was registered
        Packet buffered;
        while ((buffered = pendingControlPackets.poll()) != null) {
            log.debug("Replaying buffered control packet: {}", buffered.type());
            handler.accept(buffered);
        }
    }

    /** Set callback for when all in-flight packets are acknowledged. */
    public void onAllAcked(Runnable handler) {
        this.onAllAcked = handler;
    }

    /**
     * Set a callback fired once if the reliable stream stalls (pending work but no forward
     * progress for {@link #STALL_TIMEOUT_MS}). The carrier uses this to close its byte pipe so a
     * blocked reader unblocks and the tunnel tears down, rather than hanging forever.
     */
    public void onStall(Runnable handler) {
        this.onStall = handler;
    }

    /**
     * Override the no-progress stall deadline (default 30s). The carrier can shorten this for a
     * more responsive tunnel teardown; tests use a small value to exercise the watchdog quickly.
     */
    public void setStallTimeoutMs(long ms) {
        this.stallTimeoutMs = ms;
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
        router.removeHandler(PacketType.MANIFEST);
        router.removeHandler(PacketType.DIR_ENTRY);
        router.removeHandler(PacketType.SESSION_COMPLETE);
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
        if (!delivered.isEmpty()) lastProgressMs = System.currentTimeMillis(); // watchdog: forward progress

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

        long now = System.currentTimeMillis();
        windowLock.lock();
        try {
            int oldBase = sendWindow.baseSeq();
            List<Integer> lost = sendWindow.processSack(sack);
            int newBase = sendWindow.baseSeq();

            // Update receiver window
            receiverWindow = sack.receiverWindow();

            // Forward progress: cumulative ACK advanced (real, in-order delivery).
            if (newBase != oldBase) {
                lastProgressMs = now; // watchdog: forward progress
                int ackedSeq = sack.cumulativeAck();
                if (!sendWindow.wasRetransmitted(ackedSeq)) {
                    long sendTime = sendWindow.getSendTime(ackedSeq);
                    if (sendTime > 0) {
                        rttEstimator.addSample(now - sendTime);
                    }
                }
                rttEstimator.resetBackoff();          // FIX: undo RTO backoff on progress
                congestion.onAck();
                // Exit fast recovery once we've ACKed past the loss point (NewReno).
                if (inRecovery && !SlidingWindow.seqBefore(newBase - 1, recoveryPoint)) {
                    inRecovery = false;
                }
            }

            // FIX: SACK-driven retransmit. The receiver's SACK ranges already name the
            // missing sequences; retransmit them promptly instead of waiting for 3
            // duplicate ACKs — unreachable once cwnd has collapsed to a few packets.
            if (!lost.isEmpty()) {
                boolean sent = sackRetransmit(lost, now);
                // Reduce the window at most once per loss episode (NewReno recovery).
                if (sent && !inRecovery) {
                    congestion.onLoss();
                    inRecovery = true;
                    recoveryPoint = sendWindow.nextSeq() - 1;
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
        Consumer<Packet> handler = onControlPacket;
        if (handler != null) {
            handler.accept(pkt);
        } else {
            log.debug("Buffering control packet (no handler yet): {}", pkt.type());
            pendingControlPackets.add(pkt);
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
                    if (!relayMode) rttEstimator.backoff();
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

        // Data-progress watchdog: fires once if there is pending work (un-acked in-flight data
        // and/or a buffered receive gap) but no forward progress for STALL_TIMEOUT_MS. Distinct
        // from the router's packet keepalive, which stays satisfied even when the stream is wedged.
        // Opt-in per carrier via onStall(): the tunnel enables it (a stalled stream must tear down
        // and reconnect); file transfer and the reliability unit tests do not, so their behaviour
        // and their own timeouts are unchanged.
        if (onStall != null && !stalled && !closed) {
            int inflight;
            windowLock.lock();
            try { inflight = sendWindow.inflightCount(); } finally { windowLock.unlock(); }
            int buffered = recvBufferInitialized ? recvBuffer.bufferedCount() : 0;
            if ((inflight > 0 || buffered > 0) && (now - lastProgressMs) >= stallTimeoutMs) {
                stalled = true;
                log.warn("Reliable-stream STALL: no progress for {}ms with pending work (inflight={}, buffered={}) — {}",
                        now - lastProgressMs, inflight, buffered, debugState());
                Runnable r = onStall;
                if (r != null) {
                    try { r.run(); } catch (RuntimeException e) { log.warn("onStall handler threw: {}", e.getMessage()); }
                }
                router.requestStop("reliable-stream data stall");
            }
        }
    }

    /** One-line transport-state snapshot for stall diagnosis (#120). */
    public String debugState() {
        windowLock.lock();
        try {
            return String.format(
                "send[base=%d next=%d inflight=%d cwnd=%d recvWin=%d rto=%dms srtt=%.0fms] "
                + "recv[expected=%d buffered=%d advWin=%d] stats[sent=%d recv=%d retx=%d sacksRx=%d sacksTx=%d ticks=%d]",
                sendWindow.baseSeq(), sendWindow.nextSeq(), sendWindow.inflightCount(),
                congestion.windowSize(), receiverWindow, rttEstimator.rto(), rttEstimator.srtt(),
                recvBufferInitialized ? recvBuffer.expectedSeq() : -1,
                recvBufferInitialized ? recvBuffer.bufferedCount() : 0,
                recvBufferInitialized ? recvBuffer.advertisedWindow() : -1,
                totalPacketsSent, totalPacketsReceived, totalRetransmissions, totalSacksReceived, totalSacksSent, totalTicks);
        } finally {
            windowLock.unlock();
        }
    }

    /**
     * SACK-driven retransmit (already under windowLock). Retransmits each gap the
     * receiver reported missing, guarded so a gap appearing in many consecutive
     * 10ms SACKs is resent at most once per ~RTT rather than on every SACK. Does
     * NOT back off RTO — a SACK gap is a confident loss signal, not a timeout.
     *
     * @return true if at least one packet was retransmitted
     */
    private boolean sackRetransmit(List<Integer> lostSeqs, long now) {
        long guard = Math.max((long) rttEstimator.srtt(), 20);
        boolean sent = false;
        for (int seq : lostSeqs) {
            SlidingWindow.SentPacket sp = sendWindow.getInFlight(seq);
            if (sp == null || sp.acked || (now - sp.lastSentMs) < guard) continue;
            try {
                router.send(sp.data, 0, sp.data.length);
                sendWindow.markRetransmitted(seq, now);
                totalRetransmissions++;
                sent = true;
                log.debug("SACK retransmit seq={}", seq);
            } catch (IOException e) {
                log.warn("Failed to SACK-retransmit seq={}: {}", seq, e.getMessage());
            }
        }
        return sent;
    }

    private void sendSack() {
        try {
            totalSacksSent++;
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
    /** Thread-safe: SlidingWindow must be read under the same lock as track()/processSack(). */
    public int inflightCount() {
        windowLock.lock();
        try {
            return sendWindow.inflightCount();
        } finally {
            windowLock.unlock();
        }
    }
    public int cwnd() { return congestion.windowSize(); }
    public long rto() { return rttEstimator.rto(); }
}

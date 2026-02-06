package com.alterante.p2p.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Receiver-side packet reorder buffer and SACK generation.
 *
 * Accepts incoming packets by sequence number, buffers out-of-order packets,
 * delivers contiguous data, and generates SACK information.
 *
 * The receiver window is adaptive: starts at {@code INITIAL_WINDOW}, grows
 * toward {@code MAX_WINDOW} when deliveries are clean (no reordering), and
 * shrinks when the out-of-order buffer fills up. This lets the sender ramp
 * up on clean links without over-buffering on lossy ones.
 *
 * Thread-safety: callers must synchronize externally.
 */
public class ReceiveBuffer {

    /** Delivered data from a single packet. */
    public record DeliveredPacket(int sequence, byte[] data) {}

    private static final int INITIAL_WINDOW = 256;
    private static final int MAX_WINDOW = 512;
    private static final int MIN_WINDOW = 32;
    private static final int DELAYED_ACK_THRESHOLD = 2;
    private static final long ACK_TIMER_MS = 10;

    /** How many consecutive in-order deliveries before we grow the window. */
    private static final int GROW_THRESHOLD = 128;
    /** Growth amount each time the threshold is reached. */
    private static final int GROW_INCREMENT = 32;
    /** Shrink when out-of-order buffer exceeds this fraction of the window. */
    private static final double SHRINK_PRESSURE = 0.5;

    private int expectedSeq;   // next expected sequence (cumulative ACK base)
    private final Map<Integer, byte[]> outOfOrder = new HashMap<>();

    private int packetsSinceLastAck;
    private long lastAckTimeMs;
    private boolean gapDetected;

    // Adaptive window state
    private int maxWindow = INITIAL_WINDOW;
    private int consecutiveInOrder;

    public ReceiveBuffer(int initialExpectedSeq) {
        this.expectedSeq = initialExpectedSeq;
    }

    /**
     * Accept an incoming packet.
     *
     * @param seq     the packet's sequence number
     * @param payload the packet's data
     * @return list of contiguous packets now deliverable (in order), or empty if buffered/duplicate
     */
    public List<DeliveredPacket> deliver(int seq, byte[] payload) {
        // Duplicate or old packet
        if (SlidingWindow.seqBefore(seq, expectedSeq)) {
            return Collections.emptyList();
        }

        if (seq == expectedSeq) {
            // In-order: deliver this packet + any contiguous buffered packets
            List<DeliveredPacket> result = new ArrayList<>();
            result.add(new DeliveredPacket(seq, payload));
            expectedSeq++;

            // Check for contiguous buffered packets
            while (outOfOrder.containsKey(expectedSeq)) {
                byte[] bufferedData = outOfOrder.remove(expectedSeq);
                result.add(new DeliveredPacket(expectedSeq, bufferedData));
                expectedSeq++;
            }

            packetsSinceLastAck += result.size();

            // Track consecutive in-order deliveries for window growth
            if (outOfOrder.isEmpty()) {
                consecutiveInOrder += result.size();
                if (consecutiveInOrder >= GROW_THRESHOLD && maxWindow < MAX_WINDOW) {
                    maxWindow = Math.min(maxWindow + GROW_INCREMENT, MAX_WINDOW);
                    consecutiveInOrder = 0;
                }
            } else {
                consecutiveInOrder = 0;
            }

            return result;
        } else {
            // Out-of-order: buffer it
            if (!outOfOrder.containsKey(seq)) {
                outOfOrder.put(seq, payload);
                gapDetected = true;
            }
            packetsSinceLastAck++;
            consecutiveInOrder = 0;

            // Shrink window if buffer pressure is high
            if (outOfOrder.size() > maxWindow * SHRINK_PRESSURE && maxWindow > MIN_WINDOW) {
                maxWindow = Math.max(maxWindow / 2, MIN_WINDOW);
            }

            return Collections.emptyList();
        }
    }

    /**
     * Generate SACK information for the receiver's current state.
     */
    public SackInfo generateSack() {
        int cumAck = expectedSeq - 1; // last contiguously received

        List<SackRange> ranges = new ArrayList<>();
        if (!outOfOrder.isEmpty()) {
            // Sort out-of-order keys by modular order
            List<Integer> sortedSeqs = new ArrayList<>(outOfOrder.keySet());
            sortedSeqs.sort((a, b) -> {
                if (a == b) return 0;
                return SlidingWindow.seqAfter(a, b) ? 1 : -1;
            });

            // Build ranges from contiguous sequences
            int rangeStart = sortedSeqs.get(0);
            int rangePrev = rangeStart;
            for (int i = 1; i < sortedSeqs.size(); i++) {
                int seq = sortedSeqs.get(i);
                if (seq == rangePrev + 1) {
                    rangePrev = seq;
                } else {
                    ranges.add(new SackRange(rangeStart, rangePrev));
                    rangeStart = seq;
                    rangePrev = seq;
                }
            }
            ranges.add(new SackRange(rangeStart, rangePrev));
        }

        return new SackInfo(cumAck, advertisedWindow(), ranges);
    }

    /**
     * Whether the receiver should send an ACK now.
     */
    public boolean shouldSendAck(long nowMs) {
        if (packetsSinceLastAck == 0) return false;
        if (gapDetected) return true;
        if (packetsSinceLastAck >= DELAYED_ACK_THRESHOLD) return true;
        if ((nowMs - lastAckTimeMs) >= ACK_TIMER_MS) return true;
        return false;
    }

    /**
     * Reset ACK tracking after sending a SACK.
     */
    public void ackSent(long nowMs) {
        packetsSinceLastAck = 0;
        lastAckTimeMs = nowMs;
        gapDetected = false;
    }

    /** Available receiver window (in packets). */
    public int advertisedWindow() {
        return maxWindow - outOfOrder.size();
    }

    /** Current max window size (for monitoring). */
    public int maxWindow() {
        return maxWindow;
    }

    /** Next expected sequence number (= cumulative ACK + 1). */
    public int expectedSeq() {
        return expectedSeq;
    }

    /** Number of out-of-order buffered packets. */
    public int bufferedCount() {
        return outOfOrder.size();
    }
}

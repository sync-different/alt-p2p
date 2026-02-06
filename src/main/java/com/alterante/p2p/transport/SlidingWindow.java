package com.alterante.p2p.transport;

import java.util.*;

/**
 * Sender-side sliding window with per-packet tracking.
 *
 * Tracks in-flight packets, processes SACKs, detects gaps for retransmission,
 * and manages sequence number assignment.
 *
 * Thread-safety: callers must synchronize externally.
 */
public class SlidingWindow {

    /** Metadata for a sent packet. */
    public static class SentPacket {
        final int sequence;
        final byte[] data;          // full encoded packet for retransmission
        final long firstSentMs;
        long lastSentMs;
        boolean acked;
        boolean retransmitted;
        int retransmitCount;

        SentPacket(int sequence, byte[] data, long nowMs) {
            this.sequence = sequence;
            this.data = data;
            this.firstSentMs = nowMs;
            this.lastSentMs = nowMs;
        }
    }

    private int baseSeq;        // oldest un-ACKed sequence
    private int nextSeq;        // next sequence to assign
    private final LinkedHashMap<Integer, SentPacket> inFlight = new LinkedHashMap<>();

    public SlidingWindow(int initialSeq) {
        this.baseSeq = initialSeq;
        this.nextSeq = initialSeq;
    }

    /**
     * Assign the next sequence number and track the packet.
     *
     * @param encodedPacket full encoded packet bytes (for retransmission)
     * @param nowMs         current time in ms
     * @return the assigned sequence number
     */
    public int track(byte[] encodedPacket, long nowMs) {
        int seq = nextSeq++;
        inFlight.put(seq, new SentPacket(seq, encodedPacket, nowMs));
        return seq;
    }

    /**
     * Process a SACK from the receiver.
     *
     * @return list of sequence numbers detected as lost (gaps before SACK ranges)
     */
    public List<Integer> processSack(SackInfo sack) {
        int cumAck = sack.cumulativeAck();

        // 1. Advance baseSeq: everything <= cumAck is fully received
        Iterator<Map.Entry<Integer, SentPacket>> it = inFlight.entrySet().iterator();
        while (it.hasNext()) {
            SentPacket pkt = it.next().getValue();
            if (seqLessOrEqual(pkt.sequence, cumAck)) {
                pkt.acked = true;
                it.remove();
            } else {
                break; // LinkedHashMap maintains insertion order
            }
        }
        baseSeq = cumAck + 1;

        // 2. Mark selectively ACKed packets
        for (SackRange range : sack.ranges()) {
            for (Map.Entry<Integer, SentPacket> entry : inFlight.entrySet()) {
                int seq = entry.getKey();
                if (seqInRange(seq, range.startSeq(), range.endSeq())) {
                    entry.getValue().acked = true;
                }
            }
        }

        // 3. Detect gaps: un-acked packets before any SACK range
        List<Integer> lost = new ArrayList<>();
        if (!sack.ranges().isEmpty()) {
            for (Map.Entry<Integer, SentPacket> entry : inFlight.entrySet()) {
                SentPacket pkt = entry.getValue();
                if (!pkt.acked && seqBefore(pkt.sequence, sack.ranges().get(0).startSeq())) {
                    lost.add(pkt.sequence);
                }
            }
        }

        return lost;
    }

    /**
     * Get packets whose RTO has expired and need retransmission.
     */
    public List<SentPacket> getRetransmittable(long nowMs, long rtoMs) {
        List<SentPacket> result = new ArrayList<>();
        for (SentPacket pkt : inFlight.values()) {
            if (!pkt.acked && (nowMs - pkt.lastSentMs) >= rtoMs) {
                result.add(pkt);
            }
        }
        return result;
    }

    /**
     * Mark a packet as retransmitted (updates timestamp and retransmit count).
     */
    public void markRetransmitted(int sequence, long nowMs) {
        SentPacket pkt = inFlight.get(sequence);
        if (pkt != null) {
            pkt.retransmitted = true;
            pkt.retransmitCount++;
            pkt.lastSentMs = nowMs;
        }
    }

    /**
     * Check if a packet was retransmitted (for Karn's algorithm: don't sample RTT).
     */
    public boolean wasRetransmitted(int sequence) {
        SentPacket pkt = inFlight.get(sequence);
        return pkt != null && pkt.retransmitted;
    }

    /**
     * Get the send time of a packet (for RTT measurement).
     */
    public long getSendTime(int sequence) {
        SentPacket pkt = inFlight.get(sequence);
        return pkt != null ? pkt.firstSentMs : -1;
    }

    /** Number of un-ACKed packets in flight. Thread-safe snapshot. */
    public int inflightCount() {
        int count = 0;
        for (SentPacket pkt : inFlight.values().toArray(new SentPacket[0])) {
            if (!pkt.acked) count++;
        }
        return count;
    }

    /** Can we send more packets given the effective window? */
    public boolean canSend(int effectiveWindow) {
        return inflightCount() < effectiveWindow;
    }

    public int baseSeq() { return baseSeq; }
    public int nextSeq() { return nextSeq; }
    public int mapSize() { return inFlight.size(); }

    /** Debug: return sequences of remaining in-flight packets (up to 20). Thread-safe snapshot. */
    public String debugInflight() {
        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (SentPacket pkt : inFlight.values().toArray(new SentPacket[0])) {
            if (count > 0) sb.append(", ");
            sb.append(pkt.sequence).append(pkt.acked ? "(acked)" : "");
            if (++count >= 20) { sb.append("..."); break; }
        }
        sb.append("]");
        return sb.toString();
    }

    // --- Sequence number comparison with wraparound ---

    /** a is "after" b (modular arithmetic). */
    static boolean seqAfter(int a, int b) {
        return (a - b) > 0;
    }

    /** a is "before" b. */
    static boolean seqBefore(int a, int b) {
        return (b - a) > 0;
    }

    /** a <= b (modular). */
    static boolean seqLessOrEqual(int a, int b) {
        return a == b || seqBefore(a, b);
    }

    /** seq is in [start, end] range (modular, inclusive). */
    static boolean seqInRange(int seq, int start, int end) {
        if (start == end) return seq == start;
        // Handles wraparound: seq in [start, end] iff
        // (seq - start) unsigned <= (end - start) unsigned
        return Integer.compareUnsigned(seq - start, end - start) <= 0;
    }
}

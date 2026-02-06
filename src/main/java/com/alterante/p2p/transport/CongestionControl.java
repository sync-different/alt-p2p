package com.alterante.p2p.transport;

/**
 * AIMD (Additive Increase, Multiplicative Decrease) congestion control.
 *
 * <pre>
 * Slow Start (cwnd < ssthresh):
 *   On each ACK: cwnd += 1 (doubles each RTT)
 *
 * Congestion Avoidance (cwnd >= ssthresh):
 *   On each ACK: cwnd += 1/cwnd (linear growth: +1 per RTT)
 *
 * On packet loss:
 *   ssthresh = max(cwnd / 2, 2)
 *   cwnd = ssthresh
 * </pre>
 */
public class CongestionControl {

    private static final double INITIAL_CWND = 32.0;
    private static final int INITIAL_SSTHRESH = 2048;
    private static final int MIN_SSTHRESH = 2;
    private static final int FAST_RETRANSMIT_THRESHOLD = 3;

    private double cwnd = INITIAL_CWND;
    private int ssthresh = INITIAL_SSTHRESH;
    private int duplicateAckCount;

    /**
     * Called when a new (non-duplicate) ACK is received.
     */
    public void onAck() {
        duplicateAckCount = 0;
        if (cwnd < ssthresh) {
            // Slow start: exponential growth
            cwnd += 1.0;
        } else {
            // Congestion avoidance: linear growth
            cwnd += 1.0 / cwnd;
        }
    }

    /**
     * Called when a duplicate ACK is received.
     * Triggers fast retransmit after 3 duplicates.
     *
     * @return true if fast retransmit should be triggered
     */
    public boolean onDuplicateAck() {
        duplicateAckCount++;
        if (duplicateAckCount >= FAST_RETRANSMIT_THRESHOLD) {
            onLoss();
            return true;
        }
        return false;
    }

    /**
     * Called when packet loss is detected (RTO or 3 duplicate ACKs).
     */
    public void onLoss() {
        ssthresh = Math.max((int) (cwnd / 2), MIN_SSTHRESH);
        cwnd = ssthresh;
        duplicateAckCount = 0;
    }

    /** Current congestion window size in packets (floored). */
    public int windowSize() {
        return (int) Math.floor(cwnd);
    }

    /** Effective send window: min(cwnd, receiverWindow). */
    public int effectiveWindow(int receiverWindow) {
        return Math.min(windowSize(), receiverWindow);
    }

    /** Current slow-start threshold. */
    public int ssthresh() {
        return ssthresh;
    }

    /** Raw cwnd value (for testing). */
    double cwnd() {
        return cwnd;
    }
}

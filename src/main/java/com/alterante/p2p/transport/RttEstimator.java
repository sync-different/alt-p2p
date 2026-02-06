package com.alterante.p2p.transport;

/**
 * RTT estimation and RTO calculation using Karn's algorithm + EWMA.
 *
 * <pre>
 * On each non-retransmitted packet's ACK:
 *   sample_rtt = now - send_time
 *   srtt = (1 - ALPHA) * srtt + ALPHA * sample_rtt
 *   rttvar = (1 - BETA) * rttvar + BETA * |sample_rtt - srtt|
 *   rto = srtt + 4 * rttvar
 *   rto = clamp(rto, MIN_RTO, MAX_RTO)
 *
 * On retransmission timeout:
 *   rto = rto * 2  (exponential backoff)
 *   Do NOT update srtt from retransmitted packets (Karn's algorithm)
 * </pre>
 */
public class RttEstimator {

    private static final double ALPHA = 0.125;
    private static final double BETA = 0.25;
    private static final long MIN_RTO = 200;
    private static final long MAX_RTO = 10_000;
    private static final long INITIAL_RTO = 1000;

    private double srtt;
    private double rttvar;
    private long rto = INITIAL_RTO;
    private boolean hasFirstSample;

    /**
     * Record a new RTT sample from a non-retransmitted packet.
     * Do NOT call this for retransmitted packets (Karn's algorithm).
     */
    public void addSample(long sampleRttMs) {
        if (!hasFirstSample) {
            srtt = sampleRttMs;
            rttvar = sampleRttMs / 2.0;
            hasFirstSample = true;
        } else {
            rttvar = (1 - BETA) * rttvar + BETA * Math.abs(sampleRttMs - srtt);
            srtt = (1 - ALPHA) * srtt + ALPHA * sampleRttMs;
        }
        rto = Math.round(srtt + 4 * rttvar);
        rto = Math.max(MIN_RTO, Math.min(MAX_RTO, rto));
    }

    /**
     * Exponential backoff on retransmission timeout.
     */
    public void backoff() {
        rto = Math.min(rto * 2, MAX_RTO);
    }

    /** Current retransmission timeout in milliseconds. */
    public long rto() {
        return rto;
    }

    /** Current smoothed RTT in milliseconds, or 0 if no samples yet. */
    public double srtt() {
        return srtt;
    }

    /** Whether at least one RTT sample has been recorded. */
    public boolean hasSamples() {
        return hasFirstSample;
    }
}

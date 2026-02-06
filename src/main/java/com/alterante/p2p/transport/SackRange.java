package com.alterante.p2p.transport;

/**
 * A range of selectively acknowledged sequence numbers (inclusive on both ends).
 */
public record SackRange(int startSeq, int endSeq) {

    @Override
    public String toString() {
        return String.format("[%d-%d]", startSeq, endSeq);
    }
}

package com.alterante.p2p.transport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selective Acknowledgment information, sent by the receiver.
 *
 * <pre>
 * Wire format (within Packet payload):
 * Bytes 0-3:   Cumulative ACK (int)   — all packets up to this seq are received
 * Bytes 4-7:   Receiver Window (int)  — available buffer space in packets
 * Bytes 8+:    SACK Ranges            — 8 bytes each (startSeq:4 + endSeq:4)
 * </pre>
 */
public record SackInfo(int cumulativeAck, int receiverWindow, List<SackRange> ranges) {

    private static final int HEADER_SIZE = 8; // cumulative ACK + receiver window
    private static final int RANGE_SIZE = 8;  // startSeq + endSeq

    public SackInfo {
        ranges = List.copyOf(ranges);
    }

    public SackInfo(int cumulativeAck, int receiverWindow) {
        this(cumulativeAck, receiverWindow, Collections.emptyList());
    }

    /**
     * Encode this SACK info into a byte array suitable for a Packet payload.
     */
    public byte[] encode() {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + ranges.size() * RANGE_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        buf.putInt(cumulativeAck);
        buf.putInt(receiverWindow);
        for (SackRange r : ranges) {
            buf.putInt(r.startSeq());
            buf.putInt(r.endSeq());
        }
        return buf.array();
    }

    /**
     * Decode a SACK info from a Packet payload.
     */
    public static SackInfo decode(byte[] payload) {
        if (payload.length < HEADER_SIZE) {
            throw new IllegalArgumentException("SACK payload too short: " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int cumulativeAck = buf.getInt();
        int receiverWindow = buf.getInt();
        List<SackRange> ranges = new ArrayList<>();
        while (buf.remaining() >= RANGE_SIZE) {
            ranges.add(new SackRange(buf.getInt(), buf.getInt()));
        }
        return new SackInfo(cumulativeAck, receiverWindow, ranges);
    }

    @Override
    public String toString() {
        return String.format("SACK[cumAck=%d, recvWin=%d, ranges=%s]",
                cumulativeAck, receiverWindow, ranges);
    }
}

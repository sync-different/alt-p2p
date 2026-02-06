package com.alterante.p2p.transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SlidingWindowTest {

    @Test
    void trackAndBasicSack() {
        SlidingWindow w = new SlidingWindow(0);
        w.track(new byte[]{1}, 100);  // seq 0
        w.track(new byte[]{2}, 100);  // seq 1
        w.track(new byte[]{3}, 100);  // seq 2

        assertEquals(0, w.baseSeq());
        assertEquals(3, w.nextSeq());
        assertEquals(3, w.inflightCount());

        // ACK all 3
        w.processSack(new SackInfo(2, 256));
        assertEquals(3, w.baseSeq());
        assertEquals(0, w.inflightCount());
    }

    @Test
    void cumulativeAckAdvancesBase() {
        SlidingWindow w = new SlidingWindow(10);
        w.track(new byte[]{}, 0);  // seq 10
        w.track(new byte[]{}, 0);  // seq 11
        w.track(new byte[]{}, 0);  // seq 12
        w.track(new byte[]{}, 0);  // seq 13

        // ACK up to seq 11
        w.processSack(new SackInfo(11, 256));
        assertEquals(12, w.baseSeq());
        assertEquals(2, w.inflightCount());
    }

    @Test
    void sackRangesMarkSelectiveAcks() {
        SlidingWindow w = new SlidingWindow(0);
        w.track(new byte[]{}, 0);  // seq 0
        w.track(new byte[]{}, 0);  // seq 1
        w.track(new byte[]{}, 0);  // seq 2
        w.track(new byte[]{}, 0);  // seq 3
        w.track(new byte[]{}, 0);  // seq 4

        // cumAck=0 (seq 0 received), SACK range [3,4] (seq 3,4 received but 1,2 missing)
        List<Integer> lost = w.processSack(new SackInfo(0, 256,
                List.of(new SackRange(3, 4))));

        // Seq 1 and 2 are detected as lost (gap before SACK range)
        assertEquals(List.of(1, 2), lost);
        assertEquals(1, w.baseSeq());
        // inflight: seq 1, 2 (unacked), 3, 4 (sack-acked but still tracked until cumAck advances)
        // Actually 3 and 4 are marked acked but still in the map.
        // inflightCount counts only un-acked
        assertEquals(2, w.inflightCount());
    }

    @Test
    void canSendRespectsWindow() {
        SlidingWindow w = new SlidingWindow(0);
        assertTrue(w.canSend(10));

        for (int i = 0; i < 10; i++) {
            w.track(new byte[]{}, 0);
        }
        assertFalse(w.canSend(10));  // 10 in flight, window = 10
        assertTrue(w.canSend(11));   // window = 11

        // ACK 5 packets
        w.processSack(new SackInfo(4, 256));
        assertTrue(w.canSend(10));   // 5 in flight, window = 10
    }

    @Test
    void retransmittablePackets() {
        SlidingWindow w = new SlidingWindow(0);
        w.track(new byte[]{1}, 1000);  // seq 0
        w.track(new byte[]{2}, 1000);  // seq 1
        w.track(new byte[]{3}, 2000);  // seq 2

        // At time 2500, RTO=1000: packets sent at 1000 are expired
        List<SlidingWindow.SentPacket> rto = w.getRetransmittable(2500, 1000);
        assertEquals(2, rto.size());
        assertEquals(0, rto.get(0).sequence);
        assertEquals(1, rto.get(1).sequence);

        // Packet sent at 2000 is not yet expired
        assertArrayEquals(new byte[]{1}, rto.get(0).data);
    }

    @Test
    void markRetransmittedUpdatesState() {
        SlidingWindow w = new SlidingWindow(0);
        w.track(new byte[]{}, 1000);  // seq 0

        assertFalse(w.wasRetransmitted(0));
        w.markRetransmitted(0, 2000);
        assertTrue(w.wasRetransmitted(0));
        assertEquals(1000, w.getSendTime(0));

        // After retransmit, RTO check uses new lastSentMs
        List<SlidingWindow.SentPacket> rto = w.getRetransmittable(2500, 1000);
        assertTrue(rto.isEmpty()); // 2500 - 2000 = 500 < 1000
    }

    @Test
    void sequenceWraparound() {
        // Start near MAX_VALUE
        int start = Integer.MAX_VALUE - 2;
        SlidingWindow w = new SlidingWindow(start);

        w.track(new byte[]{}, 0);  // seq MAX_VALUE-2
        w.track(new byte[]{}, 0);  // seq MAX_VALUE-1
        w.track(new byte[]{}, 0);  // seq MAX_VALUE
        w.track(new byte[]{}, 0);  // seq MIN_VALUE (wraps)
        w.track(new byte[]{}, 0);  // seq MIN_VALUE+1

        assertEquals(5, w.inflightCount());

        // ACK the first 3 (up to MAX_VALUE)
        w.processSack(new SackInfo(Integer.MAX_VALUE, 256));
        assertEquals(Integer.MIN_VALUE, w.baseSeq());
        assertEquals(2, w.inflightCount());

        // ACK all remaining
        w.processSack(new SackInfo(Integer.MIN_VALUE + 1, 256));
        assertEquals(0, w.inflightCount());
    }

    @Test
    void seqAfterModularArithmetic() {
        // Basic
        assertTrue(SlidingWindow.seqAfter(5, 3));
        assertFalse(SlidingWindow.seqAfter(3, 5));
        assertFalse(SlidingWindow.seqAfter(3, 3));

        // Wraparound: MIN_VALUE is "after" MAX_VALUE (by 1)
        assertTrue(SlidingWindow.seqAfter(Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertFalse(SlidingWindow.seqAfter(Integer.MAX_VALUE, Integer.MIN_VALUE));
    }

    @Test
    void seqInRangeHandlesWraparound() {
        assertTrue(SlidingWindow.seqInRange(5, 3, 7));
        assertTrue(SlidingWindow.seqInRange(3, 3, 7));
        assertTrue(SlidingWindow.seqInRange(7, 3, 7));
        assertFalse(SlidingWindow.seqInRange(2, 3, 7));
        assertFalse(SlidingWindow.seqInRange(8, 3, 7));

        // Wraparound range [MAX_VALUE-1, MIN_VALUE+1]
        int start = Integer.MAX_VALUE - 1;
        int end = Integer.MIN_VALUE + 1;
        assertTrue(SlidingWindow.seqInRange(Integer.MAX_VALUE - 1, start, end));
        assertTrue(SlidingWindow.seqInRange(Integer.MAX_VALUE, start, end));
        assertTrue(SlidingWindow.seqInRange(Integer.MIN_VALUE, start, end));
        assertTrue(SlidingWindow.seqInRange(Integer.MIN_VALUE + 1, start, end));
        assertFalse(SlidingWindow.seqInRange(Integer.MAX_VALUE - 2, start, end));
    }
}

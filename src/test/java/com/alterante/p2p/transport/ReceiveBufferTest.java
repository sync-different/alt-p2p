package com.alterante.p2p.transport;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReceiveBufferTest {

    @Test
    void inOrderDelivery() {
        ReceiveBuffer buf = new ReceiveBuffer(0);

        List<ReceiveBuffer.DeliveredPacket> d0 = buf.deliver(0, new byte[]{10});
        assertEquals(1, d0.size());
        assertEquals(0, d0.get(0).sequence());
        assertArrayEquals(new byte[]{10}, d0.get(0).data());

        List<ReceiveBuffer.DeliveredPacket> d1 = buf.deliver(1, new byte[]{20});
        assertEquals(1, d1.size());
        assertEquals(1, d1.get(0).sequence());

        assertEquals(2, buf.expectedSeq());
        assertEquals(0, buf.bufferedCount());
    }

    @Test
    void outOfOrderBufferingAndDelivery() {
        ReceiveBuffer buf = new ReceiveBuffer(0);

        // Receive seq 2 first (out of order)
        List<ReceiveBuffer.DeliveredPacket> d2 = buf.deliver(2, new byte[]{30});
        assertTrue(d2.isEmpty()); // buffered, not delivered
        assertEquals(1, buf.bufferedCount());

        // Receive seq 1 (still gap at 0)
        List<ReceiveBuffer.DeliveredPacket> d1 = buf.deliver(1, new byte[]{20});
        assertTrue(d1.isEmpty());
        assertEquals(2, buf.bufferedCount());

        // Receive seq 0 — should deliver 0, 1, 2 in order
        List<ReceiveBuffer.DeliveredPacket> d0 = buf.deliver(0, new byte[]{10});
        assertEquals(3, d0.size());
        assertEquals(0, d0.get(0).sequence());
        assertEquals(1, d0.get(1).sequence());
        assertEquals(2, d0.get(2).sequence());
        assertArrayEquals(new byte[]{10}, d0.get(0).data());
        assertArrayEquals(new byte[]{20}, d0.get(1).data());
        assertArrayEquals(new byte[]{30}, d0.get(2).data());

        assertEquals(3, buf.expectedSeq());
        assertEquals(0, buf.bufferedCount());
    }

    @Test
    void duplicatePacketsIgnored() {
        ReceiveBuffer buf = new ReceiveBuffer(0);

        buf.deliver(0, new byte[]{10});
        // Duplicate of seq 0
        List<ReceiveBuffer.DeliveredPacket> dup = buf.deliver(0, new byte[]{10});
        assertTrue(dup.isEmpty());
        assertEquals(1, buf.expectedSeq());

        // Also duplicate out-of-order packet
        buf.deliver(5, new byte[]{50});
        List<ReceiveBuffer.DeliveredPacket> dup2 = buf.deliver(5, new byte[]{50});
        assertTrue(dup2.isEmpty());
        assertEquals(1, buf.bufferedCount());
    }

    @Test
    void generateSackNoGaps() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        buf.deliver(0, new byte[]{});
        buf.deliver(1, new byte[]{});
        buf.deliver(2, new byte[]{});

        SackInfo sack = buf.generateSack();
        assertEquals(2, sack.cumulativeAck()); // last contiguously received
        assertTrue(sack.ranges().isEmpty());
        assertTrue(sack.receiverWindow() > 0);
    }

    @Test
    void generateSackWithGaps() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        buf.deliver(0, new byte[]{});   // received
        // seq 1, 2 missing
        buf.deliver(3, new byte[]{});   // buffered
        buf.deliver(4, new byte[]{});   // buffered
        // seq 5 missing
        buf.deliver(6, new byte[]{});   // buffered

        SackInfo sack = buf.generateSack();
        assertEquals(0, sack.cumulativeAck()); // only seq 0 contiguous
        assertEquals(2, sack.ranges().size());

        // Range [3,4] and [6,6]
        assertEquals(3, sack.ranges().get(0).startSeq());
        assertEquals(4, sack.ranges().get(0).endSeq());
        assertEquals(6, sack.ranges().get(1).startSeq());
        assertEquals(6, sack.ranges().get(1).endSeq());
    }

    @Test
    void shouldSendAckOnGap() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        assertFalse(buf.shouldSendAck(0));

        // Receive out-of-order packet -> gap detected
        buf.deliver(2, new byte[]{});
        assertTrue(buf.shouldSendAck(0));

        buf.ackSent(100);
        assertFalse(buf.shouldSendAck(100));
    }

    @Test
    void shouldSendAckAfterTwoPackets() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        buf.deliver(0, new byte[]{});
        assertFalse(buf.shouldSendAck(0)); // only 1 packet

        buf.deliver(1, new byte[]{});
        assertTrue(buf.shouldSendAck(0)); // 2 packets -> delayed ACK threshold

        buf.ackSent(100);
        assertFalse(buf.shouldSendAck(100));
    }

    @Test
    void shouldSendAckOnTimer() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        buf.deliver(0, new byte[]{});
        buf.ackSent(100);

        buf.deliver(1, new byte[]{});
        assertFalse(buf.shouldSendAck(105)); // only 5ms elapsed

        assertTrue(buf.shouldSendAck(115)); // 15ms > 10ms timer
    }

    @Test
    void advertisedWindowDecreases() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        assertEquals(256, buf.advertisedWindow());

        buf.deliver(5, new byte[]{}); // buffered (gap at 0-4)
        assertEquals(255, buf.advertisedWindow());

        buf.deliver(10, new byte[]{}); // buffered
        assertEquals(254, buf.advertisedWindow());
    }

    @Test
    void windowGrowsAfterConsecutiveInOrderDeliveries() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        assertEquals(256, buf.maxWindow());

        // Deliver 128 consecutive in-order packets -> should trigger one growth
        for (int i = 0; i < 128; i++) {
            buf.deliver(i, new byte[]{});
        }
        assertEquals(288, buf.maxWindow()); // 256 + 32

        // Deliver 128 more -> another growth
        for (int i = 128; i < 256; i++) {
            buf.deliver(i, new byte[]{});
        }
        assertEquals(320, buf.maxWindow()); // 288 + 32
    }

    @Test
    void windowDoesNotGrowPastMax() {
        ReceiveBuffer buf = new ReceiveBuffer(0);

        // Deliver enough to hit the 512 ceiling
        // From 256, need (512-256)/32 = 8 growth cycles = 8*128 = 1024 packets
        for (int i = 0; i < 1200; i++) {
            buf.deliver(i, new byte[]{});
        }
        assertEquals(512, buf.maxWindow());
    }

    @Test
    void windowShrinksOnBufferPressure() {
        ReceiveBuffer buf = new ReceiveBuffer(0);
        assertEquals(256, buf.maxWindow());

        // Deliver seq 0, then skip a big gap to create many out-of-order packets
        buf.deliver(0, new byte[]{});

        // Buffer 129 out-of-order packets (> 50% of 256)
        for (int i = 2; i <= 130; i++) {
            buf.deliver(i, new byte[]{});
        }
        // Should have shrunk: 129 > 256*0.5=128
        assertTrue(buf.maxWindow() < 256, "Window should have shrunk, got: " + buf.maxWindow());
        assertTrue(buf.maxWindow() >= 32, "Window should not go below minimum");
    }

    @Test
    void windowRecoversAfterShrink() {
        ReceiveBuffer buf = new ReceiveBuffer(0);

        // Force a shrink by creating buffer pressure
        buf.deliver(0, new byte[]{});
        for (int i = 2; i <= 130; i++) {
            buf.deliver(i, new byte[]{});
        }
        int shrunkWindow = buf.maxWindow();
        assertTrue(shrunkWindow < 256);

        // Deliver the missing packet to clear the buffer
        buf.deliver(1, new byte[]{});
        assertEquals(0, buf.bufferedCount());

        // Now deliver many more in-order to grow it back
        for (int i = 131; i < 131 + 256; i++) {
            buf.deliver(i, new byte[]{});
        }
        assertTrue(buf.maxWindow() > shrunkWindow,
                "Window should have grown back from " + shrunkWindow + ", got: " + buf.maxWindow());
    }
}

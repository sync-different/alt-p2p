package com.alterante.p2p.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CongestionControlTest {

    @Test
    void initialState() {
        CongestionControl cc = new CongestionControl();
        assertEquals(32, cc.windowSize());
        assertEquals(2048, cc.ssthresh());
    }

    @Test
    void slowStartGrowth() {
        CongestionControl cc = new CongestionControl();
        // cwnd starts at 32, ssthresh=256 => slow start
        assertEquals(32, cc.windowSize());
        cc.onAck(); // cwnd = 33
        assertEquals(33, cc.windowSize());
        cc.onAck(); // cwnd = 34
        assertEquals(34, cc.windowSize());
    }

    @Test
    void slowStartDoublesPerRtt() {
        CongestionControl cc = new CongestionControl();
        // Simulate one RTT: 32 packets acknowledged (cwnd=32)
        // Each ACK adds 1 in slow start, so after 32 ACKs: cwnd=64
        for (int i = 0; i < 32; i++) {
            cc.onAck();
        }
        assertEquals(64, cc.windowSize());
    }

    @Test
    void transitionToCongestionAvoidance() {
        CongestionControl cc = new CongestionControl();
        // Simulate loss to set a reachable ssthresh
        // Drive cwnd to 100 via slow start, then trigger loss
        for (int i = 0; i < 68; i++) {
            cc.onAck();
        }
        assertEquals(100, cc.windowSize());
        cc.onLoss(); // ssthresh = 50, cwnd = 50

        assertEquals(50, cc.windowSize());
        assertEquals(50, cc.ssthresh());

        // Now in congestion avoidance (cwnd=50 >= ssthresh=50)
        // After 50 ACKs (one full RTT), cwnd should increase by ~1
        double cwndBefore = cc.cwnd();
        for (int i = 0; i < 50; i++) {
            cc.onAck();
        }
        double cwndAfter = cc.cwnd();
        assertEquals(1.0, cwndAfter - cwndBefore, 0.1);
    }

    @Test
    void lossHalvesCwnd() {
        CongestionControl cc = new CongestionControl();
        // Drive cwnd to 80 via slow start
        for (int i = 0; i < 48; i++) {
            cc.onAck();
        }
        assertEquals(80, cc.windowSize());

        cc.onLoss();
        // ssthresh = max(80/2, 2) = 40, cwnd = 40
        assertEquals(40, cc.windowSize());
        assertEquals(40, cc.ssthresh());
    }

    @Test
    void cwndFloorIsTwoOnLoss() {
        CongestionControl cc = new CongestionControl();
        // Multiple losses to drive cwnd down
        cc.onLoss(); // ssthresh = max(32/2, 2) = 16, cwnd = 16
        assertEquals(16, cc.windowSize());
        cc.onLoss(); // ssthresh = max(16/2, 2) = 8, cwnd = 8
        assertEquals(8, cc.windowSize());
        cc.onLoss(); // ssthresh = max(8/2, 2) = 4, cwnd = 4
        assertEquals(4, cc.windowSize());
        cc.onLoss(); // ssthresh = max(4/2, 2) = 2, cwnd = 2
        assertEquals(2, cc.windowSize());
        cc.onLoss(); // ssthresh = max(2/2, 2) = 2, cwnd = 2 (floor)
        assertEquals(2, cc.windowSize());
    }

    @Test
    void fastRetransmitOnThreeDuplicateAcks() {
        CongestionControl cc = new CongestionControl();
        // Drive cwnd up first
        for (int i = 0; i < 20; i++) {
            cc.onAck();
        }
        int cwndBefore = cc.windowSize();

        assertFalse(cc.onDuplicateAck());
        assertFalse(cc.onDuplicateAck());
        assertTrue(cc.onDuplicateAck()); // 3rd duplicate -> fast retransmit

        assertTrue(cc.windowSize() < cwndBefore);
    }

    @Test
    void onAckResetsDuplicateCount() {
        CongestionControl cc = new CongestionControl();
        cc.onDuplicateAck();
        cc.onDuplicateAck();
        // Regular ACK resets the counter
        cc.onAck();
        // Now 3 more duplicate ACKs needed for fast retransmit
        assertFalse(cc.onDuplicateAck());
        assertFalse(cc.onDuplicateAck());
        int cwndBefore = cc.windowSize();
        assertTrue(cc.onDuplicateAck());
        assertTrue(cc.windowSize() <= cwndBefore);
    }

    @Test
    void effectiveWindowRespectsReceiverWindow() {
        CongestionControl cc = new CongestionControl();
        assertEquals(32, cc.effectiveWindow(256)); // min(32, 256) = 32
        assertEquals(5, cc.effectiveWindow(5));     // min(32, 5) = 5
    }
}

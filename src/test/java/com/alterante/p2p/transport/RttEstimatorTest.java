package com.alterante.p2p.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RttEstimatorTest {

    @Test
    void initialRtoIs1000ms() {
        RttEstimator est = new RttEstimator();
        assertEquals(1000, est.rto());
        assertFalse(est.hasSamples());
    }

    @Test
    void firstSampleInitializesSrtt() {
        RttEstimator est = new RttEstimator();
        est.addSample(100);
        assertTrue(est.hasSamples());
        assertEquals(100.0, est.srtt(), 0.01);
        // rto = srtt + 4 * rttvar = 100 + 4 * 50 = 300
        assertEquals(300, est.rto());
    }

    @Test
    void subsequentSamplesUseEwma() {
        RttEstimator est = new RttEstimator();
        est.addSample(100);

        // Second sample: 200ms
        // rttvar = 0.75 * 50 + 0.25 * |200 - 100| = 37.5 + 25 = 62.5
        // srtt = 0.875 * 100 + 0.125 * 200 = 87.5 + 25 = 112.5
        // rto = 112.5 + 4 * 62.5 = 362.5 -> 363
        est.addSample(200);
        assertEquals(112.5, est.srtt(), 0.01);
        assertEquals(363, est.rto());
    }

    @Test
    void convergesToStableSamples() {
        RttEstimator est = new RttEstimator();
        // Feed 20 samples of 50ms — should converge to srtt≈50, rttvar≈0, rto≈200 (min)
        for (int i = 0; i < 20; i++) {
            est.addSample(50);
        }
        assertEquals(50.0, est.srtt(), 1.0);
        // rto should be near min (200ms) since variance is near 0
        assertEquals(200, est.rto());
    }

    @Test
    void backoffDoublesRto() {
        RttEstimator est = new RttEstimator();
        assertEquals(1000, est.rto());
        est.backoff();
        assertEquals(2000, est.rto());
        est.backoff();
        assertEquals(4000, est.rto());
        est.backoff();
        assertEquals(8000, est.rto());
    }

    @Test
    void rtoClampedToMax10000() {
        RttEstimator est = new RttEstimator();
        // Backoff repeatedly — should cap at 10000
        for (int i = 0; i < 10; i++) {
            est.backoff();
        }
        assertEquals(10_000, est.rto());
    }

    @Test
    void rtoClampedToMin200() {
        RttEstimator est = new RttEstimator();
        // Very small RTT: 1ms
        // First sample: srtt=1, rttvar=0.5, rto = 1 + 4*0.5 = 3 -> clamped to 200
        est.addSample(1);
        assertEquals(200, est.rto());
    }

    @Test
    void sampleAfterBackoffResetsRto() {
        RttEstimator est = new RttEstimator();
        est.addSample(100); // rto = 300
        est.backoff();      // rto = 600
        est.backoff();      // rto = 1200
        // New sample brings it back down
        est.addSample(100);
        assertTrue(est.rto() < 1200, "RTO should decrease after new sample, got: " + est.rto());
    }
}

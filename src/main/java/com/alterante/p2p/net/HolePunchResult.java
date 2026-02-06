package com.alterante.p2p.net;

import java.net.InetSocketAddress;

/**
 * Result of a hole-punch attempt.
 */
public record HolePunchResult(boolean success, InetSocketAddress confirmedAddress, long elapsedMs) {

    public static HolePunchResult failed(long elapsedMs) {
        return new HolePunchResult(false, null, elapsedMs);
    }

    public static HolePunchResult succeeded(InetSocketAddress confirmedAddress, long elapsedMs) {
        return new HolePunchResult(true, confirmedAddress, elapsedMs);
    }
}

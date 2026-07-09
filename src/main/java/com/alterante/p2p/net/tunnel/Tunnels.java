package com.alterante.p2p.net.tunnel;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.transport.ReliableChannel;

import java.io.IOException;

/**
 * Factory bridging an established {@link PeerConnection} to a {@link BytePipe} —
 * the carrier the tunnel runs over. Generic: it knows nothing about what bytes
 * flow through it.
 */
public final class Tunnels {

    private Tunnels() {}

    /** Cosmetic connection id stamped on outbound DATA packets (dispatch is by type). */
    private static final int TUNNEL_CONN_ID = 0x7C;

    /**
     * Build a carrier over a connected {@link PeerConnection}:
     * <ul>
     *   <li>if the connection fell back to TCP relay → {@link RelayBytePipe} over the
     *       relay's TLS streams;</li>
     *   <li>otherwise (the direct UDP path) → a {@link DirectBytePipe} over a fresh
     *       {@link ReliableChannel}. This method creates the channel, registers the
     *       DATA receiver, then starts the router — the required order, since DATA
     *       (unlike control packets) is not buffered before {@code onDataReceived} is set.</li>
     * </ul>
     * Call once per connection, after {@code conn.connect()} has returned.
     */
    public static BytePipe carrier(PeerConnection conn) throws IOException {
        if (conn.isTcpRelay()) {
            return new RelayBytePipe(conn.tcpRelayInputStream(), conn.tcpRelayOutputStream());
        }
        int sendLimit = conn.dtls().transport().getSendLimit();
        ReliableChannel channel = new ReliableChannel(conn.router(), TUNNEL_CONN_ID, sendLimit, conn.initialCwnd());
        DirectBytePipe pipe = new DirectBytePipe(channel); // registers onDataReceived
        conn.startRouter();                                 // must be AFTER the receiver is set
        return pipe;
    }
}

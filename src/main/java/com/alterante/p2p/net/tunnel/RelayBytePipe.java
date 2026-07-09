package com.alterante.p2p.net.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * {@link BytePipe} over the TCP-relay path's TLS streams — the fallback carrier
 * used when UDP hole punching fails. The relay stream is already an ordered,
 * reliable, E2E-encrypted byte stream (real TCP + TLS-PSK), so this is a thin
 * wrapper over {@code PeerConnection.tcpRelayInputStream()/OutputStream()}.
 */
public class RelayBytePipe implements BytePipe {

    private final InputStream in;
    private final OutputStream out;

    public RelayBytePipe(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    @Override public InputStream in() { return in; }
    @Override public OutputStream out() { return out; }

    @Override
    public void close() {
        try { in.close(); } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
    }
}

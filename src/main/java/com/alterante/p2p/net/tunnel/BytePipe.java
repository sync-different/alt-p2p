package com.alterante.p2p.net.tunnel;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An ordered, reliable, full-duplex byte stream between two peers — the transport
 * primitive the tunnel (StreamMux + TcpForwarder) is built on. Two implementations:
 * {@link DirectBytePipe} over the direct UDP path's {@code ReliableChannel} (primary),
 * and {@link RelayBytePipe} over the TCP-relay TLS streams (fallback).
 *
 * <p>{@link #out()} writes are delivered in order and may block for backpressure;
 * {@link #in()} reads return bytes in the same order the peer wrote them, and signal
 * EOF ({@code -1}) once the pipe is closed and drained.
 */
public interface BytePipe extends Closeable {

    /** Inbound byte stream: bytes the remote peer wrote, in order. {@code -1} at EOF. */
    InputStream in();

    /** Outbound byte stream: bytes written here are delivered to the remote peer in order. */
    OutputStream out();

    @Override
    void close();
}

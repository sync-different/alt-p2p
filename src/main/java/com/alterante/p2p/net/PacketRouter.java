package com.alterante.p2p.net;

import com.alterante.p2p.protocol.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Single-threaded I/O loop: all DTLS send/receive happens on one thread.
 *
 * External callers enqueue outbound data via {@link #send} or {@link #sendPacket}.
 * The receive loop drains the send queue, then does a 50ms blocking receive,
 * dispatches incoming packets, and runs periodic tick callbacks.
 */
public class PacketRouter {

    private static final Logger log = LoggerFactory.getLogger(PacketRouter.class);

    private static final int RECEIVE_TIMEOUT_MS = 10;
    private static final int KEEPALIVE_INTERVAL_MS = 15_000;
    private static final int KEEPALIVE_DEAD_MS = 45_000;

    private final DtlsHandler dtls;
    private final Map<PacketType, Consumer<Packet>> handlers = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<byte[]> sendQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private Thread receiveThread;

    private volatile long lastSendTimeMs;
    private volatile long lastRecvTimeMs;
    private Runnable tickCallback;

    public PacketRouter(DtlsHandler dtls) {
        this.dtls = dtls;
    }

    /** Register a handler for a specific packet type. */
    public void addHandler(PacketType type, Consumer<Packet> handler) {
        handlers.put(type, handler);
    }

    /** Remove a handler. */
    public void removeHandler(PacketType type) {
        handlers.remove(type);
    }

    /** Set a callback invoked every tick (50ms) for periodic tasks. */
    public void setTickCallback(Runnable callback) {
        this.tickCallback = callback;
    }

    /**
     * Enqueue data for sending through the DTLS channel.
     * Thread-safe; actual send happens on the router thread.
     */
    public void send(byte[] data, int offset, int length) throws IOException {
        if (!running) throw new IOException("Router not running");
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        sendQueue.add(copy);
    }

    /** Enqueue an encoded Packet for sending (convenience). */
    public void sendPacket(Packet packet) throws IOException {
        byte[] data = PacketCodec.encode(packet);
        send(data, 0, data.length);
    }

    /** Start the receive loop. */
    public void start() {
        running = true;
        long now = System.currentTimeMillis();
        lastSendTimeMs = now;
        lastRecvTimeMs = now;

        receiveThread = new Thread(this::receiveLoop, "packet-router");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    /** Stop the receive loop. */
    public void stop() {
        running = false;
        if (receiveThread != null) {
            receiveThread.interrupt();
            try {
                receiveThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Block until the receive loop exits (connection dies or stop called). */
    public void awaitStop() throws InterruptedException {
        if (receiveThread != null) {
            receiveThread.join();
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void receiveLoop() {
        byte[] recvBuf = new byte[Packet.MAX_DATAGRAM];

        while (running) {
            try {
                // 1. Drain send queue
                drainSendQueue();

                // 2. Receive with 50ms timeout
                int n = dtls.receive(recvBuf, 0, recvBuf.length, RECEIVE_TIMEOUT_MS);
                long now = System.currentTimeMillis();

                if (n > 0) {
                    lastRecvTimeMs = now;
                    try {
                        Packet pkt = PacketCodec.decode(recvBuf, n);
                        dispatch(pkt);
                    } catch (PacketException e) {
                        log.debug("Ignoring malformed packet: {}", e.getMessage());
                    }
                }

                // 3. Drain again (handlers may have enqueued responses)
                drainSendQueue();

                // 4. Periodic tick for retransmissions, SACK timer, etc.
                if (tickCallback != null) {
                    tickCallback.run();
                }

                // 5. Drain again (tick may have enqueued retransmissions/SACKs)
                drainSendQueue();

                // 6. Keepalive: send if no data sent recently
                now = System.currentTimeMillis();
                if (now - lastSendTimeMs >= KEEPALIVE_INTERVAL_MS) {
                    doSend(PacketCodec.encode(new Packet(PacketType.KEEPALIVE)));
                    log.debug("Sent keepalive");
                }

                // 7. Connection dead check
                if (now - lastRecvTimeMs >= KEEPALIVE_DEAD_MS) {
                    log.warn("Peer unresponsive for {}ms, declaring connection dead",
                            now - lastRecvTimeMs);
                    running = false;
                    break;
                }

            } catch (InterruptedIOException e) {
                // SocketTimeoutException extends InterruptedIOException — this is normal.
                // DtlsHandler.receive() should catch it, but handle defensively.
                log.debug("Receive timeout, continuing");
            } catch (IOException e) {
                if (running) {
                    log.warn("Receive loop I/O error: {}", e.getMessage(), e);
                    running = false;
                }
                break;
            } catch (RuntimeException e) {
                log.warn("Unexpected error in receive loop: {}", e.getMessage(), e);
            }
        }

        log.debug("PacketRouter receive loop exited");
    }

    /** Drain the send queue (called on the router thread). */
    private void drainSendQueue() throws IOException {
        byte[] data;
        while ((data = sendQueue.poll()) != null) {
            doSend(data);
        }
    }

    /** Actually send bytes through DTLS (router thread only). */
    private void doSend(byte[] data) throws IOException {
        dtls.send(data, 0, data.length);
        lastSendTimeMs = System.currentTimeMillis();
    }

    private void dispatch(Packet pkt) {
        // Built-in keepalive handling
        if (pkt.type() == PacketType.KEEPALIVE) {
            try {
                doSend(PacketCodec.encode(new Packet(PacketType.KEEPALIVE_ACK)));
            } catch (IOException e) {
                log.debug("Failed to send keepalive ACK: {}", e.getMessage());
            }
            return;
        }
        if (pkt.type() == PacketType.KEEPALIVE_ACK) {
            log.debug("Received keepalive ACK");
            return;
        }

        // Dispatch to registered handler
        Consumer<Packet> handler = handlers.get(pkt.type());
        if (handler != null) {
            handler.accept(pkt);
        } else {
            log.debug("No handler for packet type: {}", pkt.type());
        }
    }
}

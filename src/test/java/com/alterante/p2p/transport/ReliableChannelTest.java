package com.alterante.p2p.transport;

import com.alterante.p2p.net.*;
import org.junit.jupiter.api.Test;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ReliableChannel over a real DTLS connection.
 */
class ReliableChannelTest {

    private static final String PSK = "test-psk";
    private static final String SESSION = "reliable-test";

    @Test
    void sendAndReceiveDataPackets() throws Exception {
        // Set up two DTLS peers
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            // DTLS handshake
            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                Future<?> fA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> fB = exec.submit(() -> { dtlsB.handshake(); return null; });
                fA.get(10, TimeUnit.SECONDS);
                fB.get(10, TimeUnit.SECONDS);

                // Create routers and channels
                PacketRouter routerA = new PacketRouter(dtlsA);
                PacketRouter routerB = new PacketRouter(dtlsB);

                ReliableChannel channelA = new ReliableChannel(routerA, 0xCAFE);
                ReliableChannel channelB = new ReliableChannel(routerB, 0xBEEF);

                // Collect received data on B
                List<ReliableChannel.DataPayload> receivedOnB =
                        Collections.synchronizedList(new ArrayList<>());
                channelB.onDataReceived(receivedOnB::add);

                routerA.start();
                routerB.start();

                try {
                    // A sends 10 data packets to B
                    for (int i = 0; i < 10; i++) {
                        byte[] data = ("packet-" + i).getBytes(StandardCharsets.UTF_8);
                        channelA.sendData(i, i * 1168L, data);
                    }

                    // Wait for delivery + SACKs
                    long deadline = System.currentTimeMillis() + 5000;
                    while (receivedOnB.size() < 10 && System.currentTimeMillis() < deadline) {
                        Thread.sleep(50);
                    }

                    assertEquals(10, receivedOnB.size(), "Should receive all 10 packets");

                    // Verify order and content
                    for (int i = 0; i < 10; i++) {
                        ReliableChannel.DataPayload dp = receivedOnB.get(i);
                        assertEquals(i, dp.chunkIndex());
                        assertEquals(i * 1168L, dp.byteOffset());
                        assertEquals("packet-" + i, new String(dp.data(), StandardCharsets.UTF_8));
                    }

                } finally {
                    channelA.close();
                    channelB.close();
                    routerA.stop();
                    routerB.stop();
                }
            } finally {
                exec.shutdownNow();
                dtlsA.close();
                dtlsB.close();
            }
        }
    }

    @Test
    void largerBurstDelivery() throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            ExecutorService exec = Executors.newFixedThreadPool(2);
            try {
                Future<?> fA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> fB = exec.submit(() -> { dtlsB.handshake(); return null; });
                fA.get(10, TimeUnit.SECONDS);
                fB.get(10, TimeUnit.SECONDS);

                PacketRouter routerA = new PacketRouter(dtlsA);
                PacketRouter routerB = new PacketRouter(dtlsB);

                ReliableChannel channelA = new ReliableChannel(routerA, 0xCAFE);
                ReliableChannel channelB = new ReliableChannel(routerB, 0xBEEF);

                List<ReliableChannel.DataPayload> received =
                        Collections.synchronizedList(new ArrayList<>());
                channelB.onDataReceived(received::add);

                routerA.start();
                routerB.start();

                try {
                    int packetCount = 100;
                    // Send in a separate thread (may block on window)
                    Future<?> sender = exec.submit(() -> {
                        for (int i = 0; i < packetCount; i++) {
                            byte[] data = new byte[1000]; // ~1KB per packet
                            data[0] = (byte) (i & 0xFF);
                            data[1] = (byte) ((i >> 8) & 0xFF);
                            channelA.sendData(i, i * 1000L, data);
                        }
                        return null;
                    });

                    // Wait for all packets
                    long deadline = System.currentTimeMillis() + 15000;
                    while (received.size() < packetCount && System.currentTimeMillis() < deadline) {
                        Thread.sleep(100);
                    }

                    sender.get(5, TimeUnit.SECONDS); // ensure sender didn't error

                    assertEquals(packetCount, received.size(),
                            "Should receive all " + packetCount + " packets, got " + received.size());

                    // Verify all chunk indices received (in order since reliable)
                    for (int i = 0; i < packetCount; i++) {
                        assertEquals(i, received.get(i).chunkIndex());
                    }

                } finally {
                    channelA.close();
                    channelB.close();
                    routerA.stop();
                    routerB.stop();
                }
            } finally {
                exec.shutdownNow();
                dtlsA.close();
                dtlsB.close();
            }
        }
    }
}

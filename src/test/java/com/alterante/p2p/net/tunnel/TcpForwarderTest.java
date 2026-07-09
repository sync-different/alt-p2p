package com.alterante.p2p.net.tunnel;

import com.alterante.p2p.net.DtlsHandler;
import com.alterante.p2p.net.PacketRouter;
import com.alterante.p2p.transport.ReliableChannel;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S3: end-to-end tunnel — multiple concurrent local TCP clients reach a real TCP
 * echo server THROUGH the mux + carrier between two loopback PeerConnection-equivalent
 * channels. Proves: local socket → ForwardListener → mux stream → carrier → mux →
 * ForwardConnector → target socket → echo, and back. This is the plumbing lore will use.
 */
class TcpForwarderTest {

    private static final String PSK = "test-psk";
    private static final String SESSION = "forwarder-test";
    private static final int CLIENTS = 6;
    private static final int PER_CLIENT = 64 * 1024;

    private static byte[] pattern(int id, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((id * 137 + i * 29) & 0xFF);
        return b;
    }

    @Test
    void echoThroughTunnel() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        // Host-local echo server: echo each connection until EOF.
        ServerSocket echo = new ServerSocket();
        echo.bind(new InetSocketAddress("127.0.0.1", 0));
        Thread echoAccept = new Thread(() -> {
            try {
                while (true) {
                    Socket s = echo.accept();
                    exec.execute(() -> {
                        try (InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); out.flush(); }
                        } catch (Exception ignored) {}
                    });
                }
            } catch (Exception ignored) {}
        }, "echo-accept");
        echoAccept.setDaemon(true);
        echoAccept.start();

        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());
            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            try {
                Future<?> hA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> hB = exec.submit(() -> { dtlsB.handshake(); return null; });
                hA.get(10, TimeUnit.SECONDS);
                hB.get(10, TimeUnit.SECONDS);

                PacketRouter routerA = new PacketRouter(dtlsA);
                PacketRouter routerB = new PacketRouter(dtlsB);
                ReliableChannel channelA = new ReliableChannel(routerA, 0xCAFE);
                ReliableChannel channelB = new ReliableChannel(routerB, 0xBEEF);
                BytePipe pipeA = new DirectBytePipe(channelA);
                BytePipe pipeB = new DirectBytePipe(channelB);
                routerA.start();
                routerB.start();

                StreamMux muxA = new StreamMux(pipeA); // client
                StreamMux muxB = new StreamMux(pipeB); // host

                // Host: bridge each inbound stream to the echo server.
                ForwardConnector connector = new ForwardConnector(muxB, "127.0.0.1", echo.getLocalPort());
                muxB.start();

                // Client: local listener that opens a stream per accepted connection.
                ForwardListener listener = new ForwardListener(muxA, "127.0.0.1", 0);
                muxA.start();
                listener.start();
                int localPort = listener.localPort();

                AtomicReference<String> err = new AtomicReference<>(null);
                try {
                    List<Future<?>> tasks = new ArrayList<>();
                    for (int k = 0; k < CLIENTS; k++) {
                        final int id = k;
                        tasks.add(exec.submit(() -> {
                            byte[] payload = pattern(id, PER_CLIENT);
                            try (Socket sock = new Socket()) {
                                sock.setTcpNoDelay(true);
                                sock.connect(new InetSocketAddress("127.0.0.1", localPort), 5000);
                                Future<?> w = exec.submit(() -> {
                                    sock.getOutputStream().write(payload);
                                    sock.getOutputStream().flush();
                                    return null;
                                });
                                byte[] got = new byte[PER_CLIENT];
                                int off = 0;
                                InputStream in = sock.getInputStream();
                                while (off < got.length) {
                                    int n = in.read(got, off, got.length - off);
                                    if (n < 0) { err.compareAndSet(null, "client " + id + ": EOF at " + off); break; }
                                    off += n;
                                }
                                w.get(20, TimeUnit.SECONDS);
                                for (int i = 0; i < PER_CLIENT; i++) {
                                    if (got[i] != payload[i]) { err.compareAndSet(null, "client " + id + ": mismatch at " + i); break; }
                                }
                            }
                            return null;
                        }));
                    }
                    for (Future<?> t : tasks) t.get(30, TimeUnit.SECONDS);
                    assertNull(err.get(), err.get());
                } finally {
                    listener.close();
                    connector.close();
                    muxA.close();
                    muxB.close();
                    channelA.close();
                    channelB.close();
                    routerA.stop();
                    routerB.stop();
                }
            } finally {
                dtlsA.close();
                dtlsB.close();
            }
        } finally {
            echo.close();
            exec.shutdownNow();
        }
    }
}

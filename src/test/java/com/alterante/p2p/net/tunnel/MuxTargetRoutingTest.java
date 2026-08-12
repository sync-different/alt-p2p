package com.alterante.p2p.net.tunnel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Named targets: one session forwarding several host services.
 *
 * <p>Until now a mux carried streams to exactly one target, because {@code OPEN} had no room to say
 * where a stream should go. That is enough for a lore client reaching {@code loreserver} alone, but
 * not once the same client must also reach an identity provider — and a second
 * {@code PeerConnection} per service would cost another socket, coordination session and hole punch
 * on a host that is meant to sit idle for hours.
 *
 * <p>The label rides in the OPEN frame's payload, which was previously always empty. These tests pin
 * both halves of that: labels route, and an <em>unlabelled</em> stream still means "the default
 * target" so a peer built before this change is unaffected.
 */
class MuxTargetRoutingTest {

    /** An in-memory {@link BytePipe} pair — the carrier is not what is under test here. */
    private static BytePipe[] pipePair() throws IOException {
        PipedInputStream aIn = new PipedInputStream(1 << 16);
        PipedInputStream bIn = new PipedInputStream(1 << 16);
        PipedOutputStream aOut = new PipedOutputStream(bIn);
        PipedOutputStream bOut = new PipedOutputStream(aIn);
        return new BytePipe[]{pipe(aIn, aOut), pipe(bIn, bOut)};
    }

    private static BytePipe pipe(InputStream in, OutputStream out) {
        return new BytePipe() {
            @Override public InputStream in() { return in; }
            @Override public OutputStream out() { return out; }
            @Override public void close() {
                try { in.close(); } catch (IOException ignored) { }
                try { out.close(); } catch (IOException ignored) { }
            }
        };
    }

    /** A server that answers every connection with its own name, so replies identify the target. */
    private static ServerSocket namedServer(String name, ExecutorService exec) throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress("127.0.0.1", 0));
        exec.execute(() -> {
            try {
                while (true) {
                    Socket s = server.accept();
                    exec.execute(() -> {
                        try (OutputStream out = s.getOutputStream()) {
                            out.write(name.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } catch (IOException ignored) {
                        }
                    });
                }
            } catch (IOException ignored) {
            }
        });
        return server;
    }

    private static String readReply(int port) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void streamsReachTheTargetTheyName() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        try (ServerSocket lore = namedServer("loreserver", exec);
             ServerSocket identity = namedServer("identity", exec)) {

            BytePipe[] pipes = pipePair();
            StreamMux hostMux = new StreamMux(pipes[0]);
            StreamMux clientMux = new StreamMux(pipes[1]);

            ForwardConnector connector = new ForwardConnector(hostMux,
                    new InetSocketAddress("127.0.0.1", lore.getLocalPort()),
                    Map.of("identity", new InetSocketAddress("127.0.0.1", identity.getLocalPort())));
            hostMux.start();
            clientMux.start();

            try (ForwardListener toLore = new ForwardListener(clientMux, "127.0.0.1", 0);
                 ForwardListener toIdentity =
                         new ForwardListener(clientMux, "127.0.0.1", 0, "identity")) {
                toLore.start();
                toIdentity.start();

                assertEquals("loreserver", readReply(toLore.localPort()),
                        "an unlabelled listener must reach the default target");
                assertEquals("identity", readReply(toIdentity.localPort()),
                        "a labelled listener must reach its named target");
            }
            connector.close();
            hostMux.close();
            clientMux.close();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void bothTargetsAreUsableConcurrentlyOverOneSession() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        try (ServerSocket lore = namedServer("loreserver", exec);
             ServerSocket identity = namedServer("identity", exec)) {

            BytePipe[] pipes = pipePair();
            StreamMux hostMux = new StreamMux(pipes[0]);
            StreamMux clientMux = new StreamMux(pipes[1]);
            new ForwardConnector(hostMux,
                    new InetSocketAddress("127.0.0.1", lore.getLocalPort()),
                    Map.of("identity", new InetSocketAddress("127.0.0.1", identity.getLocalPort())));
            hostMux.start();
            clientMux.start();

            try (ForwardListener toLore = new ForwardListener(clientMux, "127.0.0.1", 0);
                 ForwardListener toIdentity =
                         new ForwardListener(clientMux, "127.0.0.1", 0, "identity")) {
                toLore.start();
                toIdentity.start();

                // A lore operation interleaves calls to both services on the same session; they must
                // not be able to land on each other's target.
                for (int i = 0; i < 8; i++) {
                    assertEquals("loreserver", readReply(toLore.localPort()));
                    assertEquals("identity", readReply(toIdentity.localPort()));
                }
            }
            hostMux.close();
            clientMux.close();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void anUnknownLabelIsRefusedRatherThanSentToTheDefault() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        try (ServerSocket lore = namedServer("loreserver", exec)) {
            BytePipe[] pipes = pipePair();
            StreamMux hostMux = new StreamMux(pipes[0]);
            StreamMux clientMux = new StreamMux(pipes[1]);
            new ForwardConnector(hostMux,
                    new InetSocketAddress("127.0.0.1", lore.getLocalPort()), Map.of());
            hostMux.start();
            clientMux.start();

            try (ForwardListener mistyped =
                         new ForwardListener(clientMux, "127.0.0.1", 0, "idenity")) {
                mistyped.start();
                // Falling back to the default would deliver identity traffic to loreserver, which is
                // far harder to diagnose than a closed connection.
                assertEquals("", readReply(mistyped.localPort()));
            }
            hostMux.close();
            clientMux.close();
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * The wire format for an unlabelled OPEN must not have changed by a single byte.
     *
     * <p>Two shipped consumers depend on it — alt-p2p-lore and alt-p2p-bbs, which forwards a telnet
     * BBS — and they are on different alt-p2p versions, so a 0.6.0 peer and a 0.6.1 peer can meet.
     * Neither sends a label, so their frames must be identical: {@code type(1) | id(4) | len(4)=0}
     * and no payload.
     */
    @Test
    void anUnlabelledOpenIsByteIdenticalToTheOriginalFrame() throws Exception {
        java.io.ByteArrayOutputStream written = new java.io.ByteArrayOutputStream();
        BytePipe capture = pipe(InputStream.nullInputStream(), written);
        StreamMux mux = new StreamMux(capture);

        mux.open();

        byte[] frame = written.toByteArray();
        assertEquals(9, frame.length, "an unlabelled OPEN carries no payload");
        assertEquals(1, frame[0], "frame type OPEN");
        assertEquals(1, frame[4], "stream id 1, big endian");
        assertEquals(0, frame[8], "payload length 0 — exactly what pre-label peers send and expect");
        mux.close();
    }

    @Test
    void aStreamOpenedWithoutALabelStillMeansTheDefaultTarget() throws Exception {
        ExecutorService exec = Executors.newCachedThreadPool();
        try (ServerSocket lore = namedServer("loreserver", exec)) {
            BytePipe[] pipes = pipePair();
            StreamMux hostMux = new StreamMux(pipes[0]);
            StreamMux clientMux = new StreamMux(pipes[1]);
            // Single-target constructor, as every existing caller uses it.
            new ForwardConnector(hostMux, "127.0.0.1", lore.getLocalPort());
            hostMux.start();
            clientMux.start();

            // open() with no argument is what a peer predating named targets sends.
            StreamMux.MuxStream stream = clientMux.open();
            assertEquals("", stream.target());
            byte[] reply = new byte[10];
            int n = stream.in().read(reply);

            assertTrue(n > 0, "an unlabelled stream must still reach the default target");
            assertEquals("loreserver", new String(reply, 0, n, StandardCharsets.UTF_8));

            hostMux.close();
            clientMux.close();
        } finally {
            exec.shutdownNow();
            assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

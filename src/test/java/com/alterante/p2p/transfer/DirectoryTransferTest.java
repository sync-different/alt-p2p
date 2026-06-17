package com.alterante.p2p.transfer;

import com.alterante.p2p.net.DtlsHandler;
import com.alterante.p2p.net.PacketRouter;
import com.alterante.p2p.transport.ReliableChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: full multi-file (folder) transfer through DTLS + ReliableChannel.
 */
class DirectoryTransferTest {

    private static final String PSK = "dir-transfer-psk";
    private static final String SESSION = "dir-session";

    @Test
    void transfersTreeWithNestedAndEmptyDirs(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");

        Result r = runDirTransfer(src, out);

        assertEquals(0, r.filesSkipped);
        assertEquals(5, r.filesSent); // root.txt, zero.bin, a/one.txt, a/b/big.bin, c/clip.bin

        // Files identical by content.
        assertArrayEquals(Files.readAllBytes(src.resolve("a/b/big.bin")),
                Files.readAllBytes(out.resolve("a/b/big.bin")));
        assertEquals("root", Files.readString(out.resolve("root.txt")));
        assertEquals(0, Files.size(out.resolve("zero.bin")));

        // Empty subfolders recreated (Q1).
        assertTrue(Files.isDirectory(out.resolve("empty1")));
        assertTrue(Files.isDirectory(out.resolve("empty2/nested")));
    }

    @Test
    void skipsAlreadyPresentIdenticalFilesOnRerun(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");

        Result first = runDirTransfer(src, out);
        assertEquals(5, first.filesSent);

        // Second run into the same dir: every file already present and identical → all skipped.
        Result second = runDirTransfer(src, out);
        assertEquals(0, second.filesSent);
        assertEquals(5, second.filesSkipped);
    }

    @Test
    void skipsSymlinksWithoutRecreatingThem(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Files.createSymbolicLink(src.resolve("link.txt"), src.resolve("root.txt"));
        Path out = tempDir.resolve("out");

        runDirTransfer(src, out);

        assertFalse(Files.exists(out.resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "symlink must not be recreated on the receiver");
    }

    @Test
    void conflictOverwriteReplacesDifferingFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("root.txt"), "OLD CONTENT");

        runDirTransfer(src, out, new ConflictPolicy(ConflictPolicy.Mode.OVERWRITE, false));

        assertEquals("root", Files.readString(out.resolve("root.txt")));
    }

    @Test
    void conflictSkipKeepsDifferingFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("root.txt"), "OLD CONTENT");

        Result r = runDirTransfer(src, out, new ConflictPolicy(ConflictPolicy.Mode.SKIP, false));

        assertEquals("OLD CONTENT", Files.readString(out.resolve("root.txt")));
        assertEquals(1, r.filesSkipped);
        assertEquals(4, r.filesSent);
    }

    @Test
    void conflictKeepBothWritesRenamedFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("root.txt"), "OLD CONTENT");

        runDirTransfer(src, out, new ConflictPolicy(ConflictPolicy.Mode.KEEP_BOTH, false));

        assertEquals("OLD CONTENT", Files.readString(out.resolve("root.txt")));
        assertEquals("root", Files.readString(out.resolve("root (1).txt")));
    }

    @Test
    void keepBothNamePicksFirstFreeSlot(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("a.txt"), "x");
        Files.writeString(tempDir.resolve("a (1).txt"), "x");
        Path picked = DirectoryReceiver.keepBothName(tempDir.resolve("a.txt"));
        assertEquals(tempDir.resolve("a (2).txt"), picked);
    }

    /** Build: root.txt, zero.bin, a/one.txt, a/b/big.bin, c/clip.bin + empty1, empty2/nested. */
    private static void buildCorpus(Path src) throws Exception {
        Files.createDirectories(src.resolve("a/b"));
        Files.createDirectories(src.resolve("c"));
        Files.createDirectories(src.resolve("empty1"));
        Files.createDirectories(src.resolve("empty2/nested"));
        Files.writeString(src.resolve("root.txt"), "root");
        Files.write(src.resolve("zero.bin"), new byte[0]);
        Files.writeString(src.resolve("a/one.txt"), "file one");
        byte[] big = new byte[ReliableChannel.MAX_CHUNK_DATA * 7 + 13];
        new Random(7).nextBytes(big);
        Files.write(src.resolve("a/b/big.bin"), big);
        byte[] clip = new byte[5000];
        new Random(8).nextBytes(clip);
        Files.write(src.resolve("c/clip.bin"), clip);
    }

    private record Result(int filesSent, int filesSkipped, int filesReceived) {}

    private Result runDirTransfer(Path srcRoot, Path outputDir) throws Exception {
        return runDirTransfer(srcRoot, outputDir, ConflictPolicy.overwrite());
    }

    private Result runDirTransfer(Path srcRoot, Path outputDir, ConflictPolicy policy) throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            ExecutorService exec = Executors.newFixedThreadPool(4);
            try {
                Future<?> fA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> fB = exec.submit(() -> { dtlsB.handshake(); return null; });
                fA.get(10, TimeUnit.SECONDS);
                fB.get(10, TimeUnit.SECONDS);

                int dtlsSendLimit = dtlsA.transport().getSendLimit();
                PacketRouter routerA = new PacketRouter(dtlsA);
                PacketRouter routerB = new PacketRouter(dtlsB);
                ReliableChannel channelA = new ReliableChannel(routerA, 0xA, dtlsSendLimit);
                ReliableChannel channelB = new ReliableChannel(routerB, 0xB, dtlsSendLimit);
                routerA.start();
                routerB.start();

                try {
                    DirectorySender sender = new DirectorySender(srcRoot, channelA);
                    DirectoryReceiver receiver = new DirectoryReceiver(outputDir, channelB, policy);

                    Future<?> sendFut = exec.submit(() -> { sender.send(); return null; });
                    Future<?> recvFut = exec.submit(() -> { receiver.receive(); return null; });

                    sendFut.get(60, TimeUnit.SECONDS);
                    recvFut.get(60, TimeUnit.SECONDS);

                    return new Result(sender.filesSent(), sender.filesSkipped(), receiver.filesReceived());
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

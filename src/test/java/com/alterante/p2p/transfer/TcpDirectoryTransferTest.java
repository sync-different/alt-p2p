package com.alterante.p2p.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-process test of the TCP-relay folder transfer (TcpDirectorySender ↔
 * TcpDirectoryReceiver) over piped streams — no network or TLS.
 */
class TcpDirectoryTransferTest {

    @Test
    void transfersTreeWithEmptyDirs(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");

        Result r = runRelay(src, out, ConflictPolicy.overwrite());

        assertEquals(5, r.filesSent);
        assertEquals(0, r.filesSkipped);
        assertArrayEquals(Files.readAllBytes(src.resolve("a/b/big.bin")),
                Files.readAllBytes(out.resolve("a/b/big.bin")));
        assertTrue(Files.isDirectory(out.resolve("empty1")));
        assertTrue(Files.isDirectory(out.resolve("empty2/nested")));
    }

    @Test
    void skipsAlreadyPresentIdenticalFiles(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");

        assertEquals(5, runRelay(src, out, ConflictPolicy.overwrite()).filesSent);
        Result second = runRelay(src, out, ConflictPolicy.overwrite());
        assertEquals(0, second.filesSent);
        assertEquals(5, second.filesSkipped); // identical → D4 skip, no prompt
    }

    @Test
    void conflictKeepBothWritesRenamedFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("root.txt"), "OLD CONTENT");

        runRelay(src, out, new ConflictPolicy(ConflictPolicy.Mode.KEEP_BOTH, false));

        assertEquals("OLD CONTENT", Files.readString(out.resolve("root.txt")));
        assertEquals("root", Files.readString(out.resolve("root (1).txt")));
    }

    @Test
    void conflictSkipKeepsDifferingFile(@TempDir Path tempDir) throws Exception {
        Path src = tempDir.resolve("src");
        buildCorpus(src);
        Path out = tempDir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("root.txt"), "OLD CONTENT");

        Result r = runRelay(src, out, new ConflictPolicy(ConflictPolicy.Mode.SKIP, false));
        assertEquals("OLD CONTENT", Files.readString(out.resolve("root.txt")));
        assertEquals(1, r.filesSkipped);
    }

    private static void buildCorpus(Path src) throws Exception {
        Files.createDirectories(src.resolve("a/b"));
        Files.createDirectories(src.resolve("empty1"));
        Files.createDirectories(src.resolve("empty2/nested"));
        Files.writeString(src.resolve("root.txt"), "root");
        Files.write(src.resolve("zero.bin"), new byte[0]);
        Files.writeString(src.resolve("a/one.txt"), "file one");
        byte[] big = new byte[200_000];
        new Random(7).nextBytes(big);
        Files.write(src.resolve("a/b/big.bin"), big);
        byte[] clip = new byte[5000];
        new Random(8).nextBytes(clip);
        Files.write(src.resolve("clip.bin"), clip);
    }

    private record Result(int filesSent, int filesSkipped, int filesReceived) {}

    private Result runRelay(Path src, Path out, ConflictPolicy policy) throws Exception {
        // Two pipes: sender->receiver and receiver->sender.
        PipedOutputStream sToR_out = new PipedOutputStream();
        PipedInputStream sToR_in = new PipedInputStream(sToR_out, 1 << 16);
        PipedOutputStream rToS_out = new PipedOutputStream();
        PipedInputStream rToS_in = new PipedInputStream(rToS_out, 1 << 16);

        TcpDirectorySender sender = new TcpDirectorySender(rToS_in, sToR_out, DirectorySender.scan(src));
        TcpDirectoryReceiver receiver = new TcpDirectoryReceiver(out, sToR_in, rToS_out, policy);

        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            Future<?> recvFut = exec.submit(() -> { receiver.receive(); return null; });
            Future<?> sendFut = exec.submit(() -> { sender.send(); return null; });
            sendFut.get(30, TimeUnit.SECONDS);
            recvFut.get(30, TimeUnit.SECONDS);
            return new Result(sender.filesSent(), sender.filesSkipped(), receiver.filesReceived());
        } finally {
            exec.shutdownNow();
        }
    }
}

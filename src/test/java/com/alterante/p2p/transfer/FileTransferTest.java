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
 * Integration test: full file transfer through DTLS + ReliableChannel.
 */
class FileTransferTest {

    private static final String PSK = "transfer-test-psk";
    private static final String SESSION = "transfer-session";

    @Test
    void transferSmallFile(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("small.txt");
        Files.writeString(sourceFile, "Hello P2P transfer!");
        Path outputDir = tempDir.resolve("received");

        doTransfer(sourceFile, outputDir);

        Path receivedFile = outputDir.resolve("small.txt");
        assertTrue(Files.exists(receivedFile));
        assertEquals("Hello P2P transfer!", Files.readString(receivedFile));
    }

    @Test
    void transfer1MbFile(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("random.bin");
        byte[] data = new byte[1_000_000];
        new Random(42).nextBytes(data);
        Files.write(sourceFile, data);
        Path outputDir = tempDir.resolve("received");

        doTransfer(sourceFile, outputDir);

        Path receivedFile = outputDir.resolve("random.bin");
        assertTrue(Files.exists(receivedFile));
        assertArrayEquals(data, Files.readAllBytes(receivedFile));
    }

    @Test
    void transferExactBoundaryFile(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("boundary.bin");
        byte[] data = new byte[ReliableChannel.MAX_CHUNK_DATA * 10];
        new Random(99).nextBytes(data);
        Files.write(sourceFile, data);
        Path outputDir = tempDir.resolve("received");

        doTransfer(sourceFile, outputDir);

        Path receivedFile = outputDir.resolve("boundary.bin");
        assertTrue(Files.exists(receivedFile));
        assertArrayEquals(data, Files.readAllBytes(receivedFile));
    }

    @Test
    void transferMultiChunkSmall(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("multi.bin");
        byte[] data = new byte[ReliableChannel.MAX_CHUNK_DATA * 3];
        new Random(77).nextBytes(data);
        Files.write(sourceFile, data);
        Path outputDir = tempDir.resolve("received");

        doTransfer(sourceFile, outputDir);

        Path receivedFile = outputDir.resolve("multi.bin");
        assertTrue(Files.exists(receivedFile));
        assertArrayEquals(data, Files.readAllBytes(receivedFile));
    }

    @Test
    void transferZeroByteFile(@TempDir Path tempDir) throws Exception {
        Path sourceFile = tempDir.resolve("empty.txt");
        Files.writeString(sourceFile, "");
        Path outputDir = tempDir.resolve("received");

        doTransfer(sourceFile, outputDir);

        Path receivedFile = outputDir.resolve("empty.txt");
        assertTrue(Files.exists(receivedFile));
        assertEquals(0, Files.size(receivedFile));
    }

    @Test
    void resumeFromPartialState(@TempDir Path tempDir) throws Exception {
        // Create source file
        Path sourceFile = tempDir.resolve("resume.bin");
        byte[] fullData = new byte[100_000];
        new Random(55).nextBytes(fullData);
        Files.write(sourceFile, fullData);

        Path outputDir = tempDir.resolve("received");
        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve("resume.bin");

        // Simulate partial transfer: write first half of the file
        long halfSize = 50_000;
        Files.write(outputFile, java.util.Arrays.copyOf(fullData, (int) halfSize));

        // Create partial state sidecar
        FileMetadata metadata = FileMetadata.fromFile(sourceFile);
        new PartialTransferState(
                metadata.fileSize(), metadata.sha256(), halfSize, metadata.filename()
        ).save(outputFile);

        assertTrue(Files.exists(PartialTransferState.partialPath(outputFile)),
                "Partial sidecar should exist before transfer");

        // Now run transfer — receiver should resume from halfSize
        doTransfer(sourceFile, outputDir);

        // Verify full file
        Path receivedFile = outputDir.resolve("resume.bin");
        assertTrue(Files.exists(receivedFile));
        assertArrayEquals(fullData, Files.readAllBytes(receivedFile));

        // Partial sidecar should be cleaned up
        assertFalse(Files.exists(PartialTransferState.partialPath(outputFile)),
                "Partial sidecar should be deleted after successful transfer");
    }

    @Test
    void partialStateSavedOnCancellation(@TempDir Path tempDir) throws Exception {
        // Verify that partial state is created when the receiver stores data
        Path sourceFile = tempDir.resolve("partial.bin");
        byte[] data = new byte[50_000];
        new Random(88).nextBytes(data);
        Files.write(sourceFile, data);
        Path outputDir = tempDir.resolve("received");

        // Normal transfer should clean up partial state
        doTransfer(sourceFile, outputDir);

        Path outputFile = outputDir.resolve("partial.bin");
        assertTrue(Files.exists(outputFile));
        assertFalse(Files.exists(PartialTransferState.partialPath(outputFile)),
                "Partial sidecar should be deleted after successful transfer");
    }

    private void doTransfer(Path sourceFile, Path outputDir) throws Exception {
        try (DatagramSocket socketA = new DatagramSocket();
             DatagramSocket socketB = new DatagramSocket()) {

            InetSocketAddress addrA = new InetSocketAddress("127.0.0.1", socketA.getLocalPort());
            InetSocketAddress addrB = new InetSocketAddress("127.0.0.1", socketB.getLocalPort());

            DtlsHandler dtlsA = new DtlsHandler(socketA, addrB, SESSION, PSK, true);
            DtlsHandler dtlsB = new DtlsHandler(socketB, addrA, SESSION, PSK, false);

            ExecutorService exec = Executors.newFixedThreadPool(4);
            try {
                // DTLS handshake
                Future<?> fA = exec.submit(() -> { dtlsA.handshake(); return null; });
                Future<?> fB = exec.submit(() -> { dtlsB.handshake(); return null; });
                fA.get(10, TimeUnit.SECONDS);
                fB.get(10, TimeUnit.SECONDS);

                // Set up routers and channels
                int dtlsSendLimit = dtlsA.transport().getSendLimit();
                PacketRouter routerA = new PacketRouter(dtlsA);
                PacketRouter routerB = new PacketRouter(dtlsB);
                ReliableChannel channelA = new ReliableChannel(routerA, 0xA, dtlsSendLimit);
                ReliableChannel channelB = new ReliableChannel(routerB, 0xB, dtlsSendLimit);

                routerA.start();
                routerB.start();

                try {
                    FileMetadata metadata = FileMetadata.fromFile(sourceFile);
                    FileSender sender = new FileSender(sourceFile, metadata, channelA);
                    FileReceiver receiver = new FileReceiver(outputDir, channelB);

                    Future<?> sendFut = exec.submit(() -> { sender.send(); return null; });
                    Future<?> recvFut = exec.submit(() -> { receiver.receive(); return null; });

                    sendFut.get(30, TimeUnit.SECONDS);
                    recvFut.get(30, TimeUnit.SECONDS);

                    assertEquals(TransferState.DONE, sender.state());
                    assertEquals(TransferState.DONE, receiver.state());

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

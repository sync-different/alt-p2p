package com.alterante.p2p.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PartialTransferStateTest {

    @Test
    void saveAndLoad(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("test.bin");
        Files.writeString(outputFile, "dummy");

        byte[] sha256 = new byte[32];
        new Random(1).nextBytes(sha256);

        PartialTransferState state = new PartialTransferState(
                1_000_000, sha256, 500_000, "test.bin");
        state.save(outputFile);

        // Sidecar file should exist
        assertTrue(Files.exists(PartialTransferState.partialPath(outputFile)));

        // Load and verify
        PartialTransferState loaded = PartialTransferState.load(outputFile);
        assertNotNull(loaded);
        assertEquals(1_000_000, loaded.fileSize());
        assertEquals(500_000, loaded.bytesWritten());
        assertEquals("test.bin", loaded.filename());
        assertArrayEquals(sha256, loaded.sha256());
    }

    @Test
    void loadReturnsNullWhenMissing(@TempDir Path tempDir) {
        Path outputFile = tempDir.resolve("nonexistent.bin");
        assertNull(PartialTransferState.load(outputFile));
    }

    @Test
    void loadReturnsNullForCorruptFile(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("corrupt.bin");
        Files.writeString(outputFile, "dummy");
        Files.write(PartialTransferState.partialPath(outputFile), new byte[]{1, 2, 3});
        assertNull(PartialTransferState.load(outputFile));
    }

    @Test
    void deleteRemovesSidecar(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("del.bin");
        Files.writeString(outputFile, "dummy");

        byte[] sha256 = new byte[32];
        new PartialTransferState(100, sha256, 50, "del.bin").save(outputFile);
        assertTrue(Files.exists(PartialTransferState.partialPath(outputFile)));

        PartialTransferState.delete(outputFile);
        assertFalse(Files.exists(PartialTransferState.partialPath(outputFile)));
    }

    @Test
    void matchesChecksAllFields() throws Exception {
        byte[] sha256 = new byte[32];
        new Random(42).nextBytes(sha256);

        PartialTransferState state = new PartialTransferState(
                1000, sha256, 500, "file.txt");

        FileMetadata matching = new FileMetadata(
                java.util.UUID.randomUUID(), 1000, sha256, "file.txt");
        assertTrue(state.matches(matching));

        // Different filename
        FileMetadata diffName = new FileMetadata(
                java.util.UUID.randomUUID(), 1000, sha256, "other.txt");
        assertFalse(state.matches(diffName));

        // Different size
        FileMetadata diffSize = new FileMetadata(
                java.util.UUID.randomUUID(), 2000, sha256, "file.txt");
        assertFalse(state.matches(diffSize));

        // Different hash
        byte[] otherHash = new byte[32];
        new Random(99).nextBytes(otherHash);
        FileMetadata diffHash = new FileMetadata(
                java.util.UUID.randomUUID(), 1000, otherHash, "file.txt");
        assertFalse(state.matches(diffHash));
    }
}

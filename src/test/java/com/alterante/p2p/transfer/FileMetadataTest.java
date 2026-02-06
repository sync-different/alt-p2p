package com.alterante.p2p.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FileMetadataTest {

    @Test
    void encodeDecodeRoundTrip() {
        UUID id = UUID.randomUUID();
        byte[] sha = new byte[32];
        sha[0] = (byte) 0xAB;
        sha[31] = (byte) 0xCD;

        FileMetadata original = new FileMetadata(id, 12345678L, sha, "test-file.txt");
        byte[] encoded = original.encode();
        FileMetadata decoded = FileMetadata.decode(encoded);

        assertEquals(id, decoded.transferId());
        assertEquals(12345678L, decoded.fileSize());
        assertArrayEquals(sha, decoded.sha256());
        assertEquals("test-file.txt", decoded.filename());
    }

    @Test
    void encodeDecodeWithUnicodeFilename() {
        UUID id = UUID.randomUUID();
        byte[] sha = new byte[32];
        String name = "archivo-\u00e9special.dat"; // é

        FileMetadata original = new FileMetadata(id, 0, sha, name);
        FileMetadata decoded = FileMetadata.decode(original.encode());

        assertEquals(name, decoded.filename());
    }

    @Test
    void fromFileComputesSha256(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "Hello, world!");

        FileMetadata meta = FileMetadata.fromFile(file);

        assertEquals("hello.txt", meta.filename());
        assertEquals(13, meta.fileSize());
        assertNotNull(meta.transferId());
        assertEquals(32, meta.sha256().length);
        // SHA-256 of "Hello, world!" is known
        assertEquals("315f5bdb76d078c43b8ac0064e4a0164612b1fce77c869345bfc94c75894edd3",
                meta.sha256Hex());
    }

    @Test
    void sha256HexFormat() {
        byte[] sha = new byte[32];
        sha[0] = (byte) 0x01;
        sha[1] = (byte) 0xFF;
        FileMetadata meta = new FileMetadata(UUID.randomUUID(), 0, sha, "x");
        assertTrue(meta.sha256Hex().startsWith("01ff"));
        assertEquals(64, meta.sha256Hex().length());
    }
}

package com.alterante.p2p.transfer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BatchManifestTest {

    @Test
    void encodeDecodeRoundTrip() {
        BatchManifest original = new BatchManifest(1234, 7, 9_876_543_210L);
        byte[] encoded = original.encode();
        assertEquals(BatchManifest.SIZE, encoded.length);

        BatchManifest decoded = BatchManifest.decode(encoded);
        assertEquals(1234, decoded.fileCount());
        assertEquals(7, decoded.dirCount());
        assertEquals(9_876_543_210L, decoded.totalBytes());
    }

    @Test
    void decodeRejectsShortPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> BatchManifest.decode(new byte[BatchManifest.SIZE - 1]));
    }

    @Test
    void handlesZeroes() {
        BatchManifest decoded = BatchManifest.decode(new BatchManifest(0, 0, 0).encode());
        assertEquals(0, decoded.fileCount());
        assertEquals(0, decoded.dirCount());
        assertEquals(0, decoded.totalBytes());
    }
}

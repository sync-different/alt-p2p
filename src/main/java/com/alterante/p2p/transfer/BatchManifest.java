package com.alterante.p2p.transfer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Summary of a multi-file (folder) transfer, sent up front as the MANIFEST
 * message payload so the receiver can show overall progress and warn about a
 * non-empty output dir before per-file offers arrive.
 *
 * <pre>
 * Bytes 0-3:  file count (int)
 * Bytes 4-7:  empty-directory count (int)
 * Bytes 8-15: total bytes across all files (long)
 * </pre>
 */
public record BatchManifest(int fileCount, int dirCount, long totalBytes) {

    public static final int SIZE = 4 + 4 + 8; // 16 bytes

    public byte[] encode() {
        return ByteBuffer.allocate(SIZE).order(ByteOrder.BIG_ENDIAN)
                .putInt(fileCount)
                .putInt(dirCount)
                .putLong(totalBytes)
                .array();
    }

    public static BatchManifest decode(byte[] payload) {
        if (payload.length < SIZE) {
            throw new IllegalArgumentException("MANIFEST payload too short: " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int fileCount = buf.getInt();
        int dirCount = buf.getInt();
        long totalBytes = buf.getLong();
        return new BatchManifest(fileCount, dirCount, totalBytes);
    }
}

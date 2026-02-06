package com.alterante.p2p.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Metadata for a file transfer, encoded as FILE_OFFER payload.
 *
 * <pre>
 * Bytes 0-15:  Transfer ID (UUID)
 * Bytes 16-23: File Size (long)
 * Bytes 24-55: SHA-256 Hash (32 bytes)
 * Bytes 56-57: Filename Length (short)
 * Bytes 58+:   Filename (UTF-8)
 * </pre>
 */
public record FileMetadata(UUID transferId, long fileSize, byte[] sha256, String filename) {

    private static final int FIXED_SIZE = 16 + 8 + 32 + 2; // 58 bytes

    /**
     * Create FileMetadata from a file on disk, computing SHA-256.
     */
    public static FileMetadata fromFile(Path file) throws IOException {
        long size = Files.size(file);
        byte[] hash = computeSha256(file);
        String name = file.getFileName().toString();
        return new FileMetadata(UUID.randomUUID(), size, hash, name);
    }

    /**
     * Encode to FILE_OFFER payload bytes.
     */
    public byte[] encode() {
        byte[] nameBytes = filename.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(FIXED_SIZE + nameBytes.length)
                .order(ByteOrder.BIG_ENDIAN);

        // UUID as two longs
        buf.putLong(transferId.getMostSignificantBits());
        buf.putLong(transferId.getLeastSignificantBits());

        buf.putLong(fileSize);
        buf.put(sha256);
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        return buf.array();
    }

    /**
     * Decode from FILE_OFFER payload bytes.
     */
    public static FileMetadata decode(byte[] payload) {
        if (payload.length < FIXED_SIZE) {
            throw new IllegalArgumentException("FILE_OFFER payload too short: " + payload.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        long msb = buf.getLong();
        long lsb = buf.getLong();
        UUID transferId = new UUID(msb, lsb);

        long fileSize = buf.getLong();

        byte[] sha256 = new byte[32];
        buf.get(sha256);

        int nameLen = Short.toUnsignedInt(buf.getShort());
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String filename = new String(nameBytes, StandardCharsets.UTF_8);

        return new FileMetadata(transferId, fileSize, sha256, filename);
    }

    private static byte[] computeSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (var is = Files.newInputStream(file)) {
                int n;
                while ((n = is.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Format SHA-256 as hex string.
     */
    public String sha256Hex() {
        StringBuilder sb = new StringBuilder(64);
        for (byte b : sha256) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

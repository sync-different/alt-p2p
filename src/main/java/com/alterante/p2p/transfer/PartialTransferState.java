package com.alterante.p2p.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists partial transfer state to a .p2p-partial sidecar file.
 * Used to resume interrupted transfers.
 *
 * <pre>
 * Format:
 * Bytes 0-3:   Magic (0x50325052 = "P2PR")
 * Bytes 4-7:   Version (1)
 * Bytes 8-15:  File Size (long)
 * Bytes 16-47: SHA-256 (32 bytes)
 * Bytes 48-55: Bytes Written (long)
 * Bytes 56-57: Filename Length (short)
 * Bytes 58+:   Filename (UTF-8)
 * </pre>
 */
public class PartialTransferState {

    private static final int MAGIC = 0x50325052; // "P2PR"
    private static final int VERSION = 1;
    private static final int FIXED_SIZE = 4 + 4 + 8 + 32 + 8 + 2; // 58 bytes

    private final long fileSize;
    private final byte[] sha256;
    private final long bytesWritten;
    private final String filename;

    public PartialTransferState(long fileSize, byte[] sha256, long bytesWritten, String filename) {
        this.fileSize = fileSize;
        this.sha256 = sha256;
        this.bytesWritten = bytesWritten;
        this.filename = filename;
    }

    /**
     * Get the sidecar file path for a given output file.
     */
    public static Path partialPath(Path outputFile) {
        return outputFile.resolveSibling(outputFile.getFileName() + ".p2p-partial");
    }

    /**
     * Save partial state to the sidecar file.
     */
    public void save(Path outputFile) throws IOException {
        byte[] nameBytes = filename.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(FIXED_SIZE + nameBytes.length)
                .order(ByteOrder.BIG_ENDIAN);

        buf.putInt(MAGIC);
        buf.putInt(VERSION);
        buf.putLong(fileSize);
        buf.put(sha256);
        buf.putLong(bytesWritten);
        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        Files.write(partialPath(outputFile), buf.array());
    }

    /**
     * Load partial state from the sidecar file, or null if not found / invalid.
     */
    public static PartialTransferState load(Path outputFile) {
        Path partial = partialPath(outputFile);
        if (!Files.exists(partial)) return null;

        try {
            byte[] data = Files.readAllBytes(partial);
            if (data.length < FIXED_SIZE) return null;

            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            int magic = buf.getInt();
            int version = buf.getInt();
            if (magic != MAGIC || version != VERSION) return null;

            long fileSize = buf.getLong();
            byte[] sha256 = new byte[32];
            buf.get(sha256);
            long bytesWritten = buf.getLong();
            int nameLen = Short.toUnsignedInt(buf.getShort());
            if (data.length < FIXED_SIZE + nameLen) return null;

            byte[] nameBytes = new byte[nameLen];
            buf.get(nameBytes);
            String filename = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

            return new PartialTransferState(fileSize, sha256, bytesWritten, filename);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Delete the sidecar file.
     */
    public static void delete(Path outputFile) {
        try {
            Files.deleteIfExists(partialPath(outputFile));
        } catch (IOException ignored) {
        }
    }

    /**
     * Check if a partial state matches a new FILE_OFFER.
     * Match means: same filename, same file size, same SHA-256.
     */
    public boolean matches(FileMetadata offer) {
        return filename.equals(offer.filename())
                && fileSize == offer.fileSize()
                && java.util.Arrays.equals(sha256, offer.sha256());
    }

    public long fileSize() { return fileSize; }
    public byte[] sha256() { return sha256; }
    public long bytesWritten() { return bytesWritten; }
    public String filename() { return filename; }
}

package com.alterante.p2p.transfer;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketType;
import com.alterante.p2p.transport.ReliableChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates receiving a file through a ReliableChannel.
 *
 * Flow: wait for FILE_OFFER → send FILE_ACCEPT → receive DATA → verify SHA-256 → send VERIFIED.
 * Supports resuming interrupted transfers via .p2p-partial sidecar files.
 */
public class FileReceiver {

    private static final Logger log = LoggerFactory.getLogger(FileReceiver.class);
    private static final long PARTIAL_SAVE_INTERVAL_MS = 2000;

    private final Path outputDir;
    private final ReliableChannel channel;

    private volatile TransferState state = TransferState.WAITING;
    private FileMetadata metadata;
    private Path outputFile;
    private RandomAccessFile raf;
    private TransferProgress progress;
    private long bytesWritten;
    private long resumeOffset;
    private long lastPartialSaveMs;

    private final CountDownLatch offerLatch = new CountDownLatch(1);
    private final CountDownLatch completeLatch = new CountDownLatch(1);

    public FileReceiver(Path outputDir, ReliableChannel channel) {
        this.outputDir = outputDir;
        this.channel = channel;

        // Register callbacks
        channel.onControlPacket(this::handleControl);
        channel.onDataReceived(this::handleData);
    }

    /**
     * Run the receive flow. Blocks until complete or error.
     */
    public void receive() throws IOException, InterruptedException {
        // 1. Wait for FILE_OFFER
        log.info("Waiting for file offer...");
        if (!offerLatch.await(120_000, TimeUnit.MILLISECONDS)) {
            state = TransferState.ERROR;
            throw new IOException("Timed out waiting for FILE_OFFER");
        }

        if (state == TransferState.CANCELLED) {
            throw new IOException("Transfer cancelled");
        }

        // 2. Accept — check for resume state
        state = TransferState.RECEIVING;
        outputFile = outputDir.resolve(metadata.filename());
        Files.createDirectories(outputDir);

        // Check for partial transfer state
        resumeOffset = 0;
        PartialTransferState partial = PartialTransferState.load(outputFile);
        if (partial != null && partial.matches(metadata)) {
            resumeOffset = partial.bytesWritten();
            bytesWritten = resumeOffset;
            log.info("Resuming transfer from offset {}", resumeOffset);
        }

        raf = new RandomAccessFile(outputFile.toFile(), "rw");
        raf.setLength(metadata.fileSize());
        progress = new TransferProgress(metadata.fileSize());
        progress.update(bytesWritten);

        log.info("Accepting file: {} ({} bytes) -> {}{}",
                metadata.filename(), metadata.fileSize(), outputFile,
                resumeOffset > 0 ? " (resuming from " + resumeOffset + ")" : "");

        // Handle zero-byte file: skip data transfer
        if (metadata.fileSize() == 0) {
            closeFile();
            sendAccept();
            state = TransferState.VERIFYING;
            log.info("Zero-byte file — skipping data transfer.");

            // Wait for COMPLETE (sender sends it immediately for 0-byte files)
            if (!completeLatch.await(30_000, TimeUnit.MILLISECONDS)) {
                state = TransferState.ERROR;
                throw new IOException("Timed out waiting for COMPLETE");
            }
            verifyAndFinish();
            return;
        }

        sendAccept();

        // 3. Wait for COMPLETE
        if (!completeLatch.await(600_000, TimeUnit.MILLISECONDS)) {
            state = TransferState.ERROR;
            savePartialState();
            closeFile();
            throw new IOException("Timed out waiting for COMPLETE");
        }

        if (state == TransferState.CANCELLED) {
            savePartialState();
            closeFile();
            throw new IOException("Transfer cancelled");
        }

        // 4. Verify SHA-256
        state = TransferState.VERIFYING;
        closeFile();
        verifyAndFinish();
    }

    private void sendAccept() throws IOException {
        ByteBuffer acceptBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        acceptBuf.putLong(metadata.transferId().getMostSignificantBits());
        acceptBuf.putLong(metadata.transferId().getLeastSignificantBits());
        acceptBuf.putLong(resumeOffset);
        channel.sendControl(new Packet(PacketType.FILE_ACCEPT, acceptBuf.array()));
    }

    private void verifyAndFinish() throws IOException {
        log.info("Verifying file integrity...");

        byte[] actualHash = computeSha256(outputFile);
        if (Arrays.equals(actualHash, metadata.sha256())) {
            channel.sendControl(new Packet(PacketType.VERIFIED));
            PartialTransferState.delete(outputFile);
            state = TransferState.DONE;
            log.info("File verified. Transfer complete: {}", outputFile);
        } else {
            savePartialState();
            state = TransferState.ERROR;
            log.error("SHA-256 mismatch! Expected: {}, Got: {}",
                    hexString(metadata.sha256()), hexString(actualHash));
            throw new IOException("File integrity check failed");
        }
    }

    private void handleControl(Packet pkt) {
        switch (pkt.type()) {
            case FILE_OFFER -> {
                metadata = FileMetadata.decode(pkt.payload());
                log.info("Received FILE_OFFER: {} ({} bytes, SHA-256: {})",
                        metadata.filename(), metadata.fileSize(), metadata.sha256Hex());
                offerLatch.countDown();
            }
            case COMPLETE -> {
                log.info("Received COMPLETE signal. Bytes written: {}/{}", bytesWritten, metadata.fileSize());
                completeLatch.countDown();
            }
            case CANCEL -> {
                log.warn("Transfer cancelled by sender");
                state = TransferState.CANCELLED;
                offerLatch.countDown();
                completeLatch.countDown();
            }
            default -> log.debug("Receiver ignoring control packet: {}", pkt.type());
        }
    }

    private void handleData(ReliableChannel.DataPayload data) {
        if (raf == null) return; // not yet accepting

        try {
            synchronized (this) {
                raf.seek(data.byteOffset());
                raf.write(data.data());
                bytesWritten += data.data().length;
                if (progress != null) {
                    progress.update(bytesWritten);
                }

                // Periodically save partial state
                long now = System.currentTimeMillis();
                if (now - lastPartialSaveMs > PARTIAL_SAVE_INTERVAL_MS) {
                    savePartialState();
                    lastPartialSaveMs = now;
                }
            }
        } catch (IOException e) {
            log.error("Error writing data at offset {}: {}", data.byteOffset(), e.getMessage());
            state = TransferState.ERROR;
        }
    }

    private void savePartialState() {
        if (outputFile == null || metadata == null) return;
        try {
            new PartialTransferState(metadata.fileSize(), metadata.sha256(), bytesWritten, metadata.filename())
                    .save(outputFile);
        } catch (IOException e) {
            log.debug("Failed to save partial state: {}", e.getMessage());
        }
    }

    private void closeFile() {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException e) {
                log.debug("Error closing file: {}", e.getMessage());
            }
            raf = null;
        }
    }

    private static byte[] computeSha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (var is = Files.newInputStream(file)) {
                int n;
                while ((n = is.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static String hexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public TransferState state() { return state; }
    public TransferProgress progress() { return progress; }
    public FileMetadata metadata() { return metadata; }
    public Path outputFile() { return outputFile; }
}

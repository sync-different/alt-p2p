package com.alterante.p2p.transfer;

import com.alterante.p2p.net.TcpRelayProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Receives a file over a TLS-encrypted TCP relay connection.
 *
 * Flow: wait FILE_OFFER → send FILE_ACCEPT → receive DATA → verify SHA-256 → send VERIFIED.
 * Uses TcpRelayProtocol message framing (type + length + payload).
 */
public class TcpFileReceiver {

    private static final Logger log = LoggerFactory.getLogger(TcpFileReceiver.class);
    private static final long PARTIAL_SAVE_INTERVAL_MS = 2000;

    private final Path outputDir;
    private final InputStream in;
    private final OutputStream out;

    private volatile TransferState state = TransferState.WAITING;
    private FileMetadata metadata;
    private Path outputFile;
    private TransferProgress progress;
    private long bytesWritten;
    private long resumeOffset;
    private long lastPartialSaveMs;

    public TcpFileReceiver(Path outputDir, InputStream tlsIn, OutputStream tlsOut) {
        this.outputDir = outputDir;
        this.in = tlsIn;
        this.out = tlsOut;
    }

    /**
     * Run the receive flow. Blocks until complete or error.
     */
    public void receive() throws IOException, InterruptedException {
        // 1. Wait for FILE_OFFER
        log.info("Waiting for file offer...");
        TcpRelayProtocol.Message offer = TcpRelayProtocol.readMessage(in);
        if (offer.type() != TcpRelayProtocol.MSG_FILE_OFFER) {
            state = TransferState.ERROR;
            throw new IOException("Expected FILE_OFFER, got type 0x" + String.format("%02X", offer.type()));
        }

        metadata = FileMetadata.decode(offer.payload());
        log.info("Received FILE_OFFER: {} ({} bytes, SHA-256: {})",
                metadata.filename(), metadata.fileSize(), metadata.sha256Hex());

        // 2. Accept — check for resume state
        state = TransferState.RECEIVING;
        outputFile = outputDir.resolve(metadata.filename());
        Files.createDirectories(outputDir);

        resumeOffset = 0;
        PartialTransferState partial = PartialTransferState.load(outputFile);
        if (partial != null && partial.matches(metadata)) {
            resumeOffset = partial.bytesWritten();
            bytesWritten = resumeOffset;
            log.info("Resuming transfer from offset {}", resumeOffset);
        }

        progress = new TransferProgress(metadata.fileSize());
        progress.update(bytesWritten);

        log.info("Accepting file: {} ({} bytes) -> {}{}",
                metadata.filename(), metadata.fileSize(), outputFile,
                resumeOffset > 0 ? " (resuming from " + resumeOffset + ")" : "");

        // Handle zero-byte file
        if (metadata.fileSize() == 0) {
            Files.createFile(outputFile);
            sendAccept();
            state = TransferState.VERIFYING;

            // Wait for COMPLETE
            TcpRelayProtocol.Message complete = TcpRelayProtocol.readMessage(in);
            if (complete.type() != TcpRelayProtocol.MSG_COMPLETE) {
                state = TransferState.ERROR;
                throw new IOException("Expected COMPLETE, got type 0x" + String.format("%02X", complete.type()));
            }
            verifyAndFinish();
            return;
        }

        sendAccept();

        // 3. Receive DATA messages
        try (RandomAccessFile raf = new RandomAccessFile(outputFile.toFile(), "rw")) {
            raf.setLength(metadata.fileSize());
            raf.seek(resumeOffset);

            while (true) {
                TcpRelayProtocol.Message msg = TcpRelayProtocol.readMessage(in);

                if (msg.type() == TcpRelayProtocol.MSG_COMPLETE) {
                    log.info("Received COMPLETE signal. Bytes written: {}/{}", bytesWritten, metadata.fileSize());
                    break;
                }

                if (msg.type() != TcpRelayProtocol.MSG_DATA) {
                    log.warn("Unexpected message type during transfer: 0x{}", String.format("%02X", msg.type()));
                    continue;
                }

                byte[] data = msg.payload();
                raf.write(data);
                bytesWritten += data.length;
                progress.update(bytesWritten);

                // Periodically save partial state
                long now = System.currentTimeMillis();
                if (now - lastPartialSaveMs > PARTIAL_SAVE_INTERVAL_MS) {
                    savePartialState();
                    lastPartialSaveMs = now;
                }
            }
        }

        // 4. Verify SHA-256
        state = TransferState.VERIFYING;
        verifyAndFinish();
    }

    private void sendAccept() throws IOException {
        ByteBuffer acceptBuf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        acceptBuf.putLong(metadata.transferId().getMostSignificantBits());
        acceptBuf.putLong(metadata.transferId().getLeastSignificantBits());
        acceptBuf.putLong(resumeOffset);
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_FILE_ACCEPT, acceptBuf.array());
    }

    private void verifyAndFinish() throws IOException {
        log.info("Verifying file integrity...");

        byte[] actualHash = computeSha256(outputFile);
        if (Arrays.equals(actualHash, metadata.sha256())) {
            TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_VERIFIED);
            PartialTransferState.delete(outputFile);
            state = TransferState.DONE;
            log.info("File verified. Transfer complete: {}", outputFile);
        } else {
            savePartialState();
            state = TransferState.ERROR;
            log.error("SHA-256 mismatch! Expected: {}, Got: {}",
                    metadata.sha256Hex(), hexString(actualHash));
            throw new IOException("File integrity check failed");
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

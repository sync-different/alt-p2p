package com.alterante.p2p.transfer;

import com.alterante.p2p.net.TcpRelayProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Receives a multi-file (folder) batch over a TLS-encrypted TCP relay connection,
 * preserving directory structure — the stream-based counterpart of
 * {@link DirectoryReceiver}. Also handles a legacy bare FILE_OFFER (no MANIFEST)
 * as a single-file transfer for backward compatibility.
 */
public class TcpDirectoryReceiver {

    private static final Logger log = LoggerFactory.getLogger(TcpDirectoryReceiver.class);
    private static final long PARTIAL_SAVE_INTERVAL_MS = 2000;

    private final Path outputDir;
    private final InputStream in;
    private final OutputStream out;
    private final ConflictPolicy conflictPolicy;

    private boolean batchMode;
    private BatchManifest manifest;
    private BatchProgress batch;
    private int filesReceived;
    private int filesSkipped;
    private int dirsCreated;
    private long bytesReceived;
    private Path lastFile;
    private volatile boolean awaitingUser;

    public TcpDirectoryReceiver(Path outputDir, InputStream in, OutputStream out) {
        this(outputDir, in, out, ConflictPolicy.overwrite());
    }

    public TcpDirectoryReceiver(Path outputDir, InputStream in, OutputStream out, ConflictPolicy conflictPolicy) {
        this.outputDir = outputDir;
        this.in = in;
        this.out = out;
        this.conflictPolicy = conflictPolicy;
    }

    public BatchProgress batchProgress() { return batch; }
    public boolean isAwaitingUser() { return awaitingUser; }
    public boolean wasBatch() { return batchMode; }
    public int filesReceived() { return filesReceived; }
    public int filesSkipped() { return filesSkipped; }
    public int dirsCreated() { return dirsCreated; }
    public long bytesReceived() { return bytesReceived; }
    public Path outputDir() { return outputDir; }
    public Path lastFile() { return lastFile; }

    public void receive() throws IOException {
        log.info("Waiting for transfer (relay)...");
        while (true) {
            TcpRelayProtocol.Message msg;
            try {
                msg = TcpRelayProtocol.readMessage(in);
            } catch (EOFException e) {
                // Peer closed the stream. In single-file mode that's the normal end.
                if (!batchMode) return;
                throw e;
            }
            switch (msg.type()) {
                case TcpRelayProtocol.MSG_MANIFEST -> onManifest(msg.payload());
                case TcpRelayProtocol.MSG_DIR_ENTRY -> onDirEntry(msg.payload());
                case TcpRelayProtocol.MSG_FILE_OFFER -> {
                    receiveOneFile(FileMetadata.decode(msg.payload()));
                    if (!batchMode) return; // single-file: done after one
                }
                case TcpRelayProtocol.MSG_SESSION_COMPLETE -> {
                    log.info("Transfer complete (relay): {} received, {} skipped into {}",
                            filesReceived, filesSkipped, outputDir);
                    return;
                }
                default -> log.warn("Unexpected relay message type 0x{}", String.format("%02X", msg.type()));
            }
        }
    }

    private void onManifest(byte[] payload) throws IOException {
        manifest = BatchManifest.decode(payload);
        batchMode = true;
        batch = new BatchProgress(manifest.fileCount(), manifest.totalBytes());
        Files.createDirectories(outputDir);
        log.info("Receiving folder (relay): {} files, {} empty dirs, {} bytes",
                manifest.fileCount(), manifest.dirCount(), manifest.totalBytes());
    }

    private void onDirEntry(byte[] payload) throws IOException {
        String relDir = new String(payload, StandardCharsets.UTF_8);
        Files.createDirectories(PathSafety.resolveChild(outputDir, relDir));
        dirsCreated++;
    }

    private void receiveOneFile(FileMetadata meta) throws IOException {
        if (batch == null) batch = new BatchProgress(1, meta.fileSize());

        Path target = PathSafety.resolveChild(outputDir, meta.filename());
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        long offset = 0;
        PartialTransferState partial = PartialTransferState.load(target);
        if (partial != null && partial.matches(meta)) {
            offset = partial.bytesWritten();
            log.info("Resuming {} from offset {}", meta.filename(), offset);
        } else if (Files.exists(target)) {
            if (Files.size(target) == meta.fileSize()
                    && Arrays.equals(computeSha256(target), meta.sha256())) {
                log.info("Already have {} (identical) — skipping", meta.filename());
                rejectFile(target, meta.fileSize());
                return;
            }
            awaitingUser = true;
            ConflictPolicy.Decision decision;
            try {
                decision = conflictPolicy.resolve(meta.filename());
            } finally {
                awaitingUser = false;
            }
            switch (decision) {
                case SKIP -> {
                    log.info("Conflict on {} — skipping (user/policy)", meta.filename());
                    rejectFile(target, meta.fileSize());
                    return;
                }
                case KEEP_BOTH -> {
                    target = DirectoryReceiver.keepBothName(target);
                    log.info("Conflict on {} — keeping both as {}", meta.filename(), target.getFileName());
                }
                case OVERWRITE -> log.info("Conflict on {} — overwriting", meta.filename());
            }
        }

        TransferProgress progress = new TransferProgress(meta.fileSize());
        progress.update(offset);
        batch.startFile(meta.filename(), progress);

        sendAccept(meta, offset);

        long bytesWritten = offset;
        long lastSave = System.currentTimeMillis();
        try (RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw")) {
            raf.setLength(meta.fileSize());
            // Sidecar immediately: setLength makes a full-size sparse file, so an
            // interruption before the first periodic save must still resume, not conflict.
            savePartial(target, meta, bytesWritten);
            raf.seek(offset);

            while (true) {
                TcpRelayProtocol.Message msg = TcpRelayProtocol.readMessage(in);
                if (msg.type() == TcpRelayProtocol.MSG_COMPLETE) break;
                if (msg.type() != TcpRelayProtocol.MSG_DATA) {
                    log.warn("Unexpected message during {} transfer: 0x{}",
                            meta.filename(), String.format("%02X", msg.type()));
                    continue;
                }
                byte[] data = msg.payload();
                raf.write(data);
                bytesWritten += data.length;
                progress.update(bytesWritten);
                long now = System.currentTimeMillis();
                if (now - lastSave > PARTIAL_SAVE_INTERVAL_MS) {
                    savePartial(target, meta, bytesWritten);
                    lastSave = now;
                }
            }
        }

        byte[] actual = computeSha256(target);
        if (Arrays.equals(actual, meta.sha256())) {
            TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_VERIFIED);
            PartialTransferState.delete(target);
            batch.fileCompleted(meta.fileSize());
            bytesReceived += meta.fileSize();
            filesReceived++;
            lastFile = target;
            log.info("Verified {} -> {}", meta.filename(), target);
        } else {
            savePartial(target, meta, bytesWritten);
            throw new IOException("SHA-256 mismatch: " + meta.filename());
        }
    }

    private void rejectFile(Path target, long size) throws IOException {
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_FILE_REJECT);
        batch.fileSkipped(size);
        filesSkipped++;
        lastFile = target;
    }

    private void sendAccept(FileMetadata meta, long offset) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(meta.transferId().getMostSignificantBits());
        buf.putLong(meta.transferId().getLeastSignificantBits());
        buf.putLong(offset);
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_FILE_ACCEPT, buf.array());
    }

    private static void savePartial(Path target, FileMetadata meta, long bytesWritten) {
        try {
            new PartialTransferState(meta.fileSize(), meta.sha256(), bytesWritten, meta.filename())
                    .save(target);
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
                while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

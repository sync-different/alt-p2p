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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Receives a multi-file (folder) batch through a ReliableChannel, preserving
 * directory structure. Also handles a legacy bare FILE_OFFER (no MANIFEST) as a
 * single-file transfer, for backward compatibility with single-file senders.
 *
 * <p>Threading: DATA packets are written on the PacketRouter thread (so disk
 * speed backpressures the sender). Control packets are processed on a dedicated
 * single-thread worker, in arrival order — this keeps the router free to send
 * keepalives while a control step blocks (hashing an existing file, or an
 * interactive conflict prompt that may wait on the user indefinitely).
 */
public class DirectoryReceiver {

    private static final Logger log = LoggerFactory.getLogger(DirectoryReceiver.class);
    private static final long PARTIAL_SAVE_INTERVAL_MS = 2000;
    private static final long IDLE_TIMEOUT_MS = 120_000;

    private final Path outputDir;
    private final ReliableChannel channel;
    private final ConflictPolicy conflictPolicy;
    private final Object ioLock = new Object();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dir-receiver");
                t.setDaemon(true);
                return t;
            });

    private final CountDownLatch doneLatch = new CountDownLatch(1);
    private volatile long lastActivityMs;
    private volatile boolean awaitingUser; // suppresses idle timeout during a prompt
    private volatile boolean error;
    private volatile String errorMsg;
    private volatile boolean cancelled;

    // Batch state (worker thread)
    private volatile boolean batchMode;
    private BatchManifest manifest;
    private volatile BatchProgress batch;
    private boolean sessionComplete;
    private int filesReceived;
    private int filesSkipped;
    private int dirsCreated;
    private long bytesReceived;

    // Current file state (worker thread, except curRaf/curBytesWritten read by handleData)
    private FileMetadata curMeta;
    private Path curTarget;
    private volatile RandomAccessFile curRaf;
    private long curBytesWritten;
    private TransferProgress curProgress;
    private long lastPartialSaveMs;
    private Path lastFile;

    public DirectoryReceiver(Path outputDir, ReliableChannel channel) {
        this(outputDir, channel, ConflictPolicy.overwrite());
    }

    public DirectoryReceiver(Path outputDir, ReliableChannel channel, ConflictPolicy conflictPolicy) {
        this.outputDir = outputDir;
        this.channel = channel;
        this.conflictPolicy = conflictPolicy;
        channel.onControlPacket(this::onControl);
        channel.onDataReceived(this::handleData);
    }

    /** Run the batch receive. Blocks until complete, cancelled, or error. */
    public void receive() throws IOException, InterruptedException {
        lastActivityMs = System.currentTimeMillis();
        log.info("Waiting for transfer...");
        try {
            while (!doneLatch.await(1, TimeUnit.SECONDS)) {
                if (error) break;
                if (!awaitingUser && System.currentTimeMillis() - lastActivityMs > IDLE_TIMEOUT_MS) {
                    throw new IOException("Timed out — no data received for "
                            + (IDLE_TIMEOUT_MS / 1000) + "s");
                }
            }
        } finally {
            worker.shutdownNow();
        }
        if (error) throw new IOException(errorMsg);
        if (cancelled) throw new IOException("Transfer cancelled by sender");
        log.info("Transfer complete: {} received, {} skipped into {}",
                filesReceived, filesSkipped, outputDir);
    }

    // --- accessors ---
    public BatchProgress batchProgress() { return batch; }
    public boolean isAwaitingUser() { return awaitingUser; }
    public boolean isDone() { return doneLatch.getCount() == 0; }
    public boolean wasBatch() { return batchMode; }
    public int filesReceived() { return filesReceived; }
    public int filesSkipped() { return filesSkipped; }
    public int dirsCreated() { return dirsCreated; }
    public long bytesReceived() { return bytesReceived; }
    public Path outputDir() { return outputDir; }
    public Path lastFile() { return lastFile; }

    // --- router thread: stamp activity, hand control packets to the worker ---

    private void onControl(Packet pkt) {
        lastActivityMs = System.currentTimeMillis();
        worker.submit(() -> handleControl(pkt));
    }

    // --- worker thread: process control packets in arrival order ---

    private void handleControl(Packet pkt) {
        try {
            switch (pkt.type()) {
                case MANIFEST -> onManifest(pkt.payload());
                case DIR_ENTRY -> onDirEntry(pkt.payload());
                case FILE_OFFER -> onFileOffer(pkt.payload());
                case COMPLETE -> finishCurrentFile();
                case SESSION_COMPLETE -> { sessionComplete = true; maybeComplete(); }
                case CANCEL -> { cancelled = true; doneLatch.countDown(); }
                default -> log.debug("DirectoryReceiver ignoring control packet: {}", pkt.type());
            }
        } catch (IOException | IllegalArgumentException e) {
            fail(e.getMessage());
        }
    }

    private void onManifest(byte[] payload) throws IOException {
        manifest = BatchManifest.decode(payload);
        batchMode = true;
        batch = new BatchProgress(manifest.fileCount(), manifest.totalBytes());
        Files.createDirectories(outputDir);
        if (isNonEmptyDir(outputDir)) {
            log.warn("Output directory {} is not empty — existing files may be skipped or overwritten",
                    outputDir);
        }
        log.info("Receiving folder: {} files, {} empty dirs, {} bytes",
                manifest.fileCount(), manifest.dirCount(), manifest.totalBytes());
        maybeComplete(); // handles an empty (0-file, 0-dir) batch
    }

    private void onDirEntry(byte[] payload) throws IOException {
        String relDir = new String(payload, StandardCharsets.UTF_8);
        Path dir = PathSafety.resolveChild(outputDir, relDir);
        Files.createDirectories(dir);
        dirsCreated++;
        maybeComplete();
    }

    private void onFileOffer(byte[] payload) throws IOException {
        FileMetadata meta = FileMetadata.decode(payload);
        if (batch == null) {
            // Legacy single-file transfer (no MANIFEST) — treat as a one-file batch.
            batch = new BatchProgress(1, meta.fileSize());
        }
        Path target = PathSafety.resolveChild(outputDir, meta.filename());
        Path parent = target.getParent();
        if (parent != null) Files.createDirectories(parent);

        log.info("Offered {} ({} bytes) -> {}", meta.filename(), meta.fileSize(), target);

        long offset = 0;
        PartialTransferState partial = PartialTransferState.load(target);
        if (partial != null && partial.matches(meta)) {
            // 1. Resume from a matching partial sidecar.
            offset = partial.bytesWritten();
            log.info("Resuming {} from offset {}", meta.filename(), offset);
        } else if (Files.exists(target)) {
            if (Files.size(target) == meta.fileSize()
                    && Arrays.equals(computeSha256(target), meta.sha256())) {
                // 2. Already present and identical (D4) — skip silently.
                log.info("Already have {} (identical) — skipping", meta.filename());
                rejectFile(target, meta.fileSize());
                return;
            }
            // 3. Exists but differs — apply conflict policy (R4).
            awaitingUser = conflictPolicy.mayPrompt();
            ConflictPolicy.Decision decision;
            try {
                decision = conflictPolicy.resolve(meta.filename());
            } finally {
                awaitingUser = false;
                lastActivityMs = System.currentTimeMillis();
            }
            switch (decision) {
                case SKIP -> {
                    log.info("Conflict on {} — skipping (user/policy)", meta.filename());
                    rejectFile(target, meta.fileSize());
                    return;
                }
                case KEEP_BOTH -> {
                    target = keepBothName(target);
                    log.info("Conflict on {} — keeping both as {}", meta.filename(), target.getFileName());
                }
                case OVERWRITE -> log.info("Conflict on {} — overwriting", meta.filename());
            }
        }

        curMeta = meta;
        curTarget = target;
        curBytesWritten = offset;
        curProgress = new TransferProgress(meta.fileSize());
        curProgress.update(offset);
        batch.startFile(meta.filename(), curProgress);
        lastPartialSaveMs = System.currentTimeMillis();

        RandomAccessFile raf = new RandomAccessFile(target.toFile(), "rw");
        raf.setLength(meta.fileSize());
        curRaf = raf; // volatile publish before FILE_ACCEPT so handleData sees it

        // Write the sidecar immediately: setLength() above makes a full-size sparse
        // file, so without a sidecar an interruption before the first periodic save
        // would look like a same-size conflict on re-run instead of a resume.
        savePartial();

        sendAccept(meta, offset);
    }

    /** Reject the current offer (skip) and account for it. */
    private void rejectFile(Path target, long size) throws IOException {
        channel.sendControl(new Packet(PacketType.FILE_REJECT));
        if (batch != null) batch.fileSkipped(size);
        filesSkipped++;
        lastFile = target;
        afterFileProcessed();
    }

    // --- router thread: write DATA straight to disk (backpressure) ---

    private void handleData(ReliableChannel.DataPayload data) {
        lastActivityMs = System.currentTimeMillis();
        RandomAccessFile raf = curRaf;
        if (raf == null) return; // between files
        try {
            synchronized (ioLock) {
                raf.seek(data.byteOffset());
                raf.write(data.data());
                curBytesWritten += data.data().length;
                if (curProgress != null) curProgress.update(curBytesWritten);

                long now = System.currentTimeMillis();
                if (now - lastPartialSaveMs > PARTIAL_SAVE_INTERVAL_MS) {
                    savePartial();
                    lastPartialSaveMs = now;
                }
            }
        } catch (IOException e) {
            fail("Error writing " + (curTarget != null ? curTarget : "file") + ": " + e.getMessage());
        }
    }

    private void finishCurrentFile() throws IOException {
        if (curMeta == null) return; // stray COMPLETE
        synchronized (ioLock) {
            closeRaf();
        }
        byte[] actual = computeSha256(curTarget);
        if (Arrays.equals(actual, curMeta.sha256())) {
            channel.sendControl(new Packet(PacketType.VERIFIED));
            PartialTransferState.delete(curTarget);
            if (batch != null) batch.fileCompleted(curMeta.fileSize());
            bytesReceived += curMeta.fileSize();
            filesReceived++;
            lastFile = curTarget;
            log.info("Verified {} -> {}", curMeta.filename(), curTarget);
            curMeta = null;
            curTarget = null;
            curProgress = null;
            afterFileProcessed();
        } else {
            savePartial();
            fail("SHA-256 mismatch: " + curMeta.filename());
        }
    }

    private void afterFileProcessed() {
        if (!batchMode) {
            doneLatch.countDown(); // single-file (legacy): complete after the one file
        } else {
            maybeComplete();
        }
    }

    private void maybeComplete() {
        if (!batchMode) return;
        if (sessionComplete
                || (manifest != null
                    && (filesReceived + filesSkipped) >= manifest.fileCount()
                    && dirsCreated >= manifest.dirCount())) {
            doneLatch.countDown();
        }
    }

    private void sendAccept(FileMetadata meta, long offset) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(meta.transferId().getMostSignificantBits());
        buf.putLong(meta.transferId().getLeastSignificantBits());
        buf.putLong(offset);
        channel.sendControl(new Packet(PacketType.FILE_ACCEPT, buf.array()));
    }

    /** Pick the first free "name (n).ext" beside the target for keep-both. */
    static Path keepBothName(Path target) {
        Path parent = target.getParent();
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidateName = stem + " (" + i + ")" + ext;
            Path candidate = (parent != null) ? parent.resolve(candidateName) : Path.of(candidateName);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
    }

    private void savePartial() {
        if (curTarget == null || curMeta == null) return;
        try {
            new PartialTransferState(curMeta.fileSize(), curMeta.sha256(),
                    curBytesWritten, curMeta.filename()).save(curTarget);
        } catch (IOException e) {
            log.debug("Failed to save partial state: {}", e.getMessage());
        }
    }

    private void closeRaf() {
        RandomAccessFile raf = curRaf;
        if (raf != null) {
            try { raf.close(); } catch (IOException e) { log.debug("close error: {}", e.getMessage()); }
            curRaf = null;
        }
    }

    private void fail(String msg) {
        error = true;
        errorMsg = msg;
        synchronized (ioLock) { closeRaf(); }
        savePartial();
        doneLatch.countDown();
    }

    private static boolean isNonEmptyDir(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try (var s = Files.list(dir)) {
            return s.findAny().isPresent();
        } catch (IOException e) {
            return false;
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

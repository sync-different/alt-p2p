package com.alterante.p2p.transfer;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketType;
import com.alterante.p2p.transport.ReliableChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Sends a folder (recursively) through a ReliableChannel as a multi-file batch.
 *
 * Wire flow: MANIFEST → DIR_ENTRY* (empty dirs) → per-file
 * (FILE_OFFER → FILE_ACCEPT|FILE_REJECT → DATA… → COMPLETE → VERIFIED) → SESSION_COMPLETE.
 *
 * Files are sent sequentially over the single channel. Symlinks are skipped
 * with a warning; directory structure (incl. empty subfolders) is preserved.
 */
public class DirectorySender {

    private static final Logger log = LoggerFactory.getLogger(DirectorySender.class);
    private static final long CONTROL_TIMEOUT_MS = 30_000;
    private static final long ALL_ACKED_TIMEOUT_MS = 60_000;
    // Generous: the receiver may be waiting on an interactive conflict prompt (R4).
    private static final long ACCEPT_TIMEOUT_MS = 600_000;

    /** A regular file to send, with its path relative to the scanned root. */
    public record FileEntry(Path abs, String relPath, long size) {}

    /** Result of scanning a directory tree. */
    public record Scan(List<FileEntry> files, List<String> emptyDirs,
                       List<String> skippedSymlinks, long totalBytes) {}

    private final Path root;
    private final ReliableChannel channel;
    private final Scan scan;
    private final BatchProgress batch;

    // Per-file coordination (reset before each FILE_OFFER)
    private volatile CountDownLatch acceptLatch;
    private volatile CountDownLatch verifiedLatch;
    private volatile CountDownLatch allAckedLatch;
    private volatile long resumeOffset;
    private volatile boolean rejected;
    private volatile boolean cancelled;
    private volatile boolean pumpComplete;

    private int filesSent;
    private int filesSkipped;

    public DirectorySender(Path root, ReliableChannel channel) throws IOException {
        this(root, channel, scan(root));
    }

    /** Construct with a pre-computed scan (avoids walking the tree twice). */
    public DirectorySender(Path root, ReliableChannel channel, Scan scan) {
        this.root = root;
        this.channel = channel;
        this.scan = scan;
        this.batch = new BatchProgress(scan.files().size(), scan.totalBytes());

        channel.onControlPacket(this::handleControl);
        channel.onAllAcked(() -> {
            if (pumpComplete) {
                CountDownLatch l = allAckedLatch;
                if (l != null) l.countDown();
            }
        });
    }

    /** Walk a directory tree: collect regular files, empty dirs, and skipped symlinks. */
    public static Scan scan(Path root) throws IOException {
        List<FileEntry> files = new ArrayList<>();
        List<Path> allDirs = new ArrayList<>();
        Set<Path> fileAncestors = new HashSet<>();
        List<String> skipped = new ArrayList<>();
        long[] total = {0};

        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isSymbolicLink(p)) {
                    skipped.add(rel(root, p));
                    continue;
                }
                if (Files.isDirectory(p)) {
                    allDirs.add(p);
                } else if (Files.isRegularFile(p)) {
                    long size = Files.size(p);
                    files.add(new FileEntry(p, rel(root, p), size));
                    total[0] += size;
                    for (Path a = p.getParent(); a != null && !a.equals(root); a = a.getParent()) {
                        fileAncestors.add(a);
                    }
                }
            }
        }

        // Empty dirs = directories that are not an ancestor of any file (so they
        // won't be created implicitly when files are written) and not the root.
        List<String> emptyDirs = new ArrayList<>();
        for (Path d : allDirs) {
            if (d.equals(root) || fileAncestors.contains(d)) continue;
            String r = rel(root, d);
            if (!r.isEmpty()) emptyDirs.add(r);
        }

        files.sort(Comparator.comparing(FileEntry::relPath));
        emptyDirs.sort(Comparator.naturalOrder());
        skipped.sort(Comparator.naturalOrder());
        return new Scan(files, emptyDirs, skipped, total[0]);
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace(File.separatorChar, '/');
    }

    public Scan scanResult() { return scan; }
    public BatchProgress batchProgress() { return batch; }
    public int filesSent() { return filesSent; }
    public int filesSkipped() { return filesSkipped; }

    /** Run the full batch send. Blocks until complete or error. */
    public void send() throws IOException, InterruptedException {
        log.info("Sending folder {}: {} files, {} empty dirs, {} bytes ({} symlinks skipped)",
                root, scan.files().size(), scan.emptyDirs().size(), scan.totalBytes(),
                scan.skippedSymlinks().size());
        for (String link : scan.skippedSymlinks()) {
            log.warn("Skipping symlink: {}", link);
        }

        // 1. MANIFEST
        BatchManifest manifest = new BatchManifest(
                scan.files().size(), scan.emptyDirs().size(), scan.totalBytes());
        channel.sendControl(new Packet(PacketType.MANIFEST, manifest.encode()));

        // 2. Empty directories
        for (String dir : scan.emptyDirs()) {
            channel.sendControl(new Packet(PacketType.DIR_ENTRY, dir.getBytes(StandardCharsets.UTF_8)));
        }

        // 3. Files
        for (FileEntry entry : scan.files()) {
            sendOneFile(entry);
            if (cancelled) throw new IOException("Transfer cancelled by receiver");
        }

        // 4. Terminator
        channel.sendControl(new Packet(PacketType.SESSION_COMPLETE));
        log.info("Folder transfer complete: {} sent, {} skipped", filesSent, filesSkipped);
    }

    private void sendOneFile(FileEntry entry) throws IOException, InterruptedException {
        FileMetadata meta = FileMetadata.fromFile(entry.abs(), entry.relPath());

        acceptLatch = new CountDownLatch(1);
        verifiedLatch = new CountDownLatch(1);
        allAckedLatch = new CountDownLatch(1);
        rejected = false;
        resumeOffset = 0;
        pumpComplete = false;

        TransferProgress progress = new TransferProgress(entry.size());
        batch.startFile(entry.relPath(), progress);

        log.info("Offering {} ({} bytes)", entry.relPath(), entry.size());
        channel.sendControl(new Packet(PacketType.FILE_OFFER, meta.encode()));

        if (!acceptLatch.await(ACCEPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("Timed out waiting for FILE_ACCEPT: " + entry.relPath());
        }
        if (cancelled) return;
        if (rejected) {
            log.info("Receiver already has {} — skipping", entry.relPath());
            batch.fileSkipped(entry.size());
            filesSkipped++;
            return;
        }

        pumpData(entry, progress);
        pumpComplete = true;

        if (channel.inflightCount() == 0) {
            allAckedLatch.countDown();
        }
        if (!allAckedLatch.await(ALL_ACKED_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log.warn("Timed out waiting for ACKs on {}, proceeding to COMPLETE", entry.relPath());
        }

        channel.sendControl(new Packet(PacketType.COMPLETE, meta.sha256()));
        if (!verifiedLatch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IOException("Timed out waiting for VERIFIED: " + entry.relPath());
        }
        batch.fileCompleted(entry.size());
        filesSent++;
    }

    private void pumpData(FileEntry entry, TransferProgress progress)
            throws IOException, InterruptedException {
        int chunkSize = channel.maxChunkData();
        try (RandomAccessFile raf = new RandomAccessFile(entry.abs().toFile(), "r")) {
            raf.seek(resumeOffset);
            long offset = resumeOffset;
            int chunkIndex = (int) (resumeOffset / chunkSize);
            byte[] buf = new byte[chunkSize];

            while (offset < entry.size()) {
                int toRead = (int) Math.min(buf.length, entry.size() - offset);
                raf.readFully(buf, 0, toRead);
                byte[] data = (toRead == buf.length) ? buf : java.util.Arrays.copyOf(buf, toRead);
                channel.sendData(chunkIndex, offset, data);
                offset += toRead;
                chunkIndex++;
                progress.update(offset - resumeOffset);
            }
        }
    }

    private void handleControl(Packet pkt) {
        switch (pkt.type()) {
            case FILE_ACCEPT -> {
                byte[] payload = pkt.payload();
                if (payload.length >= 24) {
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(payload)
                            .order(java.nio.ByteOrder.BIG_ENDIAN);
                    buf.position(16);
                    resumeOffset = buf.getLong();
                }
                CountDownLatch l = acceptLatch;
                if (l != null) l.countDown();
            }
            case FILE_REJECT -> {
                rejected = true;
                CountDownLatch l = acceptLatch;
                if (l != null) l.countDown();
            }
            case VERIFIED -> {
                CountDownLatch l = verifiedLatch;
                if (l != null) l.countDown();
            }
            case CANCEL -> {
                log.warn("Transfer cancelled by receiver");
                cancelled = true;
                countDownAll();
            }
            default -> log.debug("DirectorySender ignoring control packet: {}", pkt.type());
        }
    }

    private void countDownAll() {
        for (CountDownLatch l : new CountDownLatch[]{acceptLatch, verifiedLatch, allAckedLatch}) {
            if (l != null) l.countDown();
        }
    }
}

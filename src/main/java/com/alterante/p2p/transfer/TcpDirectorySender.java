package com.alterante.p2p.transfer;

import com.alterante.p2p.net.TcpRelayProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Sends a folder (recursively) over a TLS-encrypted TCP relay connection as a
 * multi-file batch — the stream-based counterpart of {@link DirectorySender}.
 *
 * Wire flow: MSG_MANIFEST → MSG_DIR_ENTRY* → per-file (MSG_FILE_OFFER →
 * FILE_ACCEPT|FILE_REJECT → MSG_DATA… → MSG_COMPLETE → MSG_VERIFIED) → MSG_SESSION_COMPLETE.
 */
public class TcpDirectorySender {

    private static final Logger log = LoggerFactory.getLogger(TcpDirectorySender.class);

    private final InputStream in;
    private final OutputStream out;
    private final DirectorySender.Scan scan;
    private final BatchProgress batch;
    private int filesSent;
    private int filesSkipped;

    public TcpDirectorySender(Path root, InputStream in, OutputStream out) throws IOException {
        this(in, out, DirectorySender.scan(root));
    }

    public TcpDirectorySender(InputStream in, OutputStream out, DirectorySender.Scan scan) {
        this.in = in;
        this.out = out;
        this.scan = scan;
        this.batch = new BatchProgress(scan.files().size(), scan.totalBytes());
    }

    public DirectorySender.Scan scanResult() { return scan; }
    public BatchProgress batchProgress() { return batch; }
    public int filesSent() { return filesSent; }
    public int filesSkipped() { return filesSkipped; }

    public void send() throws IOException {
        for (String link : scan.skippedSymlinks()) {
            log.warn("Skipping symlink: {}", link);
        }
        BatchManifest manifest = new BatchManifest(
                scan.files().size(), scan.emptyDirs().size(), scan.totalBytes());
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_MANIFEST, manifest.encode());

        for (String dir : scan.emptyDirs()) {
            TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_DIR_ENTRY,
                    dir.getBytes(StandardCharsets.UTF_8));
        }

        for (DirectorySender.FileEntry entry : scan.files()) {
            sendOneFile(entry);
        }

        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_SESSION_COMPLETE);
        log.info("Folder transfer complete (relay): {} sent, {} skipped", filesSent, filesSkipped);
    }

    private void sendOneFile(DirectorySender.FileEntry entry) throws IOException {
        FileMetadata meta = FileMetadata.fromFile(entry.abs(), entry.relPath());
        TransferProgress progress = new TransferProgress(entry.size());
        batch.startFile(entry.relPath(), progress);

        log.info("Offering {} ({} bytes)", entry.relPath(), entry.size());
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_FILE_OFFER, meta.encode());

        TcpRelayProtocol.Message resp = TcpRelayProtocol.readMessage(in);
        if (resp.type() == TcpRelayProtocol.MSG_FILE_REJECT) {
            log.info("Receiver already has {} — skipping", entry.relPath());
            batch.fileSkipped(entry.size());
            filesSkipped++;
            return;
        }
        if (resp.type() != TcpRelayProtocol.MSG_FILE_ACCEPT) {
            throw new IOException("Expected FILE_ACCEPT for " + entry.relPath()
                    + ", got 0x" + String.format("%02X", resp.type()));
        }

        long offset = 0;
        if (resp.payload().length >= 24) {
            ByteBuffer buf = ByteBuffer.wrap(resp.payload()).order(ByteOrder.BIG_ENDIAN);
            buf.position(16);
            offset = buf.getLong();
        }

        pumpData(entry, offset, progress);

        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_COMPLETE, meta.sha256());
        TcpRelayProtocol.Message verified = TcpRelayProtocol.readMessage(in);
        if (verified.type() != TcpRelayProtocol.MSG_VERIFIED) {
            throw new IOException("Expected VERIFIED for " + entry.relPath()
                    + ", got 0x" + String.format("%02X", verified.type()));
        }
        batch.fileCompleted(entry.size());
        filesSent++;
    }

    private void pumpData(DirectorySender.FileEntry entry, long offset, TransferProgress progress)
            throws IOException {
        int chunkSize = TcpRelayProtocol.DATA_CHUNK_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(entry.abs().toFile(), "r")) {
            raf.seek(offset);
            long pos = offset;
            byte[] buf = new byte[chunkSize];
            while (pos < entry.size()) {
                int toRead = (int) Math.min(buf.length, entry.size() - pos);
                raf.readFully(buf, 0, toRead);
                byte[] data = (toRead == buf.length) ? buf : java.util.Arrays.copyOf(buf, toRead);
                TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_DATA, data);
                pos += toRead;
                progress.update(pos - offset);
            }
        }
    }
}

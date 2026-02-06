package com.alterante.p2p.transfer;

import com.alterante.p2p.protocol.Packet;
import com.alterante.p2p.protocol.PacketType;
import com.alterante.p2p.transport.ReliableChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates sending a file through a ReliableChannel.
 *
 * Flow: send FILE_OFFER → wait for FILE_ACCEPT → pump DATA → send COMPLETE → wait for VERIFIED.
 */
public class FileSender {

    private static final Logger log = LoggerFactory.getLogger(FileSender.class);
    private static final long CONTROL_TIMEOUT_MS = 30_000;

    private final Path file;
    private final FileMetadata metadata;
    private final ReliableChannel channel;
    private final TransferProgress progress;

    private volatile TransferState state = TransferState.OFFERING;
    private volatile boolean pumpComplete;
    private long resumeOffset;
    private final CountDownLatch acceptLatch = new CountDownLatch(1);
    private final CountDownLatch verifiedLatch = new CountDownLatch(1);
    private final CountDownLatch allAckedLatch = new CountDownLatch(1);

    public FileSender(Path file, FileMetadata metadata, ReliableChannel channel) {
        this.file = file;
        this.metadata = metadata;
        this.channel = channel;
        this.progress = new TransferProgress(metadata.fileSize());

        // Register callbacks
        channel.onControlPacket(this::handleControl);
        channel.onAllAcked(() -> {
            if (pumpComplete) allAckedLatch.countDown();
        });
    }

    /**
     * Run the full send flow. Blocks until complete, cancelled, or error.
     */
    public void send() throws IOException, InterruptedException {
        // 1. Send FILE_OFFER
        log.info("Offering file: {} ({} bytes, SHA-256: {})",
                metadata.filename(), metadata.fileSize(), metadata.sha256Hex());
        Packet offer = new Packet(PacketType.FILE_OFFER, metadata.encode());
        channel.sendControl(offer);

        // 2. Wait for FILE_ACCEPT
        if (!acceptLatch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            state = TransferState.ERROR;
            throw new IOException("Timed out waiting for FILE_ACCEPT");
        }
        if (state == TransferState.CANCELLED) {
            throw new IOException("Transfer rejected by receiver");
        }

        // 3. Pump data
        state = TransferState.TRANSFERRING;
        log.info("Transfer accepted. Sending from offset {}", resumeOffset);
        pumpData();
        pumpComplete = true;

        // 4. Wait for all ACKs (check if already all acked)
        if (channel.inflightCount() == 0) {
            allAckedLatch.countDown();
        }
        if (!allAckedLatch.await(60_000, TimeUnit.MILLISECONDS)) {
            log.warn("Timed out waiting for all ACKs, proceeding to COMPLETE");
        }

        // 5. Send COMPLETE
        state = TransferState.COMPLETING;
        Packet complete = new Packet(PacketType.COMPLETE, metadata.sha256());
        channel.sendControl(complete);
        log.info("All data sent. Waiting for receiver verification...");

        // 6. Wait for VERIFIED
        if (!verifiedLatch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            state = TransferState.ERROR;
            throw new IOException("Timed out waiting for VERIFIED");
        }

        state = TransferState.DONE;
        log.info("Transfer complete. File verified by receiver.");
    }

    private void pumpData() throws IOException, InterruptedException {
        int chunkSize = channel.maxChunkData();
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(resumeOffset);
            long offset = resumeOffset;
            int chunkIndex = (int) (resumeOffset / chunkSize);
            byte[] buf = new byte[chunkSize];

            while (offset < metadata.fileSize()) {
                int toRead = (int) Math.min(buf.length, metadata.fileSize() - offset);
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
                    // Skip transfer ID (16 bytes), read resume offset
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(payload)
                            .order(java.nio.ByteOrder.BIG_ENDIAN);
                    buf.position(16);
                    resumeOffset = buf.getLong();
                }
                log.info("Received FILE_ACCEPT (resume offset: {})", resumeOffset);
                acceptLatch.countDown();
            }
            case FILE_REJECT -> {
                log.warn("File transfer rejected by receiver");
                state = TransferState.CANCELLED;
                acceptLatch.countDown();
            }
            case VERIFIED -> {
                log.info("Received VERIFIED from receiver");
                verifiedLatch.countDown();
            }
            case CANCEL -> {
                log.warn("Transfer cancelled by receiver");
                state = TransferState.CANCELLED;
                acceptLatch.countDown();
                verifiedLatch.countDown();
            }
            default -> log.debug("Sender ignoring control packet: {}", pkt.type());
        }
    }

    public TransferState state() { return state; }
    public TransferProgress progress() { return progress; }
    public FileMetadata metadata() { return metadata; }
}

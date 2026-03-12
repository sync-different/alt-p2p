package com.alterante.p2p.transfer;

import com.alterante.p2p.net.TcpRelayProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;

/**
 * Streams a file over a TLS-encrypted TCP relay connection.
 *
 * Flow: send FILE_OFFER → wait FILE_ACCEPT → stream DATA → send COMPLETE → wait VERIFIED.
 * Uses TcpRelayProtocol message framing (type + length + payload).
 */
public class TcpFileSender {

    private static final Logger log = LoggerFactory.getLogger(TcpFileSender.class);
    private final Path file;
    private final FileMetadata metadata;
    private final InputStream in;
    private final OutputStream out;
    private final TransferProgress progress;

    private volatile TransferState state = TransferState.OFFERING;
    private long resumeOffset;

    public TcpFileSender(Path file, FileMetadata metadata, InputStream tlsIn, OutputStream tlsOut) {
        this.file = file;
        this.metadata = metadata;
        this.in = tlsIn;
        this.out = tlsOut;
        this.progress = new TransferProgress(metadata.fileSize());
    }

    /**
     * Run the full send flow. Blocks until complete or error.
     */
    public void send() throws IOException, InterruptedException {
        // 1. Send FILE_OFFER
        log.info("Offering file: {} ({} bytes, SHA-256: {})",
                metadata.filename(), metadata.fileSize(), metadata.sha256Hex());
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_FILE_OFFER, metadata.encode());

        // 2. Wait for FILE_ACCEPT
        TcpRelayProtocol.Message response = TcpRelayProtocol.readMessage(in);
        if (response.type() == TcpRelayProtocol.MSG_FILE_REJECT) {
            state = TransferState.CANCELLED;
            throw new IOException("Transfer rejected by receiver");
        }
        if (response.type() != TcpRelayProtocol.MSG_FILE_ACCEPT) {
            state = TransferState.ERROR;
            throw new IOException("Expected FILE_ACCEPT, got type 0x" + String.format("%02X", response.type()));
        }

        // Decode resume offset from FILE_ACCEPT
        if (response.payload().length >= 24) {
            ByteBuffer buf = ByteBuffer.wrap(response.payload()).order(ByteOrder.BIG_ENDIAN);
            buf.position(16); // skip transfer ID
            resumeOffset = buf.getLong();
        }
        log.info("Transfer accepted. Sending from offset {}", resumeOffset);

        // 3. Stream data
        state = TransferState.TRANSFERRING;
        pumpData();

        // 4. Send COMPLETE
        state = TransferState.COMPLETING;
        TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_COMPLETE, metadata.sha256());
        log.info("All data sent. Waiting for receiver verification...");

        // 5. Wait for VERIFIED
        TcpRelayProtocol.Message verified = TcpRelayProtocol.readMessage(in);
        if (verified.type() != TcpRelayProtocol.MSG_VERIFIED) {
            state = TransferState.ERROR;
            throw new IOException("Expected VERIFIED, got type 0x" + String.format("%02X", verified.type()));
        }

        state = TransferState.DONE;
        log.info("Transfer complete. File verified by receiver.");
    }

    private void pumpData() throws IOException {
        int chunkSize = TcpRelayProtocol.DATA_CHUNK_SIZE;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(resumeOffset);
            long offset = resumeOffset;
            byte[] buf = new byte[chunkSize];

            while (offset < metadata.fileSize()) {
                int toRead = (int) Math.min(buf.length, metadata.fileSize() - offset);
                raf.readFully(buf, 0, toRead);

                byte[] data = (toRead == buf.length) ? buf : java.util.Arrays.copyOf(buf, toRead);
                TcpRelayProtocol.writeMessage(out, TcpRelayProtocol.MSG_DATA, data);

                offset += toRead;
                progress.update(offset - resumeOffset);
            }
        }
    }

    public TransferState state() { return state; }
    public TransferProgress progress() { return progress; }
    public FileMetadata metadata() { return metadata; }
}

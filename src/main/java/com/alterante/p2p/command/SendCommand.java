package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.transfer.BatchProgress;
import com.alterante.p2p.transfer.DirectorySender;
import com.alterante.p2p.transfer.FileMetadata;
import com.alterante.p2p.transfer.FileSender;
import com.alterante.p2p.transfer.TcpFileSender;
import com.alterante.p2p.transfer.TransferProgress;
import com.alterante.p2p.transport.ReliableChannel;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "send",
        description = "Send a file to a peer",
        mixinStandardHelpOptions = true
)
public class SendCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--session", "-s"}, description = "Session ID", required = true)
    private String session;

    @CommandLine.Option(names = {"--psk"}, description = "Pre-shared key", required = true)
    private String psk;

    @CommandLine.Option(names = {"--server"}, description = "Coordination server (host:port)", required = true)
    private String server;

    @CommandLine.Option(names = {"--file", "-f"}, description = "File to send", required = true)
    private Path file;

    @CommandLine.Option(names = {"--json"}, description = "Output newline-delimited JSON events instead of human-readable text")
    private boolean json;

    @CommandLine.Mixin
    private TransferOptions tuning = new TransferOptions();

    @Override
    public Integer call() throws Exception {
        try {
            return doSend();
        } catch (Exception e) {
            if (json) {
                JsonOutput.error(e.getMessage());
                return 1;
            }
            throw e;
        }
    }

    private Integer doSend() throws Exception {
        // Validate path
        if (!Files.exists(file)) {
            String msg = "file not found: " + file;
            if (json) { JsonOutput.error(msg); return 1; }
            System.err.println("Error: " + msg);
            return 1;
        }
        if (Files.isDirectory(file)) {
            return doSendDirectory();
        }
        if (!Files.isRegularFile(file)) {
            String msg = "not a regular file: " + file;
            if (json) { JsonOutput.error(msg); return 1; }
            System.err.println("Error: " + msg);
            return 1;
        }

        long fileSize = Files.size(file);
        if (!json) {
            System.out.println("File: " + file.getFileName() + " (" + formatSize(fileSize) + ")");
            System.out.println("Computing SHA-256...");
        }
        FileMetadata metadata = FileMetadata.fromFile(file);
        if (json) {
            JsonOutput.fileInfo(metadata);
        } else {
            System.out.println("SHA-256: " + metadata.sha256Hex());
        }

        InetSocketAddress serverAddr = parseAddress(server);
        PeerConnection conn = new PeerConnection(serverAddr, session, psk);
        conn.applyOptions(tuning);

        if (json) {
            conn.setStateListener(JsonOutput::status);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!json) System.out.println("\nShutting down...");
            conn.close();
        }));

        if (!json) System.out.println("Connecting to session '" + session + "' via " + serverAddr + "...");
        conn.connect();
        if (!json) {
            System.out.println("Connected! Encrypted P2P link established.");
            System.out.println("  Remote endpoint: " + conn.remoteEndpoint());
        }

        if (conn.isTcpRelay()) {
            // TCP relay path — stream file over TLS, no ReliableChannel
            try {
                TcpFileSender sender = new TcpFileSender(file, metadata,
                        conn.tcpRelayInputStream(), conn.tcpRelayOutputStream());

                Thread progressThread = new Thread(() -> {
                    if (json) {
                        printJsonProgress(sender.progress());
                    } else {
                        printProgress(sender.progress());
                    }
                }, "progress");
                progressThread.setDaemon(true);
                progressThread.start();

                sender.send();

                long durationMs = System.currentTimeMillis() - sender.progress().startTimeMs();
                if (json) {
                    JsonOutput.complete(sender.progress().totalBytes(), 0, 0, durationMs);
                } else {
                    System.out.print("\r" + sender.progress().progressBar(30));
                    System.out.println();
                    System.out.println("Transfer complete! (TCP relay)");
                    System.out.printf("  %s sent%n", formatSize(sender.progress().totalBytes()));
                }
            } finally {
                conn.close();
            }
        } else {
            // UDP path — ReliableChannel + FileSender
            int dtlsSendLimit = conn.dtls().transport().getSendLimit();
            ReliableChannel channel = new ReliableChannel(conn.router(), 0xA, dtlsSendLimit, conn.initialCwnd());
            if (conn.allowRelay()) channel.setRelayMode(true);
            conn.startRouter();

            try {
                FileSender sender = new FileSender(file, metadata, channel);

                Thread progressThread = new Thread(() -> {
                    if (json) {
                        printJsonProgress(sender.progress());
                    } else {
                        printProgress(sender.progress());
                    }
                }, "progress");
                progressThread.setDaemon(true);
                progressThread.start();

                sender.send();

                long durationMs = System.currentTimeMillis() - sender.progress().startTimeMs();
                if (json) {
                    JsonOutput.complete(
                            sender.progress().totalBytes(),
                            channel.totalPacketsSent(),
                            channel.totalRetransmissions(),
                            durationMs);
                } else {
                    printFinalProgress(sender.progress(), channel);
                }

            } finally {
                channel.close();
                conn.close();
            }
        }

        return 0;
    }

    private Integer doSendDirectory() throws Exception {
        DirectorySender.Scan scan = DirectorySender.scan(file);
        if (scan.files().isEmpty() && scan.emptyDirs().isEmpty()) {
            String msg = "folder is empty: " + file;
            if (json) { JsonOutput.error(msg); return 1; }
            System.err.println("Error: " + msg);
            return 1;
        }
        if (!json) {
            System.out.printf("Folder: %s — %d files, %d empty dirs, %s%n",
                    file, scan.files().size(), scan.emptyDirs().size(), formatSize(scan.totalBytes()));
            for (String link : scan.skippedSymlinks()) {
                System.out.println("  (skipping symlink: " + link + ")");
            }
        }

        if (json) JsonOutput.manifest(scan.files().size(), scan.totalBytes());

        InetSocketAddress serverAddr = parseAddress(server);
        int maxAttempts = tuning.reconnectAttempts != null ? tuning.reconnectAttempts : 5;
        long deadline = System.currentTimeMillis()
                + (tuning.batchDeadlineSec != null ? tuning.batchDeadlineSec : 600) * 1000L;
        java.util.concurrent.atomic.AtomicReference<PeerConnection> connRef = new java.util.concurrent.atomic.AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            PeerConnection c = connRef.get();
            if (c != null) c.close();
        }));

        int localPort = 0;
        boolean connectedOnce = false;
        for (int attempt = 1; ; attempt++) {
            PeerConnection conn = new PeerConnection(serverAddr, session, psk);
            conn.applyOptions(tuning);
            conn.setLocalPort(localPort);
            if (json) conn.setStateListener(JsonOutput::status);
            connRef.set(conn);

            ReliableChannel channel = null;
            Thread watcher = null;
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            try {
                if (!json) System.out.println(attempt == 1
                        ? "Connecting to session '" + session + "' via " + serverAddr + "..."
                        : "Reconnecting (attempt " + attempt + "/" + maxAttempts + ")...");
                conn.connect();
                connectedOnce = true;
                localPort = conn.localPort();
                if (!json) System.out.println("Connected! Encrypted P2P link established.");

                if (conn.isTcpRelay()) {
                    return doRelayDirectorySend(conn, scan); // stream-based, one-shot
                }

                int dtlsSendLimit = conn.dtls().transport().getSendLimit();
                channel = new ReliableChannel(conn.router(), 0xA, dtlsSendLimit, conn.initialCwnd());
                if (conn.allowRelay()) channel.setRelayMode(true);
                DirectorySender sender = new DirectorySender(file, channel, scan);
                conn.startRouter();
                // Start the death watcher AFTER the router thread exists, else awaitStop()
                // returns immediately and the watcher false-fires a "connection lost".
                watcher = BatchRunner.startDeathWatcher(conn, channel, Thread.currentThread(), done);

                Thread progressThread = new Thread(() -> printBatchProgress(sender.batchProgress(), done), "progress");
                progressThread.setDaemon(true);
                progressThread.start();

                sender.send();
                done.set(true);

                if (!json) {
                    System.out.print("\r" + sender.batchProgress().progressLine(30));
                    System.out.println();
                    System.out.printf("Folder transfer complete! %d files sent, %d skipped%n",
                            sender.filesSent(), sender.filesSkipped());
                } else {
                    long durationMs = System.currentTimeMillis() - sender.batchProgress().startTimeMs();
                    JsonOutput.complete(scan.totalBytes(), channel.totalPacketsSent(),
                            channel.totalRetransmissions(), durationMs);
                }
                return 0;

            } catch (Exception e) {
                done.set(true);
                if (conn.localPort() != 0) localPort = conn.localPort(); // reuse port so reconnect re-pairs
                if (channel != null) channel.close();
                conn.close();
                Thread.interrupted(); // clear any interrupt from the death watcher

                // Initial connection failed (e.g. hole punch) — don't burn the reconnect
                // budget retrying a deterministic NAT failure; fail fast with a relay hint.
                if (!connectedOnce) {
                    return initialConnectFailure(e);
                }

                long now = System.currentTimeMillis();
                if (attempt >= maxAttempts || now >= deadline) {
                    String msg = "transfer incomplete after " + attempt + " attempt(s): " + describe(e)
                            + " — re-run the same command to resume (completed files are skipped)";
                    if (json) JsonOutput.error(msg); else System.err.println("\nError: " + msg);
                    return 1;
                }
                long backoff = BatchRunner.backoffMs(attempt);
                if (!json) System.err.printf("%nConnection lost: %s — retrying in %.0fs%n",
                        describe(e), backoff / 1000.0);
                Thread.sleep(backoff);
            } finally {
                if (watcher != null) watcher.interrupt();
            }
        }
    }

    private Integer initialConnectFailure(Exception e) {
        String d = describe(e);
        boolean holePunch = d.toLowerCase().contains("hole punch");
        String hint = (holePunch && !tuning.allowRelay && !tuning.forceRelay)
                ? " — peers may be behind the same or a symmetric NAT; retry with --force-relay"
                        + " (or --allow-relay) to use the TCP relay"
                : "";
        String msg = "connection failed: " + d + hint;
        if (json) JsonOutput.error(msg); else System.err.println("\nError: " + msg);
        return 1;
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        return (m != null && !m.isBlank()) ? m : e.getClass().getSimpleName();
    }

    /** Folder send over TCP relay (stream-based, one-shot — no reconnect loop). */
    private Integer doRelayDirectorySend(PeerConnection conn, DirectorySender.Scan scan) throws Exception {
        try {
            com.alterante.p2p.transfer.TcpDirectorySender sender =
                    new com.alterante.p2p.transfer.TcpDirectorySender(
                            conn.tcpRelayInputStream(), conn.tcpRelayOutputStream(), scan);
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread progressThread = new Thread(() -> printBatchProgress(sender.batchProgress(), done), "progress");
            progressThread.setDaemon(true);
            progressThread.start();

            sender.send();
            done.set(true);

            if (!json) {
                System.out.print("\r" + sender.batchProgress().progressLine(30));
                System.out.println();
                System.out.printf("Folder transfer complete! (TCP relay) %d files sent, %d skipped%n",
                        sender.filesSent(), sender.filesSkipped());
            } else {
                long durationMs = System.currentTimeMillis() - sender.batchProgress().startTimeMs();
                JsonOutput.complete(scan.totalBytes(), 0, 0, durationMs);
            }
            return 0;
        } finally {
            conn.close();
        }
    }

    private void printBatchProgress(BatchProgress batch, java.util.concurrent.atomic.AtomicBoolean stop) {
        try {
            while (!stop.get() && !batch.isComplete()) {
                if (!json) {
                    System.out.print("\r" + batch.progressLine(30));
                    System.out.flush();
                } else {
                    JsonOutput.batchProgress(batch);
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printProgress(TransferProgress progress) {
        try {
            while (!progress.isComplete()) {
                System.out.print("\r" + progress.progressBar(30));
                System.out.flush();
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printJsonProgress(TransferProgress progress) {
        try {
            while (!progress.isComplete()) {
                JsonOutput.progress(progress);
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void printFinalProgress(TransferProgress progress, ReliableChannel channel) {
        System.out.print("\r" + progress.progressBar(30));
        System.out.println();
        System.out.println("Transfer complete!");
        System.out.printf("  %s sent, %d packets, %d retransmissions%n",
                formatSize(progress.totalBytes()),
                channel.totalPacketsSent(),
                channel.totalRetransmissions());
    }

    static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) return String.format("%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000) return String.format("%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000) return String.format("%.1f KB", bytes / 1_000.0);
        return bytes + " B";
    }

    private InetSocketAddress parseAddress(String addr) {
        String[] parts = addr.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Server address must be host:port, got: " + addr);
        }
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}

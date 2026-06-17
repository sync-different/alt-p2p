package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.transfer.BatchProgress;
import com.alterante.p2p.transfer.ConflictPolicy;
import com.alterante.p2p.transfer.DirectoryReceiver;
import com.alterante.p2p.transport.ReliableChannel;
import picocli.CommandLine;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import static com.alterante.p2p.command.SendCommand.formatSize;

@CommandLine.Command(
        name = "receive",
        description = "Receive a file from a peer",
        mixinStandardHelpOptions = true
)
public class ReceiveCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--session", "-s"}, description = "Session ID", required = true)
    private String session;

    @CommandLine.Option(names = {"--psk"}, description = "Pre-shared key", required = true)
    private String psk;

    @CommandLine.Option(names = {"--server"}, description = "Coordination server (host:port)", required = true)
    private String server;

    @CommandLine.Option(names = {"--output", "-o"}, description = "Output directory", required = true)
    private Path outputDir;

    @CommandLine.Option(names = {"--json"}, description = "Output newline-delimited JSON events instead of human-readable text")
    private boolean json;

    @CommandLine.Option(names = {"--on-conflict"},
            description = "On a differing existing file: overwrite | skip | keep-both | ask "
                    + "(default: ask on a terminal, skip with --json)")
    private String onConflict;

    @CommandLine.Mixin
    private TransferOptions tuning = new TransferOptions();

    @Override
    public Integer call() throws Exception {
        try {
            return doReceive();
        } catch (Exception e) {
            if (json) {
                JsonOutput.error(e.getMessage());
                return 1;
            }
            throw e;
        }
    }

    private Integer doReceive() throws Exception {
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
                        ? "Waiting for peer on session '" + session + "' via " + serverAddr + "..."
                        : "Reconnecting (attempt " + attempt + "/" + maxAttempts + ")...");
                conn.connect();
                connectedOnce = true;
                localPort = conn.localPort();
                if (!json) {
                    System.out.println("Connected! Encrypted P2P link established.");
                    System.out.println("  Remote endpoint: " + conn.remoteEndpoint());
                }

                if (conn.isTcpRelay()) {
                    return doRelayReceive(conn); // single-file relay (one-shot)
                }

                // UDP path — ReliableChannel + DirectoryReceiver (single-file and folder)
                int dtlsSendLimit = conn.dtls().transport().getSendLimit();
                channel = new ReliableChannel(conn.router(), 0xB, dtlsSendLimit, conn.initialCwnd());
                if (conn.allowRelay()) channel.setRelayMode(true);
                DirectoryReceiver receiver = new DirectoryReceiver(outputDir, channel, buildConflictPolicy());
                conn.startRouter();
                // Start the death watcher AFTER the router thread exists, else awaitStop()
                // returns immediately and the watcher false-fires a "connection lost".
                watcher = BatchRunner.startDeathWatcher(conn, channel, Thread.currentThread(), done);

                if (!json) System.out.println("Waiting for transfer...");
                Thread progressThread = new Thread(() -> printBatchProgress(receiver, done), "progress");
                progressThread.setDaemon(true);
                progressThread.start();

                receiver.receive();
                done.set(true);

                BatchProgress bp = receiver.batchProgress();
                long durationMs = bp != null ? System.currentTimeMillis() - bp.startTimeMs() : 0;
                if (json) {
                    String path = receiver.wasBatch()
                            ? outputDir.toString()
                            : (receiver.lastFile() != null ? receiver.lastFile().toString() : outputDir.toString());
                    JsonOutput.complete(receiver.bytesReceived(), channel.totalPacketsReceived(), 0,
                            durationMs, path);
                } else {
                    if (bp != null) { System.out.print("\r" + bp.progressLine(30)); System.out.println(); }
                    if (receiver.wasBatch()) {
                        System.out.printf("Folder transfer complete! %d files received, %d skipped into %s%n",
                                receiver.filesReceived(), receiver.filesSkipped(), outputDir);
                    } else {
                        System.out.println("Transfer complete! File saved to: " + receiver.lastFile());
                        System.out.printf("  %s received, %d packets%n",
                                formatSize(receiver.bytesReceived()), channel.totalPacketsReceived());
                    }
                }
                return 0;

            } catch (Exception e) {
                done.set(true);
                if (conn.localPort() != 0) localPort = conn.localPort(); // reuse port so reconnect re-pairs
                if (channel != null) channel.close();
                conn.close();
                Thread.interrupted(); // clear any interrupt from the death watcher

                // Initial connection failed (e.g. hole punch) — fail fast with a relay hint
                // instead of burning the reconnect budget on a deterministic NAT failure.
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

    /** TCP-relay receive (stream-based, one-shot) — handles single-file and folder. */
    private Integer doRelayReceive(PeerConnection conn) throws Exception {
        try {
            com.alterante.p2p.transfer.TcpDirectoryReceiver receiver =
                    new com.alterante.p2p.transfer.TcpDirectoryReceiver(outputDir,
                            conn.tcpRelayInputStream(), conn.tcpRelayOutputStream(), buildConflictPolicy());
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);

            if (!json) System.out.println("Waiting for transfer...");
            Thread progressThread = new Thread(() -> {
                try {
                    while (receiver.batchProgress() == null && !done.get()) Thread.sleep(100);
                    BatchProgress bp = receiver.batchProgress();
                    if (bp == null) return;
                    boolean manifestEmitted = false;
                    while (!done.get() && !bp.isComplete()) {
                        if (receiver.isAwaitingUser()) { Thread.sleep(200); continue; }
                        if (!json) { System.out.print("\r" + bp.progressLine(30)); System.out.flush(); }
                        else if (receiver.wasBatch()) {
                            if (!manifestEmitted) { JsonOutput.manifest(bp.totalFiles(), bp.totalBytes()); manifestEmitted = true; }
                            JsonOutput.batchProgress(bp);
                        } else if (bp.current() != null) JsonOutput.progress(bp.current());
                        Thread.sleep(250);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "progress");
            progressThread.setDaemon(true);
            progressThread.start();

            receiver.receive();
            done.set(true);

            BatchProgress bp = receiver.batchProgress();
            long durationMs = bp != null ? System.currentTimeMillis() - bp.startTimeMs() : 0;
            if (json) {
                String path = receiver.wasBatch()
                        ? outputDir.toString()
                        : (receiver.lastFile() != null ? receiver.lastFile().toString() : outputDir.toString());
                JsonOutput.complete(receiver.bytesReceived(), 0, 0, durationMs, path);
            } else {
                if (bp != null) { System.out.print("\r" + bp.progressLine(30)); System.out.println(); }
                if (receiver.wasBatch()) {
                    System.out.printf("Folder transfer complete! (TCP relay) %d files received, %d skipped into %s%n",
                            receiver.filesReceived(), receiver.filesSkipped(), outputDir);
                } else {
                    System.out.println("Transfer complete! (TCP relay) File saved to: " + receiver.lastFile());
                    System.out.printf("  %s received%n", formatSize(receiver.bytesReceived()));
                }
            }
            return 0;
        } finally {
            conn.close();
        }
    }

    private ConflictPolicy buildConflictPolicy() {
        ConflictPolicy.Mode mode;
        if (onConflict != null) {
            mode = ConflictPolicy.parseMode(onConflict);
        } else {
            mode = json ? ConflictPolicy.Mode.SKIP : ConflictPolicy.Mode.ASK;
        }
        boolean interactive = !json && System.console() != null;
        return new ConflictPolicy(mode, interactive);
    }

    private void printBatchProgress(DirectoryReceiver receiver, java.util.concurrent.atomic.AtomicBoolean stop) {
        try {
            while (receiver.batchProgress() == null && !receiver.isDone() && !stop.get()) {
                Thread.sleep(100);
            }
            BatchProgress bp = receiver.batchProgress();
            if (bp == null) return;
            boolean manifestEmitted = false;
            while (!receiver.isDone() && !stop.get()) {
                if (receiver.isAwaitingUser()) {
                    Thread.sleep(200); // a conflict prompt is showing — don't overwrite it
                    continue;
                }
                if (!json) {
                    System.out.print("\r" + bp.progressLine(30));
                    System.out.flush();
                } else if (receiver.wasBatch()) {
                    if (!manifestEmitted) { JsonOutput.manifest(bp.totalFiles(), bp.totalBytes()); manifestEmitted = true; }
                    JsonOutput.batchProgress(bp);
                } else if (bp.current() != null) {
                    JsonOutput.progress(bp.current());
                }
                Thread.sleep(250);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private InetSocketAddress parseAddress(String addr) {
        String[] parts = addr.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Server address must be host:port, got: " + addr);
        }
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}

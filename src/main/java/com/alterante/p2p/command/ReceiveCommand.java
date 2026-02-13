package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.transfer.FileReceiver;
import com.alterante.p2p.transfer.TransferProgress;
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
        PeerConnection conn = new PeerConnection(serverAddr, session, psk);

        if (json) {
            conn.setStateListener(JsonOutput::status);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!json) System.out.println("\nShutting down...");
            conn.close();
        }));

        if (!json) System.out.println("Waiting for peer on session '" + session + "' via " + serverAddr + "...");
        conn.connect();
        if (!json) {
            System.out.println("Connected! Encrypted P2P link established.");
            System.out.println("  Remote endpoint: " + conn.remoteEndpoint());
        }

        // Create reliable channel
        int dtlsSendLimit = conn.dtls().transport().getSendLimit();
        ReliableChannel channel = new ReliableChannel(conn.router(), 0xB, dtlsSendLimit);

        try {
            FileReceiver receiver = new FileReceiver(outputDir, channel);

            if (!json) System.out.println("Waiting for file offer...");

            // Start receive (blocks until FILE_OFFER arrives, then starts data transfer)
            // We run progress display in a separate thread once we know the file info
            Thread progressThread = new Thread(() -> {
                // Wait until metadata is available (offer received)
                while (receiver.metadata() == null) {
                    try { Thread.sleep(100); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (json) {
                    JsonOutput.fileInfo(receiver.metadata());
                } else {
                    System.out.println("Receiving: " + receiver.metadata().filename()
                            + " (" + formatSize(receiver.metadata().fileSize()) + ")");
                    System.out.println("SHA-256: " + receiver.metadata().sha256Hex());
                }

                // Wait until progress is available (file accepted)
                while (receiver.progress() == null) {
                    try { Thread.sleep(100); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (json) {
                    printJsonProgress(receiver.progress());
                } else {
                    printProgress(receiver.progress());
                }
            }, "progress");
            progressThread.setDaemon(true);
            progressThread.start();

            receiver.receive();

            // Final output
            long durationMs = receiver.progress() != null
                    ? System.currentTimeMillis() - receiver.progress().startTimeMs()
                    : 0;
            if (json) {
                JsonOutput.complete(
                        receiver.metadata().fileSize(),
                        channel.totalPacketsReceived(),
                        0,
                        durationMs,
                        receiver.outputFile().toString());
            } else {
                System.out.print("\r" + (receiver.progress() != null ? receiver.progress().progressBar(30) : ""));
                System.out.println();
                System.out.println("Transfer complete! File saved to: " + receiver.outputFile());
                System.out.printf("  %s received, %d packets%n",
                        formatSize(receiver.metadata().fileSize()),
                        channel.totalPacketsReceived());
            }

        } finally {
            channel.close();
            conn.close();
        }

        return 0;
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

    private InetSocketAddress parseAddress(String addr) {
        String[] parts = addr.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Server address must be host:port, got: " + addr);
        }
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}

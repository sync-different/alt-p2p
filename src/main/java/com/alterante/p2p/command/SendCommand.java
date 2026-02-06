package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerConnection;
import com.alterante.p2p.transfer.FileMetadata;
import com.alterante.p2p.transfer.FileSender;
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

    @Override
    public Integer call() throws Exception {
        // Validate file
        if (!Files.exists(file)) {
            System.err.println("Error: file not found: " + file);
            return 1;
        }
        if (!Files.isRegularFile(file)) {
            System.err.println("Error: not a regular file: " + file);
            return 1;
        }

        long fileSize = Files.size(file);
        System.out.println("File: " + file.getFileName() + " (" + formatSize(fileSize) + ")");
        System.out.println("Computing SHA-256...");
        FileMetadata metadata = FileMetadata.fromFile(file);
        System.out.println("SHA-256: " + metadata.sha256Hex());

        InetSocketAddress serverAddr = parseAddress(server);
        PeerConnection conn = new PeerConnection(serverAddr, session, psk);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down...");
            conn.close();
        }));

        System.out.println("Connecting to session '" + session + "' via " + serverAddr + "...");
        conn.connect();
        System.out.println("Connected! Encrypted P2P link established.");
        System.out.println("  Remote endpoint: " + conn.remoteEndpoint());

        // Create reliable channel
        int dtlsSendLimit = conn.dtls().transport().getSendLimit();
        ReliableChannel channel = new ReliableChannel(conn.router(), 0xA, dtlsSendLimit);

        try {
            FileSender sender = new FileSender(file, metadata, channel);

            // Progress display thread
            Thread progressThread = new Thread(() -> printProgress(sender.progress()), "progress");
            progressThread.setDaemon(true);
            progressThread.start();

            sender.send();

            // Final progress line
            printFinalProgress(sender.progress(), channel);

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

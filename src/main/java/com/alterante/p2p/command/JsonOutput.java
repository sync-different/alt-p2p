package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerState;
import com.alterante.p2p.transfer.FileMetadata;
import com.alterante.p2p.transfer.TransferProgress;

/**
 * Emits newline-delimited JSON events to stdout for machine-readable output.
 * Used by SendCommand and ReceiveCommand when --json flag is set.
 */
final class JsonOutput {

    private JsonOutput() {}

    static void status(PeerState state) {
        String name = switch (state) {
            case REGISTERING -> "registering";
            case WAITING_PEER -> "waiting_peer";
            case PUNCHING -> "punching";
            case HANDSHAKE -> "handshaking";
            case CONNECTED -> "connected";
            default -> state.name().toLowerCase();
        };
        emit("{\"event\":\"status\",\"state\":\"%s\"}", name);
    }

    static void fileInfo(FileMetadata metadata) {
        emit("{\"event\":\"file_info\",\"name\":\"%s\",\"size\":%d,\"sha256\":\"%s\"}",
                escapeJson(metadata.filename()),
                metadata.fileSize(),
                metadata.sha256Hex());
    }

    static void progress(TransferProgress p) {
        emit("{\"event\":\"progress\",\"bytes\":%d,\"total\":%d,\"speed_bps\":%.0f,\"eta_seconds\":%d,\"percent\":%.1f}",
                p.transferredBytes(),
                p.totalBytes(),
                p.speed(),
                p.etaSeconds(),
                p.percentComplete());
    }

    static void complete(long bytes, long packets, long retransmissions, long durationMs) {
        emit("{\"event\":\"complete\",\"bytes\":%d,\"packets\":%d,\"retransmissions\":%d,\"duration_ms\":%d}",
                bytes, packets, retransmissions, durationMs);
    }

    static void complete(long bytes, long packets, long retransmissions, long durationMs, String path) {
        emit("{\"event\":\"complete\",\"bytes\":%d,\"packets\":%d,\"retransmissions\":%d,\"duration_ms\":%d,\"path\":\"%s\"}",
                bytes, packets, retransmissions, durationMs, escapeJson(path));
    }

    static void error(String message) {
        emit("{\"event\":\"error\",\"message\":\"%s\"}", escapeJson(message));
    }

    private static void emit(String format, Object... args) {
        System.out.println(String.format(format, args));
        System.out.flush();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}

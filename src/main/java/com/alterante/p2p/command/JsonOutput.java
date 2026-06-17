package com.alterante.p2p.command;

import com.alterante.p2p.net.PeerState;
import com.alterante.p2p.transfer.BatchProgress;
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
            case RELAYING -> "relaying";
            case RELAY_TCP -> "relay_tcp";
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

    /** Emitted once at the start of a folder transfer so consumers know the totals. */
    static void manifest(int filesTotal, long bytesTotal) {
        emit("{\"event\":\"manifest\",\"files_total\":%d,\"bytes_total\":%d}", filesTotal, bytesTotal);
    }

    /** Per-tick folder progress: overall (bytes/total/percent) plus the current file. */
    static void batchProgress(BatchProgress bp) {
        TransferProgress cur = bp.current();
        long fileBytes = cur != null ? cur.transferredBytes() : 0;
        long fileTotal = cur != null ? cur.totalBytes() : 0;
        double filePercent = cur != null ? cur.percentComplete() : 0.0;
        emit("{\"event\":\"progress\",\"scope\":\"batch\",\"file\":\"%s\","
                        + "\"file_bytes\":%d,\"file_total\":%d,\"file_percent\":%.1f,"
                        + "\"files_done\":%d,\"files_total\":%d,"
                        + "\"bytes\":%d,\"total\":%d,\"percent\":%.1f,\"speed_bps\":%.0f,\"eta_seconds\":%d}",
                escapeJson(bp.currentName()), fileBytes, fileTotal, filePercent,
                bp.filesDone(), bp.totalFiles(),
                bp.processedBytes(), bp.totalBytes(), bp.overallPercent(), bp.speed(), bp.etaSeconds());
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

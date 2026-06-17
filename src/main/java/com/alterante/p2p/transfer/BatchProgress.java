package com.alterante.p2p.transfer;

/**
 * Aggregate progress across a multi-file (folder) transfer: per-file progress
 * plus an overall view (files done / total, bytes done / total, overall speed
 * and ETA). Thread-safe via volatiles — updated on the transfer thread, read by
 * the progress-printer thread.
 */
public class BatchProgress {

    private final int totalFiles;
    private final long totalBytes;
    private final long startTimeMs;

    private volatile int filesDone;
    /** Bytes accounted for by fully completed or skipped files. */
    private volatile long completedBytes;
    private volatile String currentName = "";
    private volatile TransferProgress current;

    public BatchProgress(int totalFiles, long totalBytes) {
        this.totalFiles = totalFiles;
        this.totalBytes = totalBytes;
        this.startTimeMs = System.currentTimeMillis();
    }

    /** Begin tracking a new file. */
    public void startFile(String name, TransferProgress progress) {
        this.currentName = name;
        this.current = progress;
    }

    /** A file finished transferring (its bytes were sent/received). */
    public void fileCompleted(long fileSize) {
        filesDone++;
        completedBytes += fileSize;
        current = null;
    }

    /** A file was skipped (already present / conflict skip). Counts toward done. */
    public void fileSkipped(long fileSize) {
        filesDone++;
        completedBytes += fileSize;
        current = null;
    }

    public int totalFiles() { return totalFiles; }
    public int filesDone() { return filesDone; }
    public int filesRemaining() { return Math.max(0, totalFiles - filesDone); }
    public long totalBytes() { return totalBytes; }
    public long startTimeMs() { return startTimeMs; }
    public String currentName() { return currentName; }
    public TransferProgress current() { return current; }

    /** Bytes processed so far (completed files + current file in flight). */
    public long processedBytes() {
        TransferProgress c = current;
        long inFlight = (c != null) ? c.transferredBytes() : 0;
        return completedBytes + inFlight;
    }

    public long bytesRemaining() {
        return Math.max(0, totalBytes - processedBytes());
    }

    public double overallPercent() {
        if (totalBytes == 0) return filesDone >= totalFiles ? 100.0 : 0.0;
        return Math.min(100.0, (processedBytes() * 100.0) / totalBytes);
    }

    /** Overall bytes/second since batch start. */
    public double speed() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed <= 0) return 0;
        return (processedBytes() * 1000.0) / elapsed;
    }

    public long etaSeconds() {
        double bps = speed();
        if (bps <= 0) return -1;
        return (long) (bytesRemaining() / bps);
    }

    public boolean isComplete() {
        return filesDone >= totalFiles;
    }

    /** Single-line overall + current-file progress for terminal display. */
    public String progressLine(int width) {
        double pct = overallPercent();
        int filled = (int) (width * pct / 100);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < filled) bar.append('=');
            else if (i == filled) bar.append('>');
            else bar.append(' ');
        }
        bar.append(']');

        TransferProgress c = current;
        String cur = (c != null && !currentName.isEmpty())
                ? String.format(" | %s %3.0f%%", trim(currentName), c.percentComplete())
                : "";
        // Trailing ANSI "erase to end of line" so a shorter line fully overwrites a
        // longer previous one (e.g. when the current filename gets shorter).
        return String.format("%s %3.0f%% | %d/%d files%s | %s | %s left ETA %s\u001b[K",
                bar, pct, filesDone, totalFiles, cur,
                speedString(), formatSize(bytesRemaining()), etaString());
    }

    private static String trim(String name) {
        return name.length() <= 40 ? name : "…" + name.substring(name.length() - 39);
    }

    private String speedString() {
        double bps = speed();
        if (bps >= 1_000_000) return String.format("%.1f MB/s", bps / 1_000_000);
        if (bps >= 1_000) return String.format("%.1f KB/s", bps / 1_000);
        return String.format("%.0f B/s", bps);
    }

    private String etaString() {
        long secs = etaSeconds();
        if (secs < 0) return "?";
        if (secs < 60) return secs + "s";
        if (secs < 3600) return String.format("%d:%02d", secs / 60, secs % 60);
        return String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    static String formatSize(long bytes) {
        if (bytes >= 1_000_000_000) return String.format("%.1f GB", bytes / 1_000_000_000.0);
        if (bytes >= 1_000_000) return String.format("%.1f MB", bytes / 1_000_000.0);
        if (bytes >= 1_000) return String.format("%.1f KB", bytes / 1_000.0);
        return bytes + " B";
    }
}

package com.alterante.p2p.transfer;

/**
 * Tracks file transfer progress, speed, and ETA.
 */
public class TransferProgress {

    private final long totalBytes;
    private volatile long transferredBytes;
    private final long startTimeMs;

    public TransferProgress(long totalBytes) {
        this.totalBytes = totalBytes;
        this.startTimeMs = System.currentTimeMillis();
    }

    public void update(long bytesTransferred) {
        this.transferredBytes = bytesTransferred;
    }

    public void addBytes(long bytes) {
        this.transferredBytes += bytes;
    }

    public long transferredBytes() { return transferredBytes; }
    public long totalBytes() { return totalBytes; }
    public long startTimeMs() { return startTimeMs; }

    public double percentComplete() {
        if (totalBytes == 0) return 100.0;
        return (transferredBytes * 100.0) / totalBytes;
    }

    /** Bytes per second. */
    public double speed() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        if (elapsed <= 0) return 0;
        return (transferredBytes * 1000.0) / elapsed;
    }

    /** Human-readable speed string. */
    public String speedString() {
        double bps = speed();
        if (bps >= 1_000_000) return String.format("%.1f MB/s", bps / 1_000_000);
        if (bps >= 1_000) return String.format("%.1f KB/s", bps / 1_000);
        return String.format("%.0f B/s", bps);
    }

    /** Estimated seconds remaining, or -1 if unknown. */
    public long etaSeconds() {
        double bps = speed();
        if (bps <= 0) return -1;
        long remaining = totalBytes - transferredBytes;
        return (long) (remaining / bps);
    }

    /** Human-readable ETA. */
    public String etaString() {
        long secs = etaSeconds();
        if (secs < 0) return "?";
        if (secs < 60) return secs + "s";
        if (secs < 3600) return String.format("%d:%02d", secs / 60, secs % 60);
        return String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    /** Progress bar: [=========>       ] 56% 2.3 MB/s ETA 0:45 */
    public String progressBar(int width) {
        double pct = percentComplete();
        int filled = (int) (width * pct / 100);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            if (i < filled) bar.append('=');
            else if (i == filled) bar.append('>');
            else bar.append(' ');
        }
        bar.append(String.format("] %3.0f%% %s ETA %s", pct, speedString(), etaString()));
        return bar.toString();
    }

    public boolean isComplete() {
        return transferredBytes >= totalBytes;
    }
}

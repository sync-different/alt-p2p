package com.alterante.p2p.transfer;

import java.nio.file.Path;

/**
 * Validates relative paths received from a peer before resolving them under a
 * local output directory. Prevents path-traversal writes outside the output dir
 * (e.g. {@code ../../etc/passwd} or absolute paths) in multi-file transfers.
 *
 * <p>Incoming relative paths are POSIX-style (forward slashes), as produced by
 * {@link FileMetadata#fromFile(Path, String)}. Backslashes are treated as
 * separators too, so a Windows-style {@code ..\..} can't slip through.
 */
public final class PathSafety {

    private PathSafety() {}

    /**
     * Resolve a peer-supplied relative path under {@code baseDir}, rejecting
     * anything that is absolute, contains a {@code ..} segment, or escapes
     * {@code baseDir} after normalization.
     *
     * @return the safe, normalized target path inside {@code baseDir}
     * @throws IllegalArgumentException if the relative path is unsafe
     */
    public static Path resolveChild(Path baseDir, String relPath) {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("empty relative path");
        }

        // Inspect with backslashes treated as separators.
        String normalized = relPath.replace('\\', '/');

        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("absolute path not allowed: " + relPath);
        }
        // Windows drive letter, e.g. "C:..."
        if (normalized.length() >= 2 && normalized.charAt(1) == ':') {
            throw new IllegalArgumentException("drive-qualified path not allowed: " + relPath);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals("..")) {
                throw new IllegalArgumentException("parent-directory segment not allowed: " + relPath);
            }
        }

        Path base = baseDir.toAbsolutePath().normalize();
        Path resolved = base.resolve(normalized).normalize();

        // Defense in depth: even if the segment scan missed something, the
        // resolved path must stay inside baseDir.
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("path escapes output directory: " + relPath);
        }
        return resolved;
    }
}

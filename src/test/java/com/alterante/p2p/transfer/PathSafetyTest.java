package com.alterante.p2p.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathSafetyTest {

    @Test
    void resolvesNestedRelativePath(@TempDir Path out) {
        Path resolved = PathSafety.resolveChild(out, "sub/dir/file.txt");
        assertTrue(resolved.startsWith(out.toAbsolutePath().normalize()));
        assertTrue(resolved.endsWith(Path.of("sub", "dir", "file.txt")));
    }

    @Test
    void resolvesSimpleFile(@TempDir Path out) {
        Path resolved = PathSafety.resolveChild(out, "file.txt");
        assertEquals(out.toAbsolutePath().normalize().resolve("file.txt"), resolved);
    }

    @Test
    void rejectsParentTraversal(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "../escape.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "sub/../../escape.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "a/b/../../../etc/passwd"));
    }

    @Test
    void rejectsBackslashTraversal(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "..\\escape.txt"));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "sub\\..\\..\\escape.txt"));
    }

    @Test
    void rejectsAbsolutePath(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "/etc/passwd"));
    }

    @Test
    void rejectsDriveQualifiedPath(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "C:\\Windows\\system32"));
    }

    @Test
    void rejectsEmptyPath(@TempDir Path out) {
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, ""));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> PathSafety.resolveChild(out, null));
    }

    @Test
    void allowsDotInFilename(@TempDir Path out) {
        // "..foo" or "a.b" are fine — only a lone ".." segment is rejected
        Path resolved = PathSafety.resolveChild(out, "docs/notes.final.txt");
        assertTrue(resolved.startsWith(out.toAbsolutePath().normalize()));
    }
}

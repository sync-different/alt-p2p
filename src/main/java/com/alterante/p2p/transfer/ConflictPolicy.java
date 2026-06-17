package com.alterante.p2p.transfer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * Decides what to do when an incoming file collides with a differing file that
 * already exists on the receiver (R4). Supports a fixed mode (overwrite / skip /
 * keep-both) or interactive prompting (ask) with an "apply to all" shortcut.
 *
 * Identical existing files are handled upstream (skipped via the D4 hash match)
 * and never reach here; only a genuinely conflicting file does.
 */
public class ConflictPolicy {

    public enum Mode { OVERWRITE, SKIP, KEEP_BOTH, ASK }
    public enum Decision { OVERWRITE, SKIP, KEEP_BOTH }

    private Mode mode;
    private Decision applyAll;
    private final BufferedReader in;
    private final PrintStream out = System.err;

    /**
     * @param mode        the conflict mode
     * @param interactive whether ASK may read from stdin; if false, ASK degrades to SKIP
     */
    public ConflictPolicy(Mode mode, boolean interactive) {
        this.mode = mode;
        this.in = interactive ? new BufferedReader(new InputStreamReader(System.in)) : null;
        if (mode == Mode.ASK && this.in == null) {
            this.mode = Mode.SKIP; // no TTY to prompt on
        }
    }

    /** A non-interactive overwrite policy (default for in-process use and tests). */
    public static ConflictPolicy overwrite() {
        return new ConflictPolicy(Mode.OVERWRITE, false);
    }

    /** Whether resolving may block on user input (used to suppress idle timeouts). */
    public boolean mayPrompt() {
        return mode == Mode.ASK && applyAll == null;
    }

    public synchronized Decision resolve(String relPath) {
        if (applyAll != null) return applyAll;
        return switch (mode) {
            case OVERWRITE -> Decision.OVERWRITE;
            case SKIP -> Decision.SKIP;
            case KEEP_BOTH -> Decision.KEEP_BOTH;
            case ASK -> prompt(relPath);
        };
    }

    private Decision prompt(String relPath) {
        try {
            out.println(); // break off any progress line so the prompt is visible
            while (true) {
                out.print("Conflict: '" + relPath + "' exists and differs. "
                        + "[o]verwrite [s]kip [k]eep-both (add 'a' to apply to all, e.g. 'oa'): ");
                out.flush();
                String line = in.readLine();
                if (line == null) return Decision.SKIP; // EOF
                line = line.trim().toLowerCase();
                if (line.isEmpty()) continue;
                Decision d = switch (line.charAt(0)) {
                    case 'o' -> Decision.OVERWRITE;
                    case 's' -> Decision.SKIP;
                    case 'k' -> Decision.KEEP_BOTH;
                    default -> null;
                };
                if (d == null) { out.println("  (unrecognized — enter o, s, or k)"); continue; }
                if (line.contains("a")) applyAll = d; // 'a' anywhere → apply to all
                return d;
            }
        } catch (IOException e) {
            return Decision.SKIP;
        }
    }

    public static Mode parseMode(String s) {
        return switch (s.toLowerCase()) {
            case "overwrite" -> Mode.OVERWRITE;
            case "skip" -> Mode.SKIP;
            case "keep-both", "keepboth" -> Mode.KEEP_BOTH;
            case "ask" -> Mode.ASK;
            default -> throw new IllegalArgumentException(
                    "invalid --on-conflict: " + s + " (overwrite|skip|keep-both|ask)");
        };
    }
}

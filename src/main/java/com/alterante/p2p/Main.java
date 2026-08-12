package com.alterante.p2p;

import com.alterante.p2p.command.CoordServerCommand;
import com.alterante.p2p.command.ReceiveCommand;
import com.alterante.p2p.command.SendCommand;
import picocli.CommandLine;

@CommandLine.Command(
        name = "alt-p2p",
        description = "P2P file transfer for Alterante",
        mixinStandardHelpOptions = true,
        versionProvider = Main.JarVersion.class,
        subcommands = {
                CoordServerCommand.class,
                SendCommand.class,
                ReceiveCommand.class,
        }
)
public class Main implements Runnable {

    /**
     * Reports the version recorded in the jar manifest at build time.
     *
     * <p>Replaces a hardcoded literal that was last updated for 0.5.0, so every release since
     * introduced itself as 0.5.0 — including the jars currently deployed on the fleet. A version
     * string that has to be remembered by hand is one that will be wrong, and it is wrong exactly
     * when it matters: while working out which build a misbehaving box is running.
     *
     * <p>Falls back to "dev" when running from classes rather than a packaged jar, where there is
     * no manifest to read.
     */
    public static class JarVersion implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String v = Main.class.getPackage().getImplementationVersion();
            return new String[]{"alt-p2p " + (v != null ? v : "dev (unpackaged)")};
        }
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}

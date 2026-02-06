package com.alterante.p2p;

import com.alterante.p2p.command.CoordServerCommand;
import com.alterante.p2p.command.ReceiveCommand;
import com.alterante.p2p.command.SendCommand;
import picocli.CommandLine;

@CommandLine.Command(
        name = "alt-p2p",
        description = "P2P file transfer for Alterante",
        mixinStandardHelpOptions = true,
        version = "0.1.0",
        subcommands = {
                CoordServerCommand.class,
                SendCommand.class,
                ReceiveCommand.class,
        }
)
public class Main implements Runnable {

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }
}

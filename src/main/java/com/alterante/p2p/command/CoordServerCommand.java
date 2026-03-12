package com.alterante.p2p.command;

import com.alterante.p2p.net.CoordServer;
import com.alterante.p2p.net.TcpRelayServer;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "server",
        description = "Run the coordination server",
        mixinStandardHelpOptions = true
)
public class CoordServerCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"--port", "-p"}, description = "UDP port (default: 9000)", defaultValue = "9000")
    private int port;

    @CommandLine.Option(names = {"--psk"}, description = "Pre-shared key for authentication", required = true)
    private String psk;

    @CommandLine.Option(names = {"--session-timeout"}, description = "Session timeout in seconds (default: 300)", defaultValue = "300")
    private int sessionTimeout;

    @CommandLine.Option(names = {"--tcp-port"}, description = "TCP relay port (default: UDP port + 1)")
    private Integer tcpPort;

    @Override
    public Integer call() throws Exception {
        CoordServer server = new CoordServer(port, psk, sessionTimeout);

        int relayPort = tcpPort != null ? tcpPort : port + 1;
        TcpRelayServer tcpRelay = new TcpRelayServer(relayPort, psk);

        // Shut down cleanly on Ctrl+C
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            tcpRelay.stop();
            server.stop();
        }));

        Thread tcpThread = new Thread(() -> {
            try {
                tcpRelay.start();
            } catch (Exception e) {
                System.err.println("TCP relay server failed: " + e.getMessage());
            }
        }, "tcp-relay");
        tcpThread.setDaemon(true);
        tcpThread.start();

        server.start();
        return 0;
    }
}

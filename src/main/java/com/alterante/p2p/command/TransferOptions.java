package com.alterante.p2p.command;

import picocli.CommandLine;

/**
 * Advanced tuning parameters shared by SendCommand and ReceiveCommand.
 * All values are optional — Java components use their existing defaults when null.
 */
public class TransferOptions {

    @CommandLine.Option(names = "--punch-timeout", description = "Hole punch timeout in ms (default: 10000)")
    public Integer punchTimeoutMs;

    @CommandLine.Option(names = "--punch-interval", description = "Hole punch interval in ms (default: 100)")
    public Integer punchIntervalMs;

    @CommandLine.Option(names = "--dtls-retries", description = "DTLS handshake max retries (default: 3)")
    public Integer dtlsRetries;

    @CommandLine.Option(names = "--dtls-timeout", description = "DTLS handshake timeout in ms (default: 30000)")
    public Integer dtlsTimeoutMs;

    @CommandLine.Option(names = "--initial-cwnd", description = "Initial congestion window in packets (default: 32)")
    public Integer initialCwnd;

    @CommandLine.Option(names = "--keepalive-interval", description = "Keepalive interval in ms (default: 15000)")
    public Integer keepaliveIntervalMs;

    @CommandLine.Option(names = "--allow-relay", description = "Allow relay fallback through coord server if hole punch fails (default: false)")
    public boolean allowRelay;

    @CommandLine.Option(names = "--no-relay", description = "Disable relay fallback (default)")
    public boolean noRelay;

    @CommandLine.Option(names = "--relay-mode", description = "Relay mode: 'tcp' or 'udp' (default: tcp)")
    public String relayMode = "tcp";

    @CommandLine.Option(names = "--relay-tcp-port", description = "TCP relay port (default: server UDP port + 1)")
    public Integer relayTcpPort;
}

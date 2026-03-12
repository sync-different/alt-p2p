# alt-p2p

Encrypted peer-to-peer file transfer over UDP with NAT traversal. Built for [Alterante](https://github.com/sync-different/alt-core), a decentralized virtual filesystem.

Peers connect through a lightweight coordination server, punch through NATs, establish a DTLS-encrypted channel, and transfer files with reliable delivery, congestion control, and integrity verification.

## Features

- **NAT traversal** - UDP hole punching with symmetric NAT support
- **TCP relay fallback** - When hole punching fails, streams data through the server via TLS-PSK (~15 MB/s)
- **End-to-end encryption** - DTLS 1.2 (direct) or TLS 1.2 (relay) with pre-shared key authentication
- **Reliable delivery** - SACK-based selective acknowledgment with retransmission (direct mode)
- **Congestion control** - AIMD with slow start, fast retransmit, and adaptive receiver window
- **Integrity verification** - SHA-256 hash checked by receiver after transfer
- **Resume support** - Interrupted transfers resume from last checkpoint via `.p2p-partial` sidecar files
- **Progress display** - Real-time progress bar with speed and ETA

## Performance

Tested over WAN between two NAT'd peers on different networks (155ms RTT):

### Direct UDP (hole punched)

| File Size | Throughput | Packets | Retransmissions |
|-----------|-----------|---------|-----------------|
| 100 MB | 9.1 MB/s | 92,062 | 0 |
| 1 GB | 9.5 MB/s | 942,706 | 0 |

### TCP Relay (through coordination server)

| File Size | Throughput | Notes |
|-----------|-----------|-------|
| 725 MB | ~15 MB/s | Via VPS with 155ms RTT |

TCP relay is 28x faster than the legacy UDP relay approach (530 KB/s) and 3x faster than SCP to the same VPS.

## Requirements

- JDK 17+
- Maven 3.9+

## Build

```bash
mvn package
```

Produces a fat JAR at `target/alt-p2p-0.3.0-SNAPSHOT.jar`.

## Usage

### Start the coordination server

```bash
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar server --psk <shared-key>
```

Options:
- `-p, --port` - UDP port (default: 9000)
- `--tcp-port` - TCP relay port (default: UDP port + 1)
- `--psk` - Pre-shared key for authentication (required)
- `--session-timeout` - Session timeout in seconds (default: 300)

### Send a file

```bash
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar send \
  -s <session-id> --psk <shared-key> --server <host:port> -f <file>
```

### Receive a file

```bash
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar receive \
  -s <session-id> --psk <shared-key> --server <host:port> -o <output-dir>
```

Both peers must use the same session ID and PSK. The sender and receiver can be started in any order.

### Relay options (for send and receive)

- `--allow-relay` - Allow relay fallback when hole punching fails
- `--relay-mode tcp|udp` - Relay transport (default: `tcp`, recommended)
- `--relay-tcp-port` - Override TCP relay port (default: server UDP port + 1)

## Architecture

```
Sender                    Coord Server                  Receiver
  |                           |                            |
  |--- REGISTER/AUTH -------->|                            |
  |                           |<------- REGISTER/AUTH -----|
  |<-- PEER_INFO ------------|------------ PEER_INFO ----->|
  |                                                        |
  |<=== UDP Hole Punch (direct, no server) ===============>|
  |                                                        |
  |<=== DTLS 1.2 Handshake (PSK) ========================>|
  |                                                        |
  |--- FILE_OFFER ---------------------------------------->|
  |<----------------------------------------- FILE_ACCEPT -|
  |--- DATA packets (reliable, congestion-controlled) ---->|
  |<------------------------------------ SACK (selective) -|
  |--- FILE_COMPLETE ------------------------------------->|
  |<---------------------------------------- FILE_VERIFIED -|
```

### Components

- **Coordination** (`CoordClient`, `CoordServer`) - Session registration, authentication, peer endpoint exchange
- **NAT Traversal** (`HolePuncher`) - Simultaneous UDP hole punching with symmetric NAT detection
- **Encryption** (`DtlsHandler`) - DTLS 1.2 via BouncyCastle with PSK, handshake retry on failure
- **Transport** (`ReliableChannel`, `SlidingWindow`, `ReceiveBuffer`) - Reliable ordered delivery with SACK
- **Congestion** (`CongestionControl`, `RttEstimator`) - AIMD with adaptive receiver window (256-512 packets)
- **Transfer** (`FileSender`, `FileReceiver`) - File chunking, progress tracking, SHA-256 verification
- **TCP Relay** (`TcpRelayServer`, `TcpRelayClient`, `TcpFileSender`, `TcpFileReceiver`) - Server-side TCP proxy with E2E TLS-PSK encryption
- **I/O** (`PacketRouter`) - Single-threaded event loop, 10ms tick, keepalive management

## Tests

```bash
mvn test    # 84 tests
```

## License

See [LICENSE](LICENSE) for details.

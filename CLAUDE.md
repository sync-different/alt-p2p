# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

alt-p2p is an encrypted peer-to-peer file transfer system over UDP with NAT traversal. Built for [Alterante](https://github.com/sync-different/alt-core), a decentralized virtual filesystem.

Peers connect through a lightweight coordination server, punch through NATs, establish a DTLS-encrypted channel, and transfer files with reliable delivery, congestion control, and integrity verification. When hole punching fails, a TCP relay mode streams data through the server with TLS-PSK end-to-end encryption.

## Build & Test

```bash
mvn package          # Build fat JAR → target/alt-p2p-0.3.0-SNAPSHOT.jar
mvn test             # Run all 84 tests (JUnit 5)
```

Requires JDK 17+ and Maven 3.9+.

## Running

```bash
# Coordination server (also starts TCP relay on port+1)
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar server --psk <key>

# Send a file (direct UDP)
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar send -s <session> --psk <key> --server <host:port> -f <file>

# Send with TCP relay fallback (if hole punching fails)
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar send -s <session> --psk <key> --server <host:port> -f <file> --allow-relay --relay-mode tcp

# Receive a file
java -jar target/alt-p2p-0.3.0-SNAPSHOT.jar receive -s <session> --psk <key> --server <host:port> -o <dir>
```

## Architecture

```
src/main/java/com/alterante/p2p/
├── Main.java                  # CLI entry point (picocli)
├── command/                   # CLI subcommands: server, send, receive + TransferOptions
├── net/                       # Networking: coordination, hole punch, DTLS, packet routing, TCP relay
├── protocol/                  # Binary packet format: codec, types, metadata
├── transport/                 # Reliable transport: congestion control, sliding window, SACK
└── transfer/                  # File transfer: sender, receiver, progress, resume
```

### Key Components

- **CoordServer/CoordClient** — Session registration with HMAC-SHA256 auth, peer endpoint exchange
- **HolePuncher** — Single-threaded UDP hole punching, accepts any-port for symmetric NAT support
- **DtlsHandler** — DTLS 1.2 via BouncyCastle PSK (not JDK SSLEngine). Includes non-DTLS packet filter and handshake deadline
- **PacketRouter** — Single-threaded I/O loop (10ms tick). All DTLS send/receive on one thread, lock-free send queue
- **ReliableChannel** — Combines RttEstimator, CongestionControl, SlidingWindow, ReceiveBuffer
- **FileSender/FileReceiver** — File chunking, auto-accept, SHA-256 verification, resume via `.p2p-partial` sidecar files
- **TcpRelayServer** — TCP accept loop + dumb byte-copy proxy between paired peers
- **TcpRelayClient** — TCP connect + HMAC auth + TLS-PSK handshake through proxy
- **TcpFileSender/TcpFileReceiver** — Stream file over TLS with 64KB chunks, length-prefixed messages

### Packet Format

20-byte header: magic(2) + version(1) + type(1) + flags(1) + connId(4) + seq(4) + payloadLen(2) + reserved(1) + CRC-32(4). Max 1200 bytes per datagram (1180 payload). CRC covers bytes 0-15.

## Critical Implementation Notes

### BouncyCastle DTLS

- `PSKTlsClient`/`PSKTlsServer` MUST override `getSupportedVersions()` with `ProtocolVersion.DTLSv12.only()` — NOT `new ProtocolVersion[]{...}` (causes `internal_error(80)`)
- `TlsTimeoutException extends IOException`, NOT `InterruptedIOException`. Throwing it from `DatagramTransport.receive()` kills the DTLS session via `recordLayer.fail(80)`. Let `SocketTimeoutException` propagate instead.
- BouncyCastle retries indefinitely on `SocketTimeoutException`. Use a hard `IOException` deadline to abort hung handshakes.
- Fat JAR must exclude `META-INF/*.SF`, `*.DSA`, `*.RSA` (BouncyCastle signature files)

### Thread Safety

- `LinkedHashMap.values().toArray()` is NOT thread-safe. All SlidingWindow access must happen under the same lock.
- PacketRouter is single-threaded by design — no locking needed on DTLS transport.

### Control Packet Handler Race Condition

- `PacketRouter` starts immediately in `PeerConnection.connect()`, but `FileReceiver`/`FileSender` register their control packet handlers later via `channel.onControlPacket()`.
- If the remote peer sends `FILE_OFFER` before the handler is registered, the packet is lost and both sides time out ("Timed out waiting for FILE_OFFER" / "Timed out waiting for FILE_ACCEPT").
- **Fix**: `ReliableChannel` registers control packet type handlers eagerly in its constructor and buffers any that arrive before `onControlPacket()` is called. Buffered packets are replayed when the handler is set.
- This race is more likely when running as a Tauri sidecar (piped stdout, different thread scheduling) vs terminal.

### Congestion Control Tuning

- INITIAL_CWND=32, INITIAL_SSTHRESH=2048 (CongestionControl.java)
- Receiver window: adaptive 256→512 packets, +32 per 128 clean in-order deliveries, halves on >50% buffer pressure (ReceiveBuffer.java)
- Tick/ACK timer: 10ms (PacketRouter.java, ReceiveBuffer.java)
- WAN performance: ~9.5 MB/s, 0 retransmissions on 1GB transfer

### NAT Traversal

- Receiving a PUNCH = success (don't wait for PUNCH_ACK)
- Accept PUNCH from expected IP but any port (symmetric NAT support)
- Send 3x keepalive (0x00) between hole punch and DTLS handshake
- Filter non-DTLS packets (first byte must be 0x14-0x17) during handshake
- DTLS handshake retries up to 3x with backoff (500ms, 1s, 1.5s), 30s deadline per attempt
- DTLS role assignment uses **public endpoints** (from coord server), not localPort vs remotePort. NAT remaps ports, so comparing local vs remote can give both peers the same role, deadlocking the handshake.

### TCP Relay Mode

When hole punching fails (symmetric-to-symmetric NAT), peers can fall back to TCP relay through the coordination server. The server acts as a dumb byte-copy proxy — true E2E encryption via TLS-PSK means the server never sees plaintext.

- TCP relay port defaults to UDP port + 1 (e.g., 9001). Configurable via `--tcp-port` on server, `--relay-tcp-port` on client.
- Auth: peers send a length-prefixed AUTH message with session ID + HMAC-SHA256 before TLS handshake.
- TLS role assignment: same logic as DTLS — `compareEndpoints(myPublicEndpoint, remoteEndpoint)` determines who is TLS client vs server.
- `TcpRelayServer.start()` is blocking (like `CoordServer.start()`), so it must run on a daemon thread.
- BouncyCastle TLS-PSK over TCP uses `TlsClientProtocol`/`TlsServerProtocol` with `ProtocolVersion.TLSv12.only()` (same pattern as DTLS).
- Performance: ~15 MB/s via TCP relay (28x faster than UDP relay's 530 KB/s), compared to ~5 MB/s SCP to the same VPS.
- Stream protocol: length-prefixed messages `type(1B) + length(4B BE) + payload`. Types: AUTH, AUTH_OK, FILE_OFFER, FILE_ACCEPT, DATA (64KB), COMPLETE, VERIFIED.

### JSON IPC Mode

Send and receive commands support `--json` for machine-readable NDJSON output on stdout. Used by the [alt-p2p-ui](https://github.com/sync-different/alt-p2p-ui) Tauri desktop app.

Events: `status`, `file_info`, `progress`, `complete`, `error`, `log`. Status states include `relay_tcp` for TCP relay connections. See `JsonOutput.java` for the format.

## Development Status

- **Phase 1** (Connectivity + Encryption): Complete
- **Phase 2** (Reliable Transport): Complete
- **Phase 3** (File Transfer): Complete (except CANCEL message)
- **Phase 4** (Hardening): CLI done, TCP relay done; IPv6, multi-file pending

See [ARCHITECTURE.md](ARCHITECTURE.md) for full design documentation.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

alt-p2p is an encrypted peer-to-peer file transfer system over UDP with NAT traversal. Built for [Alterante](https://github.com/sync-different/alt-core), a decentralized virtual filesystem.

Peers connect through a lightweight coordination server, punch through NATs, establish a DTLS-encrypted channel, and transfer files with reliable delivery, congestion control, and integrity verification. When hole punching fails, a TCP relay mode streams data through the server with TLS-PSK end-to-end encryption.

## Build & Test

```bash
mvn package          # Build fat JAR → target/alt-p2p-0.4.0-SNAPSHOT.jar
mvn test             # Run all 84 tests (JUnit 5)
```

Requires JDK 17+ and Maven 3.9+.

## Running

```bash
# Coordination server (also starts TCP relay on port+1)
java -jar target/alt-p2p-0.4.0-SNAPSHOT.jar server --psk <key>

# Send a file (direct UDP)
java -jar target/alt-p2p-0.4.0-SNAPSHOT.jar send -s <session> --psk <key> --server <host:port> -f <file>

# Send with TCP relay fallback (if hole punching fails)
java -jar target/alt-p2p-0.4.0-SNAPSHOT.jar send -s <session> --psk <key> --server <host:port> -f <file> --allow-relay --relay-mode tcp

# Receive a file
java -jar target/alt-p2p-0.4.0-SNAPSHOT.jar receive -s <session> --psk <key> --server <host:port> -o <dir>
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

- **CoordServer/CoordClient** — Session registration with HMAC-SHA256 auth, peer endpoint exchange. Peers also report their **LAN endpoint** in AUTH (appended after the HMAC); the server relays both public + local in PEER_INFO so same-NAT peers can punch over the LAN. Backward compatible (trailing bytes; old peer/server just omit it)
- **HolePuncher** — Single-threaded UDP hole punching; sends PUNCH to **multiple candidates** (peer's public + LAN endpoints) and accepts a validated PUNCH from any source address (symmetric NAT, multi-homed, hairpin, same-NAT)
- **DtlsHandler** — DTLS 1.2 via BouncyCastle PSK (not JDK SSLEngine). Includes non-DTLS packet filter and handshake deadline
- **PacketRouter** — Single-threaded I/O loop (10ms tick). All DTLS send/receive on one thread, lock-free send queue
- **ReliableChannel** — Combines RttEstimator, CongestionControl, SlidingWindow, ReceiveBuffer
- **FileSender/FileReceiver** — Single-file chunking, auto-accept, SHA-256 verification, resume via `.p2p-partial` sidecar files
- **DirectorySender/DirectoryReceiver** — Multi-file (folder) batch over ReliableChannel. Sender scans + sends MANIFEST/DIR_ENTRY/per-file/SESSION_COMPLETE; receiver is event-driven (control on a worker thread, DATA on the router thread), unifies batch + legacy single-file, owns conflict/skip/resume
- **TcpRelayServer** — TCP accept loop + dumb byte-copy proxy between paired peers
- **TcpRelayClient** — TCP connect + HMAC auth + TLS-PSK handshake through proxy
- **TcpFileSender/TcpFileReceiver** — Stream a single file over TLS with 64KB chunks, length-prefixed messages
- **TcpDirectorySender/TcpDirectoryReceiver** — Multi-file (folder) batch over the TLS relay stream (stream counterpart of the Directory*; receiver also unifies single-file)

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

### Multi-File (Folder) Transfer

`send -f <folder>` transfers a whole directory; works on both the direct-UDP and TCP-relay paths.

- **Batch envelope** wraps the existing per-file cycle: `MANIFEST(fileCount,dirCount,totalBytes)` → `DIR_ENTRY*` (empty dirs) → per-file `FILE_OFFER(relPath)→FILE_ACCEPT|FILE_REJECT→DATA…→COMPLETE→VERIFIED` → `SESSION_COMPLETE`. New `PacketType`/`TcpRelayProtocol` codes: `MANIFEST 0x13`, `DIR_ENTRY 0x14`, `SESSION_COMPLETE 0x33`.
- **Relative paths** ride in `FileMetadata.filename` (POSIX `/`). Receiver validates them via `PathSafety.resolveChild` (rejects `..`, absolute, drive-qualified, escaping) before writing — required to prevent path traversal.
- **Receiver unifies modes**: a leading `MANIFEST` → batch; a bare `FILE_OFFER` → legacy single-file. So `ReceiveCommand` always uses `DirectoryReceiver`/`TcpDirectoryReceiver`. Completion fires on `SESSION_COMPLETE` **or** when manifest counts are met (robust to a lost terminator).
- **DirectoryReceiver threading**: control packets processed on a single worker thread, DATA written on the router thread (preserves backpressure). This keeps keepalives flowing while a control step blocks (hashing an existing file, or an interactive conflict prompt).
- **Skip/resume (D4/R5)**: an existing file identical by size+SHA-256 is skipped silently (`FILE_REJECT`); a matching `.p2p-partial` sidecar resumes. The sidecar is written *immediately* on accept (before the first periodic save) so an interruption after `setLength()` resumes instead of looking like a same-size conflict.
- **Conflicts (R4)**: `--on-conflict overwrite|skip|keep-both|ask` (receiver). TTY default `ask` (prompt + `a`-suffix apply-to-all), `--json` default `skip`. `ConflictPolicy`. Keep-both → `name (1).ext`. The progress bar pauses while a prompt is showing (`isAwaitingUser()`), else `\r` hides it.
- **Reconnect (L1, best-effort)**: `SendCommand`/`ReceiveCommand` wrap connect→batch in a bounded loop (`--reconnect-attempts`, `--batch-deadline`). `PeerConnection.setLocalPort()` reuses the same UDP port so the coord re-pairs instead of rejecting a "3rd" peer. A death-watcher (`BatchRunner`) closes the channel + interrupts the batch thread on router death — **must be started AFTER `startRouter()`** or `awaitStop()` returns immediately (receiveThread null) and false-fires. Robust reconnect across symmetric NAT / relay (eviction, peer-id) is **L2, deferred** (needs coord/relay server changes).
- `ReliableChannel.inflightCount()` now takes `windowLock` (SlidingWindow must be read under the same lock as `track()`/`processSack()`).

### JSON IPC Mode

Send and receive commands support `--json` for machine-readable NDJSON output on stdout. Used by the [alt-p2p-ui](https://github.com/sync-different/alt-p2p-ui) Tauri desktop app.

Events: `status`, `file_info`, `progress`, `complete`, `error`, `log`. Folder transfers add `manifest` (`files_total`, `bytes_total`) and a batch `progress` variant (`scope:"batch"` with `file`, `file_bytes/total/percent`, `files_done/files_total`, and overall `bytes/total/percent/speed_bps/eta_seconds`). Status states include `relay_tcp`. See `JsonOutput.java`.

## Development Status

- **Phase 1** (Connectivity + Encryption): Complete
- **Phase 2** (Reliable Transport): Complete
- **Phase 3** (File Transfer): Complete (except CANCEL message)
- **Phase 4** (Hardening): CLI, TCP relay, **multi-file folder transfer** (direct + relay, conflicts, resume, best-effort reconnect) done; IPv6 pending
- **Deferred (L2)**: robust in-run reconnect across symmetric NAT / relay (coord dead-peer eviction + peer-id re-register, relay re-pair)

See [ARCHITECTURE.md](ARCHITECTURE.md) for full design documentation.

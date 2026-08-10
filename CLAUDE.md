# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

alt-p2p is an encrypted peer-to-peer file transfer system over UDP with NAT traversal. Built for [Alterante](https://github.com/sync-different/alt-core), a decentralized virtual filesystem.

Peers connect through a lightweight coordination server, punch through NATs, establish a DTLS-encrypted channel, and transfer files with reliable delivery, congestion control, and integrity verification. When hole punching fails, a TCP relay mode streams data through the server with TLS-PSK end-to-end encryption.

## Build & Test

```bash
mvn package          # Build fat JAR → target/alt-p2p-0.6.0-SNAPSHOT.jar
mvn test             # Run all tests (JUnit 5) — 120 run, 0 failures, 3 skipped (disabled stress spikes)

mvn test -Dtest=ReliableChannelTest              # one test class
mvn test -Dtest=ReliableChannelTest#sackRetransmit  # one test method
mvn test -Dtest='Tcp*Test'                       # pattern
```

Requires JDK 17+ and Maven 3.9+.

The two `@Disabled` cases in `FullDuplexSpikeTest` are **isolation-only** stress tests (pass
1500/1500 run alone, flaky under parallel loopback load) — re-enable them individually when
touching loss recovery, don't treat them as dead.

### End-to-end harnesses (not JUnit)

Real transfers need two processes and a coordinator, so the integration story lives in shell:

```bash
./scripts/loopback.sh            # single-machine dev loop: coord + receiver + sender + verify + teardown
./scripts/gen-corpus.sh <dir>    # build a multi-file test tree (nested, 0-byte, empty dirs, symlink)
./scripts/verify-tree.sh <src> <dst>  # assert received tree mirrors source by path + SHA-256
scripts/test-lan-multifile/      # multi-box LAN/WAN harness (config.sh + coord/send/recv/verify)
```

`loopback.sh` is the first-level check for any transfer change; `verify-tree.sh` is what makes a
multi-file run pass/fail (symlinks are excluded at the source and must be **absent** downstream).

## Running

```bash
# Coordination server (also starts TCP relay on port+1)
java -jar target/alt-p2p-0.6.0-SNAPSHOT.jar server --psk <key>

# Send a file (direct UDP)
java -jar target/alt-p2p-0.6.0-SNAPSHOT.jar send -s <session> --psk <key> --server <host:port> -f <file>

# Send with TCP relay fallback (if hole punching fails)
java -jar target/alt-p2p-0.6.0-SNAPSHOT.jar send -s <session> --psk <key> --server <host:port> -f <file> --allow-relay --relay-mode tcp

# Receive a file
java -jar target/alt-p2p-0.6.0-SNAPSHOT.jar receive -s <session> --psk <key> --server <host:port> -o <dir>
```

## Architecture

```
src/main/java/com/alterante/p2p/
├── Main.java                  # CLI entry point (picocli)
├── command/                   # CLI subcommands: server, send, receive + TransferOptions
├── net/                       # Networking: coordination, hole punch, DTLS, packet routing, TCP relay
│   └── tunnel/                # Generic TCP-over-P2P stream tunnel (carrier → mux → forwarders)
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
- **TcpRelayServer** — TCP accept loop + dumb byte-copy proxy between paired peers. Pairs by session
  id; a first arrival is *parked* until its partner shows up (see "Relay stale-peer pairing" below)
- **TcpRelayClient** — TCP connect + HMAC auth + TLS-PSK handshake through proxy
- **TcpFileSender/TcpFileReceiver** — Stream a single file over TLS with 64KB chunks, length-prefixed messages
- **TcpDirectorySender/TcpDirectoryReceiver** — Multi-file (folder) batch over the TLS relay stream (stream counterpart of the Directory*; receiver also unifies single-file)
- **net/tunnel/** — Generic TCP-over-P2P port-forwarding layer (library, no CLI command yet; built for [alt-p2p-lore](https://github.com/sync-different/alt-p2p-lore)). `Tunnels.carrier()` bridges a connected `PeerConnection` to a `BytePipe` — `DirectBytePipe` over a fresh `ReliableChannel` (direct UDP) or `RelayBytePipe` over the relay's TLS streams. `StreamMux` multiplexes many logical streams over the one pipe (frame: `type(1)|streamId(4)|length(4)|payload`; OPEN/DATA/CLOSE; initiator-assigned ids). `ForwardListener` (local port → new stream per accept) and `ForwardConnector` (inbound stream → TCP connect to a local target) with `Bridge` splicing socket↔stream both ways

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

- INITIAL_CWND=32, INITIAL_SSTHRESH=2048, MIN_SSTHRESH=8 (CongestionControl.java)
- Receiver window: adaptive 256→512 packets, +32 per 128 clean in-order deliveries, halves on >50% buffer pressure (ReceiveBuffer.java)
- Tick/ACK timer: 10ms (PacketRouter.java, ReceiveBuffer.java)
- WAN performance: ~9.5 MB/s, 0 retransmissions on 1GB transfer

### Full-Duplex Loss Recovery (ReliableChannel)

Hardening validated by `FullDuplexSpikeTest` (both peers streaming bulk DATA at once — a
prerequisite for the stream tunnel, which file transfer alone never exercised). Under
sustained loss the old fast-retransmit path stalled:

- **SACK-driven retransmit**: the receiver's SACK ranges already name the missing sequences,
  so `sackRetransmit()` resends them immediately instead of waiting for 3 duplicate ACKs —
  which are unreachable once cwnd has collapsed to a few packets. A gap seen in many
  consecutive 10ms SACKs is resent at most once per ~SRTT (guard = `max(srtt, 20)ms`), and a
  SACK gap does **not** back off RTO (it's a confident loss signal, not a timeout).
- **NewReno fast recovery**: reduce cwnd at most once per loss *episode* (per RTT), not once
  per lost packet. `inRecovery`/`recoveryPoint` clear when the cumulative ACK advances past
  the sequence in flight when the loss occurred.
- **RTO backoff reset** (`RttEstimator.resetBackoff()`): recompute RTO from the smoothed
  estimates whenever the cumulative ACK advances, so a loss burst doesn't leave RTO pinned
  near MAX long after recovery (Karn's algorithm otherwise keeps it backed off when the
  advancing ACKs are for retransmits).
- **MIN_SSTHRESH raised 2→8**: at cwnd=2 the pipe is starved and SACK feedback too sparse to
  detect further losses promptly, which re-created the stall. 8 packets (~10 KB) keeps loss
  detection dense with a still-small footprint.
- `SlidingWindow.getInFlight(seq)` — O(1) lookup used by the retransmit path (replaces an
  O(n) scan).

### Coordination Session Recycling

A REGISTER on a session that is already **full but fully paired** (`Session.bothAuthenticated()`)
recycles the slots (`Session.reset()`) instead of rejecting with "Session full". Once both peers
have received PEER_INFO they connect directly and no longer depend on the coordinator, so a fresh
REGISTER is a *new* rendezvous on the same session id — e.g. a persistent host serving successive
client operations (the alt-p2p-lore host model). Only recycled after pairing completes.

### Long-Lived Hosts: Socket Lifetime & Peer Wait

A persistent host (bbs `host`, lore `serve`) sits in `waitForPeerInfo()` for hours. Two invariants
make that survivable — both are easy to regress:

- **`connect()` must `close()` its own socket on failure.** `PeerConnection.connect()` throws before
  the caller ever holds a `PeerConnection` to close, so a failed attempt (peer-wait timeout, punch
  fail → relay read-timeout) orphans the `DatagramSocket` fd. A waiting host leaked **758 sockets**
  this way. The ordering in the `catch` is load-bearing: `close()` **then** `setState(ERROR)` —
  `close()` resets state to `INIT`, so setting ERROR first is silently undone (a test covers this).
- **`CoordClient` sends `COORD_KEEPALIVE` every 90s while waiting**, under the coordinator's ~300s
  `lastActivity` expiry. This holds *one* registration open instead of tearing down and
  re-registering per cycle — which is what produced the fd leak. The coordinator already handled
  this packet type; no server change was needed.

`--peer-wait <sec>` sets the wait (`CoordClient.setPeerWaitMs`); **`<= 0` means wait forever**.
One-shot `send`/`receive` keep the 120s default; the bbs `host` and lore `serve` commands default to
forever. Wait-forever plus the two invariants above is what makes an idle host cost exactly one socket.

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

### Relay Stale-Peer Pairing (v0.6.1)

`TcpRelayServer` matches two authenticated connections by session id and splices them. Until 0.6.1 its
only liveness test was `!socket.isClosed()` — **true whenever *we* have not closed the socket locally**,
so a peer whose process had gone still passed it. A live arrival was then spliced to that corpse.

**Why it is hard to diagnose from the client:** the symptom is a bare `handshake_failure(40)` from
BouncyCastle, which points at PSK mismatch or TLS role assignment. Both are red herrings — the far side
simply never sent a hello. The tell is server-side: **a splice that ends with 0 bytes in both
directions**, immediately.

Observed live 2026-08-10: a peer parked at 19:04:40 was spliced to a fresh arrival 44s later; the client
saw `handshake_failure(40)`. Retrying "worked" because each attempt consumed one corpse and parked a new
connection — the queue shuffled forward until two live peers happened to land together, which masks the
bug as flakiness.

The fix has two independent halves, because they catch different failures:

- **Liveness probe at pairing time** (`isPeerAlive`) — a 5ms read; **EOF means dead**. This is the only
  probe that works here: after the peer closes we hold a *half-open* socket where `isClosed()` is false
  (we did not close it), `isInputShutdown()` is false (that is *our* shutdown), and `sendUrgentData`
  **succeeds** (a half-closed socket may still send). Reading is safe because the protocol makes a
  parked peer silent — it has sent AUTH and must wait for `AUTH_OK`, which only arrives on pairing — and
  anything readable is pushed back through a `PushbackInputStream` that is then handed to the splice, so
  no byte is lost between probing and splicing.
- **`PAIR_TIMEOUT_MS` 60s → 30s** — the corpse in the incident was 44s old, i.e. *inside* the old
  window, so the reaper never saw it. Both peers normally reach the relay within seconds of PEER_INFO.

**Log lines to grep when a relay connection misbehaves:**

```bash
journalctl -u alt-p2p-coord | grep -E "STALE parked peer|0 bytes|reaping unpaired|starting splice"
```

| line | meaning |
|---|---|
| `discarding STALE parked peer …` | the probe fired — a corpse was rejected instead of spliced |
| `splice … ended with 0 bytes` | **WARN** — the peers never spoke; the live side sees `handshake_failure` |
| `reaping unpaired peer … after Nms` | timeout swept a peer whose partner never came (was DEBUG, invisible in production) |
| `starting splice (A <-> B, first peer waited Nms)` | healthy pairing, with both addresses and the wait |

`TcpRelayServerTest` is the relay's **first** test coverage — its absence is how this shipped. It covers
the happy path (pair + bytes both ways), wrong-PSK rejection, and the regression: park a peer, kill it,
and assert a live arrival is *not* paired to it.

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
- **Stream tunnel** (v0.5.0): generic TCP-over-P2P multiplexing (`net/tunnel/`) + full-duplex loss-recovery hardening + coordination session recycling. Library layer for [alt-p2p-lore](https://github.com/sync-different/alt-p2p-lore); not yet exposed as a CLI subcommand.
- **Host longevity** (v0.6.0): `DatagramSocket` fd-leak fix + coord keepalive + `--peer-wait` / wait-forever host mode — see "Long-Lived Hosts" above. Consumed by alt-p2p-bbs and alt-p2p-lore, which **shade** this JAR: bumping the version here means bumping `<alt-p2p.version>` in both sibling poms and rebuilding their fat JARs.
- **Relay stale-peer fix** (v0.6.1): `TcpRelayServer` no longer splices a live peer to a departed one —
  see "Relay stale-peer pairing" above. **Coordinator-only change**: `TcpRelayClient`/`PeerConnection`
  are untouched and there is no protocol change, so a 0.6.1 relay serves 0.5.0/0.6.0 peers unchanged and
  only the coordinator needs the new jar. Deployed to demo7 2026-08-10 (0.5.0 → 0.6.1, verified by the
  reaper firing at 30s).
- **Deferred (L2)**: robust in-run reconnect across symmetric NAT / relay (coord dead-peer eviction + peer-id re-register, relay re-pair)

See [ARCHITECTURE.md](ARCHITECTURE.md) for full design documentation.

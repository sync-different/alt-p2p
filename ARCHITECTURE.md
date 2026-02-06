# Java P2P File Transfer Architecture

A peer-to-peer file transfer system using Java command-line applications for both sender and receiver. Both peers are assumed to be behind NAT.

## Architecture Overview

```
┌─────────────┐                                     ┌─────────────┐
│   Sender    │                                     │  Receiver   │
│  (Java CLI) │                                     │  (Java CLI) │
└──────┬──────┘                                     └──────┬──────┘
       │                                                   │
       │            ┌───────────────────┐                  │
       │            │ Coordination Server│                  │
       │            │   (Lightweight)    │                  │
       │            └─────────┬─────────┘                  │
       │                      │                            │
       │    1. Register       │       1. Register          │
       ├─────────────────────►│◄───────────────────────────┤
       │                      │                            │
       │    2. Get peer info  │       2. Get peer info     │
       │◄─────────────────────┤────────────────────────────►
       │                      │                            │
       │                                                   │
       │         3. UDP Hole Punch (repeated bursts)       │
       │◄─────────────────────┬────────────────────────────►
       │                      │                            │
       │              NAT           NAT                    │
       │               │             │                     │
       └───────────────┴─────────────┴─────────────────────┘
                    Direct P2P Connection
```

## Performance (WAN Results)

Tested over the real internet between two NAT'd peers on different networks:

| File Size | Throughput | Packets | Retransmissions |
|-----------|-----------|---------|-----------------|
| 100 MB | 9.1 MB/s | 92,062 | 0 |
| 1 GB | 9.5 MB/s | 942,706 | 0 |

Key tuning parameters for these results:
- Initial CWND: 32 packets
- Initial SSTHRESH: 2048 packets
- Receiver window: adaptive 256→512 packets
- ACK/tick timer: 10ms

## How UDP Hole Punching Works

### The NAT Problem

When behind NAT, your private IP (e.g., `192.168.1.100`) is translated to a public IP:port by your router. External peers can't initiate connections to you because the NAT blocks unsolicited incoming packets.

### The Solution

1. Both peers connect to a known coordination server
2. Server records each peer's public IP:port (as seen by the server)
3. Server shares this information with both peers
4. Both peers begin sending UDP packets to each other's public endpoint repeatedly
5. Early packets are dropped by the remote NAT (no mapping exists yet), but the outgoing packets create local NAT mappings
6. Eventually, a packet from Peer B arrives at Peer A's NAT *after* Peer A has already sent to Peer B — the NAT treats it as a response to the outgoing packet and lets it through
7. Connection established in both directions

**Important**: The term "simultaneous" is misleading. The peers do not need synchronized clocks. The protocol works because *repeated* sending over several seconds creates overlapping NAT mappings. Early packets are expected to be lost.

```
Time    Peer A (behind NAT)              Peer B (behind NAT)
─────────────────────────────────────────────────────────────
 T0     Send PUNCH #1 to B ──►           (B hasn't started yet)
        (creates NAT mapping A→B)        NAT-B drops packet (no mapping)

 T1     (nothing received)               Send PUNCH #1 to A ──►
                                         (creates NAT mapping B→A)
                                         NAT-A drops packet (A→B mapping
                                         exists, but check fails on
                                         port-restricted NAT)

 T2     Send PUNCH #2 to B ──►           Send PUNCH #2 to A ──►
        NAT-B now has B→A mapping,       NAT-A now has A→B mapping,
        so A's packet gets through ✓     so B's packet gets through ✓

 T3     ◄════════ P2P Connection Established ════════►
```

### Symmetric NAT Handling

Symmetric NATs allocate a **different external port for each unique destination** (IP:port pair). The coordination server sees the port allocated for `Peer→Server` communication, but the peer will use a *different* port when sending to the remote peer.

Our implementation handles this by accepting PUNCH packets from the expected **IP address but any port**. When the port differs from the expected one, the hole puncher updates the remote endpoint to the actual observed port. This makes symmetric-to-non-symmetric NAT pairs work reliably. Symmetric-to-symmetric remains unsolvable without a relay.

### Double NAT / CGNAT

Many networks have **two NAT layers**: ISP-level Carrier-Grade NAT (CGNAT) plus the home router. The coordination server sees the outermost NAT's mapping. Hole punching can still work if both NAT layers are non-symmetric, but the additional translation layer reduces success rates. IPv6 avoids this problem entirely (see IPv6 section below).

## Components

### 1. Coordination Server

Minimal server that helps peers find each other:

- Listens on a known public IP:port (UDP)
- Registers peers with HMAC-SHA256 authentication
- Shares peer endpoint information

**Responsibilities:**
- Peer registration with challenge-response authentication
- Session management (exactly 2 peers per session)
- Endpoint exchange

### 2. Sender Application (Java CLI)

```
$ java -jar alt-p2p-0.1.0-SNAPSHOT.jar send -s <session-id> --psk <key> --server <host:port> -f <file>

Options:
  --session, -s    Session ID to join or create (required)
  --server         Coordination server address as host:port (required)
  --psk            Pre-shared key for authentication (required)
  --file, -f       File to send (required)
```

### 3. Receiver Application (Java CLI)

```
$ java -jar alt-p2p-0.1.0-SNAPSHOT.jar receive -s <session-id> --psk <key> --server <host:port> -o <dir>

Options:
  --session, -s    Session ID to join (required)
  --server         Coordination server address as host:port (required)
  --psk            Pre-shared key for authentication (required)
  --output, -o     Output directory (required)
```

The receiver **auto-accepts** incoming file offers (no interactive prompt).

## Peer State Machines

### Sender States

```
                  ┌──────────────┐
                  │     INIT     │
                  └──────┬───────┘
                         │ connect to coord server
                         ▼
                  ┌──────────────┐
              ┌───│ REGISTERING  │
              │   └──────┬───────┘
    timeout / │          │ OK + challenge response
    error     │          ▼
              │   ┌──────────────┐
              │   │WAITING_PEER  │◄──── keepalive every 15s
              │   └──────┬───────┘
              │          │ PEER_INFO received
              │          ▼
              │   ┌──────────────┐
              ├───│  PUNCHING    │──── send PUNCH every 100ms
              │   └──────┬───────┘     for up to 10s
              │          │ PUNCH or PUNCH_ACK received
              │          ▼
              │   ┌──────────────┐
              │   │  HANDSHAKE   │──── DTLS PSK handshake (up to 3 retries)
              │   └──────┬───────┘
              │          │ DTLS established
              │          ▼
              │   ┌──────────────┐
              │   │  CONNECTED   │──── send FILE_OFFER
              │   └──────┬───────┘
              │          │ FILE_ACCEPT received
              │          ▼
              │   ┌──────────────┐
              ├───│ TRANSFERRING │──── send DATA packets
              │   └──────┬───────┘     with sliding window
              │          │ all chunks ACKed
              │          ▼
              │   ┌──────────────┐
              │   │  COMPLETING  │──── send COMPLETE
              │   └──────┬───────┘
              │          │ VERIFIED received
              │          ▼
              │   ┌──────────────┐
              │   │     DONE     │
              │   └──────────────┘
              │
              ▼
        ┌──────────┐
        │  ERROR   │──── send ERROR to peer, log, exit
        └──────────┘
```

### Receiver States

```
                  ┌──────────────┐
                  │     INIT     │
                  └──────┬───────┘
                         │ connect to coord server
                         ▼
                  ┌──────────────┐
              ┌───│ REGISTERING  │
              │   └──────┬───────┘
    timeout / │          │ OK + challenge response
    error     │          ▼
              │   ┌──────────────┐
              │   │WAITING_PEER  │◄──── keepalive every 15s
              │   └──────┬───────┘
              │          │ PEER_INFO received
              │          ▼
              │   ┌──────────────┐
              ├───│  PUNCHING    │──── send PUNCH every 100ms
              │   └──────┬───────┘     for up to 10s
              │          │ PUNCH or PUNCH_ACK received
              │          ▼
              │   ┌──────────────┐
              │   │  HANDSHAKE   │──── DTLS PSK handshake (up to 3 retries)
              │   └──────┬───────┘
              │          │ DTLS established
              │          ▼
              │   ┌──────────────┐
              │   │  CONNECTED   │──── waiting for FILE_OFFER
              │   └──────┬───────┘
              │          │ FILE_OFFER received, auto-accept (FILE_ACCEPT)
              │          ▼
              │   ┌──────────────┐
              ├───│  RECEIVING   │──── receive DATA, send SACKs
              │   └──────┬───────┘
              │          │ COMPLETE received, all chunks present
              │          ▼
              │   ┌──────────────┐
              │   │  VERIFYING   │──── SHA-256 full file check
              │   └──────┬───────┘
              │          │ hash matches → send VERIFIED
              │          ▼
              │   ┌──────────────┐
              │   │     DONE     │
              │   └──────────────┘
              │
              ▼
        ┌──────────┐
        │  ERROR   │──── send ERROR to peer, log, exit
        └──────────┘
```

### State Timeouts

| State | Timeout | Action on Timeout |
|-------|---------|-------------------|
| REGISTERING | 5s per attempt | Retry up to 3x, then ERROR |
| WAITING_PEER | 120s | ERROR: peer did not join |
| PUNCHING | 10s | ERROR (relay not yet implemented) |
| HANDSHAKE | 30s per attempt | Retry up to 3x (backoff: 500ms, 1s, 1.5s), then ERROR |
| CONNECTED | 45s no data received | PacketRouter declares connection dead |

## Connection Flow

```
Sender                  Coord Server                  Receiver
  │                          │                            │
  │─── REGISTER(session) ───►│                            │
  │◄── CHALLENGE(nonce) ─────│                            │
  │─── AUTH(HMAC(psk,nonce))►│                            │
  │◄── OK(your_endpoint) ────│                            │
  │                          │                            │
  │                          │◄─── REGISTER(session) ─────│
  │                          │──── CHALLENGE(nonce) ──────►│
  │                          │◄─── AUTH(HMAC(psk,nonce)) ──│
  │                          │──── OK(your_endpoint) ─────►│
  │                          │                            │
  │◄── PEER_INFO(receiver) ──│─── PEER_INFO(sender) ─────►│
  │                          │                            │
  │                    [Hole Punching Phase]              │
  │                          │                            │
  │══════════ PUNCH packets (both directions) ═══════════►│
  │◄═════════ PUNCH packets (both directions) ════════════│
  │                          │                            │
  │                    [DTLS Handshake]                   │
  │◄═════════════════════════╤════════════════════════════►│
  │                          │                            │
  │                 [Encrypted P2P Established]           │
  │                          │                            │
  │────────────── FILE_OFFER(name, size, hash) ──────────►│
  │◄───────────────────── FILE_ACCEPT ────────────────────│
  │                          │                            │
  │════════════════ DATA packets (windowed) ═════════════►│
  │◄════════════════ SACK packets ════════════════════════│
  │                          │                            │
  │────────────── COMPLETE(final_hash) ─────────────────►│
  │◄───────────── VERIFIED ──────────────────────────────│
```

## Protocol Design

### Packet Structure

All communication (both coordination and P2P) uses the same binary packet format. This eliminates the need for two separate parsers.

```
┌───────────────────────────────────────────────────────────┐
│ Header (20 bytes)                                         │
├──────────┬─────────┬──────┬──────────┬───────────────────┤
│ Magic    │ Version │ Type │ Flags    │ Connection ID     │
│ (2 bytes)│ (1 byte)│(1 b) │ (1 byte) │ (4 bytes)         │
│ 0xA1 0x7F│  0x01   │      │          │                   │
├──────────┴─────────┴──────┴──────────┴───────────────────┤
│ Sequence (4 bytes) │ Payload Length │ Reserved │ CRC-32   │
│                    │ (2 bytes)      │ (1 byte) │ (4 bytes)│
│                    │ (max 1180)     │  0x00    │ bytes 0-15│
├────────────────────┴────────────────┴──────────┴─────────┤
│ Payload (variable, 0-1180 bytes)                          │
└───────────────────────────────────────────────────────────┘

Total: 20 byte header + up to 1180 byte payload = max 1200 bytes per UDP datagram
```

**Design rationale for 1200-byte max datagram**: Internet path MTU is typically 1500 bytes. After IP header (20 bytes) and UDP header (8 bytes), we have 1472 bytes available. We cap the entire datagram at 1200 bytes to leave headroom for IP options, tunneling overhead (VPNs add 20-60 bytes), and to match QUIC's conservative choice. This avoids IP fragmentation, which causes catastrophic packet loss through NATs and firewalls.

### Header Fields

| Offset | Field | Size | Description |
|--------|-------|------|-------------|
| 0-1 | Magic | 2 bytes | `0xA1 0x7F` — identifies our protocol, allows quick rejection of stray packets |
| 2 | Version | 1 byte | Protocol version (`0x01`), enables future evolution |
| 3 | Type | 1 byte | Message type (see table below) |
| 4 | Flags | 1 byte | Bit flags: `0x01`=encrypted, `0x02`=compressed, `0x04`=relay |
| 5-8 | Connection ID | 4 bytes | Random ID assigned during handshake; enables multiplexing and rejects stale packets from old sessions |
| 9-12 | Sequence | 4 bytes | Packet sequence number (monotonically increasing counter per direction, wraps at 2^32) |
| 13-14 | Payload Length | 2 bytes | Length of payload in bytes (0-1180) |
| 15 | Reserved | 1 byte | Reserved for future use (`0x00`) |
| 16-19 | Header CRC-32 | 4 bytes | CRC-32 over header bytes 0-15; detects corruption before parsing payload |

**Byte order**: All multi-byte fields are big-endian (network byte order).

### Message Types

| Type | Code | Direction | Description |
|------|------|-----------|-------------|
| `PUNCH` | 0x01 | Both | Hole punching packet |
| `PUNCH_ACK` | 0x02 | Both | Hole punch acknowledgment |
| `KEEPALIVE` | 0x03 | Both | NAT mapping keepalive (every 15s) |
| `KEEPALIVE_ACK` | 0x04 | Both | Keepalive response |
| `FILE_OFFER` | 0x10 | S→R | File metadata: name, size, SHA-256, chunk count |
| `FILE_ACCEPT` | 0x11 | R→S | Accept transfer, optionally with resume offset |
| `FILE_REJECT` | 0x12 | R→S | Reject transfer with reason code |
| `DATA` | 0x20 | S→R | File data (see DATA payload format below) |
| `SACK` | 0x21 | R→S | Selective acknowledgment with received ranges |
| `COMPLETE` | 0x30 | S→R | All chunks sent; includes final SHA-256 |
| `VERIFIED` | 0x31 | R→S | File integrity verified |
| `CANCEL` | 0x32 | Both | Gracefully abort transfer with reason code |
| `ERROR` | 0xFF | Both | Error with code (uint16) + message (UTF-8) |

### DATA Payload Format

```
┌──────────────┬────────────────┬──────────────────────┐
│ Chunk Index  │ Byte Offset    │ Chunk Data           │
│ (4 bytes)    │ (8 bytes)      │ (up to 1168 bytes)   │
└──────────────┴────────────────┴──────────────────────┘
```

- **Chunk Index**: Sequential chunk number (0-based)
- **Byte Offset**: Absolute byte position in the file (supports files up to 2^63 bytes). Enables resume and removes dependency on fixed chunk sizes.
- **Chunk Data**: Raw file bytes. Last chunk may be smaller.

Max chunk data per packet: 1180 (max payload) - 12 (data header) = **1168 bytes** (theoretical max). In practice, `ReliableChannel` uses a conservative `MAX_CHUNK_DATA = 1100` bytes.

The sliding window and ACK mechanism operate at the **packet level**, not the file-chunk level.

### SACK Payload Format

Uses **Selective Acknowledgment** (not cumulative ACK). This avoids the ambiguity between Go-Back-N and Selective Repeat.

```
┌────────────────┬──────────────────────────────────────┐
│ Cumulative ACK │ SACK Ranges (variable)               │
│ (4 bytes)      │                                      │
├────────────────┼──────────┬──────────┬────────────────┤
│ All packets up │ Range 1  │ Range 2  │ ... up to N    │
│ to this seq#   │ start/end│ start/end│                │
│ are received   │ (8 bytes)│ (8 bytes)│                │
└────────────────┴──────────┴──────────┴────────────────┘

Each SACK range: start_seq (4 bytes) + end_seq (4 bytes)
Max ranges per packet: (1180 - 4) / 8 = 147 ranges
```

The receiver sends SACKs:
- After every 2 received packets (delayed ACK), OR
- Immediately on detecting a gap (for fast retransmission), OR
- At least once every 10ms (timer-based)

### ERROR Payload Format

```
┌──────────────┬────────────────────────────┐
│ Error Code   │ Message (UTF-8)            │
│ (2 bytes)    │ (up to 1178 bytes)         │
└──────────────┴────────────────────────────┘
```

| Error Code | Meaning |
|------------|---------|
| 0x0001 | Session not found |
| 0x0002 | Authentication failed |
| 0x0003 | Transfer rejected |
| 0x0004 | Disk full |
| 0x0005 | File not found |
| 0x0006 | Checksum mismatch |
| 0x0007 | Timeout |
| 0x0008 | Protocol version mismatch |
| 0xFFFF | Unknown error |

## Reliable UDP Transport Layer

Since UDP provides no delivery guarantees, we implement reliability on top. This layer handles everything between "raw UDP socket" and "application file transfer."

### Sequence Numbers

- Each direction has an independent sequence counter starting at 0
- Sequence numbers are **per-packet** (not per-byte like TCP)
- Wraparound: When sequence reaches `2^32 - 1`, it wraps to `0`. The window algorithm handles this using modular arithmetic

### Retransmission

**RTT Estimation** (Karn's algorithm + EWMA):

```
On each non-retransmitted packet's ACK:
  sample_rtt = now - send_time
  srtt = 0.875 * srtt + 0.125 * sample_rtt
  rttvar = 0.75 * rttvar + 0.25 * |sample_rtt - srtt|
  rto = srtt + 4 * rttvar
  rto = clamp(rto, 200ms, 10s)

On retransmission timeout:
  rto = rto * 2  (exponential backoff)
  Do NOT update srtt from retransmitted packets (Karn's algorithm)
```

**Initial RTO**: 1000ms (before any RTT samples are collected)

### Congestion Control (AIMD)

Flow control (don't overwhelm the receiver) and congestion control (don't overwhelm the network) are separate mechanisms that both constrain the sender.

```
effective_window = min(cwnd, receiver_window)

Initialization:
  cwnd = 32 packets  (aggressive start for high-BDP paths)
  ssthresh = 2048 packets

Slow Start (cwnd < ssthresh):
  On each ACK: cwnd += 1 packet
  (exponential growth: doubles each RTT)

Congestion Avoidance (cwnd >= ssthresh):
  On each ACK: cwnd += 1/cwnd packets
  (linear growth: +1 packet per RTT)

On packet loss (detected by 3 duplicate SACKs or RTO):
  ssthresh = max(cwnd / 2, 2)
  cwnd = ssthresh          (multiplicative decrease)
  Retransmit lost packet
```

**Receiver window**: The receiver advertises available buffer space in each SACK via an **adaptive window** (see below). The sender never has more than `min(cwnd, receiver_window)` unacknowledged packets in flight.

### Adaptive Receiver Window

The receiver window is not a fixed size — it adapts based on network conditions:

```
Initialization:
  window = 256 packets
  max_window = 256

Growth (healthy network):
  After every 128 consecutive in-order deliveries:
    max_window = min(max_window + 32, 512)

Shrink (buffer pressure):
  If out-of-order buffer > 50% of max_window:
    max_window = max(max_window / 2, 32)

Advertised window = max_window - buffered_count
```

This prevents the sender from overwhelming the receiver during packet loss while allowing growth on clean links. The conservative ceiling of 512 packets (~564 KB at 1100 bytes/chunk) yields ~9.5 MB/s WAN throughput with 0 retransmissions.

### Sliding Window

```
Window: effective_window = min(cwnd, receiver_window) packets

Sender view:
  base_seq ──────────────────────────────────── next_seq
  │◄──── acknowledged ────►│◄── in flight ──►│◄── can send ──►│
  ▼                        ▼                 ▼                ▼
  [ACKed][ACKed][ACKed]   [sent][sent][sent] [ready][ready]   │
                                                               │
                                         effective_window ─────┘

On SACK(cumulative=N, ranges=[...]):
  1. Advance base_seq to N+1 (slide window)
  2. Mark selectively ACKed packets
  3. Detect gaps → schedule retransmission
  4. If new packets can be sent (window space available), send them
```

### PacketRouter (I/O Loop)

All DTLS send/receive happens on a single dedicated thread — the `PacketRouter`. External callers enqueue outbound data via a lock-free `ConcurrentLinkedQueue`.

Each iteration of the loop:
1. Drain send queue (actual DTLS sends)
2. Blocking receive with 10ms timeout
3. Dispatch received packet to registered handler
4. Drain send queue again (handlers may have enqueued responses)
5. Run tick callback (retransmission checks, SACK timer)
6. Drain send queue again (tick may have enqueued retransmissions)
7. Send KEEPALIVE if no data sent in 15 seconds
8. Check for connection death (no data received in 45 seconds)

This single-threaded design eliminates locking on the DTLS transport layer and guarantees serialized access to the socket.

### NAT Keepalive

UDP NAT mappings typically expire after 30-120 seconds of inactivity. To prevent this:

- Send `KEEPALIVE` every **15 seconds** if no data has been sent in that interval
- Respond to `KEEPALIVE` with `KEEPALIVE_ACK` immediately
- After 45 seconds with no data received, consider the connection dead
- Keepalive packets do not consume sequence numbers (use seq=0)

## NAT Compatibility

### NAT Type Pair Matrix

Hole punching success depends on the **combination** of both peers' NAT types:

| | Full Cone | Restricted | Port Restricted | Symmetric |
|---|---|---|---|---|
| **Full Cone** | Always | Always | Always | Always |
| **Restricted** | Always | Always | Always | Usually* |
| **Port Restricted** | Always | Always | Usually | Usually* |
| **Symmetric** | Always | Usually* | Usually* | Never |

*"Usually" means our implementation detects the port change (accepts PUNCH from expected IP but any port) and updates the endpoint dynamically. This makes symmetric-to-non-symmetric pairs work reliably. Only symmetric-to-symmetric remains unsolvable.

**Fallback strategy**: If hole punching fails after 10 seconds, the connection attempt fails. Relay mode through the coordination server is planned but not yet implemented.

### IPv6 Consideration

IPv6 hosts typically have globally routable addresses and do not need hole punching. The protocol should:

1. Attempt IPv6 direct connection first (no hole punching needed, just firewall traversal)
2. Fall back to IPv4 hole punching if IPv6 is unavailable or firewalled
3. The coordination server should collect both IPv4 and IPv6 endpoints from each peer

## Encryption (DTLS)

**Encryption is mandatory**, not optional. All P2P data after the hole-punch phase is encrypted with DTLS 1.2 (RFC 6347) using PSK authentication.

### Why DTLS

- Designed for UDP (unlike TLS which requires TCP)
- Provides confidentiality, integrity, and replay protection
- Handles packet reordering and loss at the handshake level

### Implementation

Uses **BouncyCastle** (`bcprov-jdk18on` + `bctls-jdk18on`) for DTLS, not JDK's `SSLEngine`. JDK's DTLS support has poor documentation and limited PSK cipher suite availability.

**PSK authentication**: Both peers use the same pre-shared key (`--psk` flag). The PSK identity is the session ID. No certificates are needed — the shared secret authenticates both sides.

**Role determination**: After hole punch, the peer with the lower local port becomes the DTLS client; the other becomes the server. This is deterministic — no negotiation needed.

### DTLS Handshake Flow

```
After hole punch succeeds:

Peer A (DTLS Client, lower port)        Peer B (DTLS Server, higher port)
        │                                        │
        │── NAT keepalive (3x 0x00 byte) ──────►│  (keeps NAT mapping alive)
        │                                        │
        │──── ClientHello ──────────────────────►│
        │◄─── HelloVerifyRequest ────────────────│  (cookie for DoS protection)
        │──── ClientHello (with cookie) ────────►│
        │◄─── ServerHello ──────────────────────│  (PSK cipher suite selected)
        │──── ClientKeyExchange + Finished ─────►│
        │◄─── Finished ─────────────────────────│
        │                                        │
        │◄═══ Encrypted data channel ═══════════►│
```

### DTLS Retry and Deadline

The DTLS handshake can fail due to NAT mapping expiry, packet loss, or stale packets from previous sessions. `PeerConnection` retries up to 3 times with exponential backoff (500ms, 1s, 1.5s).

Each handshake attempt has a **30-second hard deadline** enforced by the transport layer. BouncyCastle internally retries on `SocketTimeoutException` indefinitely (it extends `InterruptedIOException`, which BouncyCastle treats as recoverable). The deadline throws a hard `IOException` to force abort.

### Non-DTLS Packet Filter

After hole punch, stale PUNCH/PUNCH_ACK packets and NAT keepalive bytes may be queued in the socket buffer. These are filtered by the transport layer before reaching BouncyCastle:

```
Valid DTLS content types (first byte):
  0x14 = ChangeCipherSpec
  0x15 = Alert
  0x16 = Handshake
  0x17 = ApplicationData

Any packet with first byte outside 0x14-0x17 is silently dropped.
```

### BouncyCastle Gotchas

- `PSKTlsClient`/`PSKTlsServer` **must** override `getSupportedVersions()` using `ProtocolVersion.DTLSv12.only()` — NOT `new ProtocolVersion[]{ProtocolVersion.DTLSv12}`. The latter causes `internal_error(80)` during `generateClientHello`.
- BouncyCastle JARs are signed. Maven Shade plugin must exclude `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA` or you get `SecurityException: Invalid signature file digest`.
- `TlsTimeoutException extends IOException` (NOT `InterruptedIOException`). Throwing it from `DatagramTransport.receive()` triggers `recordLayer.fail(80)` which kills the DTLS session. Let `SocketTimeoutException` propagate directly instead.

## Coordination Server Protocol

The coordination server uses the **same binary packet format** as P2P communication. This means one parser handles everything.

### Coordination Message Types

| Type | Code | Direction | Description |
|------|------|-----------|-------------|
| `COORD_REGISTER` | 0xC0 | C→S | Register for a session |
| `COORD_CHALLENGE` | 0xC1 | S→C | Challenge nonce for authentication |
| `COORD_AUTH` | 0xC2 | C→S | HMAC response to challenge |
| `COORD_OK` | 0xC3 | S→C | Registration accepted |
| `COORD_PEER_INFO` | 0xC4 | S→C | Peer endpoint information |
| `COORD_KEEPALIVE` | 0xC5 | C→S | Session keepalive (every 15s) |
| `COORD_RELAY` | 0xC6 | Both | Relay-wrapped packet (fallback) |
| `COORD_PING` | 0xC7 | C→S | Health check |
| `COORD_PONG` | 0xC8 | S→C | Health check response |
| `COORD_ERROR` | 0xCF | S→C | Error with code + message |

### Authentication Flow

To prevent abuse and session hijacking, registration requires a challenge-response:

1. Client sends `COORD_REGISTER` with session ID
2. Server responds with `COORD_CHALLENGE` containing a random 32-byte nonce
3. Client computes `HMAC-SHA256(pre_shared_key, nonce + session_id)` and sends `COORD_AUTH`
4. Server verifies HMAC and responds with `COORD_OK`

**Anti-amplification**: The server never sends a response larger than the request until after authentication. The `COORD_CHALLENGE` response is small. `COORD_PEER_INFO` (which contains IP addresses) is only sent after successful auth.

### Session Rules

- Each session supports exactly **2 peers** (sender + receiver)
- Sessions expire after `--session-timeout` seconds of inactivity (default: 300s)
- A third peer attempting to join an occupied session receives `COORD_ERROR`
- Session IDs must be at least 128 bits (22+ characters in base62) to prevent guessing

## Relay Protocol (Planned — Not Yet Implemented)

When hole punching fails (symmetric-to-symmetric NAT), the coordination server would relay P2P traffic. This is planned to be transparent to the transfer layer.

### How Relay Works

```
Sender ──── COORD_RELAY(wrapped packet) ────► Coord Server
                                                   │
                                                   │ unwrap, forward
                                                   ▼
Receiver ◄── COORD_RELAY(wrapped packet) ──── Coord Server
```

The `COORD_RELAY` payload contains the original P2P packet (including its header). The coordination server extracts the connection ID to determine the recipient and forwards the packet.

### Relay Limitations

- **Bandwidth**: Relay traffic consumes server bandwidth. Rate-limit to 10 Mbps per session.
- **Latency**: Adds one hop (typically 20-50ms additional RTT)
- **Cost**: Server bandwidth is not free. Large transfers via relay should warn the user.
- **Server sizing**: A relay-capable server needs more resources than coordination-only (see Deployment section)

## File Transfer Protocol

### FILE_OFFER Payload

```
┌──────────────┬───────────────┬───────────────┬──────────────────┐
│ Transfer ID  │ File Size     │ SHA-256 Hash  │ Filename         │
│ (16 bytes,   │ (8 bytes,     │ (32 bytes)    │ (UTF-8, length-  │
│  UUID)       │  uint64)      │               │  prefixed, 2+N)  │
└──────────────┴───────────────┴───────────────┴──────────────────┘
```

The Transfer ID persists across sessions for resume support.

### FILE_ACCEPT Payload (with Resume)

```
┌──────────────┬───────────────┐
│ Transfer ID  │ Resume Offset │
│ (16 bytes)   │ (8 bytes)     │
└──────────────┴───────────────┘

Resume Offset = 0 means start from beginning.
Resume Offset = N means "I already have bytes 0 through N-1."
```

### Transfer Resume

To resume an interrupted transfer:

1. The receiver persists a `.p2p-partial` sidecar file alongside the partial download
2. On reconnection, the receiver sends `FILE_ACCEPT` with `Resume Offset = bytes_written`
3. The sender skips packets covering bytes before the resume offset
4. The receiver verifies the filename, file size, and SHA-256 match the previous attempt
5. If the source file has changed (different SHA-256), the receiver discards partial data and starts fresh

### `.p2p-partial` Sidecar File Format

```
┌──────────────┬──────────┬───────────┬───────────┬──────────────┬──────────────┐
│ Magic        │ Version  │ File Size │ SHA-256   │ Bytes Written│ Filename     │
│ (4 bytes)    │ (4 bytes)│ (8 bytes) │ (32 bytes)│ (8 bytes)    │ (2+N bytes)  │
│ 0x50325052   │    1     │           │           │              │ len + UTF-8  │
└──────────────┴──────────┴───────────┴───────────┴──────────────┴──────────────┘
```

The sidecar file is saved periodically during transfer and deleted on successful completion.

### Chunked Transfer

Each DATA packet carries up to 1100 bytes of file data (conservative limit within the 1180-byte max payload, accounting for the 12-byte data header). The sliding window and SACK mechanism operate at the **packet level**.

```
File: document.pdf (10 MB)
Chunk data per packet: 1100 bytes
Total packets: ceil(10,485,760 / 1100) = 9533 packets

Packets:
┌──────┬──────┬──────┬─────┬──────┐
│pkt 0 │pkt 1 │pkt 2 │ ... │ 9532 │
│1100 B│1100 B│1100 B│     │ rem  │
└──────┴──────┴──────┴─────┴──────┘
  │
  ▼ SACKs operate at packet level
  SACK(cumulative=55, ranges=[58-60])
```

### Sliding Window with Congestion Control

```
Initial state:
  cwnd = 32, ssthresh = 2048, rto = 1000ms

Phase 1 — Slow Start:
  cwnd doubles each RTT: 32 → 64 → 128 → ... → 2048

Phase 2 — Congestion Avoidance (cwnd >= ssthresh):
  cwnd grows by 1 per RTT: 2048 → 2049 → 2050 → ...

On 3 duplicate SACKs (fast retransmit):
  ssthresh = cwnd/2, cwnd = ssthresh
  Retransmit missing packet immediately

On RTO timeout:
  ssthresh = cwnd/2, cwnd = ssthresh
  Retransmit lost packet
```

## Java Implementation

### Dependencies (Maven)

```xml
<dependencies>
    <!-- CLI argument parsing -->
    <dependency>
        <groupId>info.picocli</groupId>
        <artifactId>picocli</artifactId>
        <version>4.7.6</version>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.16</version>
    </dependency>
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-simple</artifactId>
        <version>2.0.16</version>
    </dependency>

    <!-- Bouncy Castle for DTLS (PSK cipher suites) -->
    <dependency>
        <groupId>org.bouncycastle</groupId>
        <artifactId>bcprov-jdk18on</artifactId>
        <version>1.79</version>
    </dependency>
    <dependency>
        <groupId>org.bouncycastle</groupId>
        <artifactId>bctls-jdk18on</artifactId>
        <version>1.79</version>
    </dependency>
</dependencies>
```

The protocol is fully binary — no JSON. Build produces a fat JAR via `maven-shade-plugin` (must exclude BouncyCastle signature files `META-INF/*.SF`, `*.DSA`, `*.RSA`).

### Core Classes

```
src/main/java/com/alterante/p2p/
├── Main.java                     # CLI entry point (picocli, subcommands: server/send/receive)
├── command/
│   ├── SendCommand.java          # Send file command (--file/-f, --session/-s, --psk, --server)
│   ├── ReceiveCommand.java       # Receive file command (--output/-o, --session/-s, --psk, --server)
│   └── CoordServerCommand.java   # Run coordination server (--port/-p, --psk, --session-timeout)
├── net/
│   ├── CoordClient.java          # Coordination server client (register, auth, peer exchange)
│   ├── CoordServer.java          # Coordination server implementation (sessions, HMAC-SHA256)
│   ├── HolePuncher.java          # UDP hole punching (single-threaded, symmetric NAT support)
│   ├── HolePunchResult.java      # Result record (success/fail, confirmed address, elapsed time)
│   ├── PeerConnection.java       # Connection lifecycle (coord → punch → DTLS → router)
│   ├── PeerState.java            # Connection state enum (INIT → REGISTERING → PUNCHING → ...)
│   ├── DtlsHandler.java          # DTLS 1.2 via BouncyCastle PSK (includes UdpDatagramTransport)
│   └── PacketRouter.java         # Single-threaded I/O loop (10ms tick, send queue, keepalive)
├── transport/
│   ├── ReliableChannel.java      # Reliable transport layer (combines all transport components)
│   ├── CongestionControl.java    # AIMD congestion control (cwnd=32, ssthresh=2048)
│   ├── RttEstimator.java         # RTT measurement + RTO calculation (Karn's algorithm)
│   ├── SlidingWindow.java        # Sender window with per-packet tracking + SACK processing
│   ├── ReceiveBuffer.java        # Receiver reorder buffer with adaptive window (256→512)
│   ├── SackInfo.java             # SACK data: cumulative ACK + ranges + receiver window
│   └── SackRange.java            # A single SACK range (start_seq, end_seq)
├── protocol/
│   ├── Packet.java               # Packet structure (type, flags, connId, seq, payload)
│   ├── PacketType.java           # Message type enum (all P2P + coordination types)
│   ├── PacketCodec.java          # Encode/decode binary packets (20-byte header + CRC-32)
│   ├── PacketException.java      # Codec error (bad magic, CRC mismatch, etc.)
│   └── FileMetadata.java         # File info (name, size, SHA-256 hash)
└── transfer/
    ├── FileSender.java           # File sending (offer → accept → pump DATA → complete)
    ├── FileReceiver.java         # File receiving (auto-accept, disk writes, SHA-256 verify)
    ├── TransferProgress.java     # Progress tracking + ETA + speed calculation
    ├── TransferState.java        # Transfer lifecycle state enum
    └── PartialTransferState.java # Resume state (.p2p-partial sidecar files)
```

### Hole Punching Implementation

The hole puncher uses a **single-threaded blocking loop** — no separate receiver thread. The socket timeout is set to the punch interval (100ms), so each iteration alternates between sending a PUNCH and checking for a response.

```java
public class HolePuncher {
    private final DatagramSocket socket;
    private static final int PUNCH_INTERVAL_MS = 100;
    private static final int PUNCH_TIMEOUT_MS = 10_000;

    public HolePunchResult punch() {
        long deadline = System.currentTimeMillis() + PUNCH_TIMEOUT_MS;
        socket.setSoTimeout(PUNCH_INTERVAL_MS);

        while (System.currentTimeMillis() < deadline) {
            // Send PUNCH packet
            sendPunchPacket(remoteEndpoint);

            // Try to receive (times out after 100ms if nothing arrives)
            try {
                DatagramPacket pkt = receive();
                InetSocketAddress from = (InetSocketAddress) pkt.getSocketAddress();

                // Accept from expected IP, but any port (symmetric NAT support)
                if (!from.getAddress().equals(remoteEndpoint.getAddress())) {
                    continue; // wrong peer
                }

                // Port changed? Symmetric NAT detected — update endpoint
                if (from.getPort() != remoteEndpoint.getPort()) {
                    remoteEndpoint = from;
                }

                if (isPunchPacket(pkt)) {
                    // Receiving a PUNCH = proof of bidirectional connectivity
                    sendPunchAck(from);
                    return HolePunchResult.success(from);
                } else if (isPunchAck(pkt)) {
                    return HolePunchResult.success(from);
                }
            } catch (SocketTimeoutException e) {
                // Expected — send another PUNCH next iteration
            }
        }

        return HolePunchResult.failed();
    }
}
```

**Key design decision**: Receiving a PUNCH packet is treated as success (not just PUNCH_ACK). If Peer A can receive a PUNCH from Peer B, the NAT mapping is already open in both directions. Waiting for PUNCH_ACK would add an unnecessary round trip.

## Security

### Mandatory Protections

| Layer | Protection | Mechanism |
|-------|------------|-----------|
| Coordination | Anti-amplification | Challenge-response before sending peer info |
| Coordination | Session authentication | HMAC-SHA256 with pre-shared key |
| Coordination | Session limit | Exactly 2 peers per session |
| P2P | Confidentiality | DTLS 1.2 encryption (all data after handshake) |
| P2P | Integrity | DTLS record MAC + per-packet header CRC-32 |
| P2P | Peer authentication | DTLS-PSK (both peers must know the pre-shared key) |
| P2P | Replay protection | DTLS anti-replay window + connection ID |
| Transfer | File integrity | SHA-256 over entire file, verified after transfer |

### Session ID Requirements

- Minimum 128 bits of entropy (22 characters in base62, or 32 hex characters)
- Generated using `SecureRandom`
- Transmitted over the authenticated coordination channel only
- Not reusable — each transfer gets a new session ID

## Trade-offs vs WebRTC

| Aspect | This Approach | WebRTC |
|--------|---------------|--------|
| NAT traversal | Manual hole punch + relay | ICE framework (STUN/TURN) — battle-tested |
| Encryption | DTLS via BouncyCastle (PSK) | Built-in DTLS, automatic |
| Reliability | Must build reliable UDP from scratch | SCTP built-in, but limited window size |
| Throughput | Tunable (we control the congestion window) | Capped by SCTP window (~20 Mbps at 50ms RTT) |
| Complexity | High — reimplementing well-known protocols | Medium — using existing stack, but library maturity risk |
| Portability | Pure Java (no native libs) | Requires native WebRTC bindings |
| Debuggability | Full visibility into every packet | Opaque SCTP/DTLS internals |
| Time to production | Longer (more to build and test) | Shorter (if library works) |

## Development Phases

### Phase 1: Connectivity + Encryption (Foundation) ✓

Error handling, timeouts, and encryption are **not polish** — they are foundational. Build them from day one.

- [x] Binary packet codec (encode/decode/CRC-32 validation)
- [x] Coordination server with authentication (challenge-response)
- [x] UDP hole punching between two peers
- [x] DTLS handshake after hole punch
- [x] Verify encrypted connectivity with KEEPALIVE/KEEPALIVE_ACK
- [x] Basic error handling and state machine for connection lifecycle

### Phase 2: Reliable Transport ✓

- [x] Sequence numbers, SACK generation and processing
- [x] RTT estimation (Karn's algorithm + EWMA)
- [x] Retransmission with adaptive timeout
- [x] AIMD congestion control (slow start, congestion avoidance)
- [x] Sliding window with receiver flow control
- [x] Receiver-side reorder buffer with adaptive window

### Phase 3: File Transfer ✓

- [x] FILE_OFFER / FILE_ACCEPT negotiation (auto-accept)
- [x] Chunked file sending through reliable transport
- [x] Receiver disk I/O with backpressure (advertise receiver window based on buffer state)
- [x] Progress reporting with ETA
- [x] SHA-256 verification
- [x] Transfer resume with `.p2p-partial` state files
- [ ] CANCEL message for graceful abort

### Phase 4: Hardening + Features

- [ ] Relay fallback through coordination server
- [ ] IPv6 support (direct connection without hole punching)
- [ ] Multi-address hole punching (try all local interfaces)
- [ ] Multiple file transfer in one session
- [x] CLI interface with picocli
- [ ] Integration tests across NAT simulator

## Coordination Server Deployment

The coordination server is lightweight and easily deployable to any VPS.

### Requirements

- Any Linux VPS (Ubuntu 22.04+ recommended)
- 512 MB RAM minimum
- Java 17+ runtime
- Open UDP port (default: 9000)

### VPS Providers (Budget Options)

| Provider | Plan | Cost | Notes |
|----------|------|------|-------|
| DigitalOcean | Basic Droplet | $4/mo | 512 MB RAM, 10 GB SSD |
| Vultr | Cloud Compute | $5/mo | 1 GB RAM, 25 GB SSD |
| Linode | Nanode | $5/mo | 1 GB RAM, 25 GB SSD |
| Hetzner | CX11 | €3.29/mo | 2 GB RAM, 20 GB SSD |
| Oracle Cloud | Free Tier | Free | 1 GB RAM (always free) |

### Quick Deploy Script

```bash
#!/bin/bash
# deploy-coord-server.sh

set -e

# Install Java
sudo apt update
sudo apt install -y openjdk-17-jre-headless

# Create app directory
sudo mkdir -p /opt/p2p-coord
cd /opt/p2p-coord

# Download the coordination server JAR (update URL as needed)
# sudo wget https://your-release-url/p2p-coord.jar

# Create systemd service
sudo tee /etc/systemd/system/p2p-coord.service > /dev/null <<EOF
[Unit]
Description=P2P Coordination Server
After=network.target

[Service]
Type=simple
User=nobody
Group=nogroup
WorkingDirectory=/opt/p2p-coord
ExecStart=/usr/bin/java -jar alt-p2p-0.1.0-SNAPSHOT.jar server --port 9000 --psk ${PSK}
Restart=always
RestartSec=5

# Security hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
EOF

# Open firewall port
sudo ufw allow 9000/udp

# Enable and start service
sudo systemctl daemon-reload
sudo systemctl enable p2p-coord
sudo systemctl start p2p-coord

echo "Coordination server deployed!"
echo "Status: sudo systemctl status p2p-coord"
echo "Logs:   sudo journalctl -u p2p-coord -f"
```

### Firewall Configuration

```bash
# Ubuntu/Debian (ufw)
sudo ufw allow 9000/udp

# CentOS/RHEL (firewalld)
sudo firewall-cmd --permanent --add-port=9000/udp
sudo firewall-cmd --reload

# Raw iptables
sudo iptables -A INPUT -p udp --dport 9000 -j ACCEPT
```

### Docker Deployment

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/alt-p2p-0.1.0-SNAPSHOT.jar .
EXPOSE 9000/udp
ENV PSK=changeme
CMD ["java", "-jar", "alt-p2p-0.1.0-SNAPSHOT.jar", "server", "--port", "9000", "--psk", "${PSK}"]
```

```bash
docker build -t alt-p2p .
docker run -d --name alt-p2p -p 9000:9000/udp -e PSK=mysecret alt-p2p
```

### Docker Compose

```yaml
version: '3.8'
services:
  coord-server:
    build: .
    ports:
      - "9000:9000/udp"
    restart: unless-stopped
    deploy:
      resources:
        limits:
          memory: 256M
```

### Monitoring

```bash
# Check if running
sudo systemctl status p2p-coord

# View logs
sudo journalctl -u p2p-coord -f

# Check UDP port is listening
sudo ss -ulnp | grep 9000
```

### Health Check

```bash
# Send a COORD_PING and expect COORD_PONG (binary protocol)
# Health check endpoint is planned but not yet implemented as a CLI subcommand
```

### Configuration Options

| Flag | Default | Description |
|------|---------|-------------|
| `--port`, `-p` | 9000 | UDP port to listen on |
| `--psk` | (required) | Pre-shared key for authentication |
| `--session-timeout` | 300 | Session expiry in seconds |

### DNS Setup (Optional)

```
coord.yourdomain.com  A     203.0.113.10
coord.yourdomain.com  AAAA  2001:db8::1
```

```bash
java -jar alt-p2p-0.1.0-SNAPSHOT.jar send -f file.zip -s mysession --psk secret --server coord.yourdomain.com:9000
```

## Running the Application

### Start Coordination Server

```bash
java -jar alt-p2p-0.1.0-SNAPSHOT.jar server --psk mysecret
```

### Sender

```bash
java -jar alt-p2p-0.1.0-SNAPSHOT.jar send \
  -s abc123 --psk mysecret --server coord.example.com:9000 -f myfile.zip
> Registered with coordination server
> Waiting for peer...
> Peer found: 203.0.113.45:54321
> Hole punch succeeded in 342ms
> DTLS handshake complete. Encrypted channel established.
> Sending: myfile.zip (156 MB)
> [████████████████████] 100% | 9.5 MB/s | 156 MB / 156 MB
> Transfer complete. SHA-256 verified by receiver.
```

### Receiver

```bash
java -jar alt-p2p-0.1.0-SNAPSHOT.jar receive \
  -s abc123 --psk mysecret --server coord.example.com:9000 -o ./downloads
> Registered with coordination server
> Waiting for peer...
> Peer found: 198.51.100.22:12345
> Hole punch succeeded in 342ms
> DTLS handshake complete. Encrypted channel established.
> Receiving: myfile.zip (156 MB)
> [████████████████████] 100% | 9.5 MB/s
> File saved: ./downloads/myfile.zip
> SHA-256 verified ✓
```

## Resources

- [Peer-to-Peer Communication Across NATs (Ford, Srisuresh, Kegel)](https://bford.info/pub/net/p2pnat/) — the foundational paper on hole punching
- [UDP Hole Punching (Wikipedia)](https://en.wikipedia.org/wiki/UDP_hole_punching)
- [RFC 6347 — DTLS 1.2](https://tools.ietf.org/html/rfc6347)
- [RFC 5389 — STUN](https://tools.ietf.org/html/rfc5389) — for understanding NAT behavior classification
- [TCP Congestion Control (RFC 5681)](https://tools.ietf.org/html/rfc5681) — basis for our AIMD implementation
- [Karn's Algorithm](https://en.wikipedia.org/wiki/Karn%27s_algorithm)
- [picocli — CLI framework](https://picocli.info/)
- [Bouncy Castle DTLS](https://www.bouncycastle.org/java.html)

package com.alterante.p2p.protocol;

import java.util.Arrays;

/**
 * Immutable representation of a protocol packet.
 *
 * Wire format (20-byte header + payload):
 * <pre>
 * Offset  Field           Size
 * 0-1     Magic           2 bytes (0xA1 0x7F)
 * 2       Version         1 byte
 * 3       Type            1 byte
 * 4       Flags           1 byte
 * 5-8     Connection ID   4 bytes
 * 9-12    Sequence        4 bytes
 * 13-14   Payload Length   2 bytes (max 1180)
 * 15      Reserved        1 byte (0x00)
 * 16-19   CRC-32          4 bytes (over bytes 0-15)
 * 20+     Payload         0-1180 bytes
 * </pre>
 */
public final class Packet {

    public static final int HEADER_SIZE = 20;
    public static final int MAX_PAYLOAD = 1180;
    public static final int MAX_DATAGRAM = HEADER_SIZE + MAX_PAYLOAD; // 1200

    public static final byte VERSION = 0x01;

    // Flags
    public static final byte FLAG_ENCRYPTED  = 0x01;
    public static final byte FLAG_COMPRESSED = 0x02;
    public static final byte FLAG_RELAY      = 0x04;

    private final PacketType type;
    private final byte flags;
    private final int connectionId;
    private final int sequence;
    private final byte[] payload;

    public Packet(PacketType type, byte flags, int connectionId, int sequence, byte[] payload) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (payload != null && payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + payload.length + " > " + MAX_PAYLOAD);
        }
        this.type = type;
        this.flags = flags;
        this.connectionId = connectionId;
        this.sequence = sequence;
        this.payload = payload != null ? Arrays.copyOf(payload, payload.length) : new byte[0];
    }

    /** Convenience constructor with no flags, connId, or sequence. */
    public Packet(PacketType type, byte[] payload) {
        this(type, (byte) 0, 0, 0, payload);
    }

    /** Convenience constructor with no payload. */
    public Packet(PacketType type) {
        this(type, (byte) 0, 0, 0, null);
    }

    public PacketType type()        { return type; }
    public byte flags()             { return flags; }
    public int connectionId()       { return connectionId; }
    public int sequence()           { return sequence; }
    public byte[] payload()         { return Arrays.copyOf(payload, payload.length); }
    public int payloadLength()      { return payload.length; }

    @Override
    public String toString() {
        return String.format("Packet[type=%s, flags=0x%02X, connId=0x%08X, seq=%d, payload=%d bytes]",
                type, flags, connectionId, sequence, payload.length);
    }
}

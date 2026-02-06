package com.alterante.p2p.protocol;

/**
 * All message types in the P2P protocol.
 * Single byte type code in the packet header.
 */
public enum PacketType {

    // P2P hole punching
    PUNCH          ((byte) 0x01),
    PUNCH_ACK      ((byte) 0x02),

    // P2P keepalive
    KEEPALIVE      ((byte) 0x03),
    KEEPALIVE_ACK  ((byte) 0x04),

    // File transfer (Phase 2+)
    FILE_OFFER     ((byte) 0x10),
    FILE_ACCEPT    ((byte) 0x11),
    FILE_REJECT    ((byte) 0x12),
    DATA           ((byte) 0x20),
    SACK           ((byte) 0x21),
    COMPLETE       ((byte) 0x30),
    VERIFIED       ((byte) 0x31),
    CANCEL         ((byte) 0x32),

    // Coordination server
    COORD_REGISTER  ((byte) 0xC0),
    COORD_CHALLENGE ((byte) 0xC1),
    COORD_AUTH      ((byte) 0xC2),
    COORD_OK        ((byte) 0xC3),
    COORD_PEER_INFO ((byte) 0xC4),
    COORD_KEEPALIVE ((byte) 0xC5),
    COORD_RELAY     ((byte) 0xC6),
    COORD_PING      ((byte) 0xC7),
    COORD_PONG      ((byte) 0xC8),
    COORD_ERROR     ((byte) 0xCF),

    // General error
    ERROR          ((byte) 0xFF);

    private final byte code;

    PacketType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    private static final PacketType[] LOOKUP = new PacketType[256];

    static {
        for (PacketType t : values()) {
            LOOKUP[Byte.toUnsignedInt(t.code)] = t;
        }
    }

    /**
     * Look up a PacketType by its wire code.
     * @return the PacketType, or null if unknown
     */
    public static PacketType fromCode(byte code) {
        return LOOKUP[Byte.toUnsignedInt(code)];
    }
}

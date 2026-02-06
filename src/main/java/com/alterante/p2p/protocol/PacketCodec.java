package com.alterante.p2p.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;

/**
 * Encodes and decodes {@link Packet} instances to/from byte arrays.
 *
 * Wire format (20-byte header):
 * <pre>
 * [0-1]   Magic: 0xA1 0x7F
 * [2]     Version: 0x01
 * [3]     Type
 * [4]     Flags
 * [5-8]   Connection ID (big-endian)
 * [9-12]  Sequence (big-endian)
 * [13-14] Payload Length (big-endian, max 1180)
 * [15]    Reserved (0x00)
 * [16-19] CRC-32 (over bytes 0-15, big-endian)
 * [20+]   Payload
 * </pre>
 */
public final class PacketCodec {

    public static final byte MAGIC_0 = (byte) 0xA1;
    public static final byte MAGIC_1 = (byte) 0x7F;

    private static final int CRC_INPUT_LEN = 16; // bytes 0-15
    private static final int HEADER_SIZE = Packet.HEADER_SIZE;

    private PacketCodec() {}

    /**
     * Encode a Packet into a byte array ready for sending as a UDP datagram.
     */
    public static byte[] encode(Packet packet) {
        int payloadLen = packet.payloadLength();
        byte[] out = new byte[HEADER_SIZE + payloadLen];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);

        // Bytes 0-1: magic
        buf.put(MAGIC_0);
        buf.put(MAGIC_1);

        // Byte 2: version
        buf.put(Packet.VERSION);

        // Byte 3: type
        buf.put(packet.type().code());

        // Byte 4: flags
        buf.put(packet.flags());

        // Bytes 5-8: connection ID
        buf.putInt(packet.connectionId());

        // Bytes 9-12: sequence
        buf.putInt(packet.sequence());

        // Bytes 13-14: payload length
        buf.putShort((short) payloadLen);

        // Byte 15: reserved
        buf.put((byte) 0x00);

        // Bytes 16-19: CRC-32 over bytes 0-15
        CRC32 crc = new CRC32();
        crc.update(out, 0, CRC_INPUT_LEN);
        buf.putInt((int) crc.getValue());

        // Payload
        if (payloadLen > 0) {
            buf.put(packet.payload());
        }

        return out;
    }

    /**
     * Decode a byte array (received from a UDP datagram) into a Packet.
     *
     * @param data the raw datagram bytes
     * @param length number of bytes in the datagram
     * @return the decoded Packet
     * @throws PacketException if the data is malformed
     */
    public static Packet decode(byte[] data, int length) throws PacketException {
        if (length < HEADER_SIZE) {
            throw new PacketException("datagram too short: " + length + " < " + HEADER_SIZE);
        }

        ByteBuffer buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN);

        // Bytes 0-1: magic
        byte m0 = buf.get();
        byte m1 = buf.get();
        if (m0 != MAGIC_0 || m1 != MAGIC_1) {
            throw new PacketException(String.format("bad magic: 0x%02X%02X", m0, m1));
        }

        // Byte 2: version
        byte version = buf.get();
        if (version != Packet.VERSION) {
            throw new PacketException("unsupported version: " + version);
        }

        // Byte 3: type
        byte typeCode = buf.get();
        PacketType type = PacketType.fromCode(typeCode);
        if (type == null) {
            throw new PacketException(String.format("unknown type: 0x%02X", typeCode));
        }

        // Byte 4: flags
        byte flags = buf.get();

        // Bytes 5-8: connection ID
        int connectionId = buf.getInt();

        // Bytes 9-12: sequence
        int sequence = buf.getInt();

        // Bytes 13-14: payload length
        int payloadLen = Short.toUnsignedInt(buf.getShort());
        if (payloadLen > Packet.MAX_PAYLOAD) {
            throw new PacketException("payload length too large: " + payloadLen);
        }

        // Byte 15: reserved
        buf.get(); // skip

        // Bytes 16-19: CRC-32
        int receivedCrc = buf.getInt();

        // Verify CRC over bytes 0-15
        CRC32 crc = new CRC32();
        crc.update(data, 0, CRC_INPUT_LEN);
        int computedCrc = (int) crc.getValue();
        if (receivedCrc != computedCrc) {
            throw new PacketException(String.format("CRC mismatch: received=0x%08X computed=0x%08X",
                    receivedCrc, computedCrc));
        }

        // Verify payload fits in datagram
        if (HEADER_SIZE + payloadLen > length) {
            throw new PacketException("payload extends beyond datagram: need " +
                    (HEADER_SIZE + payloadLen) + " but only " + length + " bytes");
        }

        // Read payload
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            buf.get(payload);
        }

        return new Packet(type, flags, connectionId, sequence, payload);
    }

    /**
     * Quick check if a datagram looks like our protocol (magic bytes match).
     */
    public static boolean looksLikeOurProtocol(byte[] data, int length) {
        return length >= 2 && data[0] == MAGIC_0 && data[1] == MAGIC_1;
    }
}

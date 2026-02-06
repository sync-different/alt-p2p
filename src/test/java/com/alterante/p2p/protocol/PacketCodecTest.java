package com.alterante.p2p.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PacketCodecTest {

    @Test
    void roundTripEmptyPayload() throws PacketException {
        Packet original = new Packet(PacketType.PUNCH);
        byte[] encoded = PacketCodec.encode(original);
        assertEquals(Packet.HEADER_SIZE, encoded.length);

        Packet decoded = PacketCodec.decode(encoded, encoded.length);
        assertEquals(PacketType.PUNCH, decoded.type());
        assertEquals(0, decoded.flags());
        assertEquals(0, decoded.connectionId());
        assertEquals(0, decoded.sequence());
        assertEquals(0, decoded.payloadLength());
    }

    @Test
    void roundTripWithPayload() throws PacketException {
        byte[] payload = "Hello P2P".getBytes(StandardCharsets.UTF_8);
        Packet original = new Packet(PacketType.COORD_REGISTER, (byte) 0, 0x12345678, 42, payload);
        byte[] encoded = PacketCodec.encode(original);
        assertEquals(Packet.HEADER_SIZE + payload.length, encoded.length);

        Packet decoded = PacketCodec.decode(encoded, encoded.length);
        assertEquals(PacketType.COORD_REGISTER, decoded.type());
        assertEquals(0x12345678, decoded.connectionId());
        assertEquals(42, decoded.sequence());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void roundTripAllFields() throws PacketException {
        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        Packet original = new Packet(PacketType.DATA, Packet.FLAG_ENCRYPTED, 0xDEADBEEF, 99999, payload);
        byte[] encoded = PacketCodec.encode(original);

        Packet decoded = PacketCodec.decode(encoded, encoded.length);
        assertEquals(PacketType.DATA, decoded.type());
        assertEquals(Packet.FLAG_ENCRYPTED, decoded.flags());
        assertEquals(0xDEADBEEF, decoded.connectionId());
        assertEquals(99999, decoded.sequence());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void maxPayload() throws PacketException {
        byte[] payload = new byte[Packet.MAX_PAYLOAD];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        Packet original = new Packet(PacketType.DATA, (byte) 0, 1, 1, payload);
        byte[] encoded = PacketCodec.encode(original);
        assertEquals(Packet.MAX_DATAGRAM, encoded.length);

        Packet decoded = PacketCodec.decode(encoded, encoded.length);
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void oversizePayloadRejected() {
        byte[] oversized = new byte[Packet.MAX_PAYLOAD + 1];
        assertThrows(IllegalArgumentException.class,
                () -> new Packet(PacketType.DATA, (byte) 0, 0, 0, oversized));
    }

    @Test
    void corruptMagicRejected() {
        byte[] encoded = PacketCodec.encode(new Packet(PacketType.PUNCH));
        encoded[0] = 0x00; // corrupt magic byte 0
        assertThrows(PacketException.class, () -> PacketCodec.decode(encoded, encoded.length));
    }

    @Test
    void corruptCrcRejected() {
        byte[] encoded = PacketCodec.encode(new Packet(PacketType.PUNCH));
        encoded[16] ^= 0xFF; // corrupt CRC byte
        assertThrows(PacketException.class, () -> PacketCodec.decode(encoded, encoded.length));
    }

    @Test
    void corruptHeaderFieldDetectedByCrc() {
        byte[] encoded = PacketCodec.encode(new Packet(PacketType.PUNCH, (byte) 0, 123, 456, new byte[10]));
        encoded[5] ^= 0xFF; // corrupt connection ID byte
        assertThrows(PacketException.class, () -> PacketCodec.decode(encoded, encoded.length));
    }

    @Test
    void tooShortRejected() {
        assertThrows(PacketException.class, () -> PacketCodec.decode(new byte[10], 10));
    }

    @Test
    void unknownTypeRejected() {
        byte[] encoded = PacketCodec.encode(new Packet(PacketType.PUNCH));
        // Change type to an unknown code and recompute CRC
        encoded[3] = (byte) 0xFE; // unknown type
        recomputeCrc(encoded);
        assertThrows(PacketException.class, () -> PacketCodec.decode(encoded, encoded.length));
    }

    @Test
    void looksLikeOurProtocol() {
        byte[] encoded = PacketCodec.encode(new Packet(PacketType.PUNCH));
        assertTrue(PacketCodec.looksLikeOurProtocol(encoded, encoded.length));
        assertFalse(PacketCodec.looksLikeOurProtocol(new byte[]{0, 0, 0}, 3));
        assertFalse(PacketCodec.looksLikeOurProtocol(new byte[]{}, 0));
    }

    @Test
    void allPacketTypesRoundTrip() throws PacketException {
        for (PacketType type : PacketType.values()) {
            Packet original = new Packet(type, (byte) 0, 0, 0, new byte[]{0x42});
            byte[] encoded = PacketCodec.encode(original);
            Packet decoded = PacketCodec.decode(encoded, encoded.length);
            assertEquals(type, decoded.type(), "round-trip failed for " + type);
        }
    }

    @Test
    void packetTypeFromCodeAndBack() {
        for (PacketType type : PacketType.values()) {
            assertSame(type, PacketType.fromCode(type.code()));
        }
        assertNull(PacketType.fromCode((byte) 0xFE));
    }

    /** Recompute and write CRC-32 into bytes 16-19 over bytes 0-15. */
    private void recomputeCrc(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data, 0, 16);
        int value = (int) crc.getValue();
        data[16] = (byte) (value >>> 24);
        data[17] = (byte) (value >>> 16);
        data[18] = (byte) (value >>> 8);
        data[19] = (byte) value;
    }
}

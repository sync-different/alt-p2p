package com.alterante.p2p.net;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Length-prefixed message framing for TCP relay connections.
 *
 * Wire format: type(1B) + length(4B big-endian) + payload(0..N bytes)
 *
 * Used for both pre-TLS auth messages (peer ↔ server) and
 * in-TLS transfer messages (peer ↔ peer through proxy).
 */
public class TcpRelayProtocol {

    // Pre-TLS messages (peer ↔ server)
    public static final int MSG_AUTH      = 0x01;
    public static final int MSG_AUTH_OK   = 0x02;
    public static final int MSG_AUTH_FAIL = 0x03;

    // Transfer messages (peer ↔ peer, inside TLS)
    public static final int MSG_FILE_OFFER  = 0x10;
    public static final int MSG_FILE_ACCEPT = 0x11;
    public static final int MSG_FILE_REJECT = 0x12;
    // Multi-file batch envelope (mirrors protocol.PacketType codes)
    public static final int MSG_MANIFEST    = 0x13; // fileCount(4) + dirCount(4) + totalBytes(8)
    public static final int MSG_DIR_ENTRY   = 0x14; // empty-directory marker: relative path (UTF-8)
    public static final int MSG_DATA        = 0x20;
    public static final int MSG_COMPLETE    = 0x30;
    public static final int MSG_VERIFIED    = 0x31;
    public static final int MSG_SESSION_COMPLETE = 0x33; // batch terminator (no payload)

    /** Header size: 1 byte type + 4 bytes length */
    public static final int HEADER_SIZE = 5;

    /** Max payload size (64KB for data, smaller for control messages) */
    public static final int MAX_PAYLOAD = 65536;

    /** Default DATA chunk size */
    public static final int DATA_CHUNK_SIZE = 65536;

    /** A decoded message. */
    public record Message(int type, byte[] payload) {}

    /**
     * Write a framed message to the output stream.
     */
    public static void writeMessage(OutputStream out, int type, byte[] payload) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        header[0] = (byte) type;
        header[1] = (byte) ((payload.length >> 24) & 0xFF);
        header[2] = (byte) ((payload.length >> 16) & 0xFF);
        header[3] = (byte) ((payload.length >> 8) & 0xFF);
        header[4] = (byte) (payload.length & 0xFF);
        out.write(header);
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }

    /**
     * Write a framed message with no payload.
     */
    public static void writeMessage(OutputStream out, int type) throws IOException {
        writeMessage(out, type, new byte[0]);
    }

    /**
     * Read a framed message from the input stream.
     * @throws IOException on read error or stream closed
     */
    public static Message readMessage(InputStream in) throws IOException {
        byte[] header = readFully(in, HEADER_SIZE);
        int type = header[0] & 0xFF;
        int length = ((header[1] & 0xFF) << 24)
                   | ((header[2] & 0xFF) << 16)
                   | ((header[3] & 0xFF) << 8)
                   | (header[4] & 0xFF);

        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("Invalid message length: " + length);
        }

        byte[] payload = length > 0 ? readFully(in, length) : new byte[0];
        return new Message(type, payload);
    }

    /**
     * Encode an AUTH message payload: 2B session_id_len + session_id + 32B HMAC.
     */
    public static byte[] encodeAuth(String sessionId, byte[] hmac) {
        byte[] idBytes = sessionId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(2 + idBytes.length + 32).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        buf.put(hmac);
        return buf.array();
    }

    /**
     * Decode an AUTH message payload.
     * @return [sessionId, hmac]
     */
    public static Object[] decodeAuth(byte[] payload) {
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int idLen = Short.toUnsignedInt(buf.getShort());
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String sessionId = new String(idBytes, StandardCharsets.UTF_8);
        byte[] hmac = new byte[32];
        buf.get(hmac);
        return new Object[]{sessionId, hmac};
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(buf, offset, length - offset);
            if (n < 0) {
                throw new EOFException("Stream closed (read " + offset + "/" + length + " bytes)");
            }
            offset += n;
        }
        return buf;
    }
}

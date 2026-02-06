package com.alterante.p2p.protocol;

/**
 * Thrown when a packet cannot be decoded.
 */
public class PacketException extends Exception {

    public PacketException(String message) {
        super(message);
    }

    public PacketException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.alterante.p2p.net;

/**
 * Connection lifecycle states.
 */
public enum PeerState {
    INIT,
    REGISTERING,
    AUTHENTICATING,
    WAITING_PEER,
    PUNCHING,
    HANDSHAKE,
    CONNECTED,
    ERROR
}

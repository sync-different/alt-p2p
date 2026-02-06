package com.alterante.p2p.transfer;

/**
 * State machine for file transfer (independent of connection state).
 */
public enum TransferState {
    WAITING,        // receiver: waiting for FILE_OFFER
    OFFERING,       // sender: FILE_OFFER sent, waiting for accept
    TRANSFERRING,   // sender: sending DATA packets
    RECEIVING,      // receiver: receiving DATA packets
    COMPLETING,     // sender: sent COMPLETE, waiting for VERIFIED
    VERIFYING,      // receiver: verifying SHA-256
    DONE,
    CANCELLED,
    ERROR
}

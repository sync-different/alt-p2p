package com.alterante.p2p.net;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

/**
 * Tracks a coordination session between two peers.
 * Each session has a unique ID and holds up to 2 authenticated peers.
 */
public class Session {

    public static final int MAX_PEERS = 2;

    private final String sessionId;
    private final String psk;
    private final long createdAt;
    private long lastActivity;

    private final PeerSlot[] peers = new PeerSlot[MAX_PEERS];
    private int peerCount = 0;

    public Session(String sessionId, String psk) {
        this.sessionId = sessionId;
        this.psk = psk;
        this.createdAt = System.currentTimeMillis();
        this.lastActivity = createdAt;
    }

    public String sessionId() { return sessionId; }
    public String psk() { return psk; }
    public long lastActivity() { return lastActivity; }

    public void touch() {
        this.lastActivity = System.currentTimeMillis();
    }

    /**
     * Find a peer slot by its endpoint, or null if not found.
     */
    public PeerSlot findPeer(InetSocketAddress endpoint) {
        for (int i = 0; i < peerCount; i++) {
            if (peers[i].endpoint.equals(endpoint)) {
                return peers[i];
            }
        }
        return null;
    }

    /**
     * Add a new peer to this session. Returns the slot, or null if session is full.
     */
    public PeerSlot addPeer(InetSocketAddress endpoint) {
        if (peerCount >= MAX_PEERS) {
            return null;
        }
        PeerSlot slot = new PeerSlot(endpoint);
        peers[peerCount++] = slot;
        touch();
        return slot;
    }

    public boolean isFull() {
        return peerCount >= MAX_PEERS;
    }

    public boolean bothAuthenticated() {
        return peerCount == MAX_PEERS
                && peers[0].authenticated
                && peers[1].authenticated;
    }

    /**
     * Returns the other peer's slot, or null if there's only one peer.
     */
    public PeerSlot getOtherPeer(InetSocketAddress endpoint) {
        for (int i = 0; i < peerCount; i++) {
            if (!peers[i].endpoint.equals(endpoint)) {
                return peers[i];
            }
        }
        return null;
    }

    public PeerSlot getPeer(int index) {
        return index < peerCount ? peers[index] : null;
    }

    public int peerCount() { return peerCount; }

    /**
     * State for one peer within a session.
     */
    public static class PeerSlot {
        public final InetSocketAddress endpoint;
        public byte[] nonce;
        public boolean authenticated;

        PeerSlot(InetSocketAddress endpoint) {
            this.endpoint = endpoint;
            this.nonce = new byte[32];
            new SecureRandom().nextBytes(this.nonce);
            this.authenticated = false;
        }
    }
}

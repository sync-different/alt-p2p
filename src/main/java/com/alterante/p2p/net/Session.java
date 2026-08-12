package com.alterante.p2p.net;

import java.net.InetSocketAddress;
import java.security.SecureRandom;

/**
 * Tracks a coordination session between two peers.
 * Each session has a unique ID and holds up to 2 authenticated peers.
 */
public class Session {

    public static final int MAX_PEERS = 2;

    /**
     * How long a peer may hold a slot without authenticating.
     *
     * <p>A slot is claimed at REGISTER, before the peer has proved it holds the PSK. If it then dies
     * — wrong key, crash, network loss — nothing it has done deserves protection, so its slot is the
     * cheapest thing in the system to reclaim. Generous enough for a real handshake (challenge,
     * HMAC, reply) over a slow link; short enough that a real peer is not locked out for long.
     */
    public static final long UNAUTHENTICATED_GRACE_MS = 10_000;

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

    /** Every claimed slot, in registration order. */
    public PeerSlot[] peers() {
        return java.util.Arrays.copyOf(peers, peerCount);
    }

    /**
     * Slots still awaiting authentication, so AUTH can be matched by proof rather than by address.
     * Only unauthenticated slots are offered: an established peer must never be displaceable.
     */
    public PeerSlot[] unauthenticatedPeers() {
        PeerSlot[] out = new PeerSlot[peerCount];
        int n = 0;
        for (int i = 0; i < peerCount; i++) {
            if (!peers[i].authenticated) {
                out[n++] = peers[i];
            }
        }
        return java.util.Arrays.copyOf(out, n);
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

    /**
     * Drop slots held by peers that registered but never authenticated within the grace period.
     *
     * <p>Session expiry cannot do this job: it keys on one shared {@code lastActivity}, so a healthy
     * partner's keepalives keep the whole session — and therefore the dead slot — alive indefinitely.
     * That is exactly how a production host locked itself out of its own session.
     *
     * @param graceMs how long a peer may hold a slot without authenticating; the caller scales this
     *                to the session timeout, since a grace longer than the session's own lifetime
     *                would never fire
     * @return how many slots were reclaimed
     */
    public int reclaimStaleUnauthenticated(long graceMs) {
        long cutoff = System.currentTimeMillis() - graceMs;
        int kept = 0;
        int dropped = 0;
        for (int i = 0; i < peerCount; i++) {
            PeerSlot slot = peers[i];
            if (!slot.authenticated && slot.registeredAt < cutoff) {
                dropped++;
            } else {
                peers[kept++] = slot;
            }
        }
        for (int i = kept; i < peerCount; i++) {
            peers[i] = null;
        }
        peerCount = kept;
        return dropped;
    }

    /**
     * Clear all peer slots so the session id can be reused for a new rendezvous.
     * Safe to call only once the previous pairing has completed (see
     * {@link #bothAuthenticated()}): by then both peers have received PEER_INFO and
     * are connecting directly, so they no longer depend on the coordinator.
     */
    public void reset() {
        for (int i = 0; i < peerCount; i++) peers[i] = null;
        peerCount = 0;
        touch();
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
        /**
         * Where the coordinator will reach this peer.
         *
         * <p>Not final: a NAT may hand the peer a different public port between REGISTER and AUTH,
         * and the slot must follow it or everything the coordinator later sends — COORD_OK,
         * PEER_INFO, relayed packets — goes to an address nobody is listening on. Only ever moved by
         * {@link #rebind}, which the server calls after verifying the peer's HMAC.
         */
        public InetSocketAddress endpoint;
        /** When this slot was claimed, for reclaiming it if authentication never follows. */
        public final long registeredAt;
        /** Peer's LAN (local) endpoint, reported at auth — used as a hole-punch candidate when both peers share a public IP. Null if not reported. */
        public InetSocketAddress localEndpoint;
        public byte[] nonce;
        public boolean authenticated;

        /**
         * Move this slot to the address the peer is actually speaking from. Callers MUST have proved
         * the peer's identity first (a valid HMAC over this slot's nonce) — otherwise anyone could
         * redirect a session's traffic by sending a packet.
         */
        void rebind(InetSocketAddress newEndpoint) {
            this.endpoint = newEndpoint;
        }

        PeerSlot(InetSocketAddress endpoint) {
            this.endpoint = endpoint;
            this.registeredAt = System.currentTimeMillis();
            this.nonce = new byte[32];
            new SecureRandom().nextBytes(this.nonce);
            this.authenticated = false;
        }
    }
}

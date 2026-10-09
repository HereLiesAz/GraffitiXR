package com.hereliesaz.graffitixr.core.collaboration.wire

/**
 * Co-op wire protocol version, carried in the QR payload and the HELLO handshake. Peers whose
 * versions differ are rejected with [HelloRejectedPayload.RejectReason.VersionMismatch].
 *
 * v2 introduced the token-derived AES-256-GCM transport (SessionCrypto) and the nonce/proof
 * handshake, so a v1 (plaintext) peer can never establish a session with a v2 peer.
 *
 * v3 makes spatial alignment explicit: HELLO identifies the guest pose backend and BULK_BEGIN
 * carries the host wall-frame bridge/scale contract. A v2 peer is deliberately incompatible
 * because it can only assume peer fingerprint coordinates are interchangeable.
 *
 * v4 makes co-op bidirectional: a guest sends its own edits as GUEST_OP frames and the host acks
 * them with GUEST_OP_ACK. A v3 host would silently ignore GUEST_OP (its inbound loop drops unknown
 * frame types), so the guest's edits would vanish with no signal — hence a hard version break.
 */
internal object ProtocolVersion {
    const val CURRENT: Int = 4
}

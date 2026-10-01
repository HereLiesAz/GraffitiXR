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
 */
internal object ProtocolVersion {
    const val CURRENT: Int = 3
}

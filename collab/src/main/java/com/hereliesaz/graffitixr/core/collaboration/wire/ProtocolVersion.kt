package com.hereliesaz.graffitixr.core.collaboration.wire

/**
 * Co-op wire protocol version, carried in the QR payload and the HELLO handshake. Peers whose
 * versions differ are rejected with [HelloRejectedPayload.RejectReason.VersionMismatch].
 *
 * v2 introduced the token-derived AES-256-GCM transport (SessionCrypto) and the nonce/proof
 * handshake, so a v1 (plaintext) peer can never establish a session with a v2 peer.
 */
internal object ProtocolVersion {
    const val CURRENT: Int = 2
}

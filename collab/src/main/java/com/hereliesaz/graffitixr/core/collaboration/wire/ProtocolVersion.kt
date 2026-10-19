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
 * because it can only assume peer doodle coordinates are interchangeable.
 *
 * v4 makes co-op bidirectional: a guest sends its own edits as GUEST_OP frames and the host acks
 * them with GUEST_OP_ACK. A v3 host would silently ignore GUEST_OP (its inbound loop drops unknown
 * frame types), so the guest's edits would vanish with no signal — hence a hard version break.
 *
 * v5 adds [com.hereliesaz.graffitixr.common.model.Op.WallWidth] (Measure results, sent by host and
 * guest alike). A v4 peer cannot decode that sealed-class variant, so the width would be lost
 * mid-session — hence another hard version break.
 *
 * v6 adds the shared [com.hereliesaz.graffitixr.common.model.Layer] fields `isStencil`, `stencilView`
 * and `stencilDark`. A v5 peer's decoder drops them and rebuilds the untouched source image while the
 * host shows the stencil, so the two would silently display different artwork — hence another hard
 * version break.
 *
 * v7 adds the tripod stream: VIDEO and VIDEO_WANT frames. A v6 peer's loops ignore unknown frame
 * types, so a display guest would wait on a stream that never comes — hence another hard version break.
 */
internal object ProtocolVersion {
    const val CURRENT: Int = 7
}

package com.hereliesaz.graffitixr.core.collaboration.wire

/**
 * Wire-level size limits shared by both ends of the protocol, so the host never offers a bulk
 * transfer the guest is guaranteed to reject.
 */
internal object Limits {
    /** Generous cap on a full project bulk transfer; rejects malformed/hostile declared sizes. */
    const val MAX_BULK_BYTES: Int = 256 * 1024 * 1024
}

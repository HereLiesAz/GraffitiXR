package com.hereliesaz.graffitixr.core.collaboration.wire

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import kotlinx.serialization.Serializable

/**
 * Guest→host handshake — the only guest-authored plaintext frame. The token is never transmitted;
 * [proof] = HMAC(prk(token), "gxr/hello" || guestNonce) proves knowledge of it, and [guestNonce]
 * seeds the per-connection key schedule. Every frame after this is encrypted (FrameType.ENC).
 */
@Serializable
internal data class HelloPayload(
    val guestNonce: ByteArray,
    val proof: ByteArray,
    val clientVersion: Int,
    val deviceName: String,
    val lastAppliedSeq: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloPayload) return false
        return guestNonce.contentEquals(other.guestNonce) && proof.contentEquals(other.proof) &&
            clientVersion == other.clientVersion && deviceName == other.deviceName &&
            lastAppliedSeq == other.lastAppliedSeq
    }

    override fun hashCode(): Int {
        var result = guestNonce.contentHashCode()
        result = 31 * result + proof.contentHashCode()
        result = 31 * result + clientVersion
        result = 31 * result + deviceName.hashCode()
        result = 31 * result + lastAppliedSeq.hashCode()
        return result
    }
}

/**
 * Host→guest handshake reply. [hostNonce] completes the key schedule and [hostProof] =
 * HMAC(prk, "gxr/hello-ok" || hostNonce || guestNonce) lets the guest authenticate the host
 * before trusting any bulk data.
 */
@Serializable
internal data class HelloOkPayload(
    val sessionId: String,
    val protocolVersion: Int,
    val hostNonce: ByteArray,
    val hostProof: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloOkPayload) return false
        return sessionId == other.sessionId && protocolVersion == other.protocolVersion &&
            hostNonce.contentEquals(other.hostNonce) && hostProof.contentEquals(other.hostProof)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + protocolVersion
        result = 31 * result + hostNonce.contentHashCode()
        result = 31 * result + hostProof.contentHashCode()
        return result
    }
}

@Serializable
internal data class HelloRejectedPayload(val reason: RejectReason) {
    @Serializable
    enum class RejectReason { BadToken, VersionMismatch, AlreadyHosting }
}

@Serializable
internal data class BulkBeginPayload(
    val projectId: String,
    val layerCount: Int,
    val fingerprintBytes: Int,
    val projectBytes: Int,
)

@Serializable
internal data class DeltaPayload(val seq: Long, val op: Op)

@Serializable
internal data class DeltaAckPayload(val lastSeq: Long)

@Serializable
internal data class BulkAckPayload(val lastSeq: Long)

@Serializable
internal data class PingPayload(val ts: Long)

@Serializable
internal data class ByePayload(val reason: CoopSessionState.EndReason)

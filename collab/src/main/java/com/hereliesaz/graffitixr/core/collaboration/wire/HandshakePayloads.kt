package com.hereliesaz.graffitixr.core.collaboration.wire

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import kotlinx.serialization.Serializable

@Serializable
internal data class HelloPayload(
    val token: String,
    val clientVersion: Int,
    val deviceName: String,
    val lastAppliedSeq: Long = 0L,
)

@Serializable
internal data class HelloOkPayload(
    val sessionId: String,
    val protocolVersion: Int,
)

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

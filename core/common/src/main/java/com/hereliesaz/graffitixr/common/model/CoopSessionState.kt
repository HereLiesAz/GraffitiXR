package com.hereliesaz.graffitixr.common.model

import kotlinx.serialization.Serializable

sealed class CoopSessionState {
    object Idle : CoopSessionState()
    object WaitingForGuest : CoopSessionState()
    data class Connected(val peerName: String) : CoopSessionState()
    object Reconnecting : CoopSessionState()
    data class Ended(val reason: EndReason) : CoopSessionState()

    @Serializable
    enum class EndReason {
        UserLeft,
        NetworkLost,
        HostClosed,
        ProtocolError,
        VersionMismatch,
        BadToken,
    }
}

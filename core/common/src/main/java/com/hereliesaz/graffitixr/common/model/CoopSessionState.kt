package com.hereliesaz.graffitixr.common.model

sealed class CoopSessionState {
    object Idle : CoopSessionState()
    object WaitingForGuest : CoopSessionState()
    data class Connected(val peerName: String) : CoopSessionState()
    object Reconnecting : CoopSessionState()
    data class Ended(val reason: EndReason) : CoopSessionState()

    enum class EndReason {
        UserLeft,
        NetworkLost,
        HostClosed,
        ProtocolError,
        VersionMismatch,
        BadToken,
    }
}

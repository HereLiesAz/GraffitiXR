package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal abstract class Session {

    protected val _state: MutableStateFlow<CoopSessionState> =
        MutableStateFlow(CoopSessionState.Idle)
    val state: StateFlow<CoopSessionState> get() = _state

    protected var phase: Phase = Phase.Handshake

    abstract suspend fun close(reason: CoopSessionState.EndReason)
}

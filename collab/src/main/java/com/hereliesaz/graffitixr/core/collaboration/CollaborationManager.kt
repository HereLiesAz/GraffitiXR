package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Public API. Editor + AR features depend on this surface only. */
@Singleton
class CollaborationManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state: MutableStateFlow<CoopSessionState> = MutableStateFlow(CoopSessionState.Idle)
    val state: StateFlow<CoopSessionState> get() = _state

    @Volatile private var hostSession: HostSession? = null
    @Volatile private var guestSession: GuestSession? = null
    @Volatile private var lastQrPayload: QrPayload? = null

    /** Begin hosting. Returns the QR payload to display. */
    suspend fun startHosting(
        projectId: String,
        layerCount: Int,
        fingerprintBytes: ByteArray,
        projectBytes: ByteArray,
        localDeviceName: String,
        protocolVersion: Int = 1,
    ): String {
        check(hostSession == null && guestSession == null) { "already in a session" }
        val token = QrPayload.newToken()
        val session = HostSession(
            token = token,
            protocolVersion = protocolVersion,
            localDeviceName = localDeviceName,
            fingerprintBytes = fingerprintBytes,
            projectBytes = projectBytes,
            projectId = projectId,
            layerCount = layerCount,
        )
        hostSession = session
        observe(session.state)
        val port = session.startListening()
        val payload = QrPayload(
            host = LocalIp.discover() ?: "127.0.0.1",
            port = port,
            token = token,
            protocolVersion = protocolVersion,
        )
        lastQrPayload = payload
        return payload.encode()
    }

    suspend fun stopHosting() {
        hostSession?.close(CoopSessionState.EndReason.UserLeft)
        hostSession = null
    }

    suspend fun joinFromQr(
        qr: String,
        localDeviceName: String,
        onBulkReceived: suspend (fingerprint: ByteArray, project: ByteArray) -> Unit,
        onOp: suspend (Op) -> Unit,
    ) {
        check(hostSession == null && guestSession == null) { "already in a session" }
        val payload = QrPayload.parse(qr)
        val session = GuestSession(
            host = payload.host,
            port = payload.port,
            token = payload.token,
            protocolVersion = payload.protocolVersion,
            localDeviceName = localDeviceName,
            onBulkReceived = onBulkReceived,
            onOp = onOp,
        )
        guestSession = session
        observe(session.state)
        session.connect()
    }

    suspend fun leaveSession() {
        guestSession?.close(CoopSessionState.EndReason.UserLeft)
        guestSession = null
        hostSession?.close(CoopSessionState.EndReason.UserLeft)
        hostSession = null
        _state.value = CoopSessionState.Idle
    }

    /** Called by OpEmitterImpl on every editor mutation. */
    internal fun enqueueHostOp(op: Op) {
        hostSession?.enqueueOp(op)
    }

    private fun observe(stateFlow: StateFlow<CoopSessionState>) {
        scope.launch {
            stateFlow.collect { _state.value = it }
        }
    }
}

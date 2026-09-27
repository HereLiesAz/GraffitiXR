package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.ProtocolVersion
import com.hereliesaz.graffitixr.core.collaboration.wire.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A point-in-time snapshot of the project state a bulk resync sends to a guest. Callers (e.g.
 * [CollaborationManager.startHosting]'s caller) supply a factory rather than precomputed bytes, so
 * every bulk send — the initial one, and any later fallback when a reconnect lands in a
 * [com.hereliesaz.graffitixr.core.collaboration.session.DeltaBuffer] gap — reflects the project as
 * it stands at send time, not as it stood when hosting started minutes or hours earlier.
 */
data class ProjectSnapshot(
    val fingerprintBytes: ByteArray,
    val projectBytes: ByteArray,
    val layerCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectSnapshot) return false
        return fingerprintBytes.contentEquals(other.fingerprintBytes) &&
            projectBytes.contentEquals(other.projectBytes) &&
            layerCount == other.layerCount
    }

    override fun hashCode(): Int {
        var result = fingerprintBytes.contentHashCode()
        result = 31 * result + projectBytes.contentHashCode()
        result = 31 * result + layerCount
        return result
    }
}

/** Public API. Editor + AR features depend on this surface only. */
@Singleton
class CollaborationManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state: MutableStateFlow<CoopSessionState> = MutableStateFlow(CoopSessionState.Idle)
    val state: StateFlow<CoopSessionState> get() = _state

    @Volatile private var hostSession: HostSession? = null
    @Volatile private var guestSession: GuestSession? = null
    @Volatile private var lastQrPayload: QrPayload? = null
    // The session-state collector never completes; track it so each new session cancels the
    // previous collector (otherwise one leaks per host/join cycle and stale ones race _state).
    @Volatile private var observeJob: Job? = null

    /**
     * Begin hosting. Returns the QR payload to display.
     *
     * [snapshotProvider] is called once at construction (to validate the project isn't too large
     * to host at all) and again every time a bulk resync is actually sent — the initial one and
     * any later reconnect-gap fallback — so a guest joining or rejoining well into a session
     * always gets the project as it stands *then*, not a copy frozen at the moment hosting began.
     */
    suspend fun startHosting(
        projectId: String,
        localDeviceName: String,
        protocolVersion: Int = ProtocolVersion.CURRENT,
        snapshotProvider: () -> ProjectSnapshot,
    ): String {
        check(hostSession == null && guestSession == null) { "already in a session" }
        val token = QrPayload.newToken()
        val session = HostSession(
            token = token,
            protocolVersion = protocolVersion,
            localDeviceName = localDeviceName,
            projectId = projectId,
            snapshotProvider = snapshotProvider,
        )
        hostSession = session
        observe(session.state)
        // If startListening() throws (e.g. the port failed to bind), hostSession must not stay
        // set: check() above would then refuse every future startHosting/joinFromQr forever, with
        // no way for the user to retry after a transient failure.
        val port = try {
            session.startListening()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            observeJob?.cancel()
            observeJob = null
            hostSession = null
            throw e
        }
        val payload = QrPayload(
            host = LocalIp.discover() ?: "127.0.0.1",
            port = port,
            token = token,
            protocolVersion = protocolVersion,
        )
        lastQrPayload = payload
        return payload.encode()
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
        // Cancel the collector first so the sessions' terminal Ended emission can't race past
        // the Idle we set below (the auditor's nondeterministic Ended-vs-Idle finish).
        observeJob?.cancel()
        observeJob = null
        guestSession?.close(CoopSessionState.EndReason.UserLeft)
        guestSession = null
        hostSession?.close(CoopSessionState.EndReason.UserLeft)
        hostSession = null
        _state.value = CoopSessionState.Idle
    }

    /**
     * Fire-and-forget [leaveSession] for lifecycle owners (e.g. ViewModel.onCleared) whose own
     * coroutine scope is already cancelled at teardown time and therefore cannot run the suspend
     * variant. Runs on this singleton's [scope], which outlives the caller, so the host server
     * socket / guest connection is actually released instead of leaking until process death.
     */
    fun leaveSessionAsync() {
        scope.launch { leaveSession() }
    }

    /**
     * Signalled when an editor mutation is made in a session that cannot transmit it — i.e. as a
     * guest, because the protocol is host-broadcast and there is no guest→host channel.
     *
     * This exists because the alternative is worse than the limitation itself. `enqueueHostOp` used
     * to no-op whenever `hostSession` was null, so a guest could change a layer, see it change on
     * their own screen, and have it reach nobody — with the two canvases now silently diverging and
     * no signal on either end. Reporting the drop turns an invisible desync into a stated one.
     *
     * Conflated (`extraBufferCapacity = 1`, DROP_OLDEST) on purpose: a single gesture emits a burst
     * of ops, and the user needs to be told once, not once per op.
     */
    private val _guestEditDropped = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val guestEditDropped: SharedFlow<Unit> get() = _guestEditDropped

    /** Called by OpEmitterImpl on every editor mutation. */
    internal fun enqueueHostOp(op: Op) {
        val host = hostSession
        if (host != null) {
            host.enqueueOp(op)
            return
        }
        // Not hosting. As a guest that is a dropped edit worth reporting; with no session at all
        // (solo editing) there is nothing to report — the op simply has nowhere to go by design.
        if (guestSession != null) _guestEditDropped.tryEmit(Unit)
    }

    private fun observe(stateFlow: StateFlow<CoopSessionState>) {
        observeJob?.cancel()
        observeJob = scope.launch {
            stateFlow.collect {
                _state.value = it
                // A session can end on its own — the host's guest never reconnects, a version
                // mismatch, a bad token, the peer's own BYE — without leaveSession() ever being
                // called. Without this, hostSession/guestSession stay non-null forever after such
                // an end, and the check() guards in startHosting/joinFromQr then refuse every
                // future attempt with "already in a session", even though nothing is listening or
                // connected anymore and the user has no ended session they know to "leave".
                if (it is CoopSessionState.Ended) {
                    hostSession = null
                    guestSession = null
                }
            }
        }
    }
}

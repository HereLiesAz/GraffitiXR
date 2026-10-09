package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
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
import kotlinx.coroutines.flow.receiveAsFlow
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
    val spatialFrame: CoopSpatialFrame,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectSnapshot) return false
        return fingerprintBytes.contentEquals(other.fingerprintBytes) &&
            projectBytes.contentEquals(other.projectBytes) &&
            layerCount == other.layerCount &&
            spatialFrame == other.spatialFrame
    }

    override fun hashCode(): Int {
        var result = fingerprintBytes.contentHashCode()
        result = 31 * result + projectBytes.contentHashCode()
        result = 31 * result + layerCount
        result = 31 * result + spatialFrame.hashCode()
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
            onGuestOp = { op -> _guestOps.trySend(op) },
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
        localBackend: CoopTrackingBackend,
        onBulkReceived: suspend (
            fingerprint: ByteArray,
            project: ByteArray,
            spatialFrame: CoopSpatialFrame,
        ) -> Unit,
        onOp: suspend (Op) -> Unit,
    ) {
        check(hostSession == null && guestSession == null) { "already in a session" }
        val payload = QrPayload.parse(qr)
        if (payload.protocolVersion != ProtocolVersion.CURRENT) {
            _state.value = CoopSessionState.Ended(CoopSessionState.EndReason.VersionMismatch)
            throw IllegalArgumentException(
                "co-op protocol mismatch: QR=${payload.protocolVersion} local=${ProtocolVersion.CURRENT}"
            )
        }
        val session = GuestSession(
            host = payload.host,
            port = payload.port,
            token = payload.token,
            // Advertise THIS client's schema, never echo the QR's. The equality check above makes
            // this mostly documentary, but it prevents a future caller from reintroducing the v2/v3
            // bug by treating peer metadata as local protocol capability.
            protocolVersion = ProtocolVersion.CURRENT,
            localDeviceName = localDeviceName,
            localBackend = localBackend,
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
     * Signalled when a guest makes an edit that cannot be shared: the host would refuse it (an AR
     * placement between peers on different tracking backends — see
     * [com.hereliesaz.graffitixr.core.collaboration.session.GuestOpPolicy]) or it is too large for
     * one frame. Since protocol v4 every other guest edit IS sent; before v4 this fired for all of
     * them, because there was no guest→host channel at all.
     *
     * It exists because silence is worse than the limitation: an edit that applies locally and
     * reaches nobody leaves the two canvases diverging with no signal on either end.
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

    /**
     * Guest edits the host accepted (protocol v4), for the host's editor to apply to the
     * authoritative project. Already re-broadcast by the host session; the consumer must NOT emit
     * them again. Unbounded: dropping one would silently desync host from guest.
     */
    private val _guestOps = kotlinx.coroutines.channels.Channel<Op>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    val guestOps: kotlinx.coroutines.flow.Flow<Op> = _guestOps.receiveAsFlow()

    /** Called by OpEmitterImpl on every editor mutation. */
    internal fun enqueueHostOp(op: Op) {
        val host = hostSession
        if (host != null) {
            host.enqueueOp(op)
            return
        }
        // As a guest (v4): send it; report only an edit the host would refuse or that can't be
        // framed. With no session at all (solo editing) the op simply has nowhere to go.
        val guest = guestSession ?: return
        if (!guest.sendOp(op)) _guestEditDropped.tryEmit(Unit)
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

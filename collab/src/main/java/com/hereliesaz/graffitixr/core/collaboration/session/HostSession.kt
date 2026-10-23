// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSession.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import android.util.Log
import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.ProjectSnapshot
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkBeginPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.ByePayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Frame
import com.hereliesaz.graffitixr.core.collaboration.wire.FrameType
import com.hereliesaz.graffitixr.core.collaboration.wire.GuestOpAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.GuestOpPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloOkPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloRejectedPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Limits
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import com.hereliesaz.graffitixr.core.collaboration.wire.PingPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.SessionCrypto
import com.hereliesaz.graffitixr.core.collaboration.wire.VideoWantPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal class HostSession(
    private val token: String,
    private val protocolVersion: Int,
    private val localDeviceName: String,
    private val projectId: String,
    // Called fresh every time a bulk snapshot is actually sent (see [sendBulk]), not just once at
    // construction: a precomputed ByteArray captured here would go stale the moment a guest joins
    // (or reconnects into a DeltaBuffer gap) more than a few edits into the session, silently
    // handing them an out-of-date project.
    private val snapshotProvider: () -> ProjectSnapshot,
    // Protocol v4: a guest edit the host accepted, delivered on this session's IO thread. The owner
    // applies it to the authoritative project and THEN emits it like a local edit (enqueueOp), so
    // the host's DELTA order is its apply order — see EditorViewModel.applyGuestOp.
    private val onGuestOp: (Op) -> Unit = {},
    // How long a dropped guest has to complete a reconnect before the session ends NetworkLost.
    // Injectable so tests can use a short window instead of the production 30s.
    private val reconnectWindowMs: Long = 30_000L,
) : Session() {

    // ── Protocol v7: the tripod stream ───────────────────────────────────────────────────────
    private val _videoWanted = MutableStateFlow(false)

    /** True while the connected guest has asked for the stream; the owner captures frames only then. */
    val videoWanted: StateFlow<Boolean> get() = _videoWanted

    // Latest wins: a frame the guest hasn't been sent yet is replaced, never queued behind.
    private val videoFrames = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /**
     * Offer one JPEG of the host's composited view. Dropped (false) unless the guest is live, wants
     * the stream, and the frame fits a sealed frame; otherwise it replaces any frame still waiting.
     */
    fun offerVideo(jpeg: ByteArray): Boolean {
        if (phase != Phase.Live || !_videoWanted.value) return false
        if (jpeg.size > Frame.MAX_PAYLOAD_BYTES - SessionCrypto.SEAL_OVERHEAD_BYTES) return false
        return videoFrames.trySend(jpeg).isSuccess
    }

    private suspend fun videoLoop(output: OutputStream, crypto: SessionCrypto) {
        for (jpeg in videoFrames) {
            if (!_videoWanted.value) continue
            try {
                writeSecure(output, crypto, FrameType.VIDEO, jpeg)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                enterReconnecting(crypto)
                return
            }
        }
    }

    /** Spatial identity fixed for the lifetime of this host session. */
    private val hostedSpatialFrame: CoopSpatialFrame

    init {
        // The guest rejects bulk transfers above this cap with a bare require() that surfaces as
        // an unexplained NetworkLost on its side; failing fast here turns an oversized project
        // into an immediate, attributable hosting error instead. This only validates size against
        // whatever the project looks like right now — [sendBulk] validates again every time it
        // actually sends one, since the project this check saw at construction is not necessarily
        // the one sent later.
        val probe = snapshotProvider()
        hostedSpatialFrame = probe.spatialFrame
        if (probe.projectBytes.size > Limits.MAX_BULK_BYTES || probe.doodleBytes.size > Limits.MAX_BULK_BYTES) {
            throw ProjectTooLargeToHostException(
                maxOf(probe.projectBytes.size, probe.doodleBytes.size).toLong(),
                Limits.MAX_BULK_BYTES.toLong(),
            )
        }
    }

    /** An op with its sequence number and wire encoding, fixed at enqueue time. */
    // Holds only the seq, not the encoded bytes: the op itself lives in deltaBuffer (the single, 48 MB-
    // capped source of truth), and outboundLoop re-encodes from there at send time. Retaining the bytes
    // here too meant a second, UNBOUNDED copy of every op — including multi-MB bitmap ops — grew without
    // limit while the host sat in WaitingForGuest (nothing drains the queue until a guest connects),
    // OOMing a long solo pre-guest session. deltaBuffer's eviction does not bound this queue; seq-only does.
    private class EncodedDelta(
        val seq: Long,
        /** Monotonic enqueue time; see [BULK_PERSIST_GRACE_MS]. */
        val enqueuedAtMs: Long = System.nanoTime() / 1_000_000L,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Unbounded channel, but NOT unbounded memory: every element is appended to deltaBuffer
    // first (real encoded size), whose 48 MB / 2000-op cap (see DeltaBuffer.kt) evicts oldest
    // entries and records a gap on overflow rather than ending the session. The previous design
    // (bounded 128 + DROP_OLDEST, seq assigned at send time) silently discarded ops that
    // overflowed during a reconnect window before they ever reached the DeltaBuffer, so the
    // guest's canvas diverged with no signal.
    private val outQueue: Channel<EncodedDelta> = Channel(capacity = Channel.UNLIMITED)
    // internal (not private) so tests can shrink it and fill it directly; nothing else touches it.
    @androidx.annotation.VisibleForTesting
    internal var deltaBuffer = DeltaBuffer()
    @androidx.annotation.VisibleForTesting
    internal val seqCounter = AtomicLong(0L)
    // Orders seq assignment with channel insertion so send order always matches seq order.
    private val enqueueLock = Any()
    // Guards the check-then-set phase transition in enterReconnecting(): outbound, inbound and
    // heartbeat loops can all fail at once, and without the lock each would pass the phase check
    // and launch its own 30s reconnect watcher.
    private val phaseLock = Any()

    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    // Crypto for the current live connection, so close()/BYE can seal on the active channel.
    @Volatile private var activeCrypto: SessionCrypto? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    // The seqCutoff of the most recent bulk sent: every op through it is in a snapshot the current
    // guest already has, so outboundLoop never treats one of those as an evicted-before-sent hole.
    @Volatile private var resyncedThroughSeq: Long = 0L
    // Bumped each time a connection completes handshake + bulk/replay and goes Live. The reconnect
    // watcher waits for this to change rather than for clientSocket to be set: clientSocket is
    // adopted before bulk/replay, so a reconnect that failed partway used to satisfy the watcher,
    // after which acceptLoop reset clientSocket with nothing left to enforce the timeout.
    @Volatile private var liveGeneration: Long = 0L
    // v4 guest edits: the connected guest's backend (for GuestOpPolicy) and the highest guestSeq
    // already handled, so a resend after a reconnect is acked without being applied twice.
    @Volatile private var guestBackend: com.hereliesaz.graffitixr.common.model.CoopTrackingBackend? = null
    // Dedupe is per GuestSession INSTANCE (GuestOpPayload.guestInstance), not per connection: a
    // reconnect whose guest never received a DELTA still reports lastAppliedSeq 0 and would look
    // like a fresh join, re-applying edits already handled.
    @Volatile private var guestInstance: String? = null
    @Volatile private var lastGuestSeqHandled: Long = 0L
    private var liveJob: Job? = null

    // Serializes all writes to the single connected guest's OutputStream. The outbound,
    // inbound (PONG) and heartbeat (PING) loops write concurrently, and Frame.write issues
    // several stream writes per frame, so without this lock their bytes interleave and
    // corrupt the wire framing.
    @androidx.annotation.VisibleForTesting
    internal val writeMutex = Mutex()

    // Seal-then-write every post-handshake frame under the write lock. The lock also serializes
    // access to crypto's send counter (each of the outbound/inbound/heartbeat loops writes here).
    private suspend fun writeSecure(output: OutputStream, crypto: SessionCrypto, type: FrameType, payload: ByteArray) {
        writeMutex.withLock {
            writeFrameTimed(output, FrameType.ENC, crypto.seal(type, payload))
        }
    }

    /**
     * Write one frame with a bound on wall-clock time, so a guest that stops reading
     * (backgrounded app, throttled network, or a hostile peer that just never calls read())
     * cannot block this write forever. Adoption is serialized under [adoptMutex], so an unbounded
     * write here — the handshake replies, [sendBulk], and the reconnect replay all go through this
     * path — would wedge adoption for every subsequent joiner indefinitely.
     *
     * `java.net.Socket` exposes no write-side timeout (`soTimeout` only bounds reads), so the
     * actual write runs on a plain IO thread and this coroutine only waits up to
     * [WRITE_TIMEOUT_MS] for it.
     *
     * A bare `withTimeoutOrNull(...) { withContext(Dispatchers.IO) { blockingWrite() } }` does
     * NOT actually bound wall-clock time: `withContext` only returns once its block completes, so
     * `withTimeoutOrNull` cancelling the *coroutine* around it has nothing to act on while that
     * coroutine is suspended waiting on `withContext` — the underlying thread stays parked inside
     * the blocking call regardless, for as long as the guest simply never reads (backgrounded app,
     * throttled network, or a hostile peer). [runInterruptible] does not rescue this either: unlike
     * NIO channels, plain `java.net.Socket` streams do not respond to `Thread.interrupt()` while
     * blocked in a native write — the thread still does not unblock.
     *
     * The one thing that reliably unblocks a stuck `java.net.Socket` write is closing the stream
     * out from under it (`Socket.getOutputStream`'s documented contract: closing the stream closes
     * the socket), which turns the blocked write into an `IOException` on that thread. So instead
     * of trying to cancel the write, a watchdog coroutine races it: if the write hasn't finished by
     * [WRITE_TIMEOUT_MS], the watchdog force-closes [output], the write call unblocks with an
     * exception, and this function reports that as a timeout either way (even in the unlikely case
     * the failure the write actually saw had some other proximate cause) — the caller treats it
     * exactly like any other failed write: a dropped connection, not a wedged one.
     */
    private suspend fun writeFrameTimed(output: OutputStream, type: FrameType, payload: ByteArray) {
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = scope.launch {
            delay(WRITE_TIMEOUT_MS)
            timedOut.set(true)
            try { output.close() } catch (_: Exception) {}
        }
        try {
            withContext(Dispatchers.IO) {
                Frame.write(output, type, payload)
                output.flush()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (timedOut.get()) {
                throw java.io.IOException("write timed out after ${WRITE_TIMEOUT_MS}ms", e)
            }
            throw e
        } finally {
            watchdog.cancel()
        }
    }

    // Read one post-handshake frame: it must be an ENC envelope; open it to the inner frame.
    private fun readSecure(input: java.io.InputStream, crypto: SessionCrypto): Frame.FrameRead? {
        val frame = Frame.read(input) ?: return null
        if (frame.type != FrameType.ENC) throw java.io.IOException("expected ENC frame, got ${frame.type}")
        return crypto.open(frame.payload)
    }

    fun port(): Int = serverSocket?.localPort
        ?: error("server not started")

    /**
     * Bind the listen socket and return the port. The session enters
     * WaitingForGuest. Call enqueueOp(...) once a guest connects (handled
     * internally by the accept loop).
     */
    suspend fun startListening(): Int = withContext(Dispatchers.IO) {
        val ss = ServerSocket(0)
        serverSocket = ss
        _state.value = CoopSessionState.WaitingForGuest
        scope.launch { acceptLoop(ss) }
        ss.localPort
    }

    /**
     * Enqueue an Op to be sent to the guest. Seq assignment, wire encoding and DeltaBuffer
     * accounting all happen here, atomically, so nothing can be dropped between enqueue and send.
     *
     * A full replay buffer is no longer fatal. It used to be: an oversized op (a whole-canvas
     * `LayerBitmapReplace` from one Liquify warp) or a long stretch of editing with no guest yet to
     * ack anything both overflowed the cap, and overflow ended the session. The buffer now evicts
     * and reports a gap instead, and a reconnect that lands in the gap is served a fresh bulk
     * snapshot — see [DeltaBuffer] and the replay branch in [handleConnection]. The op itself is
     * always queued for the live guest either way.
     *
     * Encoding runs on the caller's thread; ops carrying large payloads (LayerBitmapReplace)
     * should be enqueued from a background dispatcher, which OpEmitterImpl's editor call sites
     * already do.
     *
     * An op whose encoded DELTA frame cannot fit under [Frame.MAX_PAYLOAD_BYTES] is rejected here
     * rather than queued: [Frame.write] would throw [IllegalArgumentException] the moment this op
     * reached the wire, and that happens twice over for a bad entry — once (harmlessly) in
     * [outboundLoop], which treats any write failure as a dropped connection, and then again,
     * unguarded, on every subsequent reconnect's replay in [handleConnection], permanently
     * stranding the guest. Refusing it here means it never enters [deltaBuffer] or [outQueue] at
     * all, so neither path ever sees it.
     */
    fun enqueueOp(op: Op) {
        synchronized(enqueueLock) {
            // Check inside the lock: close() clears deltaBuffer under the same lock, so an append
            // can never land after the buffer is cleared (which would leak an entry for an ended
            // session).
            if (phase == Phase.Ended) return
            val seq = seqCounter.incrementAndGet()
            // Encode once here only to validate the size and to size the deltaBuffer entry; the bytes are
            // not retained (outboundLoop re-encodes from deltaBuffer at send time — see EncodedDelta).
            val encodedSize = OpCodec.encode(DeltaPayload(seq, op)).size
            // The guard must measure against the PLAINTEXT budget: writeSecure seals this payload, and
            // seal() adds SEAL_OVERHEAD_BYTES (counter + inner type + GCM tag). Measuring against the raw
            // MAX_PAYLOAD_BYTES let a near-max op pass here, then throw inside Frame.write at send time —
            // bouncing the live guest and permanently dropping the op (it is skipped on replay too).
            val maxPlaintext = Frame.MAX_PAYLOAD_BYTES - SessionCrypto.SEAL_OVERHEAD_BYTES
            if (encodedSize > maxPlaintext) {
                Log.w(
                    TAG,
                    "dropping op (seq=$seq, ${op.javaClass.simpleName}): encoded size ${encodedSize}B " +
                        "exceeds the sealed-frame plaintext budget (${maxPlaintext}B); it can never " +
                        "be sent as a single DELTA frame",
                )
                return
            }
            deltaBuffer.append(seq, op, encodedSize)
            outQueue.trySend(EncodedDelta(seq))
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                if (!scope.isActive) return
                // close() sets phase = Ended and _state.value = Ended(reason) BEFORE closing
                // serverSocket in its finally block; closing serverSocket is exactly what
                // unblocks this accept() with a SocketException. Without this guard, that
                // exception races scope.cancel() (nothing serializes them) and can land here
                // first, overwriting the deliberate close reason with a misreported
                // NetworkLost. Mirrors GuestSession.attemptReconnect()'s phase == Ended check.
                if (phase == Phase.Ended) return
                _state.value = CoopSessionState.Ended(CoopSessionState.EndReason.NetworkLost)
                return
            }
            // Each pre-auth handshake runs in its own coroutine. It used to run inline, so every
            // idle socket from any LAN peer held this loop for up to HANDSHAKE_TOTAL_TIMEOUT_MS
            // before the next accept() — a handful of them starved a legitimate guest's reconnect
            // past its 30 s window. Only adopting an authenticated guest is serialized (see
            // [adoptMutex]); everything before that is per-socket and runs concurrently.
            //
            // A failed handshake (peer vanished mid-HELLO, garbage payload, write error) must
            // never escape: before this guard, a single flaky or hostile client permanently
            // disabled hosting and leaked its socket.
            scope.launch {
                try {
                    handleConnection(socket)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    try { socket.close() } catch (_: Exception) {}
                }
            }
        }
    }

    /**
     * Serializes adopting an authenticated guest ([adopt]): the single-guest slot, the bulk/replay
     * and the switch to Live. Pre-auth handshakes run concurrently and never take it, so a stalled
     * unauthenticated peer can't hold it either.
     */
    private val adoptMutex = Mutex()

    private suspend fun handleConnection(socket: Socket) {
        // The pre-auth HELLO read is bound tightly: it no longer blocks other accepts (each runs in
        // its own coroutine), but an unauthenticated peer must not pin a socket and a coroutine for the
        // full live READ_TIMEOUT_MS (15s). Use the shorter handshake timeout for the HELLO read, then
        // widen to READ_TIMEOUT_MS for the live phase (guests ack every 1s and answer 5s PINGs, so 15s
        // of live silence means a dead/half-open peer).
        socket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // soTimeout is per read(): a peer trickling one byte every <5s would never trip it, so the
        // whole HELLO read also runs under a total deadline that closes the socket, and its payload
        // is capped to a few KB (a HELLO is a nonce, a 32-byte proof and a version) so a declared
        // 16 MB frame cannot force a 16 MB allocation before authentication.
        val helloTimedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val helloWatchdog = scope.launch {
            delay(HANDSHAKE_TOTAL_TIMEOUT_MS)
            helloTimedOut.set(true)
            try { socket.close() } catch (_: Exception) {}
        }
        val helloRead = try {
            withContext(Dispatchers.IO) { Frame.read(input, HELLO_MAX_PAYLOAD_BYTES) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (helloTimedOut.get()) {
                throw java.io.IOException("HELLO not received within ${HANDSHAKE_TOTAL_TIMEOUT_MS}ms", e)
            }
            throw e
        } finally {
            helloWatchdog.cancel()
        }
        val helloFrame = helloRead ?: return socket.close()
        if (helloFrame.type != FrameType.HELLO) {
            sendBye(output, CoopSessionState.EndReason.ProtocolError, crypto = null)
            socket.close(); return
        }
        val hello = OpCodec.decode<HelloPayload>(helloFrame.payload)

        // Verify the guest's proof of token knowledge (constant-time). The token itself is never
        // transmitted; proof = HMAC(prk(token), "gxr/hello" || guestNonce).
        val prk = SessionCrypto.prk(token)
        val expectedProof = SessionCrypto.helloProof(prk, hello.guestNonce)
        if (!java.security.MessageDigest.isEqual(hello.proof, expectedProof)) {
            writeFrameTimed(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.BadToken)),
            )
            socket.close(); return
        }
        // Replay guard. The proof binds only the guest's nonce, so a HELLO sniffed on the LAN
        // verifies again when resent — and would take the single guest slot, locking the real guest
        // out until the read timeout. Guests draw a fresh nonce per connection, so a repeat is a
        // replay. Recorded only after the proof checks out, so unauthenticated traffic can't fill it.
        if (!rememberGuestNonce(hello.guestNonce)) {
            writeFrameTimed(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.BadToken)),
            )
            socket.close(); return
        }
        if (hello.clientVersion != protocolVersion) {
            writeFrameTimed(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.VersionMismatch)),
            )
            socket.close(); return
        }
        if (!hostedSpatialFrame.supportsGuest(hello.localBackend)) {
            writeFrameTimed(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.SpatialIncompatible)),
            )
            socket.close(); return
        }

        adoptMutex.withLock {
            try {
                adopt(socket, input, output, hello, prk)
            } catch (e: Exception) {
                // adopt only throws before the live loops start (loop failures are caught inside
                // the loops and route to enterReconnecting). If it adopted this socket
                // (clientSocket === socket) but then threw — e.g. a write failed during
                // bulk/replay — reset host state too, or the host stays pointing at a dead socket
                // with nothing to clear it. Done under adoptMutex so a later adopt can't interleave.
                if (clientSocket === socket) {
                    clientSocket = null
                    activeCrypto = null
                }
                throw e
            }
        }
    }

    /**
     * Second half of the handshake, for a guest whose HELLO already verified. Runs under
     * [adoptMutex].
     */
    private suspend fun adopt(
        socket: Socket,
        input: java.io.InputStream,
        output: OutputStream,
        hello: HelloPayload,
        prk: ByteArray,
    ) {
        if (phase == Phase.Ended) { socket.close(); return }

        // Single guest only, but a verified HELLO while a connection is still live REPLACES it rather
        // than being refused. Only a token holder gets here (proof checked, nonce fresh), and in
        // practice that is the same guest reconnecting before this host noticed its old connection
        // die: a phone switching Wi-Fi, or a half-open TCP connection that won't time out for up to
        // READ_TIMEOUT_MS. Refusing it with AlreadyHosting used to end the guest's session (it mapped
        // that to HostClosed) — killing exactly the session the reconnect window exists to save.
        // enterReconnecting() closes the old socket, stops its live loops and starts the reconnect
        // watcher, which this adoption then satisfies by going Live. Two live phases still never
        // share outQueue/output: the old loops are cancelled, and their stale failures are ignored
        // (enterReconnecting's crypto check).
        val previous = clientSocket
        if (previous != null) {
            if (phase == Phase.Live) {
                enterReconnecting(activeCrypto)
            } else {
                // Adopted but never went Live: adopt's failure path would normally have cleared it.
                clientSocket = null
                activeCrypto = null
                try { previous.close() } catch (_: Exception) {}
            }
        }

        // Accept. Build the per-connection crypto from token + both nonces (fresh keys every
        // connection) and prove host identity to the guest before any bulk data.
        val hostNonce = randomNonce()
        val hostProof = SessionCrypto.helloOkProof(prk, hostNonce, hello.guestNonce)
        val crypto = SessionCrypto.forHost(token, sessionId, hello.guestNonce, hostNonce)
        activeCrypto = crypto
        writeFrameTimed(
            output,
            FrameType.HELLO_OK,
            OpCodec.encode(
                HelloOkPayload(
                    sessionId = sessionId,
                    protocolVersion = protocolVersion,
                    hostNonce = hostNonce,
                    hostProof = hostProof,
                    hostName = localDeviceName,
                )
            ),
        )

        clientSocket = socket
        val isReconnect = hello.lastAppliedSeq > 0
        lastAppliedSeq = hello.lastAppliedSeq
        guestBackend = hello.localBackend

        // Replay buffered deltas after lastAppliedSeq. Since seqs are assigned at enqueue, this
        // may overlap ops still sitting unsent in outQueue; the guest's monotonic
        // `seq > lastAppliedSeq` filter makes the duplicates harmless, and the replay completes
        // before the live outbound loop starts, so ordering holds.
        //
        // A null answer means eviction discarded ops this guest still needs, so replay cannot
        // reconstruct its canvas. That is not a failure — it is exactly the case bulk exists for,
        // and falling back to it is what turned buffer overflow from "end the session" into "send
        // more data once".
        //
        // A lastAppliedSeq beyond anything this session has assigned is equally out of range: the
        // guest claims ops this host never sent, so an (empty) replay would leave it on whatever
        // state it has. Serve it a bulk too.
        val inRange = lastAppliedSeq <= seqCounter.get()
        val replay = if (isReconnect && inRange) deltaBuffer.opsAfter(lastAppliedSeq) else null
        // Any replay failure falls back to a bulk. It used to log and skip the failed entry and go
        // Live anyway, leaving the guest one op short of the host with nothing to ever repair it.
        // (enqueueOp rejects an op that can't fit a single Frame, but a bad buffered entry can still
        // come from elsewhere, e.g. version skew with a peer running an older cap.) A bulk carries the
        // whole project, so the partial replay already written doesn't matter: the guest resets to the
        // snapshot (it accepts BULK_BEGIN mid-stream). If the stream itself is broken the bulk throws
        // too, which ends this attempt and leaves the reconnect watcher running, as before.
        val replayed = replay != null && try {
            replay.forEach { (seq, op) ->
                writeSecure(output, crypto, FrameType.DELTA, OpCodec.encode(DeltaPayload(seq, op)))
            }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
        if (!replayed) sendBulk(output, crypto)

        // Handshake done — widen the read timeout for the live phase.
        socket.soTimeout = READ_TIMEOUT_MS
        // A new connection never inherits the last one's stream request; the guest asks again.
        _videoWanted.value = false
        videoFrames.tryReceive()
        _state.value = CoopSessionState.Connected(peerName = hello.deviceName)
        phase = Phase.Live
        liveGeneration++

        // Tear down any prior live loops (e.g. from a previous connection before a reconnect)
        // before starting fresh ones, so two outbound loops never drain outQueue concurrently.
        liveJob?.cancelAndJoin()
        liveJob = scope.launch {
            coroutineScope {
                launch { outboundLoop(output, crypto) }
                launch { inboundLoop(input, output, crypto) }
                launch { heartbeatLoop(output, crypto) }
                launch { videoLoop(output, crypto) }
            }
        }
    }

    /** Guest nonces already accepted this session (bounded, oldest evicted). */
    private val seenGuestNonces = object : LinkedHashMap<String, Unit>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > 1024
    }

    /** False if [nonce] was already used in this session, i.e. the HELLO is a replay. */
    private fun rememberGuestNonce(nonce: ByteArray): Boolean = synchronized(seenGuestNonces) {
        val key = java.util.Base64.getEncoder().encodeToString(nonce)
        if (seenGuestNonces.containsKey(key)) false else { seenGuestNonces[key] = Unit; true }
    }

    private fun randomNonce(): ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }

    /**
     * Send a full project snapshot: to a fresh guest, or to a reconnecting one whose replay
     * request landed in a [DeltaBuffer] gap. Always re-reads the project via [snapshotProvider]
     * rather than any value captured earlier, so the guest gets the project as it stands right
     * now rather than a copy frozen at some earlier point (construction, or an earlier bulk send).
     */
    private suspend fun sendBulk(output: OutputStream, crypto: SessionCrypto) {
        // Read the seq counter BEFORE calling snapshotProvider(): every op site updates project
        // state before calling enqueueOp (which is what assigns/advances this counter), so any op
        // whose seq is <= this value is guaranteed to already be reflected in the snapshot the
        // provider is about to read — in MEMORY. The snapshot reads disk, which lags; see the grace
        // window in dropStaleQueueEntries.
        val seqCutoff = seqCounter.get()
        val snapshot = snapshotProvider()
        if (snapshot.spatialFrame != hostedSpatialFrame) {
            // A recapture changes the coordinate object itself. Continuing under one session id
            // would make the guest apply later design deltas in the old wall frame. End the session
            // instead; a new Host action advertises the new frame explicitly.
            close(CoopSessionState.EndReason.SpatialFrameChanged)
            throw CancellationException("host wall frame changed during co-op session")
        }
        require(
            snapshot.projectBytes.size <= Limits.MAX_BULK_BYTES &&
                snapshot.doodleBytes.size <= Limits.MAX_BULK_BYTES,
        ) {
            "project too large to host: ${snapshot.projectBytes.size}B project / " +
                "${snapshot.doodleBytes.size}B doodle (cap ${Limits.MAX_BULK_BYTES}B)"
        }
        // The snapshot just captured already contains every op enqueued up to seqCutoff (see
        // above), so any of those ops still sitting in outQueue — left over from a previous
        // guest's session that disconnected before they were sent, or from this same reconnect's
        // gap — must not also go out as live DELTA frames once outboundLoop starts: the guest is
        // about to start from this snapshot with a lastAppliedSeq below every one of those seqs,
        // so its `seq > lastAppliedSeq` filter would let every one of them through a second time,
        // re-applying (or double-appending, for e.g. StrokeComplete) state the snapshot already
        // has. Only ops enqueued strictly after this point are genuinely new and must ship live.
        dropStaleQueueEntries(seqCutoff)
        // Everything through seqCutoff is in the snapshot, so a queued seq at or below it whose op
        // is gone from deltaBuffer is not a hole for this guest (see outboundLoop).
        resyncedThroughSeq = seqCutoff
        writeSecure(
            output, crypto, FrameType.BULK_BEGIN,
            OpCodec.encode(
                BulkBeginPayload(
                    projectId = projectId,
                    layerCount = BulkBeginPayload.ONE_DESIGN,
                    doodleBytes = snapshot.doodleBytes.size,
                    projectBytes = snapshot.projectBytes.size,
                    spatialFrame = snapshot.spatialFrame,
                )
            ),
        )
        // Chunk payload into 64KB frames.
        chunkAndWrite(output, crypto, FrameType.BULK_DOODLE, snapshot.doodleBytes)
        chunkAndWrite(output, crypto, FrameType.BULK_PROJECT, snapshot.projectBytes)
        writeSecure(output, crypto, FrameType.BULK_END, ByteArray(0))
    }

    /**
     * Discard queued deltas with seq <= [seqCutoff]: they are already represented in the bulk
     * snapshot [sendBulk] is about to (or just did) send, so shipping them again afterward would
     * double-apply them on the guest. Runs under [enqueueLock] so a concurrent [enqueueOp] can't
     * slip an old-seq entry back in between the drain and the (rare) re-send of anything newer
     * found while draining — [Channel] has no peek, so anything past the cutoff pulled off while
     * scanning has to be put back rather than left undrained.
     */
    private fun dropStaleQueueEntries(seqCutoff: Long) {
        synchronized(enqueueLock) {
            val now = System.nanoTime() / 1_000_000L
            val keep = mutableListOf<EncodedDelta>()
            while (true) {
                val delta = outQueue.tryReceive().getOrNull() ?: break
                // Also keep recent ones: the snapshot is read from DISK, but op sites update memory,
            // emit, and persist later on a coroutine, so an op enqueued moments before the bulk
            // may be in neither the snapshot nor (if dropped) the live stream. Every live op is
            // absolute state, so re-applying one the snapshot already has converges; StrokeComplete,
            // the one append-style op, is ignored by the receiver today.
            if (delta.seq > seqCutoff || now - delta.enqueuedAtMs <= BULK_PERSIST_GRACE_MS) keep.add(delta)
            }
            keep.forEach { outQueue.trySend(it) }
        }
    }

    private suspend fun chunkAndWrite(output: OutputStream, crypto: SessionCrypto, type: FrameType, bytes: ByteArray) {
        val chunkSize = 64 * 1024
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            writeSecure(output, crypto, type, bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private suspend fun outboundLoop(output: OutputStream, crypto: SessionCrypto) {
        // The queue carries only seqs; re-encode each from deltaBuffer at send time. A null means the op
        // is no longer buffered. Usually it was superseded by a newer op (whose seq is also queued and
        // carries the absolute state), or a bulk already covered it, and skipping it is correct.
        //
        // But eviction can also drop an op BEFORE it was ever sent (a burst of big ops overflowing the
        // buffer while this loop is behind). Skipping that silently left a guest that stayed connected
        // diverged for good: only a reconnect was ever checked against the gap. So a missing op inside
        // the evicted range ([DeltaBuffer.mayHaveEvicted]) that no bulk has covered forces a resync:
        // drop this connection, and the guest's reconnect resumes below the gap, which [opsAfter]
        // answers with null and [adopt] answers with a bulk. A superseded op in that range triggers
        // it too — we can't tell the two apart cheaply, and a spurious bulk costs only bandwidth.
        for (delta in outQueue) {
            val op = deltaBuffer.opBySeq(delta.seq)
            if (op == null) {
                if (delta.seq > resyncedThroughSeq && deltaBuffer.mayHaveEvicted(delta.seq)) {
                    enterReconnecting(crypto)
                    return
                }
                continue
            }
            try {
                writeSecure(output, crypto, FrameType.DELTA, OpCodec.encode(DeltaPayload(delta.seq, op)))
            } catch (e: Exception) {
                // A reconnect handoff cancels liveJob (see enterReconnecting/handleConnection),
                // which surfaces here as a CancellationException from inside writeSecure/delay.
                // That is routine teardown of THIS loop, not a broken connection — swallowing it
                // like any other Exception would additionally call enterReconnecting() a second
                // time for the connection that is already being replaced, right as the freshly
                // reconnected guest's new loops are starting, which can kick it right back out.
                if (e is CancellationException) throw e
                // Connection broken; enter reconnecting. The op stays in deltaBuffer and is
                // replayed to the reconnecting guest from there.
                enterReconnecting(crypto)
                return
            }
        }
    }

    private suspend fun inboundLoop(input: java.io.InputStream, output: OutputStream, crypto: SessionCrypto) {
        while (scope.isActive) {
            val frame = try {
                readSecure(input, crypto) ?: run { enterReconnecting(crypto); return }
            } catch (e: Exception) {
                // See outboundLoop's matching comment: a reconnect handoff cancels this loop via
                // liveJob, and that must propagate as cancellation, not be misread as the read
                // itself failing and trigger a second, spurious enterReconnecting().
                if (e is CancellationException) throw e
                enterReconnecting(crypto); return
            }
            try {
                when (frame.type) {
                    FrameType.DELTA_ACK -> {
                        val ack = OpCodec.decode<DeltaAckPayload>(frame.payload)
                        deltaBuffer.trimUpTo(ack.lastSeq)
                    }
                    FrameType.PING -> {
                        val ping = OpCodec.decode<PingPayload>(frame.payload)
                        try {
                            writeSecure(output, crypto, FrameType.PONG, OpCodec.encode(ping))
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            enterReconnecting(crypto); return
                        }
                    }
                    FrameType.BULK_ACK -> { /* bulk done; ignore */ }
                    FrameType.VIDEO_WANT -> {
                        _videoWanted.value = OpCodec.decode<VideoWantPayload>(frame.payload).wanted
                    }
                    FrameType.GUEST_OP -> {
                        val guestOp = OpCodec.decode<GuestOpPayload>(frame.payload)
                        handleGuestOp(guestOp)
                        try {
                            writeSecure(
                                output, crypto, FrameType.GUEST_OP_ACK,
                                OpCodec.encode(GuestOpAckPayload(lastGuestSeqHandled)),
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            enterReconnecting(crypto); return
                        }
                    }
                    FrameType.BYE -> {
                        // The guest is closing voluntarily; report the reason it actually sent
                        // (typically UserLeft) rather than the host's own HostClosed, which this
                        // guest-initiated BYE never is.
                        val bye = OpCodec.decode<ByePayload>(frame.payload)
                        close(bye.reason)
                        return
                    }
                    else -> {
                        // Unexpected frame in this direction; ignore but log.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Authenticated != trusted: a token-holding guest can still send a frame whose inner
                // payload is not valid CBOR for its declared type. Decoding threw — drop the connection
                // instead of letting it escape this loop and crash the process.
                Log.w(TAG, "undecodable ${frame.type} frame from guest; reconnecting", e)
                enterReconnecting(crypto); return
            }
        }
    }

    /**
     * Apply one guest edit at most once. Accepted ops go to [onGuestOp] and are then re-enqueued as
     * ordinary host deltas, so the guest (and its replay buffer, and any later bulk) sees them in the
     * host's single total order — the order both peers converge on when they edit concurrently. A
     * refused op ([GuestOpPolicy]) is still marked handled so the guest stops resending it.
     */
    private fun handleGuestOp(payload: GuestOpPayload) {
        if (payload.guestInstance != guestInstance) {
            // A different GuestSession (a fresh join, or the app restarted): its counter restarts.
            guestInstance = payload.guestInstance
            lastGuestSeqHandled = 0L
        }
        if (payload.guestSeq <= lastGuestSeqHandled) return
        lastGuestSeqHandled = payload.guestSeq
        if (!GuestOpPolicy.allows(payload.op, hostedSpatialFrame.hostBackend, guestBackend)) {
            Log.w(TAG, "refusing guest ${payload.op.javaClass.simpleName} (guestSeq=${payload.guestSeq})")
            return
        }
        try {
            onGuestOp(payload.op)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "guest op handler failed", e)
        }
    }

    private suspend fun heartbeatLoop(output: OutputStream, crypto: SessionCrypto) {
        while (scope.isActive) {
            delay(5_000)
            try {
                writeSecure(output, crypto, FrameType.PING, OpCodec.encode(PingPayload(System.currentTimeMillis())))
            } catch (e: Exception) {
                // See outboundLoop's matching comment.
                if (e is CancellationException) throw e
                enterReconnecting(crypto); return
            }
        }
    }

    /**
     * Drop the live connection and open the reconnect window. [from] is the crypto of the
     * connection whose loop is reporting the failure: a report from a connection that is no longer
     * the active one (its loops were cancelled by a replacement HELLO, but a blocked read only
     * fails once its socket closes) is ignored, so it can't knock the new connection offline.
     */
    private fun enterReconnecting(from: SessionCrypto?) {
        // Atomic check-then-set: concurrent failures in the outbound/inbound/heartbeat loops
        // must collapse into a single transition (and a single 30s timeout watcher below).
        synchronized(phaseLock) {
            if (phase == Phase.Reconnecting || phase == Phase.Ended) return
            if (from !== activeCrypto) return
            phase = Phase.Reconnecting
        }
        _state.value = CoopSessionState.Reconnecting
        val old = clientSocket
        clientSocket = null
        // The old channel's keys/counters die with the connection; the next handshake mints fresh
        // ones. handleConnection sets activeCrypto again before the new live loops start.
        activeCrypto = null
        try { old?.close() } catch (_: Exception) {}
        // Stop the current live loops so the suspended outbound loop stops consuming outQueue
        // during the reconnect window; queued ops wait for the next connection's outbound loop.
        liveJob?.cancel()
        // Wait for the guest to reconnect on the session scope (not liveJob, which we just
        // cancelled) so this timeout survives the live-loop teardown. "Reconnected" means a new
        // connection actually went Live (liveGeneration moved), not merely that one was adopted:
        // a reconnect that fails during bulk/replay must leave this timeout running.
        val generationAtDrop = liveGeneration
        scope.launch {
            val reconnected = withTimeoutOrNull(reconnectWindowMs) {
                while (liveGeneration == generationAtDrop && isActive) delay(200)
                true
            } ?: false
            if (!reconnected && phase != Phase.Ended) {
                close(CoopSessionState.EndReason.NetworkLost)
            }
        }
    }

    /**
     * Send a BYE. Before the handshake completes (rejection paths) [crypto] is null and the BYE
     * is plaintext; once a live connection exists it is sealed like every other frame so the
     * guest, which only accepts ENC frames post-handshake, can act on the reason.
     *
     * The sealed BYE goes through [writeMutex] like every other sealed frame: sealing and writing it
     * outside the lock raced the live loops for crypto's send counter (GCM nonce reuse) and
     * interleaved its bytes with a frame mid-write. The lock wait is bounded by [BYE_LOCK_TIMEOUT_MS]
     * so a write wedged on a dead guest can't stall teardown; the BYE is simply skipped then.
     */
    private suspend fun sendBye(output: OutputStream, reason: CoopSessionState.EndReason, crypto: SessionCrypto?) {
        try {
            val payload = OpCodec.encode(ByePayload(reason))
            if (crypto != null) {
                // lock() is cancellable and releases the lock if cancelled after acquiring it, so a
                // timeout here never leaves the mutex held.
                val locked = withTimeoutOrNull(BYE_LOCK_TIMEOUT_MS) { writeMutex.lock(); true } ?: false
                if (!locked) return
                try {
                    writeFrameTimed(output, FrameType.ENC, crypto.seal(FrameType.BYE, payload))
                } finally {
                    writeMutex.unlock()
                }
            } else {
                writeFrameTimed(output, FrameType.BYE, payload)
            }
        } catch (_: Exception) { /* socket may already be closed, or the write timed out */ }
    }

    private companion object {
        /**
         * How long a queued op survives a bulk send even when its seq is covered by the snapshot:
         * long enough for the editor's deferred save to reach disk, which is what the snapshot reads.
         */
        private const val BULK_PERSIST_GRACE_MS = 5_000L

        // How long close() waits for the write lock before giving up on the best-effort BYE.
        private const val BYE_LOCK_TIMEOUT_MS = 1_000L

        private const val TAG = "HostSession"

        // Guests ack every 1s and answer 5s PINGs, so 15s of read silence means a dead or
        // half-open peer.
        const val READ_TIMEOUT_MS = 15_000

        // Pre-auth HELLO read bound. Handshakes run concurrently (acceptLoop launches each), so a peer
        // that connects and sends nothing only pins its own socket and coroutine for this long — kept
        // short (5s) anyway, while the live phase widens back to READ_TIMEOUT_MS.
        const val HANDSHAKE_READ_TIMEOUT_MS = 5_000

        /** Total budget for the pre-auth HELLO frame, however slowly the peer trickles it. */
        const val HANDSHAKE_TOTAL_TIMEOUT_MS = 8_000L

        /** A HELLO is tiny; anything bigger pre-auth is hostile or broken. */
        const val HELLO_MAX_PAYLOAD_BYTES = 4 * 1024

        // Plain java.net.Socket exposes no write-side timeout, so writeFrameTimed enforces one
        // itself. Mirrors READ_TIMEOUT_MS: a guest that stops reading for this long (backgrounded,
        // throttled, or hostile) is treated as gone rather than left to block adoption.
        const val WRITE_TIMEOUT_MS = 15_000L
    }

    override suspend fun close(reason: CoopSessionState.EndReason) {
        phase = Phase.Ended
        _state.value = CoopSessionState.Ended(reason)
        try {
            clientSocket?.let { sock ->
                // getOutputStream() can throw if the socket is already closed; that must not
                // skip the serverSocket/queue/scope teardown below (which would leak the port).
                try { sendBye(sock.getOutputStream(), reason, activeCrypto) } catch (_: Exception) {}
                try { sock.close() } catch (_: Exception) {}
            }
        } finally {
            clientSocket = null
            activeCrypto = null
            try { serverSocket?.close() } catch (_: Exception) {}
            // Under enqueueLock so a concurrent enqueueOp (which checks phase and appends under
            // the same lock) can't append to deltaBuffer or send after this cleanup.
            synchronized(enqueueLock) {
                outQueue.close()
                deltaBuffer.clear()
            }
            scope.cancel()
        }
    }
}

/**
 * The project is bigger than one Link bulk transfer may be ([Limits.MAX_BULK_BYTES], held in memory
 * on both ends). Export, which streams, takes up to twice that; this says which limit was hit.
 */
class ProjectTooLargeToHostException(val bytes: Long, val capBytes: Long) :
    IllegalArgumentException("project too large to host: ${bytes}B (cap ${capBytes}B)")

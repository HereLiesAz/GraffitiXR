// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSession.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import android.util.Log
import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.ProjectSnapshot
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkBeginPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.ByePayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaAckPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Frame
import com.hereliesaz.graffitixr.core.collaboration.wire.FrameType
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloOkPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloRejectedPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Limits
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import com.hereliesaz.graffitixr.core.collaboration.wire.PingPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.SessionCrypto
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
) : Session() {

    init {
        // The guest rejects bulk transfers above this cap with a bare require() that surfaces as
        // an unexplained NetworkLost on its side; failing fast here turns an oversized project
        // into an immediate, attributable hosting error instead. This only validates size against
        // whatever the project looks like right now — [sendBulk] validates again every time it
        // actually sends one, since the project this check saw at construction is not necessarily
        // the one sent later.
        val probe = snapshotProvider()
        require(probe.projectBytes.size <= Limits.MAX_BULK_BYTES && probe.fingerprintBytes.size <= Limits.MAX_BULK_BYTES) {
            "project too large to host: ${probe.projectBytes.size}B project / ${probe.fingerprintBytes.size}B fingerprint " +
                "(cap ${Limits.MAX_BULK_BYTES}B)"
        }
    }

    /** An op with its sequence number and wire encoding, fixed at enqueue time. */
    private class EncodedDelta(val seq: Long, val bytes: ByteArray)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Unbounded channel, but NOT unbounded memory: every element is appended to deltaBuffer
    // first (real encoded size), whose 48 MB / 2000-op cap (see DeltaBuffer.kt) evicts oldest
    // entries and records a gap on overflow rather than ending the session. The previous design
    // (bounded 128 + DROP_OLDEST, seq assigned at send time) silently discarded ops that
    // overflowed during a reconnect window before they ever reached the DeltaBuffer, so the
    // guest's canvas diverged with no signal.
    private val outQueue: Channel<EncodedDelta> = Channel(capacity = Channel.UNLIMITED)
    private val deltaBuffer = DeltaBuffer()
    private val seqCounter = AtomicLong(0L)
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
    private var liveJob: Job? = null

    // Serializes all writes to the single connected guest's OutputStream. The outbound,
    // inbound (PONG) and heartbeat (PING) loops write concurrently, and Frame.write issues
    // several stream writes per frame, so without this lock their bytes interleave and
    // corrupt the wire framing.
    private val writeMutex = Mutex()

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
     * cannot block this write forever. acceptLoop calls handleConnection synchronously and
     * single-threaded, so an unbounded write here — the handshake replies, [sendBulk], and the
     * reconnect replay all go through this path — would wedge the accept loop for every
     * subsequent joiner indefinitely.
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
            val bytes = OpCodec.encode(DeltaPayload(seq, op))
            if (bytes.size > Frame.MAX_PAYLOAD_BYTES) {
                Log.w(
                    TAG,
                    "dropping op (seq=$seq, ${op.javaClass.simpleName}): encoded size ${bytes.size}B " +
                        "exceeds Frame.MAX_PAYLOAD_BYTES (${Frame.MAX_PAYLOAD_BYTES}B); it can never " +
                        "be sent as a single DELTA frame",
                )
                return
            }
            deltaBuffer.append(seq, op, bytes.size)
            outQueue.trySend(EncodedDelta(seq, bytes))
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
            // A failed handshake (peer vanished mid-HELLO, garbage payload, write error) must
            // never kill this loop: before this guard, a single flaky or hostile client
            // permanently disabled hosting and leaked its socket.
            try {
                handleConnection(socket)
            } catch (_: Exception) {
                // handleConnection only throws before the live loops start (loop failures are
                // caught inside the loops and route to enterReconnecting). If it adopted this
                // socket (clientSocket === socket) but then threw — e.g. a write failed during
                // bulk/replay — reset host state too, or the host stays wedged pointing at a dead
                // socket, rejecting new guests as AlreadyHosting with no reconnect watcher running.
                if (clientSocket === socket) {
                    clientSocket = null
                    activeCrypto = null
                }
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        // Bound every read on this connection (handshake and live). Guests ack every 1s and
        // answer PINGs sent every 5s, so 15s of silence means a dead/half-open peer — without
        // this, Frame.read blocks forever and a vanished guest is never detected.
        socket.soTimeout = READ_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val helloFrame = Frame.read(input) ?: return socket.close()
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
        if (hello.clientVersion != protocolVersion) {
            writeFrameTimed(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.VersionMismatch)),
            )
            socket.close(); return
        }

        // Single guest only. A reconnecting guest is fine because enterReconnecting() nulls
        // clientSocket before the new connection; a *second* concurrent guest is rejected so
        // two live phases never share (and interleave on) the same outQueue/output.
        if (clientSocket != null) {
            writeFrameTimed(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.AlreadyHosting)),
            )
            socket.close(); return
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

        // Replay buffered deltas after lastAppliedSeq. Since seqs are assigned at enqueue, this
        // may overlap ops still sitting unsent in outQueue; the guest's monotonic
        // `seq > lastAppliedSeq` filter makes the duplicates harmless, and the replay completes
        // before the live outbound loop starts, so ordering holds.
        //
        // A null answer means eviction discarded ops this guest still needs, so replay cannot
        // reconstruct its canvas. That is not a failure — it is exactly the case bulk exists for,
        // and falling back to it is what turned buffer overflow from "end the session" into "send
        // more data once".
        val replay = if (isReconnect) deltaBuffer.opsAfter(lastAppliedSeq) else null
        if (replay != null) {
            replay.forEach { (seq, op) ->
                // Each entry gets its own try/catch: enqueueOp now rejects an op that can't fit a
                // single Frame before it ever reaches deltaBuffer, but this stays defensive against
                // any other cause of a bad/oversized buffered entry (e.g. version skew with a peer
                // running an older cap). Without this, one bad entry threw uncaught here, which
                // killed this fresh connection on every reconnect attempt — the guest reconnects
                // successfully at the transport layer and is killed again each time, stranded until
                // its reconnect window expires. Log and skip instead.
                try {
                    writeSecure(output, crypto, FrameType.DELTA, OpCodec.encode(DeltaPayload(seq, op)))
                } catch (e: Exception) {
                    Log.w(TAG, "skipping unsendable replay entry (seq=$seq, ${op.javaClass.simpleName})", e)
                }
            }
        } else {
            sendBulk(output, crypto)
        }

        _state.value = CoopSessionState.Connected(peerName = hello.deviceName)
        phase = Phase.Live

        // Tear down any prior live loops (e.g. from a previous connection before a reconnect)
        // before starting fresh ones, so two outbound loops never drain outQueue concurrently.
        liveJob?.cancelAndJoin()
        liveJob = scope.launch {
            coroutineScope {
                launch { outboundLoop(output, crypto) }
                launch { inboundLoop(input, output, crypto) }
                launch { heartbeatLoop(output, crypto) }
            }
        }
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
        // provider is about to read. That ordering is what makes dropStaleQueueEntries below safe.
        val seqCutoff = seqCounter.get()
        val snapshot = snapshotProvider()
        require(
            snapshot.projectBytes.size <= Limits.MAX_BULK_BYTES &&
                snapshot.fingerprintBytes.size <= Limits.MAX_BULK_BYTES,
        ) {
            "project too large to host: ${snapshot.projectBytes.size}B project / " +
                "${snapshot.fingerprintBytes.size}B fingerprint (cap ${Limits.MAX_BULK_BYTES}B)"
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
        writeSecure(
            output, crypto, FrameType.BULK_BEGIN,
            OpCodec.encode(
                BulkBeginPayload(
                    projectId = projectId,
                    layerCount = snapshot.layerCount,
                    fingerprintBytes = snapshot.fingerprintBytes.size,
                    projectBytes = snapshot.projectBytes.size,
                )
            ),
        )
        // Chunk payload into 64KB frames.
        chunkAndWrite(output, crypto, FrameType.BULK_FINGERPRINT, snapshot.fingerprintBytes)
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
            val keep = mutableListOf<EncodedDelta>()
            while (true) {
                val delta = outQueue.tryReceive().getOrNull() ?: break
                if (delta.seq > seqCutoff) keep.add(delta)
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
        // Seq/encoding/DeltaBuffer accounting all happened in enqueueOp; this loop only ships
        // the pre-encoded delta payloads, sealed here.
        for (delta in outQueue) {
            try {
                writeSecure(output, crypto, FrameType.DELTA, delta.bytes)
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
                enterReconnecting()
                return
            }
        }
    }

    private suspend fun inboundLoop(input: java.io.InputStream, output: OutputStream, crypto: SessionCrypto) {
        while (scope.isActive) {
            val frame = try {
                readSecure(input, crypto) ?: run { enterReconnecting(); return }
            } catch (e: Exception) {
                // See outboundLoop's matching comment: a reconnect handoff cancels this loop via
                // liveJob, and that must propagate as cancellation, not be misread as the read
                // itself failing and trigger a second, spurious enterReconnecting().
                if (e is CancellationException) throw e
                enterReconnecting(); return
            }
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
                        enterReconnecting(); return
                    }
                }
                FrameType.BULK_ACK -> { /* bulk done; ignore */ }
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
                enterReconnecting(); return
            }
        }
    }

    private fun enterReconnecting() {
        // Atomic check-then-set: concurrent failures in the outbound/inbound/heartbeat loops
        // must collapse into a single transition (and a single 30s timeout watcher below).
        synchronized(phaseLock) {
            if (phase == Phase.Reconnecting || phase == Phase.Ended) return
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
        // cancelled) so this timeout survives the live-loop teardown.
        scope.launch {
            val reconnected = withTimeoutOrNull(30_000L) {
                while (clientSocket == null && isActive) delay(200)
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
     */
    private suspend fun sendBye(output: OutputStream, reason: CoopSessionState.EndReason, crypto: SessionCrypto?) {
        try {
            val payload = OpCodec.encode(ByePayload(reason))
            if (crypto != null) {
                writeFrameTimed(output, FrameType.ENC, crypto.seal(FrameType.BYE, payload))
            } else {
                writeFrameTimed(output, FrameType.BYE, payload)
            }
        } catch (_: Exception) { /* socket may already be closed, or the write timed out */ }
    }

    private companion object {
        private const val TAG = "HostSession"

        // Guests ack every 1s and answer 5s PINGs, so 15s of read silence means a dead or
        // half-open peer. Also bounds handshake reads in handleConnection, so a stalled
        // client can block the accept loop for at most this long.
        const val READ_TIMEOUT_MS = 15_000

        // Plain java.net.Socket exposes no write-side timeout, so writeFrameTimed enforces one
        // itself. Mirrors READ_TIMEOUT_MS: a guest that stops reading for this long (backgrounded,
        // throttled, or hostile) is treated as gone rather than left to block the accept loop.
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

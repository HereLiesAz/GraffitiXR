package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopSpatialFrame
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

internal class GuestSession(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val protocolVersion: Int,
    private val localDeviceName: String,
    private val localBackend: CoopTrackingBackend,
    private val onBulkReceived: suspend (
        fingerprint: ByteArray,
        project: ByteArray,
        spatialFrame: CoopSpatialFrame,
    ) -> Unit,
    private val onOp: suspend (Op) -> Unit,
    // How long (and how often) to retry reconnecting before giving up. Injectable so tests can
    // use a short, deterministic window instead of waiting the full production 30s.
    private val reconnectWindowMs: Long = 30_000L,
    private val reconnectIntervalMs: Long = 2_000L,
) : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var sessionId: String? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    // Read from severGuest()-style test helpers and closed from close()/attemptReconnect() on
    // whichever coroutine is running, while connectLoop() (a different IO thread each attempt)
    // assigns it. Without @Volatile a writer's assignment is not guaranteed visible to a reader
    // on another thread, unlike its neighbours sessionId/lastAppliedSeq above.
    @Volatile private var socket: Socket? = null
    // The crypto for the current live connection, so close() can seal a BYE on it. Cleared on
    // every reconnect attempt (mirrors HostSession.activeCrypto) so close() never seals a BYE
    // with keys bound to an already-dead connection.
    @Volatile private var activeCrypto: SessionCrypto? = null

    // ── Protocol v4: this guest's own edits ──────────────────────────────────────────────────
    private val outbox = GuestOutbox()
    // Identifies this GuestSession to the host's dedupe; a new instance restarts guestSeq at 1.
    private val guestInstance: String = java.util.UUID.randomUUID().toString()
    // The live connection's stream/crypto for flushing the outbox; null outside the live phase.
    @Volatile private var liveOutput: OutputStream? = null
    @Volatile private var liveCrypto: SessionCrypto? = null
    // From BULK_BEGIN; GuestOpPolicy needs it. Kept across a replay-only reconnect (same host).
    @Volatile private var hostBackend: CoopTrackingBackend? = null
    // Highest guestSeq written on the CURRENT connection, and highest the host has acknowledged.
    // A new connection rewinds the first to the second, so unacknowledged edits are resent.
    @Volatile private var lastSentGuestSeq: Long = 0L
    @Volatile private var lastAckedGuestSeq: Long = 0L
    private val flushMutex = Mutex()
    // True once a host snapshot is installed locally (Live reached). Until then the local project is
    // whatever the guest had open before joining — or, after a host restart, a baseline about to be
    // replaced — so an edit made against it must not be queued for the host.
    @Volatile private var snapshotInstalled = false
    // Set when this guest replaced its design locally (a refused DesignReplace). Its later design
    // ops — the paired DesignBitmapReplace, transforms, props, placements — describe THAT local
    // design, not the host's, so they are refused too until the host's design is back (a bulk
    // snapshot or the host's own DesignReplace).
    @Volatile private var designDiverged = false

    /**
     * Send one of this guest's own edits to the host. Returns false — and sends nothing — when the
     * host would refuse it ([GuestOpPolicy]) or it cannot fit a single sealed frame; the caller
     * reports that the edit stays local. Otherwise the edit is queued until acknowledged, surviving
     * a reconnect, and the host's re-broadcast of it arrives back as an ordinary DELTA.
     */
    fun sendOp(op: Op): Boolean {
        if (phase == Phase.Ended || !snapshotInstalled) return false
        if (op is Op.DesignReplace) designDiverged = true
        // A wall width is project state, not design state, so a locally replaced design doesn't hold it back.
        if (designDiverged && op !is Op.WallWidth) return false
        if (!GuestOpPolicy.allows(op, hostBackend, localBackend)) return false
        val maxPlaintext = Frame.MAX_PAYLOAD_BYTES - SessionCrypto.SEAL_OVERHEAD_BYTES
        if (OpCodec.encode(GuestOpPayload(guestInstance, Long.MAX_VALUE, op)).size > maxPlaintext) return false
        outbox.add(op)
        scope.launch { flushOutbox() }
        return true
    }

    /** Write every queued edit not yet sent on this connection. A write failure leaves them queued. */
    private suspend fun flushOutbox() {
        flushMutex.withLock {
            val output = liveOutput ?: return
            val crypto = liveCrypto ?: return
            if (phase != Phase.Live) return
            for ((seq, op) in outbox.pendingAfter(lastSentGuestSeq)) {
                try {
                    writeSecure(output, crypto, FrameType.GUEST_OP, OpCodec.encode(GuestOpPayload(guestInstance, seq, op)))
                    lastSentGuestSeq = seq
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return // the read loop notices the dead socket and reconnects; the op stays queued
                }
            }
        }
    }

    private fun randomNonce(): ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    // Guards the check-then-set phase transition in attemptReconnect(): the inbound loop and a
    // failed PONG write can race into it, and without the lock each would run its own full
    // reconnect loop against the same host.
    private val phaseLock = Any()

    // Serializes writes to the host: the inbound loop (PONG) and the periodic DELTA_ACK loop
    // both write to the same OutputStream, so without this lock their frames interleave.
    private val writeMutex = Mutex()

    // Seal-then-write every post-handshake frame under the write lock (also serializes crypto's
    // send counter across the inbound-PONG and DELTA_ACK loops).
    private suspend fun writeSecure(output: OutputStream, crypto: SessionCrypto, type: FrameType, payload: ByteArray) {
        writeMutex.withLock {
            val sealed = crypto.seal(type, payload)
            // java.net.Socket has no write-side timeout; without this, a host that stops reading parks
            // this blocking write (and the write lock) until the read loop's own timeout fires. Mirror
            // the host's writeFrameTimed: a watchdog force-closes the stream after WRITE_TIMEOUT_MS,
            // unblocking the write as an IOException the caller treats as a dropped connection.
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = scope.launch {
                delay(WRITE_TIMEOUT_MS)
                timedOut.set(true)
                try { output.close() } catch (_: Exception) {}
            }
            try {
                withContext(Dispatchers.IO) {
                    Frame.write(output, FrameType.ENC, sealed)
                    output.flush()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (timedOut.get()) throw java.io.IOException("write timed out after ${WRITE_TIMEOUT_MS}ms", e)
                throw e
            } finally {
                watchdog.cancel()
            }
        }
    }

    // Read one post-handshake frame: it must be an ENC envelope; open it to the inner frame.
    private fun readSecure(input: InputStream, crypto: SessionCrypto): Frame.FrameRead? {
        val frame = Frame.read(input) ?: return null
        if (frame.type != FrameType.ENC) throw java.io.IOException("expected ENC frame, got ${frame.type}")
        return crypto.open(frame.payload)
    }

    suspend fun connect() {
        scope.launch {
            // connectLoop never throws; it returns false on a transient failure. On the very
            // first attempt there is nothing to reconnect to, so a failure ends the session.
            if (!connectLoop(isReconnect = false) && phase != Phase.Ended) {
                close(CoopSessionState.EndReason.NetworkLost)
            }
        }
    }

    /**
     * Attempts one connect+handshake. Returns true once the live phase has started, false on a
     * transient failure (so the reconnect loop can retry). Terminal handshake rejections close
     * the session themselves and return false; callers must check [phase] before re-closing.
     */
    private suspend fun connectLoop(isReconnect: Boolean): Boolean {
        return try {
            val s = Socket()
            // Publish the socket to the field before connecting so a connect() failure (timeout /
            // refused / unreachable) still has its descriptor closed by the catch below — otherwise
            // the local would leak one FD per failed attempt across the reconnect loop.
            socket = s
            s.connect(java.net.InetSocketAddress(host, port), 5000)
            // Bound every read (handshake, bulk, live). The host sends PING every 5s, so 15s of
            // silence means a dead/half-open host — without this, Frame.read blocks forever and
            // the reconnect path never triggers.
            s.soTimeout = READ_TIMEOUT_MS
            val input = s.getInputStream()
            val output = s.getOutputStream()

            // Send HELLO. The token is never transmitted; prove knowledge of it with an HMAC over
            // a fresh nonce that also seeds the per-connection key schedule.
            val prk = SessionCrypto.prk(token)
            val guestNonce = randomNonce()
            val proof = SessionCrypto.helloProof(prk, guestNonce)
            Frame.write(
                output,
                FrameType.HELLO,
                OpCodec.encode(
                    HelloPayload(
                        guestNonce = guestNonce,
                        proof = proof,
                        clientVersion = protocolVersion,
                        deviceName = localDeviceName,
                        localBackend = localBackend,
                        lastAppliedSeq = if (isReconnect) lastAppliedSeq else 0L,
                    )
                ),
            )
            output.flush()

            val response = Frame.read(input) ?: error("peer closed before HELLO_OK")
            when (response.type) {
                FrameType.HELLO_OK -> {
                    val helloOk = OpCodec.decode<HelloOkPayload>(response.payload)
                    // Authenticate the host before trusting any bulk data: it must prove token
                    // knowledge bound to both nonces.
                    val expectedHostProof = SessionCrypto.helloOkProof(prk, helloOk.hostNonce, guestNonce)
                    if (!java.security.MessageDigest.isEqual(helloOk.hostProof, expectedHostProof)) {
                        try { s.close() } catch (_: Exception) {}
                        socket = null
                        close(CoopSessionState.EndReason.BadToken)
                        return false
                    }
                    if (isReconnect && sessionId != null && helloOk.sessionId != sessionId) {
                        // The host restarted with a fresh session (new seq space and empty
                        // DeltaBuffer): resuming with our lastAppliedSeq would make the host
                        // replay nothing and leave this guest silently desynced. Reset local
                        // resume state and fail this attempt — the reconnect loop retries as a
                        // fresh join (lastAppliedSeq == 0), which triggers a full bulk re-sync.
                        sessionId = null
                        lastAppliedSeq = 0L
                        // Edits queued for the old host session may or may not have been applied
                        // there; replaying them onto a new host session's freshly bulk-synced project
                        // could overwrite newer host edits. The fresh bulk is the new baseline.
                        outbox.clear()
                        snapshotInstalled = false
                        lastAckedGuestSeq = 0L
                        lastSentGuestSeq = 0L
                        try { s.close() } catch (_: Exception) {}
                        socket = null
                        return false
                    }
                    sessionId = helloOk.sessionId
                    val crypto = SessionCrypto.forGuest(token, helloOk.sessionId, guestNonce, helloOk.hostNonce)
                    activeCrypto = crypto
                    // The first post-handshake frame tells us which path the host took. A fresh
                    // join (isReconnect == false) always gets a full bulk snapshot. A reconnect
                    // gets EITHER a replay (plain DELTA/PING frames, handled below exactly like any
                    // other live frame) OR — when the host's DeltaBuffer can no longer answer the
                    // replay (a gap) — the same bulk snapshot a fresh join gets. Previously only the
                    // !isReconnect branch ever called receiveBulk(), so a reconnecting guest that hit
                    // a gap received BULK_* frames it had no handler for: they fell into livePhase's
                    // `else -> {}` and were silently discarded, leaving the guest desynced forever.
                    val first = readSecure(input, crypto) ?: error("peer closed before first live frame")
                    if (first.type == FrameType.BULK_BEGIN) {
                        receiveBulk(first, input, output, crypto)
                    } else {
                        // Only a reconnect may skip the bulk snapshot; a fresh join must always
                        // start with one.
                        require(isReconnect) { "expected BULK_BEGIN for a fresh join, got ${first.type}" }
                    }
                    snapshotInstalled = true
                    // The host's own name, now that HELLO_OK carries it. Falls back to "host" for
                    // a peer that predates the field — the same string this used to hard-code.
                    _state.value = CoopSessionState.Connected(
                        peerName = helloOk.hostName.ifBlank { "host" },
                    )
                    phase = Phase.Live
                    livePhase(input, output, crypto, firstFrame = if (first.type == FrameType.BULK_BEGIN) null else first)
                    true
                }
                FrameType.HELLO_REJECTED -> {
                    val rej = OpCodec.decode<HelloRejectedPayload>(response.payload)
                    val reason = when (rej.reason) {
                        HelloRejectedPayload.RejectReason.BadToken -> CoopSessionState.EndReason.BadToken
                        HelloRejectedPayload.RejectReason.VersionMismatch -> CoopSessionState.EndReason.VersionMismatch
                        HelloRejectedPayload.RejectReason.SpatialIncompatible -> CoopSessionState.EndReason.SpatialIncompatible
                        HelloRejectedPayload.RejectReason.AlreadyHosting -> CoopSessionState.EndReason.HostClosed
                    }
                    close(reason)
                    false
                }
                else -> {
                    close(CoopSessionState.EndReason.ProtocolError)
                    false
                }
            }
        } catch (_: Exception) {
            // Transient: let the caller decide whether to retry (reconnect) or end the session.
            // Close the half-open socket now so a failed attempt doesn't leak an FD — the reconnect
            // loop reassigns the field on the next try and would otherwise orphan this one.
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            false
        }
    }

    private suspend fun receiveBulk(
        begin: Frame.FrameRead,
        input: InputStream,
        output: OutputStream,
        crypto: SessionCrypto,
    ) {
        require(begin.type == FrameType.BULK_BEGIN)
        // A snapshot replaces whatever design this guest had diverged to.
        designDiverged = false
        val beginPayload = OpCodec.decode<BulkBeginPayload>(begin.payload)
        require(beginPayload.spatialFrame.supportsGuest(localBackend)) {
            "host spatial frame is incompatible with $localBackend"
        }
        hostBackend = beginPayload.spatialFrame.hostBackend

        val fingerprint = receiveChunked(input, crypto, FrameType.BULK_FINGERPRINT, beginPayload.fingerprintBytes)
        val project = receiveChunked(input, crypto, FrameType.BULK_PROJECT, beginPayload.projectBytes)

        val end = readSecure(input, crypto) ?: error("EOF before BULK_END")
        require(end.type == FrameType.BULK_END)

        // The snapshot is the new baseline, so the seq this guest had applied before it no longer
        // describes its state. BULK_* carries no base seq; the host treats a bulk receiver like a
        // fresh join (it drops queued deltas the snapshot covers and expects the guest to take every
        // DELTA after it). Keeping an old, possibly higher lastAppliedSeq would make the `seq >
        // lastAppliedSeq` filter silently drop those. Any delta at or below the snapshot that still
        // arrives is absolute state, so re-applying it converges.
        lastAppliedSeq = 0L

        writeSecure(output, crypto, FrameType.BULK_ACK, OpCodec.encode(BulkAckPayload(0L)))

        onBulkReceived(fingerprint, project, beginPayload.spatialFrame)
    }

    private fun receiveChunked(input: InputStream, crypto: SessionCrypto, expectedType: FrameType, totalBytes: Int): ByteArray {
        // totalBytes is peer-declared: reject negative/absurd sizes before doing anything
        // (NegativeArraySize / OOM).
        require(totalBytes in 0..Limits.MAX_BULK_BYTES) { "invalid bulk size $totalBytes" }
        // Do NOT allocate the full declared size up front: a hostile host could declare MAX_BULK_BYTES
        // (256 MB) and send nothing, forcing a 256 MB allocation before a single byte is validated as
        // present. Grow the buffer toward totalBytes only as real chunks arrive (doubling, capped at
        // totalBytes), so declared-but-unsent size costs nothing.
        var buffer = ByteArray(minOf(totalBytes, INITIAL_BULK_ALLOC_BYTES))
        var offset = 0
        while (offset < totalBytes) {
            val frame = readSecure(input, crypto) ?: error("EOF mid-bulk")
            require(frame.type == expectedType) { "expected $expectedType, got ${frame.type}" }
            // A chunk running past the declared size means the stream is misaligned with the
            // BULK_BEGIN header; silently truncating (the old minOf clamp) would desync every
            // subsequent frame boundary. Fail the transfer instead.
            if (frame.payload.size > totalBytes - offset) {
                throw java.io.IOException(
                    "bulk chunk overruns declared size: ${frame.payload.size} > ${totalBytes - offset} remaining",
                )
            }
            val needed = offset + frame.payload.size
            if (needed > buffer.size) {
                var newCap = buffer.size
                while (newCap < needed) newCap = minOf(totalBytes, maxOf(newCap * 2, needed))
                buffer = buffer.copyOf(newCap)
            }
            System.arraycopy(frame.payload, 0, buffer, offset, frame.payload.size)
            offset = needed
        }
        // The grown buffer is exactly totalBytes once complete (the loop fills every byte), so no final
        // copy is needed in the common case; guard anyway for a zero-byte transfer.
        return if (buffer.size == totalBytes) buffer else buffer.copyOf(totalBytes)
    }

    private suspend fun livePhase(
        input: InputStream,
        output: OutputStream,
        crypto: SessionCrypto,
        // A frame already read by connectLoop while it was deciding whether the host sent a bulk
        // snapshot or a replay (see the BULK_BEGIN check there). When present, it is handled as
        // this loop's first iteration instead of being re-read (and lost) from the socket.
        firstFrame: Frame.FrameRead? = null,
    ) {
        // A new connection: resend every edit the host has not acknowledged. The rewind happens
        // under flushMutex so a flush still finishing on the previous connection can't advance
        // lastSentGuestSeq past it afterwards.
        scope.launch {
            flushMutex.withLock {
                liveOutput = output
                liveCrypto = crypto
                lastSentGuestSeq = lastAckedGuestSeq
            }
            flushOutbox()
        }
        scope.launch {
            var pending = firstFrame
            while (scope.isActive) {
                val frame = pending ?: try {
                    readSecure(input, crypto) ?: run {
                        attemptReconnect(); return@launch
                    }
                } catch (_: Exception) {
                    attemptReconnect(); return@launch
                }
                pending = null
                try {
                    when (frame.type) {
                        FrameType.DELTA -> {
                            val delta = OpCodec.decode<DeltaPayload>(frame.payload)
                            if (delta.seq > lastAppliedSeq) {
                                // The host's design replaces this guest's local one: shared again.
                                if (delta.op is Op.DesignReplace) designDiverged = false
                                onOp(delta.op)
                                lastAppliedSeq = delta.seq
                            }
                        }
                        FrameType.PING -> {
                            val ping = OpCodec.decode<PingPayload>(frame.payload)
                            try {
                                writeSecure(output, crypto, FrameType.PONG, OpCodec.encode(ping))
                            } catch (_: Exception) {
                                attemptReconnect(); return@launch
                            }
                        }
                        FrameType.BYE -> {
                            val bye = OpCodec.decode<ByePayload>(frame.payload)
                            close(bye.reason); return@launch
                        }
                        FrameType.GUEST_OP_ACK -> {
                            val ack = OpCodec.decode<GuestOpAckPayload>(frame.payload)
                            if (ack.lastGuestSeq > lastAckedGuestSeq) {
                                lastAckedGuestSeq = ack.lastGuestSeq
                                outbox.ackUpTo(ack.lastGuestSeq)
                            }
                        }
                        else -> { /* ignore */ }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Authenticated != trusted: a token-holding peer can still send a frame whose inner
                    // payload is not valid CBOR for its declared type. Decoding threw — drop the
                    // connection and reconnect instead of letting it escape this coroutine (no
                    // CoroutineExceptionHandler on the SupervisorJob) and crash the process.
                    attemptReconnect(); return@launch
                }
            }
        }
        scope.launch {
            // Periodic DELTA_ACK. Terminate on write failure so a stale ack loop from a prior
            // connection doesn't linger and double up after a reconnect.
            while (scope.isActive) {
                delay(1_000)
                try {
                    writeSecure(output, crypto, FrameType.DELTA_ACK, OpCodec.encode(DeltaAckPayload(lastAppliedSeq)))
                } catch (_: Exception) {
                    return@launch
                }
            }
        }
    }

    private suspend fun attemptReconnect() {
        // Atomic check-then-set: the inbound loop and a failed PONG write can both land here;
        // only the first caller may run the reconnect loop.
        synchronized(phaseLock) {
            if (phase == Phase.Reconnecting || phase == Phase.Ended) return
            phase = Phase.Reconnecting
        }
        _state.value = CoopSessionState.Reconnecting
        liveOutput = null
        liveCrypto = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        activeCrypto = null
        val deadline = System.currentTimeMillis() + reconnectWindowMs
        while (System.currentTimeMillis() < deadline && phase != Phase.Ended) {
            delay(reconnectIntervalMs)
            // connectLoop returns true only once the live phase is running again. (It no longer
            // throws, so the previous single-shot try/return bug — which gave up after one
            // attempt — is gone.) isReconnect follows lastAppliedSeq so that a sessionId-mismatch
            // reset (see connectLoop) downgrades the next attempt to a fresh full join.
            if (connectLoop(isReconnect = lastAppliedSeq > 0L)) return
            // A terminal rejection during reconnect already closed the session.
            if (phase == Phase.Ended) return
        }
        if (phase != Phase.Ended) close(CoopSessionState.EndReason.NetworkLost)
    }

    override suspend fun close(reason: CoopSessionState.EndReason) {
        phase = Phase.Ended
        _state.value = CoopSessionState.Ended(reason)
        // Best-effort BYE so the host learns this is a voluntary departure instead of reading it
        // as silence: without this, the host's read loop only notices up to READ_TIMEOUT_MS later
        // and reports Reconnecting/NetworkLost for a guest that in fact left cleanly. Skipped when
        // no live crypto exists yet (still mid-handshake): the host only accepts ENC frames once
        // its own handshake reply has gone out, so an earlier plaintext BYE would just be rejected.
        val sock = socket
        val crypto = activeCrypto
        if (sock != null && crypto != null && !sock.isClosed) {
            // A write that blocks (peer stopped reading) must not delay teardown. Socket exposes
            // no write-side timeout and, unlike NIO channels, a plain java.net.Socket stream does
            // not respond to coroutine cancellation / Thread.interrupt() while blocked in a
            // native write — only closing the stream unblocks it (Socket.getOutputStream's
            // documented contract; see HostSession.writeFrameTimed for the long version of why a
            // withTimeoutOrNull{withContext(IO){...}} alone would not bound this). A daemon timer
            // — not a coroutine on `scope`, which this function cancels right below and so cannot
            // be relied on to still be running when a stuck write would need it — force-closes
            // the socket if the write hasn't finished within BYE_TIMEOUT_MS; the resulting
            // IOException is swallowed since the socket is being closed either way.
            //
            // The seal+write happens under writeMutex, like every other sealed frame: outside it, the
            // BYE raced the PONG/DELTA_ACK/GUEST_OP writers for crypto's send counter (GCM nonce reuse)
            // and could interleave its bytes into a frame mid-write. The lock wait is bounded too — if
            // a wedged write holds it, the BYE is skipped rather than delaying teardown.
            val locked = withTimeoutOrNull(BYE_TIMEOUT_MS) { writeMutex.lock(); true } ?: false
            if (locked) {
                val watchdog = java.util.Timer(true).apply {
                    schedule(
                        object : java.util.TimerTask() {
                            override fun run() { try { sock.close() } catch (_: Exception) {} }
                        },
                        BYE_TIMEOUT_MS,
                    )
                }
                try {
                    withContext(Dispatchers.IO) {
                        val output = sock.getOutputStream()
                        Frame.write(output, FrameType.ENC, crypto.seal(FrameType.BYE, OpCodec.encode(ByePayload(reason))))
                        output.flush()
                    }
                } catch (_: Exception) { /* best-effort */ } finally {
                    watchdog.cancel()
                    writeMutex.unlock()
                }
            }
        }
        try { socket?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    private companion object {
        // The host sends PING every 5s (plus deltas), so 15s of read silence means a dead or
        // half-open host. Mirrors HostSession.READ_TIMEOUT_MS.
        const val READ_TIMEOUT_MS = 15_000

        // Bound on the best-effort BYE write in close(). Short and separate from READ_TIMEOUT_MS:
        // this is teardown, not live traffic, and nothing should wait long on it before the
        // socket closes regardless.
        const val BYE_TIMEOUT_MS = 1_000L

        // Initial bulk-receive allocation. The buffer grows toward the declared size as chunks arrive,
        // so a declared-but-unsent size never forces a large up-front allocation. 64 KiB matches the
        // host's bulk chunk size.
        const val INITIAL_BULK_ALLOC_BYTES = 64 * 1024

        // Write-side bound (java.net.Socket has none). Mirrors HostSession.WRITE_TIMEOUT_MS: a host that
        // stops reading for this long is treated as gone rather than left to park a blocked write.
        const val WRITE_TIMEOUT_MS = 15_000L
    }
}

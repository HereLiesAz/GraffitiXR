// collab/src/main/java/com/hereliesaz/graffitixr/core/collaboration/session/HostSession.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
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
    private val fingerprintBytes: ByteArray,
    private val projectBytes: ByteArray,
    private val projectId: String,
    private val layerCount: Int,
) : Session() {

    init {
        // The guest rejects bulk transfers above this cap with a bare require() that surfaces as
        // an unexplained NetworkLost on its side; failing fast here turns an oversized project
        // into an immediate, attributable hosting error instead.
        require(projectBytes.size <= Limits.MAX_BULK_BYTES && fingerprintBytes.size <= Limits.MAX_BULK_BYTES) {
            "project too large to host: ${projectBytes.size}B project / ${fingerprintBytes.size}B fingerprint " +
                "(cap ${Limits.MAX_BULK_BYTES}B)"
        }
    }

    /** An op with its sequence number and wire encoding, fixed at enqueue time. */
    private class EncodedDelta(val seq: Long, val bytes: ByteArray)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Unbounded channel, but NOT unbounded memory: every element is appended to deltaBuffer
    // first (real encoded size), whose 5 MB / 1000-op cap ends the session on overflow. The
    // previous design (bounded 128 + DROP_OLDEST, seq assigned at send time) silently discarded
    // ops that overflowed during a reconnect window before they ever reached the DeltaBuffer,
    // so the guest's canvas diverged with no signal.
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
            Frame.write(output, FrameType.ENC, crypto.seal(type, payload))
            output.flush()
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
     * accounting all happen here, atomically, so every accepted op is replayable after a
     * reconnect — nothing can be dropped between enqueue and send anymore. On DeltaBuffer
     * overflow (guest too far behind / no guest draining) the session ends explicitly rather
     * than diverging silently.
     *
     * Encoding runs on the caller's thread; ops carrying large payloads (LayerBitmapReplace)
     * should be enqueued from a background dispatcher, which OpEmitterImpl's editor call sites
     * already do.
     */
    fun enqueueOp(op: Op) {
        synchronized(enqueueLock) {
            // Check inside the lock: close() clears deltaBuffer under the same lock, so an append
            // can never land after the buffer is cleared (which would leak an entry for an ended
            // session).
            if (phase == Phase.Ended) return
            val seq = seqCounter.incrementAndGet()
            val bytes = OpCodec.encode(DeltaPayload(seq, op))
            if (!deltaBuffer.append(seq, op, bytes.size)) {
                // Cap exceeded: a future reconnect could not replay without a gap, so end the
                // session per DeltaBuffer's contract rather than diverge silently.
                scope.launch { close(CoopSessionState.EndReason.NetworkLost) }
                return
            }
            outQueue.trySend(EncodedDelta(seq, bytes))
        }
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (scope.isActive) {
            val socket = try {
                ss.accept()
            } catch (e: Exception) {
                if (!scope.isActive) return
                _state.value = CoopSessionState.Ended(CoopSessionState.EndReason.NetworkLost)
                return
            }
            // A failed handshake (peer vanished mid-HELLO, garbage payload, write error) must
            // never kill this loop: before this guard, a single flaky or hostile client
            // permanently disabled hosting and leaked its socket.
            try {
                handleConnection(socket)
            } catch (e: Exception) {
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
            Frame.write(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.BadToken)),
            )
            output.flush(); socket.close(); return
        }
        if (hello.clientVersion != protocolVersion) {
            Frame.write(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.VersionMismatch)),
            )
            output.flush(); socket.close(); return
        }

        // Single guest only. A reconnecting guest is fine because enterReconnecting() nulls
        // clientSocket before the new connection; a *second* concurrent guest is rejected so
        // two live phases never share (and interleave on) the same outQueue/output.
        if (clientSocket != null) {
            Frame.write(
                output,
                FrameType.HELLO_REJECTED,
                OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.AlreadyHosting)),
            )
            output.flush(); socket.close(); return
        }

        // Accept. Build the per-connection crypto from token + both nonces (fresh keys every
        // connection) and prove host identity to the guest before any bulk data.
        val hostNonce = randomNonce()
        val hostProof = SessionCrypto.helloOkProof(prk, hostNonce, hello.guestNonce)
        val crypto = SessionCrypto.forHost(token, sessionId, hello.guestNonce, hostNonce)
        activeCrypto = crypto
        Frame.write(
            output,
            FrameType.HELLO_OK,
            OpCodec.encode(
                HelloOkPayload(
                    sessionId = sessionId,
                    protocolVersion = protocolVersion,
                    hostNonce = hostNonce,
                    hostProof = hostProof,
                )
            ),
        )
        output.flush()

        clientSocket = socket
        val isReconnect = hello.lastAppliedSeq > 0
        lastAppliedSeq = hello.lastAppliedSeq

        if (isReconnect) {
            // Replay buffered deltas after lastAppliedSeq. Since seqs are assigned at enqueue,
            // this may overlap ops still sitting unsent in outQueue; the guest's monotonic
            // `seq > lastAppliedSeq` filter makes the duplicates harmless, and this replay
            // completes before the live outbound loop starts, so ordering holds.
            deltaBuffer.opsAfter(lastAppliedSeq).forEach { (seq, op) ->
                writeSecure(output, crypto, FrameType.DELTA, OpCodec.encode(DeltaPayload(seq, op)))
            }
        } else {
            // Bulk transfer.
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

    private suspend fun sendBulk(output: OutputStream, crypto: SessionCrypto) {
        writeSecure(
            output, crypto, FrameType.BULK_BEGIN,
            OpCodec.encode(
                BulkBeginPayload(
                    projectId = projectId,
                    layerCount = layerCount,
                    fingerprintBytes = fingerprintBytes.size,
                    projectBytes = projectBytes.size,
                )
            ),
        )
        // Chunk payload into 64KB frames.
        chunkAndWrite(output, crypto, FrameType.BULK_FINGERPRINT, fingerprintBytes)
        chunkAndWrite(output, crypto, FrameType.BULK_PROJECT, projectBytes)
        writeSecure(output, crypto, FrameType.BULK_END, ByteArray(0))
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
                        enterReconnecting(); return
                    }
                }
                FrameType.BULK_ACK -> { /* bulk done; ignore */ }
                FrameType.BYE -> { close(CoopSessionState.EndReason.HostClosed); return }
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
    private fun sendBye(output: OutputStream, reason: CoopSessionState.EndReason, crypto: SessionCrypto?) {
        try {
            val payload = OpCodec.encode(ByePayload(reason))
            if (crypto != null) {
                Frame.write(output, FrameType.ENC, crypto.seal(FrameType.BYE, payload))
            } else {
                Frame.write(output, FrameType.BYE, payload)
            }
            output.flush()
        } catch (_: Exception) { /* socket may already be closed */ }
    }

    private companion object {
        // Guests ack every 1s and answer 5s PINGs, so 15s of read silence means a dead or
        // half-open peer. Also bounds handshake reads in handleConnection, so a stalled
        // client can block the accept loop for at most this long.
        const val READ_TIMEOUT_MS = 15_000
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

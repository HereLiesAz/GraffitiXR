package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
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
    private val onBulkReceived: suspend (fingerprint: ByteArray, project: ByteArray) -> Unit,
    private val onOp: suspend (Op) -> Unit,
    // How long (and how often) to retry reconnecting before giving up. Injectable so tests can
    // use a short, deterministic window instead of waiting the full production 30s.
    private val reconnectWindowMs: Long = 30_000L,
    private val reconnectIntervalMs: Long = 2_000L,
) : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var sessionId: String? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    private var socket: Socket? = null

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
            Frame.write(output, FrameType.ENC, crypto.seal(type, payload))
            output.flush()
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
                        try { s.close() } catch (_: Exception) {}
                        socket = null
                        return false
                    }
                    sessionId = helloOk.sessionId
                    val crypto = SessionCrypto.forGuest(token, helloOk.sessionId, guestNonce, helloOk.hostNonce)
                    if (!isReconnect) {
                        receiveBulk(input, output, crypto)
                    }
                    _state.value = CoopSessionState.Connected(peerName = "host")
                    phase = Phase.Live
                    livePhase(input, output, crypto)
                    true
                }
                FrameType.HELLO_REJECTED -> {
                    val rej = OpCodec.decode<HelloRejectedPayload>(response.payload)
                    val reason = when (rej.reason) {
                        HelloRejectedPayload.RejectReason.BadToken -> CoopSessionState.EndReason.BadToken
                        HelloRejectedPayload.RejectReason.VersionMismatch -> CoopSessionState.EndReason.VersionMismatch
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
        } catch (e: Exception) {
            // Transient: let the caller decide whether to retry (reconnect) or end the session.
            // Close the half-open socket now so a failed attempt doesn't leak an FD — the reconnect
            // loop reassigns the field on the next try and would otherwise orphan this one.
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            false
        }
    }

    private suspend fun receiveBulk(input: InputStream, output: OutputStream, crypto: SessionCrypto) {
        val begin = readSecure(input, crypto) ?: error("EOF in bulk")
        require(begin.type == FrameType.BULK_BEGIN)
        val beginPayload = OpCodec.decode<BulkBeginPayload>(begin.payload)

        val fingerprint = receiveChunked(input, crypto, FrameType.BULK_FINGERPRINT, beginPayload.fingerprintBytes)
        val project = receiveChunked(input, crypto, FrameType.BULK_PROJECT, beginPayload.projectBytes)

        val end = readSecure(input, crypto) ?: error("EOF before BULK_END")
        require(end.type == FrameType.BULK_END)

        writeSecure(output, crypto, FrameType.BULK_ACK, OpCodec.encode(BulkAckPayload(0L)))

        onBulkReceived(fingerprint, project)
    }

    private fun receiveChunked(input: InputStream, crypto: SessionCrypto, expectedType: FrameType, totalBytes: Int): ByteArray {
        // totalBytes is peer-declared: reject negative/absurd sizes before allocating
        // (NegativeArraySize / OOM).
        require(totalBytes in 0..Limits.MAX_BULK_BYTES) { "invalid bulk size $totalBytes" }
        val buffer = ByteArray(totalBytes)
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
            System.arraycopy(frame.payload, 0, buffer, offset, frame.payload.size)
            offset += frame.payload.size
        }
        return buffer
    }

    private suspend fun livePhase(input: InputStream, output: OutputStream, crypto: SessionCrypto) {
        scope.launch {
            while (scope.isActive) {
                val frame = try {
                    readSecure(input, crypto) ?: run {
                        attemptReconnect(); return@launch
                    }
                } catch (e: Exception) {
                    attemptReconnect(); return@launch
                }
                when (frame.type) {
                    FrameType.DELTA -> {
                        val delta = OpCodec.decode<DeltaPayload>(frame.payload)
                        if (delta.seq > lastAppliedSeq) {
                            onOp(delta.op)
                            lastAppliedSeq = delta.seq
                        }
                    }
                    FrameType.PING -> {
                        val ping = OpCodec.decode<PingPayload>(frame.payload)
                        try {
                            writeSecure(output, crypto, FrameType.PONG, OpCodec.encode(ping))
                        } catch (e: Exception) {
                            attemptReconnect(); return@launch
                        }
                    }
                    FrameType.BYE -> {
                        val bye = OpCodec.decode<ByePayload>(frame.payload)
                        close(bye.reason); return@launch
                    }
                    else -> { /* ignore */ }
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
        try { socket?.close() } catch (_: Exception) {}
        socket = null
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
        try { socket?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    private companion object {
        // The host sends PING every 5s (plus deltas), so 15s of read silence means a dead or
        // half-open host. Mirrors HostSession.READ_TIMEOUT_MS.
        const val READ_TIMEOUT_MS = 15_000
    }
}

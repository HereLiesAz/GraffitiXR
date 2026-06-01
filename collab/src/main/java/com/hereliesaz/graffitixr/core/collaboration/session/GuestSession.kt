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
    private var sessionId: String? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    private var socket: Socket? = null

    // Serializes writes to the host: the inbound loop (PONG) and the periodic DELTA_ACK loop
    // both write to the same OutputStream, so without this lock their frames interleave.
    private val writeMutex = Mutex()

    private suspend fun writeFrame(output: OutputStream, type: FrameType, payload: ByteArray) {
        writeMutex.withLock {
            Frame.write(output, type, payload)
            output.flush()
        }
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
            val input = s.getInputStream()
            val output = s.getOutputStream()

            // Send HELLO.
            Frame.write(
                output,
                FrameType.HELLO,
                OpCodec.encode(
                    HelloPayload(
                        token = token,
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
                    sessionId = helloOk.sessionId
                    if (!isReconnect) {
                        receiveBulk(input, output)
                    }
                    _state.value = CoopSessionState.Connected(peerName = "host")
                    phase = Phase.Live
                    livePhase(input, output)
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

    private suspend fun receiveBulk(input: InputStream, output: OutputStream) {
        val begin = Frame.read(input) ?: error("EOF in bulk")
        require(begin.type == FrameType.BULK_BEGIN)
        val beginPayload = OpCodec.decode<BulkBeginPayload>(begin.payload)

        val fingerprint = receiveChunked(input, FrameType.BULK_FINGERPRINT, beginPayload.fingerprintBytes)
        val project = receiveChunked(input, FrameType.BULK_PROJECT, beginPayload.projectBytes)

        val end = Frame.read(input) ?: error("EOF before BULK_END")
        require(end.type == FrameType.BULK_END)

        Frame.write(output, FrameType.BULK_ACK, OpCodec.encode(BulkAckPayload(0L)))
        output.flush()

        onBulkReceived(fingerprint, project)
    }

    private fun receiveChunked(input: InputStream, expectedType: FrameType, totalBytes: Int): ByteArray {
        // totalBytes is peer-declared: reject negative/absurd sizes before allocating
        // (NegativeArraySize / OOM), and never copy past the declared end even if a chunk
        // overshoots (ArrayIndexOutOfBounds).
        require(totalBytes in 0..MAX_BULK_BYTES) { "invalid bulk size $totalBytes" }
        val buffer = ByteArray(totalBytes)
        var offset = 0
        while (offset < totalBytes) {
            val frame = Frame.read(input) ?: error("EOF mid-bulk")
            require(frame.type == expectedType) { "expected $expectedType, got ${frame.type}" }
            val len = minOf(frame.payload.size, totalBytes - offset)
            System.arraycopy(frame.payload, 0, buffer, offset, len)
            offset += len
        }
        return buffer
    }

    private suspend fun livePhase(input: InputStream, output: OutputStream) {
        scope.launch {
            while (scope.isActive) {
                val frame = try {
                    Frame.read(input) ?: run {
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
                            writeFrame(output, FrameType.PONG, OpCodec.encode(ping))
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
                    writeFrame(output, FrameType.DELTA_ACK, OpCodec.encode(DeltaAckPayload(lastAppliedSeq)))
                } catch (_: Exception) {
                    return@launch
                }
            }
        }
    }

    private suspend fun attemptReconnect() {
        if (phase == Phase.Ended) return
        phase = Phase.Reconnecting
        _state.value = CoopSessionState.Reconnecting
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        val deadline = System.currentTimeMillis() + reconnectWindowMs
        while (System.currentTimeMillis() < deadline && phase != Phase.Ended) {
            delay(reconnectIntervalMs)
            // connectLoop returns true only once the live phase is running again. (It no longer
            // throws, so the previous single-shot try/return bug — which gave up after one
            // attempt — is gone.)
            if (connectLoop(isReconnect = true)) return
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
        // Generous cap on a full project bulk transfer; rejects malformed/hostile declared sizes.
        const val MAX_BULK_BYTES = 256 * 1024 * 1024
    }
}

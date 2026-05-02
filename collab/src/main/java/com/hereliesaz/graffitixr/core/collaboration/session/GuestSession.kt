package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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
) : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionId: String? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    private var socket: Socket? = null

    suspend fun connect() {
        scope.launch { connectLoop(isReconnect = false) }
    }

    private suspend fun connectLoop(isReconnect: Boolean) {
        try {
            val s = Socket(host, port)
            socket = s
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
                }
                FrameType.HELLO_REJECTED -> {
                    val rej = OpCodec.decode<HelloRejectedPayload>(response.payload)
                    val reason = when (rej.reason) {
                        HelloRejectedPayload.RejectReason.BadToken -> CoopSessionState.EndReason.BadToken
                        HelloRejectedPayload.RejectReason.VersionMismatch -> CoopSessionState.EndReason.VersionMismatch
                        HelloRejectedPayload.RejectReason.AlreadyHosting -> CoopSessionState.EndReason.HostClosed
                    }
                    close(reason)
                }
                else -> close(CoopSessionState.EndReason.ProtocolError)
            }
        } catch (e: Exception) {
            if (isReconnect) {
                close(CoopSessionState.EndReason.NetworkLost)
            } else {
                close(CoopSessionState.EndReason.NetworkLost)
            }
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
        val buffer = ByteArray(totalBytes)
        var offset = 0
        while (offset < totalBytes) {
            val frame = Frame.read(input) ?: error("EOF mid-bulk")
            require(frame.type == expectedType) { "expected $expectedType, got ${frame.type}" }
            System.arraycopy(frame.payload, 0, buffer, offset, frame.payload.size)
            offset += frame.payload.size
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
                        Frame.write(output, FrameType.PONG, OpCodec.encode(ping))
                        output.flush()
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
            // Periodic DELTA_ACK.
            while (scope.isActive) {
                delay(1_000)
                try {
                    Frame.write(output, FrameType.DELTA_ACK, OpCodec.encode(DeltaAckPayload(lastAppliedSeq)))
                    output.flush()
                } catch (_: Exception) { /* let inbound loop handle */ }
            }
        }
    }

    private suspend fun attemptReconnect() {
        phase = Phase.Reconnecting
        _state.value = CoopSessionState.Reconnecting
        socket?.close()
        socket = null
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            try {
                connectLoop(isReconnect = true)
                return // succeeded, connectLoop transitioned state
            } catch (_: Exception) {
                // try again
            }
        }
        close(CoopSessionState.EndReason.NetworkLost)
    }

    override suspend fun close(reason: CoopSessionState.EndReason) {
        phase = Phase.Ended
        _state.value = CoopSessionState.Ended(reason)
        socket?.close()
        scope.cancel()
    }
}

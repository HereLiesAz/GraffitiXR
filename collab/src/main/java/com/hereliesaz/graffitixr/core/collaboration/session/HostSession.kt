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
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import com.hereliesaz.graffitixr.core.collaboration.wire.PingPayload
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
    private val opSizeEstimator: (Op) -> Int = { 256 }, // heuristic for DeltaBuffer accounting
) : Session() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outQueue: Channel<Op> = Channel(capacity = Channel.UNLIMITED)
    private val deltaBuffer = DeltaBuffer()
    private val seqCounter = AtomicLong(0L)

    private val sessionId: String = UUID.randomUUID().toString()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var lastAppliedSeq: Long = 0L
    private var liveJob: Job? = null

    // Serializes all writes to the single connected guest's OutputStream. The outbound,
    // inbound (PONG) and heartbeat (PING) loops write concurrently, and Frame.write issues
    // several stream writes per frame, so without this lock their bytes interleave and
    // corrupt the wire framing.
    private val writeMutex = Mutex()

    private suspend fun writeFrame(output: OutputStream, type: FrameType, payload: ByteArray) {
        writeMutex.withLock {
            Frame.write(output, type, payload)
            output.flush()
        }
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

    /** Enqueue an Op to be sent to the connected guest. No-op if no guest. */
    fun enqueueOp(op: Op) {
        outQueue.trySend(op)
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
            handleConnection(socket)
        }
    }

    private suspend fun handleConnection(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val helloFrame = Frame.read(input) ?: return socket.close()
        if (helloFrame.type != FrameType.HELLO) {
            sendBye(output, CoopSessionState.EndReason.ProtocolError)
            socket.close(); return
        }
        val hello = OpCodec.decode<HelloPayload>(helloFrame.payload)

        if (hello.token != token) {
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

        // Accept.
        Frame.write(
            output,
            FrameType.HELLO_OK,
            OpCodec.encode(HelloOkPayload(sessionId = sessionId, protocolVersion = protocolVersion)),
        )
        output.flush()

        clientSocket = socket
        val isReconnect = hello.lastAppliedSeq > 0
        lastAppliedSeq = hello.lastAppliedSeq

        if (isReconnect) {
            // Replay buffered deltas after lastAppliedSeq.
            deltaBuffer.opsAfter(lastAppliedSeq).forEach { (seq, op) ->
                Frame.write(
                    output,
                    FrameType.DELTA,
                    OpCodec.encode(DeltaPayload(seq, op)),
                )
            }
            output.flush()
        } else {
            // Bulk transfer.
            sendBulk(output)
        }

        _state.value = CoopSessionState.Connected(peerName = hello.deviceName)
        phase = Phase.Live

        // Tear down any prior live loops (e.g. from a previous connection before a reconnect)
        // before starting fresh ones, so two outbound loops never drain outQueue concurrently.
        liveJob?.cancelAndJoin()
        liveJob = scope.launch {
            coroutineScope {
                launch { outboundLoop(output) }
                launch { inboundLoop(input, output) }
                launch { heartbeatLoop(output) }
            }
        }
    }

    private suspend fun sendBulk(output: OutputStream) {
        Frame.write(
            output,
            FrameType.BULK_BEGIN,
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
        chunkAndWrite(output, FrameType.BULK_FINGERPRINT, fingerprintBytes)
        chunkAndWrite(output, FrameType.BULK_PROJECT, projectBytes)
        Frame.write(output, FrameType.BULK_END, ByteArray(0))
        output.flush()
    }

    private fun chunkAndWrite(output: OutputStream, type: FrameType, bytes: ByteArray) {
        val chunkSize = 64 * 1024
        var offset = 0
        while (offset < bytes.size) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            Frame.write(output, type, bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    private suspend fun outboundLoop(output: OutputStream) {
        for (op in outQueue) {
            val seq = seqCounter.incrementAndGet()
            val payload = OpCodec.encode(DeltaPayload(seq, op))
            if (!deltaBuffer.append(seq, op, opSizeEstimator(op))) {
                // Buffer cap exceeded: a future reconnect could not replay without a gap,
                // so end the session per DeltaBuffer's contract rather than diverge silently.
                close(CoopSessionState.EndReason.NetworkLost)
                return
            }
            try {
                writeFrame(output, FrameType.DELTA, payload)
            } catch (e: Exception) {
                // Connection broken; enter reconnecting.
                enterReconnecting()
                return
            }
        }
    }

    private suspend fun inboundLoop(input: java.io.InputStream, output: OutputStream) {
        while (scope.isActive) {
            val frame = try {
                Frame.read(input) ?: run { enterReconnecting(); return }
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
                        writeFrame(output, FrameType.PONG, OpCodec.encode(ping))
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

    private suspend fun heartbeatLoop(output: OutputStream) {
        while (scope.isActive) {
            delay(5_000)
            try {
                writeFrame(output, FrameType.PING, OpCodec.encode(PingPayload(System.currentTimeMillis())))
            } catch (e: Exception) {
                enterReconnecting(); return
            }
        }
    }

    private fun enterReconnecting() {
        if (phase == Phase.Reconnecting || phase == Phase.Ended) return
        phase = Phase.Reconnecting
        _state.value = CoopSessionState.Reconnecting
        val old = clientSocket
        clientSocket = null
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

    private fun sendBye(output: OutputStream, reason: CoopSessionState.EndReason) {
        try {
            Frame.write(output, FrameType.BYE, OpCodec.encode(ByePayload(reason)))
            output.flush()
        } catch (_: Exception) { /* socket may already be closed */ }
    }

    override suspend fun close(reason: CoopSessionState.EndReason) {
        phase = Phase.Ended
        _state.value = CoopSessionState.Ended(reason)
        try {
            clientSocket?.let { sock ->
                // getOutputStream() can throw if the socket is already closed; that must not
                // skip the serverSocket/queue/scope teardown below (which would leak the port).
                try { sendBye(sock.getOutputStream(), reason) } catch (_: Exception) {}
                try { sock.close() } catch (_: Exception) {}
            }
        } finally {
            clientSocket = null
            try { serverSocket?.close() } catch (_: Exception) {}
            outQueue.close()
            deltaBuffer.clear()
            scope.cancel()
        }
    }
}

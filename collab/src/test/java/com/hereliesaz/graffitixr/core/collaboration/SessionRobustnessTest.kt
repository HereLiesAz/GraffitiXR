package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkBeginPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Frame
import com.hereliesaz.graffitixr.core.collaboration.wire.FrameType
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloOkPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-socket regression tests for the collab robustness fixes. */
class SessionRobustnessTest {

    private fun newHost(
        fingerprint: ByteArray = ByteArray(128) { it.toByte() },
        project: ByteArray = ByteArray(256) { (it * 3).toByte() },
    ) = HostSession(
        token = "tok",
        protocolVersion = 1,
        localDeviceName = "host",
        fingerprintBytes = fingerprint,
        projectBytes = project,
        projectId = "p1",
        layerCount = 0,
    )

    @Test
    fun `host keeps accepting after a garbage handshake`() = runBlocking {
        val host = newHost()
        val port = host.startListening()

        // A hostile/broken client: valid length prefix, valid HELLO type, garbage payload —
        // OpCodec.decode throws inside handleConnection. Before the accept-loop guard this
        // exception killed the accept coroutine and hosting was permanently dead.
        Socket("127.0.0.1", port).use { s ->
            val out = java.io.DataOutputStream(s.getOutputStream())
            out.writeInt(1 + 4)          // length = type byte + 4 payload bytes
            out.writeByte(0x10)          // FrameType.HELLO
            out.write(byteArrayOf(0x7F, 0x00, 0x42, 0x13)) // not a HelloPayload
            out.flush()
        }
        // And one that vanishes mid-handshake (clean EOF path).
        Socket("127.0.0.1", port).close()

        // A real guest must still be able to join.
        var bulkOk = false
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { _, _ -> bulkOk = true },
            onOp = { },
        )
        guest.connect()
        withTimeout(10_000) { while (!bulkOk) delay(50) }

        host.close(CoopSessionState.EndReason.UserLeft)
        guest.close(CoopSessionState.EndReason.UserLeft)
    }

    @Test
    fun `ops enqueued during a reconnect window all arrive after reconnect`() = runBlocking {
        val host = newHost()
        val port = host.startListening()

        val received = mutableListOf<Op>()
        var bulkOk = false
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { _, _ -> bulkOk = true },
            onOp = { op -> synchronized(received) { received.add(op) } },
            reconnectWindowMs = 15_000L,
            reconnectIntervalMs = 300L,
        )
        guest.connect()
        withTimeout(10_000) { while (!bulkOk) delay(50) }

        repeat(5) { i -> host.enqueueOp(Op.LayerAdd(Layer(id = "L$i", name = "n$i"))) }
        withTimeout(10_000) { while (synchronized(received) { received.size } < 5) delay(50) }

        // Sever the connection out from under the guest.
        val socketField = GuestSession::class.java.getDeclaredField("socket")
        socketField.isAccessible = true
        (socketField.get(guest) as? Socket)?.close()

        // Keep painting during the outage. Before seq-at-enqueue these overflowed the bounded
        // outbound channel (DROP_OLDEST) without ever reaching the DeltaBuffer and were lost.
        repeat(20) { i -> host.enqueueOp(Op.LayerAdd(Layer(id = "L${5 + i}", name = "n${5 + i}"))) }

        withTimeout(20_000) { while (synchronized(received) { received.size } < 25) delay(100) }

        val ids = synchronized(received) { received.map { (it as Op.LayerAdd).layer.id } }
        assertEquals((0 until 25).map { "L$it" }, ids)

        host.close(CoopSessionState.EndReason.UserLeft)
        guest.close(CoopSessionState.EndReason.UserLeft)
    }

    @Test
    fun `delta buffer overflow by real op bytes ends the session explicitly`() = runBlocking {
        val host = newHost()
        host.startListening()

        // Three ~2MB bitmap ops blow the 5MB DeltaBuffer cap. Under the old 256-bytes-per-op
        // estimate these were accounted as ~768B total and the host kept buffering toward OOM.
        repeat(3) { i ->
            host.enqueueOp(Op.LayerBitmapReplace(layerId = "L$i", png = ByteArray(2 * 1024 * 1024)))
        }

        val end = withTimeout(10_000) { host.state.first { it is CoopSessionState.Ended } }
        assertTrue(end is CoopSessionState.Ended)
    }

    @Test
    fun `delta buffer op-count overflow ends the session explicitly`() = runBlocking {
        val host = newHost()
        host.startListening()

        repeat(1_001) { i -> host.enqueueOp(Op.LayerAdd(Layer(id = "L$i", name = "n$i"))) }

        val end = withTimeout(10_000) { host.state.first { it is CoopSessionState.Ended } }
        assertTrue(end is CoopSessionState.Ended)
    }

    @Test
    fun `guest re-syncs with a fresh bulk when the host sessionId changes across a reconnect`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val helloSeqs = mutableListOf<Long>()
        var bulkCount = 0

        // Hand-rolled wire-speaking host: same port across "restarts", new sessionId after the
        // first connection — the shape of a host app relaunch reusing a QR.
        fun serveOnce(sessionId: String, sendDeltaAndDie: Boolean) {
            val sock = server.accept()
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()

            val hello = Frame.read(input) ?: error("no HELLO")
            assertEquals(FrameType.HELLO, hello.type)
            val payload = OpCodec.decode<HelloPayload>(hello.payload)
            synchronized(helloSeqs) { helloSeqs.add(payload.lastAppliedSeq) }

            Frame.write(output, FrameType.HELLO_OK, OpCodec.encode(HelloOkPayload(sessionId, 1)))
            output.flush()

            if (payload.lastAppliedSeq == 0L) {
                Frame.write(
                    output,
                    FrameType.BULK_BEGIN,
                    OpCodec.encode(BulkBeginPayload("p1", 0, 0, 0)),
                )
                Frame.write(output, FrameType.BULK_END, ByteArray(0))
                output.flush()
                val ack = Frame.read(input) ?: error("no BULK_ACK")
                assertEquals(FrameType.BULK_ACK, ack.type)
            }

            if (sendDeltaAndDie) {
                // Give the guest a nonzero lastAppliedSeq so its next attempt is a resume.
                Frame.write(
                    output,
                    FrameType.DELTA,
                    OpCodec.encode(DeltaPayload(1L, Op.LayerAdd(Layer(id = "X", name = "x")))),
                )
                output.flush()
                Thread.sleep(300)
            }
            sock.close()
        }

        val fakeHost = Thread {
            try {
                serveOnce(sessionId = "session-A", sendDeltaAndDie = true) // fresh join, then dies
                serveOnce(sessionId = "session-B", sendDeltaAndDie = false) // resume attempt -> mismatch
                serveOnce(sessionId = "session-B", sendDeltaAndDie = false) // fresh re-join
            } catch (_: Exception) { /* assertions re-checked on the main thread below */ }
        }
        fakeHost.start()

        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { _, _ -> bulkCount++ },
            onOp = { },
            reconnectWindowMs = 15_000L,
            reconnectIntervalMs = 200L,
        )
        guest.connect()

        withTimeout(20_000) { while (bulkCount < 2) delay(100) }
        fakeHost.join(10_000)

        // Attempt 1: fresh join (0). Attempt 2: resume with the applied delta (1) — rejected by
        // the sessionId check. Attempt 3: downgraded to a fresh join (0) => full bulk re-sync.
        assertEquals(listOf(0L, 1L, 0L), synchronized(helloSeqs) { helloSeqs.toList() })
        assertEquals(2, bulkCount)

        guest.close(CoopSessionState.EndReason.UserLeft)
        server.close()
    }
}

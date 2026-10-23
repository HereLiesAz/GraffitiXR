package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.BrushStroke
import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.DeltaBuffer
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.BulkBeginPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.DeltaPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.Frame
import com.hereliesaz.graffitixr.core.collaboration.wire.FrameType
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloOkPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloRejectedPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import com.hereliesaz.graffitixr.core.collaboration.wire.SessionCrypto
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-socket regression tests for the co-op reconnect audit: a guest reconnecting before the
 * host notices the drop, pre-auth handshakes starving the accept loop, ops evicted before they were
 * sent, and replay failures.
 */
class CoopReconnectAuditTest {

    /** A wire-speaking guest: does the HELLO handshake, then reads decrypted frames on demand. */
    private class RawGuest(port: Int, lastAppliedSeq: Long) : Closeable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 8_000 }
        private val input = socket.getInputStream()
        private val crypto: SessionCrypto

        init {
            val output = socket.getOutputStream()
            val prk = SessionCrypto.prk(TOKEN)
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            Frame.write(
                output,
                FrameType.HELLO,
                OpCodec.encode(
                    HelloPayload(
                        guestNonce = nonce,
                        proof = SessionCrypto.helloProof(prk, nonce),
                        clientVersion = 1,
                        deviceName = "raw",
                        localBackend = CoopTrackingBackend.ARCORE,
                        lastAppliedSeq = lastAppliedSeq,
                    )
                ),
            )
            output.flush()
            val ok = Frame.read(input) ?: error("no HELLO_OK")
            assertEquals(FrameType.HELLO_OK, ok.type)
            val helloOk = OpCodec.decode<HelloOkPayload>(ok.payload)
            crypto = SessionCrypto.forGuest(TOKEN, helloOk.sessionId, nonce, helloOk.hostNonce)
        }

        /** The next decrypted frame, or null once the host has closed the connection. */
        fun nextOrNull(): Frame.FrameRead? = try {
            Frame.read(input)?.let { crypto.open(it.payload) }
        } catch (_: java.net.SocketException) {
            null
        }

        fun next(): Frame.FrameRead = nextOrNull() ?: error("EOF")

        /** The next frame that is not a PING. */
        fun nextNonPing(): Frame.FrameRead {
            while (true) {
                val f = next()
                if (f.type != FrameType.PING) return f
            }
        }

        fun readThroughBulkEnd() {
            while (next().type != FrameType.BULK_END) { /* drain the snapshot */ }
        }

        override fun close() = socket.close()
    }

    private fun snapshot() = ProjectSnapshot(
        doodleBytes = ByteArray(128) { it.toByte() },
        projectBytes = ByteArray(256) { (it * 3).toByte() },
        spatialFrame = testSpatialFrame(),
    )

    private fun newHost(reconnectWindowMs: Long = 30_000L) = HostSession(
        token = TOKEN,
        protocolVersion = 1,
        localDeviceName = "host",
        projectId = "p1",
        snapshotProvider = { snapshot() },
        reconnectWindowMs = reconnectWindowMs,
    )

    private fun stroke(i: Int) = Op.StrokeComplete(BrushStroke(points = listOf(i.toFloat())))

    // ── 1. A reconnect before the host noticed the drop ───────────────────────────────────────

    @Test
    fun `a verified HELLO while a connection is live replaces it instead of AlreadyHosting`() = runBlocking {
        val host = newHost()
        val port = host.startListening()

        val old = RawGuest(port, lastAppliedSeq = 0L)
        old.readThroughBulkEnd()
        withTimeout(5_000) { host.state.first { it is CoopSessionState.Connected } }

        // The old socket is still open as far as the host knows (a half-open connection after a
        // Wi-Fi switch). The same guest reconnects: it must be adopted, not refused. RawGuest's init
        // asserts HELLO_OK; AlreadyHosting would fail it.
        RawGuest(port, lastAppliedSeq = 0L).use { fresh ->
            assertEquals(FrameType.BULK_BEGIN, fresh.next().type)
            while (fresh.next().type != FrameType.BULK_END) { /* drain */ }
            withTimeout(5_000) { host.state.first { it is CoopSessionState.Connected } }

            // The replaced connection is closed by the host...
            withTimeout(5_000) { while (old.nextOrNull() != null) { /* drain until EOF */ } }

            // ...and the new one is the live one: ops reach it, and the host stays Connected.
            host.enqueueOp(stroke(7))
            val delta = fresh.nextNonPing()
            assertEquals(FrameType.DELTA, delta.type)
            assertEquals(1L, OpCodec.decode<DeltaPayload>(delta.payload).seq)
            delay(300)
            assertTrue(host.state.value is CoopSessionState.Connected)
        }
        old.close()
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    @Test
    fun `guest treats AlreadyHosting during its reconnect loop as retryable`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val sessionId = "s1"
        val accepts = AtomicInteger(0)
        var bulkCount = 0

        // Hand-rolled host: connection 1 is a normal join that then drops; connection 2 answers
        // AlreadyHosting (an old host that hasn't noticed the drop); connection 3 accepts the resume.
        fun serve(rejectAlreadyHosting: Boolean, dropAfterMs: Long) {
            val sock = server.accept()
            accepts.incrementAndGet()
            sock.soTimeout = 10_000
            val input = sock.getInputStream()
            val output = sock.getOutputStream()
            val hello = OpCodec.decode<HelloPayload>((Frame.read(input) ?: error("no HELLO")).payload)
            if (rejectAlreadyHosting) {
                Frame.write(
                    output,
                    FrameType.HELLO_REJECTED,
                    OpCodec.encode(HelloRejectedPayload(HelloRejectedPayload.RejectReason.AlreadyHosting)),
                )
                output.flush()
                sock.close()
                return
            }
            val prk = SessionCrypto.prk(TOKEN)
            val hostNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            Frame.write(
                output,
                FrameType.HELLO_OK,
                OpCodec.encode(
                    HelloOkPayload(sessionId, 1, hostNonce, SessionCrypto.helloOkProof(prk, hostNonce, hello.guestNonce)),
                ),
            )
            output.flush()
            val crypto = SessionCrypto.forHost(TOKEN, sessionId, hello.guestNonce, hostNonce)
            val writeEnc: (FrameType, ByteArray) -> Unit = { type, p ->
                Frame.write(output, FrameType.ENC, crypto.seal(type, p)); output.flush()
            }
            writeEnc(FrameType.BULK_BEGIN, OpCodec.encode(BulkBeginPayload("p1", 0, 0, 0, testSpatialFrame())))
            writeEnc(FrameType.BULK_END, ByteArray(0))
            Thread.sleep(dropAfterMs)
            sock.close()
        }

        val fakeHost = Thread {
            try {
                serve(rejectAlreadyHosting = false, dropAfterMs = 300)
                serve(rejectAlreadyHosting = true, dropAfterMs = 0)
                serve(rejectAlreadyHosting = false, dropAfterMs = 3_000)
            } catch (_: Exception) { /* checked on the main thread below */ }
        }
        fakeHost.start()

        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = TOKEN,
            protocolVersion = 1,
            localDeviceName = "guest",
            localBackend = CoopTrackingBackend.ARCORE,
            onBulkReceived = { _, _, _ -> bulkCount++ },
            onOp = { },
            reconnectWindowMs = 15_000L,
            reconnectIntervalMs = 200L,
        )
        guest.connect()

        withTimeout(15_000) { while (bulkCount < 2) delay(50) }
        assertEquals(3, accepts.get())
        assertTrue(
            "AlreadyHosting mid-reconnect must not end the session, was ${guest.state.value}",
            guest.state.value !is CoopSessionState.Ended,
        )

        guest.close(CoopSessionState.EndReason.UserLeft)
        fakeHost.join(10_000)
        server.close()
    }

    @Test
    fun `a failed DELTA_ACK write starts a reconnect without waiting for the read timeout`() = runBlocking {
        val server = ServerSocket(0)
        val port = server.localPort
        val sessionId = "s1"

        // A host that completes the join, then goes silent (no PINGs, no deltas) and never reads:
        // the guest's 15 s read timeout is the only other thing that would notice a dead link.
        val fakeHost = Thread {
            try {
                val sock = server.accept()
                val input = sock.getInputStream()
                val output = sock.getOutputStream()
                val hello = OpCodec.decode<HelloPayload>((Frame.read(input) ?: error("no HELLO")).payload)
                val prk = SessionCrypto.prk(TOKEN)
                val hostNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
                Frame.write(
                    output,
                    FrameType.HELLO_OK,
                    OpCodec.encode(
                        HelloOkPayload(sessionId, 1, hostNonce, SessionCrypto.helloOkProof(prk, hostNonce, hello.guestNonce)),
                    ),
                )
                output.flush()
                val crypto = SessionCrypto.forHost(TOKEN, sessionId, hello.guestNonce, hostNonce)
                Frame.write(output, FrameType.ENC, crypto.seal(FrameType.BULK_BEGIN, OpCodec.encode(BulkBeginPayload("p1", 0, 0, 0, testSpatialFrame()))))
                Frame.write(output, FrameType.ENC, crypto.seal(FrameType.BULK_END, ByteArray(0)))
                output.flush()
                Thread.sleep(20_000)
                sock.close()
            } catch (_: Exception) {}
        }
        fakeHost.isDaemon = true
        fakeHost.start()

        var bulkOk = false
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = TOKEN,
            protocolVersion = 1,
            localDeviceName = "guest",
            localBackend = CoopTrackingBackend.ARCORE,
            onBulkReceived = { _, _, _ -> bulkOk = true },
            onOp = { },
            reconnectWindowMs = 15_000L,
            reconnectIntervalMs = 200L,
        )
        guest.connect()
        withTimeout(10_000) { while (!bulkOk) delay(50) }
        withTimeout(5_000) { guest.state.first { it is CoopSessionState.Connected } }

        // Break only the write side: reads keep blocking normally, so only the DELTA_ACK loop sees it.
        val socket = guest.socketForTest!!
        socket.shutdownOutput()

        // The ack loop writes every 1 s; well inside the 15 s read timeout.
        withTimeout(4_000) { guest.state.first { it is CoopSessionState.Reconnecting } }

        guest.close(CoopSessionState.EndReason.UserLeft)
        server.close()
    }

    // ── 2. Pre-auth handshakes run concurrently ───────────────────────────────────────────────

    @Test
    fun `idle unauthenticated sockets do not delay a legitimate guest`() = runBlocking {
        val host = newHost()
        val port = host.startListening()

        // Several LAN peers that connect and say nothing. Handled serially, each held the accept
        // loop for its HELLO read timeout (5 s) before the next socket was even looked at.
        val idle = List(4) { Socket("127.0.0.1", port) }
        delay(200)

        val start = System.nanoTime()
        RawGuest(port, lastAppliedSeq = 0L).use { guest ->
            guest.readThroughBulkEnd()
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("guest join took ${elapsedMs}ms behind idle sockets", elapsedMs < 3_000)

        idle.forEach { it.close() }
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    // ── 3. An op evicted before it was sent ───────────────────────────────────────────────────

    @Test
    fun `an op evicted before it reached a connected guest forces a resync`() = runBlocking {
        val host = newHost()
        // A tiny buffer so a short burst overflows it.
        host.deltaBuffer = DeltaBuffer(maxOps = 3)
        val port = host.startListening()

        val guest = RawGuest(port, lastAppliedSeq = 0L)
        guest.readThroughBulkEnd()
        withTimeout(5_000) { host.state.first { it is CoopSessionState.Connected } }

        // Stall every writer while a burst is enqueued, so eviction discards ops the outbound loop
        // has not sent yet. Before the fix those were silently skipped and the guest — still
        // connected, never reconnecting — was left without them for good.
        val writeMutex = host.writeMutex
        writeMutex.lock()
        repeat(10) { i -> host.enqueueOp(stroke(i + 1)) }
        writeMutex.unlock()

        withTimeout(5_000) { host.state.first { it is CoopSessionState.Reconnecting } }
        var lastSeq = 0L
        while (true) {
            val f = guest.nextOrNull() ?: break
            if (f.type == FrameType.DELTA) lastSeq = OpCodec.decode<DeltaPayload>(f.payload).seq
        }
        guest.close()

        // The guest's resume lands below the hole, so it is served a bulk with every op in it.
        RawGuest(port, lastAppliedSeq = lastSeq).use { again ->
            assertEquals(FrameType.BULK_BEGIN, again.next().type)
            while (again.next().type != FrameType.BULK_END) { /* drain */ }
            withTimeout(5_000) { host.state.first { it is CoopSessionState.Connected } }
            // No resync loop: ops the bulk already covered must not trigger another drop.
            delay(500)
            assertTrue(host.state.value is CoopSessionState.Connected)
        }
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    // ── 4. A failed replay write falls back to a bulk ─────────────────────────────────────────

    @Test
    fun `a replay entry that cannot be written falls back to a bulk`() = runBlocking {
        val host = newHost()
        val port = host.startListening()

        RawGuest(port, lastAppliedSeq = 0L).use { guest ->
            guest.readThroughBulkEnd()
            host.enqueueOp(stroke(1))
            assertEquals(FrameType.DELTA, guest.nextNonPing().type)
        }
        withTimeout(20_000) { host.state.first { it is CoopSessionState.Reconnecting } }

        // A buffered entry too big for any frame (enqueueOp refuses these, so plant it directly —
        // standing in for any other bad entry), followed by an ordinary op.
        val buffer = host.deltaBuffer
        host.seqCounter.set(2L)
        buffer.append(2L, Op.DesignBitmapReplace(png = ByteArray(Frame.MAX_PAYLOAD_BYTES + 1)), 0)
        host.enqueueOp(stroke(3))

        // Before the fix this resume got DELTA 3 (the bad entry skipped) and went Live one op short.
        RawGuest(port, lastAppliedSeq = 1L).use { guest ->
            assertEquals(FrameType.BULK_BEGIN, guest.next().type)
        }
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    private companion object {
        const val TOKEN = "tok"
    }
}

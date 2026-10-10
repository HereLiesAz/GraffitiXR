package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.Frame
import com.hereliesaz.graffitixr.core.collaboration.wire.FrameType
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloOkPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.HelloPayload
import com.hereliesaz.graffitixr.core.collaboration.wire.OpCodec
import com.hereliesaz.graffitixr.core.collaboration.wire.SessionCrypto
import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real-socket tests of HostSession's reconnect handling, driven by a hand-rolled guest. */
class HostReconnectTest {

    /** A wire-speaking guest: does the HELLO handshake, then reads decrypted frames on demand. */
    private class RawGuest(port: Int, lastAppliedSeq: Long) : Closeable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 8_000 }
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

        fun next(): Frame.FrameRead = crypto.open((Frame.read(input) ?: error("EOF")).payload)

        fun readThroughBulkEnd() {
            while (next().type != FrameType.BULK_END) { /* drain the snapshot */ }
        }

        override fun close() = socket.close()
    }

    private fun snapshot() = ProjectSnapshot(
        fingerprintBytes = ByteArray(128) { it.toByte() },
        projectBytes = ByteArray(256) { (it * 3).toByte() },
        layerCount = 0,
        spatialFrame = testSpatialFrame(),
    )

    @Test
    fun `a reconnect that fails during bulk still ends the session when the window expires`() = runBlocking {
        val failBulk = AtomicBoolean(false)
        val host = HostSession(
            token = TOKEN,
            protocolVersion = 1,
            localDeviceName = "host",
            projectId = "p1",
            snapshotProvider = {
                // Slow enough that the old watcher (200 ms poll on clientSocket) saw the adopted
                // socket and stood down before the bulk failed.
                if (failBulk.get()) { Thread.sleep(800); error("snapshot unavailable") }
                snapshot()
            },
            reconnectWindowMs = 2_000L,
        )
        val port = host.startListening()

        RawGuest(port, lastAppliedSeq = 0L).use { guest ->
            guest.readThroughBulkEnd()
            withTimeout(5_000) { host.state.first { it is CoopSessionState.Connected } }
        }
        // Dropping the socket puts the host into its reconnect window.
        withTimeout(20_000) { host.state.first { it is CoopSessionState.Reconnecting } }

        failBulk.set(true)
        RawGuest(port, lastAppliedSeq = 0L).use {
            // The reconnect is adopted, then its bulk fails. The 30 s (here 2 s) timeout must
            // still fire instead of leaving the host in Reconnecting forever.
            val ended = withTimeout(8_000) { host.state.first { it is CoopSessionState.Ended } }
            assertEquals(CoopSessionState.Ended(CoopSessionState.EndReason.NetworkLost), ended)
        }
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    @Test
    fun `a resume claiming a seq beyond the host counter is served a bulk`() = runBlocking {
        val host = HostSession(
            token = TOKEN,
            protocolVersion = 1,
            localDeviceName = "host",
            projectId = "p1",
            snapshotProvider = { snapshot() },
        )
        val port = host.startListening()

        // The host has assigned no seqs at all; seq 50 cannot be a state it ever sent.
        RawGuest(port, lastAppliedSeq = 50L).use { guest ->
            assertEquals(FrameType.BULK_BEGIN, guest.next().type)
        }
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    private companion object {
        const val TOKEN = "tok"
    }
}

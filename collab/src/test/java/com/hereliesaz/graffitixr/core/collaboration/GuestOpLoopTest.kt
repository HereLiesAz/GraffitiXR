package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.LayerProps
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * Protocol v4, end to end over real localhost sockets: a guest's edits reach the host, are
 * re-broadcast in the host's order, survive a dropped connection exactly once, and a refused edit
 * is neither applied nor sent.
 */
class GuestOpLoopTest {

    private class Rig(hostBackend: CoopTrackingBackend = CoopTrackingBackend.ARCORE,
                      private val guestBackend: CoopTrackingBackend = CoopTrackingBackend.ARCORE) {
        val hostApplied = Channel<Op>(Channel.UNLIMITED)
        val guestReceived = Channel<Op>(Channel.UNLIMITED)
        val bulk = CompletableDeferred<Unit>()
        val host = HostSession(
            token = "tok", protocolVersion = 4, localDeviceName = "host", projectId = "p",
            snapshotProvider = {
                ProjectSnapshot(ByteArray(8), ByteArray(8), 0, testSpatialFrame(backend = hostBackend))
            },
            onGuestOp = { hostApplied.trySend(it) },
        )
        lateinit var guest: GuestSession
        suspend fun start(): Rig {
            val port = host.startListening()
            guest = GuestSession(
                host = "127.0.0.1", port = port, token = "tok", protocolVersion = 4,
                localDeviceName = "guest", localBackend = guestBackend,
                onBulkReceived = { _, _, _ -> bulk.complete(Unit) },
                onOp = { guestReceived.trySend(it) },
                reconnectWindowMs = 10_000L, reconnectIntervalMs = 200L,
            )
            guest.connect()
            withTimeout(15_000) { bulk.await() }
            return this
        }
        suspend fun close() {
            guest.close(CoopSessionState.EndReason.UserLeft)
            host.close(CoopSessionState.EndReason.UserLeft)
        }
    }

    private fun mode(name: String, scale: Float) = Op.ModeTransform(name, ModeAdjustment(scale = scale))

    @Test
    fun `guest edits reach the host in order and come back in the host's order`() = runBlocking {
        val rig = Rig().start()
        // Distinct keys so supersession cannot collapse them.
        val sent = listOf(mode("AR", 2f), mode("TRACE", 3f), Op.DesignProps(LayerProps(opacity = 0.4f)))
        sent.forEach { assertTrue(rig.guest.sendOp(it)) }
        val applied = withTimeout(10_000) { List(3) { rig.hostApplied.receive() } }
        assertEquals(sent, applied)
        val echoed = withTimeout(10_000) { List(3) { rig.guestReceived.receive() } }
        assertEquals(sent, echoed)
        rig.close()
    }

    @Test
    fun `an AR placement between different backends is refused on the guest and never sent`() = runBlocking {
        val rig = Rig(CoopTrackingBackend.SPHERESLAM, CoopTrackingBackend.ARCORE).start()
        assertFalse(rig.guest.sendOp(mode("AR", 2f)))
        assertTrue(rig.guest.sendOp(mode("TRACE", 2f)))
        assertEquals(mode("TRACE", 2f), withTimeout(10_000) { rig.hostApplied.receive() })
        assertNull(withTimeoutOrNull(500) { rig.hostApplied.receive() })
        rig.close()
    }

    @Test
    fun `an edit made while disconnected is delivered once after reconnect`() = runBlocking {
        val rig = Rig().start()
        assertTrue(rig.guest.sendOp(mode("AR", 2f)))
        assertEquals(mode("AR", 2f), withTimeout(10_000) { rig.hostApplied.receive() })
        delay(300) // let the ack land so the first op is not resent

        // Drop the guest's socket out from under it, then edit before it reconnects.
        val socketField = GuestSession::class.java.getDeclaredField("socket").apply { isAccessible = true }
        (socketField.get(rig.guest) as? Socket)?.close()
        assertTrue(rig.guest.sendOp(mode("TRACE", 5f)))

        assertEquals(mode("TRACE", 5f), withTimeout(20_000) { rig.hostApplied.receive() })
        // Exactly once: neither op is re-applied by later resends.
        assertNull(withTimeoutOrNull(1_500) { rig.hostApplied.receive() })
        rig.close()
    }
}

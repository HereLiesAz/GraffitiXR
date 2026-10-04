// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/LocalLoopTest.kt
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-process: host listens on a real ServerSocket on localhost, guest connects
 * via real Socket. Verifies bulk + delta + ack flow.
 */
class LocalLoopTest {

    @Test
    fun `host-to-guest 50 deltas survive bulk and arrive in order`() = runBlocking {
        val fingerprint = ByteArray(1024) { it.toByte() }
        val projectBytes = ByteArray(2048) { (it * 7).toByte() }

        val host = HostSession(
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "host",
            projectId = "p1",
            snapshotProvider = {
                ProjectSnapshot(
                    fingerprintBytes = fingerprint,
                    projectBytes = projectBytes,
                    layerCount = 0,
                    spatialFrame = testSpatialFrame(),
                )
            },
        )
        val port = host.startListening()

        // Collect arriving ops through a Channel rather than polling a shared list under a fixed
        // timeout. The Channel makes the hand-off from the guest's socket-reader coroutine to this
        // test thread safe, and awaiting it wakes the instant each op lands instead of on a 50 ms
        // poll tick — so the test is both race-free and faster, with the withTimeout only a safety
        // net against a genuine hang.
        val bulkMatches = CompletableDeferred<Boolean>()
        val ops = Channel<Op>(Channel.UNLIMITED)
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            localBackend = CoopTrackingBackend.ARCORE,
            onBulkReceived = { fp, pb, _ ->
                bulkMatches.complete(fp.contentEquals(fingerprint) && pb.contentEquals(projectBytes))
            },
            onOp = { op -> ops.trySend(op) },
        )
        guest.connect()

        // Wait for bulk handshake to land.
        assertTrue("bulk fingerprint/project mismatch", withTimeout(15_000) { bulkMatches.await() })

        // Emit 50 DISTINCT deltas. The op type matters: DeltaBuffer.supersede deliberately coalesces
        // design-scoped ops (an Op.DesignReplace subsumes every earlier design op), so bursting 50 of
        // those would — correctly — collapse to the latest, and only the few the outbound loop had
        // already sent before each supersede would arrive. That is the engine working as designed, not
        // a delivery bug. Op.TextContentChange subsumes nothing, so all 50 are retained and delivered
        // in order — which is the invariant this test's name actually claims.
        repeat(50) { i ->
            host.enqueueOp(Op.TextContentChange("n$i"))
        }

        val received = withTimeout(20_000) {
            List(50) { ops.receive() }
        }

        assertEquals(50, received.size)
        received.forEachIndexed { i, op ->
            assertEquals("n$i", (op as Op.TextContentChange).text)
        }

        host.close(CoopSessionState.EndReason.UserLeft)
        guest.close(CoopSessionState.EndReason.UserLeft)
    }
}

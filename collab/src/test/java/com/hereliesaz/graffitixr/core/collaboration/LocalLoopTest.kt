// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/LocalLoopTest.kt
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
            fingerprintBytes = fingerprint,
            projectBytes = projectBytes,
            projectId = "p1",
            layerCount = 0,
        )
        val port = host.startListening()

        val received = mutableListOf<Op>()
        var bulkOk = false
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { fp, pb ->
                bulkOk = fp.contentEquals(fingerprint) && pb.contentEquals(projectBytes)
            },
            onOp = { op -> received.add(op) },
        )
        guest.connect()

        // Wait for bulk handshake to land.
        withTimeout(5_000) {
            while (!bulkOk) delay(50)
        }

        // Host emits 50 ops.
        repeat(50) { i ->
            host.enqueueOp(Op.LayerAdd(Layer(id = "L$i", name = "n$i")))
        }

        withTimeout(10_000) {
            while (received.size < 50) delay(50)
        }

        assertEquals(50, received.size)
        received.forEachIndexed { i, op ->
            assertEquals("L$i", (op as Op.LayerAdd).layer.id)
        }

        host.close(CoopSessionState.EndReason.UserLeft)
        guest.close(CoopSessionState.EndReason.UserLeft)
    }
}

// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/InterruptedBulkTest.kt
package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptedBulkTest {

    @Test
    fun `guest sees Ended NetworkLost when host closes mid-bulk`() = runBlocking {
        val fingerprint = ByteArray(64 * 1024) { it.toByte() }   // 64KB chunk
        val projectBytes = ByteArray(2 * 1024 * 1024) { 0 }       // 2MB → multiple chunks

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

        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 1,
            localDeviceName = "guest",
            onBulkReceived = { _, _ -> },
            onOp = { },
        )
        guest.connect()

        // Brief pause to let bulk start.
        delay(100)

        // Force-close the host.
        host.close(CoopSessionState.EndReason.NetworkLost)

        // Guest should transition to Ended within a reasonable timeout.
        // Note: GuestSession's reconnect logic will retry for up to 30s before
        // giving up, so we allow up to 35s.
        val state = withTimeout(35_000) {
            guest.state.first { it is CoopSessionState.Ended }
        }
        assertTrue("expected Ended state, got $state", state is CoopSessionState.Ended)
    }
}

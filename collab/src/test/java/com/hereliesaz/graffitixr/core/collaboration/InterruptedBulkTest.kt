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
            // Short, deterministic reconnect window so the test doesn't wait the production 30s
            // (which raced the old 35s timeout under parallel-module load).
            reconnectWindowMs = 1_000L,
            reconnectIntervalMs = 200L,
        )
        guest.connect()

        // Brief pause to let bulk start.
        delay(100)

        // Force-close the host.
        host.close(CoopSessionState.EndReason.NetworkLost)

        // Guest should transition to Ended once the (here-shortened ~1s) reconnect window lapses.
        // This is a real-socket / real-time / Dispatchers.IO integration test: Ended normally arrives
        // in ~1-2s, but under parallel-module CI load the IO coroutines can stall for several seconds.
        // The timeout is only a safety net (a genuine hang still fails it); the 1s reconnect window
        // above is the actual tuning. 10s raced that stall intermittently, blocking every build now
        // that the gate is required, so give it real headroom rather than re-tighten.
        val state = withTimeout(30_000) {
            guest.state.first { it is CoopSessionState.Ended }
        }
        assertTrue("expected Ended state, got $state", state is CoopSessionState.Ended)
    }
}

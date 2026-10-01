package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopSpatialScale
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialCompatibilityTest {
    @Test
    fun `normalized standalone host rejects ARCore but accepts standalone guest`() = runBlocking {
        val normalized = testSpatialFrame(
            backend = CoopTrackingBackend.SPHERESLAM,
            scale = CoopSpatialScale.NORMALIZED_PAGE,
            fingerprintAvailable = true,
        )
        val host = HostSession(
            token = "tok",
            protocolVersion = 3,
            localDeviceName = "host",
            projectId = "p1",
            snapshotProvider = {
                ProjectSnapshot(
                    fingerprintBytes = ByteArray(64) { it.toByte() },
                    projectBytes = ByteArray(64),
                    layerCount = 0,
                    spatialFrame = normalized,
                )
            },
        )
        val port = host.startListening()

        val arCoreGuest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 3,
            localDeviceName = "arcore",
            localBackend = CoopTrackingBackend.ARCORE,
            onBulkReceived = { _, _, _ -> error("incompatible guest must not receive bulk") },
            onOp = {},
        )
        arCoreGuest.connect()
        val ended = withTimeout(5_000) {
            arCoreGuest.state.first { it is CoopSessionState.Ended }
        } as CoopSessionState.Ended
        assertEquals(CoopSessionState.EndReason.SpatialIncompatible, ended.reason)

        var bulkReceived = false
        val standaloneGuest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 3,
            localDeviceName = "standalone",
            localBackend = CoopTrackingBackend.SPHERESLAM,
            onBulkReceived = { _, _, frame ->
                assertEquals(normalized, frame)
                bulkReceived = true
            },
            onOp = {},
        )
        standaloneGuest.connect()
        withTimeout(5_000) {
            while (!bulkReceived) delay(25)
        }
        assertTrue(bulkReceived)

        host.close(CoopSessionState.EndReason.UserLeft)
        standaloneGuest.close(CoopSessionState.EndReason.UserLeft)
    }
    @Test
    fun `host frame change before bulk ends session instead of reframing guest`() = runBlocking {
        var currentFrame = testSpatialFrame(
            backend = CoopTrackingBackend.ARCORE,
            scale = CoopSpatialScale.METRIC,
            fingerprintAvailable = true,
            revision = 1L,
        )
        val host = HostSession(
            token = "tok",
            protocolVersion = 3,
            localDeviceName = "host",
            projectId = "p1",
            snapshotProvider = {
                ProjectSnapshot(
                    fingerprintBytes = ByteArray(64) { it.toByte() },
                    projectBytes = ByteArray(64),
                    layerCount = 0,
                    spatialFrame = currentFrame,
                )
            },
        )
        // HostSession freezes revision 1 at construction. Simulate a wall recapture before the
        // guest's fresh bulk snapshot is produced; the snapshot must not be sent under revision 1.
        currentFrame = currentFrame.copy(anchorRevision = 2L)
        val port = host.startListening()

        var bulkReceived = false
        val guest = GuestSession(
            host = "127.0.0.1",
            port = port,
            token = "tok",
            protocolVersion = 3,
            localDeviceName = "guest",
            localBackend = CoopTrackingBackend.ARCORE,
            onBulkReceived = { _, _, _ -> bulkReceived = true },
            onOp = {},
            reconnectWindowMs = 1_000L,
            reconnectIntervalMs = 100L,
        )
        guest.connect()

        val hostEnded = withTimeout(5_000) {
            host.state.first { it is CoopSessionState.Ended }
        } as CoopSessionState.Ended
        assertEquals(CoopSessionState.EndReason.SpatialFrameChanged, hostEnded.reason)
        assertTrue("changed wall frame must never be transferred as bulk", !bulkReceived)

        guest.close(CoopSessionState.EndReason.UserLeft)
    }

}

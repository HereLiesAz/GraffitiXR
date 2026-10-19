package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.core.collaboration.session.GuestSession
import com.hereliesaz.graffitixr.core.collaboration.session.HostSession
import com.hereliesaz.graffitixr.core.collaboration.wire.ProtocolVersion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol v7, over real localhost sockets: the tripod stream flows only while a guest asks for it. */
class VideoStreamTest {

    private fun host() = HostSession(
        token = "tok", protocolVersion = ProtocolVersion.CURRENT, localDeviceName = "tripod", projectId = "p",
        snapshotProvider = { ProjectSnapshot(ByteArray(8), ByteArray(8), testSpatialFrame()) },
    )

    private fun guest(port: Int) = GuestSession(
        host = "127.0.0.1", port = port, token = "tok", protocolVersion = ProtocolVersion.CURRENT,
        localDeviceName = "phone", localBackend = CoopTrackingBackend.ARCORE,
        onBulkReceived = { _, _, _ -> }, onOp = {},
        reconnectWindowMs = 10_000L, reconnectIntervalMs = 200L,
    )

    @Test
    fun `frames are dropped until the guest asks, delivered while it does, and stop when it stops`() = runBlocking {
        val host = host()
        val guest = guest(host.startListening())
        guest.connect()
        withTimeout(15_000) { guest.state.first { it is CoopSessionState.Connected } }

        val jpeg = byteArrayOf(1, 2, 3, 4)
        assertFalse(host.offerVideo(jpeg))
        assertFalse(host.videoWanted.value)

        guest.setVideoWanted(true)
        withTimeout(15_000) { host.videoWanted.first { it } }
        assertTrue(host.offerVideo(jpeg))
        val got = withTimeout(15_000) { guest.video.first { it != null } }
        assertArrayEquals(jpeg, got)

        guest.setVideoWanted(false)
        assertNull(guest.video.value)
        withTimeout(15_000) { host.videoWanted.first { !it } }
        assertFalse(host.offerVideo(jpeg))

        guest.close(CoopSessionState.EndReason.UserLeft)
        host.close(CoopSessionState.EndReason.UserLeft)
    }

    @Test
    fun `an oversized frame is refused rather than breaking the connection`() = runBlocking {
        val host = host()
        val guest = guest(host.startListening())
        guest.connect()
        withTimeout(15_000) { guest.state.first { it is CoopSessionState.Connected } }
        guest.setVideoWanted(true)
        withTimeout(15_000) { host.videoWanted.first { it } }
        assertFalse(host.offerVideo(ByteArray(17 * 1024 * 1024)))
        guest.close(CoopSessionState.EndReason.UserLeft)
        host.close(CoopSessionState.EndReason.UserLeft)
    }
}

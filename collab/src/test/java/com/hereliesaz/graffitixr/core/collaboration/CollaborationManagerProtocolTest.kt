package com.hereliesaz.graffitixr.core.collaboration

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopTrackingBackend
import com.hereliesaz.graffitixr.core.collaboration.wire.ProtocolVersion
import com.hereliesaz.graffitixr.core.collaboration.wire.QrPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollaborationManagerProtocolTest {
    @Test
    fun `old QR is rejected locally as version mismatch before socket connect`() = runBlocking {
        val manager = CollaborationManager()
        val qr = QrPayload(
            host = "127.0.0.1",
            port = 9,
            token = "deadbeef",
            protocolVersion = ProtocolVersion.CURRENT - 1,
        ).encode()

        var rejected = false
        try {
            manager.joinFromQr(
                qr = qr,
                localDeviceName = "test",
                localBackend = CoopTrackingBackend.SPHERESLAM,
                onBulkReceived = { _, _, _ -> error("must not receive bulk") },
                onOp = {},
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue(rejected)
        assertEquals(
            CoopSessionState.Ended(CoopSessionState.EndReason.VersionMismatch),
            manager.state.value,
        )
    }
}

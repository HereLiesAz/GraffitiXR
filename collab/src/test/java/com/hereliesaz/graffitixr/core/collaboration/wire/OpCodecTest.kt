package com.hereliesaz.graffitixr.core.collaboration.wire

import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertEquals
import org.junit.Test

class OpCodecTest {

    @Test
    fun `Hello round-trips`() {
        val original = HelloPayload(token = "abc", clientVersion = 1, deviceName = "Pixel")
        assertEquals(original, OpCodec.decode<HelloPayload>(OpCodec.encode(original)))
    }

    @Test
    fun `HelloRejected round-trips with each reason`() {
        HelloRejectedPayload.RejectReason.entries.forEach { reason ->
            val original = HelloRejectedPayload(reason)
            assertEquals(original, OpCodec.decode<HelloRejectedPayload>(OpCodec.encode(original)))
        }
    }

    @Test
    fun `BulkBegin round-trips`() {
        val original = BulkBeginPayload("p1", 5, 1024, 4096)
        assertEquals(original, OpCodec.decode<BulkBeginPayload>(OpCodec.encode(original)))
    }

    @Test
    fun `Delta round-trips with LayerAdd op`() {
        val original = DeltaPayload(seq = 42L, op = Op.LayerAdd(Layer(id = "L1", name = "one")))
        assertEquals(original, OpCodec.decode<DeltaPayload>(OpCodec.encode(original)))
    }

    @Test
    fun `Bye round-trips with each reason`() {
        CoopSessionState.EndReason.entries.forEach { reason ->
            val original = ByePayload(reason)
            assertEquals(original, OpCodec.decode<ByePayload>(OpCodec.encode(original)))
        }
    }
}

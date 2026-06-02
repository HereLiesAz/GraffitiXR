package com.hereliesaz.graffitixr.feature.ar.eval

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DepthLookupTest {
    private fun buf(vararg mm: Short): ByteBuffer {
        val b = ByteBuffer.allocateDirect(mm.size * 2).order(ByteOrder.nativeOrder())
        mm.forEach { b.putShort(it) }; b.position(0); return b
    }

    @Test fun `reads mm to meters at a pixel`() {
        // 2x2, stride = 4 bytes/row. (0,0)=1000mm (1,0)=2000 (0,1)=3000 (1,1)=4000
        val b = buf(1000, 2000, 3000, 4000)
        assertEquals(1.0f, DepthLookup.depthMetersAt(b, stride = 4, depthW = 2, depthH = 2, u = 0.0f, v = 0.0f), 1e-3f)
        assertEquals(4.0f, DepthLookup.depthMetersAt(b, 4, 2, 2, u = 0.99f, v = 0.99f), 1e-3f)
    }

    @Test fun `out of range yields -1`() {
        val b = buf(0, 8000, 0, 0) // 0 invalid; 8000 > 7900 invalid
        assertEquals(-1f, DepthLookup.depthMetersAt(b, 4, 2, 2, 0f, 0f), 1e-3f)
        assertEquals(-1f, DepthLookup.depthMetersAt(b, 4, 2, 2, 0.99f, 0f), 1e-3f)
    }

    @Test fun `patch median rejects an outlier center pixel`() {
        // 3x3, stride = 6 bytes/row. Center (1,1) is a 7000mm spike; its 8 neighbors are all 1000mm.
        // Single-pixel read at center returns 7.0; patch median (radius 1) returns 1.0.
        val b = buf(
            1000, 1000, 1000,
            1000, 7000, 1000,
            1000, 1000, 1000,
        )
        assertEquals(7.0f, DepthLookup.depthMetersAt(b, 6, 3, 3, 0.5f, 0.5f), 1e-3f)
        assertEquals(1.0f, DepthLookup.depthMetersAtPatch(b, 6, 3, 3, 0.5f, 0.5f, radius = 1), 1e-3f)
    }

    @Test fun `patch ignores holes and returns -1 when all invalid`() {
        val b = buf(0, 0, 0, 0) // all holes
        assertEquals(-1f, DepthLookup.depthMetersAtPatch(b, 4, 2, 2, 0.5f, 0.5f, radius = 1), 1e-3f)
    }
}

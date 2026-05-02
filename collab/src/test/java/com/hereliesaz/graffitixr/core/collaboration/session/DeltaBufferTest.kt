// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBufferTest.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaBufferTest {

    private fun op(id: String) = Op.LayerAdd(Layer(id = id, name = id))

    @Test
    fun `append + opsAfter returns ops with seq greater than threshold`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10)
        val after1 = buf.opsAfter(1)
        assertEquals(listOf(2L to op("b"), 3L to op("c")), after1)
    }

    @Test
    fun `trimUpTo removes entries with seq at or below threshold`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10)
        buf.trimUpTo(2)
        assertEquals(listOf(3L to op("c")), buf.opsAfter(0))
    }

    @Test
    fun `cap by ops returns false when full`() {
        val buf = DeltaBuffer(maxOps = 2)
        assertTrue(buf.append(1, op("a"), 10))
        assertTrue(buf.append(2, op("b"), 10))
        assertFalse(buf.append(3, op("c"), 10))
    }

    @Test
    fun `cap by bytes returns false when full`() {
        val buf = DeltaBuffer(maxBytes = 100)
        assertTrue(buf.append(1, op("a"), 60))
        assertFalse(buf.append(2, op("b"), 60))
    }

    @Test
    fun `single op larger than cap is rejected`() {
        val buf = DeltaBuffer(maxBytes = 100)
        assertFalse(buf.append(1, op("big"), 200))
    }

    @Test
    fun `clear empties buffer`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(0L, buf.bytes())
    }
}

// collab/src/test/java/com/hereliesaz/graffitixr/core/collaboration/session/DeltaBufferTest.kt
package com.hereliesaz.graffitixr.core.collaboration.session

import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Op
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaBufferTest {

    // TextContentChange subsumes nothing and is subsumed by nothing, so it is the stand-in for
    // "an ordinary op that must survive coalescing".
    private fun op(id: String) = Op.TextContentChange(id)
    private fun bitmap(marker: Byte) = Op.DesignBitmapReplace(byteArrayOf(marker))
    private fun transform(scale: Float) = Op.DesignTransform(listOf(scale, 0f, 0f, 1f))

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
    fun `clear empties buffer`() {
        val buf = DeltaBuffer()
        buf.append(1, op("a"), 10)
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(0L, buf.bytes())
        assertFalse(buf.hasGap())
    }

    // --- Overflow degrades instead of failing -----------------------------------------------

    @Test
    fun `op cap evicts oldest and marks a gap instead of refusing`() {
        val buf = DeltaBuffer(maxOps = 2)
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10)

        assertEquals(2, buf.size())
        assertTrue(buf.hasGap())
        // The newest op is always retained; the oldest is what went.
        assertEquals(listOf(2L to op("b"), 3L to op("c")), buf.opsAfter(1))
    }

    @Test
    fun `byte cap evicts oldest and marks a gap instead of refusing`() {
        val buf = DeltaBuffer(maxBytes = 100)
        buf.append(1, op("a"), 60)
        buf.append(2, op("b"), 60)

        assertEquals(1, buf.size())
        assertTrue(buf.hasGap())
        assertEquals(listOf(2L to op("b")), buf.opsAfter(1))
    }

    @Test
    fun `a single op larger than the whole budget is still retained`() {
        // The case that used to end a live session: a whole-canvas DesignBitmapReplace exceeded
        // the cap on its own and was refused.
        val buf = DeltaBuffer(maxBytes = 100)
        buf.append(1, bitmap(1), 200)

        assertEquals(1, buf.size())
        assertEquals(listOf<Pair<Long, Op>>(1L to bitmap(1)), buf.opsAfter(0))
    }

    @Test
    fun `a guest inside the gap gets null so the caller re-sends bulk`() {
        val buf = DeltaBuffer(maxOps = 2)
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10) // evicts seq 1

        assertNull("resuming before the hole cannot be replayed", buf.opsAfter(0))
        // A guest already past the hole is unaffected.
        assertEquals(listOf(3L to op("c")), buf.opsAfter(2))
    }

    @Test
    fun `an ack past the hole clears the gap`() {
        val buf = DeltaBuffer(maxOps = 2)
        buf.append(1, op("a"), 10)
        buf.append(2, op("b"), 10)
        buf.append(3, op("c"), 10)
        assertTrue(buf.hasGap())

        buf.trimUpTo(1) // guest confirms it applied everything through the evicted seq
        assertFalse(buf.hasGap())
        assertEquals(listOf(2L to op("b"), 3L to op("c")), buf.opsAfter(1))
    }

    // --- Coalescing -------------------------------------------------------------------------

    @Test
    fun `a bitmap replace supersedes earlier pixel ops but not transform or tone`() {
        val buf = DeltaBuffer()
        buf.append(1, bitmap(1), 1_000)
        buf.append(2, transform(2f), 10)
        buf.append(3, bitmap(2), 1_000)

        // The transform survives: replacing the pixels says nothing about where the design sits.
        assertEquals(
            listOf<Pair<Long, Op>>(2L to transform(2f), 3L to bitmap(2)),
            buf.opsAfter(0),
        )
    }

    @Test
    fun `an absolute transform supersedes earlier transforms only`() {
        val buf = DeltaBuffer()
        buf.append(1, transform(1f), 10)
        buf.append(2, bitmap(1), 10)
        buf.append(3, transform(3f), 10)

        assertEquals(
            listOf<Pair<Long, Op>>(2L to bitmap(1), 3L to transform(3f)),
            buf.opsAfter(0),
        )
    }

    @Test
    fun `replacing the design supersedes everything before it`() {
        val buf = DeltaBuffer()
        buf.append(1, bitmap(1), 10)
        buf.append(2, transform(2f), 10)
        val replacement = Op.DesignReplace(Layer(id = "d", name = "design"))
        buf.append(3, replacement, 10)

        assertEquals(listOf<Pair<Long, Op>>(3L to replacement), buf.opsAfter(0))
    }

    @Test
    fun `repeated bitmap replacements stay bounded`() {
        // The scenario the old cap turned into a disconnect: many whole-canvas replaces in a row.
        val buf = DeltaBuffer(maxBytes = 10_000)
        repeat(50) { i -> buf.append(i + 1L, bitmap(i.toByte()), 4_000) }

        assertEquals(1, buf.size())
        assertFalse("coalescing kept it in budget, so no history was lost", buf.hasGap())
    }
}

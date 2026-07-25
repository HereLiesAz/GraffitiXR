package com.hereliesaz.graffitixr.feature.editor

import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun layers(vararg ids: String) = ids.map { Layer(id = it, name = it) }

    /** The counterpart entry a real caller would record; content is irrelevant to stack mechanics. */
    private fun counter(current: List<Layer>): (EditCommand) -> EditCommand = { EditCommand(current) }

    @Test
    fun `starts empty`() {
        val h = EditHistory()
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `pushProperty records a snapshot`() {
        val h = EditHistory()
        assertTrue(h.pushProperty(layers("a")))
        assertEquals(1, h.undoCount)
    }

    @Test
    fun `pushProperty deduplicates an identical consecutive snapshot`() {
        val h = EditHistory()
        assertTrue(h.pushProperty(layers("a")))
        assertFalse(h.pushProperty(layers("a")))
        assertEquals(1, h.undoCount)
    }

    @Test
    fun `pushProperty clears the redo stack`() {
        val h = EditHistory()
        h.pushProperty(layers("a"))
        h.popUndo(counter(layers("b")))
        assertEquals(1, h.redoCount)

        h.pushProperty(layers("c"))
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `popUndo moves the command onto the redo stack`() {
        val h = EditHistory()
        h.pushProperty(layers("a"))

        val popped = h.popUndo(counter(layers("b")))
        assertEquals(layers("a"), popped?.oldLayers)
        assertEquals(0, h.undoCount)
        assertEquals(1, h.redoCount)
    }

    @Test
    fun `popRedo moves the command back onto the undo stack`() {
        val h = EditHistory()
        h.pushProperty(layers("a"))
        h.popUndo(counter(layers("b")))

        val redone = h.popRedo(counter(layers("a")))
        assertEquals(layers("b"), redone?.oldLayers)
        assertEquals(1, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `popping an empty stack returns null and records nothing`() {
        val h = EditHistory()
        assertNull(h.popUndo(counter(layers("a"))))
        assertNull(h.popRedo(counter(layers("a"))))
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `undo stack is capped at the configured size`() {
        val h = EditHistory(maxStackSize = 3)
        repeat(6) { h.pushProperty(layers("layer-$it")) }
        assertEquals(3, h.undoCount)
        // The oldest entries were dropped, so the deepest remaining is the 4th push.
        repeat(2) { h.popUndo(counter(emptyList())) }
        assertEquals(layers("layer-3"), h.popUndo(counter(emptyList()))?.oldLayers)
    }

    @Test
    fun `clear empties both stacks`() {
        val h = EditHistory()
        h.pushProperty(layers("a"))
        h.popUndo(counter(layers("b")))
        h.clear()
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }
}

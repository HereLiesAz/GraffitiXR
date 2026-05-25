package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.unit.IntSize
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {

    private fun layer(id: String) = Layer(id = id, name = id)
    private fun stroke() = StrokeCommand(
        path = emptyList(),
        canvasSize = IntSize(1, 1),
        tool = Tool.NONE,
        brushSize = 1f,
        brushColor = 0,
        intensity = 0.5f,
    )

    @Test
    fun `starts empty`() {
        val h = EditHistory()
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `pushProperty deduplicates an identical consecutive snapshot`() {
        val h = EditHistory()
        val snapshot = listOf(layer("a"))
        assertTrue(h.pushProperty(snapshot))
        assertEquals(false, h.pushProperty(listOf(layer("a")))) // structurally equal -> ignored
        assertEquals(1, h.undoCount)
    }

    @Test
    fun `pushProperty trims to the max stack size`() {
        val h = EditHistory(maxStackSize = 2)
        h.pushProperty(listOf(layer("a")))
        h.pushProperty(listOf(layer("b")))
        h.pushProperty(listOf(layer("c")))
        assertEquals(2, h.undoCount)
    }

    @Test
    fun `pushing clears the redo stack`() {
        val h = EditHistory()
        h.pushProperty(listOf(layer("a")))
        h.popUndo { it }                       // redo now has 1
        assertEquals(1, h.redoCount)
        h.pushDraw("layer-1", stroke())        // any push must clear redo
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `popUndo on empty history returns null and records nothing`() {
        val h = EditHistory()
        assertNull(h.popUndo { it })
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `popUndo moves the counterpart entry onto the redo stack`() {
        val h = EditHistory()
        h.pushDraw("layer-1", stroke())
        val popped = h.popUndo { it }
        assertTrue(popped is EditCommand.Draw)
        assertEquals(0, h.undoCount)
        assertEquals(1, h.redoCount)
    }

    @Test
    fun `popRedo moves the counterpart entry back onto the undo stack`() {
        val h = EditHistory()
        h.pushDraw("layer-1", stroke())
        h.popUndo { it }
        val redone = h.popRedo { it }
        assertTrue(redone is EditCommand.Draw)
        assertEquals(1, h.undoCount)
        assertEquals(0, h.redoCount)
    }

    @Test
    fun `clear empties both stacks`() {
        val h = EditHistory()
        h.pushProperty(listOf(layer("a")))
        h.pushDraw("layer-1", stroke())
        h.popUndo { it }
        h.clear()
        assertEquals(0, h.undoCount)
        assertEquals(0, h.redoCount)
    }
}

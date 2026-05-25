package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [EditorReducer] — no mocks, no Android, no OpenCV. Just state in, state out.
 * This is the payoff of the MVI redesign: the editor's state transitions are verifiable in
 * isolation, which the god-class ViewModel never allowed.
 */
class EditorReducerTest {

    private fun lyr(id: String, name: String = id) = Layer(id = id, name = name)
    private fun state(vararg layers: Layer, active: String? = null) =
        EditorUiState(layers = layers.toList(), activeLayerId = active)

    private fun reduce(s: EditorUiState, i: EditorIntent) = EditorReducer.reduce(s, i)

    @Test
    fun `SetOpacity changes only the active layer`() {
        val s = state(lyr("a"), lyr("b"), active = "a")
        val out = reduce(s, EditorIntent.SetOpacity(0.3f))
        assertEquals(0.3f, out.layers.first { it.id == "a" }.opacity)
        assertEquals(1.0f, out.layers.first { it.id == "b" }.opacity)
    }

    @Test
    fun `property change with no active layer is a no-op`() {
        val s = state(lyr("a"), active = null)
        assertSame(s, reduce(s, EditorIntent.SetBrightness(0.5f)))
    }

    @Test
    fun `AddOffset accumulates onto the existing offset`() {
        val s = state(lyr("a").copy(offset = Offset(10f, 5f)), active = "a")
        val out = reduce(s, EditorIntent.AddOffset(Offset(3f, -2f)))
        assertEquals(Offset(13f, 3f), out.layers.first().offset)
    }

    @Test
    fun `SetRotationX sets the rotation and the active axis`() {
        val s = state(lyr("a"), active = "a")
        val out = reduce(s, EditorIntent.SetRotationX(45f))
        assertEquals(45f, out.layers.first().rotationX)
        assertEquals(RotationAxis.X, out.activeRotationAxis)
    }

    @Test
    fun `CycleRotationAxis advances X to Y to Z to X and shows feedback`() {
        var s = state(lyr("a"), active = "a").copy(activeRotationAxis = RotationAxis.X)
        s = reduce(s, EditorIntent.CycleRotationAxis); assertEquals(RotationAxis.Y, s.activeRotationAxis)
        assertTrue(s.showRotationAxisFeedback)
        s = reduce(s, EditorIntent.CycleRotationAxis); assertEquals(RotationAxis.Z, s.activeRotationAxis)
        s = reduce(s, EditorIntent.CycleRotationAxis); assertEquals(RotationAxis.X, s.activeRotationAxis)
    }

    @Test
    fun `ToggleInvert and ToggleImageLock flip the active layer`() {
        val s = state(lyr("a"), active = "a")
        assertTrue(reduce(s, EditorIntent.ToggleInvert).layers.first().isInverted)
        assertTrue(reduce(s, EditorIntent.ToggleImageLock).layers.first().isImageLocked)
    }

    @Test
    fun `ReorderLayers reorders by id`() {
        val s = state(lyr("a"), lyr("b"), lyr("c"))
        assertEquals(listOf("c", "a", "b"), reduce(s, EditorIntent.ReorderLayers(listOf("c", "a", "b"))).layers.map { it.id })
    }

    @Test
    fun `RenameLayer and ToggleVisibility affect only the target`() {
        val s = state(lyr("a", "Alpha"), lyr("b", "Beta"))
        assertEquals("X", reduce(s, EditorIntent.RenameLayer("a", "X")).layers.first { it.id == "a" }.name)
        assertFalse(reduce(s, EditorIntent.ToggleVisibility("a")).layers.first { it.id == "a" }.isVisible)
    }

    @Test
    fun `ActivateLayer sets the active id and resets the tool`() {
        val s = state(lyr("a"), lyr("b")).copy(activeTool = Tool.LIQUIFY)
        val out = reduce(s, EditorIntent.ActivateLayer("b"))
        assertEquals("b", out.activeLayerId)
        assertEquals(Tool.NONE, out.activeTool)
    }

    @Test
    fun `SetActiveTool sets the tool and dismisses the panel`() {
        val s = state(lyr("a")).copy(activePanel = EditorPanel.ADJUST)
        val out = reduce(s, EditorIntent.SetActiveTool(Tool.LIQUIFY))
        assertEquals(Tool.LIQUIFY, out.activeTool)
        assertEquals(EditorPanel.NONE, out.activePanel)
    }

    @Test
    fun `ToggleAdjustPanel toggles between ADJUST and NONE`() {
        val none = state(lyr("a"))
        val opened = reduce(none, EditorIntent.ToggleAdjustPanel)
        assertEquals(EditorPanel.ADJUST, opened.activePanel)
        assertEquals(EditorPanel.NONE, reduce(opened, EditorIntent.ToggleAdjustPanel).activePanel)
    }

    @Test
    fun `SetEditorMode keeps layers but clears transient overlay state`() {
        val s = state(lyr("a"), lyr("b")).copy(
            editorMode = EditorMode.AR,
            isSegmenting = true,
            liveStrokeLayerId = "a",
        )
        val out = reduce(s, EditorIntent.SetEditorMode(EditorMode.MOCKUP))
        assertEquals(EditorMode.MOCKUP, out.editorMode)
        assertEquals(listOf("a", "b"), out.layers.map { it.id })
        assertFalse(out.isSegmenting)
        assertNull(out.liveStrokeLayerId)
    }

    @Test
    fun `SetEditorMode to the current mode is a no-op`() {
        val s = state(lyr("a")).copy(editorMode = EditorMode.MOCKUP, isSegmenting = true)
        assertSame(s, reduce(s, EditorIntent.SetEditorMode(EditorMode.MOCKUP)))
    }
}

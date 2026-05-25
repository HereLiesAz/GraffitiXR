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

    @Test
    fun `AddLayer appends, activates, clears the tool, and resets the panel by default`() {
        val s = state(lyr("a"), active = "a").copy(activeTool = Tool.LIQUIFY, activePanel = EditorPanel.ADJUST)
        val out = reduce(s, EditorIntent.AddLayer(lyr("b")))
        assertEquals(listOf("a", "b"), out.layers.map { it.id })
        assertEquals("b", out.activeLayerId)
        assertEquals(Tool.NONE, out.activeTool)
        assertEquals(EditorPanel.NONE, out.activePanel)
    }

    @Test
    fun `AddLayer with resetActivePanel false leaves the panel open`() {
        val s = state(lyr("a")).copy(activePanel = EditorPanel.ADJUST)
        assertEquals(EditorPanel.ADJUST, reduce(s, EditorIntent.AddLayer(lyr("b"), resetActivePanel = false)).activePanel)
    }

    @Test
    fun `RemoveLayer reactivates the first remaining layer when the active one is removed`() {
        val s = state(lyr("a"), lyr("b"), active = "a")
        val out = reduce(s, EditorIntent.RemoveLayer("a"))
        assertEquals(listOf("b"), out.layers.map { it.id })
        assertEquals("b", out.activeLayerId)
    }

    @Test
    fun `RemoveLayer keeps the active id when a non-active layer is removed`() {
        val s = state(lyr("a"), lyr("b"), active = "b")
        assertEquals("b", reduce(s, EditorIntent.RemoveLayer("a")).activeLayerId)
    }

    @Test
    fun `RemoveLayer of the only layer clears the active id`() {
        val s = state(lyr("a"), active = "a")
        val out = reduce(s, EditorIntent.RemoveLayer("a"))
        assertTrue(out.layers.isEmpty())
        assertNull(out.activeLayerId)
    }

    @Test
    fun `ReplaceLayers swaps the whole set and activates the given id`() {
        val s = state(lyr("a"), lyr("b"), lyr("c"), active = "b")
        val out = reduce(s, EditorIntent.ReplaceLayers(listOf(lyr("flat")), "flat"))
        assertEquals(listOf("flat"), out.layers.map { it.id })
        assertEquals("flat", out.activeLayerId)
    }

    @Test
    fun `SetLoading toggles the loading flag`() {
        assertTrue(reduce(state(lyr("a")), EditorIntent.SetLoading(true)).isLoading)
        assertFalse(reduce(state(lyr("a")).copy(isLoading = true), EditorIntent.SetLoading(false)).isLoading)
    }

    @Test
    fun `BeginSegmentation sets the flag and default influence and EndSegmentation clears state`() {
        val begun = reduce(state(lyr("a")), EditorIntent.BeginSegmentation)
        assertTrue(begun.isSegmenting)
        assertEquals(0.5f, begun.segmentationInfluence)
        val ended = reduce(begun.copy(segmentationPreview = null), EditorIntent.EndSegmentation)
        assertFalse(ended.isSegmenting)
        assertNull(ended.segmentationPreview)
    }

    @Test
    fun `stencil flag intents update their fields`() {
        val s = state(lyr("a"))
        assertTrue(reduce(s, EditorIntent.SetStencilGenerating(true)).isStencilGenerating)
        assertFalse(reduce(s.copy(stencilHintVisible = true), EditorIntent.SetStencilHintVisible(false)).stencilHintVisible)
    }

    @Test
    fun `SetBrushSize and SetSketchThickness coerce into range`() {
        assertEquals(200f, reduce(state(lyr("a")), EditorIntent.SetBrushSize(9999f)).brushSize)
        assertEquals(1f, reduce(state(lyr("a")), EditorIntent.SetBrushSize(-5f)).brushSize)
        assertEquals(20, reduce(state(lyr("a")), EditorIntent.SetSketchThickness(99)).sketchThickness)
    }

    @Test
    fun `SetActiveColor sets the color and closes the picker`() {
        val s = state(lyr("a")).copy(showColorPicker = true)
        val out = reduce(s, EditorIntent.SetActiveColor(androidx.compose.ui.graphics.Color.Red))
        assertEquals(androidx.compose.ui.graphics.Color.Red, out.activeColor)
        assertFalse(out.showColorPicker)
    }

    @Test
    fun `ToggleHandedness flips the handedness flag`() {
        val s = state(lyr("a")).copy(isRightHanded = true)
        assertFalse(reduce(s, EditorIntent.ToggleHandedness).isRightHanded)
    }

    @Test
    fun `SetLayerWarp sets the mesh on the target layer only`() {
        val s = state(lyr("a"), lyr("b"))
        val out = reduce(s, EditorIntent.SetLayerWarp("a", listOf(1f, 2f, 3f)))
        assertEquals(listOf(1f, 2f, 3f), out.layers.first { it.id == "a" }.warpMesh)
        assertTrue(out.layers.first { it.id == "b" }.warpMesh.isEmpty())
    }

    @Test
    fun `AppendLayer adds without touching the active layer (spectator path)`() {
        val s = state(lyr("a"), active = "a")
        val out = reduce(s, EditorIntent.AppendLayer(lyr("b")))
        assertEquals(listOf("a", "b"), out.layers.map { it.id })
        assertEquals("a", out.activeLayerId) // unchanged, unlike AddLayer
    }

    @Test
    fun `RemoveLayerById drops the layer without reactivating (spectator path)`() {
        val s = state(lyr("a"), lyr("b"), active = "a")
        val out = reduce(s, EditorIntent.RemoveLayerById("a"))
        assertEquals(listOf("b"), out.layers.map { it.id })
        assertEquals("a", out.activeLayerId) // unchanged, unlike RemoveLayer
    }

    @Test
    fun `SetLayerProps copies all props onto the target layer`() {
        val s = state(lyr("a"), lyr("b"))
        val props = com.hereliesaz.graffitixr.common.model.LayerProps(opacity = 0.4f, isVisible = false)
        val out = reduce(s, EditorIntent.SetLayerProps("a", props))
        val a = out.layers.first { it.id == "a" }
        assertEquals(0.4f, a.opacity)
        assertFalse(a.isVisible)
        assertEquals(1.0f, out.layers.first { it.id == "b" }.opacity)
    }

    @Test
    fun `SetLayerTransformById sets transform on the target layer`() {
        val s = state(lyr("a"))
        val out = reduce(s, EditorIntent.SetLayerTransformById("a", scale = 2f, offset = Offset(5f, 6f), rx = 10f, ry = 20f, rz = 30f))
        val a = out.layers.first()
        assertEquals(2f, a.scale)
        assertEquals(Offset(5f, 6f), a.offset)
        assertEquals(30f, a.rotationZ)
    }
}

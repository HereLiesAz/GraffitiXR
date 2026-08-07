package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.RotationAxis
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
    fun `ToggleAdjustPanel toggles between ADJUST and NONE`() {
        val none = state(lyr("a"))
        val opened = reduce(none, EditorIntent.ToggleAdjustPanel)
        assertEquals(EditorPanel.ADJUST, opened.activePanel)
        assertEquals(EditorPanel.NONE, reduce(opened, EditorIntent.ToggleAdjustPanel).activePanel)
    }

    @Test
    fun `SetEditorMode to the current mode is a no-op`() {
        val s = state(lyr("a")).copy(editorMode = EditorMode.MOCKUP)
        assertSame(s, reduce(s, EditorIntent.SetEditorMode(EditorMode.MOCKUP)))
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
    fun `SetLoading toggles the loading flag`() {
        assertTrue(reduce(state(lyr("a")), EditorIntent.SetLoading(true)).isLoading)
        assertFalse(reduce(state(lyr("a")).copy(isLoading = true), EditorIntent.SetLoading(false)).isLoading)
    }

    @Test
    fun `ToggleHandedness flips the handedness flag`() {
        val s = state(lyr("a")).copy(isRightHanded = true)
        assertFalse(reduce(s, EditorIntent.ToggleHandedness).isRightHanded)
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

    @Test
    fun `SetLayers replaces the list but leaves active id untouched`() {
        val s = state(lyr("a"), lyr("b"), active = "a")
        val out = reduce(s, EditorIntent.SetLayers(listOf(lyr("a"))))
        assertEquals(listOf("a"), out.layers.map { it.id })
        assertEquals("a", out.activeLayerId)
    }

    @Test
    fun `BeginGesture flags the gesture and dismisses the panel`() {
        val s = state(lyr("a")).copy(activePanel = EditorPanel.ADJUST)
        val out = reduce(s, EditorIntent.BeginGesture)
        assertTrue(out.gestureInProgress)
        assertEquals(EditorPanel.NONE, out.activePanel)
    }

    @Test
    fun `SetLayers clears an active id the new list no longer contains`() {
        val s = state(lyr("a"), lyr("b"), active = "b")
        assertNull(reduce(s, EditorIntent.SetLayers(listOf(lyr("a")))).activeLayerId)
    }

    @Test
    fun `LoadedProject sets id and layers and ClearProject resets them`() {
        val loaded = reduce(state(), EditorIntent.LoadedProject("p1", listOf(lyr("a"))))
        assertEquals("p1", loaded.projectId)
        assertEquals(listOf("a"), loaded.layers.map { it.id })
        val cleared = reduce(loaded, EditorIntent.ClearProject)
        assertNull(cleared.projectId)
        assertTrue(cleared.layers.isEmpty())
    }

    @Test
    fun `LoadedProject drops an active id belonging to the previous project`() {
        val s = state(lyr("old"), active = "old")
        val out = reduce(s, EditorIntent.LoadedProject("p2", listOf(lyr("new"))))
        assertNull(out.activeLayerId)
    }

    @Test
    fun `LoadedProject keeps the active id when the same layer is still present`() {
        val s = state(lyr("a"), lyr("b"), active = "b")
        val out = reduce(s, EditorIntent.LoadedProject("p1", listOf(lyr("a"), lyr("b"))))
        assertEquals("b", out.activeLayerId)
    }

    @Test
    fun `ClearProject drops the active id`() {
        val s = state(lyr("a"), active = "a")
        assertNull(reduce(s, EditorIntent.ClearProject).activeLayerId)
    }

    @Test
    fun `ReorderLayers keeps layers the order list omits`() {
        val s = state(lyr("a"), lyr("b"), lyr("c"), active = "b")
        val out = reduce(s, EditorIntent.ReorderLayers(listOf("c", "a")))
        assertEquals(listOf("c", "a", "b"), out.layers.map { it.id })
        assertEquals("b", out.activeLayerId)
    }

}

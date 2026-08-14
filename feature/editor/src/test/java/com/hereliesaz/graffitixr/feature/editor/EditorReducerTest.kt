package com.hereliesaz.graffitixr.feature.editor

import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorPanel
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.ModeAdjustment
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

    private fun lyr(id: String = "d", name: String = id) = Layer(id = id, name = name)
    private fun state(design: Layer? = lyr()) = EditorUiState(design = design)

    private fun reduce(s: EditorUiState, i: EditorIntent) = EditorReducer.reduce(s, i)

    @Test
    fun `SetOpacity changes the design`() {
        val out = reduce(state(), EditorIntent.SetOpacity(0.3f))
        assertEquals(0.3f, out.design!!.opacity)
    }

    @Test
    fun `property change with no design is a no-op`() {
        // Before an image is chosen there is nothing to adjust; the reducer must return the state
        // unchanged rather than materialising an empty design.
        val s = state(design = null)
        assertSame(s, reduce(s, EditorIntent.SetBrightness(0.5f)))
    }

    @Test
    fun `CycleRotationAxis advances X to Y to Z to X and shows feedback`() {
        var s = state().copy(activeRotationAxis = RotationAxis.X)
        s = reduce(s, EditorIntent.CycleRotationAxis); assertEquals(RotationAxis.Y, s.activeRotationAxis)
        assertTrue(s.showRotationAxisFeedback)
        s = reduce(s, EditorIntent.CycleRotationAxis); assertEquals(RotationAxis.Z, s.activeRotationAxis)
        s = reduce(s, EditorIntent.CycleRotationAxis); assertEquals(RotationAxis.X, s.activeRotationAxis)
    }

    @Test
    fun `FeedbackShown clears the axis feedback flag`() {
        val s = state().copy(showRotationAxisFeedback = true)
        assertFalse(reduce(s, EditorIntent.FeedbackShown).showRotationAxisFeedback)
    }

    @Test
    fun `ToggleInvert and ToggleImageLock flip the design`() {
        val s = state()
        assertTrue(reduce(s, EditorIntent.ToggleInvert).design!!.isInverted)
        assertTrue(reduce(s, EditorIntent.ToggleImageLock).design!!.isImageLocked)
    }

    @Test
    fun `ToggleAdjustPanel toggles between ADJUST and NONE`() {
        val opened = reduce(state(), EditorIntent.ToggleAdjustPanel)
        assertEquals(EditorPanel.ADJUST, opened.activePanel)
        assertEquals(EditorPanel.NONE, reduce(opened, EditorIntent.ToggleAdjustPanel).activePanel)
    }

    @Test
    fun `SetEditorMode to the current mode is a no-op`() {
        val s = state().copy(editorMode = EditorMode.MOCKUP)
        assertSame(s, reduce(s, EditorIntent.SetEditorMode(EditorMode.MOCKUP)))
    }

    @Test
    fun `SetDesign replaces the design and dismisses the panel`() {
        val s = state(lyr("old")).copy(activePanel = EditorPanel.ADJUST)
        val out = reduce(s, EditorIntent.SetDesign(lyr("new")))
        assertEquals("new", out.design!!.id)
        assertEquals(EditorPanel.NONE, out.activePanel)
    }

    @Test
    fun `SetDesign with resetActivePanel false leaves the panel open`() {
        val s = state().copy(activePanel = EditorPanel.ADJUST)
        assertEquals(
            EditorPanel.ADJUST,
            reduce(s, EditorIntent.SetDesign(lyr("new"), resetActivePanel = false)).activePanel,
        )
    }

    @Test
    fun `SetLoading toggles the loading flag`() {
        assertTrue(reduce(state(), EditorIntent.SetLoading(true)).isLoading)
        assertFalse(reduce(state().copy(isLoading = true), EditorIntent.SetLoading(false)).isLoading)
    }

    @Test
    fun `ToggleHandedness flips the handedness flag`() {
        val s = state().copy(isRightHanded = true)
        assertFalse(reduce(s, EditorIntent.ToggleHandedness).isRightHanded)
    }

    @Test
    fun `SetDesignProps copies all props onto the design`() {
        val props = com.hereliesaz.graffitixr.common.model.LayerProps(opacity = 0.4f, isVisible = false)
        val out = reduce(state(), EditorIntent.SetDesignProps(props))
        assertEquals(0.4f, out.design!!.opacity)
        assertFalse(out.design!!.isVisible)
    }

    @Test
    fun `SetDesignProps with no design is a no-op`() {
        // The spectator path: a remote op can arrive before this client has a design.
        val s = state(design = null)
        val props = com.hereliesaz.graffitixr.common.model.LayerProps(opacity = 0.4f)
        assertSame(s, reduce(s, EditorIntent.SetDesignProps(props)))
    }

    @Test
    fun `SetDesignTransform sets the whole transform at once`() {
        val out = reduce(
            state(),
            EditorIntent.SetDesignTransform(scale = 2f, offset = Offset(5f, 6f), rx = 10f, ry = 20f, rz = 30f),
        )
        val d = out.design!!
        assertEquals(2f, d.scale)
        assertEquals(Offset(5f, 6f), d.offset)
        assertEquals(10f, d.rotationX)
        assertEquals(20f, d.rotationY)
        assertEquals(30f, d.rotationZ)
    }

    @Test
    fun `RestoreDesign replaces the design outright and null clears it`() {
        val restored = reduce(state(lyr("a")), EditorIntent.RestoreDesign(lyr("b")))
        assertEquals("b", restored.design!!.id)
        assertNull(reduce(restored, EditorIntent.RestoreDesign(null)).design)
    }

    @Test
    fun `BeginGesture flags the gesture and dismisses the panel`() {
        val s = state().copy(activePanel = EditorPanel.ADJUST)
        val out = reduce(s, EditorIntent.BeginGesture)
        assertTrue(out.gestureInProgress)
        assertEquals(EditorPanel.NONE, out.activePanel)
    }

    @Test
    fun `LoadedProject sets id and design and ClearProject resets them`() {
        val loaded = reduce(state(design = null), EditorIntent.LoadedProject("p1", lyr("a")))
        assertEquals("p1", loaded.projectId)
        assertEquals("a", loaded.design!!.id)
        val cleared = reduce(loaded, EditorIntent.ClearProject)
        assertNull(cleared.projectId)
        assertNull(cleared.design)
    }

    @Test
    fun `LoadedProject replaces a design belonging to the previous project`() {
        val out = reduce(state(lyr("old")), EditorIntent.LoadedProject("p2", lyr("new")))
        assertEquals("new", out.design!!.id)
    }

    // ── Reset: a placement toggle, not a destructive action ──────────────────

    /** A design that has been moved, scaled, rotated AND toned, so the two can be told apart. */
    private fun placedAndToned() = lyr().copy(
        scale = 3f,
        offset = Offset(40f, 50f),
        rotationX = 5f,
        rotationY = 10f,
        rotationZ = 15f,
        opacity = 0.5f,
        brightness = 0.2f,
        contrast = 1.4f,
        saturation = 0.7f,
        colorBalanceR = 1.3f,
        isInverted = true,
    )

    private fun placedInMode(mode: EditorMode = EditorMode.AR) = EditorUiState(
        design = placedAndToned(),
        editorMode = mode,
        modeAdjustments = mapOf(
            mode to ModeAdjustment(
                offsetX = 7f, offsetY = 8f, scale = 2f,
                rotation = 20f, rotationX = 25f, rotationY = 30f,
                brightness = 0.3f, contrast = 1.2f, saturation = 0.8f, opacity = 0.6f,
            ),
        ),
    )

    @Test
    fun `Reset flattens the design transform to identity`() {
        val out = reduce(placedInMode(), EditorIntent.ToggleTransformReset)
        val d = out.design!!
        assertEquals(1f, d.scale)
        assertEquals(Offset.Zero, d.offset)
        assertEquals(0f, d.rotationX)
        assertEquals(0f, d.rotationY)
        assertEquals(0f, d.rotationZ)
    }

    @Test
    fun `Reset flattens the current mode transform to identity`() {
        val out = reduce(placedInMode(), EditorIntent.ToggleTransformReset)
        val m = out.modeAdjustments[EditorMode.AR]!!
        assertEquals(0f, m.offsetX)
        assertEquals(0f, m.offsetY)
        assertEquals(1f, m.scale)
        assertEquals(0f, m.rotation)
        assertEquals(0f, m.rotationX)
        assertEquals(0f, m.rotationY)
    }

    @Test
    fun `Reset leaves adjustments and effects alone`() {
        // The whole point of the button as specified: it resets placement, NOT tone or effects.
        val out = reduce(placedInMode(), EditorIntent.ToggleTransformReset)
        val d = out.design!!
        assertEquals(0.5f, d.opacity)
        assertEquals(0.2f, d.brightness)
        assertEquals(1.4f, d.contrast)
        assertEquals(0.7f, d.saturation)
        assertEquals(1.3f, d.colorBalanceR)
        assertTrue(d.isInverted)
        val m = out.modeAdjustments[EditorMode.AR]!!
        assertEquals(0.3f, m.brightness)
        assertEquals(1.2f, m.contrast)
        assertEquals(0.8f, m.saturation)
        assertEquals(0.6f, m.opacity)
    }

    @Test
    fun `pressing Reset twice returns to exactly where the user was`() {
        val before = placedInMode()
        val reset = reduce(before, EditorIntent.ToggleTransformReset)
        val restored = reduce(reset, EditorIntent.ToggleTransformReset)

        assertEquals(before.design, restored.design)
        assertEquals(before.modeAdjustments, restored.modeAdjustments)
        assertNull("the stash is spent once restored", restored.transformStash)
    }

    @Test
    fun `pressing Reset a third time resets again`() {
        var s = placedInMode()
        s = reduce(s, EditorIntent.ToggleTransformReset) // reset
        s = reduce(s, EditorIntent.ToggleTransformReset) // restore
        s = reduce(s, EditorIntent.ToggleTransformReset) // reset again
        assertEquals(1f, s.design!!.scale)
        assertEquals(Offset.Zero, s.design!!.offset)
    }

    @Test
    fun `moving the design after a Reset voids the restore`() {
        // Once the user has repositioned, "put it back" would put it somewhere they deliberately
        // left — so the next press resets rather than restoring.
        var s = reduce(placedInMode(), EditorIntent.ToggleTransformReset)
        s = reduce(s, EditorIntent.SetDesignTransform(scale = 1f, offset = Offset(12f, 0f), rx = 0f, ry = 0f, rz = 0f))
        assertNull(s.transformStash)

        s = reduce(s, EditorIntent.ToggleTransformReset)
        assertEquals(Offset.Zero, s.design!!.offset)
    }

    @Test
    fun `a mode transform gesture after a Reset voids the restore`() {
        var s = reduce(placedInMode(), EditorIntent.ToggleTransformReset)
        s = reduce(s, EditorIntent.ApplyModeTransformGesture(EditorMode.AR, Offset(5f, 5f), 1.1f, 0f))
        assertNull(s.transformStash)
    }

    @Test
    fun `a stash taken in another mode resets instead of restoring`() {
        // The mode transform is per-mode, so a stash from AR does not describe what OVERLAY shows.
        val s = reduce(placedInMode(EditorMode.AR), EditorIntent.ToggleTransformReset)
        val moved = s.copy(
            editorMode = EditorMode.OVERLAY,
            modeAdjustments = s.modeAdjustments + (EditorMode.OVERLAY to ModeAdjustment(offsetX = 99f, scale = 4f)),
        )
        val out = reduce(moved, EditorIntent.ToggleTransformReset)
        val m = out.modeAdjustments[EditorMode.OVERLAY]!!
        assertEquals(0f, m.offsetX)
        assertEquals(1f, m.scale)
        assertEquals(EditorMode.OVERLAY, out.transformStash!!.mode)
    }

    @Test
    fun `Reset with no design still flattens the mode transform`() {
        // A mode transform can be non-identity before an image is chosen; the button must not be a
        // no-op just because there is nothing to place yet.
        val s = placedInMode().copy(design = null)
        val out = reduce(s, EditorIntent.ToggleTransformReset)
        assertNull(out.design)
        assertEquals(1f, out.modeAdjustments[EditorMode.AR]!!.scale)
    }

    @Test
    fun `loading a project drops a pending Reset`() {
        val s = reduce(placedInMode(), EditorIntent.ToggleTransformReset)
        assertNull(reduce(s, EditorIntent.LoadedProject("p1", lyr("a"))).transformStash)
    }

    @Test
    fun `ClearProject drops the background bitmap too`() {
        val cleared = reduce(state(), EditorIntent.ClearProject)
        assertNull(cleared.backgroundBitmap)
        assertNull(cleared.design)
    }
}

package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.ui.geometry.Offset

/**
 * What the editor UI can ask of [EditorViewModel].
 *
 * Scoped to this app's job: getting one image into place for tracing, by lightbox or by projection.
 * Authoring belongs to the companion design app, and so does compositing several images into one —
 * this app places a single finished design. What remains is placement (transform, lock) and
 * legibility (opacity / brightness / contrast / saturation / colour balance / invert), which are the
 * controls that make an overlay usable against a real wall.
 */
interface EditorActions {
    // Legibility of the overlay against the surface being traced. In a Mode these drive the
    // whole-design ModeAdjustment; in Design they adjust the active layer.
    fun onOpacityChanged(v: Float)
    fun onBrightnessChanged(v: Float)
    fun onContrastChanged(v: Float)
    fun onSaturationChanged(v: Float)
    fun onColorBalanceRChanged(v: Float)
    fun onColorBalanceGChanged(v: Float)
    fun onColorBalanceBChanged(v: Float)
    fun onToggleInvert()

    /** Outline: render the design as a sketch — the form that is actually traceable. */
    fun onToggleOutline()

    /** Subject isolation: keep the segmented subject, drop the rest to transparent. */
    fun onToggleSubjectIsolation()

    fun onUndoClicked()
    fun onRedoClicked()

    /**
     * The Reset button that sits between undo and redo. Press once to flatten placement — the
     * design's transform and the current mode's whole-design transform — back to identity; press
     * again to put it back exactly where it was. Adjustments and effects are untouched by both.
     */
    fun onResetClicked()
    fun onMagicClicked()

    fun toggleImageLock()

    /**
     * Choose the design image. If one is already placed, this stages the pick behind a
     * confirmation ([confirmReplaceDesign] / [cancelReplaceDesign]) rather than replacing it
     * outright — see [com.hereliesaz.graffitixr.common.model.EditorUiState.pendingReplaceUri].
     */
    fun onAddLayer(uri: Uri)

    /** Confirms a pending replace staged by [onAddLayer], applying it. No-op if none is pending. */
    fun confirmReplaceDesign()

    /** Discards a pending replace staged by [onAddLayer], keeping the current design. */
    fun cancelReplaceDesign()

    // Placement is driven entirely by gestures (onTransformGesture / onModeTransformGesture /
    // onCycleRotationAxis), not per-field setters: the sliders that scale/offset/per-axis-rotation
    // setters existed for were removed, and the setters outlived them with no caller.
    fun onCycleRotationAxis()

    fun onGestureStart()
    fun onGestureEnd()
    fun onAdjustmentStart()
    fun onAdjustmentEnd()

    fun onFeedbackShown()

    fun onAdjustClicked()
    fun onDismissPanel()
}

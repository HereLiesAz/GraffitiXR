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

    /** Choose (or replace) the design image. */
    fun onAddLayer(uri: Uri)

    // Placement.
    fun onScaleChanged(s: Float)
    fun onOffsetChanged(o: Offset)
    // Rotation is driven by gestures (onTransformGesture / onCycleRotationAxis), not per-axis
    // setters: the sliders those setters existed for were removed, and the setters outlived them
    // with no caller.
    fun onCycleRotationAxis()

    fun onGestureStart()
    fun onGestureEnd()
    fun onAdjustmentStart()
    fun onAdjustmentEnd()

    fun onFeedbackShown()

    fun onAdjustClicked()
    fun onDismissPanel()
}

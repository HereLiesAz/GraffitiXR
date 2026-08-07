package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.ui.geometry.Offset

/**
 * What the editor UI can ask of [EditorViewModel].
 *
 * Scoped to this app's job: getting an image into place for tracing, by lightbox or by projection.
 * Authoring the image itself belongs to the companion design app, so the painting, stencil, outline,
 * segmentation and text-authoring entry points that used to live here are gone — see the module's
 * README note in EditorViewModel. What remains is placement (transform, lock, layer selection) and
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
    fun onMagicClicked()

    fun toggleImageLock()

    fun onLayerActivated(id: String)
    fun onLayerRenamed(id: String, name: String)
    fun onLayerReordered(newOrder: List<String>)
    fun onLayerRemoved(id: String)
    fun onToggleVisibility(layerId: String)

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

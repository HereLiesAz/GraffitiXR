package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize

interface EditorActions {
    fun onOpacityChanged(v: Float)
    fun onBrightnessChanged(v: Float)
    fun onContrastChanged(v: Float)
    fun onSaturationChanged(v: Float)
    fun onColorBalanceRChanged(v: Float)
    fun onColorBalanceGChanged(v: Float)
    fun onColorBalanceBChanged(v: Float)

    fun onUndoClicked()
    fun onRedoClicked()
    fun onMagicClicked()
    fun onRemoveBackgroundClicked()
    fun onLineDrawingClicked()
    fun onCycleBlendMode()

    fun toggleImageLock()

    fun onLayerActivated(id: String)
    fun onLayerRenamed(id: String, name: String)
    fun onLayerReordered(newOrder: List<String>)
    fun onLayerDuplicated(id: String)
    fun onLayerRemoved(id: String)

    fun onAddLayer(uri: Uri)

    fun copyLayerModifications(id: String)
    fun pasteLayerModifications(id: String)

    fun onScaleChanged(s: Float)
    fun onOffsetChanged(o: Offset)
    fun onRotationXChanged(d: Float)
    fun onRotationYChanged(d: Float)
    fun onRotationZChanged(d: Float)
    fun onCycleRotationAxis()

    fun onGestureStart()
    fun onGestureEnd()

    fun onAdjustmentStart()
    fun onAdjustmentEnd()

    fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float)

    fun onFeedbackShown()
    fun onDoubleTapHintDismissed()
    fun onOnboardingComplete(mode: Any)

    // Add canvas size to route strokes appropriately
    fun onDrawingPathFinished(path: List<Offset>, canvasSize: IntSize)

    fun onAdjustClicked()
    fun onColorClicked()
    fun onDismissPanel()

    fun onLayerWarpChanged(layerId: String, mesh: List<Float>)

    fun setBrushSize(size: Float)
    fun setActiveColor(color: Color)
    fun adjustColorLightness(delta: Float)
    /** Adjusts both HSV value (lightness) and saturation simultaneously in one atomic update. */
    fun adjustColorHSV(lightnessDelta: Float, saturationDelta: Float)
    fun onColorPickerDismissed()

    fun onFlattenAllLayers()
    fun onToggleLinkLayer(layerId: String)

    fun onAddTextLayer()
    fun onTextContentChanged(layerId: String, text: String)
    fun onTextFontChanged(layerId: String, fontName: String)
    fun onTextSizeChanged(layerId: String, sizeDp: Float)
    fun onTextColorChanged(layerId: String, colorArgb: Int)
    fun onTextKerningChanged(layerId: String, letterSpacingEm: Float)
    fun onTextStyleChanged(layerId: String, isBold: Boolean, isItalic: Boolean, hasOutline: Boolean, hasDropShadow: Boolean)
}
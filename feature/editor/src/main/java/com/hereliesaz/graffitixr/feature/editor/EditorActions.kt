package com.hereliesaz.graffitixr.feature.editor

import android.net.Uri
import androidx.compose.ui.geometry.Offset

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
    
    fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float)
    
    fun onFeedbackShown()
    fun onDoubleTapHintDismissed()
    fun onOnboardingComplete(mode: Any)
    fun onDrawingPathFinished(path: List<Offset>)

    fun onAdjustClicked()
    fun onColorClicked()
    fun onDismissPanel()
}

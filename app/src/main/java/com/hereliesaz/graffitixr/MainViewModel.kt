package com.hereliesaz.graffitixr

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.feature.ar.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.EditorActions
import com.hereliesaz.graffitixr.data.ProjectManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainViewModel(
    application: Application,
    private val projectManager: ProjectManager
) : AndroidViewModel(application), EditorActions {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent: SharedFlow<CaptureEvent> = _captureEvent.asSharedFlow()

    private val _feedbackEvent = MutableSharedFlow<FeedbackEvent>()
    val feedbackEvent: SharedFlow<FeedbackEvent> = _feedbackEvent.asSharedFlow()

    // Reference to Renderer (set by Activity)
    var arRenderer: ArRenderer? = null

    fun showTapFeedback(position: Offset, isSuccess: Boolean) {
        viewModelScope.launch {
            val feedback = if (isSuccess) TapFeedback.Success(position) else TapFeedback.Failure(position)
            _uiState.update { it.copy(tapFeedback = feedback) }
            kotlinx.coroutines.delay(500)
            _uiState.update { it.copy(tapFeedback = null) }
        }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
        arRenderer?.showPointCloud = _uiState.value.showPointCloud
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    // --- EditorActions Implementation ---

    override fun onOpacityChanged(v: Float) {
        updateActiveLayer { it.copy(opacity = v) }
    }

    override fun onBrightnessChanged(v: Float) {
        updateActiveLayer { it.copy(brightness = v) }
    }

    override fun onContrastChanged(v: Float) {
        updateActiveLayer { it.copy(contrast = v) }
    }

    override fun onSaturationChanged(v: Float) {
        updateActiveLayer { it.copy(saturation = v) }
    }

    override fun onColorBalanceRChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceR = v) }
    }

    override fun onColorBalanceGChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceG = v) }
    }

    override fun onColorBalanceBChanged(v: Float) {
        updateActiveLayer { it.copy(colorBalanceB = v) }
    }

    override fun onUndoClicked() { }
    override fun onRedoClicked() { }

    override fun onMagicClicked() {
        viewModelScope.launch {
            _feedbackEvent.emit(FeedbackEvent.VibrateDouble)
        }
    }

    override fun onRemoveBackgroundClicked() { }
    override fun onLineDrawingClicked() { }
    override fun onCycleBlendMode() { }
    override fun toggleImageLock() {
        _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    }

    override fun onLayerActivated(id: String) {
        _uiState.update { it.copy(activeLayerId = id) }
    }

    override fun onLayerRenamed(id: String, name: String) {
        _uiState.update { s -> s.copy(layers = s.layers.map { if (it.id == id) it.copy(name = name) else it }) }
    }

    override fun onLayerReordered(newOrder: List<String>) {
        val map = _uiState.value.layers.associateBy { it.id }
        _uiState.update { it.copy(layers = newOrder.mapNotNull { map[it] }) }
    }

    override fun onLayerDuplicated(id: String) {
        val l = _uiState.value.layers.find { it.id == id } ?: return
        val n = l.copy(id = UUID.randomUUID().toString(), name = "${l.name} (Copy)")
        _uiState.update { it.copy(layers = it.layers + n, activeLayerId = n.id) }
    }

    override fun onLayerRemoved(id: String) {
        _uiState.update { s ->
            val n = s.layers.filter { it.id != id }
            s.copy(layers = n, activeLayerId = if (s.activeLayerId == id) n.firstOrNull()?.id else s.activeLayerId)
        }
    }

    override fun copyLayerModifications(id: String) { }
    override fun pasteLayerModifications(id: String) { }

    override fun onScaleChanged(s: Float) {
        updateActiveLayer { it.copy(scale = it.scale * s) }
    }

    override fun onOffsetChanged(o: Offset) {
        updateActiveLayer { it.copy(offset = it.offset + o) }
    }

    override fun onRotationXChanged(d: Float) {
        updateActiveLayer { it.copy(rotationX = it.rotationX + d) }
    }

    override fun onRotationYChanged(d: Float) {
        updateActiveLayer { it.copy(rotationY = it.rotationY + d) }
    }

    override fun onRotationZChanged(d: Float) {
        updateActiveLayer { it.copy(rotationZ = it.rotationZ + d) }
    }

    override fun onCycleRotationAxis() {
        val next = when (_uiState.value.activeRotationAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        _uiState.update { it.copy(activeRotationAxis = next, showRotationAxisFeedback = true) }
    }

    override fun onGestureStart() { }
    override fun onGestureEnd() { }

    override fun setLayerTransform(scale: Float, offset: Offset, rx: Float, ry: Float, rz: Float) {
        updateActiveLayer { it.copy(scale = scale, offset = offset, rotationX = rx, rotationY = ry, rotationZ = rz) }
    }

    override fun onFeedbackShown() {
        _uiState.update { it.copy(showRotationAxisFeedback = false) }
    }

    override fun onDoubleTapHintDismissed() {
        _uiState.update { it.copy(showDoubleTapHint = false) }
    }

    override fun onOnboardingComplete(mode: Any) {
        _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    }

    override fun onDrawingPathFinished(p: List<Offset>) {
        _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(p)) }
    }

    private fun updateActiveLayer(transform: (OverlayLayer) -> OverlayLayer) {
        val activeId = _uiState.value.activeLayerId ?: return
        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == activeId) transform(it) else it })
        }
        updateRenderer()
    }

    private fun updateRenderer() {
        arRenderer?.updateLayers(_uiState.value.layers)
    }

    fun onEditorModeChanged(mode: EditorMode) {
        _uiState.update { it.copy(editorMode = mode) }
    }

    fun onToggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun setTouchLocked(locked: Boolean) {
        _uiState.update { it.copy(isTouchLocked = locked) }
    }

    fun showUnlockInstructions() {
        _uiState.update { it.copy(showUnlockInstructions = true) }
        viewModelScope.launch {
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(showUnlockInstructions = false) }
        }
    }

    fun onResume() { }
    fun onPause() { }

    fun updateCurrentLocation(location: android.location.Location) {
        val gps = GpsData(location.latitude, location.longitude, location.altitude, location.accuracy, location.time)
        _uiState.update { it.copy(gpsData = gps) }
    }

    fun loadAvailableProjects(context: android.content.Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val projectIds = projectManager.getProjectList(context)
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun autoSaveProject(context: android.content.Context, thumbnail: Bitmap? = null) { }

    fun onCalibrationPointCaptured(matrix: FloatArray) { }
    fun onFingerprintGenerated(fp: Fingerprint?) { }

    fun onCaptureShutterClicked() {
        viewModelScope.launch { _captureEvent.emit(CaptureEvent.RequestCapture) }
    }

    fun onCancelCaptureClicked() {
        _uiState.update { it.copy(isCapturingTarget = false) }
    }

    fun onTargetCreationMethodSelected(mode: TargetCreationMode) {
        _uiState.update { it.copy(targetCreationMode = mode) }
    }

    fun onGridConfigChanged(rows: Int, cols: Int) {
        _uiState.update { it.copy(gridRows = rows, gridCols = cols) }
    }

    fun onGpsDecision(enabled: Boolean) { }
    fun onPhotoSequenceFinished() { }

    fun onRefinementPathAdded(path: RefinementPath) {
        _uiState.update { it.copy(refinementPaths = it.refinementPaths + path) }
    }

    fun onRefinementModeChanged(isEraser: Boolean) {
        _uiState.update { it.copy(isRefinementEraser = isEraser) }
    }

    fun onConfirmTargetCreation() {
        _uiState.update { it.copy(isArTargetCreated = true, isCapturingTarget = false) }
    }

    fun unwarpImage(points: List<Offset>) { }
    fun onRetakeCapture() { }

    fun addLayer(uri: Uri) {
        val layer = OverlayLayer(
            id = UUID.randomUUID().toString(),
            uri = uri,
            name = "New Layer"
        )
        _uiState.update { it.copy(layers = it.layers + layer, activeLayerId = layer.id) }
        updateRenderer()
    }
}

sealed class FeedbackEvent {
    data class Toast(val message: String) : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
    object VibrateSingle : FeedbackEvent()
}
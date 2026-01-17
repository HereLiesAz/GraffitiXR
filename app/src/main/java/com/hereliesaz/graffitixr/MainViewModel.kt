package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.*
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import com.hereliesaz.graffitixr.utils.ImageUtils
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.applyCurves
import com.hereliesaz.graffitixr.utils.saveBitmapToGallery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(
    private val projectManager: ProjectManager = ProjectManager()
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _feedbackEvent = Channel<FeedbackEvent>(Channel.BUFFERED)
    val feedbackEvent = _feedbackEvent.receiveAsFlow()

    private val _captureEvent = Channel<CaptureEvent>(Channel.BUFFERED)
    val captureEvent = _captureEvent.receiveAsFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    private val _artworkBounds = MutableStateFlow<android.graphics.RectF?>(null)
    val artworkBounds = _artworkBounds.asStateFlow()

    var arRenderer: ArRenderer? = null

    // History Stacks (Global Layer States)
    private val undoStack = ArrayDeque<List<OverlayLayer>>()
    private val redoStack = ArrayDeque<List<OverlayLayer>>()
    private val MAX_HISTORY = 50

    private var layerModsClipboard: OverlayLayer? = null

    // ... (Existing helpers) ...
    private fun snapshotState() {
        if (undoStack.size >= MAX_HISTORY) undoStack.removeFirst()
        undoStack.addLast(_uiState.value.layers.toList())
        redoStack.clear()
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    private fun updateActiveLayer(saveHistory: Boolean = false, block: (OverlayLayer) -> OverlayLayer) {
        val activeId = _uiState.value.activeLayerId ?: return
        if (saveHistory) snapshotState()
        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == activeId) block(it) else it })
        }
    }

    // ... (Existing adjustment methods) ...
    fun onOpacityChanged(v: Float) = updateActiveLayer { it.copy(opacity = v) }
    fun onBrightnessChanged(v: Float) = updateActiveLayer { it.copy(brightness = v) }
    fun onContrastChanged(v: Float) = updateActiveLayer { it.copy(contrast = v) }
    fun onSaturationChanged(v: Float) = updateActiveLayer { it.copy(saturation = v) }
    fun onColorBalanceRChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceR = v) }
    fun onColorBalanceGChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceG = v) }
    fun onColorBalanceBChanged(v: Float) = updateActiveLayer { it.copy(colorBalanceB = v) }

    fun onCycleBlendMode() = updateActiveLayer(saveHistory = true) { layer ->
        layer.copy(blendMode = ImageUtils.getNextBlendMode(layer.blendMode))
    }

    // ... (Existing background/outline methods) ...
    fun onRemoveBackgroundClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = arRenderer?.context ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _uiState.value.layers.find { it.id == activeId }?.let { layer ->
                ImageUtils.loadBitmapFromUri(context, layer.uri)?.let { original ->
                    snapshotState()
                    val processed = BackgroundRemover.removeBackground(original)
                    processed?.let { bmp ->
                        val newUri = ImageUtils.saveBitmapToCache(context, bmp)
                        updateActiveLayer { it.copy(uri = newUri) }
                    }
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun onLineDrawingClicked() {
        val activeId = _uiState.value.activeLayerId ?: return
        val context = arRenderer?.context ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            _uiState.value.layers.find { it.id == activeId }?.let { layer ->
                ImageUtils.loadBitmapFromUri(context, layer.uri)?.let { original ->
                    snapshotState()
                    val processed = ImageUtils.generateOutline(original)
                    val newUri = ImageUtils.saveBitmapToCache(context, processed)
                    updateActiveLayer { it.copy(uri = newUri) }
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    // ... (Layer Management) ...
    fun onLayerActivated(id: String) = _uiState.update { it.copy(activeLayerId = id) }
    fun onLayerRenamed(id: String, name: String) {
        _uiState.update { state ->
            state.copy(layers = state.layers.map { if (it.id == id) it.copy(name = name) else it })
        }
    }
    fun onLayerReordered(newOrder: List<String>) {
        snapshotState()
        val map = _uiState.value.layers.associateBy { it.id }
        _uiState.update { it.copy(layers = newOrder.mapNotNull { map[it] }) }
    }
    fun copyLayerModifications(id: String) {
        layerModsClipboard = _uiState.value.layers.find { it.id == id }
        viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateSingle) }
    }
    fun pasteLayerModifications(id: String) {
        layerModsClipboard?.let { template ->
            updateActiveLayer(saveHistory = true) { target ->
                target.copy(
                    opacity = template.opacity, brightness = template.brightness,
                    contrast = template.contrast, saturation = template.saturation,
                    colorBalanceR = template.colorBalanceR, colorBalanceG = template.colorBalanceG,
                    colorBalanceB = template.colorBalanceB, scale = template.scale,
                    rotationX = template.rotationX, rotationY = template.rotationY,
                    rotationZ = template.rotationZ, blendMode = template.blendMode
                )
            }
            viewModelScope.launch { _feedbackEvent.send(FeedbackEvent.VibrateDouble) }
        }
    }

    fun onCancelCaptureClicked() { arRenderer?.showGuide = false; arRenderer?.resetAnchor(); updateState(uiState.value.copy(isCapturingTarget = false, targetCreationState = TargetCreationState.IDLE, captureStep = CaptureStep.ADVICE, capturedTargetImages = emptyList(), targetCreationMode = TargetCreationMode.CAPTURE, isGridGuideVisible = false, arState = ArState.SEARCHING)) }

    fun onRefinementPathAdded(path: com.hereliesaz.graffitixr.data.RefinementPath) { updateState(uiState.value.copy(refinementPaths = uiState.value.refinementPaths + path), isUndoable = true); updateDetectedKeypoints() }

    private fun updateDetectedKeypoints() {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return@launch
            val keypoints = com.hereliesaz.graffitixr.utils.detectFeaturesWithMask(bitmap)
            val offsets = keypoints.map { Offset(it.pt.x.toFloat(), it.pt.y.toFloat()) }
            withContext(Dispatchers.Main) { updateState(uiState.value.copy(detectedKeypoints = offsets), isUndoable = false) }
        }
    }

    fun onRefineTargetToggled() { if (uiState.value.capturedTargetImages.isNotEmpty()) updateState(uiState.value.copy(isCapturingTarget = true, captureStep = CaptureStep.REVIEW), isUndoable = false) else Toast.makeText(getApplication(), "No target captured to refine", Toast.LENGTH_SHORT).show() }
    fun onRefinementModeChanged(isEraser: Boolean) { updateState(uiState.value.copy(isRefinementEraser = isEraser), isUndoable = false) }
    fun onImagePickerShown() { updateState(uiState.value.copy(showImagePicker = false), isUndoable = false) }

    fun onConfirmTargetCreation() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val originalBitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return@launch
                val fingerprint = com.hereliesaz.graffitixr.utils.generateFingerprint(originalBitmap)
                if (fingerprint.keypoints.isEmpty()) { withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "No features found. Try a more detailed surface.", Toast.LENGTH_LONG).show() }; return@launch }
                arRenderer?.setAugmentedImageDatabase(uiState.value.capturedTargetImages)
                arRenderer?.setFingerprint(fingerprint)
                withContext(Dispatchers.Main) { updateState(uiState.value.copy(isCapturingTarget = false, targetCreationState = TargetCreationState.SUCCESS, isArTargetCreated = true, arState = ArState.SEARCHING, fingerprintJson = Json.encodeToString(Fingerprint.serializer(), fingerprint), refinementPaths = emptyList(), targetMaskUri = null, showImagePicker = uiState.value.overlayImageUri == null)) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { handleTargetCreationFailure(e) } }
        }
    }

    fun onCancelCaptureClicked() {
        _uiState.update { it.copy(isCapturingTarget = false, captureStep = CaptureStep.PREVIEW) }
    }

    fun onUndoClicked() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.layers.toList())
        _uiState.update { it.copy(layers = undoStack.removeLast()) }
        updateHistoryFlags()
    }

    private fun handleTargetCreationFailure(e: Exception) { updateState(uiState.value.copy(isCapturingTarget = false, targetCreationState = TargetCreationState.IDLE, captureStep = CaptureStep.ADVICE, capturedTargetImages = emptyList(), arState = ArState.SEARCHING, isLoading = false, isGridGuideVisible = false)); arRenderer?.showGuide = false; Toast.makeText(getApplication(), "Target creation failed: ${e.message}", Toast.LENGTH_LONG).show() }

    fun onCycleRotationAxis() { val nextAxis = when (uiState.value.activeRotationAxis) { RotationAxis.X -> RotationAxis.Y; RotationAxis.Y -> RotationAxis.Z; RotationAxis.Z -> RotationAxis.X }; updateState(uiState.value.copy(activeRotationAxis = nextAxis, showRotationAxisFeedback = true)); Toast.makeText(getApplication(), "Rotation Axis: ${nextAxis.name}", Toast.LENGTH_SHORT).show() }

    fun onArObjectScaleChanged(scaleFactor: Float) { val newScale = (uiState.value.arObjectScale * scaleFactor).coerceIn(0.01f, 10.0f); updateState(uiState.value.copy(arObjectScale = newScale, scale = newScale), isUndoable = false); updateActiveLayer { it.copy(scale = newScale) } }

    fun onRotationXChanged(delta: Float) { val newRot = uiState.value.rotationX + delta; updateState(uiState.value.copy(rotationX = newRot), isUndoable = false); updateActiveLayer { it.copy(rotationX = newRot) } }
    fun onRotationYChanged(delta: Float) { val newRot = uiState.value.rotationY + delta; updateState(uiState.value.copy(rotationY = newRot), isUndoable = false); updateActiveLayer { it.copy(rotationY = newRot) } }
    fun onRotationZChanged(delta: Float) { val newRot = uiState.value.rotationZ + delta; updateState(uiState.value.copy(rotationZ = newRot), isUndoable = false); updateActiveLayer { it.copy(rotationZ = newRot) } }

    fun onFeedbackShown() { viewModelScope.launch { delay(1000); updateState(uiState.value.copy(showRotationAxisFeedback = false)) } }
    fun setArPlanesDetected(detected: Boolean) { if (uiState.value.isArPlanesDetected != detected) updateState(uiState.value.copy(isArPlanesDetected = detected), isUndoable = false) }

    fun onArImagePlaced() { updateState(uiState.value.copy(arState = ArState.LOCKED), isUndoable = false); if (uiState.value.isCapturingTarget && uiState.value.captureStep == CaptureStep.ADVICE) updateState(uiState.value.copy(captureStep = CaptureStep.CHOOSE_METHOD), isUndoable = false) else if (uiState.value.overlayImageUri == null) updateState(uiState.value.copy(showImagePicker = true), isUndoable = false) }

    fun onProgressUpdate(progress: Float, bitmap: Bitmap? = null) { if (abs(progress - uiState.value.progressPercentage) > 5.0f && bitmap != null) { viewModelScope.launch(Dispatchers.IO) { saveBitmapToCache(bitmap, "evolution")?.let { uri -> withContext(Dispatchers.Main) { updateState(uiState.value.copy(evolutionCaptureUris = uiState.value.evolutionCaptureUris + uri), isUndoable = false) } } } } ; updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false) }

    private var unlockInstructionsJob: kotlinx.coroutines.Job? = null
    fun showUnlockInstructions() { unlockInstructionsJob?.cancel(); unlockInstructionsJob = viewModelScope.launch { updateState(uiState.value.copy(showUnlockInstructions = true), isUndoable = false); delay(3000); updateState(uiState.value.copy(showUnlockInstructions = false), isUndoable = false) } }
    fun setTouchLocked(locked: Boolean) { if (locked) { showUnlockInstructions(); updateState(uiState.value.copy(isTouchLocked = true), isUndoable = false) } else { unlockInstructionsJob?.cancel(); updateState(uiState.value.copy(isTouchLocked = false, showUnlockInstructions = false), isUndoable = false) } }

    fun showTapFeedback(position: Offset, isSuccess: Boolean) { viewModelScope.launch { _tapFeedback.value = if (isSuccess) TapFeedback.Success(position) else TapFeedback.Failure(position); delay(500); _tapFeedback.value = null } }

    fun onRemoveBackgroundClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri
            if (uri == null) {
                setLoading(false)
                return@launch
            }

            try {
                val bitmap = BitmapUtils.getBitmapFromUri(getApplication(), uri)
                if (bitmap == null) {
                    setLoading(false)
                    return@launch
                }

                val result = BackgroundRemover.removeBackground(bitmap)
                val resultBitmap = result.getOrNull()

                if (resultBitmap != null) {
                    val newUri = saveBitmapToCache(resultBitmap, "background_removed")
                    if (newUri != null) {
                        updateState(uiState.value.copy(overlayImageUri = newUri, backgroundRemovedImageUri = newUri, isLoading = false))
                        updateActiveLayer { it.copy(uri = newUri, backgroundRemovedUri = newUri) }
                    } else {
                        setLoading(false)
                        Toast.makeText(getApplication(), "Failed to save image", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    setLoading(false)
                    Toast.makeText(getApplication(), "Background removal failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                setLoading(false)
                Toast.makeText(getApplication(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onBrightnessChanged(brightness: Float) { val safe = brightness.coerceIn(-1f, 1f); updateState(uiState.value.copy(brightness = safe), isUndoable = false); updateActiveLayer { it.copy(brightness = safe) } }

    fun onLineDrawingClicked() {
        viewModelScope.launch {
            setLoading(true)
            val isDrawing = uiState.value.isLineDrawing
            val baseUri = uiState.value.backgroundRemovedImageUri ?: uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri

            if (baseUri == null) {
                setLoading(false)
                return@launch
            }

            if (isDrawing) {
                // Revert to original image
                updateState(uiState.value.copy(overlayImageUri = baseUri, isLineDrawing = false, isLoading = false))
                updateActiveLayer { l -> l.copy(uri = baseUri) }
            } else {
                // Create line drawing
                try {
                    val bitmap = BitmapUtils.getBitmapFromUri(getApplication(), baseUri)?.copy(Bitmap.Config.ARGB_8888, true)
                    if (bitmap == null) {
                        setLoading(false)
                        return@launch
                    }
                    val lineDrawingBitmap = com.hereliesaz.graffitixr.utils.ImageProcessingUtils.createOutline(bitmap)
                    val newUri = saveBitmapToCache(lineDrawingBitmap, "line_drawing")
                    if (newUri != null) {
                        updateState(uiState.value.copy(overlayImageUri = newUri, isLineDrawing = true, isLoading = false))
                        updateActiveLayer { it.copy(uri = newUri) }
                    } else {
                        setLoading(false)
                        Toast.makeText(getApplication(), "Failed to save outline", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    setLoading(false)
                    Toast.makeText(getApplication(), "Outline creation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoading(isLoading: Boolean) { updateState(uiState.value.copy(isLoading = isLoading), isUndoable = false) }
    fun onBackgroundImageSelected(uri: Uri) { updateState(uiState.value.copy(backgroundImageUri = uri)) }
    fun onOverlayImageSelected(uri: Uri) { val newLayer = OverlayLayer(id = java.util.UUID.randomUUID().toString(), name = "Layer ${uiState.value.layers.size + 1}", uri = uri, originalUri = uri); updateState(uiState.value.copy(overlayImageUri = uri, originalOverlayImageUri = uri, backgroundRemovedImageUri = null, isLineDrawing = false, showDoubleTapHint = !onboardingManager.hasSeenDoubleTapHint(), activeRotationAxis = RotationAxis.Y, layers = uiState.value.layers + newLayer, activeLayerId = newLayer.id, rotationY = 0f, rotationX = 0f, rotationZ = 0f, scale = 1f, offset = Offset.Zero)) }

    fun onLayerActivated(layerId: String) {
        uiState.value.layers.find { it.id == layerId }?.let {
            updateState(uiState.value.copy(
                activeLayerId = layerId,
                overlayImageUri = it.uri,
                originalOverlayImageUri = it.originalUri,
                backgroundRemovedImageUri = it.backgroundRemovedUri,
                scale = it.scale,
                rotationX = it.rotationX,
                rotationY = it.rotationY,
                rotationZ = it.rotationZ,
                offset = it.offset,
                opacity = it.opacity,
                brightness = it.brightness,
                contrast = it.contrast,
                saturation = it.saturation,
                colorBalanceR = it.colorBalanceR,
                colorBalanceG = it.colorBalanceG,
                colorBalanceB = it.colorBalanceB,
                curvesPoints = it.curvesPoints,
                blendMode = it.blendMode
            ), isUndoable = false)
        }
    }

    fun onLayerReordered(newOrderIds: List<String>) { val reordered = newOrderIds.mapNotNull { id -> uiState.value.layers.find { it.id == id } }; if (reordered.size == uiState.value.layers.size) updateState(uiState.value.copy(layers = reordered)) }
    fun onLayerRenamed(layerId: String, newName: String) { updateState(uiState.value.copy(layers = uiState.value.layers.map { if (it.id == layerId) it.copy(name = newName) else it })) }
    fun onLayerDuplicated(layerId: String) { uiState.value.layers.find { it.id == layerId }?.let { val newLayer = it.copy(id = java.util.UUID.randomUUID().toString(), name = "${it.name} Copy"); updateState(uiState.value.copy(layers = uiState.value.layers + newLayer, activeLayerId = newLayer.id)) } }
    fun onLayerRemoved(layerId: String) { val updated = uiState.value.layers.filter { it.id != layerId }; updateState(uiState.value.copy(layers = updated, activeLayerId = updated.lastOrNull()?.id, overlayImageUri = if (updated.isEmpty()) null else uiState.value.overlayImageUri)) }

    fun onOpacityChanged(opacity: Float) { val safe = opacity.coerceIn(0f, 1f); updateState(uiState.value.copy(opacity = safe), isUndoable = false); updateActiveLayer { it.copy(opacity = safe) } }
    fun onContrastChanged(contrast: Float) { val safe = contrast.coerceIn(0f, 2f); updateState(uiState.value.copy(contrast = safe), isUndoable = false); updateActiveLayer { it.copy(contrast = safe) } } // Adjustment
    fun onSaturationChanged(saturation: Float) { val safe = saturation.coerceIn(0f, 2f); updateState(uiState.value.copy(saturation = safe), isUndoable = false); updateActiveLayer { it.copy(saturation = safe) } } // Adjustment
    fun onScaleChanged(factor: Float) { val newScale = (uiState.value.scale * factor).coerceIn(0.01f, 10.0f); updateState(uiState.value.copy(scale = newScale), isUndoable = false); updateActiveLayer { it.copy(scale = newScale) } }
    fun onOffsetChanged(offset: Offset) { val newOffset = uiState.value.offset + offset; updateState(uiState.value.copy(offset = newOffset), isUndoable = false); updateActiveLayer { it.copy(offset = newOffset) } }
    fun onEditorModeChanged(mode: EditorMode) { updateState(uiState.value.copy(editorMode = mode, showOnboardingDialogForMode = if (!uiState.value.completedOnboardingModes.contains(mode)) mode else null, activeRotationAxis = RotationAxis.Z)) }
    fun onOnboardingComplete(mode: EditorMode) { onboardingManager.completeMode(mode); updateState(uiState.value.copy(completedOnboardingModes = onboardingManager.getCompletedModes(), showOnboardingDialogForMode = null)) }
    fun onDoubleTapHintDismissed() { onboardingManager.setDoubleTapHintSeen(); updateState(uiState.value.copy(showDoubleTapHint = false)) }

    fun onCurvesPointsChangeFinished() { viewModelScope.launch { uiState.value.overlayImageUri?.let { applyCurvesToOverlay(it, uiState.value.curvesPoints) } } }
    private fun applyCurvesToOverlay(uri: Uri, points: List<Offset>) { viewModelScope.launch { setLoading(true); try { val resultBitmap = applyCurves(BitmapUtils.getBitmapFromUri(getApplication(), uri) ?: return@launch, points); saveBitmapToCache(resultBitmap, "curves")?.let { updateState(uiState.value.copy(processedImageUri = it, isLoading = false)) } } catch (e: Exception) { setLoading(false) } } }
    fun onCurvesPointsChanged(points: List<Offset>) { updateState(uiState.value.copy(curvesPoints = points), isUndoable = false) }

    fun onSaveClicked() { viewModelScope.launch { updateState(uiState.value.copy(hideUiForCapture = true), isUndoable = false); delay(200); _captureEvent.emit(CaptureEvent.RequestCapture) } }
    fun saveCapturedBitmap(bitmap: Bitmap) { updateState(uiState.value.copy(hideUiForCapture = false), isUndoable = false); viewModelScope.launch { setLoading(true); val success = withContext(Dispatchers.IO) { saveBitmapToGallery(getApplication(), bitmap) }; Toast.makeText(getApplication(), if (success) "Image saved" else "Save failed", Toast.LENGTH_SHORT).show(); setLoading(false) } }

    // Updated to apply changes to ACTIVE LAYER
    fun onColorBalanceRChanged(value: Float) { val safe = value.coerceIn(0f, 2f); updateState(uiState.value.copy(colorBalanceR = safe), isUndoable = false); updateActiveLayer { it.copy(colorBalanceR = safe) } } // Adjustment
    fun onColorBalanceGChanged(value: Float) { val safe = value.coerceIn(0f, 2f); updateState(uiState.value.copy(colorBalanceG = safe), isUndoable = false); updateActiveLayer { it.copy(colorBalanceG = safe) } } // Adjustment
    fun onColorBalanceBChanged(value: Float) { val safe = value.coerceIn(0f, 2f); updateState(uiState.value.copy(colorBalanceB = safe), isUndoable = false); updateActiveLayer { it.copy(colorBalanceB = safe) } } // Adjustment

    fun onCycleBlendMode() { val next = when (uiState.value.blendMode) { BlendMode.SrcOver -> BlendMode.Multiply; BlendMode.Multiply -> BlendMode.Screen; BlendMode.Screen -> BlendMode.Overlay; BlendMode.Overlay -> BlendMode.Darken; BlendMode.Darken -> BlendMode.Lighten; else -> BlendMode.SrcOver }; updateState(uiState.value.copy(blendMode = next)); updateActiveLayer { it.copy(blendMode = next) }; Toast.makeText(getApplication(), "Blend: $next", Toast.LENGTH_SHORT).show() }

    // Updated to apply alignment to ACTIVE LAYER
    fun onMagicClicked() { if (uiState.value.rotationX != 0f || uiState.value.rotationY != 0f || uiState.value.rotationZ != 0f) { updateState(uiState.value.copy(rotationX = 0f, rotationY = 0f, rotationZ = 0f)); updateActiveLayer { it.copy(rotationX = 0f, rotationY = 0f, rotationZ = 0f) }; Toast.makeText(getApplication(), "Aligned Flat", Toast.LENGTH_SHORT).show() } }

    fun onToggleFlashlight() { val next = !uiState.value.isFlashlightOn; updateState(uiState.value.copy(isFlashlightOn = next), isUndoable = false); arRenderer?.setFlashlight(next) }
    fun toggleImageLock() { val next = !uiState.value.isImageLocked; updateState(uiState.value.copy(isImageLocked = next), isUndoable = false); Toast.makeText(getApplication(), if (next) "Locked" else "Unlocked", Toast.LENGTH_SHORT).show() }

    fun saveProject(name: String) { viewModelScope.launch(Dispatchers.IO) { performSave(name, true) } }
    private suspend fun performSave(name: String, toast: Boolean) { try { val project = ProjectData(backgroundImageUri = uiState.value.backgroundImageUri, overlayImageUri = uiState.value.overlayImageUri, originalOverlayImageUri = uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri, targetImageUris = if (uiState.value.capturedTargetImages.isNotEmpty()) uiState.value.capturedTargetImages.mapNotNull { saveBitmapToCache(it) } else uiState.value.capturedTargetUris, refinementPaths = uiState.value.refinementPaths, opacity = uiState.value.opacity, brightness = uiState.value.brightness, contrast = uiState.value.contrast, saturation = uiState.value.saturation, colorBalanceR = uiState.value.colorBalanceR, colorBalanceG = uiState.value.colorBalanceG, colorBalanceB = uiState.value.colorBalanceB, scale = uiState.value.scale, rotationZ = uiState.value.rotationZ, rotationX = uiState.value.rotationX, rotationY = uiState.value.rotationY, offset = uiState.value.offset, blendMode = uiState.value.blendMode, fingerprint = uiState.value.fingerprintJson?.let { Json.decodeFromString<Fingerprint>(it) }, drawingPaths = uiState.value.drawingPaths, progressPercentage = uiState.value.progressPercentage, evolutionImageUris = uiState.value.evolutionCaptureUris, gpsData = getGpsData(), sensorData = getSensorData(), calibrationSnapshots = uiState.value.calibrationSnapshots); projectManager.saveProject(name, project); if (toast) withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Project '$name' saved", Toast.LENGTH_SHORT).show() } } catch (e: Exception) { if (toast) withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Save failed", Toast.LENGTH_SHORT).show() } } }

    private suspend fun getSensorData(): com.hereliesaz.graffitixr.data.SensorData? { return suspendCancellableCoroutine { cont -> val sm = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return@suspendCancellableCoroutine cont.resume(null); val acc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER); val mag = sm.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD); if (acc == null || mag == null) return@suspendCancellableCoroutine cont.resume(null); var g: FloatArray? = null; var m: FloatArray? = null; val l = object : android.hardware.SensorEventListener { override fun onSensorChanged(e: android.hardware.SensorEvent?) { if (e?.sensor?.type == android.hardware.Sensor.TYPE_ACCELEROMETER) g = e.values.clone() ; if (e?.sensor?.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD) m = e.values.clone() ; if (g != null && m != null) { val r = FloatArray(9); val o = FloatArray(3); if (android.hardware.SensorManager.getRotationMatrix(r, null, g, m)) { android.hardware.SensorManager.getOrientation(r, o); sm.unregisterListener(this); if (cont.isActive) cont.resume(com.hereliesaz.graffitixr.data.SensorData(o[0], o[1], o[2])) } } }; override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {} }; sm.registerListener(l, acc, android.hardware.SensorManager.SENSOR_DELAY_NORMAL); sm.registerListener(l, mag, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) } }
    private fun getGpsData(): com.hereliesaz.graffitixr.data.GpsData? { try { if (androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null; val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager; val loc = lm?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER); return loc?.let { com.hereliesaz.graffitixr.data.GpsData(it.latitude, it.longitude, it.altitude, it.accuracy, it.time) } } catch (e: Exception) { return null } }
    private fun saveBitmapToCache(b: Bitmap, p: String = "target"): Uri? { try { val context = getApplication<Application>().applicationContext; val path = File(context.cacheDir, "project_assets").apply { mkdirs() }; val file = File(path, "${p}_${System.currentTimeMillis()}.png"); FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; return FileProvider.getUriForFile(context, "${context.packageName}.provider", file) } catch (e: Exception) { return null } }

    fun loadProject(name: String) { viewModelScope.launch(Dispatchers.IO) { projectManager.loadProject(name)?.let { updateState(uiState.value.copy(backgroundImageUri = it.backgroundImageUri, overlayImageUri = it.overlayImageUri, originalOverlayImageUri = it.originalOverlayImageUri ?: it.overlayImageUri, opacity = it.opacity, brightness = it.brightness, contrast = it.contrast, saturation = it.saturation, colorBalanceR = it.colorBalanceR, colorBalanceG = it.colorBalanceG, colorBalanceB = it.colorBalanceB, scale = it.scale, rotationZ = it.rotationZ, rotationX = it.rotationX, rotationY = it.rotationY, offset = it.offset, blendMode = it.blendMode, fingerprintJson = it.fingerprint?.let { Json.encodeToString(Fingerprint.serializer(), it) }, drawingPaths = it.drawingPaths, isLineDrawing = false, capturedTargetUris = it.targetImageUris ?: emptyList())); withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Project '$name' loaded", Toast.LENGTH_SHORT).show() } } } }
    fun getProjectList(): List<String> = projectManager.getProjectList()
    fun deleteProject(name: String) { viewModelScope.launch(Dispatchers.IO) { projectManager.deleteProject(name); withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Project '$name' deleted", Toast.LENGTH_SHORT).show() } } }
    fun onNewProject() { updateState(UiState(), isUndoable = false) }

    fun onUndoClicked() { if (undoStack.isNotEmpty()) { val last = undoStack.removeAt(undoStack.lastIndex); redoStack.add(uiState.value); updateState(last, isUndoable = false) } }
    fun onRedoClicked() { if (redoStack.isNotEmpty()) { val next = redoStack.removeAt(redoStack.lastIndex); undoStack.add(uiState.value); updateState(next, isUndoable = false) } }
    private fun updateState(newState: UiState, isUndoable: Boolean = true) { val current = savedStateHandle.get<UiState>("uiState") ?: UiState(); if (isUndoable) { undoStack.add(current); if (undoStack.size > 50) undoStack.removeAt(0); redoStack.clear() }; savedStateHandle["uiState"] = newState.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    private fun updateActiveLayer(update: (OverlayLayer) -> OverlayLayer) { uiState.value.activeLayerId?.let { id -> updateState(uiState.value.copy(layers = uiState.value.layers.map { if (it.id == id) update(it) else it }), isUndoable = false) } }

    fun unwarpImage(points: List<Offset>) {
        val bitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!ensureOpenCVLoaded()) return@launch
            val mat = Mat(); val warped = Mat(); val srcMat = Mat(4, 2, CvType.CV_32F); val dstMat = Mat(4, 2, CvType.CV_32F)
            var perspectiveTransform: Mat? = null
            try {
                Utils.bitmapToMat(bitmap, mat); val w = mat.cols().toDouble(); val h = mat.rows().toDouble()
                val sortedByY = points.sortedBy { it.y } ; val topRow = sortedByY.take(2).sortedBy { it.x }; val bottomRow = sortedByY.takeLast(2).sortedBy { it.x }
                val tl = topRow[0]; val tr = topRow[1]; val bl = bottomRow[0]; val br = bottomRow[1]
                val maxWidth = max(sqrt((br.x * w - bl.x * w).pow(2) + (br.y * h - bl.y * h).pow(2)), sqrt((tr.x * w - tl.x * w).pow(2) + (tr.y * h - tl.y * h).pow(2)))
                val maxHeight = max(sqrt((tr.x * w - br.x * w).pow(2) + (tr.y * h - br.y * h).pow(2)), sqrt((tl.x * w - bl.x * w).pow(2) + (tl.y * h - bl.y * h).pow(2)))
                srcMat.put(0, 0, floatArrayOf(tl.x.toFloat() * w.toFloat(), tl.y.toFloat() * h.toFloat(), tr.x.toFloat() * w.toFloat(), tr.y.toFloat() * h.toFloat(), br.x.toFloat() * w.toFloat(), br.y.toFloat() * h.toFloat(), bl.x.toFloat() * w.toFloat(), bl.y.toFloat() * h.toFloat()))
                dstMat.put(0, 0, floatArrayOf(0f, 0f, maxWidth.toFloat() - 1, 0f, maxWidth.toFloat() - 1, maxHeight.toFloat() - 1, 0f, maxHeight.toFloat() - 1))
                perspectiveTransform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
                Imgproc.warpPerspective(mat, warped, perspectiveTransform, Size(maxWidth, maxHeight))
                val resultBitmap = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, resultBitmap)
                withContext(Dispatchers.Main) { updateState(uiState.value.copy(capturedTargetImages = listOf(resultBitmap), captureStep = CaptureStep.REVIEW), isUndoable = false) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Unwarp failed", Toast.LENGTH_SHORT).show() } }
            finally { if (mat.nativeObj != 0L) mat.release() ; if (warped.nativeObj != 0L) warped.release() ; if (srcMat.nativeObj != 0L) srcMat.release() ; if (dstMat.nativeObj != 0L) dstMat.release() ; perspectiveTransform?.let { if (it.nativeObj != 0L) it.release() } }
        }
        state.copy(activeRotationAxis = next, showRotationAxisFeedback = true)
    }

    fun onCreateTargetClicked() = _uiState.update { it.copy(isCapturingTarget = true, captureStep = CaptureStep.PREVIEW) }
    fun onCaptureShutterClicked() = viewModelScope.launch { _captureEvent.send(CaptureEvent.RequestCapture) }
    fun saveCapturedBitmap(b: Bitmap) {
        _uiState.update { it.copy(capturedTargetImages = listOf(b), captureStep = CaptureStep.REVIEW) }
        arRenderer?.triggerCapture()
    }

    fun setTouchLocked(l: Boolean) = _uiState.update { it.copy(isTouchLocked = l) }
    fun toggleImageLock() = _uiState.update { it.copy(isImageLocked = !it.isImageLocked) }
    fun onToggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
        arRenderer?.setFlashlight(_uiState.value.isFlashlightOn)
    }
    fun toggleMappingMode() = _uiState.update { it.copy(isMappingMode = !it.isMappingMode) }

    // Stubs
    fun getProjectList() = emptyList<String>()
    fun loadProject(n: String) {}
    fun deleteProject(n: String) {}
    fun onNewProject() = _uiState.update { UiState() }
    fun onSaveClicked() {}
    fun exportProjectToUri(u: Uri) {}
    fun onOnboardingComplete(m: EditorMode) = _uiState.update { it.copy(showOnboardingDialogForMode = null) }
    fun onFeedbackShown() = _uiState.update { it.copy(showRotationAxisFeedback = false) }
    fun onMarkProgressToggled() = _uiState.update { it.copy(isMarkingProgress = !it.isMarkingProgress) }
    fun onDrawingPathFinished(p: List<Offset>) = _uiState.update { it.copy(drawingPaths = it.drawingPaths + listOf(p)) }
    fun updateArtworkBounds(b: android.graphics.RectF) = _uiState.update { it.copy() }
    fun setArPlanesDetected(d: Boolean) = _uiState.update { it.copy(isArPlanesDetected = d) }
    fun onArImagePlaced() = _uiState.update { it.copy(arState = ArState.PLACED) }
    fun onFrameCaptured(b: Bitmap) {}
    fun onProgressUpdate(p: Float, b: Bitmap?) {}
    fun onTrackingFailure(m: String?) {}
    fun updateMappingScore(s: Float) = _uiState.update { it.copy(mappingQualityScore = s) }
    fun finalizeMap() {}
    fun showUnlockInstructions() = _uiState.update { it.copy(showUnlockInstructions = true) }
    fun onOverlayImageSelected(u: Uri) {
        val newLayer = OverlayLayer(uri = u, name = "Layer ${_uiState.value.layers.size + 1}")
        _uiState.update { it.copy(layers = it.layers + newLayer, activeLayerId = newLayer.id) }
    }
    fun onBackgroundImageSelected(u: Uri) = _uiState.update { it.copy(backgroundImageUri = u) }
    fun onImagePickerShown() {}
    fun onDoubleTapHintDismissed() {}
    fun onGestureStart() {}
    fun onGestureEnd() { snapshotState() }
    fun onRefineTargetToggled() {}
    fun onTargetCreationMethodSelected(m: TargetCreationMode) {}
    fun onGridConfigChanged(r: Int, c: Int) {}
    fun onGpsDecision(e: Boolean) {}
    fun onPhotoSequenceFinished() {}
    fun onCalibrationPointCaptured() {}
    fun unwarpImage(l: List<Any>) {}
    fun onRetakeCapture() {}
    fun onRefinementPathAdded(p: RefinementPath) = _uiState.update { it.copy(refinementPaths = it.refinementPaths + p) }
    fun onRefinementModeChanged(b: Boolean) {}
    fun onConfirmTargetCreation() {}
    fun onMagicClicked() {}
    fun checkForUpdates() {}
    fun installLatestUpdate() {}
}

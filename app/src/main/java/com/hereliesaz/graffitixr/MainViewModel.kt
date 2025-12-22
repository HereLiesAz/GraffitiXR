package com.hereliesaz.graffitixr

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.GithubRelease
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import com.hereliesaz.graffitixr.utils.BitmapUtils
import com.hereliesaz.graffitixr.utils.OnboardingManager
import com.hereliesaz.graffitixr.utils.ProjectManager
import com.hereliesaz.graffitixr.utils.applyCurves
import com.hereliesaz.graffitixr.utils.convertToLineDrawing
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs

sealed class CaptureEvent {
    object RequestCapture : CaptureEvent()
}

sealed class FeedbackEvent {
    object VibrateSingle : FeedbackEvent()
    object VibrateDouble : FeedbackEvent()
}

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(application) {

    private val onboardingManager = OnboardingManager(application)
    private val projectManager = ProjectManager(application)

    private val undoStack = mutableListOf<UiState>()
    private val redoStack = mutableListOf<UiState>()

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState())

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent = _captureEvent.asSharedFlow()

    private val _requestImagePicker = MutableSharedFlow<Unit>()
    val requestImagePicker = _requestImagePicker.asSharedFlow()

    private val _feedbackEvent = MutableSharedFlow<FeedbackEvent>()
    val feedbackEvent = _feedbackEvent.asSharedFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

    // Holds the screen-space bounds of the AR object for gesture hit-testing
    private val _artworkBounds = MutableStateFlow<RectF?>(null)
    val artworkBounds = _artworkBounds.asStateFlow()

    var arRenderer: ArRenderer? = null

    init {
        val completedModes = onboardingManager.getCompletedModes()
        updateState(uiState.value.copy(
            completedOnboardingModes = completedModes,
            showOnboardingDialogForMode = if (!completedModes.contains(uiState.value.editorMode)) uiState.value.editorMode else null
        ), isUndoable = false)

        startAutoSave()
    }

    private fun startAutoSave() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30000)
                try {
                    performSave("autosave", false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AutoSave", "Failed", e)
                }
            }
        }
    }

    /**
     * Updates the screen-space bounding box of the AR artwork.
     * Called from ArRenderer on the GL thread, so we post to the Flow.
     */
    fun updateArtworkBounds(bounds: RectF) {
        // Only emit if significantly different to reduce state churn
        val current = _artworkBounds.value
        if (current == null ||
            abs(current.left - bounds.left) > 10 ||
            abs(current.top - bounds.top) > 10 ||
            abs(current.right - bounds.right) > 10 ||
            abs(current.bottom - bounds.bottom) > 10) {
            _artworkBounds.value = bounds
        }
    }

    // --- AR Target Creation Logic ---

    fun onCaptureShutterClicked() {
        val state = uiState.value
        if (state.isCapturingTarget && state.editorMode == EditorMode.AR) {
            when (state.captureStep) {
                CaptureStep.INSTRUCTION -> {
                    updateState(state.copy(captureStep = CaptureStep.FRONT), isUndoable = false)
                }
                CaptureStep.GRID_CONFIG -> {
                    updateState(state.copy(captureStep = CaptureStep.GUIDED_CAPTURE), isUndoable = false)
                }
                CaptureStep.GUIDED_CAPTURE -> {
                    arRenderer?.triggerCapture()
                }
                else -> {
                    arRenderer?.triggerCapture()
                }
            }
        } else {
            viewModelScope.launch {
                _captureEvent.emit(CaptureEvent.RequestCapture)
            }
        }
    }

    fun onTargetCreationMethodSelected(mode: TargetCreationMode) {
        val nextStep = when (mode) {
            TargetCreationMode.CAPTURE -> CaptureStep.FRONT
            TargetCreationMode.GUIDED_GRID -> CaptureStep.GRID_CONFIG
            TargetCreationMode.GUIDED_POINTS -> CaptureStep.GUIDED_CAPTURE
        }

        val newScale = if (mode != TargetCreationMode.CAPTURE) 0.5f else uiState.value.arObjectScale

        updateState(uiState.value.copy(
            targetCreationMode = mode,
            captureStep = nextStep,
            isGridGuideVisible = mode != TargetCreationMode.CAPTURE,
            arObjectScale = newScale
        ), isUndoable = false)

        if (mode != TargetCreationMode.CAPTURE) {
            updateGuideOverlay()
        } else {
            arRenderer?.showGuide = false
        }
    }

    fun onGridConfigChanged(rows: Int, cols: Int) {
        updateState(uiState.value.copy(gridRows = rows, gridCols = cols), isUndoable = false)
        updateGuideOverlay()
    }

    private fun updateGuideOverlay() {
        val state = uiState.value
        val bitmap = when (state.targetCreationMode) {
            TargetCreationMode.GUIDED_GRID -> com.hereliesaz.graffitixr.utils.GuideGenerator.generateGrid(state.gridRows, state.gridCols)
            TargetCreationMode.GUIDED_POINTS -> com.hereliesaz.graffitixr.utils.GuideGenerator.generateFourXs()
            else -> null
        }

        if (bitmap != null) {
            arRenderer?.guideBitmap = bitmap
            arRenderer?.showGuide = true
        } else {
            arRenderer?.showGuide = false
        }
    }

    fun onCancelCaptureClicked() {
        arRenderer?.showGuide = false
        arRenderer?.resetAnchor()
        updateState(uiState.value.copy(
            isCapturingTarget = false,
            targetCreationState = TargetCreationState.IDLE,
            captureStep = CaptureStep.ADVICE,
            capturedTargetImages = emptyList(),
            targetCreationMode = TargetCreationMode.CAPTURE,
            isGridGuideVisible = false,
            arState = ArState.SEARCHING
        ))
    }

    fun onRefinementPathAdded(path: com.hereliesaz.graffitixr.data.RefinementPath) {
        val currentPaths = uiState.value.refinementPaths
        updateState(uiState.value.copy(refinementPaths = currentPaths + path), isUndoable = true)
        updateDetectedKeypoints()
    }

    private fun updateDetectedKeypoints() {
        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return@launch
            val paths = uiState.value.refinementPaths
            val maskUri = uiState.value.targetMaskUri
            val mask = if (maskUri != null) BitmapUtils.getBitmapFromUri(getApplication(), maskUri) else null

            val keypoints = com.hereliesaz.graffitixr.utils.detectFeaturesWithMask(bitmap, paths, mask)
            withContext(Dispatchers.Main) {
                updateState(uiState.value.copy(detectedKeypoints = keypoints), isUndoable = false)
            }
        }
    }

    fun onRefineTargetToggled() {
        if (uiState.value.capturedTargetImages.isNotEmpty()) {
            updateState(uiState.value.copy(
                isCapturingTarget = true,
                captureStep = CaptureStep.REVIEW
            ), isUndoable = false)
        } else {
            Toast.makeText(getApplication(), "No target captured to refine", Toast.LENGTH_SHORT).show()
        }
    }

    fun onRefinementModeChanged(isEraser: Boolean) {
        updateState(uiState.value.copy(isRefinementEraser = isEraser), isUndoable = false)
    }

    fun onConfirmTargetCreation() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val originalBitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return@launch
                val refinementPaths = uiState.value.refinementPaths
                val maskUri = uiState.value.targetMaskUri
                val mask = if (maskUri != null) BitmapUtils.getBitmapFromUri(getApplication(), maskUri) else null

                val refinedBitmap = com.hereliesaz.graffitixr.utils.applyMaskToBitmap(originalBitmap, refinementPaths, mask)

                val fingerprint = com.hereliesaz.graffitixr.utils.generateFingerprint(originalBitmap, refinementPaths, mask)

                if (fingerprint.keypoints.isEmpty() || fingerprint.descriptors.empty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(getApplication(), "No features found. Try a more detailed surface.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val fingerprintJson = Json.encodeToString(Fingerprint.serializer(), fingerprint)

                arRenderer?.setAugmentedImageDatabase(listOf(originalBitmap))
                arRenderer?.setFingerprint(fingerprint)

                withContext(Dispatchers.Main) {
                    updateState(
                        uiState.value.copy(
                            isCapturingTarget = false,
                            targetCreationState = TargetCreationState.SUCCESS,
                            isArTargetCreated = true,
                            arState = ArState.SEARCHING,
                            fingerprintJson = fingerprintJson
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error confirming target creation", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Target confirmation failed: ${e.message}", Toast.LENGTH_LONG).show()
                    updateState(
                        uiState.value.copy(
                            isCapturingTarget = false,
                            targetCreationState = TargetCreationState.ERROR,
                            arState = ArState.SEARCHING
                        )
                    )
                }
            }
        }
    }

    fun onTrackingFailure(message: String?) {
        updateState(uiState.value.copy(qualityWarning = message), isUndoable = false)
    }

    fun onCreateTargetClicked() {
        arRenderer?.resetAnchor()
        updateState(uiState.value.copy(
            isCapturingTarget = true,
            captureStep = CaptureStep.ADVICE,
            capturedTargetImages = emptyList(),
            targetCreationMode = TargetCreationMode.CAPTURE,
            arState = ArState.SEARCHING
        ), isUndoable = false)
    }

    fun onFrameCaptured(bitmap: Bitmap) {
        val currentImages = uiState.value.capturedTargetImages
        val newImages = currentImages + bitmap
        val currentStep = uiState.value.captureStep
        val mode = uiState.value.targetCreationMode

        val nextStep = if (mode == TargetCreationMode.CAPTURE) {
            when (currentStep) {
                CaptureStep.INSTRUCTION -> CaptureStep.FRONT
                CaptureStep.FRONT -> CaptureStep.LEFT
                CaptureStep.LEFT -> CaptureStep.RIGHT
                CaptureStep.RIGHT -> CaptureStep.UP
                CaptureStep.UP -> CaptureStep.DOWN
                CaptureStep.DOWN -> CaptureStep.REVIEW
                CaptureStep.REVIEW -> CaptureStep.REVIEW
                else -> CaptureStep.REVIEW
            }
        } else {
            // For guided modes, 1 frame is enough
            CaptureStep.REVIEW
        }

        viewModelScope.launch {
            _feedbackEvent.emit(FeedbackEvent.VibrateSingle)
        }

        if (nextStep != CaptureStep.REVIEW) {
            updateState(uiState.value.copy(
                capturedTargetImages = newImages,
                captureStep = nextStep
            ), isUndoable = false)
        } else {
            // Hide guide before processing
            arRenderer?.showGuide = false
            updateState(uiState.value.copy(
                capturedTargetImages = newImages,
                isLoading = true,
                isGridGuideVisible = false
            ), isUndoable = false)
            processCapturedTargets(newImages)
        }
    }

    private fun processCapturedTargets(images: List<Bitmap>) {
        viewModelScope.launch {
            _feedbackEvent.emit(FeedbackEvent.VibrateDouble)

            val moduleInstallClient = ModuleInstall.getClient(getApplication())
            val subjectOptions = SubjectSegmenterOptions.SubjectResultOptions.Builder()
                .enableConfidenceMask()
                .build()
            val segmenterOptions = SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(subjectOptions)
                .build()
            val segmenter = SubjectSegmentation.getClient(segmenterOptions)

            val areModulesAvailable = try {
                withContext(Dispatchers.IO) {
                    com.google.android.gms.tasks.Tasks.await(
                        moduleInstallClient.areModulesAvailable(segmenter)
                    ).areModulesAvailable()
                }
            } catch (e: Exception) { false }

            if (!areModulesAvailable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Downloading AI models... Please wait.", Toast.LENGTH_LONG).show()
                }
                try {
                    withContext(Dispatchers.IO) {
                        val request = ModuleInstallRequest.newBuilder()
                            .addApi(segmenter)
                            .build()
                        com.google.android.gms.tasks.Tasks.await(
                            moduleInstallClient.installModules(request)
                        )
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to request module install", e)
                }
            }

            withContext(Dispatchers.IO) {
                val frontImage = images.firstOrNull()
                if (frontImage == null) {
                    withContext(Dispatchers.Main) {
                        updateState(uiState.value.copy(isLoading = false))
                        Toast.makeText(getApplication(), "Failed to create grid", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext
                }

                val inputImage = InputImage.fromBitmap(frontImage, 0)

                segmenter.process(inputImage)
                    .addOnSuccessListener { result ->
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val subject = result.subjects.firstOrNull()
                                var maskUri: Uri? = null

                                if (subject != null) {
                                    val width = subject.width
                                    val height = subject.height
                                    val maskBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    val buffer = subject.confidenceMask
                                    if (buffer != null) {
                                        buffer.rewind()
                                        val pixels = IntArray(width * height)
                                        for (i in 0 until width * height) {
                                            if (buffer.hasRemaining()) {
                                                val confidence = buffer.get()
                                                val alpha = (confidence * 255).toInt().coerceIn(0, 255)
                                                pixels[i] = (alpha shl 24) or 0x00FFFFFF
                                            }
                                        }
                                        maskBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                                    }
                                    maskUri = saveBitmapToCache(maskBitmap, "mask")
                                }

                                arRenderer?.setAugmentedImageDatabase(images)

                                withContext(Dispatchers.Main) {
                                    updateState(
                                        uiState.value.copy(
                                            capturedTargetImages = images,
                                            targetMaskUri = maskUri,
                                            captureStep = CaptureStep.REVIEW,
                                            targetCreationState = TargetCreationState.SUCCESS,
                                            isArTargetCreated = true,
                                            arState = ArState.SEARCHING,
                                            isLoading = false
                                        )
                                    )
                                    Toast.makeText(getApplication(), "Grid created successfully", Toast.LENGTH_SHORT).show()
                                    updateDetectedKeypoints()
                                }
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error processing segmentation result", e)
                                withContext(Dispatchers.Main) {
                                    handleTargetCreationFailure(e)
                                }
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("MainViewModel", "Segmentation failed", e)
                        arRenderer?.setAugmentedImageDatabase(images)
                        viewModelScope.launch(Dispatchers.Main) {
                            updateState(
                                uiState.value.copy(
                                    capturedTargetImages = images,
                                    captureStep = CaptureStep.REVIEW,
                                    targetCreationState = TargetCreationState.SUCCESS,
                                    isArTargetCreated = true,
                                    arState = ArState.SEARCHING,
                                    isLoading = false
                                )
                            )
                            Toast.makeText(getApplication(), "Grid created (segmentation failed)", Toast.LENGTH_SHORT).show()
                            updateDetectedKeypoints()
                        }
                    }
            }
        }
    }

    private fun handleTargetCreationFailure(e: Exception) {
        updateState(
            uiState.value.copy(
                isCapturingTarget = false,
                targetCreationState = TargetCreationState.IDLE,
                captureStep = CaptureStep.ADVICE,
                capturedTargetImages = emptyList(),
                arState = ArState.SEARCHING,
                isLoading = false,
                isGridGuideVisible = false
            )
        )
        arRenderer?.showGuide = false
        Toast.makeText(getApplication(), "Target creation failed: ${e.message}", Toast.LENGTH_LONG).show()
    }


    fun exportProjectToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            setLoading(true)
            try {
                val savedTargetUris = uiState.value.capturedTargetImages.mapNotNull { bitmap ->
                    saveBitmapToCache(bitmap)
                }
                val projectData = ProjectData(
                    backgroundImageUri = uiState.value.backgroundImageUri,
                    overlayImageUri = uiState.value.overlayImageUri,
                    targetImageUris = savedTargetUris,
                    refinementPaths = uiState.value.refinementPaths,
                    opacity = uiState.value.opacity,
                    contrast = uiState.value.contrast,
                    saturation = uiState.value.saturation,
                    colorBalanceR = uiState.value.colorBalanceR,
                    colorBalanceG = uiState.value.colorBalanceG,
                    colorBalanceB = uiState.value.colorBalanceB,
                    scale = uiState.value.scale,
                    rotationZ = uiState.value.rotationZ,
                    rotationX = uiState.value.rotationX,
                    rotationY = uiState.value.rotationY,
                    offset = uiState.value.offset,
                    blendMode = uiState.value.blendMode,
                    fingerprint = uiState.value.fingerprintJson?.let { Json.decodeFromString(it) },
                    drawingPaths = uiState.value.drawingPaths,
                    progressPercentage = uiState.value.progressPercentage,
                    evolutionImageUris = uiState.value.evolutionCaptureUris,
                    gpsData = getGpsData(),
                    sensorData = getSensorData()
                )
                projectManager.exportProjectToZip(uri, projectData)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Project saved successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error exporting project", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to save project", Toast.LENGTH_SHORT).show()
                }
            } finally {
                setLoading(false)
            }
        }
    }

    fun onCycleRotationAxis() {
        val currentAxis = uiState.value.activeRotationAxis
        val nextAxis = when (currentAxis) {
            RotationAxis.X -> RotationAxis.Y
            RotationAxis.Y -> RotationAxis.Z
            RotationAxis.Z -> RotationAxis.X
        }
        updateState(uiState.value.copy(activeRotationAxis = nextAxis, showRotationAxisFeedback = true))
        Toast.makeText(getApplication(), "Rotation Axis: ${nextAxis.name}", Toast.LENGTH_SHORT).show()
    }

    fun onArObjectScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.arObjectScale
        val newScale = (currentScale * scaleFactor).coerceIn(0.1f, 10.0f)
        updateState(uiState.value.copy(arObjectScale = newScale), isUndoable = false)
    }

    fun onRotationXChanged(delta: Float) {
        updateState(uiState.value.copy(rotationX = uiState.value.rotationX + delta), isUndoable = false)
    }

    fun onRotationYChanged(delta: Float) {
        updateState(uiState.value.copy(rotationY = uiState.value.rotationY + delta), isUndoable = false)
    }

    fun onRotationZChanged(delta: Float) {
        val currentRotation = uiState.value.rotationZ
        updateState(uiState.value.copy(rotationZ = currentRotation + delta), isUndoable = false)
    }

    fun onFeedbackShown() {
        viewModelScope.launch {
            delay(1000)
            updateState(uiState.value.copy(showRotationAxisFeedback = false))
        }
    }

    fun setArPlanesDetected(detected: Boolean) {
        if (uiState.value.isArPlanesDetected != detected) {
            updateState(uiState.value.copy(isArPlanesDetected = detected), isUndoable = false)
        }
    }

    fun onArImagePlaced() {
        updateState(uiState.value.copy(arState = ArState.LOCKED), isUndoable = false)

        if (uiState.value.isCapturingTarget) {
            if (uiState.value.captureStep == CaptureStep.ADVICE) {
                updateState(uiState.value.copy(captureStep = CaptureStep.CHOOSE_METHOD), isUndoable = false)
            }
        } else if (uiState.value.overlayImageUri == null) {
            viewModelScope.launch {
                _requestImagePicker.emit(Unit)
            }
        }
    }

    fun onProgressUpdate(progress: Float, bitmap: Bitmap? = null) {
        val currentProgress = uiState.value.progressPercentage
        val isSignificant = abs(progress - currentProgress) > 5.0f
        if (isSignificant && bitmap != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val uri = saveBitmapToCache(bitmap, "evolution")
                if (uri != null) {
                    withContext(Dispatchers.Main) {
                        val currentUris = uiState.value.evolutionCaptureUris
                        updateState(uiState.value.copy(evolutionCaptureUris = currentUris + listOf(uri)), isUndoable = false)
                    }
                }
            }
        }
        updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false)
    }

    fun setTouchLocked(locked: Boolean) {
        updateState(uiState.value.copy(isTouchLocked = locked), isUndoable = false)
    }

    fun showTapFeedback(position: Offset, isSuccess: Boolean) {
        viewModelScope.launch {
            _tapFeedback.value = if (isSuccess) TapFeedback.Success(position) else TapFeedback.Failure(position)
            delay(500)
            _tapFeedback.value = null
        }
    }

    fun onRemoveBackgroundClicked() {
        viewModelScope.launch {
            setLoading(true)
            val uri = uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri
            if (uri != null) {
                try {
                    val context = getApplication<Application>().applicationContext
                    val bitmap = BitmapUtils.getBitmapFromUri(context, uri) ?: return@launch
                    val resultBitmap = BackgroundRemover.removeBackground(bitmap)
                    if (resultBitmap != null) {
                        val cachePath = File(context.cacheDir, "images")
                        cachePath.mkdirs()
                        val file = File(cachePath, "background_removed_${System.currentTimeMillis()}.png")
                        val fOut = FileOutputStream(file)
                        resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                        fOut.close()
                        val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                        updateState(uiState.value.copy(overlayImageUri = newUri, backgroundRemovedImageUri = newUri, isLoading = false))
                    } else {
                        setLoading(false)
                        Toast.makeText(getApplication(), "Background removal failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    setLoading(false)
                }
            } else {
                setLoading(false)
            }
        }
    }

    fun onBrightnessChanged(brightness: Float) {
        updateState(uiState.value.copy(brightness = brightness), isUndoable = false)
    }

    fun onLineDrawingClicked() {
        viewModelScope.launch {
            setLoading(true)
            val isCurrentlyLineDrawing = uiState.value.isLineDrawing
            val nextState = !isCurrentlyLineDrawing
            if (!nextState) {
                val restoreUri = uiState.value.backgroundRemovedImageUri ?: uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri
                if (restoreUri != null) {
                    updateState(uiState.value.copy(overlayImageUri = restoreUri, isLineDrawing = false, isLoading = false))
                } else {
                    updateState(uiState.value.copy(isLineDrawing = false, isLoading = false))
                }
                return@launch
            }
            val uri = uiState.value.backgroundRemovedImageUri ?: uiState.value.originalOverlayImageUri ?: uiState.value.overlayImageUri
            if (uri != null) {
                val context = getApplication<Application>().applicationContext
                val bitmap = BitmapUtils.getBitmapFromUri(context, uri)?.copy(Bitmap.Config.ARGB_8888, true) ?: return@launch
                val lineDrawingBitmap = convertToLineDrawing(bitmap, isWhite = true)
                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "line_drawing_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                lineDrawingBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()
                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                updateState(uiState.value.copy(overlayImageUri = newUri, isLineDrawing = true, isLoading = false))
            } else {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        updateState(uiState.value.copy(isLoading = isLoading), isUndoable = false)
    }

    fun onBackgroundImageSelected(uri: Uri) {
        updateState(uiState.value.copy(backgroundImageUri = uri))
    }

    fun onOverlayImageSelected(uri: Uri) {
        val showHint = !onboardingManager.hasSeenDoubleTapHint()
        updateState(uiState.value.copy(overlayImageUri = uri, originalOverlayImageUri = uri, backgroundRemovedImageUri = null, isLineDrawing = false, showDoubleTapHint = showHint))
    }

    fun onOpacityChanged(opacity: Float) {
        updateState(uiState.value.copy(opacity = opacity), isUndoable = false)
    }

    fun onContrastChanged(contrast: Float) {
        updateState(uiState.value.copy(contrast = contrast), isUndoable = false)
    }

    fun onSaturationChanged(saturation: Float) {
        updateState(uiState.value.copy(saturation = saturation), isUndoable = false)
    }

    fun onScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.scale
        updateState(uiState.value.copy(scale = currentScale * scaleFactor), isUndoable = false)
    }

    fun onOffsetChanged(offset: Offset) {
        updateState(uiState.value.copy(offset = uiState.value.offset + offset), isUndoable = false)
    }

    fun onEditorModeChanged(mode: EditorMode) {
        if (mode == EditorMode.HELP) {
            onboardingManager.resetOnboarding()
            updateState(uiState.value.copy(completedOnboardingModes = emptySet(), editorMode = mode, showOnboardingDialogForMode = null, activeRotationAxis = RotationAxis.Z))
        } else {
            val showOnboarding = !uiState.value.completedOnboardingModes.contains(mode)
            updateState(uiState.value.copy(editorMode = mode, showOnboardingDialogForMode = if (showOnboarding) mode else null, activeRotationAxis = RotationAxis.Z))
        }
    }

    fun onOnboardingComplete(mode: EditorMode) {
        onboardingManager.completeMode(mode)
        val updatedModes = onboardingManager.getCompletedModes()
        val updatedState = uiState.value.copy(completedOnboardingModes = updatedModes, showOnboardingDialogForMode = null)
        if (mode == EditorMode.HELP) {
            updateState(updatedState.copy(editorMode = EditorMode.STATIC))
        } else {
            updateState(updatedState)
        }
    }

    fun onDoubleTapHintDismissed() {
        onboardingManager.setDoubleTapHintSeen()
        updateState(uiState.value.copy(showDoubleTapHint = false))
    }

    fun onCurvesPointsChangeFinished() {
        viewModelScope.launch {
            val uri = uiState.value.overlayImageUri
            if (uri != null) {
                applyCurvesToOverlay(uri, uiState.value.curvesPoints)
            }
        }
    }

    private fun applyCurvesToOverlay(uri: Uri, points: List<Offset>) {
        viewModelScope.launch {
            setLoading(true)
            try {
                val context = getApplication<Application>().applicationContext
                val bitmap = BitmapUtils.getBitmapFromUri(context, uri) ?: return@launch
                val resultBitmap = applyCurves(bitmap, points)
                val cachePath = File(context.cacheDir, "images")
                cachePath.mkdirs()
                val file = File(cachePath, "curves_processed_${System.currentTimeMillis()}.png")
                val fOut = FileOutputStream(file)
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
                fOut.close()
                val newUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                updateState(uiState.value.copy(processedImageUri = newUri, isLoading = false))
            } catch (e: Exception) {
                e.printStackTrace()
                setLoading(false)
            }
        }
    }

    fun onCurvesPointsChanged(points: List<Offset>) {
        updateState(uiState.value.copy(curvesPoints = points), isUndoable = false)
    }

    fun onSaveClicked() {
        viewModelScope.launch {
            updateState(uiState.value.copy(hideUiForCapture = true), isUndoable = false)
            delay(200)
            _captureEvent.emit(CaptureEvent.RequestCapture)
        }
    }

    fun saveCapturedBitmap(bitmap: Bitmap) {
        updateState(uiState.value.copy(hideUiForCapture = false), isUndoable = false)

        viewModelScope.launch {
            setLoading(true)
            val success = withContext(Dispatchers.IO) {
                saveBitmapToGallery(getApplication(), bitmap)
            }
            withContext(Dispatchers.Main) {
                val message = if (success) "Image saved to gallery" else "Failed to save image"
                Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
                setLoading(false)
            }
        }
    }

    fun onColorBalanceRChanged(value: Float) {
        updateState(uiState.value.copy(colorBalanceR = value), isUndoable = false)
    }

    fun onColorBalanceGChanged(value: Float) {
        updateState(uiState.value.copy(colorBalanceG = value), isUndoable = false)
    }

    fun onColorBalanceBChanged(value: Float) {
        updateState(uiState.value.copy(colorBalanceB = value), isUndoable = false)
    }

    fun onCycleBlendMode() {
        val currentMode = uiState.value.blendMode
        val nextMode = when (currentMode) {
            BlendMode.SrcOver -> BlendMode.Multiply
            BlendMode.Multiply -> BlendMode.Screen
            BlendMode.Screen -> BlendMode.Overlay
            BlendMode.Overlay -> BlendMode.Darken
            BlendMode.Darken -> BlendMode.Lighten
            BlendMode.Lighten -> BlendMode.SrcOver
            else -> BlendMode.SrcOver
        }
        Toast.makeText(getApplication(), "Blend Mode: ${nextMode}", Toast.LENGTH_SHORT).show()
        updateState(uiState.value.copy(blendMode = nextMode))
    }

    fun onMagicClicked() {
        val state = uiState.value
        if (state.rotationX != 0f || state.rotationY != 0f || state.rotationZ != 0f) {
            updateState(state.copy(rotationX = 0f, rotationY = 0f, rotationZ = 0f))
            Toast.makeText(getApplication(), "Aligned Flat", Toast.LENGTH_SHORT).show()
        } else if (state.arState == ArState.PLACED) {
            Toast.makeText(getApplication(), "Already Anchored", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(getApplication(), "Point at target to anchor", Toast.LENGTH_SHORT).show()
        }
    }

    fun onToggleFlashlight() {
        val newState = !uiState.value.isFlashlightOn
        updateState(uiState.value.copy(isFlashlightOn = newState), isUndoable = false)
        arRenderer?.setFlashlight(newState)
    }

    fun saveProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            performSave(projectName, true)
        }
    }

    private suspend fun performSave(projectName: String, showToast: Boolean) {
        val snapshot = uiState.value
        try {
            val currentImages = snapshot.capturedTargetImages
            val currentUris = snapshot.capturedTargetUris

            val savedTargetUris = if (currentImages.isNotEmpty()) {
                currentImages.mapNotNull { bitmap ->
                    saveBitmapToCache(bitmap)
                }
            } else {
                currentUris
            }

            val projectData = ProjectData(
                backgroundImageUri = snapshot.backgroundImageUri,
                overlayImageUri = snapshot.overlayImageUri,
                originalOverlayImageUri = snapshot.originalOverlayImageUri,targetImageUris = savedTargetUris,
                refinementPaths = snapshot.refinementPaths,
                opacity = snapshot.opacity,
                brightness = snapshot.brightness,
                contrast = snapshot.contrast,
                saturation = snapshot.saturation,
                colorBalanceR = snapshot.colorBalanceR,
                colorBalanceG = snapshot.colorBalanceG,
                colorBalanceB = snapshot.colorBalanceB,
                scale = snapshot.scale,
                rotationZ = snapshot.rotationZ,
                rotationX = snapshot.rotationX,
                rotationY = snapshot.rotationY,
                offset = snapshot.offset,
                blendMode = snapshot.blendMode,
                fingerprint = snapshot.fingerprintJson?.let { Json.decodeFromString(Fingerprint.serializer(), it) },
                drawingPaths = snapshot.drawingPaths,
                progressPercentage = snapshot.progressPercentage,
                evolutionImageUris = snapshot.evolutionCaptureUris,
                gpsData = getGpsData(),
                sensorData = getSensorData()
            )
            projectManager.saveProject(projectName, projectData)
            if (showToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Project '$projectName' saved", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error saving project", e)
            if (showToast) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to save project", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun getSensorData(): com.hereliesaz.graffitixr.data.SensorData? {
        return suspendCancellableCoroutine { cont ->
            val sensorManager = getApplication<Application>().getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
            if (sensorManager == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val accelerometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
            val magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
            if (accelerometer == null || magnetometer == null) {
                if (cont.isActive) cont.resume(null)
                return@suspendCancellableCoroutine
            }
            var gravity: FloatArray? = null
            var geomagnetic: FloatArray? = null
            val listener = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                    event ?: return
                    if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) gravity = event.values.clone()
                    if (event.sensor.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD) geomagnetic = event.values.clone()
                    if (gravity != null && geomagnetic != null) {
                        val R = FloatArray(9)
                        val I = FloatArray(9)
                        if (android.hardware.SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                            val orientation = FloatArray(3)
                            android.hardware.SensorManager.getOrientation(R, orientation)
                            val sensorData = com.hereliesaz.graffitixr.data.SensorData(orientation[0], orientation[1], orientation[2])
                            sensorManager.unregisterListener(this)
                            if (cont.isActive) cont.resume(sensorData)
                        }
                    }
                }
                override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            }
            sensorManager.registerListener(listener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            sensorManager.registerListener(listener, magnetometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            handler.postDelayed({ sensorManager.unregisterListener(listener); if (cont.isActive) cont.resume(null) }, 1000)
            cont.invokeOnCancellation { sensorManager.unregisterListener(listener); handler.removeCallbacksAndMessages(null) }
        }
    }

    private fun getGpsData(): com.hereliesaz.graffitixr.data.GpsData? {
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
            val locationManager = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            val location = locationManager?.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) ?: locationManager?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
            return location?.let { com.hereliesaz.graffitixr.data.GpsData(it.latitude, it.longitude, it.altitude, it.accuracy, it.time) }
        } catch (e: Exception) { return null }
    }

    private fun saveBitmapToCache(bitmap: Bitmap, prefix: String = "target"): Uri? {
        try {
            val context = getApplication<Application>().applicationContext
            val cachePath = File(context.cacheDir, "project_assets")
            cachePath.mkdirs()
            val file = File(cachePath, "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.png")
            val fOut = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
            fOut.close()
            return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        } catch (e: Exception) { e.printStackTrace(); return null }
    }

    fun loadProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                projectManager.loadProject(projectName)?.let { projectData ->
                    updateState(uiState.value.copy(backgroundImageUri = projectData.backgroundImageUri, overlayImageUri = projectData.overlayImageUri, originalOverlayImageUri = projectData.originalOverlayImageUri ?: projectData.overlayImageUri, opacity = projectData.opacity, brightness = projectData.brightness, contrast = projectData.contrast, saturation = projectData.saturation, colorBalanceR = projectData.colorBalanceR, colorBalanceG = projectData.colorBalanceG, colorBalanceB = projectData.colorBalanceB, scale = projectData.scale, rotationZ = projectData.rotationZ, rotationX = projectData.rotationX, rotationY = projectData.rotationY, offset = projectData.offset, blendMode = projectData.blendMode, fingerprintJson = projectData.fingerprint?.let { Json.encodeToString(Fingerprint.serializer(), it) }, drawingPaths = projectData.drawingPaths, isLineDrawing = projectData.isLineDrawing, capturedTargetUris = projectData.targetImageUris ?: emptyList()))
                    withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Project '$projectName' loaded", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) { Log.e("MainViewModel", "Error loading project", e); withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Failed to load project", Toast.LENGTH_SHORT).show() } }
        }
    }

    fun getProjectList(): List<String> = projectManager.getProjectList()

    fun deleteProject(projectName: String) {
        viewModelScope.launch(Dispatchers.IO) { projectManager.deleteProject(projectName); withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Project '$projectName' deleted", Toast.LENGTH_SHORT).show() } }
    }

    fun onNewProject() { updateState(UiState(), isUndoable = false) }

    fun onUndoClicked() { if (undoStack.isNotEmpty()) { val lastState = undoStack.removeAt(undoStack.lastIndex); redoStack.add(uiState.value); updateState(lastState, isUndoable = false) } }

    fun onRedoClicked() { if (redoStack.isNotEmpty()) { val nextState = redoStack.removeAt(redoStack.lastIndex); undoStack.add(uiState.value); updateState(nextState, isUndoable = false) } }

    fun onGestureStart() { if (undoStack.isNotEmpty()) undoStack[undoStack.lastIndex] = uiState.value else undoStack.add(uiState.value); redoStack.clear() }

    fun onGestureEnd() { }

    private fun updateState(newState: UiState, isUndoable: Boolean = true) {
        val currentState = savedStateHandle.get<UiState>("uiState") ?: UiState()
        if (isUndoable) { undoStack.add(currentState); if (undoStack.size > MAX_UNDO_STACK_SIZE) undoStack.removeAt(0); redoStack.clear() }
        savedStateHandle["uiState"] = newState.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
    }

    fun onMarkProgressToggled() { updateState(uiState.value.copy(isMarkingProgress = !uiState.value.isMarkingProgress)) }

    fun onDrawingPathFinished(points: List<Pair<Float, Float>>) { val newPaths = uiState.value.drawingPaths + listOf(points); updateState(uiState.value.copy(drawingPaths = newPaths)); recalculateProgress() }

    private fun recalculateProgress() {
        viewModelScope.launch {
            val overlayImageUri = uiState.value.overlayImageUri ?: return@launch
            val allPaths = uiState.value.drawingPaths
            if (allPaths.isEmpty()) { updateState(uiState.value.copy(progressPercentage = 0f), isUndoable = false); return@launch }
            val (width, height) = BitmapUtils.getBitmapDimensions(getApplication(), overlayImageUri)
            if (width == 0 || height == 0) return@launch
            val progressBitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val composePaths = allPaths.map { pointList -> androidx.compose.ui.graphics.Path().apply { if (pointList.isNotEmpty()) { moveTo(pointList.first().first, pointList.first().second); for (i in 1 until pointList.size) lineTo(pointList[i].first, pointList[i].second) } } }
            val totalColoredPixels = com.hereliesaz.graffitixr.utils.calculateProgress(composePaths, progressBitmap)
            val progress = (totalColoredPixels.toFloat() / (width * height).toFloat()) * 100
            updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false)
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateState(uiState.value.copy(isCheckingForUpdate = true, updateStatusMessage = null), isUndoable = false)
            withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://api.github.com/repos/HereLiesAZ/GraffitiXR/releases")
                    val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.requestMethod = "GET"; connection.connectTimeout = 5000; connection.readTimeout = 5000
                    connection.setRequestProperty("User-Agent", "GraffitiXR-App")
                    if (connection.responseCode == 200) {
                        val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                        val response = StringBuilder(); var line: String?
                        while (reader.readLine().also { line = it } != null) response.append(line)
                        reader.close()
                        val json = Json { ignoreUnknownKeys = true }
                        val releases = json.decodeFromString<List<GithubRelease>>(response.toString())
                        val experimentalRelease = releases.firstOrNull { it.prerelease }
                        withContext(Dispatchers.Main) {
                            if (experimentalRelease != null) {
                                val apkAsset = experimentalRelease.assets.firstOrNull {
                                    it.browser_download_url.endsWith(".apk")
                                }

                                var updateAvailable = false
                                var versionString = experimentalRelease.tag_name

                                if (apkAsset != null) {
                                    val regex = Regex("""GraffitiXR-(\d+\.\d+\.\d+\.\d+)-debug\.apk""")
                                    val match = regex.find(apkAsset.browser_download_url.substringAfterLast("/"))
                                    if (match != null) {
                                        val remoteVersion = match.groupValues[1]
                                        versionString = remoteVersion
                                        updateAvailable = isNewerVersion(BuildConfig.VERSION_NAME, remoteVersion)
                                    } else {
                                        updateAvailable = experimentalRelease.tag_name > BuildConfig.VERSION_NAME
                                    }
                                } else {
                                    updateAvailable = experimentalRelease.tag_name > BuildConfig.VERSION_NAME
                                }

                                val message = if (updateAvailable) "New experimental build: $versionString" else "Latest experimental build installed."
                                updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = message, latestRelease = experimentalRelease), isUndoable = false)
                            } else {
                                updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "No experimental builds found."), isUndoable = false)
                            }
                        }
                    } else { withContext(Dispatchers.Main) { updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "HTTP ${connection.responseCode}"), isUndoable = false) } }
                } catch (e: Exception) { e.printStackTrace(); withContext(Dispatchers.Main) { updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "Error: ${e.message}"), isUndoable = false) } }
            }
        }
    }

    private fun isNewerVersion(current: String, remote: String): Boolean {
        val v1 = current.split(".").map { it.toIntOrNull() ?: 0 }
        val v2 = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val length = kotlin.math.max(v1.size, v2.size)
        for (i in 0 until length) {
            val p1 = if (i < v1.size) v1[i] else 0
            val p2 = if (i < v2.size) v2[i] else 0
            if (p2 > p1) return true
            if (p1 > p2) return false
        }
        return false
    }

    fun installLatestUpdate() {
        val release = uiState.value.latestRelease ?: return
        val asset = release.assets.firstOrNull { it.browser_download_url.endsWith(".apk") } ?: return
        val downloadUrl = asset.browser_download_url; val fileName = "GraffitiXR-${release.tag_name}.apk"
        try {
            val request = DownloadManager.Request(downloadUrl.toUri()).setTitle(fileName).setDescription("Downloading GraffitiXR Update").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val downloadManager = getApplication<Application>().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)
            getApplication<Application>().getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("update_download_id", downloadId)
                .apply()
            Toast.makeText(getApplication(), "Downloading update...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(getApplication(), "Failed to start download: ${e.message}", Toast.LENGTH_LONG).show() }
    }

    companion object { private const val MAX_UNDO_STACK_SIZE = 50 }
}
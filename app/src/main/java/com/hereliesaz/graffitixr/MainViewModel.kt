package com.hereliesaz.graffitixr

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.GithubRelease
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.slam.SlamManager
import com.hereliesaz.graffitixr.utils.BackgroundRemover
import com.hereliesaz.graffitixr.utils.BitmapUtils
import com.hereliesaz.graffitixr.utils.OnboardingManager
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import com.google.ar.core.Session.FeatureMapQuality
import com.google.ar.core.Pose
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded

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
    private val slamManager = SlamManager()

    private val undoStack = mutableListOf<UiState>()
    private val redoStack = mutableListOf<UiState>()

    val uiState: StateFlow<UiState> = savedStateHandle.getStateFlow("uiState", UiState())

    private val _captureEvent = MutableSharedFlow<CaptureEvent>()
    val captureEvent = _captureEvent.asSharedFlow()

    private val _feedbackEvent = MutableSharedFlow<FeedbackEvent>()
    val feedbackEvent = _feedbackEvent.asSharedFlow()

    private val _tapFeedback = MutableStateFlow<TapFeedback?>(null)
    val tapFeedback = _tapFeedback.asStateFlow()

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

    fun toggleMappingMode() {
        val next = !uiState.value.isMappingMode
        updateState(uiState.value.copy(isMappingMode = next), isUndoable = false)
        arRenderer?.showMiniMap = next

        if (!next) {
            slamManager.reset()
        }
    }

    fun updateMappingScore(score: Float) {
        if (abs(score - uiState.value.mappingQualityScore) > 0.05f) {
            updateState(uiState.value.copy(mappingQualityScore = score), isUndoable = false)
        }
    }

    fun finalizeMap() {
        val session = arRenderer?.session ?: return
        val cameraPose = session.update().camera.pose
        val anchorPose = cameraPose.compose(Pose.makeTranslation(0f, 0f, -0.5f))
        val anchor = session.createAnchor(anchorPose)

        updateState(uiState.value.copy(isHostingAnchor = true), isUndoable = false)

        slamManager.hostAnchor(
            session = session,
            anchor = anchor,
            onSuccess = { id ->
                updateState(uiState.value.copy(isHostingAnchor = false, cloudAnchorId = id, isMappingMode = false), isUndoable = false)
                arRenderer?.showMiniMap = false
                Toast.makeText(getApplication(), "Map Saved to Cloud", Toast.LENGTH_LONG).show()
            },
            onError = { error ->
                updateState(uiState.value.copy(isHostingAnchor = false), isUndoable = false)
                Toast.makeText(getApplication(), "Hosting Failed: $error", Toast.LENGTH_SHORT).show()
            }
        )
    }

    fun updateArtworkBounds(bounds: RectF) {
        val current = _artworkBounds.value
        if (current == null || abs(current.left - bounds.left) > 10 || abs(current.top - bounds.top) > 10 || abs(current.right - bounds.right) > 10 || abs(current.bottom - bounds.bottom) > 10) {
            _artworkBounds.value = bounds
        }
    }

    fun onCaptureShutterClicked() {
        val state = uiState.value
        if (state.isCapturingTarget && state.editorMode == EditorMode.AR) {
            when (state.captureStep) {
                CaptureStep.INSTRUCTION -> updateState(state.copy(captureStep = CaptureStep.FRONT), isUndoable = false)
                CaptureStep.GRID_CONFIG -> updateState(state.copy(captureStep = CaptureStep.ASK_GPS), isUndoable = false)
                else -> arRenderer?.triggerCapture()
            }
        } else {
            viewModelScope.launch { _captureEvent.emit(CaptureEvent.RequestCapture) }
        }
    }

    fun onTargetCreationMethodSelected(mode: TargetCreationMode) {
        val nextStep = when (mode) {
            TargetCreationMode.CAPTURE -> CaptureStep.ASK_GPS
            TargetCreationMode.GUIDED_GRID -> CaptureStep.GRID_CONFIG
            TargetCreationMode.GUIDED_POINTS -> CaptureStep.ASK_GPS
            TargetCreationMode.MULTI_POINT_CALIBRATION -> CaptureStep.PHOTO_SEQUENCE
            TargetCreationMode.RECTIFY -> CaptureStep.INSTRUCTION
            else -> CaptureStep.ASK_GPS
        }
        val newScale = if (mode != TargetCreationMode.CAPTURE && mode != TargetCreationMode.MULTI_POINT_CALIBRATION && mode != TargetCreationMode.RECTIFY) 0.5f else uiState.value.arObjectScale
        updateState(uiState.value.copy(targetCreationMode = mode, captureStep = nextStep, isGridGuideVisible = mode == TargetCreationMode.GUIDED_GRID || mode == TargetCreationMode.GUIDED_POINTS, arObjectScale = newScale), isUndoable = false)
        updateGuideOverlay()
    }

    fun onGpsDecision(enableGps: Boolean) {
        val mode = uiState.value.targetCreationMode
        val nextStep = if (mode == TargetCreationMode.MULTI_POINT_CALIBRATION) CaptureStep.CALIBRATION_POINT_1 else if (enableGps) CaptureStep.CALIBRATION_POINT_1 else { if (mode == TargetCreationMode.CAPTURE || mode == TargetCreationMode.RECTIFY) CaptureStep.FRONT else CaptureStep.GUIDED_CAPTURE }
        updateState(uiState.value.copy(isGpsMarkingEnabled = enableGps, captureStep = nextStep), isUndoable = false)
    }

    fun onPhotoSequenceFinished() { updateState(uiState.value.copy(captureStep = CaptureStep.ASK_GPS), isUndoable = false) }

    fun onCalibrationPointCaptured() {
        viewModelScope.launch {
            setLoading(true); _feedbackEvent.emit(FeedbackEvent.VibrateSingle); delay(1000)
            val snapshot = com.hereliesaz.graffitixr.data.CalibrationSnapshot(gpsData = getGpsData(), sensorData = getSensorData(), poseMatrix = arRenderer?.arImagePose?.toList(), timestamp = System.currentTimeMillis())
            val currentSnapshots = uiState.value.calibrationSnapshots + snapshot
            val nextStep = when (uiState.value.captureStep) { CaptureStep.CALIBRATION_POINT_1 -> CaptureStep.CALIBRATION_POINT_2; CaptureStep.CALIBRATION_POINT_2 -> CaptureStep.CALIBRATION_POINT_3; CaptureStep.CALIBRATION_POINT_3 -> CaptureStep.CALIBRATION_POINT_4; else -> CaptureStep.REVIEW }
            _feedbackEvent.emit(FeedbackEvent.VibrateDouble); setLoading(false)
            updateState(uiState.value.copy(calibrationSnapshots = currentSnapshots, captureStep = nextStep), isUndoable = false)
            if (nextStep == CaptureStep.REVIEW) processCapturedTargets(uiState.value.capturedTargetImages)
        }
    }

    fun onGridConfigChanged(rows: Int, cols: Int) {
        val safeRows = rows.coerceIn(1, 10); val safeCols = cols.coerceIn(1, 10)
        updateState(uiState.value.copy(gridRows = safeRows, gridCols = safeCols), isUndoable = false)
        updateGuideOverlay()
    }

    private fun updateGuideOverlay() {
        val state = uiState.value
        val bitmap = when (state.targetCreationMode) {
            TargetCreationMode.GUIDED_GRID -> com.hereliesaz.graffitixr.utils.GuideGenerator.generateGrid(state.gridRows, state.gridCols)
            TargetCreationMode.GUIDED_POINTS -> com.hereliesaz.graffitixr.utils.GuideGenerator.generateFourXs()
            else -> null
        }
        if (bitmap != null) { arRenderer?.guideBitmap = bitmap; arRenderer?.showGuide = true } else arRenderer?.showGuide = false
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

    fun onTrackingFailure(message: String?) { updateState(uiState.value.copy(qualityWarning = message), isUndoable = false) }
    fun onCreateTargetClicked() { arRenderer?.resetAnchor(); updateState(uiState.value.copy(isCapturingTarget = true, captureStep = CaptureStep.ADVICE, capturedTargetImages = emptyList(), targetCreationMode = TargetCreationMode.CAPTURE, arState = ArState.SEARCHING), isUndoable = false) }

    fun onFrameCaptured(bitmap: Bitmap) {
        val newImages = uiState.value.capturedTargetImages + bitmap
        val mode = uiState.value.targetCreationMode
        var nextStep = if (mode == TargetCreationMode.MULTI_POINT_CALIBRATION) CaptureStep.PHOTO_SEQUENCE else if (mode == TargetCreationMode.CAPTURE) { when (uiState.value.captureStep) { CaptureStep.FRONT -> CaptureStep.LEFT; CaptureStep.LEFT -> CaptureStep.RIGHT; CaptureStep.RIGHT -> CaptureStep.UP; CaptureStep.UP -> CaptureStep.DOWN; else -> CaptureStep.REVIEW } } else if (mode == TargetCreationMode.RECTIFY) { if (uiState.value.captureStep == CaptureStep.FRONT) CaptureStep.RECTIFY else CaptureStep.REVIEW } else CaptureStep.REVIEW
        viewModelScope.launch { _feedbackEvent.emit(FeedbackEvent.VibrateSingle) }
        if (nextStep == CaptureStep.PHOTO_SEQUENCE) { updateState(uiState.value.copy(capturedTargetImages = newImages), isUndoable = false); Toast.makeText(getApplication(), "Photo captured (${newImages.size})", Toast.LENGTH_SHORT).show() }
        else if (nextStep != CaptureStep.REVIEW) updateState(uiState.value.copy(capturedTargetImages = newImages, captureStep = nextStep), isUndoable = false)
        else { arRenderer?.showGuide = false; updateState(uiState.value.copy(capturedTargetImages = newImages, isLoading = true, isGridGuideVisible = false), isUndoable = false); processCapturedTargets(newImages) }
    }

    private fun processCapturedTargets(images: List<Bitmap>) {
        viewModelScope.launch {
            _feedbackEvent.emit(FeedbackEvent.VibrateDouble); val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build()).build())
            withContext(Dispatchers.IO) {
                val calibrationSnapshots = uiState.value.calibrationSnapshots
                if (calibrationSnapshots.isNotEmpty()) {
                    val avgLat = calibrationSnapshots.mapNotNull { it.gpsData?.latitude }.average()
                    val avgLng = calibrationSnapshots.mapNotNull { it.gpsData?.longitude }.average()
                    val avgAlt = calibrationSnapshots.mapNotNull { it.gpsData?.altitude }.average()
                    val avgAzimuthDeg = Math.toDegrees(calibrationSnapshots.mapNotNull { it.sensorData?.azimuth?.toDouble() }.average())
                    if (!avgLat.isNaN()) arRenderer?.createGeospatialAnchor(avgLat, avgLng, avgAlt, avgAzimuthDeg)
                }
                val frontImage = images.firstOrNull() ?: return@withContext
                segmenter.process(InputImage.fromBitmap(frontImage, 0)).addOnSuccessListener { result ->
                    viewModelScope.launch(Dispatchers.IO) {
                        var maskUri: Uri? = null
                        result.subjects.firstOrNull()?.let { subject ->
                            val maskBitmap = createBitmap(subject.width, subject.height, Bitmap.Config.ARGB_8888)
                            subject.confidenceMask?.let { buffer -> buffer.rewind(); val pixels = IntArray(subject.width * subject.height); for (i in pixels.indices) { if (buffer.hasRemaining()) { val alpha = (buffer.get() * 255).toInt().coerceIn(0, 255); pixels[i] = (alpha shl 24) or 0x00FFFFFF } }; maskBitmap.setPixels(pixels, 0, subject.width, 0, 0, subject.width, subject.height) }
                            maskUri = saveBitmapToCache(maskBitmap, "mask")
                        }
                        arRenderer?.setAugmentedImageDatabase(images); withContext(Dispatchers.Main) { updateState(uiState.value.copy(capturedTargetImages = images, targetMaskUri = maskUri, captureStep = CaptureStep.REVIEW, targetCreationState = TargetCreationState.SUCCESS, isArTargetCreated = true, arState = ArState.SEARCHING, isLoading = false)); updateDetectedKeypoints(); if (uiState.value.overlayImageUri == null) updateState(uiState.value.copy(showImagePicker = true), isUndoable = false) }
                    }
                }.addOnFailureListener { viewModelScope.launch(Dispatchers.Main) { arRenderer?.setAugmentedImageDatabase(images); updateState(uiState.value.copy(capturedTargetImages = images, captureStep = CaptureStep.REVIEW, targetCreationState = TargetCreationState.SUCCESS, isArTargetCreated = true, arState = ArState.SEARCHING, isLoading = false)); updateDetectedKeypoints() } }
            }
        }
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
    }

    fun onRetakeCapture() { updateState(uiState.value.copy(capturedTargetImages = emptyList(), captureStep = CaptureStep.FRONT), isUndoable = false) }
    fun onDrawingPathFinished(points: List<Pair<Float, Float>>) { updateState(uiState.value.copy(drawingPaths = uiState.value.drawingPaths + listOf(points))); viewModelScope.launch { val overlayUri = uiState.value.overlayImageUri ?: return@launch; val (w, h) = BitmapUtils.getBitmapDimensions(getApplication(), overlayUri); if (w == 0) return@launch; val progress = (com.hereliesaz.graffitixr.utils.calculateProgress(uiState.value.drawingPaths.map { pl -> androidx.compose.ui.graphics.Path().apply { if (pl.isNotEmpty()) { moveTo(pl.first().first, pl.first().second); for (i in 1 until pl.size) lineTo(pl[i].first, pl[i].second) } } }, createBitmap(w, h, Bitmap.Config.ARGB_8888)).toFloat() / (w * h).toFloat()) * 100; updateState(uiState.value.copy(progressPercentage = progress), isUndoable = false) } }

    fun exportProjectToUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            setLoading(true); try {
            val savedTargetUris = uiState.value.capturedTargetImages.mapNotNull { saveBitmapToCache(it) }
            val projectData = ProjectData(backgroundImageUri = uiState.value.backgroundImageUri, overlayImageUri = uiState.value.overlayImageUri, targetImageUris = savedTargetUris, refinementPaths = uiState.value.refinementPaths, opacity = uiState.value.opacity, contrast = uiState.value.contrast, saturation = uiState.value.saturation, colorBalanceR = uiState.value.colorBalanceR, colorBalanceG = uiState.value.colorBalanceG, colorBalanceB = uiState.value.colorBalanceB, scale = uiState.value.scale, rotationZ = uiState.value.rotationZ, rotationX = uiState.value.rotationX, rotationY = uiState.value.rotationY, offset = uiState.value.offset, blendMode = uiState.value.blendMode, fingerprint = uiState.value.fingerprintJson?.let { Json.decodeFromString<Fingerprint>(it) }, drawingPaths = uiState.value.drawingPaths, progressPercentage = uiState.value.progressPercentage, evolutionImageUris = uiState.value.evolutionCaptureUris, gpsData = getGpsData(), sensorData = getSensorData(), calibrationSnapshots = uiState.value.calibrationSnapshots)
            projectManager.exportProjectToZip(uri, projectData)
            withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Project exported", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(getApplication(), "Export failed", Toast.LENGTH_SHORT).show() } } finally { setLoading(false) }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateState(uiState.value.copy(isCheckingForUpdate = true, updateStatusMessage = null), isUndoable = false)
            withContext(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://api.github.com/repos/HereLiesAZ/GraffitiXR/releases")
                    val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
                    connection.requestMethod = "GET"; connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.setRequestProperty("User-Agent", "GraffitiXR-App")
                    if (connection.responseCode == 200) {
                        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                        val release = Json { ignoreUnknownKeys = true }.decodeFromString<List<GithubRelease>>(response).firstOrNull { it.prerelease }
                        withContext(Dispatchers.Main) {
                            if (release != null) {
                                val remoteVersion = release.assets.firstOrNull { it.browser_download_url.endsWith(".apk") }?.browser_download_url?.substringAfterLast("/")?.let { Regex("""GraffitiXR-(\d+\.\d+\.\d+\.\d+)-debug\.apk""").find(it)?.groupValues?.get(1) } ?: release.tag_name
                                val message = if (isNewerVersion(BuildConfig.VERSION_NAME, remoteVersion)) "New update: $remoteVersion" else "Latest build installed."
                                updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = message, latestRelease = release), isUndoable = false)
                            } else updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "No experimental builds."), isUndoable = false)
                        }
                    } else withContext(Dispatchers.Main) { updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "HTTP ${connection.responseCode}"), isUndoable = false) }
                } catch (e: Exception) { withContext(Dispatchers.Main) { updateState(uiState.value.copy(isCheckingForUpdate = false, updateStatusMessage = "Check failed"), isUndoable = false) } }
            }
        }
    }

    private fun isNewerVersion(cur: String, rem: String): Boolean {
        val v1 = cur.split(".").map { it.toIntOrNull() ?: 0 }; val v2 = rem.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until max(v1.size, v2.size)) { val p1 = v1.getOrElse(i) { 0 }; val p2 = v2.getOrElse(i) { 0 }; if (p2 > p1) return true; if (p1 > p2) return false }
        return false
    }

    fun installLatestUpdate() {
        val asset = uiState.value.latestRelease?.assets?.firstOrNull { it.browser_download_url.endsWith(".apk") } ?: return
        try {
            val request = DownloadManager.Request(asset.browser_download_url.toUri()).setTitle("GraffitiXR Update").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Update.apk")
            val id = (getApplication<Application>().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            getApplication<Application>().getSharedPreferences("secure_prefs", Context.MODE_PRIVATE).edit().putLong("update_download_id", id).apply()
            Toast.makeText(getApplication(), "Downloading update...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(getApplication(), "Download failed", Toast.LENGTH_SHORT).show() }
    }

    fun onMarkProgressToggled() { updateState(uiState.value.copy(isMarkingProgress = !uiState.value.isMarkingProgress)) }
    fun copyLayerModifications(id: String) { uiState.value.layers.find { it.id == id }?.let { clipboardLayer = it; Toast.makeText(getApplication(), "Modifications copied", Toast.LENGTH_SHORT).show() } }
    private var clipboardLayer: OverlayLayer? = null
    fun pasteLayerModifications(id: String) { clipboardLayer?.let { src -> val layers = uiState.value.layers.map { if (it.id == id) it.copy(scale = src.scale, rotationX = src.rotationX, rotationY = src.rotationY, rotationZ = src.rotationZ, offset = src.offset, opacity = src.opacity, brightness = src.brightness, contrast = src.contrast, saturation = src.saturation, colorBalanceR = src.colorBalanceR, colorBalanceG = src.colorBalanceG, colorBalanceB = src.colorBalanceB, curvesPoints = src.curvesPoints, blendMode = src.blendMode) else it }; updateState(uiState.value.copy(layers = layers)); if (id == uiState.value.activeLayerId) onLayerActivated(id); Toast.makeText(getApplication(), "Modifications pasted", Toast.LENGTH_SHORT).show() } }

    fun onGestureStart() { if (undoStack.isNotEmpty()) undoStack[undoStack.lastIndex] = uiState.value else undoStack.add(uiState.value); redoStack.clear() }
    fun onGestureEnd() { }
}
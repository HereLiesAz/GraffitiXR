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
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.GithubRelease
import com.hereliesaz.graffitixr.data.OverlayLayer
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
import org.opencv.android.OpenCVLoader
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
                    updateState(state.copy(captureStep = CaptureStep.ASK_GPS), isUndoable = false)
                }
                CaptureStep.GUIDED_CAPTURE -> {
                    arRenderer?.triggerCapture()
                }
                CaptureStep.PHOTO_SEQUENCE -> {
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
            TargetCreationMode.CAPTURE -> CaptureStep.ASK_GPS
            TargetCreationMode.GUIDED_GRID -> CaptureStep.GRID_CONFIG
            TargetCreationMode.GUIDED_POINTS -> CaptureStep.ASK_GPS
            TargetCreationMode.MULTI_POINT_CALIBRATION -> CaptureStep.PHOTO_SEQUENCE
            TargetCreationMode.RECTIFY -> CaptureStep.INSTRUCTION
            else -> CaptureStep.ASK_GPS // Sentinel for future modes
        }

        val newScale = if (mode != TargetCreationMode.CAPTURE && mode != TargetCreationMode.MULTI_POINT_CALIBRATION && mode != TargetCreationMode.RECTIFY) 0.5f else uiState.value.arObjectScale

        updateState(uiState.value.copy(
            targetCreationMode = mode,
            captureStep = nextStep,
            isGridGuideVisible = mode == TargetCreationMode.GUIDED_GRID || mode == TargetCreationMode.GUIDED_POINTS,
            arObjectScale = newScale
        ), isUndoable = false)

        if (mode == TargetCreationMode.GUIDED_GRID || mode == TargetCreationMode.GUIDED_POINTS) {
            updateGuideOverlay()
        } else {
            arRenderer?.showGuide = false
        }
    }

    fun onGpsDecision(enableGps: Boolean) {
        val mode = uiState.value.targetCreationMode
        val nextStep = if (mode == TargetCreationMode.MULTI_POINT_CALIBRATION) {
             CaptureStep.CALIBRATION_POINT_1
        } else if (enableGps) {
            CaptureStep.CALIBRATION_POINT_1
        } else {
            if (mode == TargetCreationMode.CAPTURE) CaptureStep.FRONT else if (mode == TargetCreationMode.RECTIFY) CaptureStep.FRONT else CaptureStep.GUIDED_CAPTURE
        }

        updateState(uiState.value.copy(
            isGpsMarkingEnabled = enableGps,
            captureStep = nextStep
        ), isUndoable = false)
    }

    fun onPhotoSequenceFinished() {
        updateState(uiState.value.copy(captureStep = CaptureStep.ASK_GPS), isUndoable = false)
    }

    fun onCalibrationPointCaptured() {
        viewModelScope.launch {
            // Simulate waiting for stabilization and data collection
            setLoading(true)
            _feedbackEvent.emit(FeedbackEvent.VibrateSingle)
            delay(1000) // Wait for "stabilization"

            // Capture Sensors and Pose (No image capture)
            val gps = getGpsData()
            val sensors = getSensorData()

            val snapshot = com.hereliesaz.graffitixr.data.CalibrationSnapshot(
                gpsData = gps,
                sensorData = sensors,
                poseMatrix = arRenderer?.arImagePose?.toList(),
                timestamp = System.currentTimeMillis()
            )

            val currentSnapshots = uiState.value.calibrationSnapshots + snapshot

            // Auto-advance step
            val currentStep = uiState.value.captureStep
            val nextStep = when (currentStep) {
                CaptureStep.CALIBRATION_POINT_1 -> CaptureStep.CALIBRATION_POINT_2
                CaptureStep.CALIBRATION_POINT_2 -> CaptureStep.CALIBRATION_POINT_3
                CaptureStep.CALIBRATION_POINT_3 -> CaptureStep.CALIBRATION_POINT_4
                CaptureStep.CALIBRATION_POINT_4 -> CaptureStep.REVIEW
                else -> CaptureStep.REVIEW
            }

            _feedbackEvent.emit(FeedbackEvent.VibrateDouble)
            setLoading(false)

            updateState(uiState.value.copy(
                calibrationSnapshots = currentSnapshots,
                captureStep = nextStep
            ), isUndoable = false)

            if (nextStep == CaptureStep.REVIEW) {
                 processCapturedTargets(uiState.value.capturedTargetImages)
            }
        }
    }

    fun onGridConfigChanged(rows: Int, cols: Int) {
        // Sentinel Security: Limit grid dimensions to prevent excessive resource usage (DoS)
        val safeRows = rows.coerceIn(1, 10)
        val safeCols = cols.coerceIn(1, 10)
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

                arRenderer?.setAugmentedImageDatabase(uiState.value.capturedTargetImages)
                arRenderer?.setFingerprint(fingerprint)

                withContext(Dispatchers.Main) {
                    updateState(
                        uiState.value.copy(
                            isCapturingTarget = false,
                            targetCreationState = TargetCreationState.SUCCESS,
                            isArTargetCreated = true,
                            arState = ArState.SEARCHING,
                            fingerprintJson = fingerprintJson,
                            refinementPaths = emptyList(),    // Clear refinement paths
                            targetMaskUri = null              // Clear the mask URI
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

        var nextStep = CaptureStep.REVIEW

        // Special handling for PHOTO_SEQUENCE in Multi-Point Calibration
        if (mode == TargetCreationMode.MULTI_POINT_CALIBRATION && currentStep == CaptureStep.PHOTO_SEQUENCE) {
            // Stay in PHOTO_SEQUENCE, just add image. User must manually proceed.
            nextStep = CaptureStep.PHOTO_SEQUENCE
        } else if (uiState.value.isGpsMarkingEnabled && mode != TargetCreationMode.MULTI_POINT_CALIBRATION) {
             // Logic for other modes with GPS enabled (if they exist)
             nextStep = when (currentStep) {
                CaptureStep.CALIBRATION_POINT_1 -> CaptureStep.CALIBRATION_POINT_2
                CaptureStep.CALIBRATION_POINT_2 -> CaptureStep.CALIBRATION_POINT_3
                CaptureStep.CALIBRATION_POINT_3 -> CaptureStep.CALIBRATION_POINT_4
                CaptureStep.CALIBRATION_POINT_4 -> CaptureStep.REVIEW
                else -> CaptureStep.REVIEW
            }
        } else {
            nextStep = if (mode == TargetCreationMode.CAPTURE) {
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
            } else if (mode == TargetCreationMode.RECTIFY) {
                when(currentStep) {
                    CaptureStep.INSTRUCTION -> CaptureStep.FRONT
                    CaptureStep.FRONT -> CaptureStep.RECTIFY
                    else -> CaptureStep.REVIEW
                }
            } else if (mode == TargetCreationMode.MULTI_POINT_CALIBRATION) {
                 // Should not happen here for calibration points as they don't capture frames
                 CaptureStep.REVIEW
            } else {
                // For guided modes, 1 frame is enough
                CaptureStep.REVIEW
            }
        }

        viewModelScope.launch {
            _feedbackEvent.emit(FeedbackEvent.VibrateSingle)
        }

        // If staying in same step (Photo Sequence), just update images
        if (nextStep == CaptureStep.PHOTO_SEQUENCE) {
             updateState(uiState.value.copy(
                capturedTargetImages = newImages
            ), isUndoable = false)
             Toast.makeText(getApplication(), "Photo captured (${newImages.size})", Toast.LENGTH_SHORT).show()
        } else if (nextStep == CaptureStep.RECTIFY) {
            // Store captured image and move to RECTIFY screen
            updateState(uiState.value.copy(
                capturedTargetImages = newImages,
                captureStep = nextStep
            ), isUndoable = false)
        } else if (nextStep != CaptureStep.REVIEW) {
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
                // If we have calibration data, use it to create a geospatial anchor
                val calibrationSnapshots = uiState.value.calibrationSnapshots
                if (calibrationSnapshots.isNotEmpty()) {
                    val avgLat = calibrationSnapshots.mapNotNull { it.gpsData?.latitude }.average()
                    val avgLng = calibrationSnapshots.mapNotNull { it.gpsData?.longitude }.average()
                    val avgAlt = calibrationSnapshots.mapNotNull { it.gpsData?.altitude }.average()

                    // Convert azimuth (radians) to degrees
                    val avgAzimuthRad = calibrationSnapshots.mapNotNull { it.sensorData?.azimuth?.toDouble() }.average()
                    val avgAzimuthDeg = Math.toDegrees(avgAzimuthRad)

                    if (!avgLat.isNaN() && !avgLng.isNaN() && !avgAlt.isNaN()) {
                         arRenderer?.createGeospatialAnchor(avgLat, avgLng, avgAlt, avgAzimuthDeg)
                    }
                }

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
                    fingerprint = uiState.value.fingerprintJson?.let { Json.decodeFromString<Fingerprint>(it) },
                    drawingPaths = uiState.value.drawingPaths,
                    progressPercentage = uiState.value.progressPercentage,
                    evolutionImageUris = uiState.value.evolutionCaptureUris,
                    gpsData = getGpsData(),
                    sensorData = getSensorData(),
                    calibrationSnapshots = uiState.value.calibrationSnapshots
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
        val newScale = (currentScale * scaleFactor).coerceIn(0.01f, 10.0f)
        updateState(uiState.value.copy(arObjectScale = newScale, scale = newScale), isUndoable = false)
        updateActiveLayer { it.copy(scale = newScale) }
    }

    fun onRotationXChanged(delta: Float) {
        val newRot = uiState.value.rotationX + delta
        updateState(uiState.value.copy(rotationX = newRot), isUndoable = false)
        updateActiveLayer { it.copy(rotationX = newRot) }
    }

    fun onRotationYChanged(delta: Float) {
        val newRot = uiState.value.rotationY + delta
        updateState(uiState.value.copy(rotationY = newRot), isUndoable = false)
        updateActiveLayer { it.copy(rotationY = newRot) }
    }

    fun onRotationZChanged(delta: Float) {
        val currentRotation = uiState.value.rotationZ
        val newRot = currentRotation + delta
        updateState(uiState.value.copy(rotationZ = newRot), isUndoable = false)
        updateActiveLayer { it.copy(rotationZ = newRot) }
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

    private var unlockInstructionsJob: kotlinx.coroutines.Job? = null

    fun showUnlockInstructions() {
        unlockInstructionsJob?.cancel()
        unlockInstructionsJob = viewModelScope.launch {
            updateState(uiState.value.copy(showUnlockInstructions = true), isUndoable = false)
            delay(3000)
            updateState(uiState.value.copy(showUnlockInstructions = false), isUndoable = false)
        }
    }

    fun setTouchLocked(locked: Boolean) {
        if (locked) {
            showUnlockInstructions()
            updateState(uiState.value.copy(isTouchLocked = true), isUndoable = false)
        } else {
            unlockInstructionsJob?.cancel()
            updateState(uiState.value.copy(isTouchLocked = false, showUnlockInstructions = false), isUndoable = false)
        }
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
                        updateActiveLayer { it.copy(uri = newUri, backgroundRemovedUri = newUri) }
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
        // Sentinel Security: Clamp brightness to valid range
        val safeBrightness = brightness.coerceIn(-1f, 1f)
        updateState(uiState.value.copy(brightness = safeBrightness), isUndoable = false)
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
                    updateActiveLayer { it.copy(uri = restoreUri) }
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
                updateActiveLayer { it.copy(uri = newUri) }
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

        // Multi-Layer Support
        val newLayer = OverlayLayer(
            id = java.util.UUID.randomUUID().toString(),
            name = "Layer ${uiState.value.layers.size + 1}",
            uri = uri,
            originalUri = uri,
            // Reset transforms for new layer
            rotationY = 0f,
            rotationX = 0f,
            rotationZ = 0f,
            scale = 1f,
            offset = Offset.Zero
        )
        val newLayers = uiState.value.layers + newLayer

        updateState(uiState.value.copy(
            overlayImageUri = uri,
            originalOverlayImageUri = uri,
            backgroundRemovedImageUri = null,
            isLineDrawing = false,
            showDoubleTapHint = showHint,
            activeRotationAxis = RotationAxis.Y,
            layers = newLayers,
            activeLayerId = newLayer.id,
            // Also reset global controls to match new layer
            rotationY = 0f,
            rotationX = 0f,
            rotationZ = 0f,
            scale = 1f,
            offset = Offset.Zero
        ))
    }

    fun onLayerActivated(layerId: String) {
        val layer = uiState.value.layers.find { it.id == layerId } ?: return

        // Sync layer properties to UI controls
        updateState(uiState.value.copy(
            activeLayerId = layerId,
            overlayImageUri = layer.uri,
            originalOverlayImageUri = layer.originalUri,
            backgroundRemovedImageUri = layer.backgroundRemovedUri,
            scale = layer.scale,
            rotationX = layer.rotationX,
            rotationY = layer.rotationY,
            rotationZ = layer.rotationZ,
            offset = layer.offset,
            opacity = layer.opacity,
            brightness = layer.brightness,
            contrast = layer.contrast,
            saturation = layer.saturation,
            colorBalanceR = layer.colorBalanceR,
            colorBalanceG = layer.colorBalanceG,
            colorBalanceB = layer.colorBalanceB,
            curvesPoints = layer.curvesPoints,
            blendMode = layer.blendMode
        ), isUndoable = false)
    }

    fun onLayerReordered(newOrderIds: List<String>) {
        val currentLayers = uiState.value.layers
        // Reorder current layers to match newOrderIds
        // Note: newOrderIds might be partial or contain only relocatable items.
        // But since all our layers are relocatable, we can assume 1:1 map.

        val reorderedLayers = newOrderIds.mapNotNull { id ->
            currentLayers.find { it.id == id }
        }

        // If mapNotNull filtered out items (shouldn't happen in normal flow), append leftovers or handle error.
        // For safety, we only update if sizes match.
        if (reorderedLayers.size == currentLayers.size) {
            updateState(uiState.value.copy(layers = reorderedLayers))
        }
    }

    fun onLayerRenamed(layerId: String, newName: String) {
        val updatedLayers = uiState.value.layers.map {
            if (it.id == layerId) it.copy(name = newName) else it
        }
        updateState(uiState.value.copy(layers = updatedLayers))
    }

    fun onLayerDuplicated(layerId: String) {
        val layer = uiState.value.layers.find { it.id == layerId } ?: return
        val newLayer = layer.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = "${layer.name} Copy"
        )
        val updatedLayers = uiState.value.layers + newLayer
        updateState(uiState.value.copy(layers = updatedLayers, activeLayerId = newLayer.id))
    }

    fun onLayerRemoved(layerId: String) {
        val updatedLayers = uiState.value.layers.filter { it.id != layerId }
        val nextActiveId = if (updatedLayers.isNotEmpty()) updatedLayers.last().id else null

        // If removing the last layer, clear main overlay URIs
        val overlayUri = if (updatedLayers.isEmpty()) null else uiState.value.overlayImageUri

        updateState(uiState.value.copy(
            layers = updatedLayers,
            activeLayerId = nextActiveId,
            overlayImageUri = overlayUri
        ))
    }

    // Clipboard for layer modifications
    private var clipboardLayer: OverlayLayer? = null

    fun copyLayerModifications(layerId: String) {
        val layer = uiState.value.layers.find { it.id == layerId } ?: return
        clipboardLayer = layer
        Toast.makeText(getApplication(), "Layer modifications copied", Toast.LENGTH_SHORT).show()
    }

    fun pasteLayerModifications(layerId: String) {
        val source = clipboardLayer ?: return
        val layers = uiState.value.layers.map { layer ->
            if (layer.id == layerId) {
                layer.copy(
                    scale = source.scale,
                    rotationX = source.rotationX,
                    rotationY = source.rotationY,
                    rotationZ = source.rotationZ,
                    offset = source.offset,
                    opacity = source.opacity,
                    brightness = source.brightness,
                    contrast = source.contrast,
                    saturation = source.saturation,
                    colorBalanceR = source.colorBalanceR,
                    colorBalanceG = source.colorBalanceG,
                    colorBalanceB = source.colorBalanceB,
                    curvesPoints = source.curvesPoints,
                    blendMode = source.blendMode
                )
            } else {
                layer
            }
        }
        updateState(uiState.value.copy(layers = layers))
        // If pasting to active layer, sync UI
        if (layerId == uiState.value.activeLayerId) {
            onLayerActivated(layerId)
        }
        Toast.makeText(getApplication(), "Modifications pasted", Toast.LENGTH_SHORT).show()
    }

    private fun updateActiveLayer(update: (OverlayLayer) -> OverlayLayer) {
        val activeId = uiState.value.activeLayerId ?: return
        val layers = uiState.value.layers.map {
            if (it.id == activeId) update(it) else it
        }
        updateState(uiState.value.copy(layers = layers), isUndoable = false)
    }

    fun onOpacityChanged(opacity: Float) {
        // Sentinel Security: Clamp opacity to 0..1
        val safeOpacity = opacity.coerceIn(0f, 1f)
        updateState(uiState.value.copy(opacity = safeOpacity), isUndoable = false)
        updateActiveLayer { it.copy(opacity = safeOpacity) }
    }

    fun onContrastChanged(contrast: Float) {
        // Sentinel Security: Clamp contrast to 0..2
        val safeContrast = contrast.coerceIn(0f, 2f)
        updateState(uiState.value.copy(contrast = safeContrast), isUndoable = false)
        updateActiveLayer { it.copy(contrast = safeContrast) }
    }

    fun onSaturationChanged(saturation: Float) {
        // Sentinel Security: Clamp saturation to 0..2
        val safeSaturation = saturation.coerceIn(0f, 2f)
        updateState(uiState.value.copy(saturation = safeSaturation), isUndoable = false)
        updateActiveLayer { it.copy(saturation = safeSaturation) }
    }

    fun onScaleChanged(scaleFactor: Float) {
        val currentScale = uiState.value.scale
        // Sentinel Security: Prevent scale explosion or collapse
        val newScale = (currentScale * scaleFactor).coerceIn(0.01f, 10.0f)
        updateState(uiState.value.copy(scale = newScale), isUndoable = false)
        updateActiveLayer { it.copy(scale = newScale) }
    }

    fun onOffsetChanged(offset: Offset) {
        val newOffset = uiState.value.offset + offset
        updateState(uiState.value.copy(offset = newOffset), isUndoable = false)
        updateActiveLayer { it.copy(offset = newOffset) }
    }

    fun onEditorModeChanged(mode: EditorMode) {
        val showOnboarding = !uiState.value.completedOnboardingModes.contains(mode)
        updateState(uiState.value.copy(editorMode = mode, showOnboardingDialogForMode = if (showOnboarding) mode else null, activeRotationAxis = RotationAxis.Z))
    }

    fun onOnboardingComplete(mode: EditorMode) {
        onboardingManager.completeMode(mode)
        val updatedModes = onboardingManager.getCompletedModes()
        val updatedState = uiState.value.copy(completedOnboardingModes = updatedModes, showOnboardingDialogForMode = null)
        updateState(updatedState)
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
        // Sentinel Security: Clamp color balance
        val safeValue = value.coerceIn(0f, 2f)
        updateState(uiState.value.copy(colorBalanceR = safeValue), isUndoable = false)
    }

    fun onColorBalanceGChanged(value: Float) {
        // Sentinel Security: Clamp color balance
        val safeValue = value.coerceIn(0f, 2f)
        updateState(uiState.value.copy(colorBalanceG = safeValue), isUndoable = false)
    }

    fun onColorBalanceBChanged(value: Float) {
        // Sentinel Security: Clamp color balance
        val safeValue = value.coerceIn(0f, 2f)
        updateState(uiState.value.copy(colorBalanceB = safeValue), isUndoable = false)
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
        updateActiveLayer { it.copy(blendMode = nextMode) }
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

    fun toggleImageLock() {
        val newState = !uiState.value.isImageLocked
        updateState(uiState.value.copy(isImageLocked = newState), isUndoable = false)
        val message = if (newState) "Image Locked" else "Image Unlocked"
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
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
                fingerprint = snapshot.fingerprintJson?.let { Json.decodeFromString<Fingerprint>(it) },
                drawingPaths = snapshot.drawingPaths,
                progressPercentage = snapshot.progressPercentage,
                evolutionImageUris = snapshot.evolutionCaptureUris,
                gpsData = getGpsData(),
                sensorData = getSensorData(),
                calibrationSnapshots = snapshot.calibrationSnapshots
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
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
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

    fun unwarpImage(points: List<Offset>) {
        val bitmap = uiState.value.capturedTargetImages.firstOrNull() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            // --- EXPLICIT MAT MANAGEMENT IN VIEWMODEL ---
            val mat = Mat()
            val warped = Mat()
            val transform = Mat()
            val srcMat = Mat(4, 2, CvType.CV_32F)
            val dstMat = Mat(4, 2, CvType.CV_32F)
            
            try {
                // Ensure library is loaded before any Mat usage
                if (!OpenCVLoader.initLocal()) {
                    Log.e("MainViewModel", "OpenCV not initialized for unwarpImage")
                    return@launch
                }

                Utils.bitmapToMat(bitmap, mat)

                val width = mat.cols().toDouble()
                val height = mat.rows().toDouble()

                val sortedByY = points.sortedBy { it.y }
                val topRow = sortedByY.take(2).sortedBy { it.x }
                val bottomRow = sortedByY.takeLast(2).sortedBy { it.x }

                val tl = topRow[0]; val tr = topRow[1]
                val bl = bottomRow[0]; val br = bottomRow[1]
                val sortedPoints = listOf(tl, tr, br, bl)

                val srcArray = DoubleArray(8)
                srcArray[0] = sortedPoints[0].x * width; srcArray[1] = sortedPoints[0].y * height
                srcArray[2] = sortedPoints[1].x * width; srcArray[3] = sortedPoints[1].y * height
                srcArray[4] = sortedPoints[2].x * width; srcArray[5] = sortedPoints[2].y * height
                srcArray[6] = sortedPoints[3].x * width; srcArray[7] = sortedPoints[3].y * height

                val brX = sortedPoints[2].x * width; val brY = sortedPoints[2].y * height
                val blX = sortedPoints[3].x * width; val blY = sortedPoints[3].y * height
                val trX = sortedPoints[1].x * width; val trY = sortedPoints[1].y * height
                val tlX = sortedPoints[0].x * width; val tlY = sortedPoints[0].y * height

                val widthA = sqrt((brX - blX).pow(2) + (brY - blY).pow(2))
                val widthB = sqrt((trX - tlX).pow(2) + (trY - tlY).pow(2))
                val maxWidth = max(widthA, widthB)

                val heightA = sqrt((trX - brX).pow(2) + (trY - brY).pow(2))
                val heightB = sqrt((tlX - blX).pow(2) + (tlY - blY).pow(2))
                val maxHeight = max(heightA, heightB)

                val dstArray = doubleArrayOf(
                    0.0, 0.0,
                    maxWidth - 1, 0.0,
                    maxWidth - 1, maxHeight - 1,
                    0.0, maxHeight - 1
                )

                srcMat.put(0, 0, floatArrayOf(
                    srcArray[0].toFloat(), srcArray[1].toFloat(),
                    srcArray[2].toFloat(), srcArray[3].toFloat(),
                    srcArray[4].toFloat(), srcArray[5].toFloat(),
                    srcArray[6].toFloat(), srcArray[7].toFloat()
                ))

                dstMat.put(0, 0, floatArrayOf(
                    dstArray[0].toFloat(), dstArray[1].toFloat(),
                    dstArray[2].toFloat(), dstArray[3].toFloat(),
                    dstArray[4].toFloat(), dstArray[5].toFloat(),
                    dstArray[6].toFloat(), dstArray[7].toFloat()
                ))

                val perspectiveTransform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
                perspectiveTransform.copyTo(transform)
                perspectiveTransform.release() // Initial transform Mat

                Imgproc.warpPerspective(mat, warped, transform, Size(maxWidth, maxHeight))

                val resultBitmap = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, resultBitmap)

                withContext(Dispatchers.Main) {
                    updateState(
                        uiState.value.copy(
                            capturedTargetImages = listOf(resultBitmap),
                            captureStep = CaptureStep.REVIEW
                        ),
                        isUndoable = false
                    )
                }

            } catch (e: Exception) {
                Log.e("MainViewModel", "Unwarp failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Failed to unwarp image", Toast.LENGTH_SHORT).show()
                }
            } finally {
                // --- AGGRESSIVE NATIVE RELEASE ---
                mat.release()
                warped.release()
                transform.release()
                srcMat.release()
                dstMat.release()
            }
        }
    }

    fun onRetakeCapture() {
        // Go back to capture screen
        updateState(
            uiState.value.copy(
                capturedTargetImages = emptyList(),
                captureStep = CaptureStep.FRONT
            ),
            isUndoable = false
        )
    }

    companion object { private const val MAX_UNDO_STACK_SIZE = 50 }
}

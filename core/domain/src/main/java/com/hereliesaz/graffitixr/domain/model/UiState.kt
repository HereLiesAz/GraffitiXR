package com.hereliesaz.graffitixr

import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import com.hereliesaz.graffitixr.data.OverlayLayer
import com.hereliesaz.graffitixr.data.ProjectData
import com.hereliesaz.graffitixr.data.RefinementPath
// NOTE: We assume EditorMode, ArState, etc. are defined in the root package
// or their respective files as indicated by the "Redeclaration" errors.
// If imports are missing, add them here based on where your original Enums live.
// Based on logs: com.hereliesaz.graffitixr.EditorMode exists.

data class UiState(
    // Global & Editor Modes
    val editorMode: EditorMode = EditorMode.AR,
    val showImagePicker: Boolean = false,
    val isLoading: Boolean = false,
    val hideUiForCapture: Boolean = false,

    // Project Management
    val availableProjects: List<ProjectData> = emptyList(),
    val showProjectList: Boolean = true,
    val currentProjectId: String? = null,
    val gpsData: com.hereliesaz.graffitixr.data.GpsData? = null,

    // Layers & Images
    val overlayImageUri: Uri? = null,
    val backgroundImageUri: Uri? = null,
    val layers: List<OverlayLayer> = emptyList(),
    val activeLayerId: String? = null,
    val isImageLocked: Boolean = false,

    // AR State
    // Fix: Using the ArState expected by MainScreen
    val arState: ArState = ArState.SEARCHING,
    val isArPlanesDetected: Boolean = false,
    val isArTargetCreated: Boolean = false,
    val qualityWarning: String? = null,
    val mappingQualityScore: Float = 0f,
    val isMappingMode: Boolean = false,
    val isHostingAnchor: Boolean = false,
    val fingerprintJson: String? = null,

    // Target Capture Flow
    val isCapturingTarget: Boolean = false,
    val captureStep: CaptureStep = CaptureStep.PREVIEW,
    val targetCreationMode: TargetCreationMode = TargetCreationMode.SINGLE_IMAGE,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList(),
    val calibrationSnapshots: List<com.hereliesaz.graffitixr.data.CalibrationSnapshot> = emptyList(),
    val targetMaskUri: Uri? = null,
    val gridRows: Int = 3,
    val gridCols: Int = 3,
    val isGridGuideVisible: Boolean = false,
    val captureFailureTimestamp: Long = 0L,

    // Refinement / Editing
    val isMarkingProgress: Boolean = false,
    val progressPercentage: Float = 0f,
    val drawingPaths: List<List<androidx.compose.ui.geometry.Offset>> = emptyList(),
    val refinementPaths: List<RefinementPath> = emptyList(),
    val detectedKeypoints: List<androidx.compose.ui.geometry.Offset> = emptyList(),
    val isRefinementEraser: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,

    // Interaction
    val isRightHanded: Boolean = true,
    val isTouchLocked: Boolean = false,
    val showUnlockInstructions: Boolean = false,
    val showOnboardingDialogForMode: EditorMode? = null,
    val showDoubleTapHint: Boolean = false,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val showRotationAxisFeedback: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val activeColorSeed: Int = 0,

    // Updates
    val updateStatusMessage: String? = null,
    val isCheckingForUpdate: Boolean = false
) {
    val activeLayer: OverlayLayer?
        get() = layers.find { it.id == activeLayerId } ?: layers.firstOrNull()
}
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset

/**
 * Represents the UI state for the Augmented Reality (AR) feature.
 *
 * @property isScanning Indicates if the SLAM mapping session is active.
 * @property pointCloudCount The number of points in the current point cloud (debug metric).
 * @property planeCount The number of detected planes (debug metric).
 * @property isTargetDetected True if the target image has been recognized and tracked.
 * @property trackingState A string representation of the current AR tracking state (e.g., "Tracking", "Paused").
 * @property showPointCloud Whether to render the point cloud visualization.
 * @property isFlashlightOn Whether the device flashlight is enabled.
 * @property tempCaptureBitmap A temporary bitmap used during the target creation flow (e.g., capture preview).
 * @property capturedTargetUris A list of URIs for saved target images.
 * @property capturedTargetImages A list of loaded Bitmaps for target images.
 */
data class ArUiState(
    val isScanning: Boolean = false,
    val pointCloudCount: Int = 0,
    val planeCount: Int = 0,
    val isTargetDetected: Boolean = false,
    val trackingState: String = "Initializing",
    val showPointCloud: Boolean = true,
    val isFlashlightOn: Boolean = false,
    val tempCaptureBitmap: Bitmap? = null,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList(),
    
    // NEW: Sensor and Location Data
    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null
)

/**
 * Represents the UI state for the Image Editor feature.
 *
 * @property activeLayerId The ID of the currently selected layer.
 * @property layers The list of image layers in the composition.
 * @property editorMode The current operational mode of the editor (e.g., EDIT, MOCKUP).
 * @property activeRotationAxis The axis currently being rotated (X, Y, or Z).
 * @property isRightHanded True for right-handed UI layout, false for left-handed.
 * @property isImageLocked If true, prevents accidental modification of the selected layer.
 * @property hideUiForCapture If true, hides UI elements to allow clean screen capture.
 * @property isLoading Indicates if a long-running operation is in progress.
 * @property mapPath The file path to a 3D map (for 3D Mockup mode).
 * @property backgroundBitmap The 2D background image.
 * @property backgroundImageUri The URI of the background image.
 * @property isEditingBackground True if the user is currently adjusting the background image.
 * @property backgroundScale The scale factor of the background image.
 * @property backgroundOffset The translation offset of the background image.
 * @property activePanel The currently active tool panel (e.g., LAYERS, ADJUST).
 * @property gestureInProgress True if a touch gesture is currently active.
 * @property showRotationAxisFeedback True to show the rotation axis visualization.
 * @property showDoubleTapHint True to show the double-tap hint dialog.
 * @property progressPercentage The progress of a loading operation (0.0 to 1.0).
 */
data class EditorUiState(
    val activeLayerId: String? = null,
    val layers: List<Layer> = emptyList(),
    val editorMode: EditorMode = EditorMode.EDIT,
    val activeRotationAxis: RotationAxis = RotationAxis.Z,
    val isRightHanded: Boolean = true,
    val isImageLocked: Boolean = false,
    val hideUiForCapture: Boolean = false,
    val isLoading: Boolean = false,

    // Background Fields
    val mapPath: String? = null,
    val backgroundBitmap: Bitmap? = null,
    val backgroundImageUri: String? = null,

    // Background Editing
    val isEditingBackground: Boolean = false,
    val backgroundScale: Float = 1f,
    val backgroundOffset: Offset = Offset.Zero,

    // Tool State
    val activePanel: EditorPanel = EditorPanel.NONE,
    val gestureInProgress: Boolean = false,
    val showRotationAxisFeedback: Boolean = false,
    val showDoubleTapHint: Boolean = false,
    val progressPercentage: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)

/**
 * Represents the steps in the Target Creation Flow.
 */
enum class CaptureStep {
    /** No capture in progress. */
    NONE,
    /** Capturing an image from the camera. */
    CAPTURE,
    /** Rectifying (unwarping) the captured image. */
    RECTIFY,
    /** Masking the image to select the target area. */
    MASK,
    /** Reviewing the final target image. */
    REVIEW
}

/**
 * Enum representing the available tool panels in the Editor.
 */
enum class EditorPanel {
    NONE, LAYERS, ADJUST, COLOR, BLEND
}

/**
 * Represents a single image layer in the Editor composition.
 *
 * @property id Unique identifier for the layer.
 * @property name Display name of the layer.
 * @property bitmap The image data.
 * @property offset The position of the layer on the canvas.
 * @property scale The scale factor of the layer.
 * @property rotationX Rotation around the X-axis (in degrees).
 * @property rotationY Rotation around the Y-axis (in degrees).
 * @property rotationZ Rotation around the Z-axis (in degrees).
 * @property isVisible Whether the layer is visible.
 * @property opacity The opacity of the layer (0.0 to 1.0).
 * @property blendMode The blending mode applied to this layer.
 * @property saturation Saturation adjustment (1.0 is normal).
 * @property contrast Contrast adjustment (1.0 is normal).
 * @property brightness Brightness adjustment (0.0 is normal).
 * @property colorBalanceR Red channel balance adjustment.
 * @property colorBalanceG Green channel balance adjustment.
 * @property colorBalanceB Blue channel balance adjustment.
 */
data class Layer(
    val id: String,
    val name: String,
    val bitmap: Bitmap,
    val uri: Uri, // Added for persistence
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val rotationX: Float = 0f,
    val rotationY: Float = 0f,
    val rotationZ: Float = 0f,
    val isVisible: Boolean = true,
    val isImageLocked: Boolean = false, // Added per-layer lock
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.SrcOver,

    // Image Adjustment
    val saturation: Float = 1f,
    val contrast: Float = 1f,
    val brightness: Float = 0f,
    val colorBalanceR: Float = 0f,
    val colorBalanceG: Float = 0f,
    val colorBalanceB: Float = 0f,

    // Warp Mesh (Flattened array of [x, y] coordinates)
    val warpMesh: List<Float>? = null
)

/**
 * Enum representing supported blend modes for layer composition.
 * Matches Android's [androidx.compose.ui.graphics.BlendMode] where possible.
 */
enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}

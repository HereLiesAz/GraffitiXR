// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/model/UiState.kt
package com.hereliesaz.graffitixr.common.model

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.nio.ByteBuffer

data class ArUiState(
    val isScanning: Boolean = false,
    val splatCount: Int = 0,
    val isTargetDetected: Boolean = false,
    val isFlashlightOn: Boolean = false,
    val lightLevel: Float = 1.0f,
    val tempCaptureBitmap: Bitmap? = null,
    val targetDepthBuffer: ByteBuffer? = null,
    val targetDepthWidth: Int = 0,
    val targetDepthHeight: Int = 0,
    val targetIntrinsics: FloatArray? = null,
    val capturedTargetUris: List<Uri> = emptyList(),
    val capturedTargetImages: List<Bitmap> = emptyList(),
    val gpsData: GpsData? = null,
    val sensorData: SensorData? = null,
    val pendingKeyframePath: String? = null,
    val unwarpPoints: List<Offset> = emptyList(),
    val activeUnwarpPointIndex: Int = -1,
    val magnifierPosition: Offset = Offset.Zero,
    val maskPath: androidx.compose.ui.graphics.Path? = null,
    val isCaptureRequested: Boolean = false,
    val undoCount: Int = 0,
    val gestureInProgress: Boolean = false,

    // Live diagnostic log lines for in-app debugging (newest entry replaces old)
    val diagLog: String? = null,

    // Contextual scan coaching hint. Non-null only during the scanning phase
    // (splatCount < 50000). Computed by ArViewModel based on what the user is
    // actually failing to do — low light, not moving, not pointing at surfaces.
    val scanHint: String? = null
)

enum class Tool {
    NONE, BRUSH, ERASER, BLUR, HEAL, BURN, DODGE, LIQUIFY, COLOR
}

enum class CaptureStep {
    NONE, CAPTURE, RECTIFY, MASK, REVIEW
}

enum class BlendMode {
    SrcOver, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn,
    HardLight, SoftLight, Difference, Exclusion, Hue, Saturation, Color, Luminosity,
    Clear, Src, Dst, DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop,
    Xor, Plus, Modulate
}

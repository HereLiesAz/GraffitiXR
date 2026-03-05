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
    val gestureInProgress: Boolean = false
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
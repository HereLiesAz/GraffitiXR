package com.hereliesaz.graffitixr.common.sensor

import java.nio.ByteBuffer

/**
 * A single camera frame from a [SensorSource].
 *
 * For [PixelFormat.RGBA_8888] frames, [pixels] holds the full RGBA buffer and
 * [yuvLayout] is null.
 *
 * For [PixelFormat.YUV_420_888] frames, [pixels] holds the concatenated Y+U+V
 * data and [yuvLayout] describes how to slice it into the three planes that
 * the native SLAM pipeline consumes.
 */
data class CameraFrame(
    val pixels: ByteBuffer,
    val format: PixelFormat,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val yuvLayout: YuvLayout? = null,
)

/**
 * Describes the byte layout of a single-buffer YUV frame.
 *
 * Offsets are positions within [CameraFrame.pixels]. Strides are in bytes.
 * [uvPixelStride] is 1 for fully-planar I420 (separate U and V planes) or 2
 * for semi-planar NV12/NV21 (interleaved UV).
 */
data class YuvLayout(
    val yOffset: Int,
    val ySize: Int,
    val yStride: Int,
    val uOffset: Int,
    val uSize: Int,
    val vOffset: Int,
    val vSize: Int,
    val uvStride: Int,
    val uvPixelStride: Int,
) {
    companion object {
        /**
         * Standard I420 layout for a width×height frame: Y plane (W·H bytes),
         * then U plane (W/2 · H/2), then V plane (W/2 · H/2). Tightly packed,
         * no row padding. Suitable for most software-decoded YUV sources.
         */
        fun i420(width: Int, height: Int): YuvLayout {
            val yLen = width * height
            val uvLen = (width / 2) * (height / 2)
            return YuvLayout(
                yOffset = 0,
                ySize = yLen,
                yStride = width,
                uOffset = yLen,
                uSize = uvLen,
                vOffset = yLen + uvLen,
                vSize = uvLen,
                uvStride = width / 2,
                uvPixelStride = 1,
            )
        }
    }
}

package com.hereliesaz.graffitixr.feature.ar

import java.nio.ByteBuffer

/** A tightly packed, display-oriented luminance frame. */
internal data class RotatedLuma(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Packs CameraX's Y plane (including row/pixel stride) and rotates it into display orientation.
 *
 * KPM calibration and pixels must describe the exact same image. CameraX reports intrinsics in the
 * raw sensor frame while [androidx.camera.core.ImageInfo.getRotationDegrees] describes the
 * sensor-to-display quarter-turn, so this performs the pixel half of the same transform
 * [com.hereliesaz.graffitixr.feature.ar.anchor.CaptureRotation.rotateIntrinsics] performs for
 * calibration.
 */
internal object LumaFrameTransform {

    fun packAndRotate(
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        rotationDegrees: Int,
    ): RotatedLuma {
        require(width > 0 && height > 0)
        require(rowStride > 0 && pixelStride > 0)

        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
            "rotation must be a multiple of 90 degrees"
        }

        val base = source.position()
        val lastIndex = base + (height - 1) * rowStride + (width - 1) * pixelStride
        require(lastIndex < source.limit()) {
            "Y plane does not contain the requested ${width}x$height strided image"
        }

        val outWidth = if (rotation == 90 || rotation == 270) height else width
        val outHeight = if (rotation == 90 || rotation == 270) width else height
        val out = ByteArray(width * height)
        val src = source.duplicate()

        for (y in 0 until height) {
            val row = base + y * rowStride
            for (x in 0 until width) {
                val value = src.get(row + x * pixelStride)
                val dx: Int
                val dy: Int
                when (rotation) {
                    90 -> {
                        dx = height - 1 - y
                        dy = x
                    }
                    180 -> {
                        dx = width - 1 - x
                        dy = height - 1 - y
                    }
                    270 -> {
                        dx = y
                        dy = width - 1 - x
                    }
                    else -> {
                        dx = x
                        dy = y
                    }
                }
                out[dy * outWidth + dx] = value
            }
        }

        return RotatedLuma(out, outWidth, outHeight)
    }
}

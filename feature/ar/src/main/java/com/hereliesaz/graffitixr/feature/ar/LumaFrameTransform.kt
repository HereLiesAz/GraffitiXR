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
    ): RotatedLuma = packCropAndRotate(
        source = source,
        sourceWidth = width,
        sourceHeight = height,
        rowStride = rowStride,
        pixelStride = pixelStride,
        cropLeft = 0,
        cropTop = 0,
        cropWidth = width,
        cropHeight = height,
        rotationDegrees = rotationDegrees,
    )

    /**
     * Packs only the CameraX crop rectangle, then rotates that cropped image into display
     * orientation. CameraX defines [androidx.camera.core.ImageProxy.getCropRect] in the
     * ImageProxy buffer's coordinate system, so crop offsets are applied before rotation.
     */
    fun packCropAndRotate(
        source: ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        cropLeft: Int,
        cropTop: Int,
        cropWidth: Int,
        cropHeight: Int,
        rotationDegrees: Int,
    ): RotatedLuma {
        require(sourceWidth > 0 && sourceHeight > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(cropLeft >= 0 && cropTop >= 0)
        require(cropWidth > 0 && cropHeight > 0)
        require(cropLeft + cropWidth <= sourceWidth)
        require(cropTop + cropHeight <= sourceHeight)

        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270) {
            "rotation must be a multiple of 90 degrees"
        }

        val base = source.position() + cropTop * rowStride + cropLeft * pixelStride
        val lastIndex = base + (cropHeight - 1) * rowStride + (cropWidth - 1) * pixelStride
        require(lastIndex < source.limit()) {
            "Y plane does not contain the requested cropped strided image"
        }

        val outWidth = if (rotation == 90 || rotation == 270) cropHeight else cropWidth
        val outHeight = if (rotation == 90 || rotation == 270) cropWidth else cropHeight
        val out = ByteArray(cropWidth * cropHeight)
        val src = source.duplicate()

        for (y in 0 until cropHeight) {
            val row = base + y * rowStride
            for (x in 0 until cropWidth) {
                val value = src.get(row + x * pixelStride)
                val dx: Int
                val dy: Int
                when (rotation) {
                    90 -> {
                        dx = cropHeight - 1 - y
                        dy = x
                    }
                    180 -> {
                        dx = cropWidth - 1 - x
                        dy = cropHeight - 1 - y
                    }
                    270 -> {
                        dx = y
                        dy = cropWidth - 1 - x
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

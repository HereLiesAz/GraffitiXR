// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/SketchProcessor.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Produces a pencil-sketch effect from a bitmap using the dodge-blend algorithm.
 *
 * Output is white-background with dark sketch lines, ARGB_8888.
 * Callers should render the result with MULTIPLY blend mode so the white areas
 * become visually transparent, leaving only the dark lines.
 */
object SketchProcessor {

    /**
     * Applies a pencil-sketch (dodge-blend) effect to [bitmap].
     *
     * @param bitmap    Source image (any config; read via OpenCV).
     * @param thickness Controls the Gaussian blur radius. Larger values produce softer/thicker lines.
     *                  Must be >= 1. The actual kernel size will be `(thickness * 2 + 1)` squared.
     * @return          A new ARGB_8888 bitmap with white background and dark sketch lines,
     *                  or null if processing fails.
     */
    fun sketchEffect(bitmap: Bitmap, thickness: Int = 5): Bitmap? {
        return try {
            val clampedThickness = thickness.coerceAtLeast(1)

            // Step 1: Load bitmap into an OpenCV Mat and convert RGBA → grayscale
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)

            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            src.release()

            // Step 2: Invert grayscale (creates a "negative" layer)
            val inverted = Mat()
            Core.bitwise_not(gray, inverted)

            // Step 3: Gaussian blur the inverted image
            val kernelSide = (clampedThickness * 2 + 1).toDouble()
            val blurred = Mat()
            Imgproc.GaussianBlur(inverted, blurred, Size(kernelSide, kernelSide), 0.0)
            inverted.release()

            // Step 4: Color-dodge blend
            //   result[i] = min(255, gray[i] * 255 / max(1, 255 - blurred[i]))
            //
            // Using float arithmetic to avoid integer overflow/truncation artefacts:
            //   denominator = 255 - blurred              (CV_8U subtraction from scalar)
            //   denominator = max(denominator, 1)         (avoid division by zero)
            //   numerator   = gray * 255.0               (promote to CV_32F)
            //   result_f    = numerator / denominator_f
            //   result      = min(result_f, 255) → CV_8U

            // 255 - blurred  (stays CV_8UC1 because Core.subtract(scalar, mat) saturates)
            val denominator8u = Mat()
            Core.subtract(Scalar(255.0), blurred, denominator8u)
            blurred.release()

            // Clamp denominator to [1, 255] so we never divide by zero
            val denominatorClamped = Mat()
            Core.max(denominator8u, Scalar(1.0), denominatorClamped)
            denominator8u.release()

            // Promote both operands to float for the division
            val grayFloat = Mat()
            gray.convertTo(grayFloat, CvType.CV_32F)
            gray.release()

            val denomFloat = Mat()
            denominatorClamped.convertTo(denomFloat, CvType.CV_32F)
            denominatorClamped.release()

            // numerator = grayFloat * 255
            val numeratorFloat = Mat()
            Core.multiply(grayFloat, Scalar(255.0), numeratorFloat)
            grayFloat.release()

            // divide element-wise
            val dodgeFloat = Mat()
            Core.divide(numeratorFloat, denomFloat, dodgeFloat)
            numeratorFloat.release()
            denomFloat.release()

            // Clamp to [0, 255] and convert back to CV_8U
            val dodgeClamped = Mat()
            Core.min(dodgeFloat, Scalar(255.0), dodgeClamped)
            dodgeFloat.release()

            val sketchGray = Mat()
            dodgeClamped.convertTo(sketchGray, CvType.CV_8U)
            dodgeClamped.release()

            // Step 5: Convert single-channel grayscale → RGBA for Bitmap output
            //   Grayscale value becomes all three RGB channels; alpha = 255 (fully opaque).
            //   White pixels (value=255) will act as "transparent" when MULTIPLY-blended.
            val sketchRgba = Mat()
            Imgproc.cvtColor(sketchGray, sketchRgba, Imgproc.COLOR_GRAY2RGBA)
            sketchGray.release()

            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sketchRgba, resultBitmap)
            sketchRgba.release()

            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

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
 * Output is an alpha-channel bitmap: dark sketch lines become opaque in [penColor],
 * while light areas become transparent. Callers should render with SrcOver blend mode.
 */
object SketchProcessor {

    init {
        NativeLibLoader.loadAll()
    }

    /**
     * Applies a pencil-sketch (dodge-blend) effect to [bitmap].
     *
     * @param bitmap    Source image (any config; read via OpenCV).
     * @param thickness Controls the Gaussian blur radius. Larger values produce softer/thicker lines.
     *                  Must be >= 1. The actual kernel size will be `(thickness * 2 + 1)` squared.
     * @param penColor  ARGB color for the sketch lines. Alpha of each output pixel is derived from
     *                  sketch darkness: dark pixels → opaque [penColor], light pixels → transparent.
     *                  Defaults to white for backward compatibility.
     * @return          A new ARGB_8888 bitmap with alpha-channel sketch lines in [penColor],
     *                  or null if processing fails.
     */
    fun sketchEffect(bitmap: Bitmap, thickness: Int = 5, penColor: Int = android.graphics.Color.WHITE): Bitmap? {
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
            // MUCH thicker lines: scale the thickness factor for larger kernels
            val kernelSide = (clampedThickness * 8 + 1).toDouble()
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

            // 255 - blurred via bitwise_not (equivalent for CV_8U, no scalar-first overload available)
            val denominator8u = Mat()
            Core.bitwise_not(blurred, denominator8u)
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

            // Step 5: Build ARGB output — alpha derived from sketch darkness, color = penColor
            //   Dark sketch pixels (gray ≈ 0)   → alpha ≈ 255 (fully opaque pen color)
            //   Light sketch pixels (gray ≈ 255) → alpha ≈ 0   (fully transparent)
            val w = sketchGray.cols(); val h = sketchGray.rows()
            val grayBytes = ByteArray(w * h)
            sketchGray.get(0, 0, grayBytes)
            sketchGray.release()

            val pr = android.graphics.Color.red(penColor)
            val pg = android.graphics.Color.green(penColor)
            val pb = android.graphics.Color.blue(penColor)
            val argbPixels = IntArray(w * h)
            for (i in argbPixels.indices) {
                val grayVal = grayBytes[i].toInt() and 0xFF
                val alpha = (255 - grayVal).coerceIn(0, 255)
                argbPixels[i] = android.graphics.Color.argb(alpha, pr, pg, pb)
            }
            val resultBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            resultBitmap.setPixels(argbPixels, 0, w, 0, 0, w, h)
            resultBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.dnn.Dnn
import org.opencv.dnn.Net
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

/** The image has no subject the segmenter can find. */
class SubjectNotFoundException : IllegalStateException("No subject found to isolate")

/** The bundled segmentation model could not be loaded or run. Nothing is wrong with the image. */
class SegmenterUnavailableException(cause: Throwable?) :
    IllegalStateException("Subject segmentation model unavailable", cause)

/**
 * Cuts the subject out of an image, making everything else transparent — so a projected design does
 * not paint its own background onto the wall.
 *
 * Runs U²-Net (small, `u2netp.onnx`, bundled in the APK; provenance and licenses in
 * `assets/u2netp.LICENSE.txt` and `docs/MODELS.md`) through OpenCV's DNN module, on the device and
 * offline. The network is loaded lazily, because most sessions never isolate, and used under a lock:
 * an OpenCV `Net` is not safe to run from two threads at once.
 */
@Singleton
class SubjectIsolator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val lock = Any()
    private var net: Net? = null

    private fun network(): Net = synchronized(lock) {
        net ?: run {
            val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
            Dnn.readNetFromONNX(MatOfByte(*bytes)).also {
                if (it.empty()) throw IllegalStateException("empty network")
                net = it
            }
        }
    }

    suspend fun isolate(bitmap: Bitmap): Result<IsolationResult> = withContext(Dispatchers.Default) {
        runCatching {
            val scaled = downsample(bitmap, 2048)
            val confidence = try {
                saliency(scaled)
            } catch (e: Exception) {
                throw SegmenterUnavailableException(e)
            }
            // Nothing salient enough to call a subject: isolating would make the WHOLE image
            // transparent, silently destroying the design rather than reporting the failure.
            if (confidence.count { it >= THRESHOLD } < confidence.size * MIN_SUBJECT_FRACTION) {
                throw SubjectNotFoundException()
            }
            IsolationResult(isolatedBitmap = applyConfidenceThreshold(scaled, confidence, threshold = THRESHOLD))
        }
    }

    /** Per-pixel subject confidence in [0, 1] at [bitmap]'s size (row-major). */
    private fun saliency(bitmap: Bitmap): FloatArray {
        val rgba = Mat()
        val rgb = Mat()
        val input = Mat()
        val map = Mat()
        val full = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.resize(rgb, input, Size(INPUT.toDouble(), INPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            input.convertTo(input, CvType.CV_32FC3, 1.0 / 255.0)
            // ImageNet normalisation, as the network was trained.
            Core.subtract(input, Scalar(0.485, 0.456, 0.406), input)
            Core.divide(input, Scalar(0.229, 0.224, 0.225), input)
            val blob = Dnn.blobFromImage(input)
            val out = synchronized(lock) {
                val n = network()
                n.setInput(blob)
                val outs = ArrayList<Mat>()
                n.forward(outs, n.unconnectedOutLayersNames)
                // The first output is the fused map (d0); the rest are side outputs.
                outs.first().also { first -> outs.drop(1).forEach { it.release() } }
            }
            blob.release()
            out.reshape(1, INPUT).copyTo(map)
            out.release()
            // Min-max normalised, as U²-Net's own inference does.
            val mm = Core.minMaxLoc(map)
            val range = (mm.maxVal - mm.minVal).takeIf { it > 1e-6 } ?: return FloatArray(bitmap.width * bitmap.height)
            Core.subtract(map, Scalar(mm.minVal), map)
            Core.multiply(map, Scalar(1.0 / range), map)
            Imgproc.resize(map, full, Size(bitmap.width.toDouble(), bitmap.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val result = FloatArray(bitmap.width * bitmap.height)
            full.get(0, 0, result)
            return result
        } finally {
            rgba.release(); rgb.release(); input.release(); map.release(); full.release()
        }
    }

    fun applyConfidenceThreshold(
        source: Bitmap,
        confidence: FloatArray,
        threshold: Float,
        featherRange: Float = 0.1f
    ): Bitmap {
        val w = source.width; val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            // Guard against a confidence array that doesn't match the pixel count: treat missing
            // entries as zero confidence rather than throwing ArrayIndexOutOfBounds.
            val conf = if (i < confidence.size) confidence[i] else 0f
            val alpha = when {
                conf >= threshold -> 255
                conf <= threshold - featherRange -> 0
                else -> ((conf - (threshold - featherRange)) / featherRange * 255f).toInt().coerceIn(0, 255)
            }
            pixels[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun downsample(bitmap: Bitmap, maxDim: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxDim) return bitmap
        val scale = maxDim.toFloat() / max
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }

    private companion object {
        const val MODEL_ASSET = "u2netp.onnx"
        /** The network's square input side. */
        const val INPUT = 320
        const val THRESHOLD = 0.5f
        /** Less than this share of the image above [THRESHOLD] is no subject. */
        const val MIN_SUBJECT_FRACTION = 0.005f
    }
}

data class IsolationResult(
    val isolatedBitmap: Bitmap,
)

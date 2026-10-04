// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/depth/DepthEstimator.kt
package com.hereliesaz.graffitixr.feature.ar.depth

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import timber.log.Timber
import java.io.File
import java.nio.FloatBuffer

/** A single monocular depth map, relative (unitless) unless the model is a metric variant. */
data class DepthMap(
    val width: Int,
    val height: Int,
    /** Row-major `height * width` values. Depth Anything outputs INVERSE depth: larger = nearer. */
    val data: FloatArray,
) {
    init {
        require(data.size == width * height) { "depth data ${data.size} != $width*$height" }
    }
}

/**
 * Monocular depth (Depth Anything V2-Small, int8) via ONNX Runtime, for the non-ARCore path.
 *
 * Why ONNX Runtime and not the native engine's OpenCV DNN: `cv::dnn` reliably imports the existing
 * CNNs (SuperPoint, ZeroDCE) but not a DINOv2-backbone ViT, and it can't run the int8 (QDQ) weights
 * that keep this model phone-sized; ORT runs them directly. It lives in Kotlin because its consumer,
 * [com.hereliesaz.graffitixr.feature.ar.SphereSlamStandaloneTrackingAnalyzer], is Kotlin.
 *
 * The model ships as ONNX external-data (`model_quantized.onnx` + `model_quantized.onnx_data`); ORT
 * resolves the sibling data file by name from the graph's directory, so both are extracted to
 * [Context.getFilesDir] and the session is opened from that path — asset bytes alone can't satisfy
 * the external reference.
 *
 * Fails soft: a missing asset or a load error leaves [isLoaded] false and [estimate] returning null,
 * exactly like the native engine's optional distortion head. All public methods are synchronized; ORT
 * sessions are not re-entrant.
 */
class DepthEstimator(private val appContext: Context) : AutoCloseable {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Extract the model (+ external weights) from assets to filesDir and open the ORT session. Safe
     * to call repeatedly; only the first successful call does work. Returns [isLoaded].
     */
    @Synchronized
    fun load(): Boolean {
        if (isLoaded) return true
        return try {
            val dir = File(appContext.filesDir, MODEL_DIR).apply { mkdirs() }
            val graph = File(dir, GRAPH_ASSET)
            // Both files are needed on disk together; the graph references the data file by name.
            if (!copyAssetIfNeeded(GRAPH_ASSET, graph) ||
                !copyAssetIfNeeded(DATA_ASSET, File(dir, DATA_ASSET))
            ) {
                Timber.w("DepthEstimator: model assets absent; depth disabled")
                return false
            }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // NNAPI accelerates where available but silently rejects some int8 graphs; fall back to
                // the default CPU/XNNPACK execution provider rather than failing the whole load.
                try {
                    addNnapi()
                } catch (t: Throwable) {
                    Timber.i("DepthEstimator: NNAPI unavailable, using CPU EP (%s)", t.message)
                }
            }
            session = environment.createSession(graph.absolutePath, options)
            env = environment
            isLoaded = true
            Timber.i("DepthEstimator: loaded (inputs=%s outputs=%s)", session?.inputNames, session?.outputNames)
            true
        } catch (t: Throwable) {
            Timber.w(t, "DepthEstimator: load failed; depth disabled")
            close()
            false
        }
    }

    /**
     * Estimate a depth map for [bitmap], downscaled to at most [outMaxDim] on its long side (plenty
     * for plane fitting / scale, and keeps the returned array small). Null if not loaded or inference
     * fails.
     */
    @Synchronized
    fun estimate(bitmap: Bitmap, outMaxDim: Int = DEFAULT_OUT_MAX_DIM): DepthMap? {
        val s = session ?: return null
        val environment = env ?: return null
        return try {
            val inputName = s.inputNames.first()
            val input = preprocess(bitmap, environment)
            input.use {
                s.run(mapOf(inputName to it)).use { results ->
                    val out = results[0] as OnnxTensor
                    readDepth(out, outMaxDim)
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "DepthEstimator: inference failed")
            null
        }
    }

    /** Build the `[1,3,518,518]` ImageNet-normalized NCHW tensor Depth Anything expects. */
    private fun preprocess(bitmap: Bitmap, environment: OrtEnvironment): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled !== bitmap) scaled.recycle()

        val plane = INPUT * INPUT
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            chw[i] = (r - MEAN[0]) / STD[0]
            chw[plane + i] = (g - MEAN[1]) / STD[1]
            chw[2 * plane + i] = (b - MEAN[2]) / STD[2]
        }
        return OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()),
        )
    }

    /** Read `predicted_depth` (`[1,H,W]` or `[1,1,H,W]`) and nearest-neighbour downscale to outMaxDim. */
    private fun readDepth(out: OnnxTensor, outMaxDim: Int): DepthMap {
        val shape = out.info.shape
        val h: Int
        val w: Int
        when (shape.size) {
            4 -> { h = shape[2].toInt(); w = shape[3].toInt() }
            3 -> { h = shape[1].toInt(); w = shape[2].toInt() }
            else -> error("unexpected depth output rank ${shape.size}")
        }
        val src = out.floatBuffer // length h*w (batch 1, single channel)
        val scale = maxOf(1, maxOf(h, w) / outMaxDim)
        val ow = w / scale
        val oh = h / scale
        val dst = FloatArray(ow * oh)
        for (y in 0 until oh) {
            val sy = y * scale
            for (x in 0 until ow) {
                dst[y * ow + x] = src.get(sy * w + x * scale)
            }
        }
        return DepthMap(ow, oh, dst)
    }

    private fun copyAssetIfNeeded(assetName: String, dest: File): Boolean {
        return try {
            // Re-copy only when size differs (first run, or a model swap on app update).
            val expected = appContext.assets.openFd(assetName).use { it.length }
            if (dest.exists() && dest.length() == expected) return true
            appContext.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (t: Throwable) {
            Timber.w(t, "DepthEstimator: asset %s unavailable", assetName)
            false
        }
    }

    @Synchronized
    override fun close() {
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        // OrtEnvironment is a process-global singleton; do not close it here.
        env = null
        isLoaded = false
    }

    companion object {
        const val INPUT = 518
        const val DEFAULT_OUT_MAX_DIM = 128
        private const val MODEL_DIR = "depth"
        private const val GRAPH_ASSET = "model_quantized.onnx"
        private const val DATA_ASSET = "model_quantized.onnx_data"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

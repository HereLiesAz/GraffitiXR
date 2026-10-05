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
    /** Row-major `height * width` values. MiDaS outputs INVERSE depth: larger = nearer. */
    val data: FloatArray,
) {
    init {
        require(data.size == width * height) { "depth data ${data.size} != $width*$height" }
    }
}

/**
 * Monocular depth (MiDaS v2.1 Small, 256×256, int8) via ONNX Runtime, for the non-ARCore path.
 *
 * MiDaS Small replaced Depth Anything V2-Small here: it is ~17 MB int8 vs ~38 MB and a single
 * self-contained graph (no external-data sidecar), and its coarse relative depth is all this path
 * needs — the output feeds plane-fit + relative scale and is downscaled to [DEFAULT_OUT_MAX_DIM]
 * anyway, so the ViT's extra fidelity was overkill for the bytes. MIT-licensed.
 *
 * Why ONNX Runtime and not the native engine's OpenCV DNN: ORT runs the int8 (QDQ) weights directly,
 * which keep the model phone-sized. It lives in Kotlin because its consumer,
 * [com.hereliesaz.graffitixr.feature.ar.SphereSlamStandaloneTrackingAnalyzer], is Kotlin. The model
 * is extracted from assets to [Context.getFilesDir] and the session opened from that path.
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

    /** Reason the last [load] failed, for the on-device diagnostic overlay. Null once loaded. */
    @Volatile
    var lastError: String? = null
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
            // Single self-contained file (no ONNX external-data sidecar for this model).
            if (!copyAssetIfNeeded(GRAPH_ASSET, graph)) {
                lastError = "assets absent (copy failed)"
                Timber.w("DepthEstimator: model assets absent; depth disabled")
                return false
            }
            val environment = OrtEnvironment.getEnvironment()
            // CPU execution provider only. NNAPI was tried here, but on some SoCs it does not reject
            // the int8 ViT graph gracefully: it registers, then aborts the whole process from inside
            // OrtSession.createSession (native SIGABRT in checkOrtStatus) — an abort Kotlin can't catch,
            // so the try/catch below never sees it and the app dies. The default CPU EP (MLAS) runs the
            // quantized model fine; depth is downscaled and off the hot path, so CPU latency is fine.
            val options = OrtSession.SessionOptions()
            session = environment.createSession(graph.absolutePath, options)
            env = environment
            isLoaded = true
            lastError = null
            Timber.i("DepthEstimator: loaded (inputs=%s outputs=%s)", session?.inputNames, session?.outputNames)
            true
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message?.take(160) ?: "no message"}"
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

    /** Build the `[1,3,256,256]` ImageNet-normalized NCHW tensor MiDaS Small expects. */
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
            // Copy once; MODEL_DIR carries a version so a model swap lands in a fresh dir. Do NOT use
            // AssetManager.openFd() to size-check — it throws on a COMPRESSED asset (.onnx is gz-packed
            // in the APK unless noCompress'd), which previously aborted the whole load. assets.open()
            // streams through the decompressor regardless. Copy via a temp file + rename so a crash
            // mid-copy can't leave a truncated model that then fails to parse forever.
            if (dest.exists() && dest.length() > 0L) return true
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            appContext.assets.open(assetName).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            dest.exists() && dest.length() > 0L
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
        const val INPUT = 256
        const val DEFAULT_OUT_MAX_DIM = 128
        private const val MODEL_DIR = "depth-v2"
        private const val GRAPH_ASSET = "midas_v21_small_256_int8.onnx"
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}

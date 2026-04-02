// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.graphics.Bitmap
import android.graphics.Color
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import com.hereliesaz.graffitixr.common.model.TonalPolarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

/**
 * Sealed class representing pipeline progress events emitted by [StencilProcessor.process].
 */
sealed class StencilProgress {
    data class Stage(val message: String, val fraction: Float) : StencilProgress()
    data class Done(val layers: List<StencilLayer>) : StencilProgress()
    data class Error(val message: String) : StencilProgress()
}

/**
 * Core image processing pipeline for Stencil Mode.
 *
 * Converts a pre-isolated bitmap (background already removed by SubjectIsolator) into
 * 1, 2, or 3 physical stencil layers via K-means clustering on the HSV V-channel:
 *   Layer 1 — SILHOUETTE  : solid subject outline (always present)
 *   Layer 2 — MIDTONE     : mid-luminance cluster (3-layer mode only)
 *   Layer 3 — HIGHLIGHT   : peak luminance cluster
 *
 * Island handling: OVERPAINT sequential-layering strategy — no bridging required.
 * Upper-layer holes are physically supported by the surrounding sheet material.
 *
 * All processing runs on Dispatchers.Default. Progress is emitted via Flow.
 */
class StencilProcessor @Inject constructor() {

    companion object {
        // Morphological closing kernel size for edge smoothing
        private const val MORPH_KERNEL_SIZE = 5
    }

    /**
     * Run the full binary stencil pipeline on [isolatedBitmap].
     * The bitmap must have background removed (transparent pixels = background).
     * SubjectIsolator is expected to have already downsampled to ≤2048px.
     * Always returns exactly two layers (Base + Detail).
     * Emits [StencilProgress] events; final event is [StencilProgress.Done] or [StencilProgress.Error].
     */
    fun process(
        isolatedBitmap: Bitmap,
        influence: Float = 0.5f
    ): Flow<StencilProgress> = flow {

        emit(StencilProgress.Stage("Preparing image…", 0.05f))

        if (isolatedBitmap.width < 200 || isolatedBitmap.height < 200) {
            emit(StencilProgress.Error("Source image too small for stencil. (Min 200px)"))
            return@flow
        }

        val result = runCatching {
            // Stage 1: Assessment
            emit(StencilProgress.Stage("Analysing brightness…", 0.15f))
            val polarity = assessTonalPolarity(isolatedBitmap)

            // Stage 2: Build subject mask from alpha channel
            emit(StencilProgress.Stage("Building mask…", 0.30f))
            val subjectMask = alphaToMask(isolatedBitmap)

            // Stage 3: Binary Pair Generation via K-means
            emit(StencilProgress.Stage("Analysing details…", 0.60f))
            val layers = kmeansLayers(isolatedBitmap, subjectMask, polarity, influence)

            // Stage 4: Morphological closing on detail layer
            emit(StencilProgress.Stage("Smoothing edges…", 0.85f))
            applyMorphClose(layers)
        }

        result.fold(
            onSuccess = { layers ->
                emit(StencilProgress.Stage("Done.", 1.0f))
                emit(StencilProgress.Done(layers))
            },
            onFailure = { e ->
                emit(StencilProgress.Error(e.message ?: "Unknown error in stencil pipeline"))
            }
        )
    }.flowOn(Dispatchers.Default)

    /**
     * Legacy method for single-layer extraction. Now returns the binary pair
     * but emphasizes the requested [type] in the progress event.
     */
    fun processSingle(
        isolatedBitmap: Bitmap,
        type: StencilLayerType, // Kept for interface compliance
        totalCount: StencilLayerCount, // Kept for interface compliance
        influence: Float = 0.5f
    ): Flow<StencilProgress> = process(isolatedBitmap, influence)

    /**
     * Assesses whether the [bitmap] subject is dark-dominant (Luminance ≤ 0.5)
     * or light-dominant (Luminance > 0.5).
     */
    fun assessTonalPolarity(bitmap: Bitmap): TonalPolarity {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var sumLuminance = 0f
        var count = 0
        for (pixel in pixels) {
            if (android.graphics.Color.alpha(pixel) > 0) {
                // Luminance = 0.299R + 0.587G + 0.114B
                val r = android.graphics.Color.red(pixel) / 255f
                val g = android.graphics.Color.green(pixel) / 255f
                val b = android.graphics.Color.blue(pixel) / 255f
                sumLuminance += (0.299f * r + 0.587f * g + 0.114f * b)
                count++
            }
        }

        if (count == 0) return TonalPolarity.DARK

        val mean = sumLuminance / count
        return if (mean <= 0.5f) TonalPolarity.DARK else TonalPolarity.LIGHT
    }

    // ── Stage 2 ───────────────────────────────────────────────────────────────

    /**
     * Converts the alpha channel of [segmented] into a binary ARGB_8888 mask bitmap.
     * White (0xFFFFFFFF) = subject pixel (alpha > 0), black (0xFF000000) = background.
     */
    private fun alphaToMask(segmented: Bitmap): Bitmap {
        val w = segmented.width
        val h = segmented.height
        val pixels = IntArray(w * h)
        segmented.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            pixels[i] = if (Color.alpha(pixels[i]) > 0) Color.WHITE else Color.BLACK
        }
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        mask.setPixels(pixels, 0, w, 0, 0, w, h)
        return mask
    }

    // ── Stage 3 ───────────────────────────────────────────────────────────────

    /**
     * Clusters subject pixels into a binary tonal pair using K-means on the HSV
     * V-channel (luminance). Returns exactly two [StencilLayer]s:
     *   1. A SOLID BASE (Black if [polarity] is DARK, White if LIGHT)
     *   2. A CONTRAST DETAIL (the cluster most different from the base)
     */
    internal fun kmeansLayers(
        isolated: Bitmap,
        subjectMask: Bitmap,
        polarity: TonalPolarity,
        influence: Float
    ): List<StencilLayer> {
        val w = isolated.width
        val h = isolated.height

        // ── Step 1: Generate Solid Base ──────────────────────────────────────────
        val baseType = if (polarity == TonalPolarity.DARK) StencilLayerType.SILHOUETTE else StencilLayerType.HIGHLIGHT
        val basePixels = IntArray(w * h) { Color.TRANSPARENT }
        val maskPixels = IntArray(w * h)
        subjectMask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        
        // Find subject pixels for K-means sample building
        val subjectIndices = maskPixels.indices.filter { maskPixels[it] == Color.WHITE }
        
        // Fill base: 100% solid color inside the mask (or whole canvas if empty)
        if (subjectIndices.isNotEmpty()) {
            for (idx in subjectIndices) basePixels[idx] = baseType.color
        } else {
            // No subject isolated; fill the whole frame as fallback
            for (i in basePixels.indices) basePixels[i] = baseType.color
        }
        val baseLayer = StencilLayer(baseType, pixelsToBitmap(basePixels, w, h), "Base - ${baseType.label}")

        if (subjectIndices.isEmpty()) return listOf(baseLayer)

        // ── Step 2: Extract Contrast Detail via K-means (K=2) ─────────────────────
        val srcMat = Mat()
        Utils.bitmapToMat(isolated, srcMat)
        val hsvMat = Mat()
        Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_RGBA2RGB)
        val hsvConverted = Mat()
        Imgproc.cvtColor(hsvMat, hsvConverted, Imgproc.COLOR_RGB2HSV)
        srcMat.release(); hsvMat.release()

        val channels = ArrayList<Mat>()
        Core.split(hsvConverted, channels)
        hsvConverted.release()
        val vChannel = channels[2]   // V = luminance
        channels[0].release(); channels[1].release()

        // 1.5 Detail Simplification: Median Blur (ksize must be odd)
        val ksizeRaw = (15 - (influence * 14).toInt())
        val ksize = if (ksizeRaw % 2 == 0) ksizeRaw + 1 else ksizeRaw
        if (ksize > 1) {
            Imgproc.medianBlur(vChannel, vChannel, ksize)
        }

        // 1.6 Tonal Bias: Shift brightness (alpha=1.0, beta=bias)
        // influence = 0.0 -> -40; influence = 0.5 -> 0; influence = 1.0 -> +40
        val bias = (influence - 0.5f) * 80.0
        vChannel.convertTo(vChannel, -1, 1.0, bias)

        // 3. Build CV_32F sample matrix from subject V-values
        val vBytes = ByteArray(w * h)
        vChannel.get(0, 0, vBytes)
        vChannel.release()

        val samples = Mat(subjectIndices.size, 1, CvType.CV_32F)
        subjectIndices.forEachIndexed { row, idx ->
            samples.put(row, 0, floatArrayOf((vBytes[idx].toInt() and 0xFF).toFloat()))
        }

        // 4. Run K-means with K=2
        val k = 2
        val labels = Mat()
        val centers = Mat()
        val criteria = TermCriteria(
            TermCriteria.EPS + TermCriteria.MAX_ITER, 10, 1.0
        )
        Core.kmeans(
            samples, k, labels, criteria, 3,
            Core.KMEANS_PP_CENTERS, centers
        )
        samples.release()

        // 5. Select the cluster most contrasting with the base
        // If base is DARK (Black), we want the Lightest cluster.
        // If base is LIGHT (White), we want the Darkest cluster.
        val centroidValues = FloatArray(k) { i -> centers.get(i, 0)[0].toFloat() }
        centers.release()
        
        val detailClusterIdx = if (polarity == TonalPolarity.DARK) {
            centroidValues.indices.maxByOrNull { centroidValues[it] } ?: 0
        } else {
            centroidValues.indices.minByOrNull { centroidValues[it] } ?: 0
        }

        // 6. Build Detail Layer
        val detailType = if (polarity == TonalPolarity.DARK) StencilLayerType.HIGHLIGHT else StencilLayerType.SILHOUETTE
        val detailPixels = IntArray(w * h) { Color.TRANSPARENT }
        subjectIndices.forEachIndexed { row, idx ->
            val cluster = labels.get(row, 0)[0].toInt()
            if (cluster == detailClusterIdx) {
                detailPixels[idx] = detailType.color
            }
        }
        labels.release()
        val detailLayer = StencilLayer(detailType, pixelsToBitmap(detailPixels, w, h), "Detail - ${detailType.label}")

        return listOf(baseLayer, detailLayer)
    }

    private fun pixelsToBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    // ── Stage 4 ───────────────────────────────────────────────────────────────

    /**
     * Applies a morphological closing operation (dilation → erosion) to all
     * non-silhouette layers to smooth jagged cut edges.
     * Silhouette is left unchanged — its outer boundary doesn't need smoothing.
     */
    internal fun applyMorphClose(layers: List<StencilLayer>): List<StencilLayer> {
        return layers.map { layer ->
            if (layer.type == StencilLayerType.SILHOUETTE) return@map layer
            layer.copy(bitmap = morphClose(layer.bitmap, layer.type.color))
        }
    }

    private fun morphClose(src: Bitmap, color: Int): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // The stencil features are defined by alpha > 0
        val channels = ArrayList<Mat>()
        Core.split(srcMat, channels)
        val alphaMat = channels[3]

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(MORPH_KERNEL_SIZE.toDouble(), MORPH_KERNEL_SIZE.toDouble())
        )
        val closedAlpha = Mat()
        Imgproc.morphologyEx(alphaMat, closedAlpha, Imgproc.MORPH_CLOSE, kernel)

        // Simpler way using Android Bitmap for pixel manipulation
        val w = src.width
        val h = src.height
        val alphaBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(closedAlpha, alphaBmp)
        
        val alphaPixels = IntArray(w * h)
        alphaBmp.getPixels(alphaPixels, 0, w, 0, 0, w, h)
        alphaBmp.recycle()

        val outPixels = IntArray(w * h)
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        for (i in outPixels.indices) {
            // closedAlpha is grayscale (CV_8UC1), but bitmap conversion will replicate 
            // the value across R,G,B. We'll take the blue channel.
            val aValue = Color.blue(alphaPixels[i])
            outPixels[i] = Color.argb(aValue, r, g, b)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, w, 0, 0, w, h)

        srcMat.release(); alphaMat.release(); closedAlpha.release(); kernel.release()
        for(c in channels) { c.release() }

        return out
    }

}

// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
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
        // Registration mark geometry (px at source resolution)
        private const val REG_MARK_ARM_LENGTH = 40   // crosshair arm length in px
        private const val REG_MARK_STROKE = 8        // crosshair stroke width in px
        private const val REG_MARK_MARGIN = 20       // inset from bounding box corner

        // Morphological closing kernel size for edge smoothing
        private const val MORPH_KERNEL_SIZE = 5
    }

    /**
     * Run the full stencil pipeline on [isolatedBitmap].
     * The bitmap must have background removed (transparent pixels = background).
     * SubjectIsolator is expected to have already downsampled to ≤2048px.
     * Emits [StencilProgress] events; final event is [StencilProgress.Done] or [StencilProgress.Error].
     */
    fun process(
        isolatedBitmap: Bitmap,
        layerCount: StencilLayerCount
    ): Flow<StencilProgress> = flow {

        emit(StencilProgress.Stage("Preparing image…", 0.05f))

        if (isolatedBitmap.width < 200 || isolatedBitmap.height < 200) {
            emit(StencilProgress.Error("Source image too small for stencil. (Min 200px)"))
            return@flow
        }

        val result = runCatching {
            // Stage 2: Build subject mask from alpha channel
            emit(StencilProgress.Stage("Building mask…", 0.20f))
            val subjectMask = alphaToMask(isolatedBitmap)

            // Stage 3: K-means clustering on luminance channel
            emit(StencilProgress.Stage("Analysing tones…", 0.45f))
            val layers = kmeansLayers(isolatedBitmap, subjectMask, layerCount)

            // Stage 4: Morphological closing on non-silhouette layers
            emit(StencilProgress.Stage("Smoothing edges…", 0.70f))
            val smoothed = applyMorphClose(layers)

            // Stage 5: Registration marks
            emit(StencilProgress.Stage("Adding registration marks…", 0.88f))
            injectRegistrationMarks(smoothed, subjectMask)
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
     * Extracts a single specific [type] from [isolatedBitmap] based on [totalCount] clusters.
     */
    fun processSingle(
        isolatedBitmap: Bitmap,
        type: StencilLayerType,
        totalCount: StencilLayerCount
    ): Flow<StencilProgress> = flow {
        emit(StencilProgress.Stage("Preparing image…", 0.05f))

        if (isolatedBitmap.width < 200 || isolatedBitmap.height < 200) {
            emit(StencilProgress.Error("Source image too small for stencil. (Min 200px)"))
            return@flow
        }

        val result = runCatching {
            emit(StencilProgress.Stage("Building mask…", 0.20f))
            val subjectMask = alphaToMask(isolatedBitmap)

            emit(StencilProgress.Stage("Analysing tones…", 0.45f))
            val allLayers = kmeansLayers(isolatedBitmap, subjectMask, totalCount)
            
            // Find the specific requested layer
            val targetLayer = allLayers.find { it.type == type } 
                ?: throw Exception("Layer type ${type.label} not found for count ${totalCount.count}")

            emit(StencilProgress.Stage("Smoothing edges…", 0.70f))
            val smoothed = if (type == StencilLayerType.SILHOUETTE) targetLayer 
                           else targetLayer.copy(bitmap = morphClose(targetLayer.bitmap, type.color))

            emit(StencilProgress.Stage("Adding registration marks…", 0.88f))
            val bmpWithMarks = smoothed.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val corners = computeSubjectBoundingBoxCorners(subjectMask)
            if (corners != null) {
                drawRegistrationMarks(bmpWithMarks, corners)
            }
            
            listOf(smoothed.copy(bitmap = bmpWithMarks))
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
     * Clusters subject pixels into [layerCount] tonal groups using K-means on the HSV
     * V-channel (luminance). Returns one [StencilLayer] per cluster, sorted darkest first
     * (overpaint strategy: each layer includes all pixels at this darkness or darker).
     */
    private fun kmeansLayers(
        isolated: Bitmap,
        subjectMask: Bitmap,
        layerCount: StencilLayerCount
    ): List<StencilLayer> {
        val k = layerCount.count
        val w = isolated.width
        val h = isolated.height

        // 1. Convert isolated bitmap → HSV, extract V channel (luminance)
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
        val vChannel = channels[2]   // V = luminance (index 2 in HSV)
        channels[0].release(); channels[1].release()

        // 2. Get subject pixels only (mask out background)
        val maskPixels = IntArray(w * h)
        subjectMask.getPixels(maskPixels, 0, w, 0, 0, w, h)
        val subjectIndices = maskPixels.indices.filter { maskPixels[it] == Color.WHITE }

        if (subjectIndices.isEmpty()) {
            vChannel.release()
            // Fallback: return a single silhouette layer
            val silPixels = maskPixels.map {
                if (it == Color.WHITE) Color.BLACK else Color.WHITE
            }.toIntArray()
            val silBmp = pixelsToBitmap(silPixels, w, h)
            return listOf(StencilLayer(StencilLayerType.SILHOUETTE, silBmp))
        }

        // 3. Build CV_32F sample matrix from subject V-values
        val vBytes = ByteArray(w * h)
        vChannel.get(0, 0, vBytes)
        vChannel.release()

        val samples = Mat(subjectIndices.size, 1, CvType.CV_32F)
        subjectIndices.forEachIndexed { row, idx ->
            samples.put(row, 0, floatArrayOf((vBytes[idx].toInt() and 0xFF).toFloat()))
        }

        // 4. Run K-means
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

        // 5. Sort cluster indices by centroid value (low luminance = shadows first)
        val centroidValues = FloatArray(k) { i -> centers.get(i, 0)[0].toFloat() }
        centers.release()
        val sortedClusterOrder = centroidValues.indices.sortedBy { centroidValues[it] }
        // sortedClusterOrder[0] = original cluster index that has the lowest centroid (darkest)

        // Build reverse map: original cluster index → sorted position (0 = darkest)
        val clusterToSortedPos = IntArray(k)
        sortedClusterOrder.forEachIndexed { sortedPos, originalCluster ->
            clusterToSortedPos[originalCluster] = sortedPos
        }

        // 6. Assign each subject pixel to its sorted cluster position
        val pixelCluster = IntArray(w * h) { -1 }  // -1 = background
        subjectIndices.forEachIndexed { row, idx ->
            val originalCluster = labels.get(row, 0)[0].toInt()
            pixelCluster[idx] = clusterToSortedPos[originalCluster]
        }
        labels.release()

        // 7. Build stencil layers: each sorted position → one layer
        //    Sorted position 0 = darkest (silhouette base)
        //    Sorted position k-1 = lightest (highlights)
        //    Layer rule: pixels at this sorted position OR darker → black (overpaint strategy)
        val layerTypes = when (k) {
            1 -> listOf(StencilLayerType.SILHOUETTE)
            2 -> listOf(StencilLayerType.SILHOUETTE, StencilLayerType.HIGHLIGHT)
            else -> listOf(StencilLayerType.SILHOUETTE, StencilLayerType.MIDTONE, StencilLayerType.HIGHLIGHT)
        }

        return layerTypes.mapIndexed { sortedPos, layerType ->
            val layerPixels = IntArray(w * h) { Color.TRANSPARENT }
            for (i in pixelCluster.indices) {
                // Paint black if this pixel belongs to this cluster or a darker cluster
                if (pixelCluster[i] in 0..sortedPos) {
                    layerPixels[i] = layerType.color
                }
            }
            StencilLayer(layerType, pixelsToBitmap(layerPixels, w, h))
        }
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
    private fun applyMorphClose(layers: List<StencilLayer>): List<StencilLayer> {
        return layers.map { layer ->
            if (layer.type == StencilLayerType.SILHOUETTE) return@map layer
            layer.copy(bitmap = morphClose(layer.bitmap, layer.type.color))
        }
    }

    private fun morphClose(src: Bitmap, color: Int): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // The stencil features are defined by alpha > 0
        val channels = java.util.ArrayList<Mat>()
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

    // ── Stage 5 ───────────────────────────────────────────────────────────────

    /**
     * Injects identical crosshair registration marks at all 4 corners of the subject's
     * bounding box into every layer. Marks are pure black on all layers.
     *
     * Physical alignment workflow:
     *   1. User sprays Layer 1 (Silhouette) — marks are painted onto the surface.
     *   2. User aligns Layer 2+ sheets by matching the crosshair marks.
     */
    private fun injectRegistrationMarks(
        layers: List<StencilLayer>,
        subjectMask: Bitmap
    ): List<StencilLayer> {
        val corners = computeSubjectBoundingBoxCorners(subjectMask) ?: return layers
        return layers.map { layer ->
            val bmp = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            drawRegistrationMarks(bmp, corners)
            layer.copy(bitmap = bmp)
        }
    }

    /**
     * Returns the four corner points of the subject's bounding box, inset by [REG_MARK_MARGIN].
     * Returns null if the mask contains no subject pixels.
     */
    private fun computeSubjectBoundingBoxCorners(mask: Bitmap): List<Pair<Int, Int>>? {
        val w = mask.width
        val h = mask.height
        val pixels = IntArray(w * h)
        mask.getPixels(pixels, 0, w, 0, 0, w, h)

        var minX = w; var maxX = 0; var minY = h; var maxY = 0
        var found = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (pixels[y * w + x] == Color.WHITE) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                    found = true
                }
            }
        }
        if (!found) return null

        val l = (minX - REG_MARK_MARGIN).coerceAtLeast(REG_MARK_ARM_LENGTH)
        val t = (minY - REG_MARK_MARGIN).coerceAtLeast(REG_MARK_ARM_LENGTH)
        val r = (maxX + REG_MARK_MARGIN).coerceAtMost(w - REG_MARK_ARM_LENGTH - 1)
        val b = (maxY + REG_MARK_MARGIN).coerceAtMost(h - REG_MARK_ARM_LENGTH - 1)

        return listOf(
            Pair(l, t),  // top-left
            Pair(r, t),  // top-right
            Pair(r, b),  // bottom-right
            Pair(l, b)   // bottom-left
        )
    }

    private fun drawRegistrationMarks(bmp: Bitmap, corners: List<Pair<Int, Int>>) {
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = REG_MARK_STROKE.toFloat()
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
        }
        val arm = REG_MARK_ARM_LENGTH.toFloat()
        for ((cx, cy) in corners) {
            val x = cx.toFloat(); val y = cy.toFloat()
            // Horizontal arm
            canvas.drawLine(x - arm, y, x + arm, y, paint)
            // Vertical arm
            canvas.drawLine(x, y - arm, x, y + arm, paint)
        }
    }
}

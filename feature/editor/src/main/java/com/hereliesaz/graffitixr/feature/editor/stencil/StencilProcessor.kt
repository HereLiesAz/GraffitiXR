// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/stencil/StencilProcessor.kt
package com.hereliesaz.graffitixr.feature.editor.stencil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.hereliesaz.graffitixr.common.model.StencilLayer
import com.hereliesaz.graffitixr.common.model.StencilLayerCount
import com.hereliesaz.graffitixr.common.model.StencilLayerType
import com.hereliesaz.graffitixr.feature.editor.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
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
 * Converts an arbitrary source bitmap into 1, 2, or 3 physical stencil layers:
 *   Layer 1 — SILHOUETTE  : solid subject outline (always present)
 *   Layer 2 — MIDTONE     : mid-luminance band (3-layer mode only)
 *   Layer 3 — HIGHLIGHT   : peak luminance areas above the silhouette
 *
 * Island handling: OVERPAINT sequential-layering strategy — no bridging required.
 * Upper-layer holes are physically supported by the surrounding sheet material.
 *
 * All processing runs on Dispatchers.Default. Progress is emitted via Flow.
 */
class StencilProcessor @Inject constructor(
    private val backgroundRemover: BackgroundRemover
) {

    companion object {
        // Max input dimension before downsampling for GrabCut (performance ceiling)
        private const val MAX_INPUT_PX = 2048

        // Luminance thresholds for tonal band extraction (0..255)
        private const val HIGHLIGHT_THRESHOLD = 160  // pixels above this → highlight
        private const val MIDTONE_THRESHOLD = 80     // pixels above this → midtone (below highlight)

        // Registration mark geometry (px at source resolution)
        private const val REG_MARK_ARM_LENGTH = 40   // crosshair arm length in px
        private const val REG_MARK_STROKE = 8        // crosshair stroke width in px
        private const val REG_MARK_MARGIN = 20       // inset from bounding box corner

        // Morphological closing kernel size for edge smoothing
        private const val MORPH_KERNEL_SIZE = 5
    }

    /**
     * Run the full stencil pipeline on [sourceBitmap].
     * Emits [StencilProgress] events; final event is [StencilProgress.Done] or [StencilProgress.Error].
     */
    fun process(
        sourceBitmap: Bitmap,
        layerCount: StencilLayerCount
    ): Flow<StencilProgress> = flow {

        emit(StencilProgress.Stage("Preparing image…", 0.05f))

        if (sourceBitmap.width < 200 || sourceBitmap.height < 200) {
            emit(StencilProgress.Error("Source image too small for stencil. (Min 200px)"))
            return@flow
        }

        val result = runCatching {
            // ── Stage 1: Downsample if needed ──────────────────────────────────────
            val source = downsample(sourceBitmap)

            // ── Stage 2: Subject segmentation via OpenCV GrabCut ──────────────────
            emit(StencilProgress.Stage("Segmenting subject…", 0.15f))
            val segmented = backgroundRemover.removeBackground(source)
                .getOrElse { throw IllegalStateException("Subject segmentation failed: ${it.message}", it) }

            // ── Stage 3: Derive binary subject mask from alpha channel ─────────────
            emit(StencilProgress.Stage("Building mask…", 0.30f))
            val subjectMask = alphaToMask(segmented)  // white = subject, black = bg

            // ── Stage 4: Contrast crush for tonal separation ──────────────────────
            emit(StencilProgress.Stage("Analysing tones…", 0.45f))
            val contrasted = crushContrast(source)

            // ── Stage 5: Extract layers ───────────────────────────────────────────
            emit(StencilProgress.Stage("Extracting layers…", 0.60f))
            val layers = extractLayers(contrasted, subjectMask, layerCount)

            // ── Stage 6: Morphological closing on non-silhouette layers ───────────
            emit(StencilProgress.Stage("Smoothing edges…", 0.75f))
            val smoothed = applyMorphClose(layers)

            // ── Stage 7: Registration marks on all layers ─────────────────────────
            emit(StencilProgress.Stage("Adding registration marks…", 0.88f))
            val marked = injectRegistrationMarks(smoothed, subjectMask)

            marked
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

    // ── Stage 1 ───────────────────────────────────────────────────────────────

    private fun downsample(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= MAX_INPUT_PX) return src.copy(Bitmap.Config.ARGB_8888, false)
        val scale = MAX_INPUT_PX.toFloat() / maxDim
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    // ── Stage 3 ───────────────────────────────────────────────────────────────

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

    // ── Stage 4 ───────────────────────────────────────────────────────────────

    /**
     * Aggressively crushes contrast to maximise tonal separation.
     * Uses dynamic histogram analysis to compute optimal gain/bias parameters.
     */
    private fun crushContrast(src: Bitmap): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        val grayMat = Mat()
        Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_RGBA2GRAY)

        val (alpha, beta) = calculateDynamicContrastParams(grayMat)

        val crushedMat = Mat()
        Core.convertScaleAbs(grayMat, crushedMat, alpha, beta)

        // Convert back to RGBA so we can work uniformly in ARGB_8888
        val rgbaMat = Mat()
        Imgproc.cvtColor(crushedMat, rgbaMat, Imgproc.COLOR_GRAY2RGBA)

        val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgbaMat, result)

        srcMat.release(); grayMat.release(); crushedMat.release(); rgbaMat.release()
        return result
    }

    /**
     * Computes optimal alpha (gain) and beta (bias) for contrast maximization
     * by clipping 2% of the histogram tails.
     */
    private fun calculateDynamicContrastParams(grayMat: Mat): Pair<Double, Double> {
        val hist = Mat()
        Imgproc.calcHist(listOf(grayMat), MatOfInt(0), Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))

        val totalPixels = grayMat.rows() * grayMat.cols()
        val clipLimit = (totalPixels * 0.02).toInt()

        var minIntensity = 0
        var cumulative = 0.0
        for (i in 0 until 256) {
            cumulative += hist.get(i, 0)[0]
            if (cumulative > clipLimit) {
                minIntensity = i
                break
            }
        }

        var maxIntensity = 255
        cumulative = 0.0
        for (i in 255 downTo 0) {
            cumulative += hist.get(i, 0)[0]
            if (cumulative > clipLimit) {
                maxIntensity = i
                break
            }
        }

        hist.release()

        if (maxIntensity <= minIntensity) return 2.5 to -80.0 // fallback

        val alpha = 255.0 / (maxIntensity - minIntensity)
        val beta = -minIntensity * alpha

        return alpha to beta
    }

    // ── Stage 5 ───────────────────────────────────────────────────────────────

    /**
     * Extracts stencil layers using luminance thresholding + Porter-Duff compositing.
     *
     * 1-layer:  SILHOUETTE only (solid subject silhouette on white background)
     * 2-layer:  SILHOUETTE + HIGHLIGHT
     * 3-layer:  SILHOUETTE + MIDTONE + HIGHLIGHT
     *
     * Returns white-background black-content bitmaps ordered bottom→top.
     */
    private fun extractLayers(
        contrasted: Bitmap,
        subjectMask: Bitmap,
        layerCount: StencilLayerCount
    ): List<StencilLayer> {
        val w = contrasted.width
        val h = contrasted.height
        val pixels = IntArray(w * h)
        contrasted.getPixels(pixels, 0, w, 0, 0, w, h)

        val maskPixels = IntArray(w * h)
        subjectMask.getPixels(maskPixels, 0, w, 0, 0, w, h)

        // Classify each pixel into tonal bands, masked to subject only
        val silPixels = IntArray(w * h) { Color.TRANSPARENT }
        val midPixels = IntArray(w * h) { Color.TRANSPARENT }
        val hiPixels  = IntArray(w * h) { Color.TRANSPARENT }

        for (i in pixels.indices) {
            val isSubject = maskPixels[i] == Color.WHITE
            if (!isSubject) continue

            val lum = luminance(pixels[i])
            // Silhouette — every subject pixel becomes black on transparent
            silPixels[i] = Color.BLACK

            if (layerCount.count >= 2) {
                // Highlight — brightest pixels
                if (lum >= HIGHLIGHT_THRESHOLD) hiPixels[i] = Color.WHITE
            }
            if (layerCount.count >= 3) {
                // Midtone — between the two thresholds
                if (lum in MIDTONE_THRESHOLD until HIGHLIGHT_THRESHOLD) midPixels[i] = Color.GRAY
            }
        }

        val silBmp = pixelsToBitmap(silPixels, w, h)
        val layers = mutableListOf(StencilLayer(StencilLayerType.SILHOUETTE, silBmp))

        if (layerCount.count >= 3) {
            layers.add(StencilLayer(StencilLayerType.MIDTONE, pixelsToBitmap(midPixels, w, h)))
        }
        if (layerCount.count >= 2) {
            layers.add(StencilLayer(StencilLayerType.HIGHLIGHT, pixelsToBitmap(hiPixels, w, h)))
        }

        return layers
    }

    private fun luminance(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun pixelsToBitmap(pixels: IntArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        return bmp
    }

    // ── Stage 6 ───────────────────────────────────────────────────────────────

    /**
     * Applies a morphological closing operation (dilation → erosion) to all
     * non-silhouette layers to smooth jagged cut edges.
     * Silhouette is left unchanged — its outer boundary doesn't need smoothing.
     */
    private fun applyMorphClose(layers: List<StencilLayer>): List<StencilLayer> {
        return layers.map { layer ->
            if (layer.type == StencilLayerType.SILHOUETTE) return@map layer
            layer.copy(bitmap = morphClose(layer.bitmap))
        }
    }

    private fun morphClose(src: Bitmap): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(src, srcMat)

        // The stencil features are alpha>0
        val channels = java.util.ArrayList<Mat>()
        Core.split(srcMat, channels)
        val alphaMat = channels[3]

        val binary = Mat()
        Imgproc.threshold(alphaMat, binary, 127.0, 255.0, Imgproc.THRESH_BINARY)

        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(MORPH_KERNEL_SIZE.toDouble(), MORPH_KERNEL_SIZE.toDouble())
        )
        val closedAlpha = Mat()
        Imgproc.morphologyEx(binary, closedAlpha, Imgproc.MORPH_CLOSE, kernel)

        // Find average color to restore (black, gray, or white)
        val hsv = Mat()
        Imgproc.cvtColor(srcMat, hsv, Imgproc.COLOR_RGBA2RGB)
        val mask = Mat()
        Imgproc.threshold(alphaMat, mask, 0.0, 255.0, Imgproc.THRESH_BINARY)
        val meanColor = Core.mean(hsv, mask)

        // We know it's either Black (0,0,0), Gray (128,128,128), or White (255,255,255)
        // Just fill the RGB channels based on where closedAlpha > 0
        // Or simpler, since the input had transparent background and a single color,
        // we can just recreate the image using the original color and the new alpha

        // This is a bit complex in OpenCV. A simpler way is to bitwise_and the color.
        val colorMat = Mat(srcMat.size(), srcMat.type(), org.opencv.core.Scalar(meanColor.`val`[0], meanColor.`val`[1], meanColor.`val`[2], 255.0))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)

        // Simpler way using Android Bitmap
        Utils.matToBitmap(srcMat, out)
        val alphaPixels = IntArray(src.width * src.height)
        val outPixels = IntArray(src.width * src.height)

        val alphaBmp = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(closedAlpha, alphaBmp)

        alphaBmp.getPixels(alphaPixels, 0, src.width, 0, 0, src.width, src.height)
        out.getPixels(outPixels, 0, src.width, 0, 0, src.width, src.height)

        // The color is meanColor. But wait, it's easier: just use the original outPixels color
        // since we only have one color in each layer, but set alpha from alphaPixels.
        // Actually, just find the first non-transparent pixel to get the color, or use meanColor.
        val color = Color.argb(255, meanColor.`val`[0].toInt(), meanColor.`val`[1].toInt(), meanColor.`val`[2].toInt())

        for (i in outPixels.indices) {
            val a = Color.blue(alphaPixels[i]) // closedAlpha is grayscale, so blue channel has the value
            outPixels[i] = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
        }
        out.setPixels(outPixels, 0, src.width, 0, 0, src.width, src.height)
        alphaBmp.recycle()


        srcMat.release(); binary.release()
        kernel.release(); closedAlpha.release(); hsv.release(); mask.release()
        for(c in channels) { c.release() }

        return out
    }

    // ── Stage 7 ───────────────────────────────────────────────────────────────

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

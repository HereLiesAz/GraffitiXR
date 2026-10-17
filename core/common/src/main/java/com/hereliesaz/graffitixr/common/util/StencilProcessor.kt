// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/StencilProcessor.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.StencilView
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import timber.log.Timber

/**
 * The stencil tool: splits a design into three sheets (black, grey, white) and renders the ones
 * asked for. It is the app's old StencilProcessor, restored: the value channel is simplified with
 * a median blur, clustered into three tones, and each sheet's edge is closed 5x5 so the cuts are
 * smooth.
 *
 * Callers cut the background out first when they can (the editor tries; see EditorViewModel), so
 * this sees the subject alone. It then decides whether the image is dark or light: the subject's
 * brightness is turned down and its contrast turned up, and the call goes to whichever of dark and
 * light covers more area ([StencilTones.isDarkByArea]). That picks the BASE sheet, the one covering
 * the whole subject: black for a dark image, white for a light one. The other two sheets are laid
 * over it, sequentially, as paint is (see [StencilTones.render]).
 *
 * Transparent pixels of the source stay transparent (an isolated subject keeps its cut-out).
 */
/** A rendered stencil view, and whether the image was judged dark (black base) or light (white base). */
class StencilResult(val bitmap: Bitmap, val dark: Boolean)

object StencilProcessor {

    private const val MEDIAN_KERNEL = 9
    private const val CLOSE_KERNEL = 5

    /**
     * [observed] (the pixels the cut-out kept) with its gaps closed and its enclosed holes filled:
     * the subject's complete silhouette. Holes are what the flood from the frame's edge cannot reach.
     */
    private fun filledSilhouette(observed: BooleanArray, w: Int, h: Int, mats: MutableList<Mat>): BooleanArray {
        val mask = Mat(h, w, CvType.CV_8UC1).also { mats.add(it) }
        mask.put(0, 0, ByteArray(w * h) { if (observed[it]) 0xFF.toByte() else 0 })
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(CLOSE_KERNEL.toDouble(), CLOSE_KERNEL.toDouble()))
            .also { mats.add(it) }
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        // A one-pixel border of background, so the flood from the corner reaches around the subject.
        val padded = Mat().also { mats.add(it) }
        Core.copyMakeBorder(mask, padded, 1, 1, 1, 1, Core.BORDER_CONSTANT, Scalar(0.0))
        val floodMask = Mat.zeros(h + 4, w + 4, CvType.CV_8UC1).also { mats.add(it) }
        Imgproc.floodFill(padded, floodMask, Point(0.0, 0.0), Scalar(255.0))
        val flooded = ByteArray((w + 2) * (h + 2))
        padded.get(0, 0, flooded)
        val closed = ByteArray(w * h)
        mask.get(0, 0, closed)
        return BooleanArray(w * h) { i ->
            val x = i % w
            val y = i / w
            // Not reached by the flood from outside = inside the silhouette.
            closed[i].toInt() != 0 || flooded[(y + 1) * (w + 2) + (x + 1)].toInt() == 0
        }
    }

    /**
     * @return the design rendered as [view], an ARGB_8888 bitmap the size of [bitmap] together with
     *   the dark-or-light call that chose the base sheet, or null if processing fails (the caller falls back to the original, as with the other effects).
     */
    fun stencil(bitmap: Bitmap, view: StencilView): StencilResult? {
        val mats = mutableListOf<Mat>()
        return try {
            // Loaded here and not in an init block, for the reason given in SketchProcessor.
            NativeLibLoader.loadAll()
            val w = bitmap.width
            val h = bitmap.height
            if (w < 2 || h < 2) return null

            val src = Mat().also { mats.add(it) }
            Utils.bitmapToMat(bitmap, src)
            val channels = ArrayList<Mat>()
            Core.split(src, channels)
            channels.forEach { mats.add(it) }
            if (channels.size < 3) return null

            // V of HSV: the brightest channel.
            val v = Mat().also { mats.add(it) }
            Core.max(channels[0], channels[1], v)
            Core.max(v, channels[2], v)
            Imgproc.medianBlur(v, v, MEDIAN_KERNEL)

            val valueBytes = ByteArray(w * h)
            v.get(0, 0, valueBytes)
            val alphaBytes = ByteArray(w * h) { 0xFF.toByte() }
            if (channels.size >= 4) channels[3].get(0, 0, alphaBytes)
            // bitmapToMat gives RGBA channel order.
            val rBytes = ByteArray(w * h).also { channels[0].get(0, 0, it) }
            val gBytes = ByteArray(w * h).also { channels[1].get(0, 0, it) }
            val bBytes = ByteArray(w * h).also { channels[2].get(0, 0, it) }

            val histogram = IntArray(256)
            val lumaHistogram = IntArray(256)
            for (i in valueBytes.indices) {
                if ((alphaBytes[i].toInt() and 0xFF) > 0) {
                    histogram[valueBytes[i].toInt() and 0xFF]++
                    val luma = StencilTones.luma(
                        rBytes[i].toInt() and 0xFF, gBytes[i].toInt() and 0xFF, bBytes[i].toInt() and 0xFF,
                    )
                    lumaHistogram[(luma * 255.0).toInt().coerceIn(0, 255)]++
                }
            }
            val (lo, hi) = StencilTones.thresholds(histogram) ?: return null
            val dark = StencilTones.isDarkByArea(lumaHistogram)

            // The two sheets laid over the base, closed so their edges are smooth. A dark image grows
            // outwards from black (grey-or-lighter, then the lightest); a light one from white
            // (grey-or-darker, then the darkest).
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(CLOSE_KERNEL.toDouble(), CLOSE_KERNEL.toDouble()))
                .also { mats.add(it) }
            val middle = Mat().also { mats.add(it) }
            val top = Mat().also { mats.add(it) }
            if (dark) {
                Imgproc.threshold(v, middle, lo.toDouble(), 255.0, Imgproc.THRESH_BINARY)
                Imgproc.threshold(v, top, hi.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            } else {
                Imgproc.threshold(v, middle, hi.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
                Imgproc.threshold(v, top, lo.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
            }
            Imgproc.morphologyEx(middle, middle, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(top, top, Imgproc.MORPH_CLOSE, kernel)
            val middleBytes = ByteArray(w * h)
            val topBytes = ByteArray(w * h)
            middle.get(0, 0, middleBytes)
            top.get(0, 0, topBytes)

            // The base is the complete silhouette of the subject: every pixel the cut-out kept, plus
            // any hole inside it filled in. With no cut-out (background removal found nothing) the
            // image is fully opaque and the silhouette is the whole frame.
            val observed = BooleanArray(w * h) { (alphaBytes[it].toInt() and 0xFF) > 0 }
            val silhouette = filledSilhouette(observed, w, h, mats)
            val ranks = StencilTones.ranks(
                silhouette = silhouette,
                observed = observed,
                middle = BooleanArray(w * h) { middleBytes[it].toInt() != 0 },
                top = BooleanArray(w * h) { topBytes[it].toInt() != 0 },
                dark = dark,
            )
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(StencilTones.render(ranks, view, dark), 0, w, 0, 0, w, h)
            StencilResult(out, dark)
        } catch (e: Exception) {
            Timber.e(e, "StencilProcessor: stencil failed")
            null
        } finally {
            mats.forEach { it.release() }
        }
    }
}

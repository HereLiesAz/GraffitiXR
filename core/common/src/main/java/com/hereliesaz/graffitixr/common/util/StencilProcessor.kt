// FILE: core/common/src/main/java/com/hereliesaz/graffitixr/common/util/StencilProcessor.kt
package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.model.StencilView
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import timber.log.Timber

/**
 * The stencil tool: splits a design into three sheets (black, grey, white) and renders the ones
 * asked for. It is the app's old StencilProcessor, restored: the value channel is simplified with
 * a median blur, clustered into three tones, and each sheet's edge is closed 5x5 so the cuts are
 * smooth.
 *
 * It decides first whether the image is dark or light (mean luminance, 0.299R + 0.587G + 0.114B
 * over the opaque pixels; dark at 0.5 or below), and that picks the BASE sheet, the one covering
 * the whole subject: black for a dark image, white for a light one. The other two sheets are laid
 * over it, sequentially, as paint is (see [StencilTones.render]).
 *
 * Transparent pixels of the source stay transparent (an isolated subject keeps its cut-out).
 */
object StencilProcessor {

    private const val MEDIAN_KERNEL = 9
    private const val CLOSE_KERNEL = 5

    /**
     * @return the design rendered as [view], an ARGB_8888 bitmap the size of [bitmap], or null if
     *   processing fails (the caller falls back to the original, as with the other effects).
     */
    fun stencil(bitmap: Bitmap, view: StencilView): Bitmap? {
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
            var lumaSum = 0.0
            var opaque = 0
            for (i in valueBytes.indices) {
                if ((alphaBytes[i].toInt() and 0xFF) > 0) {
                    histogram[valueBytes[i].toInt() and 0xFF]++
                    lumaSum += StencilTones.luma(
                        rBytes[i].toInt() and 0xFF, gBytes[i].toInt() and 0xFF, bBytes[i].toInt() and 0xFF,
                    )
                    opaque++
                }
            }
            val (lo, hi) = StencilTones.thresholds(histogram) ?: return null
            val dark = StencilTones.isDark(if (opaque == 0) 0.0 else lumaSum / opaque)

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

            val ranks = IntArray(w * h) { i ->
                val onTop = topBytes[i].toInt() != 0
                val onMiddle = onTop || middleBytes[i].toInt() != 0
                when {
                    (alphaBytes[i].toInt() and 0xFF) == 0 -> StencilTones.NONE
                    dark -> if (onTop) StencilTones.WHITE else if (onMiddle) StencilTones.GREY else StencilTones.BLACK
                    else -> if (onTop) StencilTones.BLACK else if (onMiddle) StencilTones.GREY else StencilTones.WHITE
                }
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(StencilTones.render(ranks, view, dark), 0, w, 0, 0, w, h)
            out
        } catch (e: Exception) {
            Timber.e(e, "StencilProcessor: stencil failed")
            null
        } finally {
            mats.forEach { it.release() }
        }
    }
}

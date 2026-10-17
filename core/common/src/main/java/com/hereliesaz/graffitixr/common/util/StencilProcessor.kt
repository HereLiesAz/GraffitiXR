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
 * The stencil tool: splits a design into three tonal sheets (black, grey, white) and renders the
 * ones asked for. It is the app's old StencilProcessor in a smaller form: the value channel is
 * simplified with a median blur, clustered into three tones, and each sheet's edge is closed 5x5 so
 * the cuts are smooth.
 *
 * The sheets are sequential, as paint is: the black sheet covers the whole subject, the grey sheet
 * everything grey or lighter, the white sheet the lightest. What the view SHOWS is each tone's own
 * area ([StencilTones.shade]), so cycling through them shows where each colour goes.
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

            val histogram = IntArray(256)
            for (i in valueBytes.indices) {
                if ((alphaBytes[i].toInt() and 0xFF) > 0) histogram[valueBytes[i].toInt() and 0xFF]++
            }
            val (lo, hi) = StencilTones.thresholds(histogram) ?: return null

            // Sheets laid bottom to top: "grey or lighter" and "white".
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(CLOSE_KERNEL.toDouble(), CLOSE_KERNEL.toDouble()))
                .also { mats.add(it) }
            val greyUp = Mat().also { mats.add(it) }
            val whiteUp = Mat().also { mats.add(it) }
            Imgproc.threshold(v, greyUp, lo.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(v, whiteUp, hi.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(greyUp, greyUp, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.morphologyEx(whiteUp, whiteUp, Imgproc.MORPH_CLOSE, kernel)
            val greyBytes = ByteArray(w * h)
            val whiteBytes = ByteArray(w * h)
            greyUp.get(0, 0, greyBytes)
            whiteUp.get(0, 0, whiteBytes)

            val ranks = IntArray(w * h) { i ->
                when {
                    (alphaBytes[i].toInt() and 0xFF) == 0 -> StencilTones.NONE
                    whiteBytes[i].toInt() != 0 -> StencilTones.WHITE
                    greyBytes[i].toInt() != 0 -> StencilTones.GREY
                    else -> StencilTones.BLACK
                }
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(StencilTones.render(ranks, view), 0, w, 0, 0, w, h)
            out
        } catch (e: Exception) {
            Timber.e(e, "StencilProcessor: stencil failed")
            null
        } finally {
            mats.forEach { it.release() }
        }
    }
}

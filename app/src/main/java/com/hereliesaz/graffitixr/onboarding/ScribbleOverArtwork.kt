package com.hereliesaz.graffitixr.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The first-run scribble drawn over the artwork it will be copied for: the subject's part of the
 * picture at [artworkAlpha] (0 to 1) on black, and the glyphs on top, each pixel of a glyph the
 * [GlyphColor.opposite] of the pixel behind it: vivid over muted, light over dark, the other half of the
 * colour wheel over a colour. With the picture at 0 the glyphs are vivid cyan on black. The glyphs are
 * always fully opaque: the slider that sets [artworkAlpha] changes the picture, and so the colours, only.
 *
 * Drawn into a canvas of the scribble's own aspect ratio: the picture's region is stretched over it, so
 * the glyph layout (made for that aspect) covers the picture shown.
 *
 * A thin dark halo is drawn under the glyphs, so a glyph still has an edge where a backdrop that varies
 * under it pulls its colour close to the picture's.
 */
@Composable
fun ScribbleOverArtwork(
    scribble: Scribble,
    artwork: FirstRunArtwork,
    artworkAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val paints = remember { ArtworkPaints() }
    // What depends only on the scribble, the picture and the size drawn at: made once, not per slider move.
    val layer = remember { GlyphLayer() }
    Canvas(modifier) {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@Canvas
        layer.prepare(scribble, artwork, w, h, paints)
        val alpha = layer.step(artworkAlpha)
        val doodle = layer.doodle(alpha)
        drawIntoCanvas { canvas ->
            drawOverArtwork(canvas.nativeCanvas, w.toFloat(), h.toFloat(), scribble, layer, alpha, doodle, paints)
        }
    }
}

/** How many distinct picture opacities the glyph colours are worked out for: a drag over the slider recolours at most this often. */
private const val ALPHA_STEPS = 32

/** Paints reused across frames: nothing here allocates in the draw loop. */
private class ArtworkPaints {
    val black = Paint().apply { color = AndroidColor.BLACK; style = Paint.Style.FILL }
    val image = Paint(Paint.FILTER_BITMAP_FLAG)
    val doodle = Paint(Paint.FILTER_BITMAP_FLAG)
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(220, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        setShadowLayer(14f, 0f, 0f, AndroidColor.argb(230, 0, 0, 0))
    }
    val maskGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    val fontMetrics = Paint.FontMetrics()
}

/**
 * The picture's crop scaled to the canvas, and the glyph pixels with the picture's hue, saturation and
 * value under each, so that a new opacity only recolours the glyphs: [doodle] is the glyphs as
 * [GlyphColor.opposite] of that backdrop, transparent elsewhere.
 */
private class GlyphLayer {
    private var scribble: Scribble? = null
    private var artwork: FirstRunArtwork? = null
    private var width = 0
    private var height = 0

    /** The picture's subject scaled to the canvas, over black: what is behind the glyphs at opacity 1. */
    var crop: Bitmap? = null
        private set

    private var doodleBitmap: Bitmap? = null
    private var doodleStep = -1

    // Per glyph pixel: where it is, how much of it is glyph (0..255), and the backdrop at opacity 1.
    private var index = IntArray(0)
    private var cover = IntArray(0)
    private var hue = FloatArray(0)
    private var sat = FloatArray(0)
    private var value = FloatArray(0)
    private var out = IntArray(0)

    fun step(alpha: Float): Int = (alpha.coerceIn(0f, 1f) * ALPHA_STEPS).roundToInt()

    fun prepare(scribble: Scribble, artwork: FirstRunArtwork, w: Int, h: Int, p: ArtworkPaints) {
        if (crop != null && this.scribble === scribble && this.artwork === artwork && width == w && height == h) return
        crop?.recycle()
        doodleBitmap?.recycle()

        val c = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cc = AndroidCanvas(c)
        cc.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p.black)
        val r = artwork.subject.region
        val bw = artwork.bitmap.width
        val bh = artwork.bitmap.height
        val src = Rect(
            (r.left * bw).roundToInt().coerceIn(0, bw - 1),
            (r.top * bh).roundToInt().coerceIn(0, bh - 1),
            (r.right * bw).roundToInt().coerceIn(1, bw),
            (r.bottom * bh).roundToInt().coerceIn(1, bh),
        )
        p.image.alpha = 255
        cc.drawBitmap(artwork.bitmap, src, RectF(0f, 0f, w.toFloat(), h.toFloat()), p.image)

        // Where the glyphs are, as coverage.
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawScribble(AndroidCanvas(mask), scribble, w.toFloat(), h.toFloat(), min(w, h).toFloat(), p.maskGlyph, p.fontMetrics)
        val maskPx = IntArray(w * h)
        mask.getPixels(maskPx, 0, w, 0, 0, w, h)
        mask.recycle()
        val backPx = IntArray(w * h)
        c.getPixels(backPx, 0, w, 0, 0, w, h)

        var n = 0
        for (v in maskPx) if ((v ushr 24) != 0) n++
        index = IntArray(n)
        cover = IntArray(n)
        hue = FloatArray(n)
        sat = FloatArray(n)
        value = FloatArray(n)
        out = IntArray(w * h)
        val hsv = FloatArray(3)
        var k = 0
        for (i in maskPx.indices) {
            val a = maskPx[i] ushr 24
            if (a == 0) continue
            GlyphColor.toHsv(backPx[i], hsv)
            index[k] = i; cover[k] = a; hue[k] = hsv[0]; sat[k] = hsv[1]; value[k] = hsv[2]
            k++
        }

        crop = c
        doodleBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        doodleStep = -1
        this.scribble = scribble
        this.artwork = artwork
        width = w
        height = h
    }

    /** The glyphs coloured for picture opacity [step] of [ALPHA_STEPS]; recoloured only when the step changes. */
    fun doodle(step: Int): Bitmap {
        val bmp = doodleBitmap!!
        if (step == doodleStep) return bmp
        val a = step.toFloat() / ALPHA_STEPS
        java.util.Arrays.fill(out, 0)
        for (k in index.indices) {
            // The picture at opacity a over black is the same colour with its value scaled by a.
            val rgb = GlyphColor.opposite(hue[k], sat[k], value[k] * a)
            out[index[k]] = (cover[k] shl 24) or (rgb and 0xFFFFFF)
        }
        bmp.setPixels(out, 0, width, 0, 0, width, height)
        doodleStep = step
        return bmp
    }
}

private fun drawOverArtwork(
    nc: AndroidCanvas,
    w: Float,
    h: Float,
    scribble: Scribble,
    layer: GlyphLayer,
    step: Int,
    doodle: Bitmap,
    p: ArtworkPaints,
) {
    nc.drawRect(0f, 0f, w, h, p.black)
    p.image.alpha = (step.toFloat() / ALPHA_STEPS * 255f).roundToInt()
    if (p.image.alpha > 0) layer.crop?.let { nc.drawBitmap(it, 0f, 0f, p.image) }
    // A dark halo round each glyph, so a glyph the colour of its surroundings still has an edge.
    drawScribble(nc, scribble, w, h, min(w, h), p.halo, p.fontMetrics)
    nc.drawBitmap(doodle, 0f, 0f, p.doodle)
}

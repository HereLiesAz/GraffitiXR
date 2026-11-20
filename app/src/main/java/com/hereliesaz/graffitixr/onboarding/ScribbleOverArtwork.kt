package com.hereliesaz.graffitixr.onboarding

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
 * picture at [artworkAlpha] (0 to 1) on black, and the glyphs on top as an inverting mask, so each
 * glyph is the colour of whatever is behind it, turned over. They read on a light patch and a dark one
 * alike, and with the picture at 0 they are the white-on-black they used to be. The glyphs are always
 * fully opaque: the slider that sets [artworkAlpha] changes the picture only.
 *
 * Drawn into a canvas of the scribble's own aspect ratio: the picture's region is stretched over it, so
 * the glyph layout (made for that aspect) covers the picture shown.
 *
 * Inverting a mid-tone gives a mid-tone, so a thin dark halo is drawn first and the inverted glyph on top
 * of it: where the picture is the colour the glyph would be, the halo still shows its edge.
 */
@Composable
fun ScribbleOverArtwork(
    scribble: Scribble,
    artwork: FirstRunArtwork,
    artworkAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val paints = remember { ArtworkPaints() }
    // The glyph mask only depends on the scribble and the size drawn at: rendered once, not per slider move.
    val maskCache = remember { MaskCache() }
    Canvas(modifier) {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return@Canvas
        val mask = maskCache.get(scribble, w, h, paints)
        drawIntoCanvas { canvas ->
            drawOverArtwork(canvas.nativeCanvas, w.toFloat(), h.toFloat(), scribble, artwork, artworkAlpha, mask, paints)
        }
    }
}

/** Paints reused across frames: nothing here allocates in the draw loop. */
private class ArtworkPaints {
    val black = Paint().apply { color = AndroidColor.BLACK; style = Paint.Style.FILL }
    val image = Paint(Paint.FILTER_BITMAP_FLAG)
    val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(220, 0, 0, 0)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        isFakeBoldText = true
        setShadowLayer(14f, 0f, 0f, AndroidColor.argb(230, 0, 0, 0))
    }
    val maskGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        isFakeBoldText = true
    }
    /** Keeps the layer only where the mask has alpha: DST_IN, with the mask drawn as a full-size bitmap. */
    val keepInsideMask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    /** Applied to the layer as it is composited: every colour channel turned over, alpha kept. */
    val invertLayer = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    val fontMetrics = Paint.FontMetrics()
}

/** The glyphs as white-on-transparent alpha, for the scribble and size it was made for. */
private class MaskCache {
    private var scribble: Scribble? = null
    private var width = 0
    private var height = 0
    private var bitmap: Bitmap? = null

    fun get(scribble: Scribble, w: Int, h: Int, paints: ArtworkPaints): Bitmap {
        val cached = bitmap
        if (cached != null && this.scribble === scribble && width == w && height == h) return cached
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawScribble(AndroidCanvas(out), scribble, w.toFloat(), h.toFloat(), min(w, h).toFloat(), paints.maskGlyph, paints.fontMetrics)
        this.scribble = scribble
        width = w
        height = h
        bitmap = out
        return out
    }
}

private fun drawOverArtwork(
    nc: AndroidCanvas,
    w: Float,
    h: Float,
    scribble: Scribble,
    artwork: FirstRunArtwork,
    artworkAlpha: Float,
    mask: Bitmap,
    p: ArtworkPaints,
) {
    val dst = RectF(0f, 0f, w, h)
    val r = artwork.subject.region
    val bw = artwork.bitmap.width
    val bh = artwork.bitmap.height
    val src = Rect(
        (r.left * bw).roundToInt().coerceIn(0, bw - 1),
        (r.top * bh).roundToInt().coerceIn(0, bh - 1),
        (r.right * bw).roundToInt().coerceIn(1, bw),
        (r.bottom * bh).roundToInt().coerceIn(1, bh),
    )
    p.image.alpha = (artworkAlpha.coerceIn(0f, 1f) * 255f).roundToInt()

    // What is behind the glyphs: black, then the picture at the chosen opacity.
    fun backdrop() {
        nc.drawRect(dst, p.black)
        if (p.image.alpha > 0) nc.drawBitmap(artwork.bitmap, src, dst, p.image)
    }

    backdrop()
    // A dark halo round each glyph, so a glyph the colour of its surroundings still has an edge.
    drawScribble(nc, scribble, w, h, min(w, h), p.halo, p.fontMetrics)
    // The glyphs: the backdrop again, kept only where the mask is set, turned over as the layer lands.
    // (A text draw in DST_IN would only touch the pixels it covers, so the mask is a full-size bitmap.)
    val layer = nc.saveLayer(dst, p.invertLayer)
    backdrop()
    nc.drawBitmap(mask, 0f, 0f, p.keepInsideMask)
    nc.restoreToCount(layer)
}

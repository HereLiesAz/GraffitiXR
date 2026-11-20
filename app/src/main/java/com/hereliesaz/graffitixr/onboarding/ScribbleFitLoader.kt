package com.hereliesaz.graffitixr.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hereliesaz.graffitixr.common.util.ImageUtils

/** Longest edge, in pixels, of the decode the draw screen shows: enough for the subject's crop to stay sharp. */
private const val ARTWORK_MAX_PX = 1280

/** Longest edge, in pixels, of the copy the subject's shape is read from: a handful of pixels is enough. */
private const val FIT_MAX_PX = 96

/**
 * The picked artwork as the first-run draw screen uses it: the [bitmap] to show under the glyphs, and
 * the [subject] (its region and aspect, see [ScribbleFit]) the scribble is laid out over.
 */
class FirstRunArtwork(val bitmap: Bitmap, val subject: ScribbleFit.Subject)

/** What the draw screen needs: the [scribble] and, when the picture could be read, the [artwork] under it. */
class FirstRunDraw(val scribble: Scribble, val artwork: FirstRunArtwork?)

/**
 * Decodes the picture at [uri] for the first-run draw screen, or null when it cannot be read. One
 * decode serves both the picture shown and, from a tiny copy of it, the subject's shape
 * (`ImageDecoder` applies the picture's rotation from API 28; earlier, a photo stored sideways with an
 * orientation tag comes out with its sides swapped).
 */
suspend fun loadFirstRunArtwork(context: Context, uri: Uri): FirstRunArtwork? {
    val bitmap = ImageUtils.loadBitmapAsync(context, uri, ARTWORK_MAX_PX) ?: return null
    if (bitmap.width <= 0 || bitmap.height <= 0) return null
    val scale = minOf(1f, FIT_MAX_PX.toFloat() / maxOf(bitmap.width, bitmap.height))
    val w = maxOf(1, (bitmap.width * scale).toInt())
    val h = maxOf(1, (bitmap.height * scale).toInt())
    val small = if (w == bitmap.width && h == bitmap.height) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
    return try {
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        FirstRunArtwork(bitmap, ScribbleFit.subjectOf(pixels, w, h))
    } finally {
        if (small !== bitmap) small.recycle()
    }
}

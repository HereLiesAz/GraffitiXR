package com.hereliesaz.graffitixr.onboarding

import android.content.Context
import android.net.Uri
import com.hereliesaz.graffitixr.common.util.ImageUtils

/** Longest edge, in pixels, of the decode the subject's shape is read from: a handful of pixels is enough. */
private const val FIT_DECODE_MAX_PX = 96

/**
 * The aspect ratio (width / height) the first-run scribble should be drawn in for the picture at
 * [uri]: its subject's, see [ScribbleFit]. 1 when the picture cannot be read.
 *
 * Decodes a tiny copy (`ImageDecoder` applies the picture's rotation from API 28; earlier, a photo
 * stored sideways with an orientation tag comes out with its sides swapped).
 */
suspend fun scribbleAspectFor(context: Context, uri: Uri): Float {
    val bitmap = ImageUtils.loadBitmapAsync(context, uri, FIT_DECODE_MAX_PX) ?: return 1f
    return try {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        ScribbleFit.aspectOfSubject(pixels, w, h)
    } finally {
        bitmap.recycle()
    }
}

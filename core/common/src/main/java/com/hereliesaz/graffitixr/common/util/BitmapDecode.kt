package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Two-pass byte-array decode with a size cap. Solves the pattern Play Console flagged on release
 * 14142 ("manually downloading and decoding images from the network"): the co-op wire ships raw
 * PNG bytes for `LayerBitmapReplace` ops, and a peer accidentally shipping a very large image
 * would otherwise decode at native resolution and OOM the guest.
 *
 * First pass reads `outWidth`/`outHeight` with `inJustDecodeBounds = true` (no pixel allocation).
 * Second pass decodes with an `inSampleSize` that keeps the longest edge at or under [maxDimPx].
 * Returns null if [bytes] is not a valid image; the caller is expected to log and skip the op
 * rather than throw across the op-apply.
 *
 * Doesn't try to replace an image-loading library — Coil is designed for URL/URI-driven loading
 * and adding it here for a byte-array decode would be worse than this direct call.
 */
fun decodeBoundedBitmap(bytes: ByteArray, maxDimPx: Int): Bitmap? {
    if (bytes.isEmpty() || maxDimPx <= 0) return null

    // Pass 1: metadata only.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    // Pass 2: real decode with a power-of-two sample size.
    val decode = BitmapFactory.Options().apply {
        inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDimPx)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
}

/**
 * Smallest power of two that shrinks a [w] x [h] source so the longest edge fits within [maxDimPx].
 * Returns 1 when the source already fits (no over-sampling of a small image).
 *
 * Guarded against a runaway loop on malformed metadata: caps at `1 shl 30` so `sample *= 2` never
 * overflows past `Int.MAX_VALUE` (which would either wrap negative → undefined BitmapFactory
 * behaviour, or wrap through zero → division-by-zero on the next iteration).
 */
internal fun computeSampleSize(w: Int, h: Int, maxDimPx: Int): Int {
    var sample = 1
    while ((w / sample > maxDimPx || h / sample > maxDimPx) && sample < (1 shl 30)) {
        sample *= 2
    }
    return sample
}

package com.hereliesaz.graffitixr.common.util

import android.graphics.Bitmap

/**
 * Recycles this bitmap if it is non-null and not already recycled. Safe to call repeatedly.
 */
fun Bitmap?.safeRecycle() {
    if (this != null && !isRecycled) {
        recycle()
    }
}

/**
 * Holds a single bitmap and recycles the displaced one whenever the slot is reassigned.
 *
 * Centralises the "swap a bitmap into UI state and free the previous one" pattern. Reassigning
 * the same instance is a no-op (the bitmap is not recycled).
 *
 * Do NOT place a bitmap in a [BitmapSlot] if it may still be referenced elsewhere — e.g. one
 * retained by an undo stack, or an alias of the incoming bitmap. For those cases recycle
 * selectively with [safeRecycle] after confirming no other owner holds the reference.
 */
class BitmapSlot {
    var bitmap: Bitmap? = null
        private set

    /**
     * Sets [next] as the current bitmap, recycling the previously held bitmap unless it is the
     * same instance as [next].
     */
    fun set(next: Bitmap?) {
        val previous = bitmap
        if (previous === next) return
        bitmap = next
        previous.safeRecycle()
    }

    /** Recycles and clears the held bitmap. */
    fun clear() {
        bitmap.safeRecycle()
        bitmap = null
    }
}

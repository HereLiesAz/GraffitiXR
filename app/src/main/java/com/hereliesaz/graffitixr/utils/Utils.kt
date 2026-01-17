package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader

// --- Single Source of Truth for OpenCV Loading ---
fun ensureOpenCVLoaded(): Boolean {
    try {
        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "Unable to load OpenCV!")
            return false
        }
        return true
    } catch (e: Throwable) {
        Log.e("OpenCV", "OpenCV load failed", e)
        return false
    }
}

fun resizeBitmapForArCore(bitmap: Bitmap): Bitmap {
    val MAX_DIMENSION = 1024
    if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap

    val ratio = Math.min(
        MAX_DIMENSION.toFloat() / bitmap.width,
        MAX_DIMENSION.toFloat() / bitmap.height
    )
    val width = (bitmap.width * ratio).toInt()
    val height = (bitmap.height * ratio).toInt()

    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

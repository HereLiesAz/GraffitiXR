package com.hereliesaz.graffitixr.nativebridge

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

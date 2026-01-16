package com.hereliesaz.graffitixr.utils

import android.util.Log
import org.opencv.android.OpenCVLoader

object OpenCVUtils {
    private const val TAG = "OpenCVUtils"
    private var isLoaded = false

    @Synchronized
    fun ensureOpenCVLoaded(): Boolean {
        if (isLoaded) return true
        return try {
            if (OpenCVLoader.initLocal()) {
                isLoaded = true
                true
            } else {
                Log.e(TAG, "OpenCV initLocal returned false")
                false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to load OpenCV", e)
            false
        }
    }
}

// Global helper function to match existing calls across the app
fun ensureOpenCVLoaded() = OpenCVUtils.ensureOpenCVLoaded()
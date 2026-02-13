package com.hereliesaz.graffitixr.nativebridge

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * Utility function to initialize the OpenCV library.
 * This function ensures that the OpenCV native libraries are loaded correctly
 * using [OpenCVLoader.initLocal()].
 *
 * It is recommended to call this function early in the application lifecycle,
 * for example in [Application.onCreate].
 *
 * @return `true` if OpenCV was initialized successfully, `false` otherwise.
 */
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

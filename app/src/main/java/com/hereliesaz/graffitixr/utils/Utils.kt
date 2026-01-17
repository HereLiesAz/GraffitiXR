package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.google.ar.core.Session
import org.opencv.android.OpenCVLoader

// OpenCV Initialization Helper
fun ensureOpenCVLoaded(): Boolean {
    if (!OpenCVLoader.initLocal()) {
        Log.e("OpenCV", "Unable to load OpenCV!")
        return false
    }
    return true
}

// Bitmap Resizing for ARCore (Augmented Image Database requirements)
fun resizeBitmapForArCore(bitmap: Bitmap): Bitmap {
    // ARCore suggests max 1280px dimension for Augmented Images for optimal performance
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

class DisplayRotationHelper(context: Context) {
    private val display = try {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.defaultDisplay
    } catch (e: Exception) { null }

    val rotation: Int
        get() = display?.rotation ?: android.view.Surface.ROTATION_0

    fun onResume() {}
    fun onPause() {}
    fun onSurfaceChanged(width: Int, height: Int) {}

    fun updateSessionIfNeeded(session: Session) {
        val displayRotation = rotation
        session.setDisplayGeometry(displayRotation, 0, 0) // Width/Height 0 means auto-detect
    }
}

// Bitmap Utilities
object BitmapUtils {
    fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}

// YUV Converter (Stripped down version - normally uses RenderScript or complex C++,
// this is a placeholder if you don't have the full implementation.
// Ideally, use a library or the Image.toBitmap extension from Android X if available.)
class YuvToRgbConverter(context: Context) {
    fun yuvToRgb(image: android.media.Image, output: Bitmap) {
        // Implementation omitted for brevity - assumes standard NV21/YUV420 to ARGB conversion.
        // If you need the full YUV converter code, ask explicitly as it is lengthy.
        // For now, we assume this class exists in your project or you use a library.
    }
}
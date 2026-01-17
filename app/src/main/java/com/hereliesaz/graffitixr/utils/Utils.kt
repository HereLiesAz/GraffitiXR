package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.util.Log
import com.google.ar.core.Session
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

class DisplayRotationHelper(context: Context) : DisplayManager.DisplayListener {
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val display = try {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.defaultDisplay
    } catch (e: Exception) { null }

    val rotation: Int
        get() = display?.rotation ?: android.view.Surface.ROTATION_0

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    fun onSurfaceChanged(width: Int, height: Int) {}

    fun updateSessionIfNeeded(session: Session) {
        val displayRotation = rotation
        session.setDisplayGeometry(displayRotation, 0, 0)
    }

    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}
    override fun onDisplayChanged(displayId: Int) {
        // Handle rotation changes if needed
    }
}

object BitmapUtils {
    fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) return source
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
package com.hereliesaz.graffitixr.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Window

/**
 * Captures the current window content as a Bitmap.
 *
 * Explicitly validates that the decorView dimensions are positive before attempting to create a Bitmap,
 * preventing IllegalArgumentException on devices where the view layout might not be complete or valid.
 *
 * @param activity The target activity to capture.
 * @param callback invoked with the captured Bitmap, or null if capture failed or dimensions were invalid.
 */
fun captureWindow(activity: Activity, callback: (Bitmap?) -> Unit) {
    val window: Window = activity.window ?: return callback(null)
    val view = window.decorView

    val width = view.width
    val height = view.height

    // Prevent crash: Bitmap.createBitmap throws IllegalArgumentException if width or height are <= 0
    if (width <= 0 || height <= 0) {
        Log.w("Capture", "Invalid window dimensions: ${width}x${height}")
        callback(null)
        return
    }

    try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        PixelCopy.request(
            window,
            Rect(0, 0, width, height),
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    callback(bitmap)
                } else {
                    Log.e("Capture", "PixelCopy failed with result: $copyResult")
                    callback(null)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } catch (e: Exception) {
        Log.e("Capture", "Exception during captureWindow", e)
        callback(null)
    }
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "GraffitiXR_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GraffitiXR")
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return false

    return try {
        resolver.openOutputStream(uri)?.let {
            it.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }
        } ?: false
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

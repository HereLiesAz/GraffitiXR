package com.hereliesaz.graffitixr.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
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
    val window: Window = activity.window
    val view = window.decorView

    // Prevent crash: Bitmap.createBitmap throws IllegalArgumentException if width or height are <= 0
    if (view.width <= 0 || view.height <= 0) {
        callback(null)
        return
    }

    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

    try {
        PixelCopy.request(
            window,
            Rect(0, 0, view.width, view.height),
            bitmap,
            { copyResult ->
                if (copyResult == PixelCopy.SUCCESS) {
                    callback(bitmap)
                } else {
                    callback(null)
                }
            },
            Handler(Looper.getMainLooper())
        )
    } catch (e: Exception) {
        callback(null)
        e.printStackTrace()
    }
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Boolean {
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "GraffitiXR_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GraffitiXR")
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

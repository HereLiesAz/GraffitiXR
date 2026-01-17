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
import android.view.PixelCopy
import android.view.Window

fun captureWindow(activity: Activity, callback: (Bitmap?) -> Unit) {
    val window: Window = activity.window

    if (window.decorView.width <= 0 || window.decorView.height <= 0) {
        callback(null)
        return
    }

    val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
    val locationOfViewInWindow = IntArray(2)
    window.decorView.getLocationInWindow(locationOfViewInWindow)
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopy.request(
                window,
                Rect(0, 0, window.decorView.width, window.decorView.height),
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
        }
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

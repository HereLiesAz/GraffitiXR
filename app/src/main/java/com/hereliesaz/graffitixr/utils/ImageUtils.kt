package com.hereliesaz.graffitixr.utils

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.widget.Toast
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Captures the content of the current window as a Bitmap.
 * This is useful for capturing scenes that include SurfaceViews, like the AR camera feed.
 *
 * @param activity The activity whose window to capture.
 * @param callback A callback to receive the bitmap once it's ready.
 */
fun captureWindow(activity: Activity, callback: (Bitmap?) -> Unit) {
    val view = activity.window.decorView.rootView
    if (view.width == 0 || view.height == 0) {
        // The view has not been measured yet.
        callback(null)
        return
    }
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)

    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locationOfViewInWindow = IntArray(2)
            view.getLocationInWindow(locationOfViewInWindow)
            PixelCopy.request(
                activity.window,
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
        } else {
            // Fallback for older APIs. This might not capture SurfaceViews correctly.
            callback(view.drawToBitmap())
        }
    } catch (e: IllegalArgumentException) {
        // This can happen if the window is not available.
        callback(null)
    }
}

/**
 * Saves a bitmap to the device's gallery.
 *
 * @param context The context.
 * @param bitmap The bitmap to save.
 * @param displayName The name for the saved image file.
 */
suspend fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap,
    displayName: String = "GraffitiXR_${System.currentTimeMillis()}.jpg"
) {
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val imageCollection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        var imageUri: Uri? = null
        try {
            imageUri = resolver.insert(imageCollection, contentValues)
            imageUri?.let { uri ->
                resolver.openOutputStream(uri)?.use { outputStream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                        throw Exception("Failed to save bitmap.")
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Image saved to gallery", Toast.LENGTH_SHORT).show()
                }
            } ?: throw Exception("MediaStore returned null URI")
        } catch (e: Exception) {
            e.printStackTrace()
            // Clean up if something went wrong
            imageUri?.let { resolver.delete(it, null, null) }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save image: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
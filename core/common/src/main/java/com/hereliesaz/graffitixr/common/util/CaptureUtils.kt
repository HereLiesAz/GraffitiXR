package com.hereliesaz.graffitixr.common.util

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Window
import timber.log.Timber

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

/** Outcome of [saveBitmapToGallery]; lets the caller say WHY an export failed. */
enum class GallerySaveResult {
    SAVED,

    /** API 26-28 only: `WRITE_EXTERNAL_STORAGE` is not granted, so MediaStore refused the insert. */
    PERMISSION_DENIED,

    /** Anything else: no row could be created, no stream opened, or the PNG could not be encoded. */
    FAILED,
}

/**
 * Saves [bitmap] as a PNG under Pictures/GraffitiXR through MediaStore.
 *
 * Below API 29 MediaStore inserts into the shared Pictures collection need `WRITE_EXTERNAL_STORAGE`
 * (declared with `maxSdkVersion="28"`; the caller requests it before exporting). From API 29 the
 * app owns the rows it inserts and no permission is needed. A refusal is reported as
 * [GallerySaveResult.PERMISSION_DENIED] rather than a generic failure.
 *
 * The insert is inside the same guard as the write, and a row whose image could not be written is
 * deleted again, so a failed export never leaves an empty entry in the user's gallery.
 */
fun saveBitmapToGallery(context: Context, bitmap: Bitmap): GallerySaveResult {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        return GallerySaveResult.PERMISSION_DENIED
    }

    val resolver = context.contentResolver
    var uri: Uri? = null
    return try {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "GraffitiXR_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GraffitiXR")
            }
        }
        uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        val target = uri
        if (target == null) {
            Timber.e("CaptureUtils: MediaStore returned no row for the export")
            GallerySaveResult.FAILED
        } else {
            val written = resolver.openOutputStream(target)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            } ?: false
            if (written) {
                GallerySaveResult.SAVED
            } else {
                Timber.e("CaptureUtils: could not write the PNG to $target")
                deleteRow(resolver, target)
                GallerySaveResult.FAILED
            }
        }
    } catch (e: SecurityException) {
        Timber.e(e, "CaptureUtils: gallery write was refused")
        uri?.let { deleteRow(resolver, it) }
        GallerySaveResult.PERMISSION_DENIED
    } catch (e: Exception) {
        Timber.e(e, "CaptureUtils: failed to save bitmap")
        uri?.let { deleteRow(resolver, it) }
        GallerySaveResult.FAILED
    }
}

/** Best-effort removal of a MediaStore row whose image never got written. */
private fun deleteRow(resolver: ContentResolver, uri: Uri) {
    try {
        resolver.delete(uri, null, null)
    } catch (e: Exception) {
        Timber.w(e, "CaptureUtils: could not remove the incomplete gallery entry")
    }
}

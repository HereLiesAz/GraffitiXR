package com.hereliesaz.graffitixr

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.PixelCopy
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

fun captureScreenshot(window: android.view.Window, onCaptured: (Bitmap) -> Unit) {
    val view = window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    try {
        PixelCopy.request(window, android.graphics.Rect(0, 0, view.width, view.height), bitmap, { copyResult ->
            if (copyResult == PixelCopy.SUCCESS) {
                onCaptured(bitmap)
            }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    } catch (e: IllegalArgumentException) {
        e.printStackTrace()
    }
}

fun saveExportedImage(context: android.content.Context, bitmap: Bitmap) {
    val filename = "GraffitiXR_Export_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GraffitiXR")
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    uri?.let {
        resolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

fun saveBitmapToCache(context: android.content.Context, bitmap: Bitmap): android.net.Uri? {
    val filename = "Target_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val file = File(context.cacheDir, filename)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    return androidx.core.content.FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
}

fun mapBlendMode(mode: ModelBlendMode): BlendMode {
    return when(mode) {
        ModelBlendMode.SrcOver -> BlendMode.SrcOver
        ModelBlendMode.Multiply -> BlendMode.Multiply
        ModelBlendMode.Screen -> BlendMode.Screen
        ModelBlendMode.Overlay -> BlendMode.Overlay
        ModelBlendMode.Darken -> BlendMode.Darken
        ModelBlendMode.Lighten -> BlendMode.Lighten
        ModelBlendMode.ColorDodge -> BlendMode.ColorDodge
        ModelBlendMode.ColorBurn -> BlendMode.ColorBurn
        ModelBlendMode.Difference -> BlendMode.Difference
        ModelBlendMode.Exclusion -> BlendMode.Exclusion
        ModelBlendMode.Hue -> BlendMode.Hue
        ModelBlendMode.Saturation -> BlendMode.Saturation
        ModelBlendMode.Color -> BlendMode.Color
        ModelBlendMode.Luminosity -> BlendMode.Luminosity
        ModelBlendMode.Clear -> BlendMode.Clear
        ModelBlendMode.Src -> BlendMode.Src
        ModelBlendMode.Dst -> BlendMode.Dst
        ModelBlendMode.DstOver -> BlendMode.DstOver
        ModelBlendMode.SrcIn -> BlendMode.SrcIn
        ModelBlendMode.DstIn -> BlendMode.DstIn
        ModelBlendMode.SrcOut -> BlendMode.SrcOut
        ModelBlendMode.DstOut -> BlendMode.DstOut
        ModelBlendMode.SrcAtop -> BlendMode.SrcAtop
        ModelBlendMode.DstAtop -> BlendMode.DstAtop
        ModelBlendMode.Xor -> BlendMode.Xor
        ModelBlendMode.Plus -> BlendMode.Plus
        ModelBlendMode.Modulate -> BlendMode.Modulate
        else -> BlendMode.SrcOver
    }
}
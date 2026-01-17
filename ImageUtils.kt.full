package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }
    }

    fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri {
        val file = File(context.cacheDir, "layer_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return getUriForFile(file)
    }

    fun getUriForFile(file: File): Uri {
        return Uri.fromFile(file)
    }

    fun getNextBlendMode(current: androidx.compose.ui.graphics.BlendMode): androidx.compose.ui.graphics.BlendMode {
        val modes = listOf(
            androidx.compose.ui.graphics.BlendMode.SrcOver,
            androidx.compose.ui.graphics.BlendMode.Screen,
            androidx.compose.ui.graphics.BlendMode.Multiply,
            androidx.compose.ui.graphics.BlendMode.Overlay,
            androidx.compose.ui.graphics.BlendMode.Darken,
            androidx.compose.ui.graphics.BlendMode.Lighten,
            androidx.compose.ui.graphics.BlendMode.ColorDodge,
            androidx.compose.ui.graphics.BlendMode.ColorBurn,
            androidx.compose.ui.graphics.BlendMode.Hardlight,
            androidx.compose.ui.graphics.BlendMode.Softlight,
            androidx.compose.ui.graphics.BlendMode.Difference,
            androidx.compose.ui.graphics.BlendMode.Exclusion,
            androidx.compose.ui.graphics.BlendMode.Hue,
            androidx.compose.ui.graphics.BlendMode.Saturation,
            androidx.compose.ui.graphics.BlendMode.Color,
            androidx.compose.ui.graphics.BlendMode.Luminosity
        )
        val index = modes.indexOf(current)
        return modes.getOrElse((index + 1) % modes.size) { androidx.compose.ui.graphics.BlendMode.SrcOver }
    }
}

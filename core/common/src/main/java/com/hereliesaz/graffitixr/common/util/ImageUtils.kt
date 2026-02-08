package com.hereliesaz.graffitixr.common.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object ImageUtils {

    fun getBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri {
        val filename = "layer_${UUID.randomUUID()}.png"
        val file = File(context.cacheDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(file)
    }

    // Cycles through available BlendModes for the UI
    fun getNextBlendMode(current: String): String {
        val modes = listOf(
            "SrcOver", "Multiply", "Screen", "Overlay",
            "Darken", "Lighten", "ColorDodge", "ColorBurn",
            "HardLight", "SoftLight", "Difference", "Exclusion",
            "Hue", "Saturation", "Color", "Luminosity"
        )
        val index = modes.indexOf(current)
        return if (index == -1 || index == modes.lastIndex) {
            modes[0]
        } else {
            modes[index + 1]
        }
    }
}
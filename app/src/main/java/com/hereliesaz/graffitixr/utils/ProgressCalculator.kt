package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb

fun calculateProgress(paths: List<Path>, width: Int, height: Int): Float {
    if (width == 0 || height == 0) return 0f

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        color = Color.Red.toArgb()
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    paths.forEach { path ->
        canvas.drawPath(path.asAndroidPath(), paint)
    }

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val coloredPixels = pixels.count { it != 0 }

    return (coloredPixels.toFloat() / (width * height).toFloat()) * 100
}

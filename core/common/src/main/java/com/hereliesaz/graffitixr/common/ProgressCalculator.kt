package com.hereliesaz.graffitixr.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb

fun calculateProgress(paths: List<Path>, bitmap: Bitmap): Int {
    val width = bitmap.width
    val height = bitmap.height
    if (width == 0 || height == 0) return 0

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

    return pixels.count { it != 0 }
}

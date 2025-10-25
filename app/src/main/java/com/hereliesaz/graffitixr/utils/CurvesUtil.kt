package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun applyCurves(bitmap: Bitmap, points: List<Offset>): Bitmap = withContext(Dispatchers.Default) {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val lut = createLut(points)

    for (i in pixels.indices) {
        val color = pixels[i]
        val r = lut[Color.red(color)]
        val g = lut[Color.green(color)]
        val b = lut[Color.blue(color)]
        pixels[i] = Color.argb(Color.alpha(color), r, g, b)
    }

    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun createLut(points: List<Offset>): IntArray {
    val lut = IntArray(256)
    val sortedPoints = points.sortedBy { it.x }

    for (i in 0..255) {
        val x = i / 255f
        val p1 = sortedPoints.lastOrNull { it.x <= x } ?: sortedPoints.first()
        val p2 = sortedPoints.firstOrNull { it.x >= x } ?: sortedPoints.last()

        val y = if (p1 == p2) {
            p1.y
        } else {
            val t = (x - p1.x) / (p2.x - p1.x)
            p1.y + t * (p2.y - p1.y)
        }
        lut[i] = (y * 255).toInt().coerceIn(0, 255)
    }

    return lut
}

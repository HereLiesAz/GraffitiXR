package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path

/**
 * Utility to calculate the progress of an artwork based on drawn paths.
 */
fun calculateProgress(paths: List<Path>, bitmap: Bitmap): Int {
    if (bitmap.width == 0 || bitmap.height == 0) return 0
    
    val canvas = Canvas(bitmap)
    val paint = Paint().apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    paths.forEach { canvas.drawPath(it, paint) }
    
    var count = 0
    for (x in 0 until bitmap.width) {
        for (y in 0 until bitmap.height) {
            if (bitmap.getPixel(x, y) != 0) count++
        }
    }
    
    return count
}

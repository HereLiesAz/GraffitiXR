package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object GuideGenerator {
    fun generateGrid(rows: Int, cols: Int, width: Int = 1024, height: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Clear transparent
        bitmap.eraseColor(Color.TRANSPARENT)

        val paint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 10f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        // Draw border
        canvas.drawRect(5f, 5f, width.toFloat() - 5f, height.toFloat() - 5f, paint)

        // Draw rows
        val rowHeight = height.toFloat() / rows
        for (i in 1 until rows) {
            val y = i * rowHeight
            canvas.drawLine(0f, y, width.toFloat(), y, paint)
        }

        // Draw cols
        val colWidth = width.toFloat() / cols
        for (i in 1 until cols) {
            val x = i * colWidth
            canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        }

        return bitmap
    }

    fun generateFourXs(width: Int = 1024, height: Int = 1024): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Clear transparent
        bitmap.eraseColor(Color.TRANSPARENT)

        val paint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 15f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        val padding = 200f
        val points = listOf(
            Pair(padding, padding), // Top-Left
            Pair(width - padding, padding), // Top-Right
            Pair(padding, height - padding), // Bottom-Left
            Pair(width - padding, height - padding) // Bottom-Right
        )

        val xSize = 50f
        for ((cx, cy) in points) {
            canvas.drawLine(cx - xSize, cy - xSize, cx + xSize, cy + xSize, paint)
            canvas.drawLine(cx + xSize, cy - xSize, cx - xSize, cy + xSize, paint)
        }

        return bitmap
    }
}

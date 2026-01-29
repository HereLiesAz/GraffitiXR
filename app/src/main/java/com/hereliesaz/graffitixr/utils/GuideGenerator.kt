package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

object GuideGenerator {

    /**
     * Generates a transparent Bitmap containing a grid with labeled cells (A1, A2, B1...).
     * This simulates the traditional "Grid Method" used by muralists.
     * * @param rows Number of horizontal rows
     * @param cols Number of vertical columns
     * @return A transparent Bitmap with drawn grid lines and labels.
     */
    fun generateGrid(rows: Int, cols: Int): Bitmap {
        // High resolution for quality scaling on walls
        val width = 2048
        // Calculate height based on a square ratio per cell or just square overall?
        // Usually screen aspect, but for a "Grid Guide", square or 4:3 is safer.
        // Let's assume a standard aspect or based on input.
        // For a generic guide, we'll keep the bitmap square-ish or match row/col ratio.
        val height = (width * (rows.toFloat() / cols.toFloat())).toInt()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Paint for lines
        val linePaint = Paint().apply {
            color = Color.CYAN // High visibility
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        // Paint for text (Cell Labels: A1, B2, etc.)
        val textPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 64f
            typeface = Typeface.DEFAULT_BOLD
            style = Paint.Style.FILL
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            textAlign = Paint.Align.CENTER
        }

        val cellWidth = width.toFloat() / cols
        val cellHeight = height.toFloat() / rows

        // Draw Vertical Lines
        for (c in 0..cols) {
            val x = c * cellWidth
            canvas.drawLine(x, 0f, x, height.toFloat(), linePaint)
        }

        // Draw Horizontal Lines
        for (r in 0..rows) {
            val y = r * cellHeight
            canvas.drawLine(0f, y, width.toFloat(), y, linePaint)
        }

        // Draw Cell Labels (A1, A2, etc.) in the center of each cell
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                // Char magic: 0 -> A, 1 -> B
                // Limitation: After 26 columns, this logic needs simpler numbering or AA/AB.
                // For a mobile app mural tool, >26 cols is rare.
                val rowLabel = (65 + r).toChar().toString() // A, B, C...
                val colLabel = (c + 1).toString() // 1, 2, 3...
                val label = "$rowLabel$colLabel"

                val centerX = (c * cellWidth) + (cellWidth / 2)
                val centerY = (r * cellHeight) + (cellHeight / 2) - ((textPaint.descent() + textPaint.ascent()) / 2)

                canvas.drawText(label, centerX, centerY, textPaint)
            }
        }

        // Draw Border
        val borderPaint = Paint().apply {
            color = Color.MAGENTA
            strokeWidth = 10f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), borderPaint)

        return bitmap
    }

    /**
     * Generates a calibration pattern with 4 "X" marks at the corners.
     * Used for the "Guided Points" target creation mode.
     */
    fun generateFourXs(): Bitmap {
        val size = 1024
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 12f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            setShadowLayer(5f, 0f, 0f, Color.BLACK)
        }

        val padding = 100f
        val len = 60f

        // Helper to draw X at x,y
        fun drawX(cx: Float, cy: Float) {
            canvas.drawLine(cx - len, cy - len, cx + len, cy + len, paint)
            canvas.drawLine(cx + len, cy - len, cx - len, cy + len, paint)
            // Draw circle indicator
            canvas.drawCircle(cx, cy, len * 1.5f, paint)
        }

        // Top-Left
        drawX(padding, padding)
        // Top-Right
        drawX(size - padding, padding)
        // Bottom-Right
        drawX(size - padding, size - padding)
        // Bottom-Left
        drawX(padding, size - padding)

        return bitmap
    }
}
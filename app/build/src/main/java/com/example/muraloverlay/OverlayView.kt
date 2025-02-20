package com.example.muraloverlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1f
        alpha = 100
    }

    private val edgePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private var gridSize = 3
    private var edgePath: Path? = null

    fun updateEdges(path: Path?) {
        edgePath = path
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw grid
        if (visibility == VISIBLE) {
            val width = width.toFloat()
            val height = height.toFloat()
            val stepX = width / (gridSize + 1)
            val stepY = height / (gridSize + 1)

            for (i in 1..gridSize) {
                canvas.drawLine(stepX * i, 0f, stepX * i, height, gridPaint)
                canvas.drawLine(0f, stepY * i, width, stepY * i, gridPaint)
            }
        }

        // Draw edges
        edgePath?.let { canvas.drawPath(it, edgePaint) }
    }
}
package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin UI layer implementation of image manipulation tools.
 * Routes heavy operations (Liquify, Heal, Burn) directly to C++ via JNI.
 */
object ImageProcessor {

    /**
     * Maps screen-space touch coordinates to pixel-space bitmap coordinates,
     * accounting for Compose's ContentScale.Fit logic used in the UI.
     */
    fun mapScreenToBitmap(
        stroke: List<Offset>,
        screenWidth: Int,
        screenHeight: Int,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): List<Offset> {
        val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

        var renderWidth = screenWidth.toFloat()
        var renderHeight = screenHeight.toFloat()
        var offsetX = 0f
        var offsetY = 0f

        if (imageAspect > screenAspect) {
            renderHeight = renderWidth / imageAspect
            offsetY = (screenHeight - renderHeight) / 2f
        } else {
            renderWidth = renderHeight * imageAspect
            offsetX = (screenWidth - renderWidth) / 2f
        }

        val scaleX = bitmapWidth / renderWidth
        val scaleY = bitmapHeight / renderHeight

        return stroke.map { pt ->
            Offset(
                (pt.x - offsetX) * scaleX,
                (pt.y - offsetY) * scaleY
            )
        }
    }

    suspend fun applyToolToBitmap(
        originalBitmap: Bitmap,
        stroke: List<Offset>,
        tool: Tool,
        brushSize: Float = 50f,
        brushColor: Int = Color.BLACK,
        intensity: Float = 0.5f,
        slamManager: SlamManager
    ): Bitmap = withContext(Dispatchers.Default) {
        if (stroke.isEmpty()) return@withContext originalBitmap

        // Create a mutable hardware-accelerated compatible bitmap copy
        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        // Convert Offset list to flat float array for JNI
        val flatPoints = FloatArray(stroke.size * 2)
        for (i in stroke.indices) {
            flatPoints[i * 2] = stroke[i].x
            flatPoints[i * 2 + 1] = stroke[i].y
        }

        when (tool) {
            Tool.BRUSH -> {
                val paint = Paint().apply {
                    color = brushColor
                    strokeWidth = brushSize
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                }
                drawStroke(canvas, stroke, paint)
            }
            Tool.ERASER -> {
                val paint = Paint().apply {
                    strokeWidth = brushSize
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
                drawStroke(canvas, stroke, paint)
            }
            Tool.BLUR -> {
                // Keep simple blur in Kotlin using scaled Paint strokes for speed,
                // or just call HEAL natively for complex blending.
                slamManager.applyHeal(resultBitmap, flatPoints, brushSize)
            }
            Tool.LIQUIFY -> {
                slamManager.applyLiquify(resultBitmap, flatPoints, brushSize, intensity)
            }
            Tool.HEAL -> {
                slamManager.applyHeal(resultBitmap, flatPoints, brushSize)
            }
            Tool.BURN -> {
                slamManager.applyBurnDodge(resultBitmap, flatPoints, brushSize, intensity, isBurn = true)
            }
            Tool.DODGE -> {
                slamManager.applyBurnDodge(resultBitmap, flatPoints, brushSize, intensity, isBurn = false)
            }
            else -> {}
        }

        resultBitmap
    }

    private fun drawStroke(canvas: Canvas, stroke: List<Offset>, paint: Paint) {
        if (stroke.size == 1) {
            canvas.drawPoint(stroke.first().x, stroke.first().y, paint)
            return
        }
        val path = android.graphics.Path()
        path.moveTo(stroke.first().x, stroke.first().y)
        for (i in 1 until stroke.size) {
            path.lineTo(stroke[i].x, stroke[i].y)
        }
        canvas.drawPath(path, paint)
    }
}
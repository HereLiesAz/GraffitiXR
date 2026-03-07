package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.BlurMaskFilter
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin UI layer implementation of image manipulation tools.
  * Native Android hardware-accelerated 2D pipeline.
   * JNI boundary calls have been fully eradicated.
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
                        mutateInPlace: Boolean = false
                    ): Bitmap = withContext(Dispatchers.Default) {
                        if (stroke.isEmpty()) return@withContext originalBitmap

                        val resultBitmap = if (mutateInPlace) originalBitmap
                        else originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
                                val canvas = Canvas(resultBitmap)

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
                                                                                                    val paint = Paint().apply {
                                                                                                                            strokeWidth = brushSize
                                                                                                                            style = Paint.Style.STROKE
                                                                                                                            strokeCap = Paint.Cap.ROUND
                                                                                                                            strokeJoin = Paint.Join.ROUND
                                                                                                                            isAntiAlias = true
                                                                                                                            maskFilter = BlurMaskFilter(brushSize * intensity.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
                                                                                                                                                alpha = 150
                                                                                                    }
                                                                                                                    drawStroke(canvas, stroke, paint)
                                                                                }

                                                                                            Tool.LIQUIFY -> {
                                                                                                                applyLiquifyNative(resultBitmap, stroke, brushSize, intensity)
                                                                                            }

                                                                                                        Tool.HEAL -> {
                                                                                                                            val paint = Paint().apply {
                                                                                                                                                    color = brushColor
                                                                                                                                                    strokeWidth = brushSize
                                                                                                                                                    style = Paint.Style.STROKE
                                                                                                                                                    strokeCap = Paint.Cap.ROUND
                                                                                                                                                    strokeJoin = Paint.Join.ROUND
                                                                                                                                                    isAntiAlias = true
                                                                                                                                                    alpha = 128
                                                                                                                            }
                                                                                                                                            drawStroke(canvas, stroke, paint)
                                                                                                        }
                                                                                                        
                                                                                                                    Tool.BURN -> {
                                                                                                                                        val paint = Paint().apply {
                                                                                                                                                                color = Color.BLACK
                                                                                                                                                                strokeWidth = brushSize
                                                                                                                                                                style = Paint.Style.STROKE
                                                                                                                                                                strokeCap = Paint.Cap.ROUND
                                                                                                                                                                strokeJoin = Paint.Join.ROUND
                                                                                                                                                                alpha = (255 * intensity * 0.3f).toInt().coerceIn(0, 255)
                                                                                                                                                                                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                                                                                                                                        }
                                                                                                                                                        drawStroke(canvas, stroke, paint)
                                                                                                                    }
                                                                                                                    
                                                                                                                                Tool.DODGE -> {
                                                                                                                                                    val paint = Paint().apply {
                                                                                                                                                                            color = Color.WHITE
                                                                                                                                                                            strokeWidth = brushSize
                                                                                                                                                                            style = Paint.Style.STROKE
                                                                                                                                                                            strokeCap = Paint.Cap.ROUND
                                                                                                                                                                            strokeJoin = Paint.Join.ROUND
                                                                                                                                                                            alpha = (255 * intensity * 0.3f).toInt().coerceIn(0, 255)
                                                                                                                                                                                                xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                                                                                                                                                                                                                }
                                                                                                                                                                    drawStroke(canvas, stroke, paint)
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

                    private fun applyLiquifyNative(bitmap: Bitmap, stroke: List<Offset>, brushSize: Float, intensity: Float) {
                                val canvas = Canvas(bitmap)
                                        val meshWidth = 20
                                val meshHeight = 20
                                val verts = FloatArray((meshWidth + 1) * (meshHeight + 1) * 2)
                                        var index = 0
                                for (y in 0..meshHeight) {
                                                val fy = y.toFloat() / meshHeight * bitmap.height
                                                for (x in 0..meshWidth) {
                                                                    val fx = x.toFloat() / meshWidth * bitmap.width
                                                                    var dx = 0f
                                                                    var dy = 0f
                                                                    stroke.forEach { pt ->
                                                                                            val dist = Math.hypot((fx - pt.x).toDouble(), (fy - pt.y).toDouble()).toFloat()
                                                                                                                if (dist < brushSize) {
                                                                                                                                            val force = (1f - dist / brushSize) * intensity * 10f
                                                                                                                                            dx += force
                                                                                                                                            dy += force
                                                                                                                }
                                                                    }
                                                                                    verts[index++] = fx + dx
                                                                    verts[index++] = fy + dy
                                                }
                                }
                                        val tempBmp = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                                                        canvas.drawBitmapMesh(tempBmp, meshWidth, meshHeight, verts, 0, null, 0, null)
                    }
}

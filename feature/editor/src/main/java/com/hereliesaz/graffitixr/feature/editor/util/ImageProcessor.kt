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
                    bitmapHeight: Int,
                    layerScale: Float = 1f,
                    layerOffset: Offset = Offset.Zero,
                    layerRotationZ: Float = 0f
                ): List<Offset> {
                    val screenCx = screenWidth / 2f
                    val screenCy = screenHeight / 2f

                    // Precompute inverse rotation (negate the layer's rotationZ).
                    val angleRad = Math.toRadians(-layerRotationZ.toDouble())
                    val cosA = Math.cos(angleRad).toFloat()
                    val sinA = Math.sin(angleRad).toFloat()

                    // ContentScale.Fit: compute how the bitmap is letterboxed into the full screen.
                    val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
                    val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
                    val renderWidth: Float
                    val renderHeight: Float
                    if (imageAspect > screenAspect) {
                        renderWidth = screenWidth.toFloat()
                        renderHeight = screenWidth / imageAspect
                    } else {
                        renderHeight = screenHeight.toFloat()
                        renderWidth = screenHeight * imageAspect
                    }
                    val fitOffX = (screenWidth - renderWidth) / 2f
                    val fitOffY = (screenHeight - renderHeight) / 2f
                    val fitScaleX = bitmapWidth / renderWidth
                    val fitScaleY = bitmapHeight / renderHeight

                    return stroke.map { pt ->
                        // Step 1: Move to pivot-relative coords and undo layer translation.
                        val dx = pt.x - screenCx - layerOffset.x
                        val dy = pt.y - screenCy - layerOffset.y

                        // Step 2: Undo layer rotationZ.
                        val rx = dx * cosA - dy * sinA
                        val ry = dx * sinA + dy * cosA

                        // Step 3: Undo layer scale.
                        val ux = rx / layerScale
                        val uy = ry / layerScale

                        // Step 4: Back to layout space, then undo ContentScale.Fit letterboxing.
                        val lx = ux + screenCx
                        val ly = uy + screenCy
                        Offset(
                            (lx - fitOffX) * fitScaleX,
                            (ly - fitOffY) * fitScaleY
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
                        mutateInPlace: Boolean = false,
                        feathering: Float = 0f
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
                                                                                                    if (feathering > 0f) {
                                                                                                        maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                                                                                    }
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
                                                                                                                if (feathering > 0f) {
                                                                                                                    maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                                                                                                }
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

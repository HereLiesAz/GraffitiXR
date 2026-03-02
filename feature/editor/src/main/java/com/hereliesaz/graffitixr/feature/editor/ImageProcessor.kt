package com.hereliesaz.graffitixr.feature.editor.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp

/**
 * Pure Kotlin/Android UI layer implementation of image manipulation tools.
 * Eliminates the need to marshal 2D bitmaps across the JNI bridge to MobileGS.
 * Handles standard brushing as well as advanced distortion tools (Liquify, Heal, Burn).
 */
object ImageProcessor {

    /**
     * Applies the selected tool effect along the stroke path onto the given bitmap.
     * Operates purely in the UI layer on a background coroutine dispatcher.
     */
    suspend fun applyToolToBitmap(
        originalBitmap: Bitmap,
        stroke: List<Offset>,
        tool: Tool,
        brushSize: Float = 50f,
        brushColor: Int = Color.BLACK,
        intensity: Float = 0.5f
    ): Bitmap = withContext(Dispatchers.Default) {
        if (stroke.isEmpty()) return@withContext originalBitmap

        // Create a mutable hardware-accelerated compatible bitmap copy
        val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
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
                applyRenderEffectBlur(resultBitmap, stroke, brushSize)
            }
            Tool.LIQUIFY -> {
                applyLiquify(resultBitmap, stroke, brushSize, intensity)
            }
            Tool.HEAL -> {
                applyHeal(resultBitmap, stroke, brushSize)
            }
            Tool.BURN -> {
                val paint = Paint().apply {
                    strokeWidth = brushSize
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                    color = Color.argb((50 * intensity).toInt(), 0, 0, 0)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                }
                drawStroke(canvas, stroke, paint)
            }
            Tool.DODGE -> {
                val paint = Paint().apply {
                    strokeWidth = brushSize
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    isAntiAlias = true
                    color = Color.argb((50 * intensity).toInt(), 255, 255, 255)
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

    private fun applyLiquify(bitmap: Bitmap, stroke: List<Offset>, radius: Float, intensity: Float) {
        if (stroke.size < 2) return

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = pixels.copyOf()

        // Push pixels along the vector of the stroke
        for (i in 0 until stroke.size - 1) {
            val p1 = stroke[i]
            val p2 = stroke[i + 1]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y

            val minX = maxOf(0, (p1.x - radius).toInt())
            val maxX = minOf(width - 1, (p1.x + radius).toInt())
            val minY = maxOf(0, (p1.y - radius).toInt())
            val maxY = minOf(height - 1, (p1.y + radius).toInt())

            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val distSq = (x - p1.x) * (x - p1.x) + (y - p1.y) * (y - p1.y)
                    if (distSq < radius * radius) {
                        val falloff = exp(-distSq / (radius * radius / 2.0))
                        val shiftX = (dx * falloff * intensity).toInt()
                        val shiftY = (dy * falloff * intensity).toInt()

                        val srcX = (x - shiftX).coerceIn(0, width - 1)
                        val srcY = (y - shiftY).coerceIn(0, height - 1)

                        outPixels[y * width + x] = pixels[srcY * width + srcX]
                    }
                }
            }
        }
        bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
    }

    private fun applyHeal(bitmap: Bitmap, stroke: List<Offset>, radius: Float) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = pixels.copyOf()

        // Sample from a slightly offset region to act as a clone stamp/heal
        val cloneOffsetX = (radius * 1.5f).toInt()
        val cloneOffsetY = (radius * 1.5f).toInt()

        for (point in stroke) {
            val minX = maxOf(0, (point.x - radius).toInt())
            val maxX = minOf(width - 1, (point.x + radius).toInt())
            val minY = maxOf(0, (point.y - radius).toInt())
            val maxY = minOf(height - 1, (point.y + radius).toInt())

            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val distSq = (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y)
                    if (distSq < radius * radius) {
                        val srcX = (x + cloneOffsetX).coerceIn(0, width - 1)
                        val srcY = (y + cloneOffsetY).coerceIn(0, height - 1)

                        val srcColor = pixels[srcY * width + srcX]
                        val destColor = outPixels[y * width + x]

                        val falloff = 1.0f - (distSq / (radius * radius)).toFloat()
                        outPixels[y * width + x] = blendColors(srcColor, destColor, falloff)
                    }
                }
            }
        }
        bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
    }

    private fun blendColors(src: Int, dst: Int, ratio: Float): Int {
        val a1 = Color.alpha(src); val r1 = Color.red(src)
        val g1 = Color.green(src); val b1 = Color.blue(src)

        val a2 = Color.alpha(dst); val r2 = Color.red(dst)
        val g2 = Color.green(dst); val b2 = Color.blue(dst)

        val a = (a1 * ratio + a2 * (1 - ratio)).toInt()
        val r = (r1 * ratio + r2 * (1 - ratio)).toInt()
        val g = (g1 * ratio + g2 * (1 - ratio)).toInt()
        val b = (b1 * ratio + b2 * (1 - ratio)).toInt()

        return Color.argb(a, r, g, b)
    }

    private fun applyRenderEffectBlur(bitmap: Bitmap, stroke: List<Offset>, radius: Float) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val outPixels = pixels.copyOf()

        val blurRad = (radius / 4).toInt().coerceAtLeast(1)

        for (point in stroke) {
            val minX = maxOf(0, (point.x - radius).toInt())
            val maxX = minOf(width - 1, (point.x + radius).toInt())
            val minY = maxOf(0, (point.y - radius).toInt())
            val maxY = minOf(height - 1, (point.y + radius).toInt())

            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    val distSq = (x - point.x) * (x - point.x) + (y - point.y) * (y - point.y)
                    if (distSq < radius * radius) {
                        var r = 0; var g = 0; var b = 0; var a = 0
                        var count = 0
                        for (by in maxOf(0, y - blurRad)..minOf(height - 1, y + blurRad)) {
                            for (bx in maxOf(0, x - blurRad)..minOf(width - 1, x + blurRad)) {
                                val c = pixels[by * width + bx]
                                a += Color.alpha(c)
                                r += Color.red(c)
                                g += Color.green(c)
                                b += Color.blue(c)
                                count++
                            }
                        }
                        outPixels[y * width + x] = Color.argb(a / count, r / count, g / count, b / count)
                    }
                }
            }
        }
        bitmap.setPixels(outPixels, 0, width, 0, 0, width, height)
    }
}
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
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * Kotlin UI layer implementation of image manipulation tools.
  * Native Android hardware-accelerated 2D pipeline.
   * JNI boundary calls have been fully eradicated.
    */
object ImageProcessor {

    init {
        NativeLibLoader.loadAll()
    }

        /**
         * The linear scale that [mapScreenToBitmap] applies to a *length* (e.g. a brush diameter):
         * screen_px * scale = bitmap_px. It is the same `fitScaleX / layerScale` used for coordinates
         * (ContentScale.Fit is uniform, so X and Y scales are equal). Drawing a stroke whose width is
         * `brushSize * scale` in bitmap space therefore renders at exactly `brushSize` screen px,
         * matching the size preview shown in the rail item. Returns 1f for a 1:1, unscaled layer.
         */
        fun screenToBitmapScale(
            screenWidth: Int,
            screenHeight: Int,
            bitmapWidth: Int,
            bitmapHeight: Int,
            layerScale: Float,
        ): Float {
            if (screenWidth <= 0 || screenHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return 1f
            val imageAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
            val renderWidth = if (imageAspect > screenAspect) screenWidth.toFloat() else screenHeight * imageAspect
            val fitScaleX = if (renderWidth > 0f) bitmapWidth / renderWidth else 1f
            val safeLayerScale = if (layerScale > 0.0001f) layerScale else 1f
            return fitScaleX / safeLayerScale
        }

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
                                    // Actually blur the pixels under the stroke: build a blurred copy of the layer
                                    // and stamp it back only where the stroke draws (the stroke is used as an alpha
                                    // mask). The old code set no Paint color, so Paint's default (black) was painted
                                    // as a translucent line — smearing black instead of blurring.
                                    val factor = (2 + (intensity.coerceIn(0f, 1f) * 12f)).toInt().coerceIn(2, 16)
                                    val blurred = cheapBlur(resultBitmap, factor)
                                    val maskBmp = Bitmap.createBitmap(resultBitmap.width, resultBitmap.height, Bitmap.Config.ARGB_8888)
                                    val maskCanvas = Canvas(maskBmp)
                                    val maskPaint = Paint().apply {
                                        strokeWidth = brushSize
                                        style = Paint.Style.STROKE
                                        strokeCap = Paint.Cap.ROUND
                                        strokeJoin = Paint.Join.ROUND
                                        isAntiAlias = true
                                        if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                                    }
                                    drawStroke(maskCanvas, stroke, maskPaint)
                                    // Keep the blurred pixels only where the stroke drew, then composite onto the layer.
                                    maskCanvas.drawBitmap(blurred, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) })
                                    canvas.drawBitmap(maskBmp, 0f, 0f, null)
                                    maskBmp.recycle()
                                    blurred.recycle()
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

                /**
                 * A cheap separable-ish blur via downscale→upscale with bilinear filtering, scaled by
                 * [factor] (larger = blurrier). Used by the BLUR tool to soften the region under a
                 * stroke without RenderScript (removed in API 31) or a native dependency.
                 */
                private fun cheapBlur(src: Bitmap, factor: Int): Bitmap {
                    val w = (src.width / factor).coerceAtLeast(1)
                    val h = (src.height / factor).coerceAtLeast(1)
                    val small = Bitmap.createScaledBitmap(src, w, h, true)
                    val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
                    if (small !== up) small.recycle()
                    return up
                }

            /**
             * Applies Canny Edge Detection to the entire bitmap and returns a new transparent bitmap
             * containing the extracted stroke outlines.
             */
            suspend fun applyCannyEdgeDetection(
                originalBitmap: Bitmap,
                threshold1: Double = 100.0,
                threshold2: Double = 200.0,
                apertureSize: Int = 3
            ): Bitmap = withContext(Dispatchers.Default) {
                val mat = Mat()
                Utils.bitmapToMat(originalBitmap, mat)

                val gray = Mat()
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

                val edges = Mat()
                Imgproc.Canny(gray, edges, threshold1, threshold2, apertureSize, false)

                // Convert grayscale edges to an ARGB mask (white edges, transparent background)
                val argbEdges = Mat(edges.rows(), edges.cols(), org.opencv.core.CvType.CV_8UC4)
                
                // Create a completely transparent base
                argbEdges.setTo(org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0))
                
                // Copy the white edges to the transparent base using the edges as a mask
                val whiteColor = org.opencv.core.Scalar(255.0, 255.0, 255.0, 255.0)
                argbEdges.setTo(whiteColor, edges)

                val resultBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(argbEdges, resultBitmap)
                
                mat.release()
                gray.release()
                edges.release()
                argbEdges.release()
                
                resultBitmap
            }
}

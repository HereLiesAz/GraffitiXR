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

        /**
         * Single source of truth for a tool's stroke [Paint]. Used both for the live vector preview
         * and the final rasterization so the in-progress stroke looks identical to the committed
         * result. [brushSize] is in the destination's pixel units (bitmap px when committing, local
         * canvas px when previewing). Liquify isn't paint-based, so it returns a plain brush paint.
         */
        fun buildToolPaint(
            tool: Tool,
            brushColor: Int,
            brushSize: Float,
            intensity: Float = 0.5f,
            feathering: Float = 0f,
        ): Paint = Paint().apply {
            strokeWidth = brushSize
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            when (tool) {
                Tool.BRUSH -> {
                    color = brushColor
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.ERASER -> {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    if (feathering > 0f) maskFilter = BlurMaskFilter(brushSize * feathering * 0.5f, BlurMaskFilter.Blur.NORMAL)
                }
                Tool.BLUR -> {
                    maskFilter = BlurMaskFilter(brushSize * intensity.coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
                    alpha = 150
                }
                Tool.HEAL -> {
                    color = brushColor
                    alpha = 128
                }
                Tool.BURN -> {
                    color = Color.BLACK
                    alpha = (255 * intensity * 0.3f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.DARKEN)
                }
                Tool.DODGE -> {
                    color = Color.WHITE
                    alpha = (255 * intensity * 0.3f).toInt().coerceIn(0, 255)
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
                }
                else -> { color = brushColor }
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
                                            Tool.LIQUIFY -> applyLiquifyNative(resultBitmap, stroke, brushSize, intensity)
                                            Tool.NONE, Tool.COLOR -> Unit // not paint tools — nothing to rasterize
                                            else -> drawStroke(
                                                canvas, stroke,
                                                buildToolPaint(tool, brushColor, brushSize, intensity, feathering)
                                            )
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
                        // This is now handled by SlamManager's native ImageWarper
                        // This method is called at the end of the stroke to bake the results.
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

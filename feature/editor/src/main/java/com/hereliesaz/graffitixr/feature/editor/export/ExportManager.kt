// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/export/ExportManager.kt
package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.BlendMode as NativeBlendMode
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.feature.editor.createColorMatrix
import javax.inject.Inject

/**
 * Handles compositing and exporting of project layers.
 */
class ExportManager @Inject constructor() {

    fun compositeLayers(
        layers: List<Layer>,
        screenWidth: Int,
        screenHeight: Int,
        backgroundBitmap: Bitmap? = null,
        backgroundColor: Int = android.graphics.Color.TRANSPARENT
    ): Bitmap {
        val result = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        if (backgroundColor != android.graphics.Color.TRANSPARENT) {
            canvas.drawColor(backgroundColor)
        }

        backgroundBitmap?.let { bg ->
            val bgAspect = bg.width.toFloat() / bg.height.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

            var renderWidth = screenWidth.toFloat()
            var renderHeight = screenHeight.toFloat()

            if (bgAspect > screenAspect) {
                renderWidth = renderHeight * bgAspect
            } else {
                renderHeight = renderWidth / bgAspect
            }

            val matrix = Matrix()
            matrix.postScale(renderWidth / bg.width, renderHeight / bg.height)
            matrix.postTranslate((screenWidth - renderWidth) / 2f, (screenHeight - renderHeight) / 2f)

            canvas.drawBitmap(bg, matrix, null)
        }

        layers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { b ->
                val cm = createColorMatrix(
                    saturation = layer.saturation,
                    contrast = layer.contrast,
                    brightness = layer.brightness,
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB,
                    isInverted = layer.isInverted
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                    blendMode = layer.blendMode.toNativeBlendMode()
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix(cm.values)
                    )
                }

                val matrix = getLayerScreenMatrix(layer, screenWidth, screenHeight)
                canvas.drawBitmap(b, matrix, paint)
            }
        }
        return result
    }

    /**
     * Composites [linkedLayers] into the local coordinate space of the [anchor] layer.
     * The resulting bitmap is capped to a maximum dimension of 2048px to prevent OOM.
     */
    fun compositeToLayerSpace(anchor: Layer, linkedLayers: List<Layer>, screenWidth: Int, screenHeight: Int): Bitmap {
        val anchorBitmap = anchor.bitmap ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        // Cap target dimensions to 2048px to avoid OOM
        val maxDim = 2048
        var targetWidth = anchorBitmap.width
        var targetHeight = anchorBitmap.height
        val aspect = targetWidth.toFloat() / targetHeight.toFloat()
        
        if (targetWidth > maxDim || targetHeight > maxDim) {
            if (aspect > 1f) {
                targetWidth = maxDim
                targetHeight = (maxDim / aspect).toInt()
            } else {
                targetHeight = maxDim
                targetWidth = (maxDim * aspect).toInt()
            }
        }

        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val anchorMatrix = getLayerScreenMatrix(anchor, screenWidth, screenHeight)
        val anchorMatrixInv = Matrix()
        if (!anchorMatrix.invert(anchorMatrixInv)) {
            return result
        }

        // Scale factor from original anchor pixels to capped target pixels
        val canvasScale = targetWidth.toFloat() / anchorBitmap.width.toFloat()

        linkedLayers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { b ->
                val cm = createColorMatrix(
                    saturation = layer.saturation,
                    contrast = layer.contrast,
                    brightness = layer.brightness,
                    colorBalanceR = layer.colorBalanceR,
                    colorBalanceG = layer.colorBalanceG,
                    colorBalanceB = layer.colorBalanceB,
                    isInverted = layer.isInverted
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
                    blendMode = layer.blendMode.toNativeBlendMode()
                    colorFilter = android.graphics.ColorMatrixColorFilter(
                        android.graphics.ColorMatrix(cm.values)
                    )
                }

                val layerMatrix = getLayerScreenMatrix(layer, screenWidth, screenHeight)
                val relativeMatrix = Matrix(anchorMatrixInv)
                relativeMatrix.postConcat(layerMatrix)
                
                // Adjust for capped canvas size
                relativeMatrix.postScale(canvasScale, canvasScale)

                canvas.drawBitmap(b, relativeMatrix, paint)
            }
        }
        return result
    }

    private fun getLayerScreenMatrix(layer: Layer, screenWidth: Int, screenHeight: Int): Matrix {
        val b = layer.bitmap ?: return Matrix()
        val matrix = Matrix()

        // Calculate ContentScale.Fit logic so the exported image matches the UI layout bounds
        val imageAspect = b.width.toFloat() / b.height.toFloat()
        val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()

        var renderWidth = screenWidth.toFloat()
        var renderHeight = screenHeight.toFloat()

        if (imageAspect > screenAspect) {
            renderHeight = renderWidth / imageAspect
        } else {
            renderWidth = renderHeight * imageAspect
        }

        // 1. Initial Scale to screen constraints
        matrix.postScale(renderWidth / b.width, renderHeight / b.height)

        // 2. Center Pivot
        matrix.postTranslate(-renderWidth / 2f, -renderHeight / 2f)

        // 3. User Transforms (Scale, Rotate, Offset)
        matrix.postScale(layer.scale, layer.scale)
        matrix.postRotate(layer.rotationZ) // Standard 2D export only respects Z

        // 4. Move to center of screen + apply pan
        matrix.postTranslate(screenWidth / 2f + layer.offset.x, screenHeight / 2f + layer.offset.y)

        return matrix
    }

    private fun BlendMode.toNativeBlendMode(): NativeBlendMode {
        return when (this) {
            BlendMode.Clear -> NativeBlendMode.CLEAR
            BlendMode.Src -> NativeBlendMode.SRC
            BlendMode.Dst -> NativeBlendMode.DST
            BlendMode.SrcOver -> NativeBlendMode.SRC_OVER
            BlendMode.DstOver -> NativeBlendMode.DST_OVER
            BlendMode.SrcIn -> NativeBlendMode.SRC_IN
            BlendMode.DstIn -> NativeBlendMode.DST_IN
            BlendMode.SrcOut -> NativeBlendMode.SRC_OUT
            BlendMode.DstOut -> NativeBlendMode.DST_OUT
            BlendMode.SrcAtop -> NativeBlendMode.SRC_ATOP
            BlendMode.DstAtop -> NativeBlendMode.DST_ATOP
            BlendMode.Xor -> NativeBlendMode.XOR
            BlendMode.Plus -> NativeBlendMode.PLUS
            BlendMode.Modulate -> NativeBlendMode.MODULATE
            BlendMode.Screen -> NativeBlendMode.SCREEN
            BlendMode.Overlay -> NativeBlendMode.OVERLAY
            BlendMode.Darken -> NativeBlendMode.DARKEN
            BlendMode.Lighten -> NativeBlendMode.LIGHTEN
            BlendMode.ColorDodge -> NativeBlendMode.COLOR_DODGE
            BlendMode.ColorBurn -> NativeBlendMode.COLOR_BURN
            BlendMode.Hardlight -> NativeBlendMode.HARD_LIGHT
            BlendMode.Softlight -> NativeBlendMode.SOFT_LIGHT
            BlendMode.Difference -> NativeBlendMode.DIFFERENCE
            BlendMode.Exclusion -> NativeBlendMode.EXCLUSION
            BlendMode.Multiply -> NativeBlendMode.MULTIPLY
            BlendMode.Hue -> NativeBlendMode.HUE
            BlendMode.Saturation -> NativeBlendMode.SATURATION
            BlendMode.Color -> NativeBlendMode.COLOR
            BlendMode.Luminosity -> NativeBlendMode.LUMINOSITY
            else -> NativeBlendMode.SRC_OVER
        }
    }
}
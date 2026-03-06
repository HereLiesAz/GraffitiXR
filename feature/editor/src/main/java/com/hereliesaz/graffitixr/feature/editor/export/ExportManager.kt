package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.hereliesaz.graffitixr.common.model.Layer
import javax.inject.Inject

class ExportManager @Inject constructor() {

    fun compositeLayers(layers: List<Layer>, screenWidth: Int, screenHeight: Int): Bitmap {
        val result = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        layers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { b ->
                val paint = Paint().apply {
                    alpha = (layer.opacity * 255).toInt()
                }

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
                matrix.postRotate((layer.rotationZ * 180f / Math.PI).toFloat()) // Standard 2D export only respects Z

                // 4. Move to center of screen + apply pan
                matrix.postTranslate(screenWidth / 2f + layer.offset.x, screenHeight / 2f + layer.offset.y)

                canvas.drawBitmap(b, matrix, paint)
            }
        }
        return result
    }
}
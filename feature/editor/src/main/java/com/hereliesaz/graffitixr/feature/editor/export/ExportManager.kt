package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.hereliesaz.graffitixr.common.model.Layer
import javax.inject.Inject

class ExportManager @Inject constructor() {

    /**
     * Composites all visible layers into a single output bitmap.
     */
    fun compositeLayers(layers: List<Layer>): Bitmap {
        val sourceLayer = layers.firstOrNull { it.bitmap != null }
        val sourceBitmap = sourceLayer?.bitmap ?: throw IllegalStateException("No bitmaps to export")

        // Ensure Config is non-null by defaulting to ARGB_8888
        val config = sourceBitmap.config ?: Bitmap.Config.ARGB_8888

        val result = Bitmap.createBitmap(
            sourceBitmap.width,
            sourceBitmap.height,
            config
        )

        val canvas = Canvas(result)
        val paint = Paint()

        layers.filter { it.isVisible }.forEach { layer ->
            layer.bitmap?.let { b ->
                paint.alpha = (layer.opacity * 255).toInt()
                canvas.drawBitmap(b, 0f, 0f, paint)
            }
        }
        return result
    }
}
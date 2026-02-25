package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Matrix
import android.graphics.BlendMode as AndroidBlendMode
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.common.model.BlendMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.sqrt

@Singleton
class ExportManager @Inject constructor() {

    companion object {
        private const val TAG = "ExportManager"
    }

    /**
     * Exports the given layers to a single Bitmap.
     * Respects layer transformations (Scale, Rotate, Translate), opacity, and blend modes.
     * Also applies Mesh Warp if present.
     *
     * @param layers The list of layers to export.
     * @param width The width of the target canvas (e.g., screen width).
     * @param height The height of the target canvas (e.g., screen height).
     */
    fun exportSingleImage(layers: List<Layer>, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Reuse Paint and Matrix to reduce GC pressure
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        val matrix = Matrix()

        layers.forEach { layer ->
            if (layer.isVisible) {
                drawLayer(canvas, layer, width, height, paint, matrix)
            }
        }
        return bitmap
    }

    private fun drawLayer(
        canvas: Canvas,
        layer: Layer,
        canvasWidth: Int,
        canvasHeight: Int,
        paint: Paint,
        matrix: Matrix
    ) {
        paint.alpha = (layer.opacity * 255).toInt()
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            paint.blendMode = mapBlendMode(layer.blendMode)
        }

        // Prepare matrix for transformation
        matrix.reset()

        // 1. ContentScale.Fit logic (Center and Scale to fit canvas)
        val srcW = layer.bitmap.width.toFloat()
        val srcH = layer.bitmap.height.toFloat()
        val dstW = canvasWidth.toFloat()
        val dstH = canvasHeight.toFloat()

        val scaleFit = min(dstW / srcW, dstH / srcH)
        val dx = (dstW - srcW * scaleFit) / 2f
        val dy = (dstH - srcH * scaleFit) / 2f

        matrix.setScale(scaleFit, scaleFit)
        matrix.postTranslate(dx, dy)

        // 2. User Transformations (Scale, Rotate, Translate around center)
        val px = dstW / 2f
        val py = dstH / 2f

        matrix.postScale(layer.scale, layer.scale, px, py)
        matrix.postRotate(layer.rotationZ, px, py)
        matrix.postTranslate(layer.offset.x, layer.offset.y)

        val mesh = layer.warpMesh
        if (!mesh.isNullOrEmpty()) {
            canvas.save()
            canvas.concat(matrix)

            // FIX: Dynamically calculate grid dimension.
            // Vertices array size formula: (rows + 1) * (cols + 1) * 2 = size
            // Assuming square grids (rows == cols), we reverse engineer cols:
            val meshSize = mesh.size
            val gridCols = (sqrt(meshSize / 2.0)).toInt() - 1
            val expectedSize = (gridCols + 1) * (gridCols + 1) * 2

            if (meshSize == expectedSize && gridCols > 0) {
                canvas.drawBitmapMesh(layer.bitmap, gridCols, gridCols, mesh.toFloatArray(), 0, null, 0, paint)
            } else {
                Log.w(TAG, "Mesh size mismatch or invalid grid. Drawing flat image fallback.")
                canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
            }
            canvas.restore()
        } else {
            // Standard draw
            canvas.drawBitmap(layer.bitmap, matrix, paint)
        }
    }

    private fun mapBlendMode(mode: BlendMode): AndroidBlendMode? {
        if (android.os.Build.VERSION.SDK_INT < 29) return null

        return when(mode) {
            BlendMode.SrcOver -> AndroidBlendMode.SRC_OVER
            BlendMode.Multiply -> AndroidBlendMode.MULTIPLY
            BlendMode.Screen -> AndroidBlendMode.SCREEN
            BlendMode.Overlay -> AndroidBlendMode.OVERLAY
            BlendMode.Darken -> AndroidBlendMode.DARKEN
            BlendMode.Lighten -> AndroidBlendMode.LIGHTEN
            BlendMode.ColorDodge -> AndroidBlendMode.COLOR_DODGE
            BlendMode.ColorBurn -> AndroidBlendMode.COLOR_BURN
            BlendMode.HardLight -> AndroidBlendMode.HARD_LIGHT
            BlendMode.SoftLight -> AndroidBlendMode.SOFT_LIGHT
            BlendMode.Difference -> AndroidBlendMode.DIFFERENCE
            BlendMode.Exclusion -> AndroidBlendMode.EXCLUSION
            BlendMode.Hue -> AndroidBlendMode.HUE
            BlendMode.Saturation -> AndroidBlendMode.SATURATION
            BlendMode.Color -> AndroidBlendMode.COLOR
            BlendMode.Luminosity -> AndroidBlendMode.LUMINOSITY
            BlendMode.Clear -> AndroidBlendMode.CLEAR
            BlendMode.Src -> AndroidBlendMode.SRC
            BlendMode.Dst -> AndroidBlendMode.DST
            BlendMode.DstOver -> AndroidBlendMode.DST_OVER
            BlendMode.SrcIn -> AndroidBlendMode.SRC_IN
            BlendMode.DstIn -> AndroidBlendMode.DST_IN
            BlendMode.SrcOut -> AndroidBlendMode.SRC_OUT
            BlendMode.DstOut -> AndroidBlendMode.DST_OUT
            BlendMode.SrcAtop -> AndroidBlendMode.SRC_ATOP
            BlendMode.DstAtop -> AndroidBlendMode.DST_ATOP
            BlendMode.Xor -> AndroidBlendMode.XOR
            BlendMode.Plus -> AndroidBlendMode.PLUS
            BlendMode.Modulate -> AndroidBlendMode.MODULATE
        }
    }

    fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save bitmap to file", e)
            throw e
        }
    }
}
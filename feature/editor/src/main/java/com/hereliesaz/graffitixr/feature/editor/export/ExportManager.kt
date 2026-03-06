package com.hereliesaz.graffitixr.feature.editor.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.BlendMode as NativeBlendMode
import androidx.compose.ui.graphics.BlendMode
import com.hereliesaz.graffitixr.common.model.Layer
import javax.inject.Inject

class ExportManager @Inject constructor() {
    fun compositeLayers(layers: List<Layer>, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        for (layer in layers) {
            val bitmap = layer.bitmap ?: continue
            paint.alpha = (layer.opacity * 255).toInt().coerceIn(0, 255)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                paint.blendMode = layer.blendMode.toNativeBlendMode()
            }
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
        }

        return result
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

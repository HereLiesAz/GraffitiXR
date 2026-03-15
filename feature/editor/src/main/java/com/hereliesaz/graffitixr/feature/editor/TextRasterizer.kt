package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import com.hereliesaz.graffitixr.common.model.TextLayerParams

object TextRasterizer {

    fun rasterize(
        params: TextLayerParams,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        typeface: Typeface? = null
    ): Bitmap {
        val bitmap = createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val textSizePx = params.fontSizeDp * density

        fun buildPaint(style: Paint.Style, color: Int): TextPaint {
            return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSizePx
                this.color = color
                letterSpacing = params.letterSpacingEm
                this.typeface = when {
                    typeface != null -> typeface
                    params.isBold && params.isItalic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
                    params.isBold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    params.isItalic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
                    else -> Typeface.DEFAULT
                }
                this.style = style
            }
        }

        val displayText = params.text.ifEmpty { "Text" }

        fun makeLayout(paint: TextPaint): StaticLayout {
            return StaticLayout.Builder
                .obtain(displayText, 0, displayText.length, paint, widthPx)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
        }

        // Outline pass (drawn before fill so fill sits on top)
        if (params.hasOutline) {
            val outlinePaint = buildPaint(Paint.Style.STROKE, params.outlineColorArgb).apply {
                strokeWidth = params.outlineWidthDp * density
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            val layout = makeLayout(outlinePaint)
            val textHeight = layout.height.toFloat()
            val top = (heightPx - textHeight) / 2f
            canvas.save()
            canvas.translate(0f, top)
            layout.draw(canvas)
            canvas.restore()
        }

        // Fill pass (with optional drop shadow)
        val fillPaint = buildPaint(Paint.Style.FILL, params.colorArgb)
        if (params.hasDropShadow) {
            fillPaint.setShadowLayer(
                params.shadowRadiusDp * density,
                params.shadowDxDp * density,
                params.shadowDyDp * density,
                params.shadowColorArgb
            )
        }
        val fillLayout = makeLayout(fillPaint)
        val textHeight = fillLayout.height.toFloat()
        val top = (heightPx - textHeight) / 2f
        canvas.save()
        canvas.translate(0f, top)
        fillLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}

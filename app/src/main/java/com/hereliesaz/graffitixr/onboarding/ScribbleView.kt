package com.hereliesaz.graffitixr.onboarding

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.min

/**
 * Renders a [Scribble] — the glyphs are laid out in a normalized [0,1] square, so this scales the
 * generator's output to whatever box the caller gives it. Each glyph is drawn centred on its
 * ([ScribbleGlyph.cx], [ScribbleGlyph.cy]) and rotated by [ScribbleGlyph.rotationDeg]. A soft shadow
 * keeps it legible over the live camera feed behind the overlay.
 */
@Composable
fun ScribbleView(
    scribble: Scribble,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    val paint = remember(color) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgb()
            textAlign = Paint.Align.CENTER
            style = Paint.Style.FILL
            isFakeBoldText = true
            setShadowLayer(12f, 0f, 0f, android.graphics.Color.argb(200, 0, 0, 0))
        }
    }
    // Reused across glyphs and frames — the `paint.fontMetrics` getter allocates a fresh object each
    // call, which we don't want in a continuously-rendered overlay's draw loop.
    val fontMetrics = remember { Paint.FontMetrics() }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val edge = min(w, h)
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            scribble.glyphs.forEach { g ->
                val px = g.cx * w
                val py = g.cy * h
                paint.textSize = g.sizeFrac * edge
                paint.getFontMetrics(fontMetrics)
                // Paint draws text on the baseline; offset so the glyph's visual middle sits at py.
                val baselineY = py - (fontMetrics.ascent + fontMetrics.descent) / 2f
                nc.save()
                nc.rotate(g.rotationDeg, px, py)
                nc.drawText(g.char.toString(), px, baselineY, paint)
                nc.restore()
            }
        }
    }
}

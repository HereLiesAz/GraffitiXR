package com.hereliesaz.graffitixr.common.azphalt

import android.graphics.Bitmap

/**
 * Android bridge: grade a bitmap through a [CubeLut], returning a new ARGB_8888 bitmap. Processes a
 * row at a time so the working buffer is `width` ints, not `width * height` — a 4K image needs ~16KB
 * here instead of ~34MB, which matters when the editor is already holding several layer bitmaps.
 */
fun Bitmap.applyCubeLut(lut: CubeLut): Bitmap {
    val out = copy(Bitmap.Config.ARGB_8888, true)
    val width = out.width
    val height = out.height
    val row = IntArray(width)
    for (y in 0 until height) {
        out.getPixels(row, 0, width, 0, y, width, 1)
        lut.applyPixels(row)
        out.setPixels(row, 0, width, 0, y, width, 1)
    }
    return out
}

package com.hereliesaz.graffitixr.common.azphalt

import android.graphics.Bitmap

/** Android bridge: grade a bitmap through a [CubeLut], returning a new ARGB_8888 bitmap. */
fun Bitmap.applyCubeLut(lut: CubeLut): Bitmap {
    val out = copy(Bitmap.Config.ARGB_8888, true)
    val px = IntArray(out.width * out.height)
    out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
    lut.applyPixels(px)
    out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
    return out
}

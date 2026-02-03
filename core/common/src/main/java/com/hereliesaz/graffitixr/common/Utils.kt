package com.hereliesaz.graffitixr.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap

fun resizeBitmapForArCore(bitmap: Bitmap): Bitmap {
    val MAX_DIMENSION = 1024
    if (bitmap.width <= MAX_DIMENSION && bitmap.height <= MAX_DIMENSION) return bitmap

    val ratio = Math.min(
        MAX_DIMENSION.toFloat() / bitmap.width,
        MAX_DIMENSION.toFloat() / bitmap.height
    )
    val width = (bitmap.width * ratio).toInt()
    val height = (bitmap.height * ratio).toInt()

    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

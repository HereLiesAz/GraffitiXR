package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await

suspend fun Bitmap.removeBackground(): Bitmap {
    val segmenterOptions = SelfieSegmenterOptions.Builder()
        .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
        .build()
    val segmenter = Segmentation.getClient(segmenterOptions)
    val image = InputImage.fromBitmap(this, 0)
    val mask = segmenter.process(image).await()

    val maskBitmap = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
    for (y in 0 until mask.height) {
        for (x in 0 until mask.width) {
            val alpha = (mask.buffer.float * 255).toInt()
            maskBitmap.setPixel(x, y, android.graphics.Color.argb(alpha, 255, 255, 255))
        }
    }

    val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(resultBitmap)
    val paint = android.graphics.Paint()
    paint.isAntiAlias = true
    canvas.drawBitmap(maskBitmap, 0f, 0f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, 0f, 0f, paint)

    return resultBitmap
}
package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

suspend fun Bitmap.removeBackground(): Bitmap {
    val segmenterOptions = SubjectSegmenterOptions.Builder()
        .enableForegroundConfidenceMask()
        .build()

    val segmenter = SubjectSegmentation.getClient(segmenterOptions)

    val image = InputImage.fromBitmap(this, 0)

    val result = segmenter.process(image).await()

    if (result.subjects.isEmpty()) {
        return this
    }

    // For now, we'll just use the first subject found.
    val subject = result.subjects[0]

    val maskBuffer = subject.confidenceMask ?: return this
    val maskWidth = subject.width
    val maskHeight = subject.height

    val maskBitmap = Bitmap.createBitmap(maskWidth, maskHeight, Bitmap.Config.ARGB_8888)
    maskBuffer.rewind()
    for (y in 0 until maskHeight) {
        for (x in 0 until maskWidth) {
            val alpha = (maskBuffer.get() * 255).toInt()
            maskBitmap.setPixel(x, y, android.graphics.Color.argb(alpha, 255, 255, 255))
        }
    }

    // The mask needs to be scaled to the original image size to be applied correctly.
    val scaledMaskBitmap = Bitmap.createScaledBitmap(maskBitmap, width, height, true)

    val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(resultBitmap)
    val paint = android.graphics.Paint()
    paint.isAntiAlias = true
    canvas.drawBitmap(scaledMaskBitmap, 0f, 0f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(this, 0f, 0f, paint)

    return resultBitmap
}
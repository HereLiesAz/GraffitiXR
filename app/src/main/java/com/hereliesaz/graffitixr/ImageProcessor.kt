package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmentation
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

suspend fun removeBackground(context: Context, imageUri: Uri): Result<Uri> {
    return try {
        val segmenter = SelfieSegmentation.getClient()
        val originalBitmap = MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
        val image = InputImage.fromBitmap(originalBitmap, 0)

        val segmentationMask = segmenter.process(image).await()

        val mask: ByteBuffer = segmentationMask.mask
        val maskWidth: Int = segmentationMask.width
        val maskHeight: Int = segmentationMask.height

        val resultBitmap = Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

        // Reset the buffer's position to the beginning
        mask.rewind()

        for (y in 0 until maskHeight) {
            for (x in 0 until maskWidth) {
                // The mask contains a float confidence value for each pixel
                val confidence = mask.float
                // If confidence is high, copy the original pixel. Otherwise, it will be transparent (default ARGB_8888).
                if (confidence > 0.8f) { // Adjusted threshold for better quality
                    resultBitmap.setPixel(x, y, originalBitmap.getPixel(x, y))
                }
            }
        }

        val file = File(context.cacheDir, "nobg.png")
        FileOutputStream(file).use {
            resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        Result.success(file.toUri())
    } catch (e: Exception) {
        Result.failure(e)
    }
}

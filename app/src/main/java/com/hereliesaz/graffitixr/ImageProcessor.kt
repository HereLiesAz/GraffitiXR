package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

object ImageProcessor {
    /**
     * Removes the background from an image using ML Kit's Selfie Segmentation.
     * The resulting image has a transparent background.
     *
     * @param context The application context.
     * @param imageUri The URI of the image to process.
     * @return A [Result] containing the URI of the processed image, or an exception on failure.
     */
    suspend fun removeBackground(context: Context, imageUri: Uri): Result<Uri> {
        return try {
            val options =
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()
                    .build()
            val segmenter = Segmentation.getClient(options)

            val originalBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, imageUri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
            }

            val image = InputImage.fromBitmap(originalBitmap, 0)

            val segmentationMask = segmenter.process(image).await()

            val mask: ByteBuffer = segmentationMask.buffer
            val maskWidth: Int = segmentationMask.width
            val maskHeight: Int = segmentationMask.height

            val resultBitmap =
                Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

            mask.rewind()

            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    val confidence = mask.float
                    if (confidence > 0.8f) {
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
}
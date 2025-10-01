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

/**
 * A singleton object responsible for handling intensive, off-thread image processing tasks.
 *
 * This object centralizes operations like background removal to ensure they are handled
 * consistently and can be easily invoked from a background coroutine context.
 */
object ImageProcessor {
    /**
     * Removes the background from a given image using Google's ML Kit Selfie Segmentation API.
     *
     * This function takes a source image URI, decodes it into a bitmap, and then processes it
     * with the ML Kit segmenter. It creates a new bitmap where pixels belonging to the foreground
     * (the "selfie") are preserved, and all other pixels are made transparent.
     *
     * The resulting bitmap is saved as a PNG file to the application's cache directory to preserve
     * transparency and a URI to this new file is returned.
     *
     * This is a resource-intensive operation and should always be called from a background
     * thread (e.g., using `withContext(Dispatchers.IO)`).
     *
     * @param context The application [Context], required for accessing the `ContentResolver` to
     *   decode the image URI and for accessing the cache directory.
     * @param imageUri The [Uri] of the source image to process. This can be a `content://` URI
     *   from the media store or a `file://` URI.
     * @return A [Result] wrapper which, on success, contains the [Uri] of the newly created
     *   PNG file with the transparent background. On failure, it contains the [Exception]
     *   that occurred during processing, which could be due to file I/O errors, decoding issues,
     *   or ML Kit processing failures.
     */
    suspend fun removeBackground(context: Context, imageUri: Uri): Result<Uri> {
        return try {
            val options =
                SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                    .enableRawSizeMask()
                    .build()
            val segmenter = Segmentation.getClient(options)

            // Decode the image URI into a mutable bitmap.
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

            // Process the image with ML Kit.
            val segmentationMask = segmenter.process(image).await()

            val mask: ByteBuffer = segmentationMask.buffer
            val maskWidth: Int = segmentationMask.width
            val maskHeight: Int = segmentationMask.height

            val resultBitmap =
                Bitmap.createBitmap(originalBitmap.width, originalBitmap.height, Bitmap.Config.ARGB_8888)

            // Iterate over each pixel and apply the mask.
            mask.rewind()
            for (y in 0 until maskHeight) {
                for (x in 0 until maskWidth) {
                    // The mask confidence is a float from 0.0 to 1.0.
                    val confidence = mask.float
                    // Copy the pixel from the original bitmap if it's part of the foreground.
                    if (confidence > 0.8f) {
                        resultBitmap.setPixel(x, y, originalBitmap.getPixel(x, y))
                    }
                }
            }

            // Save the resulting bitmap to a file in the cache directory.
            val file = File(context.cacheDir, "nobg.png")
            FileOutputStream(file).use {
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            Result.success(file.toUri())
        } catch (e: Exception) {
            // If any step fails, wrap the exception in a Result.failure.
            Result.failure(e)
        }
    }
}
package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

object BackgroundRemover {

    private const val TAG = "BackgroundRemover"

    /**
     * Removes the background from the provided bitmap using MLKit Subject Segmentation.
     * Returns the foreground bitmap on success, or null on failure.
     */
    suspend fun removeBackground(bitmap: Bitmap): Bitmap? {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        return try {
            // Ensure bitmap is in a compatible format (ARGB_8888) and mutable/readable
            val safeBitmap = if (bitmap.config != Bitmap.Config.ARGB_8888) {
                bitmap.copy(Bitmap.Config.ARGB_8888, true)
            } else {
                bitmap
            }

            val inputImage = InputImage.fromBitmap(safeBitmap, 0)
            val result = segmenter.process(inputImage).await()
            result.foregroundBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Background removal failed", e)
            e.printStackTrace()
            null
        } finally {
            segmenter.close()
        }
    }
}

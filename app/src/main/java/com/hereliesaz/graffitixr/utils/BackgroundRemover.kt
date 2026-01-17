package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

object BackgroundRemover {

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
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(inputImage).await()
            result.foregroundBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            segmenter.close()
        }
    }
}

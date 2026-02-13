package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * Uses Google ML Kit to remove the background from an image.
 * * Why ML Kit? because rewriting a semantic segmentation network in C++
 * for a phone on a Friday night is not my idea of a good time.
 */
class BackgroundRemover(private val context: Context) {

    private val options = SubjectSegmenterOptions.Builder()
        .enableForegroundBitmap()
        .build()

    private val segmenter = SubjectSegmentation.getClient(options)

    suspend fun removeBackground(bitmap: Bitmap): Result<Bitmap> {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            // This is a suspend function that waits for the Task to complete
            val result = segmenter.process(inputImage).await()

            // ML Kit gives us the foreground bitmap directly if enabled
            val foreground = result.foregroundBitmap

            if (foreground != null) {
                Result.success(foreground)
            } else {
                Result.failure(Exception("Segmentation failed: No foreground detected"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
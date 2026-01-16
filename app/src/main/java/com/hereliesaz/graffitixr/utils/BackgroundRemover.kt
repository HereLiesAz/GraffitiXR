package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

/**
 * A utility class that performs the existential surgery of removing a background.
 * It uses Google's ML Kit because we trust the machine to know what matters.
 */
object BackgroundRemover {

    /**
     * Isolates the subject of the image, discarding the rest into the digital abyss.
     * * @param bitmap The source image, full of unnecessary context.
     * @return A Result containing the isolated subject as a Bitmap, or an error if the machine blinked.
     */
    suspend fun removeBackground(bitmap: Bitmap): Result<Bitmap> {
        return try {
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()
            
            val segmenter = SubjectSegmentation.getClient(options)
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            val result = segmenter.process(inputImage).await()
            
            val foreground = result.foregroundBitmap
            if (foreground != null) {
                Result.success(foreground)
            } else {
                Result.failure(Exception("The machine saw nothing worth saving."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

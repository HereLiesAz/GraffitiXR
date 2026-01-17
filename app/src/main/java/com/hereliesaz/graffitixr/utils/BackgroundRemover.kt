package com.hereliesaz.graffitixr.utils

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

object BackgroundRemover {

    suspend fun removeBackground(bitmap: Bitmap): Bitmap? {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

<<<<<<< HEAD
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = segmenter.process(image).await()
            result.foregroundBitmap
=======
            val result = segmenter.process(inputImage).await()
            
            val foreground: Bitmap? = result.foregroundBitmap
            if (foreground != null) {
                Result.success(foreground)
            } else {
                Result.failure(Exception("The machine saw nothing worth saving."))
            }
>>>>>>> origin/feature/ar-editor-enhancements-4573859779138866612
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            segmenter.close()
        }
    }
}

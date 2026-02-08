package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object BackgroundRemover {

    suspend fun removeBackground(context: Context, inputBitmap: Bitmap): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            val options = SubjectSegmenterOptions.Builder()
                .enableForegroundBitmap()
                .build()

            val segmenter = SubjectSegmentation.getClient(options)
            val image = InputImage.fromBitmap(inputBitmap, 0)

            segmenter.process(image)
                .addOnSuccessListener { result ->
                    // The foreground bitmap is the image with the background removed (transparent)
                    val foreground = result.foregroundBitmap
                    if (foreground != null) {
                        continuation.resume(foreground)
                    } else {
                        // Fallback: If no foreground bitmap, try to use the mask (simplified)
                        // For this implementation, we strictly return null if ML Kit fails to generate the bitmap
                        continuation.resume(null)
                    }
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                    continuation.resume(null)
                }
        }
    }
}
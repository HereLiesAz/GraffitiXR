// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/BackgroundRemover.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Uses OpenCV GrabCut to remove the background from an image natively.
 * Excising Google ML Kit to return to the offline, strictly decoupled local C++ roots.
 */
class BackgroundRemover @Inject constructor() {

    suspend fun removeBackground(bitmap: Bitmap): Result<Bitmap> = withContext(Dispatchers.Default) {
        try {
            val result = ImageProcessor.removeBackground(bitmap)
            if (result != null) {
                Result.success(result)
            } else {
                Result.failure(Exception("Failed to remove background via OpenCV GrabCut"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
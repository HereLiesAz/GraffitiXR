// FILE: feature/editor/src/main/java/com/hereliesaz/graffitixr/feature/editor/SubjectIsolator.kt
package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for GrabCut-based subject isolation. Both the "Isolate" button in the
 * editor and the stencil wizard use this class so segmentation behaviour can never silently diverge.
 */
@Singleton
class SubjectIsolator @Inject constructor() {

    suspend fun isolate(bitmap: Bitmap): Result<Bitmap> =
        withContext(Dispatchers.Default) {
            try {
                val downsampled = downsample(bitmap)
                val result = ImageProcessor.removeBackground(downsampled)
                if (result != null) Result.success(result)
                else Result.failure(Exception("Segmentation failed — GrabCut returned null"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun downsample(src: Bitmap): Bitmap {
        val maxDim = maxOf(src.width, src.height)
        if (maxDim <= MAX_INPUT_PX) return src.copy(Bitmap.Config.ARGB_8888, false)
        val scale = MAX_INPUT_PX.toFloat() / maxDim
        val w = (src.width * scale).toInt()
        val h = (src.height * scale).toInt()
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    companion object {
        private const val MAX_INPUT_PX = 2048
    }
}

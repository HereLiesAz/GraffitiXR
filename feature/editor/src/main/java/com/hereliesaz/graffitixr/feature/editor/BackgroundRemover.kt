package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
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
    suspend fun removeBackground(context: Context, bitmap: Bitmap): Bitmap? {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)

        // Check for module availability to avoid native crashes
        try {
            val moduleInstallClient = ModuleInstall.getClient(context)
            val areModulesAvailable = moduleInstallClient.areModulesAvailable(segmenter).await()
            if (!areModulesAvailable.areModulesAvailable()) {
                Log.w(TAG, "Subject Segmentation module not installed. Requesting download.")
                val request = ModuleInstallRequest.newBuilder()
                    .addApi(segmenter)
                    .build()
                moduleInstallClient.installModules(request).await()
                // Return null for now, user can retry once installed
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/install modules", e)
            // Continue and try to process, or fail safely
        }

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

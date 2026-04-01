package com.hereliesaz.graffitixr.feature.editor

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubjectIsolator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableMultipleSubjects(
                    SubjectSegmenterOptions.SubjectResultOptions.Builder()
                        .enableConfidenceMask()
                        .build()
                )
                .build()
        )
    }

    suspend fun isolate(bitmap: Bitmap): Result<IsolationResult> = withContext(Dispatchers.Default) {
        runCatching {
            val scaled = downsample(bitmap, 2048)
            val image = InputImage.fromBitmap(scaled, 0)
            val segResult = Tasks.await(segmenter.process(image))
            val subjects = segResult.subjects
            val w = scaled.width; val h = scaled.height
            val mergedConf = FloatArray(w * h)
            for (subject in subjects) {
                val subjectConf = subject.confidenceMask ?: continue
                for (i in mergedConf.indices) {
                    if (subjectConf[i] > mergedConf[i]) mergedConf[i] = subjectConf[i]
                }
            }
            val isolated = applyConfidenceThreshold(scaled, mergedConf, threshold = 0.5f)
            IsolationResult(isolatedBitmap = isolated, rawConfidence = mergedConf, width = w, height = h)
        }
    }

    fun applyConfidenceThreshold(
        source: Bitmap,
        confidence: FloatArray,
        threshold: Float,
        featherRange: Float = 0.1f
    ): Bitmap {
        val w = source.width; val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val conf = confidence[i]
            val alpha = when {
                conf >= threshold -> 255
                conf <= threshold - featherRange -> 0
                else -> ((conf - (threshold - featherRange)) / featherRange * 255f).toInt().coerceIn(0, 255)
            }
            pixels[i] = (pixels[i] and 0x00FFFFFF) or (alpha shl 24)
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun downsample(bitmap: Bitmap, maxDim: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxDim) return bitmap
        val scale = maxDim.toFloat() / max
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    }
}

data class IsolationResult(
    val isolatedBitmap: Bitmap,
    val rawConfidence: FloatArray,
    val width: Int,
    val height: Int
)

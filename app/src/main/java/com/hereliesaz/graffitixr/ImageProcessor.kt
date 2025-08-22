package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.subjectsegmentation.SubjectSegmentation
import com.google.mlkit.vision.subjectsegmentation.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

suspend fun removeBackground(context: Context, imageUri: Uri): Result<Uri> {
    return try {
        val inputImage = InputImage.fromFilePath(context, imageUri)

        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()

        val segmenter = SubjectSegmentation.getClient(options)

        val result = segmenter.process(inputImage).await()

        val foregroundBitmap = result.foregroundBitmap

        val fileName = "bg_removed_${System.currentTimeMillis()}.png"
        val file = File(context.cacheDir, fileName)
        val fOut = FileOutputStream(file)
        foregroundBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
        fOut.flush()
        fOut.close()

        Result.success(file.toUri())
    } catch (e: Exception) {
        e.printStackTrace()
        Result.failure(e)
    }
}

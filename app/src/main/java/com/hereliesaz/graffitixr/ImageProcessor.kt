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
import java.io.IOException

suspend fun removeBackground(context: Context, imageUri: Uri): Uri? {
    return try {
        val inputImage = InputImage.fromFilePath(context, imageUri)

        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()

        val segmenter = SubjectSegmentation.getClient(options)

        val result = segmenter.process(inputImage).await()

        val foregroundBitmap = result.foregroundBitmap

        val file = File.createTempFile("bg_removed_image_", ".png", context.cacheDir)
        FileOutputStream(file).use { fOut ->
            foregroundBitmap.compress(Bitmap.CompressFormat.PNG, 100, fOut)
            fOut.flush()
        }

        file.toUri()
    } catch (e: IOException) {
        e.printStackTrace()
        null
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

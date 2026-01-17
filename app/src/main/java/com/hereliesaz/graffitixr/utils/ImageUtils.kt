package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    fun generateOutline(input: Bitmap): Bitmap {
        // Ensure OpenCV loaded logic handled by Utils.kt or caller
        val mat = Mat()
        val edges = Mat()
        val result = Mat()

        Utils.bitmapToMat(input, mat)
        Imgproc.cvtColor(mat, edges, Imgproc.COLOR_RGB2GRAY)
        Imgproc.Canny(edges, edges, 50.0, 150.0)
        Core.bitwise_not(edges, result)

        val finalColor = Mat()
        Imgproc.cvtColor(result, finalColor, Imgproc.COLOR_GRAY2RGBA)

        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(finalColor, output)

        mat.release()
        edges.release()
        result.release()
        finalColor.release()

        return output
    }

    fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (e: Exception) { null }
    }

    fun saveBitmapToCache(context: Context, bitmap: Bitmap): Uri {
        val file = File(context.cacheDir, "layer_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return Uri.fromFile(file)
    }

    fun getNextBlendMode(current: androidx.compose.ui.graphics.BlendMode): androidx.compose.ui.graphics.BlendMode {
        val modes = listOf(
            androidx.compose.ui.graphics.BlendMode.SrcOver,
            androidx.compose.ui.graphics.BlendMode.Screen,
            androidx.compose.ui.graphics.BlendMode.Multiply,
            androidx.compose.ui.graphics.BlendMode.Overlay,
            androidx.compose.ui.graphics.BlendMode.Darken,
            androidx.compose.ui.graphics.BlendMode.Lighten,
            androidx.compose.ui.graphics.BlendMode.ColorDodge,
            androidx.compose.ui.graphics.BlendMode.ColorBurn,
            androidx.compose.ui.graphics.BlendMode.Hardlight,
            androidx.compose.ui.graphics.BlendMode.Softlight,
            androidx.compose.ui.graphics.BlendMode.Difference,
            androidx.compose.ui.graphics.BlendMode.Exclusion,
            androidx.compose.ui.graphics.BlendMode.Hue,
            androidx.compose.ui.graphics.BlendMode.Saturation,
            androidx.compose.ui.graphics.BlendMode.Color,
            androidx.compose.ui.graphics.BlendMode.Luminosity
        )
        val index = modes.indexOf(current)
        return modes.getOrElse((index + 1) % modes.size) { androidx.compose.ui.graphics.BlendMode.SrcOver }
    }
}

package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.installations.Utils
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    /**
     * Generates a white outline on a transparent background from the input bitmap using Canny edge detection.
     */
    fun generateOutline(input: Bitmap): Bitmap {
        // Ensure OpenCV loaded logic handled by Utils.kt or caller
        // Using Canny Edge Detection
        val mat = Mat()
        val gray = Mat()
        val edges = Mat()

        Utils.bitmapToMat(input, mat)
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        // Create a black image for RGB channels
        val black = Mat(edges.size(), CvType.CV_8UC1, Scalar(0.0))

        // Merge to create RGBA: R=Black, G=Black, B=Black, A=Edges (White=Opaque, Black=Transparent)
        val channels = java.util.ArrayList<Mat>()
        channels.add(black) // R
        channels.add(black) // G
        channels.add(black) // B
        channels.add(edges) // A

        val result = Mat()
        Core.merge(channels, result)

        val output = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(result, output)

        mat.release()
        gray.release()
        edges.release()
        black.release()
        result.release()

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
        return getUriForFile(file)
    }

    fun getUriForFile(file: File): Uri {
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

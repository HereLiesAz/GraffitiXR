package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import org.opencv.android.Utils as OpenCVUtils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

object ImageUtils {

    /**
     * Generates a white outline on a transparent background from the input bitmap using Canny edge detection.
     */
    fun generateOutline(input: Bitmap): Bitmap {
        val mat = Mat()
        val gray = Mat()
        val edges = Mat()

        OpenCVUtils.bitmapToMat(input, mat)
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
        OpenCVUtils.matToBitmap(result, output)

        mat.release()
        gray.release()
        edges.release()
        black.release()
        result.release()

        return output
    }

    /**
     * Performs a perspective transformation on the bitmap.
     * @param input The source bitmap.
     * @param points normalized (0..1) corners in order: TL, TR, BR, BL.
     */
    fun perspectiveTransform(input: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        val srcMat = Mat()
        OpenCVUtils.bitmapToMat(input, srcMat)

        val w = input.width.toFloat()
        val h = input.height.toFloat()

        // Convert normalized points to pixel coordinates
        // Points are expected in order: TL, TR, BR, BL
        val p0 = Point((points[0].x * w).toDouble(), (points[0].y * h).toDouble())
        val p1 = Point((points[1].x * w).toDouble(), (points[1].y * h).toDouble())
        val p2 = Point((points[2].x * w).toDouble(), (points[2].y * h).toDouble())
        val p3 = Point((points[3].x * w).toDouble(), (points[3].y * h).toDouble())

        // Calculate output dimensions based on max side lengths
        val widthTop = sqrt((p1.x - p0.x).pow(2) + (p1.y - p0.y).pow(2))
        val widthBot = sqrt((p2.x - p3.x).pow(2) + (p2.y - p3.y).pow(2))
        val maxWidth = max(widthTop, widthBot).toInt()

        val heightLeft = sqrt((p3.x - p0.x).pow(2) + (p3.y - p0.y).pow(2))
        val heightRight = sqrt((p2.x - p1.x).pow(2) + (p2.y - p1.y).pow(2))
        val maxHeight = max(heightLeft, heightRight).toInt()

        if (maxWidth <= 0 || maxHeight <= 0) {
            srcMat.release()
            return null
        }

        // Source points from the image
        val src = MatOfPoint2f(p0, p1, p2, p3)

        // Destination points (Rectangular)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble(), 0.0),
            Point(maxWidth.toDouble(), maxHeight.toDouble),
            Point(0.0, maxHeight.toDouble)
        )

        // Get transform matrix
        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val dstMat = Mat(Size(maxWidth.toDouble(), maxHeight.toDouble), srcMat.type())

        // Apply warp
        Imgproc.warpPerspective(srcMat, dstMat, transform, dstMat.size())

        val output = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
        OpenCVUtils.matToBitmap(dstMat, output)

        // Cleanup
        srcMat.release()
        dstMat.release()
        src.release()
        dst.release()
        transform.release()

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

    fun getNextBlendMode(current: BlendMode): BlendMode {
        val modes = listOf(
            BlendMode.SrcOver,
            BlendMode.Screen,
            BlendMode.Multiply,
            BlendMode.Overlay,
            BlendMode.Darken,
            BlendMode.Lighten,
            BlendMode.ColorDodge,
            BlendMode.ColorBurn,
            BlendMode.Hardlight,
            BlendMode.Softlight,
            BlendMode.Difference,
            BlendMode.Exclusion,
            BlendMode.Hue,
            BlendMode.Saturation,
            BlendMode.Color,
            BlendMode.Luminosity
        )
        val index = modes.indexOf(current)
        return modes.getOrElse((index + 1) % modes.size) { BlendMode.SrcOver }
    }
}

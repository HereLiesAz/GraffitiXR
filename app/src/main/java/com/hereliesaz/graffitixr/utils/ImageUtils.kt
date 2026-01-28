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

object ImageUtils {

    fun generateOutline(input: Bitmap): Bitmap {
        val mat = Mat()
        val gray = Mat()
        val edges = Mat()

        OpenCVUtils.bitmapToMat(input, mat)
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        val black = Mat(edges.size(), CvType.CV_8UC1, Scalar(0.0))

        val channels = java.util.ArrayList<Mat>()
        channels.add(black)
        channels.add(black)
        channels.add(black)
        channels.add(edges)

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

    fun perspectiveTransform(input: Bitmap, points: List<Offset>): Bitmap? {
        if (points.size != 4) return null

        val srcMat = Mat()
        OpenCVUtils.bitmapToMat(input, srcMat)

        val w = input.width.toDouble()
        val h = input.height.toDouble()

        val p0x = points[0].x.toDouble() * w
        val p0y = points[0].y.toDouble() * h
        val p0 = Point(p0x, p0y)

        val p1x = points[1].x.toDouble() * w
        val p1y = points[1].y.toDouble() * h
        val p1 = Point(p1x, p1y)

        val p2x = points[2].x.toDouble() * w
        val p2y = points[2].y.toDouble() * h
        val p2 = Point(p2x, p2y)

        val p3x = points[3].x.toDouble() * w
        val p3y = points[3].y.toDouble() * h
        val p3 = Point(p3x, p3y)

        // Calculate dimensions
        val dxTop = p1.x - p0.x
        val dyTop = p1.y - p0.y
        val widthTop = Math.hypot(dxTop, dyTop)

        val dxBot = p2.x - p3.x
        val dyBot = p2.y - p3.y
        val widthBot = Math.hypot(dxBot, dyBot)

        val maxWidth = max(widthTop, widthBot)

        val dxLeft = p3.x - p0.x
        val dyLeft = p3.y - p0.y
        val heightLeft = Math.hypot(dxLeft, dyLeft)

        val dxRight = p2.x - p1.x
        val dyRight = p2.y - p1.y
        val heightRight = Math.hypot(dxRight, dyRight)

        val maxHeight = max(heightLeft, heightRight)

        if (maxWidth <= 0 || maxHeight <= 0) {
            srcMat.release()
            return null
        }

        val src = MatOfPoint2f(p0, p1, p2, p3)

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth, 0.0),
            Point(maxWidth, maxHeight),
            Point(0.0, maxHeight)
        )

        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val dstMat = Mat(Size(maxWidth, maxHeight), srcMat.type())

        Imgproc.warpPerspective(srcMat, dstMat, transform, dstMat.size())

        val output = Bitmap.createBitmap(maxWidth.toInt(), maxHeight.toInt(), Bitmap.Config.ARGB_8888)
        OpenCVUtils.matToBitmap(dstMat, output)

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

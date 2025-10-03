package com.hereliesaz.graffitixr.graphics

import android.view.View
import com.google.ar.core.Camera
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point

/**
 * A helper class for calculating a homography matrix to project an image onto a set of 3D points.
 */
object HomographyHelper {

    /**
     * Calculates the homography matrix to warp an image to the specified 3D points.
     *
     * @param worldPoints A list of four 3D points (as `FloatArray` of size 3) in world coordinates.
     * @param camera The ARCore camera, used to get the view and projection matrices.
     * @param view The view that is rendering the camera feed, used to get the correct dimensions.
     * @param imageWidth The width of the image to be projected.
     * @param imageHeight The height of the image to be projected.
     * @return A 3x3 homography matrix as an OpenCV [Mat].
     */
    fun calculateHomography(
        worldPoints: List<FloatArray>,
        camera: Camera,
        view: View,
        imageWidth: Int,
        imageHeight: Int
    ): Mat? {
        if (worldPoints.size < 4) return null

        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)

        val screenPoints = worldPoints.map { worldPoint ->
            val worldPos = floatArrayOf(worldPoint[0], worldPoint[1], worldPoint[2], 1.0f)
            val viewPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, worldPos, 0)
            val screenPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(screenPos, 0, projectionMatrix, 0, viewPos, 0)

            // Convert from normalized device coordinates to view coordinates
            val x = (screenPos[0] / screenPos[3] + 1.0f) / 2.0f * view.width
            val y = (1.0f - (screenPos[1] / screenPos[3])) / 2.0f * view.height
            Point(x.toDouble(), y.toDouble())
        }

        val srcPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(imageWidth.toDouble(), 0.0),
            Point(imageWidth.toDouble(), imageHeight.toDouble()),
            Point(0.0, imageHeight.toDouble())
        )

        val dstPoints = MatOfPoint2f().apply { fromList(screenPoints) }

        return Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC, 5.0)
    }
}
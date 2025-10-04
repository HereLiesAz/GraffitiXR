package com.hereliesaz.graffitixr.graphics

import android.view.View
import com.google.ar.core.Camera
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point

object HomographyHelper {

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

        val screenPoints = worldPoints.mapNotNull { worldPoint ->
            val worldPos = floatArrayOf(worldPoint[0], worldPoint[1], worldPoint[2], 1.0f)
            val viewPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, worldPos, 0)
            val screenPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(screenPos, 0, projectionMatrix, 0, viewPos, 0)

            if (screenPos[3] == 0.0f) return@mapNotNull null

            // Convert from normalized device coordinates to view coordinates
            val x = (screenPos[0] / screenPos[3] + 1.0f) / 2.0f * view.width
            val y = (1.0f - (screenPos[1] / screenPos[3])) / 2.0f * view.height

            if (!x.isFinite() || !y.isFinite()) return@mapNotNull null

            Point(x.toDouble(), y.toDouble())
        }

        if (screenPoints.size < 4) return null

        val srcPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(imageWidth.toDouble(), 0.0),
            Point(imageWidth.toDouble(), imageHeight.toDouble()),
            Point(0.0, imageHeight.toDouble())
        )

        val dstPoints = MatOfPoint2f().apply { fromList(screenPoints) }

        if (dstPoints.rows() < 4) return null

        return Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC, 5.0)
    }
}

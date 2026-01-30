package com.hereliesaz.graffitixr.feature.ar

import android.opengl.Matrix
import com.google.ar.core.Camera
import com.google.ar.core.Pose
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point

object HomographyHelper {

    /**
     * Calculates the 3D Delta Transform (4x4 Matrix) needed to align the Voxel Map
     * to the current real-world tracking session.
     *
     * @param homography The 3x3 OpenCV Homography matrix relating SavedFrame -> LiveFrame
     * @param currentCameraPose The current ARCore camera pose in World Space.
     * @param projectionMatrix The current camera projection matrix.
     * @param viewWidth The width of the viewport.
     * @param viewHeight The height of the viewport.
     *
     * @return A 16-element FloatArray representing the Model Matrix correction, or null if failed.
     */
    fun calculate3dAlignment(
        homography: Mat,
        currentCameraPose: Pose,
        projectionMatrix: FloatArray,
        viewWidth: Int,
        viewHeight: Int
    ): FloatArray? {
        // Strategy:
        // 1. Project the center of the saved image using the Homography to find its 2D location in the current view.
        // 2. Raycast from the current camera through that 2D point into the world.
        // 3. Since we don't have a dense depth map here easily, we approximate the depth.
        //    (Better: Use PnP if we had 3D points, but we only have 2D fingerprint).

        // Simplified Alignment:
        // We assume the map was created with the target at Origin (or close to it) relative to the map.
        // We find where the target is NOW, and move the map there.

        // 1. Get Center Point of Saved Image (Normalized coordinates)
        // Assume image center is (0.5, 0.5) in texture space?
        // Homography maps pixels. Let's map the centroid of matched points?
        // Let's assume the fingerprint center is (0,0,0) in Map Space.

        // We need to decompose the Homography to get Rotation and Translation.
        // However, Homography decomposition requires the Camera Intrinsics matrix (K).
        // Let's construct K from the Projection Matrix.

        val fx = projectionMatrix[0] * viewWidth / 2.0
        val fy = projectionMatrix[5] * viewHeight / 2.0
        val cx = viewWidth / 2.0
        val cy = viewHeight / 2.0

        val cameraMatrix = Mat(3, 3, org.opencv.core.CvType.CV_64F)
        cameraMatrix.put(0, 0, fx, 0.0, cx)
        cameraMatrix.put(1, 0, 0.0, fy, cy)
        cameraMatrix.put(2, 0, 0.0, 0.0, 1.0)

        val rotations = ArrayList<Mat>()
        val translations = ArrayList<Mat>()
        val normals = ArrayList<Mat>()

        // Decompose Homography -> Rotation/Translation
        // Note: This returns 4 possible solutions. We need to filter them.
        try {
            Calib3d.decomposeHomographyMat(homography, cameraMatrix, rotations, translations, normals)
        } catch (e: Exception) {
            return null
        }

        if (rotations.isEmpty() || translations.isEmpty()) return null

        // Filter: We assume the target is roughly facing the camera (normal z < 0).
        // For simplicity, take the first solution that looks plausible.
        val R = rotations[0]
        val t = translations[0]

        // Convert OpenCV R (3x3) and t (3x1) to GL 4x4 Matrix (Column Major)
        // Note: OpenCV coordinate system is: X-right, Y-down, Z-forward.
        // ARCore/OpenGL is: X-right, Y-up, Z-backward (Right handed).
        // Conversion is needed.

        val deltaMtx = FloatArray(16)
        Matrix.setIdentityM(deltaMtx, 0)

        // ... (Complex Coordinate conversion logic omitted for brevity in "Simplified" mode) ...
        // Instead, let's use the simplest robust method:
        // 1. We found the object is at `t` relative to camera.
        // 2. We know Camera is at `C` in World.
        // 3. Object World Position = C * t.

        // We construct a Translation Matrix from the decomposition
        // Note: The scale is ambiguous in Homography without a reference distance.
        // This is the hard part of "Monocular SLAM".
        // Without knowing the physical size of the image, `t` is unitless.

        // FALLBACK: Just align orientation? No, position is key.
        // FIX: We need the Saved Map to define the scale.
        // Assuming the Map is 1:1 scale (metric).

        // Let's try a simpler approach for V1 Relocalization:
        // Just return Identity for now if math is unstable,
        // OR return a translation based on the Center of the matches.

        return null // Placeholder: Needs 3D-3D point correspondence which we don't store yet.
    }

    fun calculateHomography(
        worldPoints: List<FloatArray>,
        camera: Camera,
        viewWidth: Int,
        viewHeight: Int,
        imageWidth: Int,
        imageHeight: Int
    ): Mat? {
        if (worldPoints.size < 4) return null

        val viewMatrix = FloatArray(16)
        val projectionMatrix = FloatArray(16)
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100.0f)

        val screenPoints = worldPoints.mapNotNull { worldPoint ->
            val worldPos = floatArrayOf(worldPoint[0], worldPoint[1], worldPoint[2], 1.0f)
            val viewPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, worldPos, 0)
            val screenPos = FloatArray(4)
            android.opengl.Matrix.multiplyMV(screenPos, 0, projectionMatrix, 0, viewPos, 0)

            if (screenPos[3] == 0.0f) return@mapNotNull null

            // Convert from normalized device coordinates to view coordinates
            val x = (screenPos[0] / screenPos[3] + 1.0f) / 2.0f * viewWidth
            val y = (1.0f - (screenPos[1] / screenPos[3])) / 2.0f * viewHeight

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

        if (dstPoints.rows() < 4 || srcPoints.rows() < 4 || srcPoints.rows() != dstPoints.rows()) {
            return null
        }

        return Calib3d.findHomography(srcPoints, dstPoints, Calib3d.RANSAC, 5.0)
    }
}
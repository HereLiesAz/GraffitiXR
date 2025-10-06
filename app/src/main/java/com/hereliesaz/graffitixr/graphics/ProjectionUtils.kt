package com.hereliesaz.graffitixr.graphics

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Pose

object ProjectionUtils {

    /**
     * Projects a 3D world coordinate to 2D screen coordinates.
     *
     * This is a crucial function for bridging the 3D world of ARCore with the 2D UI of Jetpack Compose.
     * It takes a 3D pose (like an anchor's pose), the camera's view and projection matrices, and the
     * dimensions of the screen view. It then performs the standard computer graphics transformation
     * pipeline (Model -> View -> Clip -> NDC) to find the corresponding (x, y) coordinate on the screen.
     *
     * @param pose The 3D pose in world coordinates.
     * @param viewMatrix The camera view matrix, representing the camera's position and orientation.
     * @param projectionMatrix The camera projection matrix, representing the camera's lens properties (FOV, etc.).
     * @param viewWidth The width of the view where the content is being rendered.
     * @param viewHeight The height of the view where the content is being rendered.
     * @return The projected 2D [Offset] on the screen, or null if the point is behind the camera (and thus not visible).
     */
    fun projectWorldToScreen(
        pose: Pose,
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        viewWidth: Int,
        viewHeight: Int
    ): Offset? {
        val worldPos = floatArrayOf(pose.tx(), pose.ty(), pose.tz(), 1f)
        val viewPos = FloatArray(4)
        Matrix.multiplyMV(viewPos, 0, viewMatrix, 0, worldPos, 0)

        val clipPos = FloatArray(4)
        Matrix.multiplyMV(clipPos, 0, projectionMatrix, 0, viewPos, 0)

        // The point is behind the camera's near plane.
        if (clipPos[3] == 0.0f) {
            return null
        }

        // Convert from clip space to Normalized Device Coordinates (NDC).
        val ndcPos = floatArrayOf(
            clipPos[0] / clipPos[3],
            clipPos[1] / clipPos[3],
            clipPos[2] / clipPos[3]
        )

        // Convert from NDC to screen coordinates.
        val screenX = (ndcPos[0] + 1.0f) / 2.0f * viewWidth
        val screenY = (1.0f - ndcPos[1]) / 2.0f * viewHeight

        return Offset(screenX, screenY)
    }
}
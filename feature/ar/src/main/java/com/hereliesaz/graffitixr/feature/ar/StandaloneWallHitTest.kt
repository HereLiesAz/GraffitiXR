package com.hereliesaz.graffitixr.feature.ar

import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.Vec3
import kotlin.math.abs

/**
 * One rectangular region of the canonical standalone wall currently backed by a KPM page.
 *
 * All regions use the immutable centered SphereSLAM wall frame: +X right, +Y up, z=0 on the wall.
 */
internal data class StandaloneWallRegion(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    init {
        require(centerX.isFinite() && centerY.isFinite())
        require(width.isFinite() && width > 0f)
        require(height.isFinite() && height > 0f)
    }

    fun contains(x: Float, y: Float, epsilon: Float = 1e-5f): Boolean =
        x >= centerX - width * 0.5f - epsilon &&
            x <= centerX + width * 0.5f + epsilon &&
            y >= centerY - height * 0.5f - epsilon &&
            y <= centerY + height * 0.5f + epsilon
}

/**
 * Canonical-wall ray intersection for standalone consumers that genuinely need a screen-point hit.
 *
 * Standalone wall establishment itself is NOT tap-to-place: target capture + KPM establish the
 * canonical wall, and ordinary artwork placement uses wall-local gesture offsets. Consequently this
 * helper is intentionally not wired to a synthetic "tap to anchor" action merely to imitate ARCore.
 * It exists for shared features that require a screen point (future cross-backend calibration,
 * measurement, etc.). Such a caller must pass a visually locked same-frame KPM pose.
 *
 * The function back-projects one display pixel through the calibrated CameraX pinhole model,
 * intersects the ray with canonical z=0, and accepts the hit only inside a registered KPM page. It
 * never invents a hit while unlocked and never treats normalized KPM scale as physical depth.
 */
internal object StandaloneWallHitTest {
    fun hit(
        screenX: Float,
        screenY: Float,
        viewMatrix: FloatArray,
        intrinsics: CameraIntrinsics,
        frameWidth: Int,
        frameHeight: Int,
        coveredRegions: List<StandaloneWallRegion>,
        wallLocked: Boolean,
    ): Vec3? {
        if (!wallLocked || coveredRegions.isEmpty()) return null
        if (
            viewMatrix.size != 16 ||
            viewMatrix.any { !it.isFinite() } ||
            frameWidth <= 0 ||
            frameHeight <= 0 ||
            intrinsics.fx <= 0f ||
            intrinsics.fy <= 0f ||
            screenX !in 0f..frameWidth.toFloat() ||
            screenY !in 0f..frameHeight.toFloat()
        ) return null

        // Convert the OpenGL eye transform back to the OpenCV/CameraX convention used by KPM:
        // +X right, +Y down, +Z forward.
        val r00 = viewMatrix[0]; val r01 = viewMatrix[4]; val r02 = viewMatrix[8]
        val r10 = -viewMatrix[1]; val r11 = -viewMatrix[5]; val r12 = -viewMatrix[9]
        val r20 = -viewMatrix[2]; val r21 = -viewMatrix[6]; val r22 = -viewMatrix[10]
        val tx = viewMatrix[12]
        val ty = -viewMatrix[13]
        val tz = -viewMatrix[14]

        // Camera centre in canonical coordinates: C = -R^T t.
        val cx = -(r00 * tx + r10 * ty + r20 * tz)
        val cy = -(r01 * tx + r11 * ty + r21 * tz)
        val cz = -(r02 * tx + r12 * ty + r22 * tz)

        val dxCamera = (screenX - intrinsics.cx) / intrinsics.fx
        val dyCamera = (screenY - intrinsics.cy) / intrinsics.fy
        val dzCamera = 1f

        // Ray direction in canonical coordinates: R^T * d_camera.
        val dx = r00 * dxCamera + r10 * dyCamera + r20 * dzCamera
        val dy = r01 * dxCamera + r11 * dyCamera + r21 * dzCamera
        val dz = r02 * dxCamera + r12 * dyCamera + r22 * dzCamera
        if (!dz.isFinite() || abs(dz) < 1e-6f) return null

        val lambda = -cz / dz
        if (!lambda.isFinite() || lambda <= 0f) return null

        val x = cx + lambda * dx
        val y = cy + lambda * dy
        if (!x.isFinite() || !y.isFinite()) return null
        if (coveredRegions.none { it.contains(x, y) }) return null
        return Vec3(x, y, 0f)
    }
}

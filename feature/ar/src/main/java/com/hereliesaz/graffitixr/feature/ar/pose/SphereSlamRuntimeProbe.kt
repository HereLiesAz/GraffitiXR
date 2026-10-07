package com.hereliesaz.graffitixr.feature.ar.pose

import com.hereliesaz.sphereslam.SphereSlam
import com.hereliesaz.sphereslam.SphereSlamCalibration
import com.hereliesaz.sphereslam.SphereSlamEngine

/**
 * Supported SphereSLAM runtime capability probe.
 *
 * SphereSLAM 0.23.5 made its native-only `smokeTest` hook internal. GraffitiXR still needs the
 * stronger fail-closed check that hook previously provided: JNI symbols being present is not enough;
 * a calibrated native KPM session must actually be creatable and destroyable.
 *
 * This probe therefore stays entirely on SphereSLAM's supported public API:
 * 1. [SphereSlam.isAvailable] verifies the native KPM entry points are linked.
 * 2. [SphereSlam.create] creates the same calibrated engine used by the real runtime.
 * 3. [SphereSlamEngine.isReady] requires a nonzero native session handle.
 * 4. The temporary engine is always closed.
 *
 * The calibration is synthetic because no image is matched here; it only needs to be valid for
 * native session construction. Real tracking still uses the actual Camera2/ARCore intrinsics.
 */
internal object SphereSlamRuntimeProbe {
    private const val DEFAULT_WIDTH = 640
    private const val DEFAULT_HEIGHT = 480

    fun isOperational(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
    ): Boolean = runCatching {
        probe(
            width = width,
            height = height,
            available = SphereSlam::isAvailable,
            create = SphereSlam::create,
        )
    }.getOrDefault(false)

    internal fun probe(
        width: Int,
        height: Int,
        available: () -> Boolean,
        create: (Int, Int, SphereSlamCalibration) -> SphereSlamEngine,
    ): Boolean {
        require(width > 0 && height > 0)
        if (!available()) return false

        val calibration = SphereSlamCalibration(
            fx = width.toFloat(),
            fy = width.toFloat(),
            cx = width / 2f,
            cy = height / 2f,
        )
        val engine = create(width, height, calibration)
        return try {
            engine.isReady
        } finally {
            engine.close()
        }
    }
}

package com.hereliesaz.graffitixr.feature.ar.pose

import android.util.Log
import com.hereliesaz.graffitixr.nativebridge.KpmBridge

/**
 * Entry point for the non-ARCore tracking bring-up.
 *
 * The non-ARCore base is a fork of artoolkitX's KPM (keypoint matching) + AR2, vendored as native
 * source under `third_party/artoolkitx` and built into the `graffitixr` native library (see
 * core/nativebridge CMake). On top of it we build the planar-mosaic fingerprint (each captured view a
 * metric planar KPM page, tiled across the wall plane so toward/away translation comes from the
 * homography — not a photosphere, which loses depth), grown adaptively for overpaint survival.
 *
 * Phase 1 (now) is a link smoke test: prove the forked KPM actually compiles and links in the NDK
 * build on-device before any capture/match/mosaic logic. [probe] runs it and logs the result.
 */
object PoseSourceRegistry {
    private const val TAG = "POSEPROBE"

    /**
     * Non-ARCore bring-up smoke test, invoked by the -PposeProbe launch path. Currently: confirm the
     * forked artoolkitX KPM links and a handle can be created natively. Never touches the ARCore
     * render path. Logs to logcat (tag POSEPROBE).
     */
    fun probe() {
        Thread({
            val available = runCatching { KpmBridge.isAvailable() }.getOrDefault(false)
            if (!available) {
                Log.w(TAG, "probe: KPM not built in (is the third_party/artoolkitx submodule present " +
                    "and CMake building arx_kpm?)")
                return@Thread
            }
            val ok = runCatching { KpmBridge.smokeTest(640, 480) }.getOrElse { e ->
                Log.e(TAG, "probe: KPM smoke test threw", e); false
            }
            Log.i(TAG, "probe: KPM link smoke test ${if (ok) "PASSED (handle created)" else "FAILED"}")
        }, "pose-probe").start()
    }
}

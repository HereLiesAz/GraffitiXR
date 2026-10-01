package com.hereliesaz.graffitixr.feature.ar.pose

import android.util.Log
import com.hereliesaz.sphereslam.SphereSlam

/**
 * Registry/bring-up entry point for pose producers.
 *
 * Runtime architecture is capability-dependent:
 *
 * - with ARCore, [ArCorePoseSource] remains the primary continuous pose source and SphereSLAM runs
 *   beside it for wall relocalization/correction;
 * - without ARCore, SphereSLAM is required to become the primary standalone [PoseSource] so AR mode
 *   remains functional without constructing an ARCore Session.
 *
 * SphereSLAM Phase 2 currently exposes calibrated artoolkitX KPM page generation/matching only.
 * That is enough for the hybrid relocalization sidecar, but it is not yet the continuous metric
 * 6-DoF tracker required for standalone mode. Selection must therefore distinguish "KPM available"
 * from "standalone SphereSLAM ready".
 */
object PoseSourceRegistry {
    private const val TAG = "POSEPROBE"

    /**
     * Opt-in native link probe invoked by -PposeProbe=true. This checks only the currently
     * implemented SphereSLAM/KPM relocalization capability. A passing probe does not mean the
     * standalone SphereSLAM pose backend is complete or safe to select.
     */
    fun probe() {
        Thread({
            val available = runCatching { SphereSlam.isAvailable() }.getOrDefault(false)
            if (!available) {
                Log.w(
                    TAG,
                    "probe: SphereSLAM/KPM not built in (is third_party/artoolkitx checked out?)",
                )
                return@Thread
            }
            val ok = runCatching { SphereSlam.smokeTest(640, 480) }.getOrElse { e ->
                Log.e(TAG, "probe: SphereSLAM/KPM smoke test threw", e)
                false
            }
            Log.i(
                TAG,
                "probe: SphereSLAM/KPM link smoke test " +
                    if (ok) "PASSED (KPM available; standalone pose readiness not implied)" else "FAILED",
            )
        }, "pose-probe").start()
    }
}

package com.hereliesaz.graffitixr.feature.ar.pose

import android.util.Log
import com.hereliesaz.sphereslam.SphereSlam

/**
 * Registry/bring-up entry point for pose producers.
 *
 * ARCore remains the existing production pose source through [ArCorePoseSource]. SphereSLAM is added
 * beside it, not underneath it and not in place of it. The two paths deliberately stay independent
 * behind [PoseSource] so devices can use ARCore when available while the native path can mature and be
 * selected explicitly later.
 *
 * SphereSLAM Phase 2 currently exposes artoolkitX KPM planar homography tracking. That is enough to
 * validate wall locking while moving toward/away from the surface, but it is NOT yet advertised as a
 * calibrated metric 6-DoF pose. The ARCore render path is untouched.
 */
object PoseSourceRegistry {
    private const val TAG = "POSEPROBE"

    /**
     * Opt-in native bring-up probe invoked by -PposeProbe=true.
     *
     * This checks only the SphereSLAM/KPM sibling path. It never changes, replaces, or wraps the
     * active [ArCorePoseSource].
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
                    if (ok) "PASSED (ARCore unchanged)" else "FAILED",
            )
        }, "pose-probe").start()
    }
}

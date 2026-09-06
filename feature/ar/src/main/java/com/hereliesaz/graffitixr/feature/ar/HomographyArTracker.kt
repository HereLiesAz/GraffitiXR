// FILE: feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/HomographyArTracker.kt
package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.nativebridge.HomographyTrackerNative

/**
 * ARCore-unavailable fallback: a Kotlin-shaped wrapper over [HomographyTrackerNative] /
 * `HomographyTracker.h`'s planar-target tracker.
 *
 * This is the tracking math and its JNI boundary. [HomographyFallbackOverlay] is what actually
 * wires this to CameraX, camera intrinsics, [com.hereliesaz.graffitixr.feature.ar.rendering.OverlayRenderer],
 * and [com.hereliesaz.graffitixr.common.model.EditorMode]/`ArUiState` — that wiring exists and is
 * live, despite an earlier version of this comment (and `BridgedHomographyTracker`'s) describing it
 * as a future "Phase 2." Trusting that stale claim is plausibly how the sign error in
 * `HomographyTracker.cpp`'s CV->GL pose conversion (see BACKLOG.md's remediation-plan Phase 2)
 * went unnoticed — a reader who believes this subsystem isn't wired up yet has no reason to test it.
 * Read `HomographyTracker.h`'s class doc before using this for anything beyond a fallback — it is
 * explicit about what's tracked exactly (on-screen size/position/skew, as long as callers pass the
 * SAME half-extents to [setReference] that the renderer draws the design quad at) versus what has
 * no ground truth to check against (absolute distance from the camera, since there is no depth
 * sensor or VIO baseline behind this).
 *
 * Not a singleton and not DI-managed: unlike [com.hereliesaz.graffitixr.nativebridge.SlamManager],
 * this wraps stateless native calls (the actual reference/continuity state lives in the native
 * `gHomographyTracker` singleton) — callers can construct one per use without any lifecycle to
 * coordinate with other users of the tracker. If two callers used one concurrently they'd be
 * sharing that single native reference, which is only ever meant to be true of one active tracking
 * session at a time.
 */
class HomographyArTracker {

    /** One tracked pose: an OverlayRenderer-ready view matrix plus a [0,1] tracking confidence. */
    data class HomographyPose(val viewMatrix: FloatArray, val confidence: Float) {
        init {
            require(viewMatrix.size == 16) { "viewMatrix must be FloatArray(16)" }
        }

        // FloatArray has no structural equals/hashCode; generated ones would be reference-identity
        // for the array field, silently breaking equality for a data class that otherwise looks
        // value-like. Content-based, matching what a caller comparing two poses would expect.
        override fun equals(other: Any?): Boolean =
            this === other || (other is HomographyPose &&
                viewMatrix.contentEquals(other.viewMatrix) && confidence == other.confidence)

        override fun hashCode(): Int = 31 * viewMatrix.contentHashCode() + confidence.hashCode()
    }

    // Reused across track() calls so tracking a live camera feed doesn't allocate every frame.
    private val trackOut = FloatArray(17)

    /**
     * Delegates to [HomographyTrackerNative.setReference] — see its doc, and
     * `HomographyTracker.h`'s class doc, for why [objectHalfW]/[objectHalfH] matter: pass the
     * SAME half-extents [com.hereliesaz.graffitixr.feature.ar.rendering.OverlayRenderer.setExtent]
     * will draw the design quad at, and every tracked pose comes back already correctly scaled to
     * match the reference shape's real on-screen size — no separate calibration needed.
     */
    fun setReference(referenceBitmap: Bitmap, objectHalfW: Float, objectHalfH: Float): Boolean =
        HomographyTrackerNative.setReference(referenceBitmap, objectHalfW, objectHalfH)

    /** Delegates to [HomographyTrackerNative.hasReference]. */
    fun hasReference(): Boolean = HomographyTrackerNative.hasReference()

    /** Delegates to [HomographyTrackerNative.reset]. */
    fun reset() = HomographyTrackerNative.reset()

    /**
     * Track one live camera frame. Returns null on a failed/lost-tracking frame — callers should
     * hold their last good [HomographyPose] rather than treat null as "snap to nothing", the same
     * way AR mode holds its last fused pose across a dropped ARCore frame.
     */
    fun track(frameBitmap: Bitmap, fx: Float, fy: Float, cx: Float, cy: Float): HomographyPose? {
        if (!HomographyTrackerNative.track(frameBitmap, fx, fy, cx, cy, trackOut)) return null
        return HomographyPose(trackOut.copyOf(16), trackOut[16])
    }
}

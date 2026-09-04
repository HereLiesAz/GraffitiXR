// FILE: core/nativebridge/src/main/java/com/hereliesaz/graffitixr/nativebridge/HomographyTrackerNative.kt
package com.hereliesaz.graffitixr.nativebridge

import android.graphics.Bitmap
import com.hereliesaz.graffitixr.common.util.NativeLibLoader

/**
 * Thin JNI binding for [HomographyTracker.h]'s ARCore-unavailable fallback tracker — see that
 * header for what it does and, just as importantly, what it approximates rather than measures.
 *
 * A top-level `object` (no DI, no per-instance state on the Kotlin side): the native
 * `gHomographyTracker` it binds to is itself a single process-lifetime instance in
 * GraffitiJNI.cpp, entirely independent of [SlamManager]'s `gSlamEngine` — no shared state, no
 * `ensureInitialized`/`destroy` lifecycle to coordinate with.
 *
 * Prefer [com.hereliesaz.graffitixr.feature.ar.HomographyArTracker] (feature:ar) for actual call
 * sites — it wraps this raw binding in a Kotlin-shaped API (a `HomographyPose` result instead of a
 * bare `FloatArray`, `Boolean` returns turned into nulls). This object exists as the frozen-ABI
 * boundary that wrapper calls into, matching [YuvConverter]'s split.
 */
object HomographyTrackerNative {

    init {
        NativeLibLoader.loadAll()
    }

    /**
     * Detect and store features on a reference photo of the traced/painted shape, replacing any
     * previous reference. `bitmap` must be `ARGB_8888`. Returns false if too few features were
     * found (a caller-facing "try a better-lit or more detailed reference").
     *
     * @param objectHalfW,objectHalfH the reference shape's half-extents in the SAME units the
     *   caller's renderer will draw the design quad at — see `HomographyTracker.h`'s class doc.
     *   Every tracked pose comes back already scaled to match; there is no separate calibration
     *   step and no arbitrary "assumed distance" anywhere in this path.
     */
    fun setReference(bitmap: Bitmap, objectHalfW: Float, objectHalfH: Float): Boolean {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "bitmap must be ARGB_8888" }
        require(objectHalfW > 0f && objectHalfH > 0f) { "objectHalfW/H must be positive" }
        return nativeHomographySetReference(bitmap, objectHalfW, objectHalfH)
    }

    /** True once [setReference] has succeeded and [reset] has not been called since. */
    fun hasReference(): Boolean = nativeHomographyHasReference()

    /** Drops the stored reference and pose continuity. */
    fun reset() = nativeHomographyReset()

    /**
     * Track one live camera frame against the stored reference.
     *
     * @param bitmap the live frame, `ARGB_8888`.
     * @param fx,fy,cx,cy camera intrinsics in pixels, assumed shared with the reference capture.
     * @param out a caller-owned `FloatArray(17)`: `[0..15]` = the 4x4 column-major, OpenGL-
     *   convention "camera-from-reference-plane" pose, `[16]` = the RANSAC inlier-ratio confidence
     *   in `[0, 1]`. Left untouched when this returns false.
     * @return false if tracking failed this frame — no reference set, too few matches, or the
     *   homography/decomposition didn't produce a usable pose.
     */
    fun track(bitmap: Bitmap, fx: Float, fy: Float, cx: Float, cy: Float, out: FloatArray): Boolean {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "bitmap must be ARGB_8888" }
        require(out.size >= 17) { "out must be at least FloatArray(17)" }
        return nativeHomographyTrack(bitmap, fx, fy, cx, cy, out)
    }

    /**
     * FROZEN JNI ABI — GraffitiJNI.cpp resolves these by exact descriptor.
     * See HomographyTrackerNativeContractTest, which locks the descriptors against literals.
     */
    private external fun nativeHomographySetReference(bitmap: Bitmap, objectHalfW: Float, objectHalfH: Float): Boolean
    private external fun nativeHomographyHasReference(): Boolean
    private external fun nativeHomographyReset()
    private external fun nativeHomographyTrack(
        bitmap: Bitmap,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        out: FloatArray,
    ): Boolean
}

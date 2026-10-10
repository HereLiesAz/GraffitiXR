package com.hereliesaz.graffitixr.feature.ar

/**
 * Pure capture-time seam between a standalone fingerprint capture and the base SphereSLAM
 * photosphere (docs/SPHERESLAM_SPHERE_MAP.md §5.3).
 *
 * A fingerprint is bound to the photosphere through the camera view it was captured from: the
 * pointing direction (compass heading/elevation from `CameraAttitudeProvider`) plus pinhole
 * intrinsics. This object picks that view and, when it cannot, names the reason so the artist gets
 * an actionable message instead of a silent fail-closed confirmation.
 *
 * It never invents a direction: no attitude means no placement.
 */
internal object StandalonePhotosphereCapture {

    /** Why a standalone fingerprint capture cannot be bound to the photosphere. */
    enum class Blocker {
        /** The device has no magnetometer-fused rotation-vector sensor. Not recoverable. */
        ATTITUDE_SENSOR_MISSING,

        /** The sensor exists but has not produced a sample yet (just started / near-vertical aim). */
        ATTITUDE_NOT_READY,

        /** No usable photosphere keyframe exists yet, so there are no frame intrinsics to project with. */
        PHOTOSPHERE_EMPTY,
    }

    /** Camera view a capture is projected through; intrinsics are in [width]x[height] pixels. */
    data class View(
        val headingDeg: Float,
        val elevationDeg: Float,
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
    )

    /**
     * The capture view for the current camera direction.
     *
     * Prefers the keyframe already recorded for the current tile (unchanged behavior). When that tile
     * has no keyframe yet — the artist aimed at a fresh direction and captured before the 250 ms
     * keyframe cadence / quality gate recorded it — the capture still knows exactly where the camera
     * points ([attitude]) and the session's intrinsics are constant, so the most recent keyframe's
     * intrinsics are paired with the CURRENT attitude. The fallback never borrows another tile's
     * direction.
     *
     * @return null when there is no attitude, no keyframe at all, or the intrinsics are unusable.
     */
    fun view(
        attitude: Pair<Float, Float>?,
        currentTileKeyframe: SphereSlamPhotosphereKeyframe?,
        latestKeyframe: SphereSlamPhotosphereKeyframe?,
    ): View? {
        if (attitude == null || !attitude.first.isFinite() || !attitude.second.isFinite()) return null
        val source = currentTileKeyframe ?: latestKeyframe ?: return null
        val headingDeg = if (currentTileKeyframe != null) source.headingDeg else attitude.first
        val elevationDeg = if (currentTileKeyframe != null) source.elevationDeg else attitude.second
        val fx = source.intrinsics.getOrNull(0) ?: return null
        val fy = source.intrinsics.getOrNull(1) ?: return null
        val cx = source.intrinsics.getOrNull(2) ?: return null
        val cy = source.intrinsics.getOrNull(3) ?: return null
        if (fx <= 0f || fy <= 0f || source.width <= 0 || source.height <= 0) return null
        return View(headingDeg, elevationDeg, source.width, source.height, fx, fy, cx, cy)
    }

    /** First reason [view] would fail, or null when a capture can be bound. */
    fun blocker(
        attitudeSensorAvailable: Boolean,
        attitude: Pair<Float, Float>?,
        hasAnyKeyframe: Boolean,
    ): Blocker? = when {
        !attitudeSensorAvailable -> Blocker.ATTITUDE_SENSOR_MISSING
        attitude == null -> Blocker.ATTITUDE_NOT_READY
        !hasAnyKeyframe -> Blocker.PHOTOSPHERE_EMPTY
        else -> null
    }

    /** Concise artist-facing instruction for [blocker]. */
    fun userMessage(blocker: Blocker): String = when (blocker) {
        Blocker.ATTITUDE_SENSOR_MISSING ->
            "This phone has no rotation sensor, so SphereSLAM can't place a wall target here."
        Blocker.ATTITUDE_NOT_READY ->
            "Compass isn't ready yet — hold the phone upright toward the wall for a second, then retake."
        Blocker.PHOTOSPHERE_EMPTY ->
            "SphereSLAM hasn't mapped this view yet — hold the camera steady on a lit, " +
                "detailed part of the wall for a second, then retake."
    }
}

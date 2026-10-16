package com.hereliesaz.graffitixr.feature.ar

/**
 * Pure capture-time seam between a standalone fingerprint capture and the base SphereSLAM
 * photosphere (docs/SPHERESLAM_SPHERE_MAP.md §5.3).
 *
 * A fingerprint is bound to the photosphere through the camera view it was captured from: the
 * pointing direction (compass heading/elevation/roll from `CameraAttitudeProvider`) plus pinhole
 * intrinsics. SphereSLAM picks that view (`PhotosphereKeyframeStore.view`) and places the
 * fingerprint (`PhotosphereFingerprintFrame.placement`); this object names the reason when it
 * cannot, so the artist gets an actionable message instead of a silent fail-closed confirmation.
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

    /** First reason a capture cannot be bound to the photosphere, or null when it can. */
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

    /** Concise artist-facing instruction for [blocker], as a typed key resolved at the UI edge. */
    fun userText(blocker: Blocker): StandaloneText = StandaloneText(
        when (blocker) {
            Blocker.ATTITUDE_SENSOR_MISSING -> StandaloneTextKey.PHOTOSPHERE_NO_SENSOR
            Blocker.ATTITUDE_NOT_READY -> StandaloneTextKey.PHOTOSPHERE_COMPASS_NOT_READY
            Blocker.PHOTOSPHERE_EMPTY -> StandaloneTextKey.PHOTOSPHERE_NOT_MAPPED
        }
    )
}

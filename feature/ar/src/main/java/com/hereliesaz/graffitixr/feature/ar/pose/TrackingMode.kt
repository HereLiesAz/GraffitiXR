package com.hereliesaz.graffitixr.feature.ar.pose

/**
 * Runtime tracking choice for GraffitiXR AR.
 *
 * This policy deliberately separates "SphereSLAM KPM relocalization is linked" from
 * "the packaged standalone wall-tracking runtime is ready". GraffitiXR's current standalone path
 * uses calibrated KPM as the primary per-frame wall pose, CameraX for raw frames, a short IMU
 * rotation bridge across visual misses, and an ARCore-independent wall transform. It is therefore
 * a usable standalone wall backend even though broader world-scale VIO parity remains future work.
 */
enum class TrackingMode {
    /** ARCore owns continuous pose; SphereSLAM contributes relocalization/correction observations. */
    HYBRID_ARCORE_SPHERESLAM,

    /** ARCore remains fully functional when the optional SphereSLAM relocalizer is unavailable. */
    ARCORE_ONLY,

    /** No ARCore Session exists; SphereSLAM supplies the complete primary tracking/anchoring path. */
    SPHERESLAM_STANDALONE,

    /** Neither a usable ARCore path nor a complete standalone SphereSLAM path is available. */
    UNAVAILABLE,
}

/**
 * Capabilities remain split because hybrid relocalization and standalone product readiness are
 * different policy questions. In the current build both depend on KPM linkage, while standalone
 * additionally depends on the CameraX wall-tracking path being shipped/enabled by the caller.
 */
data class TrackingCapabilities(
    val arCoreAvailable: Boolean,
    val sphereSlamRelocalizationAvailable: Boolean,
    val sphereSlamStandaloneAvailable: Boolean,
)

object TrackingModeSelector {
    fun select(capabilities: TrackingCapabilities): TrackingMode = when {
        capabilities.arCoreAvailable && capabilities.sphereSlamRelocalizationAvailable ->
            TrackingMode.HYBRID_ARCORE_SPHERESLAM

        capabilities.arCoreAvailable ->
            TrackingMode.ARCORE_ONLY

        capabilities.sphereSlamStandaloneAvailable ->
            TrackingMode.SPHERESLAM_STANDALONE

        else ->
            TrackingMode.UNAVAILABLE
    }
}

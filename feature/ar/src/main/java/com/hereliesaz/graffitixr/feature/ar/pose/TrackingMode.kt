package com.hereliesaz.graffitixr.feature.ar.pose

/**
 * Runtime tracking choice for GraffitiXR AR.
 *
 * This policy deliberately separates "SphereSLAM KPM relocalization is linked" from
 * "SphereSLAM can run the entire AR experience without ARCore". The latter requires a continuous
 * metric 6-DoF pose source, raw-camera/IMU pipeline, and ARCore-independent wall anchoring.
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
 * Capabilities are intentionally split so a successful KPM native-link probe can never
 * accidentally enable standalone mode before continuous SphereSLAM tracking is actually ready.
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

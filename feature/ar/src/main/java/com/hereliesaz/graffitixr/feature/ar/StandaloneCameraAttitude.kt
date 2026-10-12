package com.hereliesaz.graffitixr.feature.ar

/**
 * One rotation-vector sample's camera-axis heading and elevation, with the roll of the image a given
 * camera-from-device rotation maps the body into — all three from the SAME sample
 * (`CameraAttitudeProvider.latestAttitude`). Near a floor or ceiling the heading is sensor noise on
 * its own and only the set is meaningful; reading them one getter at a time could mix samples.
 */
data class StandaloneCameraAttitude(
    val headingDeg: Float,
    val elevationDeg: Float,
    /** Null when it could not be computed for the requested image frame. */
    val rollDeg: Float?,
)

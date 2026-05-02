package com.hereliesaz.graffitixr.common.sensor

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface SensorSource {
    val cameraFrames: Flow<CameraFrame> get() = emptyFlow()
    val imuSamples: Flow<ImuSample> get() = emptyFlow()
    val cameraIntrinsics: CameraIntrinsics get() = CameraIntrinsics.UNKNOWN
}

package com.hereliesaz.graffitixr.common.sensor

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneSensorSource @Inject constructor() : SensorSource {

    private val _cameraFrames = MutableSharedFlow<CameraFrame>(replay = 0, extraBufferCapacity = 4)
    override val cameraFrames: SharedFlow<CameraFrame> = _cameraFrames.asSharedFlow()

    private val _imuSamples = MutableSharedFlow<ImuSample>(replay = 0, extraBufferCapacity = 64)
    override val imuSamples: SharedFlow<ImuSample> = _imuSamples.asSharedFlow()

    @Volatile
    override var cameraIntrinsics: CameraIntrinsics = CameraIntrinsics.UNKNOWN

    fun pumpFrame(frame: CameraFrame) { _cameraFrames.tryEmit(frame) }
    fun pumpImu(sample: ImuSample) { _imuSamples.tryEmit(sample) }
    fun setIntrinsics(intrinsics: CameraIntrinsics) { cameraIntrinsics = intrinsics }
}

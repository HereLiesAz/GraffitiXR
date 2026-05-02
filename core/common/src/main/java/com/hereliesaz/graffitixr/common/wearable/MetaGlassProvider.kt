package com.hereliesaz.graffitixr.common.wearable

import android.app.Activity
import android.content.Context
import android.util.Log
import com.hereliesaz.graffitixr.common.sensor.CameraFrame
import com.hereliesaz.graffitixr.common.sensor.CameraIntrinsics
import com.hereliesaz.graffitixr.common.sensor.PixelFormat
import com.hereliesaz.graffitixr.common.sensor.YuvLayout
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.Session
import com.meta.wearable.dat.core.types.RegistrationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaGlassProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : SmartGlassProvider {
    override val name: String = "Meta Ray-Ban"

    override val capabilities: Set<GlassCapability> = setOf(
        GlassCapability.CAMERA_FEED,
    )

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Camera frames — Task 4
    private val _cameraFrames = MutableSharedFlow<CameraFrame>(replay = 0, extraBufferCapacity = 4)
    override val cameraFrames: SharedFlow<CameraFrame> = _cameraFrames.asSharedFlow()

    // IMU not exposed by Meta DAT SDK 0.6.0; imuSamples inherits empty.

    // Fallback intrinsics for Meta Ray-Ban Wayfarer (1280×720, estimated focal length)
    override val cameraIntrinsics: CameraIntrinsics = CameraIntrinsics(
        fx = 800f, fy = 800f, cx = 640f, cy = 360f, width = 1280, height = 720,
    )

    private val scope = CoroutineScope(Dispatchers.Main)

    private var activeSession: Session? = null
    private var activeStream: Stream? = null

    private var clockOffsetNs: Long = 0L
    @Volatile private var clockOffsetCaptured: Boolean = false

    private fun normalizeTimestamp(glassesNs: Long): Long {
        if (!clockOffsetCaptured) {
            clockOffsetNs = System.nanoTime() - glassesNs
            clockOffsetCaptured = true
        }
        return glassesNs + clockOffsetNs
    }

    init {
        scope.launch {
            Wearables.registrationState.collect { state ->
                val newState = when (state) {
                    is RegistrationState.Registered -> ConnectionState.Connected
                    is RegistrationState.Available -> ConnectionState.Disconnected
                    is RegistrationState.Unavailable -> {
                        if (state.error != null) ConnectionState.Error(state.error.toString())
                        else ConnectionState.Disconnected
                    }
                    else -> ConnectionState.Connecting
                }
                _connectionState.value = newState

                if (newState is ConnectionState.Connected) {
                    startCameraStream()
                } else if (newState is ConnectionState.Disconnected || newState is ConnectionState.Error) {
                    stopCameraStream()
                }
            }
        }
    }

    override fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    override fun connect() {
        // Registration state is handled by the Meta AI app callback
    }

    override fun disconnect() {
        stopCameraStream()
    }

    private fun startCameraStream() {
        scope.launch {
            try {
                val sessionResult = Wearables.createSession(AutoDeviceSelector())
                val session = sessionResult.getOrNull()
                if (session == null) {
                    Log.w(TAG, "createSession failed: $sessionResult")
                    return@launch
                }
                activeSession = session
                session.start()

                val streamResult = session.addStream(
                    StreamConfiguration(VideoQuality.HIGH, 30, false),
                )
                val stream = streamResult.getOrNull()
                if (stream == null) {
                    Log.w(TAG, "addStream failed: $streamResult")
                    return@launch
                }
                activeStream = stream
                stream.start()

                stream.videoStream.collect { frame ->
                    val timestampNs = normalizeTimestamp(frame.presentationTimeUs * 1000)
                    // Meta SDK 0.6.0 with compressVideo=false emits I420-packed YUV.
                    // Layout confirmation requires on-device byte inspection — adjust
                    // here once verified (e.g. NV12 if uvPixelStride should be 2).
                    val layout = YuvLayout.i420(frame.width, frame.height)
                    _cameraFrames.tryEmit(
                        CameraFrame(
                            pixels = frame.buffer,
                            format = PixelFormat.YUV_420_888,
                            width = frame.width,
                            height = frame.height,
                            timestampNs = timestampNs,
                            yuvLayout = layout,
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera stream error", e)
            }
        }
    }

    private fun stopCameraStream() {
        try {
            activeStream?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing stream", e)
        }
        try {
            activeSession?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping session", e)
        }
        activeStream = null
        activeSession = null
        clockOffsetCaptured = false
    }

    private companion object {
        private const val TAG = "MetaGlassProvider"
    }
}

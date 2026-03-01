package com.hereliesaz.graffitixr.feature.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.GpsData
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Manages spatial state and native bridge coordination for AR features.
 */
@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val projectRepository: ProjectRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    private var arSession: Session? = null

    /** Creates the ARCore session once; returns the existing session on subsequent calls. */
    fun initArSession(): Session? {
        if (arSession != null) return arSession
        return try {
            val s = Session(context)
            val cfg = Config(s)
            if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                cfg.depthMode = Config.DepthMode.AUTOMATIC
            }
            cfg.focusMode = Config.FocusMode.AUTO
            cfg.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            s.configure(cfg)
            arSession = s
            s
        } catch (e: Exception) {
            null
        }
    }

    /** Initialises the ARCore session and assigns it to [renderer]. Safe to call multiple times. */
    fun attachSessionToRenderer(renderer: ArRenderer) {
        renderer.session = initArSession()
    }

    fun resumeArSession() {
        try { arSession?.resume() } catch (_: Exception) {}
    }

    fun pauseArSession() {
        try { arSession?.pause() } catch (_: Exception) {}
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val gpsData = GpsData(
                latitude = location.latitude,
                longitude = location.longitude,
                altitude = location.altitude,
                accuracy = location.accuracy,
                time = location.time
            )
            slamManager.feedLocationData(location.latitude, location.longitude, location.altitude)
            _uiState.update { it.copy(gpsData = gpsData) }
            viewModelScope.launch {
                projectRepository.updateProject { it.copy(gpsData = gpsData) }
            }
        }
        @Deprecated("Kept for API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 2f, locationListener)
        } catch (e: Exception) { /* GPS unavailable */ }
    }

    fun stopLocationUpdates() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.removeUpdates(locationListener)
    }

    override fun onCleared() {
        super.onCleared()
        stopLocationUpdates()
        arSession?.close()
        arSession = null
    }

    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int) {
        slamManager.feedMonocularData(buffer, width, height)
    }

    fun captureKeyframe() {
        val timestamp = System.currentTimeMillis()
        val keyframesDir = java.io.File(context.filesDir, "keyframes").apply { mkdirs() }
        val outputPath = java.io.File(keyframesDir, "keyframe_$timestamp.gxrm").absolutePath
        val success = slamManager.saveKeyframe(timestamp, outputPath)
        if (success) {
            _uiState.update { it.copy(pendingKeyframePath = outputPath) }
        }
    }

    fun toggleFlashlight() {
        val newState = !_uiState.value.isFlashlightOn
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
            cameraManager.setTorchMode(cameraId, newState)
            _uiState.update { it.copy(isFlashlightOn = newState) }
        } catch (e: Exception) {
            // Camera unavailable or torch not supported
        }
    }

    fun initEngine() {
        slamManager.initialize()
    }

    fun startScanning() {
        _uiState.update { it.copy(isScanning = true) }
        startLocationUpdates()
    }

    fun stopScanning() {
        _uiState.update { it.copy(isScanning = false) }
        stopLocationUpdates()
    }

    fun updateTrackingState(state: String, pointCount: Int) {
        _uiState.update { it.copy(trackingState = state, pointCloudCount = pointCount) }
    }

    fun setTempCapture(bitmap: Bitmap) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun setUnwarpPoints(points: List<Offset>) {
        _uiState.update { it.copy(unwarpPoints = points) }
    }
}

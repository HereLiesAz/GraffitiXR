
package com.hereliesaz.graffitixr.feature.ar

import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import javax.inject.Inject
import dagger.hilt.android.lifecycle.HiltViewModel

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    private var renderer: ArRenderer? = null
    var isSessionResumed = false

    fun attachSessionToRenderer(arRenderer: ArRenderer) {
        this.renderer = arRenderer
    }

    fun resumeArSession() {
        isSessionResumed = true
        renderer?.session?.resume()
    }

    fun pauseArSession() {
        isSessionResumed = false
        renderer?.session?.pause()
    }

    fun updateTrackingState(state: com.google.ar.core.TrackingState, count: Int) {
        _uiState.update { it.copy(trackingState = state, pointCount = count) }
    }

    fun feedLocationData(lat: Double, lon: Double, alt: Double, accuracy: Float) {
        slamManager.feedLocationData(lat, lon, alt, accuracy)
    }

    fun feedMonocularData(buffer: ByteBuffer, width: Int, height: Int) {
        slamManager.feedMonocularData(buffer, width, height)
    }

    fun saveKeyframe() {
        slamManager.saveKeyframe()
    }

    fun initialize() {
        slamManager.initialize()
    }

    fun setTempCapture(bitmap: android.graphics.Bitmap) {}
    fun onCaptureConsumed() {}
    fun setUnwarpPoints(points: List<androidx.compose.ui.geometry.Offset>) {}
}

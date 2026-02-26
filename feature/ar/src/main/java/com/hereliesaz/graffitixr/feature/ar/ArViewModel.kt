package com.hereliesaz.graffitixr.feature.ar

import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.feature.ar.computervision.TeleologicalTracker
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import javax.inject.Inject

@HiltViewModel
class ArViewModel @Inject constructor(
    private val slamManager: SlamManager,
    private val stereoDepthProvider: StereoDepthProvider,
    private val teleologicalTracker: TeleologicalTracker,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // You created the native instance in MainActivity, but no one ever turned the key.
        // We initialize the SLAM core here.
        slamManager.initialize()
    }

    fun toggleFlashlight() {
        _uiState.update { it.copy(isFlashlightOn = !it.isFlashlightOn) }
    }

    fun togglePointCloud() {
        _uiState.update { it.copy(showPointCloud = !it.showPointCloud) }
    }

    fun setTempCapture(bitmap: Bitmap?) {
        _uiState.update { it.copy(tempCaptureBitmap = bitmap) }
    }

    fun onFrameCaptured(bitmap: Bitmap, uri: Uri) {
        _uiState.update {
            it.copy(
                tempCaptureBitmap = bitmap,
                pendingKeyframePath = uri.path
            )
        }
    }

    fun onCaptureConsumed() {
        _uiState.update { it.copy(tempCaptureBitmap = null) }
    }

    fun requestCapture() {}
    fun updateUnwarpPoints(points: List<Offset>) {}
    fun setActiveUnwarpPointIndex(index: Int?) {}
    fun setMagnifierPosition(position: Offset?) {}
    fun setMaskPath(path: androidx.compose.ui.graphics.Path?) {}

    fun captureKeyframe() {
        // SlamManager actually has a saveKeyframe method. Let's use it.
        val path = "/data/user/0/com.hereliesaz.graffitixr/cache/keyframe_${System.currentTimeMillis()}.bin"
        if (slamManager.saveKeyframe(path)) {
            _uiState.update { it.copy(pendingKeyframePath = path) }
        }
    }

    fun onKeyframeCaptured() {
        _uiState.update { it.copy(pendingKeyframePath = null) }
    }

    // THE FUEL LINE: We finally feed the SLAM engine.
    fun processTeleologicalFrame(image: Image): org.opencv.core.Mat {
        // The Y-Plane of a YUV_420_888 Image is a native grayscale byte buffer.
        // We pipe it directly into C++ without the OpenCV middleman overhead.
        val yPlane = image.planes[0].buffer
        slamManager.feedMonocularData(yPlane, image.width, image.height)

        // Still return the OpenCV Mat because ArView expects it for legacy reasons.
        return teleologicalTracker.processTeleologicalFrame(image)
    }

    // The legacy fallback for when the view feeds us Bitmaps instead of Images
    fun processTeleologicalFrame(bitmap: Bitmap, vararg params: Any): org.opencv.core.Mat {
        val mat = teleologicalTracker.processTeleologicalFrame(bitmap)

        val size = (mat.total() * mat.channels()).toInt()
        val bytes = ByteArray(size)
        mat.get(0, 0, bytes)

        val directBuffer = ByteBuffer.allocateDirect(size)
        directBuffer.put(bytes)
        directBuffer.position(0)

        slamManager.feedMonocularData(directBuffer, mat.cols(), mat.rows())
        return mat
    }
}
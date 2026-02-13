package com.hereliesaz.graffitixr.feature.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.core.domain.repository.ProjectRepository // IMPORT FROM DOMAIN
import com.hereliesaz.graffitixr.core.native.GraffitiJNI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository
) : ViewModel() {
    // ... (Rest of the class remains the same, as the Interface contract hasn't changed)

    // For completeness of the "Lock" logic:
    fun lockOverlay(bitmap: Bitmap, matrix: Matrix) {
        val project = _currentProject.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true

            val warpedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            val bytes = GraffitiJNI.extractFeaturesFromBitmap(warpedBitmap)
            val meta = GraffitiJNI.extractFeaturesMeta(warpedBitmap)

            if (bytes != null && meta != null) {
                val filename = "target_${System.currentTimeMillis()}.orb"
                projectRepository.saveArtifact(project.id, filename, bytes)
                projectRepository.updateTargetFingerprint(project.id, filename)
                GraffitiJNI.setTargetDescriptors(bytes, meta[0], meta[1], meta[2])
            }

            if (warpedBitmap != bitmap) warpedBitmap.recycle()
            _isProcessing.value = false
        }
    }
}
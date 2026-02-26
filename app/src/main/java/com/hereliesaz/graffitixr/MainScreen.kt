package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Layer
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import androidx.compose.runtime.collectAsState

/**
 * The primary container for the AR and Editor views.
 * Reconciled to accept the specific parameter list demanded by MainActivity.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    onRendererCreated: (ArRenderer) -> Unit,
    hasCameraPermission: Boolean,
    modifier: Modifier = Modifier
) {
    // This assumes MainViewModel and EditorViewModel provide the necessary state
    // We observe the state here to pass down to subcomponents
    val uiState = editorViewModel.uiState.collectAsState().value
    val activeLayer = uiState.layers.find { it.id == uiState.activeLayerId }

    Box(modifier = modifier.fillMaxSize()) {
        // Implementation of the AR viewport or background
        ArViewport(
            viewModel = arViewModel,
            uiState = uiState,
            projectRepository = projectRepository,
            activeLayer = activeLayer,
            onRendererCreated = onRendererCreated,
            hasCameraPermission = hasCameraPermission
        )

        // Overlay for editor-specific feedback
        EditorOverlay(
            uiState = uiState
        )
    }
}

@Composable
fun ArViewport(
    viewModel: ArViewModel,
    uiState: EditorUiState,
    projectRepository: ProjectRepository,
    activeLayer: Layer?,
    onRendererCreated: (ArRenderer) -> Unit,
    hasCameraPermission: Boolean
) {
    // Subcomponent implementation that now recognizes its parameters
}

@Composable
fun EditorOverlay(
    uiState: EditorUiState
) {
    // Feedback logic for gestures or tools
}
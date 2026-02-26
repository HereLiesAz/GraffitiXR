package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager

/**
 * MappingScreen coordinates the AR session UI and the underlying ArView.
 */
@Composable
fun MappingScreen(
    viewModel: ArViewModel,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    hasCameraPermission: Boolean,
    onBackClick: () -> Unit = {},
    onScanComplete: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        ArView(
            viewModel = viewModel,
            uiState = uiState,
            slamManager = slamManager,
            projectRepository = projectRepository,
            activeLayer = null,
            onRendererCreated = { /* Internal JNI state handled in ArView */ },
            hasCameraPermission = hasCameraPermission
        )
    }
}
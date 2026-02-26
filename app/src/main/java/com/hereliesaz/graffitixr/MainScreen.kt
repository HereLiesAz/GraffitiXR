package com.hereliesaz.graffitixr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.navigation.NavHostController
import com.hereliesaz.aznavrail.AzNavHostScope
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager

@Composable
fun MainScreen(
    navHostScope: AzNavHostScope,
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    dashboardViewModel: DashboardViewModel,
    navController: NavHostController,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    renderRefState: MutableState<ArRenderer?>,
    onRendererCreated: (ArRenderer) -> Unit,
    hoistedUse3dBackgroundProvider: () -> Boolean,
    hoistedShowSaveDialogProvider: () -> Boolean,
    hoistedShowInfoScreenProvider: () -> Boolean,
    onUse3dBackgroundChange: (Boolean) -> Unit,
    onShowSaveDialogChange: (Boolean) -> Unit,
    onShowInfoScreenChange: (Boolean) -> Unit,
    hasCameraPermissionProvider: () -> Boolean,
    requestPermissions: () -> Unit,
    onOverlayImagePick: () -> Unit,
    onBackgroundImagePick: () -> Unit,
    dockingSide: AzDockingSide,
    hasCameraPermission: Boolean
) {
    val editorUiState by editorViewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            ArView(
                viewModel = arViewModel,
                uiState = arUiState,
                slamManager = slamManager,
                projectRepository = projectRepository,
                activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId },
                onRendererCreated = onRendererCreated,
                hasCameraPermission = hasCameraPermission
            )
        }
    }
}

@Composable
fun TraceBackground(editorViewModel: EditorViewModel) {
    val editorUiState by editorViewModel.uiState.collectAsState()

    if (editorUiState.editorMode == EditorMode.TRACE) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black), // Black background for lightbox mode
            contentAlignment = Alignment.Center
        ) {
            val bitmap = editorUiState.backgroundBitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Trace Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("Lightbox Mode: Import an image to trace", color = Color.White)
            }
        }
    }
}

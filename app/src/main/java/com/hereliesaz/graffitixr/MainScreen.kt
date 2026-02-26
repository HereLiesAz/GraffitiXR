package com.hereliesaz.graffitixr

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.MockupScreen
import com.hereliesaz.graffitixr.feature.editor.TraceScreen
import com.hereliesaz.graffitixr.nativebridge.SlamManager

/**
 * Renders the primary Viewport content.
 * This should be placed in the 'background' block of AzHostActivityLayout.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    slamManager: SlamManager,
    projectRepository: ProjectRepository,
    onRendererCreated: (ArRenderer) -> Unit,
    hasCameraPermission: Boolean
) {
    val editorUiState by editorViewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        when (editorUiState.editorMode) {
            EditorMode.AR, EditorMode.OVERLAY -> {
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
            EditorMode.STATIC -> {
                MockupScreen(
                    uiState = editorUiState,
                    viewModel = editorViewModel
                )
            }
            EditorMode.TRACE -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
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
                        Text("Lightbox Mode: Import an image to trace", color = Color.Black)
                    }

                    // The actual interactive trace layer
                    TraceScreen(
                        uiState = editorUiState,
                        viewModel = editorViewModel
                    )
                }
            }
            else -> {
                // Default fallback
                Box(Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}
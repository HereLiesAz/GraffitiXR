package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.graffitixr.composables.ArModeScreen
import com.hereliesaz.graffitixr.composables.ImageTraceScreen
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.OnboardingDialog

/**
 * The main screen of the application, serving as the primary UI entry point.
 *
 * This composable acts as a router, observing the `uiState` from the [MainViewModel] and
 * displaying the appropriate editor screen (`StaticImageEditor` or `NonArModeScreen`)
 * based on the current [EditorMode]. It also manages the display of the onboarding dialog
 * for each mode and provides the top-level navigation controls for switching between modes.
 *
 * @param viewModel The central [MainViewModel] instance for the application, which provides
 * the UI state and handles all user events.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Show onboarding dialog if the current mode hasn't been completed yet
    if (!uiState.completedOnboardingModes.contains(uiState.editorMode)) {
        val (title, message) = when (uiState.editorMode) {
            EditorMode.AR_OVERLAY -> "AR Mode" to "This mode uses Augmented Reality to project the image onto a real-world surface. It's the most immersive way to visualize your artwork."
            EditorMode.IMAGE_TRACE -> "Image Trace" to "Trace an image from your camera feed."
            EditorMode.MOCK_UP -> "Mock Up" to "Mock up a mural on a static image."
        }
        OnboardingDialog(
            title = title,
            message = message,
            onDismiss = { viewModel.onOnboardingComplete(uiState.editorMode) }
        )
    }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { viewModel.onEditorModeChanged(EditorMode.MOCK_UP) }) {
                Text("Mock-up")
            }
            Button(onClick = { viewModel.onEditorModeChanged(EditorMode.IMAGE_TRACE) }) {
                Text("Image Trace")
            }
            Button(onClick = { viewModel.onEditorModeChanged(EditorMode.AR_OVERLAY) }) {
                Text("AR")
            }
        }

        when (uiState.editorMode) {
            EditorMode.AR_OVERLAY -> ArModeScreen(
                viewModel = viewModel
            )
            EditorMode.IMAGE_TRACE -> ImageTraceScreen(
                uiState = uiState,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onOpacityChanged = viewModel::onOpacityChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onScaleChanged = viewModel::onScaleChanged,
                onRotationChanged = viewModel::onRotationChanged
            )
            EditorMode.MOCK_UP -> MockupScreen(
                uiState = uiState,
                onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onOpacityChanged = viewModel::onOpacityChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onPointsInitialized = viewModel::onPointsInitialized,
                onPointChanged = viewModel::onPointChanged,
                isWarpEnabled = uiState.isWarpEnabled
            )
        }
    }
}
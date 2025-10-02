package com.hereliesaz.graffitixr.composables

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
import com.hereliesaz.graffitixr.EditorMode
import com.hereliesaz.graffitixr.MainViewModel

/**
 * The main screen of the application.
 * This composable will act as a router to display the correct
 * editor mode based on the current UI state.
 *
 * @param viewModel The [MainViewModel] instance for the application.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Show onboarding dialog if the current mode hasn't been completed yet
    if (!uiState.completedOnboardingModes.contains(uiState.editorMode)) {
        val (title, message) = when (uiState.editorMode) {
            EditorMode.STATIC -> "Mock-up Mode" to "Use this mode to project an image onto a static background. You can warp the image, adjust its properties, and see how it looks."
            EditorMode.NON_AR -> "On-the-Go Mode" to "This mode uses your camera to overlay the image in a real-world environment, without AR tracking. It's great for quick previews."
            EditorMode.AR -> "AR Mode" to "Enter Augmented Reality. Scan your environment, tap on a surface to place markers, and see your image projected in 3D space."
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
            Button(onClick = { viewModel.onEditorModeChanged(EditorMode.STATIC) }) {
                Text("Static")
            }
            Button(onClick = { viewModel.onEditorModeChanged(EditorMode.NON_AR) }) {
                Text("Non-AR")
            }
            Button(onClick = { viewModel.onEditorModeChanged(EditorMode.AR) }) {
                Text("AR")
            }
        }

        when (uiState.editorMode) {
            EditorMode.STATIC -> StaticImageEditor(
                uiState = uiState,
                onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onOpacityChanged = viewModel::onOpacityChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onScaleChanged = viewModel::onScaleChanged,
                onRotationChanged = viewModel::onRotationChanged,
                onPointsInitialized = viewModel::onPointsInitialized,
                onPointChanged = viewModel::onPointChanged
            )
            EditorMode.NON_AR -> NonArModeScreen(
                uiState = uiState,
                onOverlayImageSelected = viewModel::onOverlayImageSelected,
                onOpacityChanged = viewModel::onOpacityChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onScaleChanged = viewModel::onScaleChanged,
                onRotationChanged = viewModel::onRotationChanged
            )
            EditorMode.AR -> ArModeScreen(
                uiState = uiState,
                onArMarkerPlaced = viewModel::onArMarkerPlaced
            )
        }
    }
}
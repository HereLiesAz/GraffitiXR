package com.hereliesaz.graffitixr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.composables.ArModeScreen
import com.hereliesaz.graffitixr.composables.ImageTraceScreen
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.dialogs.AdjustmentSliderDialog

/**
 * The main screen of the application.
 *
 * This composable acts as the primary container for the UI. It observes the [UiState] from the
 * [MainViewModel] and displays the appropriate content based on the current [EditorMode].
 * It also houses the `AzNavRail` for navigation and controls.
 *
 * @param viewModel The [MainViewModel] instance that holds the application's state and business logic.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var showSliderDialog by remember { mutableStateOf<String?>(null) }

    val overlayImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onOverlayImageSelected(it) } }

    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onBackgroundImageSelected(it) } }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when (uiState.editorMode) {
                EditorMode.STATIC -> MockupScreen(
                    uiState = uiState,
                    onBackgroundImageSelected = viewModel::onBackgroundImageSelected,
                    onOverlayImageSelected = viewModel::onOverlayImageSelected,
                    onOpacityChanged = viewModel::onOpacityChanged,
                    onContrastChanged = viewModel::onContrastChanged,
                    onSaturationChanged = viewModel::onSaturationChanged,
                    onScaleChanged = viewModel::onScaleChanged,
                    onOffsetChanged = viewModel::onOffsetChanged,
                    onRotationZChanged = viewModel::onRotationZChanged
                )
                EditorMode.NON_AR -> ImageTraceScreen(
                    uiState = uiState,
                    onScaleChanged = viewModel::onScaleChanged,
                    onOffsetChanged = viewModel::onOffsetChanged,
                    onRotationZChanged = viewModel::onRotationZChanged
                )
                EditorMode.AR -> ArModeScreen(viewModel = viewModel)
            }
        }

        AzNavRail {
            azSettings(isLoading = uiState.isLoading,
                packRailButtons = true
            )


            azMenuItem(id = "ar_overlay", text = "AR Overlay", onClick = { viewModel.onEditorModeChanged(EditorMode.AR) })
            azMenuItem(id = "trace_image", text = "Trace Image", onClick = { viewModel.onEditorModeChanged(EditorMode.NON_AR) })
            azMenuItem(id = "mockup", text = "Mockup", onClick = { viewModel.onEditorModeChanged(EditorMode.STATIC) })

            azRailItem(id = "overlay", text = "Image") {
                overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            if (uiState.editorMode == EditorMode.STATIC) {
                azRailItem(id = "background", text = "Background") {
                    backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            if (uiState.overlayImageUri != null) {
                 azRailItem(id = "remove_bg", text = "Remove\n Background", onClick = viewModel::onRemoveBackgroundClicked)
            }

            azRailItem(id = "opacity", text = "Opacity") { showSliderDialog = "Opacity" }
            azRailItem(id = "contrast", text = "Contrast") { showSliderDialog = "Contrast" }
            azRailItem(id = "saturation", text = "Saturation") { showSliderDialog = "Saturation" }
        }

        when (showSliderDialog) {
            "Opacity" -> AdjustmentSliderDialog(
                title = "Opacity",
                value = uiState.opacity,
                onValueChange = viewModel::onOpacityChanged,
                onDismissRequest = { showSliderDialog = null }
            )
            "Contrast" -> AdjustmentSliderDialog(
                title = "Contrast",
                value = uiState.contrast,
                onValueChange = viewModel::onContrastChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
            "Saturation" -> AdjustmentSliderDialog(
                title = "Saturation",
                value = uiState.saturation,
                onValueChange = viewModel::onSaturationChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
        }
    }
}
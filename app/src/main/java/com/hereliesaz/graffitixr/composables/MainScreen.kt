package com.hereliesaz.graffitixr.composables

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.EditorMode
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.dialogs.AdjustmentSliderDialog

/**
 * The main screen of the application, which hosts the AzNavRail navigation
 * and the content for the currently selected editor mode.
 *
 * @param viewModel The central [MainViewModel] instance for the application.
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
        // The main content area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 80.dp), // Adjust padding to not be obscured by the rail
            contentAlignment = Alignment.Center
        ) {
            when (uiState.editorMode) {
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
                EditorMode.IMAGE_TRACE -> ImageTraceScreen(
                    uiState = uiState,
                    onOverlayImageSelected = viewModel::onOverlayImageSelected,
                    onOpacityChanged = viewModel::onOpacityChanged,
                    onContrastChanged = viewModel::onContrastChanged,
                    onSaturationChanged = viewModel::onSaturationChanged,
                    onScaleChanged = viewModel::onScaleChanged,
                    onRotationChanged = viewModel::onRotationChanged
                )
                EditorMode.AR_OVERLAY -> ArModeScreen(
                    viewModel = viewModel
                )
            }
        }

        // The AzNavRail navigation
        AzNavRail {
            azSettings(isLoading = uiState.isLoading)

            azMenuCycler(
                id = "mode_cycler",
                options = EditorMode.values().map { it.name.replace("_", " ") },
                selectedOption = uiState.editorMode.name.replace("_", " "),
                onClick = {
                    val currentModeIndex = EditorMode.values().indexOf(uiState.editorMode)
                    val nextModeIndex = (currentModeIndex + 1) % EditorMode.values().size
                    viewModel.onEditorModeChanged(EditorMode.values()[nextModeIndex])
                }
            )

            // Dynamic controls based on the current mode
            when (uiState.editorMode) {
                EditorMode.MOCK_UP -> {
                    azRailItem(id = "overlay", text = "Overlay") {
                        overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    azRailItem(id = "background", text = "Background") {
                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    azRailItem(id = "opacity", text = "Opacity") { showSliderDialog = "Opacity" }
                    azRailItem(id = "saturation", text = "Saturation") { showSliderDialog = "Saturation" }
                    azRailItem(id = "contrast", text = "Contrast") { showSliderDialog = "Contrast" }
                    azRailToggle(
                        id = "warp",
                        isChecked = uiState.isWarpEnabled,
                        toggleOnText = "Warp On",
                        toggleOffText = "Warp Off",
                        onClick = viewModel::onWarpToggled
                    )
                    azRailItem(id = "undo", text = "Undo", onClick = viewModel::onUndoMockup)
                    azRailItem(id = "redo", text = "Redo", onClick = viewModel::onRedoMockup)
                    azRailItem(id = "reset", text = "Reset", onClick = viewModel::onResetMockup)
                }
                EditorMode.IMAGE_TRACE -> {
                    azRailItem(id = "image", text = "Image") {
                        overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    azRailItem(id = "opacity", text = "Opacity") { showSliderDialog = "Opacity" }
                    azRailItem(id = "saturation", text = "Saturation") { showSliderDialog = "Saturation" }
                    azRailItem(id = "contrast", text = "Contrast") { showSliderDialog = "Contrast" }
                }
                EditorMode.AR_OVERLAY -> {
                    azRailItem(id = "image", text = "Image") {
                        overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    azRailItem(id = "opacity", text = "Opacity") { showSliderDialog = "Opacity" }
                    azRailToggle(
                        id = "lock",
                        isChecked = uiState.isArLocked,
                        toggleOnText = "Locked",
                        toggleOffText = "Unlocked",
                        onClick = viewModel::onArLockToggled
                    )
                }
            }
        }

        // Show the adjustment slider dialog when needed
        when (showSliderDialog) {
            "Opacity" -> AdjustmentSliderDialog(
                title = "Opacity",
                value = uiState.opacity,
                onValueChange = viewModel::onOpacityChanged,
                onDismissRequest = { showSliderDialog = null }
            )
            "Saturation" -> AdjustmentSliderDialog(
                title = "Saturation",
                value = uiState.saturation,
                onValueChange = viewModel::onSaturationChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
            "Contrast" -> AdjustmentSliderDialog(
                title = "Contrast",
                value = uiState.contrast,
                onValueChange = viewModel::onContrastChanged,
                onDismissRequest = { showSliderDialog = null },
                valueRange = 0f..2f
            )
        }
    }
}
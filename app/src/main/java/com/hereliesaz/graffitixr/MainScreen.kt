package com.hereliesaz.graffitixr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.NavController
import com.hereliesaz.graffitixr.feature.ar.*
import com.hereliesaz.graffitixr.feature.editor.*
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.UiState

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    editorViewModel: EditorViewModel,
    arViewModel: ArViewModel,
    navController: NavController,
    onRendererCreated: (ArRenderer) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()
    val context = LocalContext.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            editorViewModel.addLayer(uri)
        }
    }

    Scaffold(
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                // Point Cloud Toggle (Via ArViewModel)
                SmallFloatingActionButton(
                    onClick = { arViewModel.togglePointCloud() },
                    modifier = Modifier.padding(bottom = 16.dp),
                    containerColor = if (arUiState.showPointCloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                ) {
                    Icon(Icons.Filled.Grain, contentDescription = "Toggle Point Cloud")
                }

                // Flashlight Toggle (Via ArViewModel)
                SmallFloatingActionButton(
                    onClick = { arViewModel.toggleFlashlight() },
                    modifier = Modifier.padding(bottom = 16.dp),
                    containerColor = if (arUiState.isFlashlightOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                ) {
                    Icon(
                        if (arUiState.isFlashlightOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        contentDescription = "Toggle Flashlight"
                    )
                }

                // Add Layer FAB
                FloatingActionButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add Layer")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // AR View (Background)
            ArView(
                viewModel = arViewModel,
                uiState = arUiState,
                onRendererCreated = onRendererCreated
            )

            // Editor Overlay
            // We use a helper to map specific UI states into the legacy common UiState
            // expected by EditorUi until that component is also refactored.
            EditorUi(
                uiState = mapToCommonUiState(uiState, editorUiState, arUiState),
                actions = editorViewModel,
                tapFeedback = uiState.tapFeedback,
                showSliderDialog = editorUiState.sliderDialogType,
                showColorBalanceDialog = editorUiState.showColorBalanceDialog,
                gestureInProgress = editorUiState.gestureInProgress
            )

            // Global Overlays
            TouchLockOverlay(uiState.isTouchLocked, viewModel::showUnlockInstructions)
            UnlockInstructionsPopup(uiState.showUnlockInstructions)

            // Target Creation Overlay
            if (uiState.isCapturingTarget) {
                Box(modifier = Modifier.fillMaxSize().zIndex(20f)) {
                    TargetCreationFlow(uiState, viewModel, context)
                }
            }
        }
    }
}

// Helper to bridge the gap during refactor until EditorUi is fully decoupled
fun mapToCommonUiState(main: UiState, editor: EditorUiState, ar: ArUiState): UiState {
    return main.copy(
        layers = editor.layers,
        activeLayerId = editor.activeLayerId,
        editorMode = editor.editorMode,
        isRightHanded = editor.isRightHanded,
        activeRotationAxis = editor.activeRotationAxis,
        showRotationAxisFeedback = editor.showRotationAxisFeedback,
        showPointCloud = ar.showPointCloud,
        isFlashlightOn = ar.isFlashlightOn,
        isArTargetCreated = ar.isArTargetCreated,
        mappingQualityScore = ar.mappingQualityScore
    )
}
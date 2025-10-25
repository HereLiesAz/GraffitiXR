package com.hereliesaz.graffitixr

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.composables.ImageTraceScreen
import com.hereliesaz.graffitixr.composables.MockupScreen
import com.hereliesaz.graffitixr.composables.ProjectLibraryScreen
import com.hereliesaz.graffitixr.composables.RotationAxisFeedback
import com.hereliesaz.graffitixr.composables.SettingsScreen
import com.hereliesaz.graffitixr.composables.TapFeedbackEffect
import com.hereliesaz.graffitixr.dialogs.DoubleTapHintDialog
import com.hereliesaz.graffitixr.dialogs.OnboardingDialog
import com.hereliesaz.graffitixr.dialogs.SaveProjectDialog
import com.hereliesaz.graffitixr.utils.captureWindow

@Composable
fun MainScreen(viewModel: MainViewModel, arCoreManager: ARCoreManager) {
    val uiState by viewModel.uiState.collectAsState()
    val tapFeedback by viewModel.tapFeedback.collectAsState()
    val context = LocalContext.current
    var showOnboardingForMode by remember { mutableStateOf<EditorMode?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showProjectLibrary by remember { mutableStateOf(false) }
    var showSaveProjectDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.editorMode) {
        if (!uiState.completedOnboardingModes.contains(uiState.editorMode)) {
            showOnboardingForMode = uiState.editorMode
        }
    }

    LaunchedEffect(viewModel, context) {
        viewModel.captureEvent.collect { event ->
            when (event) {
                is CaptureEvent.RequestCapture -> {
                    (context as? Activity)?.let { activity ->
                        captureWindow(activity) { bitmap ->
                            bitmap?.let {
                                viewModel.saveCapturedBitmap(it)
                            }
                        }
                    }
                }
            }
        }
    }

    val overlayImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onOverlayImageSelected(it) } }

    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.onBackgroundImageSelected(it) } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showProjectLibrary) {
            ProjectLibraryScreen(
                projects = viewModel.getProjectList(),
                onLoadProject = { projectName ->
                    viewModel.loadProject(projectName)
                    showProjectLibrary = false
                },
                onDeleteProject = { projectName ->
                    viewModel.deleteProject(projectName)
                },
                onNewProject = {
                    viewModel.onNewProject()
                    showProjectLibrary = false
                }
            )
        }

        if (!showProjectLibrary) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1f),
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
                        onRotationZChanged = viewModel::onRotationZChanged,
                        onRotationXChanged = viewModel::onRotationXChanged,
                        onRotationYChanged = viewModel::onRotationYChanged,
                        onCycleRotationAxis = viewModel::onCycleRotationAxis
                    )
                    EditorMode.NON_AR -> ImageTraceScreen(
                        uiState = uiState,
                        onScaleChanged = viewModel::onScaleChanged,
                        onOffsetChanged = viewModel::onOffsetChanged,
                        onRotationZChanged = viewModel::onRotationZChanged,
                        onRotationXChanged = viewModel::onRotationXChanged,
                        onRotationYChanged = viewModel::onRotationYChanged,
                        onCycleRotationAxis = viewModel::onCycleRotationAxis
                    )
                    EditorMode.AR -> ARScreen(arCoreManager = arCoreManager, overlayImageUri = uiState.overlayImageUri)
                }
            }
        }

        Box(modifier = Modifier.zIndex(2f)) {
            AzNavRail {
                azSettings(isLoading = uiState.isLoading,
                    packRailButtons = true
                )

                azMenuItem(id = "ar", text = "AR Mode", onClick = { viewModel.onEditorModeChanged(EditorMode.AR) })

                azMenuItem(id = "trace_image", text = "Trace", onClick = { viewModel.onEditorModeChanged(EditorMode.NON_AR) })
                azMenuItem(id = "mockup", text = "Mockup", onClick = { viewModel.onEditorModeChanged(EditorMode.STATIC) })

                if (uiState.editorMode == EditorMode.AR) {
                    azRailItem(id = "create_target", text = "Create Target", onClick = viewModel::onCreateTargetClicked)
                }
                azDivider()
                if (uiState.editorMode == EditorMode.STATIC) {
                    azRailItem(id = "background", text = "Background") {
                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
                azRailItem(id = "overlay", text = "Image") {
                    overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }



                if (uiState.overlayImageUri != null) {
                     azRailItem(id = "remove_bg", text = "Remove\n Background", onClick = viewModel::onRemoveBackgroundClicked)
                     azRailItem(id = "line_drawing", text = "Outline", onClick = viewModel::onLineDrawingClicked)
                }

                azDivider()
                azRailItem(id = "blend_mode", text = "Blend Mode", onClick = viewModel::onCycleBlendMode)
                azDivider()
                azRailItem(id = "export", text = "Export", onClick = viewModel::onSaveClicked)
                azRailItem(id = "save_project", text = "Save") {
                    showSaveProjectDialog = true
                }
                azRailItem(id = "project_library", text = "Library") {
                    showProjectLibrary = true
                }
                azRailItem(id = "settings", text = "Settings") {
                    showSettingsDialog = true
                }
            }
        }

        if (showSaveProjectDialog) {
            SaveProjectDialog(
                onDismissRequest = { showSaveProjectDialog = false },
                onSaveRequest = { projectName ->
                    viewModel.saveProject(projectName)
                    showSaveProjectDialog = false
                }
            )
        }

        if (showSettingsDialog) {
            SettingsScreen(
                uiState = uiState,
                onOpacityChanged = viewModel::onOpacityChanged,
                onSaturationChanged = viewModel::onSaturationChanged,
                onContrastChanged = viewModel::onContrastChanged,
                onColorBalanceRChanged = viewModel::onColorBalanceRChanged,
                onColorBalanceGChanged = viewModel::onColorBalanceGChanged,
                onColorBalanceBChanged = viewModel::onColorBalanceBChanged,
                onCurvesPointsChanged = viewModel::onCurvesPointsChanged,
                onCurvesPointsChangeFinished = viewModel::onCurvesPointsChangeFinished,
                onBack = { showSettingsDialog = false }
            )
        }

        showOnboardingForMode?.let { mode ->
            OnboardingDialog(
                editorMode = mode,
                onDismissRequest = { dontShowAgain ->
                    viewModel.onOnboardingComplete(mode, dontShowAgain)
                    showOnboardingForMode = null
                }
            )
        }

        RotationAxisFeedback(
            axis = uiState.activeRotationAxis,
            visible = uiState.showRotationAxisFeedback,
            onFeedbackShown = viewModel::onFeedbackShown,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )

        TapFeedbackEffect(feedback = tapFeedback)

        if (uiState.showDoubleTapHint) {
            DoubleTapHintDialog(onDismissRequest = viewModel::onDoubleTapHintDismissed)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 82.dp)
        ) {
            IconButton(
                onClick = viewModel::onUndoClicked,
                enabled = uiState.canUndo
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
            }
            Spacer(modifier = Modifier.width(16.dp))
            IconButton(
                onClick = viewModel::onRedoClicked,
                enabled = uiState.canRedo
            ) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
            }
        }
    }
}

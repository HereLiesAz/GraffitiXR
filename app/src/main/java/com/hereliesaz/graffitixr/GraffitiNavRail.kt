package com.hereliesaz.graffitixr

import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel

/**
 * The immutable definition of the GraffitiXR Navigation Rail.
 * Extracted to preserve the exact UI/UX behavior while cleaning up MainScreen.
 */
fun AzNavHostScope.GraffitiNavRail(
    navStrings: NavStrings,
    editorUiState: EditorUiState,
    editorViewModel: EditorViewModel,
    viewModel: MainViewModel,
    arViewModel: ArViewModel,
    navController: NavController,
    hasCameraPermission: Boolean,
    requestPermissions: () -> Unit,
    performHaptic: () -> Unit,
    resetDialogs: () -> Unit,
    backgroundImagePicker: ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
    overlayImagePicker: ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
    has3dModel: Boolean,
    use3dBackground: Boolean,
    onToggle3dBackground: () -> Unit,
    onShowInfoScreen: () -> Unit,
    onSaveProject: () -> Unit
) {
    val activeHighlightColor = when (editorUiState.activeRotationAxis) {
        com.hereliesaz.graffitixr.common.model.RotationAxis.X -> Color.Red
        com.hereliesaz.graffitixr.common.model.RotationAxis.Y -> Color.Green
        com.hereliesaz.graffitixr.common.model.RotationAxis.Z -> Color.Blue
    }

    azTheme(
        activeColor = activeHighlightColor,
        defaultShape = AzButtonShape.RECTANGLE,
        headerIconShape = AzHeaderIconShape.ROUNDED
    )
    azConfig(
        packButtons = true,
        dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
    )

    azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
    azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, info = navStrings.arModeInfo, onClick = {
        performHaptic()
        if (hasCameraPermission) {
            editorViewModel.setEditorMode(EditorMode.AR)
        } else {
            requestPermissions()
        }
    })
    azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = {
        performHaptic()
        if (hasCameraPermission) {
            editorViewModel.setEditorMode(EditorMode.OVERLAY)
        } else {
            requestPermissions()
        }
    })
    azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = {
        performHaptic()
        editorViewModel.setEditorMode(EditorMode.STATIC)
    })
    azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = {
        performHaptic()
        editorViewModel.setEditorMode(EditorMode.TRACE)
    })

    azDivider()

    if (editorUiState.editorMode == EditorMode.AR) {
        azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
        azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, info = navStrings.createInfo, onClick = {
            performHaptic()
            if (hasCameraPermission) {
                viewModel.startTargetCapture()
                resetDialogs()
            } else {
                requestPermissions()
            }
        })
        azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo, onClick = {
            performHaptic()
            if (hasCameraPermission) {
                navController.navigate("surveyor")
                resetDialogs()
            } else {
                requestPermissions()
            }
        })
        azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe", info = "Save for reconstruction", onClick = {
            performHaptic()
            arViewModel.captureKeyframe()
        })
        azDivider()
    }

    azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})

    if (editorUiState.editorMode == EditorMode.STATIC) {
        azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, info = navStrings.wallInfo) {
            performHaptic()
            resetDialogs()
            backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        if (has3dModel) {
            azRailSubToggle(
                id = "toggle_3d",
                hostId = "design_host",
                isChecked = use3dBackground,
                toggleOnText = "3D View",
                toggleOffText = "2D View",
                info = "Switch Mockup",
                onClick = {
                    performHaptic()
                    onToggle3dBackground()
                }
            )
        }
    }

    val openButtonText = if (editorUiState.layers.isNotEmpty()) "Add" else navStrings.open
    val openButtonId = if (editorUiState.layers.isNotEmpty()) "add_layer" else "image"
    azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) {
        performHaptic()
        resetDialogs()
        overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    editorUiState.layers.reversed().forEach { layer ->
        azRailRelocItem(
            id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
            onClick = {
                performHaptic()
                if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id)
            },
            onRelocate = { _, _, newOrder -> editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed()) }
        ) {
            inputItem(hint = "Rename") { editorViewModel.onLayerRenamed(layer.id, it) }
            listItem(text = "Remove") { editorViewModel.onLayerRemoved(layer.id) }
        }
    }

    if (editorUiState.layers.isNotEmpty()) {
        azRailSubItem(id = "isolate", hostId = "design_host", text = navStrings.isolate, info = navStrings.isolateInfo, onClick = {
            performHaptic()
            editorViewModel.onRemoveBackgroundClicked()
            resetDialogs()
        })
        azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = {
            performHaptic()
            editorViewModel.onLineDrawingClicked()
            resetDialogs()
        })
        azDivider()
        azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) {
            performHaptic()
            editorViewModel.onAdjustClicked()
            resetDialogs()
        }
        azRailSubItem(id = "balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) {
            performHaptic()
            editorViewModel.onColorClicked()
            resetDialogs()
        }
        azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = {
            performHaptic()
            editorViewModel.onCycleBlendMode()
            resetDialogs()
        })
        azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = editorUiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = {
            performHaptic()
            editorViewModel.toggleImageLock()
        })
    }
    azDivider()
    azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
    azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) {
        performHaptic()
        onSaveProject()
        resetDialogs()
    }
    azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) {
        performHaptic()
        navController.navigate("project_library")
        resetDialogs()
    }
    azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo) {
        performHaptic()
        editorViewModel.exportProject()
        resetDialogs()
    }
    azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") {
        performHaptic()
        navController.navigate("settings")
        resetDialogs()
    }
    azDivider()

    azRailItem(id = "help", text = "Help", info = "Show Help") {
        performHaptic()
        onShowInfoScreen()
        resetDialogs()
    }
    if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = {
        performHaptic()
        arViewModel.toggleFlashlight()
        resetDialogs()
    })
    if (editorUiState.editorMode == EditorMode.TRACE) azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = {
        performHaptic()
        viewModel.setTouchLocked(true)
        resetDialogs()
    })
}
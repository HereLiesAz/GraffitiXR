package com.hereliesaz.graffitixr

import android.os.Bundle
import com.hereliesaz.aznavrail.AzActivity
import com.hereliesaz.aznavrail.annotation.App
import com.hereliesaz.aznavrail.annotation.Az
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The Single Activity for the application.
 * Extends AzActivity to use the generated AzNavRail graph.
 */
@AndroidEntryPoint
@Az(app = App())
class MainActivity : AzActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository

    override val graph = AzGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // RESURRECTION: Ensure native engine is alive
        slamManager.ensureInitialized()

        // Observe ViewModels
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                editorViewModel.uiState.collect { editorUiState = it }
            }
        }

        setContent {
            val navController = rememberNavController()
            val currentEditorUiState = editorUiState // Capture local value for stability
            val currentProjectName by editorViewModel.currentProjectName.collectAsStateWithLifecycle()
            val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()

            AzHostActivityLayout(
                navController = navController,
            ) {
                // --- RAIL CONFIGURATION ---
                val activeHighlightColor = when (currentEditorUiState.activeRotationAxis) {
                    com.hereliesaz.graffitixr.common.model.RotationAxis.X -> Color.Red
                    com.hereliesaz.graffitixr.common.model.RotationAxis.Y -> Color.Green
                    com.hereliesaz.graffitixr.common.model.RotationAxis.Z -> Color.Blue
                }


                azConfig(
                    packButtons = true,
                    dockingSide = if (currentEditorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
                )

                // MODES HOST
                azRailHostItem(id = "mode_host", text = "Modes", onClick = {})
                azRailSubItem(id = "ar", hostId = "mode_host", text = "AR", info = "Augmented Reality", route = "ar")
                azRailSubItem(id = "overlay", hostId = "mode_host", text = "Overlay", info = "2D Overlay", route = "overlay")
                azRailSubItem(id = "mockup", hostId = "mode_host", text = "Mockup", info = "Static Image", route = "mockup")
                azRailSubItem(id = "trace", hostId = "mode_host", text = "Trace", info = "Trace Lines", route = "trace")

                azDivider()

                // TARGET / GRID HOST (Only in AR)
                if (currentEditorUiState.editorMode == EditorMode.AR) {
                    azRailHostItem(id = "target_host", text = "Grid", onClick = {})
                    azRailSubItem(id = "create", hostId = "target_host", text = "Create", info = "New Target", route = "create")
                    azRailSubItem(id = "surveyor", hostId = "target_host", text = "Surveyor", info = "Map Environment", route = "surveyor")
                    azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe", info = "Save for reconstruction") {
                        arViewModel.captureKeyframe()
                    }
                    azDivider()
                }

                // DESIGN HOST
                azRailHostItem(id = "design_host", text = "Design", onClick = {})

                if (currentEditorUiState.editorMode == EditorMode.STATIC) {
                    azRailSubItem(id = "wall", hostId = "design_host", text = "Wall", info = "Background Image") {
                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }

                val openButtonText = if (currentEditorUiState.layers.isNotEmpty()) "Add" else "Open"
                val openButtonId = if (currentEditorUiState.layers.isNotEmpty()) "add_layer" else "image"

                azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = "Import Image") {
                    overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }

                // Dynamic Layers
                currentEditorUiState.layers.reversed().forEach { layer ->
                    azRailRelocItem(
                        id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
                        onClick = {
                            if (currentEditorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id)
                        },
                        onRelocate = { _, _, newOrder -> editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed()) }
                    ) {
                        inputItem(hint = "Rename") { editorViewModel.onLayerRenamed(layer.id, it) }
                        listItem(text = "Remove") { editorViewModel.onLayerRemoved(layer.id) }
                    }
                }

                if (currentEditorUiState.layers.isNotEmpty()) {
                    azRailSubItem(id = "isolate", hostId = "design_host", text = "Isolate", info = "Remove Background", onClick = {
                        editorViewModel.onRemoveBackgroundClicked()
                    })
                    azRailSubItem(id = "outline", hostId = "design_host", text = "Outline", info = "Edge Detection", onClick = {
                        editorViewModel.onLineDrawingClicked()
                    })
                    azDivider()
                    azRailSubItem(id = "adjust", hostId = "design_host", text = "Adjust", info = "Transform Layer") {
                        editorViewModel.onAdjustClicked()
                    }
                    azRailSubItem(id = "balance", hostId = "design_host", text = "Balance", info = "Color Correction") {
                        editorViewModel.onColorClicked()
                    }
                    azRailSubItem(id = "blending", hostId = "design_host", text = "Blend", info = "Blend Mode", onClick = {
                        editorViewModel.onCycleBlendMode()
                    })
                    azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = currentEditorUiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = {
                        editorViewModel.toggleImageLock()
                    })
                }
                azDivider()

                // PROJECT HOST
                azRailHostItem(id = "project_host", text = "Project", onClick = {})
                azRailSubItem(id = "save_project", hostId = "project_host", text = "Save", info = "Save Project") {
                    showSaveDialog = true
                }
                azRailSubItem(id = "load_project", hostId = "project_host", text = "Load", info = "Load Project", route = "project_library")

                azRailSubItem(id = "export_project", hostId = "project_host", text = "Export", info = "Export Image") {
                    editorViewModel.exportProject()
                }
                azRailSubItem(id = "settings_sub", hostId = "project_host", text = "Settings", info = "App Settings", route = "settings")
                azDivider()

                azRailItem(id = "help", text = "Help", info = "Show Help", route = "help")

                if (currentEditorUiState.editorMode == EditorMode.AR || currentEditorUiState.editorMode == EditorMode.OVERLAY) {
                    azRailItem(id = "light", text = "Light", info = "Toggle Flashlight") {
                        arViewModel.toggleFlashlight()
                    }
                }

                if (currentEditorUiState.editorMode == EditorMode.TRACE) {
                    azRailItem(id = "lock_trace", text = "Lock", info = "Lock Touch") {
                        mainViewModel.setTouchLocked(true)
                    }
                }

                // --- CONTENT GRAPH ---
                onscreen {
                  AzNavHost(startDestination = "ar") {
                    composable("help") { HelpScreen() }
                    composable("ar") { ArScreen() }
                    composable("overlay") { OverlayScreen() }
                    composable("mockup") { MockupScreen() }
                    composable("trace") { TraceScreen() }
                    composable("create") { CreateScreen() }
                    composable("surveyor") { SurveyorScreen() }
                    composable("project_library") { ProjectLibraryWrapper(navController) }
                    composable("settings") { SettingsWrapper(navController) }
                  }

                  // Overlays
                  com.hereliesaz.graffitixr.design.components.TouchLockOverlay(
                      isLocked = mainUiState.isTouchLocked,
                      onUnlockRequested = { mainViewModel.setTouchLocked(false) }
                  )

                  if (showSaveDialog) {
                      SaveProjectDialog(
                          initialName = currentProjectName,
                          onDismissRequest = { showSaveDialog = false },
                          onSaveRequest = { name ->
                              editorViewModel.saveProject(name)
                              showSaveDialog = false
                          }
                      )
                  }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            slamManager.destroy()
        }
    }
}

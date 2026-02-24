package com.hereliesaz.graffitixr

import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.AzActivity
import com.hereliesaz.aznavrail.AzGraphInterface
import com.hereliesaz.aznavrail.AzNavRailScope
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The Single Activity for the application.
 * Sets up the Compose content, Hilt injection, and the top-level Navigation Graph via AzNavRail.
 */
@AndroidEntryPoint
class MainActivity : AzActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository

    // Override graph to point to the manually implemented AzGraph.
    override val graph: AzGraphInterface = AzGraph

    internal val mainViewModel: MainViewModel by viewModels()
    internal val editorViewModel: EditorViewModel by viewModels()
    internal val arViewModel: ArViewModel by viewModels()
    internal val dashboardViewModel: DashboardViewModel by viewModels()

    private var arRenderer: ArRenderer? = null

    // Hoisted state for Rail and Screen
    var use3dBackground by mutableStateOf(false)
    var showSaveDialog by mutableStateOf(false)
    var showInfoScreen by mutableStateOf(false)

    // Permissions and Launchers
    var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasCameraPermission = permissions[android.Manifest.permission.CAMERA] ?: false
    }

    private val overlayImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.onAddLayer(it) }
    }

    private val backgroundImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check initial permission
        hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        // RESURRECTION: Ensure native engine is alive even if the Process survived
        // but the Activity was previously destroyed.
        slamManager.ensureInitialized()

        // AzActivity calls graph.Run(this) which sets up the layout.
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            slamManager.destroy()
        }
    }

    // Dynamic Rail Configuration
    override fun AzNavRailScope.configureRail() {
        val editorUiState = editorViewModel.uiState.value
        val isRightHanded = editorUiState.isRightHanded
        val viewModel = mainViewModel // Access member

        // Theme and Config
        val activeHighlightColor = when (editorUiState.activeRotationAxis) {
            RotationAxis.X -> Color.Red
            RotationAxis.Y -> Color.Green
            RotationAxis.Z -> Color.Blue
        }

        azTheme(
            activeColor = activeHighlightColor,
            defaultShape = AzButtonShape.RECTANGLE,
            headerIconShape = AzHeaderIconShape.ROUNDED
        )
        azConfig(
            packButtons = true,
            dockingSide = if (isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
        )

        // Navigation Strings
        val navStrings = NavStrings(
            modes = "Modes", arMode = "AR", arModeInfo = "AR Projection",
            overlay = "Overlay", overlayInfo = "Overlay Mode",
            mockup = "Mockup", mockupInfo = "Mockup Mode",
            trace = "Trace", traceInfo = "Trace Mode",
            grid = "Target", surveyor = "Survey", surveyorInfo = "Map Wall",
            create = "Create", createInfo = "New Target",
            refine = "Refine", refineInfo = "Adjust Target",
            update = "Progress", updateInfo = "Mark Work",
            design = "Design", open = "Open", openInfo = "Add Image",
            wall = "Wall", wallInfo = "Change Wall",
            isolate = "Isolate", isolateInfo = "Remove BG",
            outline = "Outline", outlineInfo = "Line Art",
            adjust = "Adjust", adjustInfo = "Colors",
            balance = "Color", balanceInfo = "Color Tint",
            build = "Blend", blendingInfo = "Blend Mode",
            settings = "Settings", project = "Project",
            new = "New", newInfo = "Clear Canvas",
            save = "Save", saveInfo = "Save to File",
            load = "Load", loadInfo = "Open Project",
            export = "Export", exportInfo = "Export Image",
            help = "Help", helpInfo = "Guide",
            light = "Light", lightInfo = "Flashlight",
            lock = "Lock", lockInfo = "Touch Lock"
        )

        val requestPermissions = {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        val resetDialogs = {
            // Reset dialogs if managed here, but MainScreen manages most dialogs locally (slider, color).
            // We can only reset what we lifted.
        }

        // --- Rail Items ---

        azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, info = navStrings.arModeInfo, onClick = {
            if (hasCameraPermission) {
                editorViewModel.setEditorMode(EditorMode.AR)
            } else {
                requestPermissions()
            }
        })
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, info = navStrings.overlayInfo, onClick = {
            if (hasCameraPermission) {
                editorViewModel.setEditorMode(EditorMode.OVERLAY)
            } else {
                requestPermissions()
            }
        })
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, info = navStrings.mockupInfo, onClick = {
            editorViewModel.setEditorMode(EditorMode.STATIC)
        })
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, info = navStrings.traceInfo, onClick = {
            editorViewModel.setEditorMode(EditorMode.TRACE)
        })

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, info = navStrings.createInfo, onClick = {
                if (hasCameraPermission) {
                    viewModel.startTargetCapture()
                    resetDialogs()
                } else {
                    requestPermissions()
                }
            })

            azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = navStrings.surveyorInfo, onClick = {
                if (hasCameraPermission) {
                    dashboardViewModel.navigateToSurveyor()
                    resetDialogs()
                } else {
                    requestPermissions()
                }
            })
            azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe", info = "Save for reconstruction", onClick = {
                arViewModel.captureKeyframe()
            })
            azDivider()
        }

        azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})

        if (editorUiState.editorMode == EditorMode.STATIC) {
            azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, info = navStrings.wallInfo) {
                resetDialogs()
                backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            // has3dModel check
            val has3dModel = !editorUiState.mapPath.isNullOrEmpty()
            if (has3dModel) {
                azRailSubToggle(
                    id = "toggle_3d",
                    hostId = "design_host",
                    isChecked = use3dBackground,
                    toggleOnText = "3D View",
                    toggleOffText = "2D View",
                    info = "Switch Mockup",
                    onClick = {
                        use3dBackground = !use3dBackground
                    }
                )
            }
        }

        val openButtonText = if (editorUiState.layers.isNotEmpty()) "Add" else navStrings.open
        val openButtonId = if (editorUiState.layers.isNotEmpty()) "add_layer" else "image"
        azRailSubItem(id = openButtonId, text = openButtonText, hostId = "design_host", info = navStrings.openInfo) {
            resetDialogs()
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        editorUiState.layers.reversed().forEach { layer ->
            azRailRelocItem(
                id = "layer_${layer.id}", hostId = "design_host", text = layer.name,
                onClick = {
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
                editorViewModel.onRemoveBackgroundClicked()
                resetDialogs()
            })
            azRailSubItem(id = "outline", hostId = "design_host", text = navStrings.outline, info = navStrings.outlineInfo, onClick = {
                editorViewModel.onLineDrawingClicked()
                resetDialogs()
            })
            azDivider()
            azRailSubItem(id = "adjust", hostId = "design_host", text = navStrings.adjust, info = navStrings.adjustInfo) {
                editorViewModel.onAdjustClicked()
                resetDialogs()
            }
            azRailSubItem(id = "balance", hostId = "design_host", text = navStrings.balance, info = navStrings.balanceInfo) {
                editorViewModel.onColorClicked()
                resetDialogs()
            }
            azRailSubItem(id = "blending", hostId = "design_host", text = navStrings.build, info = navStrings.blendingInfo, onClick = {
                editorViewModel.onCycleBlendMode()
                resetDialogs()
            })
            azRailSubToggle(id = "lock_image", hostId = "design_host", isChecked = editorUiState.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Prevent accidental moves", onClick = {
                editorViewModel.toggleImageLock()
            })
        }
        azDivider()
        azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
        azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = navStrings.saveInfo) {
            showSaveDialog = true
            resetDialogs()
        }
        azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = navStrings.loadInfo) {
            dashboardViewModel.navigateToLibrary() // Need to implement
            resetDialogs()
        }
        azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = navStrings.exportInfo) {
            editorViewModel.exportProject()
            resetDialogs()
        }
        azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "App Settings") {
            dashboardViewModel.navigateToSettings() // Need to implement
            resetDialogs()
        }
        azDivider()

        azRailItem(id = "help", text = "Help", info = "Show Help") {
            showInfoScreen = true
            resetDialogs()
        }
        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) azRailItem(id = "light", text = navStrings.light, info = navStrings.lightInfo, onClick = {
            arViewModel.toggleFlashlight()
            resetDialogs()
        })
        if (editorUiState.editorMode == EditorMode.TRACE) azRailItem(id = "lock_trace", text = navStrings.lock, info = navStrings.lockInfo, onClick = {
            mainViewModel.setTouchLocked(true)
            resetDialogs()
        })
    }

    @Composable
    fun AppContent(navController: NavHostController) {
        GraffitiXRTheme {
            val navTrigger by dashboardViewModel.navigationTrigger.collectAsState()

            LaunchedEffect(navTrigger) {
                navTrigger?.let { dest ->
                    navController.navigate(dest)
                    dashboardViewModel.onNavigationConsumed()
                }
            }

            MainScreen(
                viewModel = mainViewModel,
                editorViewModel = editorViewModel,
                arViewModel = arViewModel,
                dashboardViewModel = dashboardViewModel,
                navController = navController,
                slamManager = slamManager,
                projectRepository = projectRepository,
                onRendererCreated = { renderer ->
                    arRenderer = renderer
                },
                // Pass hoisted state
                hoistedUse3dBackground = use3dBackground,
                hoistedShowSaveDialog = showSaveDialog,
                hoistedShowInfoScreen = showInfoScreen,
                onUse3dBackgroundChange = { use3dBackground = it },
                onShowSaveDialogChange = { showSaveDialog = it },
                onShowInfoScreenChange = { showInfoScreen = it },
                hasCameraPermission = hasCameraPermission,
                requestPermissions = { permissionLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )},
                onOverlayImagePick = { overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onBackgroundImagePick = { backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            )
        }
    }
}

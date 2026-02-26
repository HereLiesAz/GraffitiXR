package com.hereliesaz.graffitixr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHostScope
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.aznavrail.model.AzNestedRailAlignment
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.RotationAxis
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingActivity
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.security.SecurityProviderState
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository
    @Inject lateinit var securityProviderManager: SecurityProviderManager

    var use3dBackground by mutableStateOf(false)
    var showSaveDialog by mutableStateOf(false)
    var showInfoScreen by mutableStateOf(false)
    var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[android.Manifest.permission.CAMERA] ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        slamManager.ensureInitialized()

        lifecycleScope.launch {
            securityProviderManager.securityProviderState.collect { state ->
                if (state is SecurityProviderState.RecoverableError) {
                    GoogleApiAvailability.getInstance().getErrorDialog(
                        this@MainActivity,
                        state.errorCode,
                        9000
                    )?.show()
                }
            }
        }

        setContent {
            GraffitiXRTheme {
                val navController = rememberNavController()
                val renderRefState = remember { mutableStateOf<ArRenderer?>(null) }

                val mainViewModel: MainViewModel = hiltViewModel()
                val editorViewModel: EditorViewModel = hiltViewModel()
                val arViewModel: ArViewModel = hiltViewModel()
                val dashboardViewModel: DashboardViewModel = hiltViewModel()

                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()

                // 1. Dashboard Navigation Observer
                LaunchedEffect(dashboardNavigation) {
                    dashboardNavigation?.let { destination ->
                        when (destination) {
                            "surveyor" -> {
                                val intent = Intent(this@MainActivity, MappingActivity::class.java)
                                startActivity(intent)
                            }
                            "project_library" -> {
                                // Just a modal/overlay in this single-activity architecture for now
                                // or potentially a navigation destination if we expanded the graph
                            }
                            "settings" -> { }
                        }
                        dashboardViewModel.onNavigationConsumed()
                    }
                }

                // 2. Rail <-> ViewModel Synchronization
                // When the Rail navigates (changes route), update the ViewModel.
                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        val route = entry.destination.route
                        if (route != null) {
                            try {
                                val mode = EditorMode.valueOf(route)
                                editorViewModel.setEditorMode(mode)
                            } catch (e: IllegalArgumentException) {
                                // Ignore routes that aren't modes (if any)
                            }
                        }
                    }
                }

                val isRailVisible = !editorUiState.hideUiForCapture && !mainUiState.isTouchLocked
                val dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT

                val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { editorViewModel.onAddLayer(it) }
                }

                val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { editorViewModel.setBackgroundImage(it) }
                }

                AzHostActivityLayout(
                    navController = navController,
                    initiallyExpanded = false,
                ) {
                    if (isRailVisible) {
                        configureRail(mainViewModel, editorViewModel, arViewModel, dashboardViewModel, overlayImagePicker, backgroundImagePicker)
                    }

                    onscreen {
                        AppContent(
                            navHostScope = this@AzHostActivityLayout,
                            navController = navController,
                            mainViewModel = mainViewModel,
                            editorViewModel = editorViewModel,
                            arViewModel = arViewModel,
                            dashboardViewModel = dashboardViewModel,
                            dockingSide = dockingSide,
                            renderRefState = renderRefState,
                            overlayImagePicker = overlayImagePicker,
                            backgroundImagePicker = backgroundImagePicker
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) slamManager.destroy()
    }

    private fun AzNavHostScope.configureRail(
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel,
        overlayImagePicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        backgroundImagePicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>
    ) {
        val editorUiState = editorViewModel.uiState.value
        val isRightHanded = editorUiState.isRightHanded
        val navStrings = NavStrings()

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
        azConfig(packButtons = true, dockingSide = if (isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)
        azAdvanced(infoScreen = showInfoScreen, onDismissInfoScreen = { showInfoScreen = false })

        val requestPermissions = { permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_FINE_LOCATION)) }

        // --- CORE MODES ---
        // Added 'route' parameters. AzNavRail uses these to highlight the active item.
        // We removed the 'onClick' handlers because the route change triggers the LaunchedEffect in MainActivity.

        azRailHostItem(id = "mode_host", text = navStrings.modes, info = "Switch editor modes")

        azRailSubItem(
            id = "ar",
            hostId = "mode_host",
            text = navStrings.arMode,
            info = "Augmented Reality",
            shape = AzButtonShape.NONE,
            route = EditorMode.AR.name
        )

        azRailSubItem(
            id = "overlay",
            hostId = "mode_host",
            text = navStrings.overlay,
            info = "AR Overlay",
            shape = AzButtonShape.NONE,
            route = EditorMode.OVERLAY.name
        )

        azRailSubItem(
            id = "mockup",
            hostId = "mode_host",
            text = navStrings.mockup,
            info = "Static Image",
            shape = AzButtonShape.NONE,
            route = EditorMode.STATIC.name
        )

        azRailSubItem(
            id = "trace",
            hostId = "mode_host",
            text = navStrings.trace,
            info = "Trace Mode",
            shape = AzButtonShape.NONE,
            route = EditorMode.TRACE.name
        )

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            azRailHostItem(id = "target_host", text = navStrings.grid, info = "Scanning tools")
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, info = "Create Target", shape = AzButtonShape.NONE) { if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions() }
            azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, info = "Surveyor", shape = AzButtonShape.NONE) { if (hasCameraPermission) dashboardViewModel.navigateToSurveyor() else requestPermissions() }
            azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe", info = "Capture Keyframe", shape = AzButtonShape.NONE) { arViewModel.captureKeyframe() }
            azDivider()
        }

        azRailHostItem(id = "design_host", text = navStrings.design, info = "Design tools")
        azRailSubItem(id = "add_image", hostId = "design_host", text = "Image", info = navStrings.openInfo, shape = AzButtonShape.NONE) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        azRailSubItem(id = "add_draw", hostId = "design_host", text = "Draw", info = "New Blank Sketch", shape = AzButtonShape.NONE) {
            editorViewModel.onAddBlankLayer()
        }

        if (editorUiState.editorMode == EditorMode.STATIC) {
            azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, info = "Set Wall Image", shape = AzButtonShape.NONE) {
                backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (!editorUiState.mapPath.isNullOrEmpty()) {
                azRailSubItem(
                    id = "toggle_3d",
                    hostId = "design_host",
                    text = if (use3dBackground) "3D View" else "2D View",
                    info = "Toggle 3D/2D",
                    shape = AzButtonShape.NONE
                ) {
                    use3dBackground = !use3dBackground
                }
            }
        }
        azDivider()

        // --- DYNAMIC LAYERS (Nested Rail Parents) ---
        editorUiState.layers.reversed().forEach { layer ->
            azRailRelocItem(
                id = "layer_${layer.id}",
                hostId = "design_host",
                text = layer.name,
                info = "Open Layer Tools",
                nestedRailAlignment = AzNestedRailAlignment.HORIZONTAL,
                onClick = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) },
                onRelocate = { _, _, newOrder ->
                    editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed())
                },
                nestedContent = {
                    val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }

                    if (layer.isSketch) {
                        azRailItem(id = "brush_${layer.id}", text = "Brush", info = "Paint Brush") { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                        azRailItem(id = "eraser_${layer.id}", text = "Eraser", info = "Erase") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                        azRailItem(id = "blur_${layer.id}", text = "Blur", info = "Blur tool") { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                        azRailItem(id = "heal_${layer.id}", text = "Heal", info = "Healing tool") { activate(); editorViewModel.setActiveTool(Tool.HEAL) }
                        azRailItem(id = "burn_${layer.id}", text = "Burn", info = "Burn tool") { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                        azRailItem(id = "dodge_${layer.id}", text = "Dodge", info = "Dodge tool") { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                        azRailItem(id = "liquify_${layer.id}", text = "Liquify", info = "Liquify tool") { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }

                        azDivider()
                        azRailItem(id = "flip_h_sketch_${layer.id}", text = "Flip Horiz", info = "Flip Horizontal") { activate(); editorViewModel.onFlipLayer(true) }
                        azRailItem(id = "flip_v_sketch_${layer.id}", text = "Flip Vert", info = "Flip Vertical") { activate(); editorViewModel.onFlipLayer(false) }
                        azDivider()

                        val hexColor = "#%06X".format(0xFFFFFF and editorUiState.activeColor.toArgb())
                        azRailItem(id = "color_${layer.id}", text = hexColor, info = "Color Picker") { activate(); editorViewModel.setShowColorPicker(true) }
                        azRailItem(id = "size_${layer.id}", text = "Size: ${editorUiState.brushSize.toInt()}", info = "Brush Size") { activate(); editorViewModel.setShowSizePicker(true) }
                    } else {
                        azRailItem(id = "isolate_${layer.id}", text = "Isolate", info = "Remove Background") { activate(); editorViewModel.onRemoveBackgroundClicked() }
                        azRailItem(id = "outline_${layer.id}", text = "Outline", info = "Detect Edges") { activate(); editorViewModel.onLineDrawingClicked() }
                        azRailItem(id = "liquify_img_${layer.id}", text = "Liquify", info = "Liquify tool") { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                        azRailItem(id = "eraser_img_${layer.id}", text = "Eraser", info = "Erase") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                        azRailItem(id = "heal_img_${layer.id}", text = "Heal", info = "Healing tool") { activate(); editorViewModel.setActiveTool(Tool.HEAL) }
                        azRailItem(id = "burn_img_${layer.id}", text = "Burn", info = "Burn tool") { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                        azRailItem(id = "dodge_img_${layer.id}", text = "Dodge", info = "Dodge tool") { activate(); editorViewModel.setActiveTool(Tool.DODGE) }

                        azDivider()
                        azRailItem(id = "flip_h_img_${layer.id}", text = "Flip Horiz", info = "Flip Horizontal") { activate(); editorViewModel.onFlipLayer(true) }
                        azRailItem(id = "flip_v_img_${layer.id}", text = "Flip Vert", info = "Flip Vertical") { activate(); editorViewModel.onFlipLayer(false) }
                        azDivider()

                        azRailItem(id = "color_bal_${layer.id}", text = "Color Bal", info = "Color Balance") { activate(); editorViewModel.onColorClicked() }
                        azRailItem(id = "adjust_${layer.id}", text = "Adjust", info = "Adjustments") { activate(); editorViewModel.onAdjustClicked() }
                        azDivider()
                        azRailItem(id = "blending_${layer.id}", text = "Blend Mode", info = "Cycle Blend Mode") { activate(); editorViewModel.onCycleBlendMode() }
                        azRailToggle(id = "lock_img_${layer.id}", isChecked = layer.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked", info = "Lock Layer") { activate(); editorViewModel.toggleImageLock() }
                    }
                }
            ) {
                // TRAILING CONTENT: Context Menu (Admin actions)
                val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }
                inputItem(hint = "Rename") { activate(); editorViewModel.onLayerRenamed(layer.id, it) }
                listItem(text = "Duplicate") { activate(); editorViewModel.onDuplicateLayer(layer.id) }
                listItem(text = "Copy Edits") { activate(); editorViewModel.onCopyLayerEdits(layer.id) }
                listItem(text = "Paste Edits") { activate(); editorViewModel.onPasteLayerEdits(layer.id) }
                listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        azDivider()
        azRailHostItem(id = "project_host", text = navStrings.project, info = "Project Actions")
        azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, info = "Save Project", shape = AzButtonShape.NONE) { showSaveDialog = true }
        azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load, info = "Load Project", shape = AzButtonShape.NONE) { dashboardViewModel.navigateToLibrary() }
        azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, info = "Export", shape = AzButtonShape.NONE) { editorViewModel.exportProject() }
        azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, info = "Settings", shape = AzButtonShape.NONE) { dashboardViewModel.navigateToSettings() }

        azDivider()

        azRailItem(id = "help", text = "Help", info = "Show Help") { showInfoScreen = true }
        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailItem(id = "light", text = navStrings.light, info = "Toggle Flashlight") { arViewModel.toggleFlashlight() }
        }
        if (editorUiState.editorMode == EditorMode.TRACE) {
            azRailItem(id = "lock_trace", text = navStrings.lock, info = "Lock Touch") { mainViewModel.setTouchLocked(true) }
        }
    }

    @Composable
    private fun AppContent(
        navHostScope: AzNavHostScope,
        navController: NavHostController,
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel,
        dockingSide: AzDockingSide,
        renderRefState: MutableState<ArRenderer?>,
        overlayImagePicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        backgroundImagePicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>
    ) {
        MainScreen(
            navHostScope = navHostScope,
            viewModel = mainViewModel,
            editorViewModel = editorViewModel,
            arViewModel = arViewModel,
            dashboardViewModel = dashboardViewModel,
            navController = navController,
            slamManager = slamManager,
            projectRepository = projectRepository,
            renderRefState = renderRefState,
            onRendererCreated = { renderRefState.value = it },
            hoistedUse3dBackgroundProvider = { use3dBackground },
            hoistedShowSaveDialogProvider = { showSaveDialog },
            hoistedShowInfoScreenProvider = { showInfoScreen },
            onUse3dBackgroundChange = { use3dBackground = it },
            onShowSaveDialogChange = { showSaveDialog = it },
            onShowInfoScreenChange = { showInfoScreen = it },
            hasCameraPermissionProvider = { hasCameraPermission },
            requestPermissions = { permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )},
            onOverlayImagePick = { overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onBackgroundImagePick = { backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            dockingSide = dockingSide,
            hasCameraPermission = hasCameraPermission
        )
    }
}
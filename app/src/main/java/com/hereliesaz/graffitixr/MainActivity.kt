package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHostScope
import com.hereliesaz.aznavrail.AzNavRailScope
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

    internal val mainViewModel: MainViewModel by viewModels()
    internal val editorViewModel: EditorViewModel by viewModels()
    internal val arViewModel: ArViewModel by viewModels()
    internal val dashboardViewModel: DashboardViewModel by viewModels()

    var use3dBackground by mutableStateOf(false)
    var showSaveDialog by mutableStateOf(false)
    var showInfoScreen by mutableStateOf(false)
    var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[android.Manifest.permission.CAMERA] ?: false
    }

    private val overlayImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.onAddLayer(it) }
    }

    private val backgroundImagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasCameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        slamManager.ensureInitialized()

        // Monitor Security Provider installation for recoverable errors
        lifecycleScope.launch {
            securityProviderManager.securityProviderState.collect { state ->
                if (state is SecurityProviderState.RecoverableError) {
                    GoogleApiAvailability.getInstance().getErrorDialog(
                        this@MainActivity,
                        state.errorCode,
                        9000 // Request code for Play Services resolution
                    )?.show()
                }
            }
        }

        setContent {
            GraffitiXRTheme {
                val navController = rememberNavController()
                val renderRefState = remember { mutableStateOf<ArRenderer?>(null) }

                // Collect states to force recomposition when they change
                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()

                val isRailVisible = !editorUiState.hideUiForCapture && !mainUiState.isTouchLocked

                // Calculate docking side here to pass down
                val dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT

                AzHostActivityLayout(
                    navController = navController,
                    initiallyExpanded = false,
                ) {
                    if (isRailVisible) {
                        configureRail()
                    }

                    // Pass the AzNavHostScope (this) down so AppContent can enforce the rail's UI boundaries
                    AppContent(
                        navHostScope = this,
                        navController = navController,
                        dockingSide = dockingSide,
                        renderRefState = renderRefState
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) slamManager.destroy()
    }

    private fun AzNavRailScope.configureRail() {
        val editorUiState = editorViewModel.uiState.value
        val isRightHanded = editorUiState.isRightHanded
        val viewModel = mainViewModel
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

        // Core Modes
        azNestedRail(id = "mode_host", text = navStrings.modes, alignment = AzNestedRailAlignment.VERTICAL) {
            azRailItem(id = "ar", text = navStrings.arMode) { if(hasCameraPermission) editorViewModel.setEditorMode(EditorMode.AR) else requestPermissions() }
            azRailItem(id = "overlay", text = navStrings.overlay) { if(hasCameraPermission) editorViewModel.setEditorMode(EditorMode.OVERLAY) else requestPermissions() }
            azRailItem(id = "mockup", text = navStrings.mockup) { editorViewModel.setEditorMode(EditorMode.STATIC) }
            azRailItem(id = "trace", text = navStrings.trace) { editorViewModel.setEditorMode(EditorMode.TRACE) }
        }

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            azNestedRail(id = "target_host", text = navStrings.grid, alignment = AzNestedRailAlignment.VERTICAL) {
                azRailItem(id = "create", text = navStrings.create) { if (hasCameraPermission) viewModel.startTargetCapture() else requestPermissions() }
                azRailItem(id = "surveyor", text = navStrings.surveyor) { if (hasCameraPermission) dashboardViewModel.navigateToSurveyor() else requestPermissions() }
                azRailItem(id = "capture_keyframe", text = "Keyframe") { arViewModel.captureKeyframe() }
            }
            azDivider()
        }

        // Design Actions (Global)
        azNestedRail(id = "design_host", text = navStrings.design, alignment = AzNestedRailAlignment.VERTICAL) {
            azRailItem(id = "add_image", text = "Image", info = navStrings.openInfo) {
                overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            azRailItem(id = "add_draw", text = "Draw", info = "New Blank Sketch") {
                editorViewModel.onAddBlankLayer()
            }

            if (editorUiState.editorMode == EditorMode.STATIC) {
                azRailItem(id = "wall", text = navStrings.wall) {
                    backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (!editorUiState.mapPath.isNullOrEmpty()) {
                    azRailToggle(
                        id = "toggle_3d", isChecked = use3dBackground,
                        toggleOnText = "3D View", toggleOffText = "2D View",
                        onClick = { use3dBackground = !use3dBackground }
                    )
                }
            }
        }
        azDivider()

        // --- GLOBAL TOOLS NESTED RAIL (Context-Aware) ---
        azNestedRail(id = "tools_global", text = "Tools", alignment = AzNestedRailAlignment.VERTICAL) {
            val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId }

            if (activeLayer != null) {
                if (activeLayer.isSketch) {
                    azRailItem(id = "brush_global", text = "Brush") { editorViewModel.setActiveTool(Tool.BRUSH) }
                    azRailItem(id = "eraser_global", text = "Eraser") { editorViewModel.setActiveTool(Tool.ERASER) }
                    azRailItem(id = "blur_global", text = "Blur") { editorViewModel.setActiveTool(Tool.BLUR) }
                    azRailItem(id = "heal_global", text = "Heal") { editorViewModel.setActiveTool(Tool.HEAL) }
                    azRailItem(id = "burn_global", text = "Burn") { editorViewModel.setActiveTool(Tool.BURN) }
                    azRailItem(id = "dodge_global", text = "Dodge") { editorViewModel.setActiveTool(Tool.DODGE) }
                    azRailItem(id = "liquify_global", text = "Liquify") { editorViewModel.setActiveTool(Tool.LIQUIFY) }
                    azDivider()

                    val hexColor = "#%06X".format(0xFFFFFF and editorUiState.activeColor.toArgb())
                    azRailItem(id = "color_global", text = hexColor) { editorViewModel.setShowColorPicker(true) }
                    azRailItem(id = "size_global", text = "Size: ${editorUiState.brushSize.toInt()}") { editorViewModel.setShowSizePicker(true) }
                } else {
                    azRailItem(id = "isolate_global", text = "Isolate") { editorViewModel.onRemoveBackgroundClicked() }
                    azRailItem(id = "outline_global", text = "Outline") { editorViewModel.onLineDrawingClicked() }
                    azRailItem(id = "liquify_img_global", text = "Liquify") { editorViewModel.setActiveTool(Tool.LIQUIFY) }
                    azRailItem(id = "eraser_img_global", text = "Eraser") { editorViewModel.setActiveTool(Tool.ERASER) }
                    azRailItem(id = "heal_img_global", text = "Heal") { editorViewModel.setActiveTool(Tool.HEAL) }
                    azRailItem(id = "burn_img_global", text = "Burn") { editorViewModel.setActiveTool(Tool.BURN) }
                    azRailItem(id = "dodge_img_global", text = "Dodge") { editorViewModel.setActiveTool(Tool.DODGE) }
                    azDivider()
                    azRailItem(id = "color_bal_global", text = "Color Bal") { editorViewModel.onColorClicked() }
                    azRailItem(id = "adjust_global", text = "Adjust") { editorViewModel.onAdjustClicked() }
                    azDivider()
                    azRailItem(id = "blending_global", text = "Blend Mode") { editorViewModel.onCycleBlendMode() }
                    azRailToggle(id = "lock_img_global", isChecked = activeLayer.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked") { editorViewModel.toggleImageLock() }
                }
            } else {
                azRailItem(id = "no_layer", text = "No Active Layer", disabled = true) { }
            }
        }

        azDivider()

        // DYNAMIC LAYERS (Relocatable)
        editorUiState.layers.reversed().forEach { layer ->

            // 1. The Layer as a Reorderable Item
            azRailRelocItem(
                id = "layer_${layer.id}",
                hostId = "design_host",
                text = layer.name,
                onClick = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) },
                onRelocate = { _, _, newOrder ->
                    editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed())
                }
                // REMOVED nestedContent (tools moved to global rail)
            ) {
                // HIDDEN CONTEXT MENU (Keep admin actions)
                val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }
                inputItem(hint = "Rename") { activate(); editorViewModel.onLayerRenamed(layer.id, it) }
                listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        // Project / Admin
        azDivider()
        azNestedRail(id = "project_host", text = navStrings.project, alignment = AzNestedRailAlignment.VERTICAL) {
            azRailItem(id = "save_project", text = navStrings.save) { showSaveDialog = true }
            azRailItem(id = "load_project", text = navStrings.load) { dashboardViewModel.navigateToLibrary() }
            azRailItem(id = "export_project", text = navStrings.export) { editorViewModel.exportProject() }
            azRailItem(id = "settings_sub", text = navStrings.settings) { dashboardViewModel.navigateToSettings() }
        }

        azDivider()

        azRailItem(id = "help", text = "Help") { showInfoScreen = true }
        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailItem(id = "light", text = navStrings.light) { arViewModel.toggleFlashlight() }
        }
        if (editorUiState.editorMode == EditorMode.TRACE) {
            azRailItem(id = "lock_trace", text = navStrings.lock) { mainViewModel.setTouchLocked(true) }
        }
    }

    fun AppContent(
        navHostScope: AzNavHostScope,
        navController: NavHostController,
        dockingSide: AzDockingSide,
        renderRefState: MutableState<ArRenderer?>
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
            dockingSide = dockingSide
        )
    }
}

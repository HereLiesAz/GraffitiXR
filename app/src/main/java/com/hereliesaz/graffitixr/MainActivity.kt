package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.NavHostController
import com.hereliesaz.aznavrail.AzActivity
import com.hereliesaz.aznavrail.AzGraphInterface
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
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AzActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository

    override val graph: AzGraphInterface = AzGraph

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
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) slamManager.destroy()
    }

    override fun AzNavRailScope.configureRail() {
        val editorUiState = editorViewModel.uiState.value
        val isRightHanded = editorUiState.isRightHanded
        val viewModel = mainViewModel
        val navStrings = NavStrings()

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
        azConfig(packButtons = true, dockingSide = if (isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)
        azAdvanced(infoScreen = showInfoScreen, onDismissInfoScreen = { showInfoScreen = false })

        val requestPermissions = { permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_FINE_LOCATION)) }

        // Core Modes (Nested Rail)
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

        // Design Actions (Nested Rail)
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

        // DYNAMIC LAYERS AS NESTED RAILS
        editorUiState.layers.reversed().forEach { layer ->

            azNestedRail(
                id = "layer_${layer.id}",
                text = layer.name,
                alignment = AzNestedRailAlignment.VERTICAL
            ) {
                // Ensure layer is activated when any tool inside it is clicked
                val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }

                // Dynamic Tool Injection based on layer type
                if (layer.isSketch) {
                    azRailItem(id = "brush_${layer.id}", text = "Brush") { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                    azRailItem(id = "eraser_${layer.id}", text = "Eraser") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                    azRailItem(id = "blur_${layer.id}", text = "Blur") { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                    azRailItem(id = "heal_${layer.id}", text = "Heal") { activate(); editorViewModel.setActiveTool(Tool.HEAL) }
                    azRailItem(id = "burn_${layer.id}", text = "Burn") { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                    azRailItem(id = "dodge_${layer.id}", text = "Dodge") { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                    azRailItem(id = "liquify_${layer.id}", text = "Liquify") { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                    azDivider()

                    val hexColor = "#%06X".format(0xFFFFFF and editorUiState.activeColor.toArgb())
                    azRailItem(id = "color_${layer.id}", text = hexColor) { activate(); editorViewModel.setShowColorPicker(true) }
                    azRailItem(id = "size_${layer.id}", text = "Size: ${editorUiState.brushSize.toInt()}") { activate(); editorViewModel.setShowSizePicker(true) }
                } else {
                    azRailItem(id = "isolate_${layer.id}", text = "Isolate") { activate(); editorViewModel.onRemoveBackgroundClicked() }
                    azRailItem(id = "outline_${layer.id}", text = "Outline") { activate(); editorViewModel.onLineDrawingClicked() }
                    azRailItem(id = "liquify_img_${layer.id}", text = "Liquify") { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                    azRailItem(id = "eraser_img_${layer.id}", text = "Eraser") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                    azRailItem(id = "heal_img_${layer.id}", text = "Heal") { activate(); editorViewModel.setActiveTool(Tool.HEAL) }
                    azRailItem(id = "burn_img_${layer.id}", text = "Burn") { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                    azRailItem(id = "dodge_img_${layer.id}", text = "Dodge") { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                    azDivider()
                    azRailItem(id = "color_bal_${layer.id}", text = "Color Bal") { activate(); editorViewModel.onColorClicked() }
                    azRailItem(id = "adjust_${layer.id}", text = "Adjust") { activate(); editorViewModel.onAdjustClicked() }
                }

                azDivider()

                if (!layer.isSketch) {
                    azRailItem(id = "blending_${layer.id}", text = "Blend Mode") { activate(); editorViewModel.onCycleBlendMode() }
                    azRailToggle(id = "lock_img_${layer.id}", isChecked = layer.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked") { activate(); editorViewModel.toggleImageLock() }
                }
                azRailItem(id = "remove_${layer.id}", text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        // Project / Admin (Nested Rail)
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
// ~~~ FILE: ./app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt ~~~
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
        // FIX: Reverted to raw Color classes to avoid calling @Composable MaterialTheme.colorScheme here
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
        azRailHostItem(id = "mode_host", text = navStrings.modes, onClick = {})
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode) { if(hasCameraPermission) editorViewModel.setEditorMode(EditorMode.AR) else requestPermissions() }
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay) { if(hasCameraPermission) editorViewModel.setEditorMode(EditorMode.OVERLAY) else requestPermissions() }
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup) { editorViewModel.setEditorMode(EditorMode.STATIC) }
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace) { editorViewModel.setEditorMode(EditorMode.TRACE) }
        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            azRailHostItem(id = "target_host", text = navStrings.grid, onClick = {})
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create) { if (hasCameraPermission) viewModel.startTargetCapture() else requestPermissions() }
            azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor) { if (hasCameraPermission) dashboardViewModel.navigateToSurveyor() else requestPermissions() }
            azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe") { arViewModel.captureKeyframe() }
            azDivider()
        }

        // Design Actions (Global)
        azRailHostItem(id = "design_host", text = navStrings.design, onClick = {})
        azRailSubItem(id = "add_image", text = "Image", hostId = "design_host", info = navStrings.openInfo) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        azRailSubItem(id = "add_draw", text = "Draw", hostId = "design_host", info = "New Blank Sketch") {
            editorViewModel.onAddBlankLayer()
        }

        if (editorUiState.editorMode == EditorMode.STATIC) {
            azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall) {
                backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (!editorUiState.mapPath.isNullOrEmpty()) {
                azRailSubToggle(
                    id = "toggle_3d", hostId = "design_host", isChecked = use3dBackground,
                    toggleOnText = "3D View", toggleOffText = "2D View",
                    onClick = { use3dBackground = !use3dBackground }
                )
            }
        }

        azDivider()

        // DYNAMIC LAYERS AS NESTED RAIL PARENTS
        editorUiState.layers.reversed().forEach { layer ->

            // The Layer itself is a Host
            azRailHostItem(
                id = "layer_${layer.id}",
                text = layer.name,
                onClick = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }
            )

            // Dynamic Tool Injection based on layer type
            if (layer.isSketch) {
                azRailSubItem(id = "brush_${layer.id}", hostId = "layer_${layer.id}", text = "Brush") { editorViewModel.setActiveTool(Tool.BRUSH) }
                azRailSubItem(id = "eraser_${layer.id}", hostId = "layer_${layer.id}", text = "Eraser") { editorViewModel.setActiveTool(Tool.ERASER) }
                azRailSubItem(id = "blur_${layer.id}", hostId = "layer_${layer.id}", text = "Blur") { editorViewModel.setActiveTool(Tool.BLUR) }
                azRailSubItem(id = "heal_${layer.id}", hostId = "layer_${layer.id}", text = "Heal") { editorViewModel.setActiveTool(Tool.HEAL) }
                azRailSubItem(id = "burn_${layer.id}", hostId = "layer_${layer.id}", text = "Burn") { editorViewModel.setActiveTool(Tool.BURN) }
                azRailSubItem(id = "dodge_${layer.id}", hostId = "layer_${layer.id}", text = "Dodge") { editorViewModel.setActiveTool(Tool.DODGE) }
                azRailSubItem(id = "liquify_${layer.id}", hostId = "layer_${layer.id}", text = "Liquify") { editorViewModel.setActiveTool(Tool.LIQUIFY) }
                azDivider()
                // Color representation as hex text inside standard subitem
                val hexColor = "#%06X".format(0xFFFFFF and editorUiState.activeColor.toArgb())
                azRailSubItem(id = "color_${layer.id}", hostId = "layer_${layer.id}", text = hexColor) { editorViewModel.setShowColorPicker(true) }
                azRailSubItem(id = "size_${layer.id}", hostId = "layer_${layer.id}", text = "Size: ${editorUiState.brushSize.toInt()}") { editorViewModel.setShowSizePicker(true) }
            } else {
                azRailSubItem(id = "isolate_${layer.id}", hostId = "layer_${layer.id}", text = "Isolate") { editorViewModel.onRemoveBackgroundClicked() }
                azRailSubItem(id = "outline_${layer.id}", hostId = "layer_${layer.id}", text = "Outline") { editorViewModel.onLineDrawingClicked() }
                azRailSubItem(id = "liquify_img_${layer.id}", hostId = "layer_${layer.id}", text = "Liquify") { editorViewModel.setActiveTool(Tool.LIQUIFY) }
                azRailSubItem(id = "eraser_img_${layer.id}", hostId = "layer_${layer.id}", text = "Eraser") { editorViewModel.setActiveTool(Tool.ERASER) }
                azRailSubItem(id = "heal_img_${layer.id}", hostId = "layer_${layer.id}", text = "Heal") { editorViewModel.setActiveTool(Tool.HEAL) }
                azRailSubItem(id = "burn_img_${layer.id}", hostId = "layer_${layer.id}", text = "Burn") { editorViewModel.setActiveTool(Tool.BURN) }
                azRailSubItem(id = "dodge_img_${layer.id}", hostId = "layer_${layer.id}", text = "Dodge") { editorViewModel.setActiveTool(Tool.DODGE) }
                azDivider()
                azRailSubItem(id = "color_bal_${layer.id}", hostId = "layer_${layer.id}", text = "Color Bal") { editorViewModel.onColorClicked() }
                azRailSubItem(id = "adjust_${layer.id}", hostId = "layer_${layer.id}", text = "Adjust") { editorViewModel.onAdjustClicked() }
            }

            azDivider()
            azRailSubItem(id = "blending_${layer.id}", hostId = "layer_${layer.id}", text = "Blend Mode") { editorViewModel.onCycleBlendMode() }
            azRailSubToggle(id = "lock_img_${layer.id}", hostId = "layer_${layer.id}", isChecked = layer.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked") { editorViewModel.toggleImageLock() }
            azRailSubItem(id = "remove_${layer.id}", hostId = "layer_${layer.id}", text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
        }

        // Project / Admin
        azDivider()
        azRailHostItem(id = "project_host", text = navStrings.project, onClick = {})
        azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save) { showSaveDialog = true }
        azRailSubItem(id = "load_project", hostId = "project_host", text = navStrings.load) { dashboardViewModel.navigateToLibrary() }
        azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export) { editorViewModel.exportProject() }
        azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings) { dashboardViewModel.navigateToSettings() }
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
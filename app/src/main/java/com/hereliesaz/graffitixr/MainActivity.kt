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

        // Core Modes (Hosted Rail)
        azRailHostItem(id = "mode_host", text = navStrings.modes)
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode) { if(hasCameraPermission) editorViewModel.setEditorMode(EditorMode.AR) else requestPermissions() }
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay) { if(hasCameraPermission) editorViewModel.setEditorMode(EditorMode.OVERLAY) else requestPermissions() }
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup) { editorViewModel.setEditorMode(EditorMode.STATIC) }
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace) { editorViewModel.setEditorMode(EditorMode.TRACE) }

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            // Target/Grid (Hosted Rail)
            azRailHostItem(id = "target_host", text = navStrings.grid)
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create) { if (hasCameraPermission) viewModel.startTargetCapture() else requestPermissions() }
            azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor) { if (hasCameraPermission) dashboardViewModel.navigateToSurveyor() else requestPermissions() }
            azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe") { arViewModel.captureKeyframe() }
            azDivider()
        }

        // Design Actions (Hosted Rail)
        azRailHostItem(id = "design_host", text = navStrings.design)
        azRailSubItem(id = "add_image", hostId = "design_host", text = "Image", info = navStrings.openInfo) {
            overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        azRailSubItem(id = "add_draw", hostId = "design_host", text = "Draw", info = "New Blank Sketch") {
            editorViewModel.onAddBlankLayer()
        }

        if (editorUiState.editorMode == EditorMode.STATIC) {
            azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall) {
                backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (!editorUiState.mapPath.isNullOrEmpty()) {
                azRailSubItem(
                    id = "toggle_3d",
                    hostId = "design_host",
                    text = if (use3dBackground) "3D View" else "2D View"
                ) {
                    use3dBackground = !use3dBackground
                }
            }
        }
        azDivider()

        // DYNAMIC LAYERS (Relocatable)
        // These are children of the Design host, but they themselves have a Nested Rail for tools.
        editorUiState.layers.reversed().forEach { layer ->

            azRailRelocItem(
                id = "layer_${layer.id}",
                hostId = "design_host",
                text = layer.name,
                onClick = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) },
                onRelocate = { _, _, newOrder ->
                    editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed())
                },
                // The Tools are defined HERE, creating a Nested Rail attached to this item.
                nestedContent = {
                    val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }

                    if (layer.isSketch) {
                        azRailItem(id = "brush_${layer.id}", text = "Brush") { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                        azRailItem(id = "eraser_${layer.id}", text = "Eraser") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                        azRailItem(id = "blur_${layer.id}", text = "Blur") { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                        azRailItem(id = "heal_${layer.id}", text = "Heal") { activate(); editorViewModel.setActiveTool(Tool.HEAL) }
                        azRailItem(id = "burn_${layer.id}", text = "Burn") { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                        azRailItem(id = "dodge_${layer.id}", text = "Dodge") { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                        azRailItem(id = "liquify_${layer.id}", text = "Liquify") { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }

                        azDivider()
                        azRailItem(id = "flip_h_sketch_${layer.id}", text = "Flip Horiz") { activate(); editorViewModel.onFlipLayer(true) }
                        azRailItem(id = "flip_v_sketch_${layer.id}", text = "Flip Vert") { activate(); editorViewModel.onFlipLayer(false) }
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
                        azRailItem(id = "flip_h_img_${layer.id}", text = "Flip Horiz") { activate(); editorViewModel.onFlipLayer(true) }
                        azRailItem(id = "flip_v_img_${layer.id}", text = "Flip Vert") { activate(); editorViewModel.onFlipLayer(false) }
                        azDivider()

                        azRailItem(id = "color_bal_${layer.id}", text = "Color Bal") { activate(); editorViewModel.onColorClicked() }
                        azRailItem(id = "adjust_${layer.id}", text = "Adjust") { activate(); editorViewModel.onAdjustClicked() }
                        azDivider()
                        azRailItem(id = "blending_${layer.id}", text = "Blend Mode") { activate(); editorViewModel.onCycleBlendMode() }
                        azRailToggle(id = "lock_img_${layer.id}", isChecked = layer.isImageLocked, toggleOnText = "Locked", toggleOffText = "Unlocked") { activate(); editorViewModel.toggleImageLock() }
                    }
                }
            ) {
                // HIDDEN CONTEXT MENU (Admin Actions)
                val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }
                inputItem(hint = "Rename") { activate(); editorViewModel.onLayerRenamed(layer.id, it) }
                listItem(text = "Duplicate") { activate(); editorViewModel.onDuplicateLayer(layer.id) }
                listItem(text = "Copy Edits") { activate(); editorViewModel.onCopyLayerEdits(layer.id) }
                listItem(text = "Paste Edits") { activate(); editorViewModel.onPasteLayerEdits(layer.id) }
                listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        // Project / Admin (Hosted Rail)
        azDivider()
        azRailHostItem(id = "project_host", text = navStrings.project)
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

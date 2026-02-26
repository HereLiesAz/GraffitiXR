package com.hereliesaz.graffitixr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.aznavrail.AzHostActivityLayout
import com.hereliesaz.aznavrail.AzNavHost
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
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.google.android.gms.common.GoogleApiAvailability
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * The core entry point for GraffitiXR.
 * Strictly follows the AzNavRail v7.55 Architecture:
 * 1. AzHostActivityLayout manages safe zones and rail z-ordering.
 * 2. Viewports (AR/Mockup) are handled in the 'background' block.
 * 3. Interactive UI (Editor tools) is handled in 'onscreen' via 'AzNavHost'.
 */
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
                val arUiState by arViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()

                // 1. Dashboard Navigation Observer (External Activities)
                LaunchedEffect(dashboardNavigation) {
                    dashboardNavigation?.let { destination ->
                        if (destination == "surveyor") {
                            startActivity(Intent(this@MainActivity, MappingActivity::class.java))
                        }
                        dashboardViewModel.onNavigationConsumed()
                    }
                }

                // 2. Rail -> ViewModel Synchronization
                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        val route = entry.destination.route
                        if (route != null) {
                            try {
                                val mode = EditorMode.valueOf(route)
                                if (editorUiState.editorMode != mode) {
                                    editorViewModel.setEditorMode(mode)
                                }
                            } catch (e: Exception) { /* Not an editor mode */ }
                        }
                    }
                }

                val isRailVisible = !editorUiState.hideUiForCapture && !mainUiState.isTouchLocked

                AzHostActivityLayout(
                    navController = navController,
                    initiallyExpanded = false,
                ) {
                    // CONFIGURE RAIL DSL
                    if (isRailVisible) {
                        configureRail(mainViewModel, editorViewModel, arViewModel, dashboardViewModel)
                    }

                    // BACKGROUND LAYER (Full-screen viewports, ignores safe zones)
                    background(weight = 0) {
                        MainScreen(
                            viewModel = mainViewModel,
                            editorViewModel = editorViewModel,
                            arViewModel = arViewModel,
                            slamManager = slamManager,
                            projectRepository = projectRepository,
                            onRendererCreated = { renderRefState.value = it },
                            hasCameraPermission = hasCameraPermission
                        )
                    }

                    // ONSCREEN LAYER (Safe UI, respects safe zones and rail padding)
                    onscreen {
                        AzNavHost(startDestination = EditorMode.AR.name) {
                            // Define all modes as routes to satisfy NavController
                            composable(EditorMode.AR.name) { EditorOverlay(editorViewModel, mainUiState) }
                            composable(EditorMode.OVERLAY.name) { EditorOverlay(editorViewModel, mainUiState) }
                            composable(EditorMode.STATIC.name) { EditorOverlay(editorViewModel, mainUiState) }
                            composable(EditorMode.TRACE.name) { EditorOverlay(editorViewModel, mainUiState) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditorOverlay(viewModel: EditorViewModel, mainUiState: MainUiState) {
        val uiState by viewModel.uiState.collectAsState()
        EditorUi(
            actions = viewModel,
            uiState = uiState,
            isTouchLocked = mainUiState.isTouchLocked,
            showUnlockInstructions = mainUiState.showUnlockInstructions,
            isCapturingTarget = mainUiState.isCapturingTarget
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) slamManager.destroy()
    }

    private fun AzNavHostScope.configureRail(
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel
    ) {
        val editorUiState = editorViewModel.uiState.value
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
        azConfig(
            packButtons = true,
            dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
        )
        azAdvanced(infoScreen = showInfoScreen, onDismissInfoScreen = { showInfoScreen = false })

        val requestPermissions = { permissionLauncher.launch(arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.ACCESS_FINE_LOCATION)) }

        // --- MODES ---
        azRailHostItem(id = "mode_host", text = navStrings.modes, info = "Switch editor modes")
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, route = EditorMode.AR.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, route = EditorMode.OVERLAY.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, route = EditorMode.STATIC.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, route = EditorMode.TRACE.name, shape = AzButtonShape.NONE)

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            azRailHostItem(id = "target_host", text = navStrings.grid, info = "Scanning tools")
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, shape = AzButtonShape.NONE) { if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions() }
            azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, shape = AzButtonShape.NONE) { if (hasCameraPermission) dashboardViewModel.navigateToSurveyor() else requestPermissions() }
            azRailSubItem(id = "capture_keyframe", hostId = "target_host", text = "Keyframe", shape = AzButtonShape.NONE) { arViewModel.captureKeyframe() }
            azDivider()
        }

        azRailHostItem(id = "design_host", text = navStrings.design, info = "Design tools")
        // Note: Image/Draw pickers would typically be here (Simplified for brevity)

        azDivider()

        // --- LAYERS ---
        editorUiState.layers.reversed().forEach { layer ->
            azRailRelocItem(
                id = "layer_${layer.id}",
                hostId = "design_host",
                text = layer.name,
                nestedRailAlignment = AzNestedRailAlignment.HORIZONTAL,
                onClick = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) },
                onRelocate = { _, _, newOrder -> editorViewModel.onLayerReordered(newOrder.map { it.removePrefix("layer_") }.reversed()) },
                nestedContent = {
                    val activate = { if (editorUiState.activeLayerId != layer.id) editorViewModel.onLayerActivated(layer.id) }
                    if (layer.isSketch) {
                        azRailItem(id = "brush_${layer.id}", text = "Brush") { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                        azRailItem(id = "eraser_${layer.id}", text = "Eraser") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                    } else {
                        azRailItem(id = "isolate_${layer.id}", text = "Isolate") { activate(); editorViewModel.onRemoveBackgroundClicked() }
                        azRailItem(id = "outline_${layer.id}", text = "Outline") { activate(); editorViewModel.onLineDrawingClicked() }
                        azRailItem(id = "adjust_${layer.id}", text = "Adjust") { activate(); editorViewModel.onAdjustClicked() }
                    }
                }
            ) {
                listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        azDivider()
        azRailHostItem(id = "project_host", text = navStrings.project, info = "Project Actions")
        azRailSubItem(id = "save_project", hostId = "project_host", text = navStrings.save, shape = AzButtonShape.NONE) { showSaveDialog = true }
        azRailSubItem(id = "export_project", hostId = "project_host", text = navStrings.export, shape = AzButtonShape.NONE) { editorViewModel.exportProject() }

        azDivider()
        azRailItem(id = "help", text = "Help") { showInfoScreen = true }
        if (editorUiState.editorMode == EditorMode.AR) {
            azRailItem(id = "light", text = navStrings.light) { arViewModel.toggleFlashlight() }
        }
    }
}
package com.hereliesaz.graffitixr

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.common.GoogleApiAvailability
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
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.security.SecurityProviderState
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingActivity
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository
    @Inject lateinit var securityProviderManager: SecurityProviderManager

    var use3dBackground by mutableStateOf(false)
    var showSaveDialog by mutableStateOf(false)
    var showInfoScreen by mutableStateOf(false)
    var showLibrary by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
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
                    GoogleApiAvailability.getInstance().getErrorDialog(this@MainActivity, state.errorCode, 9000)?.show()
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

                LaunchedEffect(dashboardNavigation) {
                    dashboardNavigation?.let { destination ->
                        when (destination) {
                            "surveyor" -> startActivity(Intent(this@MainActivity, MappingActivity::class.java))
                            "project_library" -> showLibrary = true
                            "settings" -> showSettings = true
                        }
                        dashboardViewModel.onNavigationConsumed()
                    }
                }

                LaunchedEffect(navController) {
                    navController.currentBackStackEntryFlow.collect { entry ->
                        val route = entry.destination.route
                        if (route != null) {
                            try {
                                val mode = EditorMode.valueOf(route)
                                if (editorUiState.editorMode != mode) editorViewModel.setEditorMode(mode)
                            } catch (e: Exception) { }
                        }
                    }
                }

                val isRailVisible = !editorUiState.hideUiForCapture && !mainUiState.isTouchLocked
                val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { editorViewModel.onAddLayer(it) } }
                val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { editorViewModel.setBackgroundImage(it) } }

                AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
                    if (isRailVisible) {
                        configureRail(
                            mainViewModel, editorViewModel, arViewModel, dashboardViewModel,
                            overlayImagePicker, backgroundImagePicker
                        )
                    }

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

                    onscreen {
                        Box(Modifier.fillMaxSize()) {
                            AzNavHost(startDestination = EditorMode.AR.name) {
                                composable(EditorMode.AR.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.OVERLAY.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.STATIC.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.TRACE.name) { EditorOverlay(editorViewModel, mainUiState) }
                            }

                            if (showSaveDialog) {
                                SaveProjectDialog(
                                    initialName = editorUiState.projectId ?: "New Project",
                                    onDismissRequest = { showSaveDialog = false },
                                    onSaveRequest = { name ->
                                        editorViewModel.saveProject(name)
                                        showSaveDialog = false
                                    }
                                )
                            }

                            if (showLibrary) {
                                val dashboardState by dashboardViewModel.uiState.collectAsState()
                                LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                                ProjectLibraryScreen(
                                    projects = dashboardState.availableProjects,
                                    onLoadProject = {
                                        dashboardViewModel.openProject(it)
                                        showLibrary = false
                                    },
                                    onDeleteProject = { dashboardViewModel.deleteProject(it) },
                                    onNewProject = {
                                        dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                                        showLibrary = false
                                    }
                                )
                            }

                            if (showSettings) {
                                SettingsScreen(
                                    currentVersion = "1.18.0",
                                    updateStatus = "Up to date",
                                    isCheckingForUpdate = false,
                                    isRightHanded = editorUiState.isRightHanded,
                                    onHandednessChanged = { editorViewModel.toggleHandedness() },
                                    onCheckForUpdates = {},
                                    onInstallUpdate = {},
                                    onClose = { showSettings = false }
                                )
                            }
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

    private fun AzNavHostScope.configureRail(
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel,
        overlayPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        backgroundPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>
    ) {
        val editorUiState = editorViewModel.uiState.value
        val navStrings = NavStrings()
        val activeHighlightColor = when (editorUiState.activeRotationAxis) {
            RotationAxis.X -> Color.Red
            RotationAxis.Y -> Color.Green
            RotationAxis.Z -> Color.Blue
        }

        azTheme(activeColor = activeHighlightColor, defaultShape = AzButtonShape.RECTANGLE, headerIconShape = AzHeaderIconShape.ROUNDED)
        azConfig(packButtons = true, dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT)

        azRailHostItem(id = "mode_host", text = navStrings.modes)
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, route = EditorMode.AR.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, route = EditorMode.OVERLAY.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, route = EditorMode.STATIC.name, shape = AzButtonShape.NONE)
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, route = EditorMode.TRACE.name, shape = AzButtonShape.NONE)

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR) {
            azRailHostItem(id = "target_host", text = navStrings.grid)
            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, shape = AzButtonShape.NONE) { mainViewModel.startTargetCapture() }
            azRailSubItem(id = "surveyor", hostId = "target_host", text = navStrings.surveyor, shape = AzButtonShape.NONE) { dashboardViewModel.navigateToSurveyor() }
            azRailSubItem(id = "key", hostId = "target_host", text = "Keyframe", shape = AzButtonShape.NONE) { arViewModel.captureKeyframe() }
            azDivider()
        }

        azRailHostItem(id = "design_host", text = navStrings.design)
        azRailSubItem(id = "add_img", hostId = "design_host", text = "Image", shape = AzButtonShape.NONE) { overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        azRailSubItem(id = "add_draw", hostId = "design_host", text = "Draw", shape = AzButtonShape.NONE) { editorViewModel.onAddBlankLayer() }

        azDivider()

        editorUiState.layers.reversed().forEach { layer ->
            azRailRelocItem(
                id = "layer_${layer.id}",
                hostId = "design_host",
                text = layer.name,
                nestedRailAlignment = AzNestedRailAlignment.HORIZONTAL,
                onClick = { editorViewModel.onLayerActivated(layer.id) },
                onRelocate = { _, _, new -> editorViewModel.onLayerReordered(new.map { it.removePrefix("layer_") }.reversed()) },
                nestedContent = {
                    val activate = { editorViewModel.onLayerActivated(layer.id) }
                    if (layer.isSketch) {
                        azRailItem(id = "brush_${layer.id}", text = "Brush") { activate(); editorViewModel.setActiveTool(Tool.BRUSH) }
                        azRailItem(id = "eraser_${layer.id}", text = "Eraser") { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                    } else {
                        azRailItem(id = "iso_${layer.id}", text = "Isolate") { activate(); editorViewModel.onRemoveBackgroundClicked() }
                        azRailItem(id = "line_${layer.id}", text = "Outline") { activate(); editorViewModel.onLineDrawingClicked() }
                        azRailItem(id = "adj_${layer.id}", text = "Adjust") { activate(); editorViewModel.onAdjustClicked() }
                    }
                }
            ) {
                listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
            }
        }

        azDivider()
        azRailHostItem(id = "project_host", text = navStrings.project)
        azRailSubItem(id = "save", hostId = "project_host", text = navStrings.save, shape = AzButtonShape.NONE) { showSaveDialog = true }
        azRailSubItem(id = "load", hostId = "project_host", text = navStrings.load, shape = AzButtonShape.NONE) { dashboardViewModel.navigateToLibrary() }
        azRailSubItem(id = "export", hostId = "project_host", text = navStrings.export, shape = AzButtonShape.NONE) { editorViewModel.exportProject() }
        azRailSubItem(id = "settings_sub", hostId = "project_host", text = navStrings.settings, shape = AzButtonShape.NONE) { dashboardViewModel.navigateToSettings() }

        azDivider()
        azRailItem(id = "help", text = "Help") { showInfoScreen = true }
        if (editorUiState.editorMode == EditorMode.AR) {
            azRailItem(id = "light", text = navStrings.light) { arViewModel.toggleFlashlight() }
        }
    }
}
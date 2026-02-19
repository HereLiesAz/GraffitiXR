package com.hereliesaz.graffitixr

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.hereliesaz.aznavrail.annotation.*
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingUi
import com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground
import com.hereliesaz.graffitixr.feature.ar.TargetCreationUi
import com.hereliesaz.graffitixr.feature.ar.rememberTargetCreationState
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.MockupScreen as EditorMockupScreen
import com.hereliesaz.graffitixr.feature.editor.OverlayScreen as EditorOverlayScreen
import com.hereliesaz.graffitixr.feature.editor.TraceScreen as EditorTraceScreen
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScreensEntryPoint {
    fun slamManager(): SlamManager
    fun projectRepository(): com.hereliesaz.graffitixr.domain.repository.ProjectRepository
}

/**
 * Wrapper to ensure permissions are granted before showing content.
 */
@Composable
fun PermissionWrapper(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var hasPermissions by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms[Manifest.permission.CAMERA] == true
    }

    if (hasPermissions) {
        content()
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Button(onClick = {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }) {
                Text("Grant Permissions")
            }
        }
    }
}

// Help Screen
@Az(rail = RailItem(id = "help", text = "Help", parent = "hidden_host"))
@Composable
fun HelpScreen() {
    com.hereliesaz.graffitixr.design.components.InfoDialog(
        title = "GraffitiXR Help",
        content = "Design and project graffiti onto physical walls using AR.",
        onDismiss = { /* No-op, managed by AzNavRail info screen */ }
    )
}

/**
 * Shared wrapper for Editor Screens to reduce duplication.
 */
@Composable
fun BaseEditorScreen(
    editorMode: EditorMode,
    content: @Composable (EditorViewModel, EditorUiState) -> Unit
) {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)

    val editorUiState by editorViewModel.uiState.collectAsState()
    val mainUiState by mainViewModel.uiState.collectAsState()

    LaunchedEffect(editorMode) {
        editorViewModel.setEditorMode(editorMode)
    }

    Box(Modifier.fillMaxSize()) {
        content(editorViewModel, editorUiState)

        EditorUi(
            actions = editorViewModel,
            uiState = editorUiState,
            isTouchLocked = mainUiState.isTouchLocked,
            showUnlockInstructions = mainUiState.showUnlockInstructions,
            isCapturingTarget = mainUiState.isCapturingTarget
        )
    }
}

// AR Screen (Default/Home)
@Az(rail = RailItem(id = "ar", text = "AR", parent = "hidden_host", home = true))
@Composable
fun ArScreen() {
    PermissionWrapper {
        val context = LocalContext.current
        val entryPoint = EntryPointAccessors.fromApplication(context, ScreensEntryPoint::class.java)
        val slamManager = entryPoint.slamManager()
        val projectRepository = entryPoint.projectRepository()
        val activity = context as ComponentActivity
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val arUiState by arViewModel.uiState.collectAsState()

        BaseEditorScreen(EditorMode.AR) { editorViewModel, editorUiState ->
            val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

            ArView(
                viewModel = arViewModel,
                uiState = arUiState,
                slamManager = slamManager,
                projectRepository = projectRepository,
                activeLayer = activeLayer,
                onRendererCreated = { /* Handle renderer creation if needed */ },
                hasCameraPermission = true
            )

            if (!arUiState.isTargetDetected && editorUiState.layers.isNotEmpty()) {
                EditorOverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
            }
        }
    }
}

// Overlay Screen
@Az(rail = RailItem(id = "overlay", text = "Overlay", parent = "hidden_host"))
@Composable
fun OverlayScreen() {
    PermissionWrapper {
        val context = LocalContext.current
        val entryPoint = EntryPointAccessors.fromApplication(context, ScreensEntryPoint::class.java)
        val slamManager = entryPoint.slamManager()
        val projectRepository = entryPoint.projectRepository()
        val activity = context as ComponentActivity
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val arUiState by arViewModel.uiState.collectAsState()

        BaseEditorScreen(EditorMode.OVERLAY) { editorViewModel, editorUiState ->
            val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

            ArView(
                viewModel = arViewModel,
                uiState = arUiState.copy(showPointCloud = false),
                slamManager = slamManager,
                projectRepository = projectRepository,
                activeLayer = activeLayer,
                onRendererCreated = { },
                hasCameraPermission = true
            )
            EditorOverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
        }
    }
}

// Mockup Screen
@Az(rail = RailItem(id = "mockup", text = "Mockup", parent = "hidden_host"))
@Composable
fun MockupScreen() {
    BaseEditorScreen(EditorMode.STATIC) { editorViewModel, editorUiState ->
        EditorMockupScreen(uiState = editorUiState, viewModel = editorViewModel)
    }
}

// Trace Screen
@Az(rail = RailItem(id = "trace", text = "Trace", parent = "hidden_host"))
@Composable
fun TraceScreen() {
    BaseEditorScreen(EditorMode.TRACE) { editorViewModel, editorUiState ->
        EditorTraceScreen(uiState = editorUiState, viewModel = editorViewModel)
    }
}

// Create Screen
@Az(rail = RailItem(id = "create", text = "Create", parent = "hidden_host"))
@Composable
fun CreateScreen() {
    PermissionWrapper {
        val context = LocalContext.current
        val activity = context as ComponentActivity
        val mainViewModel: MainViewModel = hiltViewModel(activity)
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val editorViewModel: EditorViewModel = hiltViewModel(activity)

        val mainUiState by mainViewModel.uiState.collectAsState()
        val arUiState by arViewModel.uiState.collectAsState()
        val editorUiState by editorViewModel.uiState.collectAsState()

        val targetCreationState = rememberTargetCreationState()
        val scope = rememberCoroutineScope()

        // Ensure we are in capture mode
        LaunchedEffect(Unit) {
            mainViewModel.startTargetCapture()
        }

        if (mainUiState.captureStep == CaptureStep.NONE) {
            // Flow finished
            Text("Target Created.")
        } else {
            Box(Modifier.fillMaxSize()) {
                // 1. Background (Camera / Unwarp Preview)
                TargetCreationBackground(
                    uiState = arUiState,
                    captureStep = mainUiState.captureStep,
                    state = targetCreationState,
                    onPhotoCaptured = { bitmap ->
                        arViewModel.setTempCapture(bitmap)
                        mainViewModel.setCaptureStep(CaptureStep.RECTIFY)
                    }
                )

                // 2. UI Overlay (Buttons, Guides)
                TargetCreationUi(
                    uiState = arUiState,
                    isRightHanded = editorUiState.isRightHanded,
                    captureStep = mainUiState.captureStep,
                    state = targetCreationState,
                    onConfirm = {
                        val bitmapToSave = arUiState.tempCaptureBitmap
                        if (bitmapToSave != null) {
                            scope.launch(Dispatchers.IO) {
                                val uri = saveBitmapToCache(context, bitmapToSave)
                                if (uri != null) {
                                    withContext(Dispatchers.Main) {
                                        arViewModel.onFrameCaptured(bitmapToSave, uri)
                                        mainViewModel.onConfirmTargetCreation()
                                    }
                                }
                            }
                        } else {
                            mainViewModel.onConfirmTargetCreation()
                        }
                    },
                    onRetake = mainViewModel::onRetakeCapture,
                    onCancel = mainViewModel::onCancelCaptureClicked,
                    onUnwarpConfirm = { points: List<Offset> ->
                        arUiState.tempCaptureBitmap?.let { src ->
                            ImageProcessor.unwarpImage(src, points)?.let { unwarped ->
                                arViewModel.setTempCapture(unwarped)
                                // Inline state transition to MASK, keeping flow self-contained
                                mainViewModel.setCaptureStep(CaptureStep.MASK)
                            }
                        }
                    },
                    onMaskConfirmed = { maskedBitmap: Bitmap ->
                        val extracted = ImageProcessor.detectEdges(maskedBitmap) ?: maskedBitmap
                        arViewModel.setTempCapture(extracted)
                        mainViewModel.setCaptureStep(CaptureStep.REVIEW)
                    }
                )
            }
        }
    }
}

// Surveyor Screen
@Az(rail = RailItem(id = "surveyor", text = "Survey", parent = "hidden_host"))
@Composable
fun SurveyorScreen() {
    PermissionWrapper {
        MappingUi(
            onBackClick = { /* Handle via rail */ },
            onScanComplete = { /* Handle complete */ }
        )
    }
}

// Project Library Screen
@Az(rail = RailItem(id = "project_library", text = "Library", parent = "hidden_host"))
@Composable
fun ProjectLibraryWrapper(navController: NavController) {
    val activity = LocalActivity.current as ComponentActivity
    val dashboardViewModel: DashboardViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val dashboardUiState by dashboardViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }

    Box(Modifier.fillMaxSize()) {
        ProjectLibraryScreen(
            projects = dashboardUiState.availableProjects,
            onLoadProject = {
                dashboardViewModel.openProject(it)
                // Navigate to default editor screen (AR)
                navController.navigate("ar") {
                    popUpTo("project_library") { inclusive = true }
                }
            },
            onDeleteProject = { projectId ->
                dashboardViewModel.deleteProject(projectId)
            },
            onNewProject = {
                dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                navController.navigate("ar") {
                    popUpTo("project_library") { inclusive = true }
                }
            }
        )
    }
}

// Settings Screen
@Az(rail = RailItem(id = "settings", text = "Settings", parent = "hidden_host"))
@Composable
fun SettingsWrapper(navController: NavController) {
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val editorUiState by editorViewModel.uiState.collectAsState()

    Box(Modifier.fillMaxSize()) {
        SettingsScreen(
            currentVersion = BuildConfig.VERSION_NAME,
            updateStatus = "Up to date",
            isCheckingForUpdate = false,
            isRightHanded = editorUiState.isRightHanded,
            onHandednessChanged = { editorViewModel.toggleHandedness() },
            onCheckForUpdates = { },
            onInstallUpdate = { },
            onClose = { navController.popBackStack() }
        )
    }
}

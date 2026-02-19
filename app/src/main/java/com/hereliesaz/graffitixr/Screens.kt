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
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingUi
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

// Light Screen (Toggle)
@Az(rail = RailItem(id = "light", text = "Light", parent = "hidden_host"))
@Composable
fun LightScreen() {
    val activity  = LocalActivity.current as ComponentActivity
    val arViewModel: ArViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        arViewModel.toggleFlashlight()
    }
    Box(Modifier.fillMaxSize())
}

// Lock Trace Screen
@Az(rail = RailItem(id = "lock_trace", text = "Lock", parent = "hidden_host"))
@Composable
fun LockTraceScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        mainViewModel.setTouchLocked(true)
    }
    Text("Trace Locked")
}

// AR Screen (Default/Home)
// Marked as home=true, but parent=hidden_host so it's not in the generated rail.
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
        val mainViewModel: MainViewModel = hiltViewModel(activity)
        val editorViewModel: EditorViewModel = hiltViewModel(activity)

        val arUiState by arViewModel.uiState.collectAsState()
        val mainUiState by mainViewModel.uiState.collectAsState()
        val editorUiState by editorViewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            editorViewModel.setEditorMode(com.hereliesaz.graffitixr.common.model.EditorMode.AR)
        }

        val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

        Box(Modifier.fillMaxSize()) {
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

            EditorUi(
                actions = editorViewModel,
                uiState = editorUiState,
                isTouchLocked = mainUiState.isTouchLocked,
                showUnlockInstructions = mainUiState.showUnlockInstructions,
                isCapturingTarget = mainUiState.isCapturingTarget
            )
        }
    }
}

// Overlay Screen
@Az(rail = RailItem(id = "overlay", text = "Overlay", parent = "hidden_host"))
@Composable
fun OverlayScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val arViewModel: ArViewModel = hiltViewModel(activity)

    val editorUiState by editorViewModel.uiState.collectAsState()
    val mainUiState by mainViewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        editorViewModel.setEditorMode(com.hereliesaz.graffitixr.common.model.EditorMode.OVERLAY)
    }

    PermissionWrapper {
        val context = LocalContext.current
        val entryPoint = EntryPointAccessors.fromApplication(context, ScreensEntryPoint::class.java)
        val slamManager = entryPoint.slamManager()
        val projectRepository = entryPoint.projectRepository()

        val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

        Box(Modifier.fillMaxSize()) {
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

            EditorUi(
                actions = editorViewModel,
                uiState = editorUiState,
                isTouchLocked = mainUiState.isTouchLocked,
                showUnlockInstructions = mainUiState.showUnlockInstructions,
                isCapturingTarget = mainUiState.isCapturingTarget
            )
        }
    }
}

// Mockup Screen
@Az(rail = RailItem(id = "mockup", text = "Mockup", parent = "hidden_host"))
@Composable
fun MockupScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)

    val editorUiState by editorViewModel.uiState.collectAsState()
    val mainUiState by mainViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        editorViewModel.setEditorMode(com.hereliesaz.graffitixr.common.model.EditorMode.STATIC)
    }

    Box(Modifier.fillMaxSize()) {
        EditorMockupScreen(uiState = editorUiState, viewModel = editorViewModel)
        EditorUi(
            actions = editorViewModel,
            uiState = editorUiState,
            isTouchLocked = mainUiState.isTouchLocked,
            showUnlockInstructions = mainUiState.showUnlockInstructions,
            isCapturingTarget = mainUiState.isCapturingTarget
        )
    }
}

// Trace Screen
@Az(rail = RailItem(id = "trace", text = "Trace", parent = "hidden_host"))
@Composable
fun TraceScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)

    val editorUiState by editorViewModel.uiState.collectAsState()
    val mainUiState by mainViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        editorViewModel.setEditorMode(com.hereliesaz.graffitixr.common.model.EditorMode.TRACE)
    }

    Box(Modifier.fillMaxSize()) {
        EditorTraceScreen(uiState = editorUiState, viewModel = editorViewModel)
        EditorUi(
            actions = editorViewModel,
            uiState = editorUiState,
            isTouchLocked = mainUiState.isTouchLocked,
            showUnlockInstructions = mainUiState.showUnlockInstructions,
            isCapturingTarget = mainUiState.isCapturingTarget
        )
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
                            // Navigate to target_evolution flow logic (handled by state in simple version)
                            // But original MainScreen navigated to "target_evolution" composable.
                            // We need to either replicate that navigation or handle it inline.
                            // Assuming inline state management for now as navigation is restricted in this context.
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

// Capture Keyframe Screen
@Az(rail = RailItem(id = "capture_keyframe", text = "Keyframe", parent = "hidden_host"))
@Composable
fun CaptureKeyframeScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val arViewModel: ArViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        arViewModel.captureKeyframe()
    }
    Text("Capturing Keyframe...")
}

// Wall Screen
@Az(rail = RailItem(id = "wall", text = "Wall", parent = "hidden_host"))
@Composable
fun WallScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    LaunchedEffect(Unit) {
        launcher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
            currentVersion = "1.0.0",
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

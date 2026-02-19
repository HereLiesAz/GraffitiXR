package com.hereliesaz.graffitixr.migrated

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.aznavrail.annotation.*
import com.hereliesaz.graffitixr.MainViewModel
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingUi
import com.hereliesaz.graffitixr.feature.ar.TargetCreationUi
import com.hereliesaz.graffitixr.feature.ar.rememberTargetCreationState
import com.hereliesaz.graffitixr.feature.ar.ui.TargetEvolutionScreen
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.MockupScreen
import com.hereliesaz.graffitixr.feature.editor.OverlayScreen
import com.hereliesaz.graffitixr.feature.editor.TraceScreen
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.saveBitmapToCache
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// AUTO-GENERATED MIGRATION FILE - INTEGRATED WITH APP LOGIC

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MigratedEntryPoint {
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

// Original ID: help
@Az(rail = RailItem(id = "help", text = "Help"))
@Composable
fun HelpScreen() {
    com.hereliesaz.graffitixr.design.components.InfoDialog(
        title = "GraffitiXR Help",
        content = "Design and project graffiti onto physical walls using AR.",
        onDismiss = { /* No-op, managed by AzNavRail info screen */ }
    )
}

// Original ID: light
@Az(rail = RailItem(id = "light", text = "Light"))
@Composable
fun LightScreen() {
    val activity  = LocalActivity.current as ComponentActivity
    val arViewModel: ArViewModel = hiltViewModel(activity)

    // Trigger the toggle only once when this screen is composed
    LaunchedEffect(Unit) {
        arViewModel.toggleFlashlight()
    }
    Box(Modifier.fillMaxSize())
}

// Original ID: lock_trace
@Az(rail = RailItem(id = "lock_trace", text = "Lock"))
@Composable
fun LockTraceScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        mainViewModel.setTouchLocked(true)
    }
    Text("Trace Locked")
}

// Original ID: mode_host
@Az(host = RailHost(id = "mode_host", text = "Modes"))
val ModeHostHost = null

// Original ID: target_host
@Az(host = RailHost(id = "target_host", text = "Target"))
val TargetHostHost = null

// Original ID: design_host
@Az(host = RailHost(id = "design_host", text = "Design"))
val DesignHostHost = null

// Original ID: project_host
@Az(host = RailHost(id = "project_host", text = "Project"))
val ProjectHostHost = null

// Original ID: ar
@Az(rail = RailItem(id = "ar", text = "AR", parent = "mode_host", home = true))
@Composable
fun ArScreen() {
    PermissionWrapper {
        val context = LocalContext.current
        val entryPoint = EntryPointAccessors.fromApplication(context, MigratedEntryPoint::class.java)
        val slamManager = entryPoint.slamManager()
        val projectRepository = entryPoint.projectRepository()

        val activity = context as ComponentActivity
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val arUiState by arViewModel.uiState.collectAsState()
        val editorViewModel: EditorViewModel = hiltViewModel(activity)
        val editorUiState by editorViewModel.uiState.collectAsState()

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
            OverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
        }
    }
}

// Original ID: overlay
@Az(rail = RailItem(id = "overlay", text = "Overlay", parent = "mode_host"))
@Composable
fun OverlayScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val editorUiState by editorViewModel.uiState.collectAsState()

    PermissionWrapper {
        val context = LocalContext.current
        val entryPoint = EntryPointAccessors.fromApplication(context, MigratedEntryPoint::class.java)
        val slamManager = entryPoint.slamManager()
        val projectRepository = entryPoint.projectRepository()
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val arUiState by arViewModel.uiState.collectAsState()
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
            OverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
        }
    }
}

// Original ID: mockup
@Az(rail = RailItem(id = "mockup", text = "Mockup", parent = "mode_host"))
@Composable
fun MockupScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val editorUiState by editorViewModel.uiState.collectAsState()

    MockupScreen(uiState = editorUiState, viewModel = editorViewModel)
}

// Original ID: trace
@Az(rail = RailItem(id = "trace", text = "Trace", parent = "mode_host"))
@Composable
fun TraceScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val editorUiState by editorViewModel.uiState.collectAsState()

    TraceScreen(uiState = editorUiState, viewModel = editorViewModel)
}

// Original ID: create
@Az(rail = RailItem(id = "create", text = "Create", parent = "target_host"))
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

// Original ID: surveyor
@Az(rail = RailItem(id = "surveyor", text = "Survey", parent = "target_host"))
@Composable
fun SurveyorScreen() {
    PermissionWrapper {
        MappingUi(
            onBackClick = { /* Handle via rail */ },
            onScanComplete = { /* Handle complete */ }
        )
    }
}

// Original ID: capture_keyframe
@Az(rail = RailItem(id = "capture_keyframe", text = "Keyframe", parent = "target_host"))
@Composable
fun CaptureKeyframeScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val arViewModel: ArViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        arViewModel.captureKeyframe()
    }
    Text("Capturing Keyframe...")
}

// Original ID: wall
@Az(rail = RailItem(id = "wall", text = "Wall", parent = "design_host"))
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

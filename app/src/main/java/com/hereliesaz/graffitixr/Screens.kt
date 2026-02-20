package com.hereliesaz.graffitixr

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
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
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog
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
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                    (androidx.core.content.ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                context, Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms[Manifest.permission.CAMERA] == true &&
                (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true)
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

// Keyframe Screen (Action)
@Az(rail = RailItem(id = "capture_keyframe", text = "Keyframe", parent = "target"))
@Composable
fun KeyframeScreen() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val arViewModel: ArViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        arViewModel.captureKeyframe()
        navController.popBackStack()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Capturing Keyframe...")
    }
}

// Light Screen (Toggle Action)
@Az(rail = RailItem(id = "light", text = "Light"))
@Composable
fun LightScreen() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val arViewModel: ArViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        arViewModel.toggleFlashlight()
        navController.popBackStack()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Toggling Light...")
    }
}

// Lock Trace Screen (Action)
@Az(rail = RailItem(id = "lock_trace", text = "Lock Trace", parent = "modes"))
@Composable
fun LockTraceScreen() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        mainViewModel.setTouchLocked(true)
        navController.popBackStack()
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Locking Touch...")
    }
}

// Save Project Screen
@Az(rail = RailItem(id = "save_project", text = "Save", parent = "project"))
@Composable
fun SaveProjectScreen() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val currentProjectName by editorViewModel.currentProjectName.collectAsState()

    SaveProjectDialog(
        initialName = currentProjectName,
        onDismissRequest = { navController.popBackStack() },
        onSaveRequest = { name ->
            editorViewModel.saveProject(name)
            navController.popBackStack()
        }
    )
}

// Export Project Screen
@Az(rail = RailItem(id = "export_project", text = "Export", parent = "project"))
@Composable
fun ExportProjectScreen() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Export Project", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = {
                editorViewModel.exportProject()
                navController.popBackStack()
            }) {
                Text("Confirm Export")
            }
            Button(onClick = { navController.popBackStack() }) {
                Text("Cancel")
            }
        }
    }
}

// Define Hosts
// Trying 'host' parameter instead of 'railHost'
@Az(host = RailHost(id = "modes", text = "Modes"))
val ModesHost = null

@Az(host = RailHost(id = "target", text = "Target"))
val TargetHost = null

@Az(host = RailHost(id = "design", text = "Design"))
val DesignHost = null

@Az(host = RailHost(id = "project", text = "Project"))
val ProjectHost = null

// Help Screen
@Az(rail = RailItem(id = "help", text = "Help"))
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

        // Overlays
        com.hereliesaz.graffitixr.design.components.TouchLockOverlay(
            isLocked = mainUiState.isTouchLocked,
            onUnlockRequested = { mainViewModel.setTouchLocked(false) }
        )
    }
}

// AR Screen (Default/Home)
@Az(rail = RailItem(id = "ar", text = "AR", parent = "modes", home = true))
@Composable
fun ArScreen() {
    PermissionWrapper {
        val activity = LocalActivity.current as ComponentActivity
        val mainViewModel: MainViewModel = hiltViewModel(activity)
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val arUiState by arViewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            mainViewModel.setCurrentScreen("ar")
        }

        BaseEditorScreen(EditorMode.AR) { editorViewModel, editorUiState ->
            // ArView is now in GlobalBackground

            if (!arUiState.isTargetDetected && editorUiState.layers.isNotEmpty()) {
                EditorOverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
            }
        }
    }
}

// Overlay Screen
@Az(rail = RailItem(id = "overlay", text = "Overlay", parent = "modes"))
@Composable
fun OverlayScreen() {
    PermissionWrapper {
        val activity = LocalActivity.current as ComponentActivity
        val mainViewModel: MainViewModel = hiltViewModel(activity)
        val arViewModel: ArViewModel = hiltViewModel(activity)
        val arUiState by arViewModel.uiState.collectAsState()

        LaunchedEffect(Unit) {
            mainViewModel.setCurrentScreen("overlay")
        }

        BaseEditorScreen(EditorMode.OVERLAY) { editorViewModel, editorUiState ->
            // ArView is now in GlobalBackground
            EditorOverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
        }
    }
}

// Mockup Screen
@Az(rail = RailItem(id = "mockup", text = "Mockup", parent = "modes"))
@Composable
fun MockupScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        mainViewModel.setCurrentScreen("mockup")
    }

    BaseEditorScreen(EditorMode.STATIC) { editorViewModel, editorUiState ->
        EditorMockupScreen(uiState = editorUiState, viewModel = editorViewModel)
    }
}

// Trace Screen
@Az(rail = RailItem(id = "trace", text = "Trace", parent = "modes"))
@Composable
fun TraceScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)

    LaunchedEffect(Unit) {
        mainViewModel.setCurrentScreen("trace")
    }

    BaseEditorScreen(EditorMode.TRACE) { editorViewModel, editorUiState ->
        EditorTraceScreen(uiState = editorUiState, viewModel = editorViewModel)
    }
}

// Create Screen
@Az(rail = RailItem(id = "create", text = "Create", parent = "target"))
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

        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            mainViewModel.setCurrentScreen("create")
            mainViewModel.startTargetCapture()
        }

        if (mainUiState.captureStep == CaptureStep.NONE) {
            // Flow finished
            Text("Target Created.")
        } else {
            Box(Modifier.fillMaxSize()) {
                // Background (Camera) is now in GlobalBackground

                // UI Overlay (Buttons, Guides)
                TargetCreationUi(
                    uiState = arUiState,
                    isRightHanded = editorUiState.isRightHanded,
                    captureStep = mainUiState.captureStep,
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
                        scope.launch(Dispatchers.IO) {
                            val extracted = ImageProcessor.detectEdges(maskedBitmap) ?: maskedBitmap
                            withContext(Dispatchers.Main) {
                                arViewModel.setTempCapture(extracted)
                                mainViewModel.setCaptureStep(CaptureStep.REVIEW)
                            }
                        }
                    },
                    onRequestCapture = { arViewModel.requestCapture() },
                    onUpdateUnwarpPoints = { arViewModel.updateUnwarpPoints(it) },
                    onSetActiveUnwarpPoint = { arViewModel.setActiveUnwarpPointIndex(it) },
                    onSetMagnifierPosition = { arViewModel.setMagnifierPosition(it) },
                    onUpdateMaskPath = { arViewModel.setMaskPath(it) }
                )
            }
        }
    }
}

// Surveyor Screen
@Az(rail = RailItem(id = "surveyor", text = "Survey", parent = "target"))
@Composable
fun SurveyorScreen() {
    PermissionWrapper {
        val activity = LocalActivity.current as ComponentActivity
        val mainViewModel: MainViewModel = hiltViewModel(activity)

        LaunchedEffect(Unit) {
            mainViewModel.setCurrentScreen("surveyor")
        }

        MappingUi(
            onBackClick = { /* Handle via rail */ },
            onScanComplete = { /* Handle complete */ }
        )
    }
}

// Project Library Screen
@Az(rail = RailItem(id = "project_library", text = "Library", parent = "project"))
@Composable
fun ProjectLibraryWrapper() {
    val actualNavController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val dashboardViewModel: DashboardViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val dashboardUiState by dashboardViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()

    val mainViewModel: MainViewModel = hiltViewModel(activity)
    LaunchedEffect(Unit) {
        mainViewModel.setCurrentScreen("project_library")
        dashboardViewModel.loadAvailableProjects()
    }

    Box(Modifier.fillMaxSize()) {
        ProjectLibraryScreen(
            projects = dashboardUiState.availableProjects,
            onLoadProject = {
                dashboardViewModel.openProject(it)
                // Navigate to default editor screen (AR)
                actualNavController.navigate("ar") {
                    popUpTo("project_library") { inclusive = true }
                }
            },
            onDeleteProject = { projectId ->
                dashboardViewModel.deleteProject(projectId)
            },
            onNewProject = {
                dashboardViewModel.onNewProject(editorUiState.isRightHanded)
                actualNavController.navigate("ar") {
                    popUpTo("project_library") { inclusive = true }
                }
            }
        )
    }
}

// Settings Screen
@Az(rail = RailItem(id = "settings", text = "Settings", parent = "project"))
@Composable
fun SettingsWrapper() {
    val navController = rememberNavController()
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val editorUiState by editorViewModel.uiState.collectAsState()

    val mainViewModel: MainViewModel = hiltViewModel(activity)
    LaunchedEffect(Unit) {
        mainViewModel.setCurrentScreen("settings")
    }

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

// Layers Screen
@OptIn(ExperimentalLayoutApi::class)
@Az(rail = RailItem(id = "layers", text = "Layers", parent = "design"))
@Composable
fun LayersScreen() {
    val activity = LocalActivity.current as ComponentActivity
    val editorViewModel: EditorViewModel = hiltViewModel(activity)
    val editorUiState by editorViewModel.uiState.collectAsState()

    val mainViewModel: MainViewModel = hiltViewModel(activity)
    LaunchedEffect(Unit) {
        mainViewModel.setCurrentScreen("layers")
    }

    val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.onAddLayer(it) }
    }

    val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { editorViewModel.setBackgroundImage(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Layers", style = MaterialTheme.typography.headlineMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add Layer")
            }
            if (editorUiState.editorMode == EditorMode.STATIC) {
                Button(
                    onClick = { backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Wallpaper, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Background")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(editorUiState.layers.reversed()) { index, layer ->
                val isSelected = layer.id == editorUiState.activeLayerId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { editorViewModel.onLayerActivated(layer.id) }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isEditing = editorUiState.editingLayerId == layer.id

                    if (isEditing) {
                        OutlinedTextField(
                            value = editorUiState.editingLayerName,
                            onValueChange = { editorViewModel.onUpdateLayerRenaming(it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        IconButton(onClick = {
                            editorViewModel.onConfirmLayerRenaming()
                        }) {
                            Text("OK")
                        }
                    } else {
                        Text(
                            text = layer.name,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { editorViewModel.onStartLayerRenaming(layer.id, layer.name) },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                    IconButton(onClick = {
                         // Move Up (which means earlier in the list, since reversed)
                         // Actually, standard list: 0 is bottom, last is top.
                         // We are displaying reversed (top first).
                         // If we move this item "Up" in display (index - 1), it means moving it later in the original list.
                         // Note: List is displayed reversed, so "Up" in UI corresponds to moving to a higher index (later) in the source list.
                         val layers = editorUiState.layers
                         val currentIdx = layers.indexOfFirst { it.id == layer.id }
                         if (currentIdx < layers.size - 1) {
                             val newLayers = layers.toMutableList()
                             java.util.Collections.swap(newLayers, currentIdx, currentIdx + 1)
                             editorViewModel.onLayerReordered(newLayers.map { it.id })
                         }
                    }) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                    }

                    IconButton(onClick = {
                        // Move Down
                         val layers = editorUiState.layers
                         val currentIdx = layers.indexOfFirst { it.id == layer.id }
                         if (currentIdx > 0) {
                             val newLayers = layers.toMutableList()
                             java.util.Collections.swap(newLayers, currentIdx, currentIdx - 1)
                             editorViewModel.onLayerReordered(newLayers.map { it.id })
                         }
                    }) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                    }

                    IconButton(onClick = { editorViewModel.onLayerRemoved(layer.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                    }
                }
            }
        }

        if (editorUiState.layers.isNotEmpty()) {
            Text("Actions", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                 Button(onClick = { editorViewModel.onRemoveBackgroundClicked() }) { Text("Isolate") }
                 Button(onClick = { editorViewModel.onLineDrawingClicked() }) { Text("Outline") }
                 Button(onClick = { editorViewModel.onAdjustClicked() }) { Text("Adjust") }
                 Button(onClick = { editorViewModel.onColorClicked() }) { Text("Balance") }
                 Button(onClick = { editorViewModel.onCycleBlendMode() }) { Text("Blend") }
                 Button(onClick = { editorViewModel.toggleImageLock() }) {
                     Icon(if (editorUiState.isImageLocked) Icons.Default.Lock else Icons.Default.LockOpen, contentDescription = null)
                     Text(if (editorUiState.isImageLocked) "Unlock" else "Lock")
                 }
            }
        }
    }
}

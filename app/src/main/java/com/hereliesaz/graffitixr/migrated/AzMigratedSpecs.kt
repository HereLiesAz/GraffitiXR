package com.hereliesaz.graffitixr.migrated

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.hereliesaz.aznavrail.annotation.*
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.MockupScreen
import com.hereliesaz.graffitixr.feature.editor.OverlayScreen
import com.hereliesaz.graffitixr.feature.editor.TraceScreen
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// AUTO-GENERATED MIGRATION FILE - INTEGRATED WITH APP LOGIC

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MigratedEntryPoint {
    fun slamManager(): SlamManager
    fun projectRepository(): com.hereliesaz.graffitixr.domain.repository.ProjectRepository
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
    val arViewModel: ArViewModel = hiltViewModel()

    // Trigger the toggle only once when this screen is composed
    androidx.compose.runtime.LaunchedEffect(Unit) {
        arViewModel.toggleFlashlight()
    }

    Box(Modifier.fillMaxSize())
}

// Original ID: lock_trace
@Az(rail = RailItem(id = "lock_trace", text = "Lock"))
@Composable
fun LockTraceScreen() {
    // Logic to lock trace mode.
    // This typically interacted with MainViewModel in the old code.
    // We might need to inject MainViewModel here if possible.
    Text("Trace Locked")
}

// Original ID: mode_host
@Az(railHost = RailHost(id = "mode_host", text = "Modes"))
val ModeHostHost = null

// Original ID: target_host
@Az(railHost = RailHost(id = "target_host", text = "Target"))
val TargetHostHost = null

// Original ID: design_host
@Az(railHost = RailHost(id = "design_host", text = "Design"))
val DesignHostHost = null

// Original ID: project_host
@Az(railHost = RailHost(id = "project_host", text = "Project"))
val ProjectHostHost = null

// Original ID: ar
@Az(rail = RailItem(id = "ar", text = "AR", parent = "mode_host", home = true))
@Composable
fun ArScreen() {
    val context = LocalContext.current
    val entryPoint = EntryPointAccessors.fromApplication(context, MigratedEntryPoint::class.java)
    val slamManager = entryPoint.slamManager()
    val projectRepository = entryPoint.projectRepository()

    val arViewModel: ArViewModel = hiltViewModel()
    val arUiState by arViewModel.uiState.collectAsState()
    val editorViewModel: EditorViewModel = hiltViewModel()
    val editorUiState by editorViewModel.uiState.collectAsState()

    // We need the active layer from EditorViewModel
    val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

    ArView(
        viewModel = arViewModel,
        uiState = arUiState,
        slamManager = slamManager,
        projectRepository = projectRepository,
        activeLayer = activeLayer,
        onRendererCreated = { /* Handle renderer creation if needed */ },
        hasCameraPermission = true // Assuming permission is handled by AzNavRail or wrapper
    )
}

// Original ID: overlay
@Az(rail = RailItem(id = "overlay", text = "Overlay", parent = "mode_host"))
@Composable
fun OverlayScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    val editorUiState by editorViewModel.uiState.collectAsState()

    OverlayScreen(uiState = editorUiState, viewModel = editorViewModel)
}

// Original ID: mockup
@Az(rail = RailItem(id = "mockup", text = "Mockup", parent = "mode_host"))
@Composable
fun MockupScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    val editorUiState by editorViewModel.uiState.collectAsState()

    MockupScreen(uiState = editorUiState, viewModel = editorViewModel)
}

// Original ID: trace
@Az(rail = RailItem(id = "trace", text = "Trace", parent = "mode_host"))
@Composable
fun TraceScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    val editorUiState by editorViewModel.uiState.collectAsState()

    TraceScreen(uiState = editorUiState, viewModel = editorViewModel)
}

// Original ID: create
@Az(rail = RailItem(id = "create", text = "Create", parent = "target_host"))
@Composable
fun CreateScreen() {
    // Target creation flow
    // This was previously handled by MainViewModel state.
    // For migration, we might need to invoke a specialized screen.
    // Assuming we can trigger it here.
    Text("Create Target Flow")
}

// Original ID: surveyor
@Az(rail = RailItem(id = "surveyor", text = "Survey", parent = "target_host"))
@Composable
fun SurveyorScreen() {
    MappingUi(
        onBackClick = { /* Handle back */ },
        onScanComplete = { /* Handle complete */ }
    )
}

// Original ID: capture_keyframe
@Az(rail = RailItem(id = "capture_keyframe", text = "Keyframe", parent = "target_host"))
@Composable
fun CaptureKeyframeScreen() {
    val arViewModel: ArViewModel = hiltViewModel()
    arViewModel.captureKeyframe()
    Text("Capturing Keyframe...")
}

// Original ID: wall
@Az(rail = RailItem(id = "wall", text = "Wall", parent = "design_host"))
@Composable
fun WallScreen() {
    // Logic to pick background image
    Text("Pick Wall Image")
}

// Original ID: openButtonId
@Az(rail = RailItem(id = "openButtonId", text = "Add Image", parent = "design_host"))
@Composable
fun OpenbuttonidScreen() {
    // Logic to pick overlay layer
    Text("Add Layer Image")
}

// Original ID: isolate
@Az(rail = RailItem(id = "isolate", text = "Isolate", parent = "design_host"))
@Composable
fun IsolateScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.onRemoveBackgroundClicked()
    Text("Removing Background...")
}

// Original ID: outline
@Az(rail = RailItem(id = "outline", text = "Outline", parent = "design_host"))
@Composable
fun OutlineScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.onLineDrawingClicked()
    Text("Generating Outline...")
}

// Original ID: adjust
@Az(rail = RailItem(id = "adjust", text = "Adjust", parent = "design_host"))
@Composable
fun AdjustScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.onAdjustClicked()
    // The panel logic needs to be adapted to AzNavRail's view if not using overlays
    Text("Adjust Panel")
}

// Original ID: balance
@Az(rail = RailItem(id = "balance", text = "Color", parent = "design_host"))
@Composable
fun BalanceScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.onColorClicked()
    Text("Color Balance Panel")
}

// Original ID: blending
@Az(rail = RailItem(id = "blending", text = "Blend", parent = "design_host"))
@Composable
fun BlendingScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.onCycleBlendMode()
    Text("Cycling Blend Mode")
}

// Original ID: save_project
@Az(rail = RailItem(id = "save_project", text = "Save", parent = "project_host"))
@Composable
fun SaveProjectScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.saveProject()
    Text("Saving Project...")
}

// Original ID: load_project
@Az(rail = RailItem(id = "load_project", text = "Load", parent = "project_host"))
@Composable
fun LoadProjectScreen() {
    // Navigate to project library
    // This logic relies on navigation controller which AzNavRail handles.
    // If we want to show the library here:
    // val dashboardViewModel: DashboardViewModel = hiltViewModel()
    // ProjectLibraryScreen(...)
    Text("Load Project Library")
}

// Original ID: export_project
@Az(rail = RailItem(id = "export_project", text = "Export", parent = "project_host"))
@Composable
fun ExportProjectScreen() {
    val editorViewModel: EditorViewModel = hiltViewModel()
    editorViewModel.exportProject()
    Text("Exporting Project...")
}

// Original ID: settings_sub
@Az(rail = RailItem(id = "settings_sub", text = "Settings", parent = "project_host"))
@Composable
fun SettingsSubScreen() {
    // SettingsScreen(...)
    Text("Settings Screen")
}

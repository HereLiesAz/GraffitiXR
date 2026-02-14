package com.hereliesaz.graffitixr

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hereliesaz.aznavrail.*
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.design.theme.rememberNavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel(),
    dashboardViewModel: DashboardViewModel = hiltViewModel(),
    editorViewModel: EditorViewModel = hiltViewModel(),
    arViewModel: ArViewModel = hiltViewModel(),
    navController: NavHostController = rememberNavController(),
    slamManager: SlamManager? = null,
    projectRepository: ProjectRepository? = null,
    onRendererCreated: (ArRenderer) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val dashboardUiState by dashboardViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()

    val navStrings = rememberNavStrings()

    val backgroundImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { editorViewModel.setBackgroundImage(it) } }
    )

    val overlayImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { editorViewModel.onAddLayer(it) } }
    )

    AzHostActivityLayout(
        navController = navController,
        modifier = Modifier.fillMaxSize()
    ) {
        // GraffitiNavRail is an extension on AzNavHostScope
        GraffitiNavRail(
            navStrings = navStrings,
            editorUiState = editorUiState,
            editorViewModel = editorViewModel,
            viewModel = viewModel,
            arViewModel = arViewModel,
            navController = navController,
            hasCameraPermission = true, // Simplified
            requestPermissions = { /* Handle permissions */ },
            performHaptic = { /* Handle haptic */ },
            resetDialogs = { /* Handle reset */ },
            backgroundImagePicker = backgroundImagePicker,
            overlayImagePicker = overlayImagePicker,
            has3dModel = false,
            use3dBackground = false,
            onToggle3dBackground = { },
            onShowInfoScreen = { },
            onSaveProject = { editorViewModel.saveProject() }
        )

        onscreen {
            AzNavHost(
                startDestination = "project_library",
                navController = navController
            ) {
                composable("editor") {
                    EditorUi(
                        actions = editorViewModel,
                        uiState = editorUiState,
                        isTouchLocked = uiState.isTouchLocked,
                        showUnlockInstructions = uiState.showUnlockInstructions,
                        isCapturingTarget = uiState.isCapturingTarget
                    )
                }
                composable("surveyor") {
                    com.hereliesaz.graffitixr.feature.ar.MappingUi(
                        onBackClick = { navController.popBackStack() },
                        onScanComplete = { navController.popBackStack() }
                    )
                }
                composable("project_library") {
                    LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                        ProjectLibraryScreen(
                            projects = dashboardUiState.availableProjects,
                            onLoadProject = {
                                dashboardViewModel.openProject(it)
                                navController.navigate("editor") {
                                    popUpTo("project_library") { inclusive = false }
                                }
                            },
                            onDeleteProject = { projectId ->
                                dashboardViewModel.deleteProject(projectId)
                            },
                            onNewProject = {
                                navController.navigate("surveyor")
                            }
                        )
                    }
                }
            }
        }
    }
}

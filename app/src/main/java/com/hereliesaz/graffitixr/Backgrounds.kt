package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.hereliesaz.aznavrail.annotation.Az
import com.hereliesaz.aznavrail.annotation.Background
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.feature.ar.ArView
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.MappingBackground
import com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import dagger.hilt.android.EntryPointAccessors

@Composable
fun rememberHasCameraPermission(): Boolean {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                 hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return hasPermission
}

@Az(background = Background(weight = 0))
@Composable
fun GlobalBackground() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val mainViewModel: MainViewModel = hiltViewModel(activity)
    val arViewModel: ArViewModel = hiltViewModel(activity)
    val editorViewModel: EditorViewModel = hiltViewModel(activity)

    val entryPoint = EntryPointAccessors.fromApplication(context, ScreensEntryPoint::class.java)
    val slamManager = entryPoint.slamManager()
    val projectRepository = entryPoint.projectRepository()

    val mainUiState by mainViewModel.uiState.collectAsState()
    val arUiState by arViewModel.uiState.collectAsState()
    val editorUiState by editorViewModel.uiState.collectAsState()

    val currentScreen = mainUiState.currentScreen
    val captureStep = mainUiState.captureStep
    val hasPermission = rememberHasCameraPermission()

    // Decide what to render
    // Priority: Target Creation (if active) > Specific Screens

    if (currentScreen == AppScreens.CREATE || mainUiState.isCapturingTarget) {
        if (hasPermission) {
            TargetCreationBackground(
                uiState = arUiState,
                captureStep = captureStep,
                onPhotoCaptured = { bitmap ->
                    arViewModel.setTempCapture(bitmap)
                    mainViewModel.setCaptureStep(CaptureStep.RECTIFY)
                },
                onCaptureConsumed = {
                    arViewModel.onCaptureConsumed()
                },
                onInitUnwarpPoints = { points ->
                    arViewModel.updateUnwarpPoints(points)
                }
            )
        }
    } else if (currentScreen == AppScreens.SURVEYOR) {
        if (hasPermission) {
            MappingBackground(
                slamManager = slamManager,
                projectRepository = projectRepository,
                onRendererCreated = { /* Lifecycle managed internally */ }
            )
        }
    } else {
        // Default to ArView for "ar", "overlay", "trace"
        val activeLayer = editorUiState.layers.find { it.id == editorUiState.activeLayerId } ?: editorUiState.layers.firstOrNull()

        if (currentScreen == AppScreens.AR || currentScreen == AppScreens.OVERLAY || currentScreen == AppScreens.TRACE) {
            ArView(
                viewModel = arViewModel,
                uiState = arUiState,
                slamManager = slamManager,
                projectRepository = projectRepository,
                activeLayer = activeLayer,
                onRendererCreated = { },
                hasCameraPermission = hasPermission
            )
        }
    }
}

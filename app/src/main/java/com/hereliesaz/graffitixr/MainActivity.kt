package com.hereliesaz.graffitixr

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme

/**
 * The main and only Activity for the GraffitiXR application.
 *
 * This Activity serves as the entry point for the application. Its primary responsibilities are:
 * 1.  Handling essential runtime permissions, specifically for the camera.
 * 2.  Setting up the Jetpack Compose content with the application's theme.
 * 3.  Displaying the main UI of the app once permissions are granted.
 */
@OptIn(ExperimentalPermissionsApi::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make the app full-screen to draw behind system bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            GraffitiXRTheme {
                // Manages the state of the camera permission.
                val permissionStates = rememberMultiplePermissionsState(
                    listOf(Manifest.permission.CAMERA)
                )
                if (permissionStates.allPermissionsGranted) {
                    // If permissions are granted, show the main application screen.
                    MainScreen()
                } else {
                    // If permissions are not granted, show a rationale and a request button.
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Camera permission is required to use this application.")
                        Button(onClick = { permissionStates.launchMultiplePermissionRequest() }) {
                            Text("Request Permission")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The main screen composable, which orchestrates the entire UI of the application.
 *
 * This composable function sets up the overall layout, including the camera view,
 * the navigation rail, popups, and dialogs. It observes the [UiState] from the
 * [MainViewModel] and recomposes in response to state changes.
 *
 * @param viewModel The [MainViewModel] instance that holds the application's state and business logic.
 */
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val navRailColor = Color.hsl(uiState.hue, 1f, uiState.lightness)

    // Activity result launcher for selecting the main overlay image.
    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.onSelectImage(uri)
    }

    // Activity result launcher for selecting the background image for static mode.
    val backgroundLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        viewModel.onSelectBackgroundImage(uri)
    }

    // A LaunchedEffect that shows a snackbar whenever a new message is available in the state.
    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            // Notify the ViewModel that the message has been shown, so it doesn't reappear.
            viewModel.onSnackbarMessageShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // The primary content view, which crossfades between different editor modes.
            Crossfade(targetState = uiState.editorMode, label = "Mode Switcher") { mode ->
                when (mode) {
                    EditorMode.AR -> ArModeScreen(viewModel = viewModel)
                    EditorMode.NON_AR -> NonArModeScreen(uiState = uiState)
                    EditorMode.STATIC_IMAGE -> StaticImageEditor(uiState, viewModel)
                }
            }

            // The main navigation rail on the side of the screen.
            Row(modifier = Modifier.padding(padding)) {
                AzNavRail {
                    azSettings(isLoading = uiState.isProcessing)
                    azRailItem(id = "select_image", text = "Image", color = if (uiState.imageUri != null) Color.Green else navRailColor) {
                        imageLauncher.launch("image/*")
                    }
                    azRailItem(id = "select_bg_image", text = "BG Image", color = if (uiState.backgroundImageUri != null) Color.Green else navRailColor) {
                        backgroundLauncher.launch("image/*")
                    }
                    azRailItem(id = "settings", text = "Settings", color = navRailColor) {
                        viewModel.onSettingsClicked(true)
                    }
                    azRailItem(id = "remove_bg", text = "Remove BG", color = navRailColor) {
                        viewModel.onRemoveBg()
                    }
                    azRailItem(id = "clear", text = "Clear", color = navRailColor) {
                        viewModel.onClear()
                    }

                    // Mode selection buttons
                    azRailItem(
                        id = "mode_ar",
                        text = "AR",
                        color = if (uiState.editorMode == EditorMode.AR) navRailColor else Color.Gray
                    ) {
                        viewModel.onEditorModeChange(EditorMode.AR)
                    }
                    azRailItem(
                        id = "mode_non_ar",
                        text = "Cam",
                        color = if (uiState.editorMode == EditorMode.NON_AR) navRailColor else Color.Gray
                    ) {
                        viewModel.onEditorModeChange(EditorMode.NON_AR)
                    }
                    azRailItem(
                        id = "mode_static",
                        text = "Mockup",
                        color = if (uiState.editorMode == EditorMode.STATIC_IMAGE) navRailColor else Color.Gray
                    ) {
                        viewModel.onEditorModeChange(EditorMode.STATIC_IMAGE)
                    }

                    // Dynamically create a nav item for each slider type.
                    SliderType.values().forEach { sliderType ->
                        azRailItem(
                            id = sliderType.name,
                            text = sliderType.name,
                            color = if (uiState.activeSlider == sliderType) navRailColor else Color.Gray,
                        ) {
                            // If the same slider is clicked again, dismiss it. Otherwise, select it.
                            val newSlider = if (uiState.activeSlider == sliderType) null else sliderType
                            viewModel.onSliderSelected(newSlider)
                        }
                    }
                }
            }

            // Show AR guidance message if no planes are detected for a while.
            if (uiState.showARGuidance) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 100.dp), // Position it above the bottom edge
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = "Move phone slowly to scan for surfaces",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .background(
                                color = Color.Black.copy(alpha = 0.5f),
                                shape = MaterialTheme.shapes.medium
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            // Container for the animated slider panel at the bottom of the screen.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                AnimatedVisibility(
                    visible = uiState.activeSlider != null,
                    enter = slideInVertically(initialOffsetY = { it }),
                    exit = slideOutVertically(targetOffsetY = { it })
                ) {
                    // A let block is safe here because visibility is tied to activeSlider != null.
                    uiState.activeSlider?.let { sliderType ->
                        AdjustmentSliderPanel(
                            sliderType = sliderType,
                            uiState = uiState,
                            viewModel = viewModel
                        )
                    }
                }
            }

            // A semi-transparent overlay and a progress indicator shown during long operations.
            if (uiState.isProcessing) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Show the settings screen if it's active in the state.
            if (uiState.showSettings) {
                SettingsScreen(
                    hue = uiState.hue,
                    onHueChange = viewModel::onHueChange,
                    lightness = uiState.lightness,
                    onLightnessChange = viewModel::onLightnessChange,
                    onDismiss = { viewModel.onSettingsClicked(false) }
                )
            }
        }
    }
}

/**
 * A modal popup dialog that displays a single slider for adjusting an image property.
 *
 * The popup darkens the background and presents a card containing the slider and a close button.
 *
 * @param sliderType The [SliderType] that determines which property this slider controls.
 * @param uiState The current [UiState] of the application, used to get the current value for the slider.
 * @param viewModel The [MainViewModel] instance, used to invoke callbacks when the slider value changes.
 * @param onDismiss A lambda function to be called when the user clicks the "Close" button.
 */
@Composable
fun SliderPopup(
    sliderType: SliderType,
    uiState: UiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.padding(32.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = sliderType.name, style = MaterialTheme.typography.headlineSmall)
                // Display the correct slider based on the sliderType.
                when (sliderType) {
                    SliderType.Opacity -> Slider(value = uiState.opacity, onValueChange = viewModel::onOpacityChange)
                    SliderType.Contrast -> Slider(value = uiState.contrast, onValueChange = viewModel::onContrastChange, valueRange = 0f..10f)
                    SliderType.Saturation -> Slider(value = uiState.saturation, onValueChange = viewModel::onSaturationChange, valueRange = 0f..10f)
                    SliderType.Brightness -> Slider(value = uiState.brightness, onValueChange = viewModel::onBrightnessChange, valueRange = -1f..1f)
                }
                Button(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    }
}
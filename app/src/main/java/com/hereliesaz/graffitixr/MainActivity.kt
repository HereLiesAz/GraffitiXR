// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import android.content.ClipData
import android.content.ClipboardManager as AndroidClipboardManager
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.common.GoogleApiAvailability
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.aznavrail.tutorial.LocalAzTutorialController
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.common.model.Tool
import com.hereliesaz.graffitixr.common.model.ArUiState
import com.hereliesaz.graffitixr.common.security.SecurityProviderManager
import com.hereliesaz.graffitixr.common.security.SecurityProviderState
import com.hereliesaz.graffitixr.common.util.ImageProcessor
import com.hereliesaz.graffitixr.design.components.InfoDialog
import com.hereliesaz.graffitixr.design.components.PosterOptionsDialog
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import com.hereliesaz.graffitixr.design.theme.Cyan
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.design.theme.HotPink
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.feature.ar.TargetCreationBackground
import com.hereliesaz.graffitixr.feature.ar.TargetCreationUi
import com.hereliesaz.graffitixr.feature.ar.rememberCameraController
import com.hereliesaz.graffitixr.feature.dashboard.DashboardViewModel
import com.hereliesaz.graffitixr.feature.dashboard.ProjectLibraryScreen
import com.hereliesaz.graffitixr.feature.dashboard.SaveProjectDialog
import com.hereliesaz.graffitixr.feature.dashboard.SettingsScreen
import com.hereliesaz.graffitixr.feature.dashboard.SettingsViewModel
import com.hereliesaz.graffitixr.feature.editor.EditorUi
import com.hereliesaz.graffitixr.feature.editor.EditorViewModel
import com.hereliesaz.graffitixr.feature.editor.util.ImageProcessor as EditorImageProcessor
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.common.model.RelocState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import kotlinx.coroutines.delay
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.core.net.toUri
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository
    @Inject lateinit var securityProviderManager: SecurityProviderManager

    private val arViewModel: ArViewModel by viewModels()

    var showSaveDialog by mutableStateOf(false)
    var showLibrary by mutableStateOf(true)
    var showSettings by mutableStateOf(false)
    var showPosterDialog by mutableStateOf(false)
    var posterSourceLayerId by mutableStateOf<String?>(null)
    var hasCameraPermission by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[Manifest.permission.CAMERA] ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        securityProviderManager.installAsync(this)
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

                @Suppress("DEPRECATION")
                val mainViewModel: MainViewModel = hiltViewModel()
                @Suppress("DEPRECATION")
                val editorViewModel: EditorViewModel = hiltViewModel()
                @Suppress("DEPRECATION")
                val dashboardViewModel: DashboardViewModel = hiltViewModel()
                @Suppress("DEPRECATION")
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val cameraController = rememberCameraController()

                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val arUiState by arViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()
                val completedTutorials by settingsViewModel.completedTutorials.collectAsState()

                var isProcessing by remember { mutableStateOf(false) }

                val currentTempCapture = arUiState.tempCaptureBitmap
                val currentCaptureStep = mainUiState.captureStep
                val isWaitingForTap = mainUiState.isWaitingForTap

                LaunchedEffect(currentTempCapture, currentCaptureStep, isWaitingForTap) {
                    if (currentTempCapture != null) {
                        if (currentCaptureStep == CaptureStep.CAPTURE) {
                            mainViewModel.setCaptureStep(CaptureStep.REVIEW)
                        } else if (isWaitingForTap) {
                            mainViewModel.confirmTapCapture()
                        }
                    }
                }

                // Task 5: AR tap → auto-confirm. Skip the REVIEW step when the capture
                // originated from a screen tap (not the manual "Create Anchor" rail button).
                LaunchedEffect(currentCaptureStep, mainUiState.captureOriginatedFromTap) {
                    if (currentCaptureStep == CaptureStep.REVIEW && mainUiState.captureOriginatedFromTap) {
                        arViewModel.setInitialAnchorFromCapture()
                        mainViewModel.onConfirmTargetCreation(
                            bitmap        = arUiState.tempCaptureBitmap,
                            selectionMask = null,
                            depthBuffer   = arUiState.targetDepthBuffer,
                            depthW        = arUiState.targetDepthBufferWidth,
                            depthH        = arUiState.targetDepthBufferHeight,
                            depthStride   = arUiState.targetDepthStride,
                            intrinsics    = arUiState.targetIntrinsics,
                            viewMatrix    = arUiState.targetCaptureViewMatrix
                        )
                    }
                }

                LaunchedEffect(dashboardNavigation) {
                    dashboardNavigation?.let { destination ->
                        when (destination) {
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
                            } catch (_: Exception) {
                                // Intentional: NavController iterates all destinations including non-EditorMode routes;
                                // IllegalArgumentException from EditorMode.valueOf() on those routes is expected and safe to discard.
                            }
                        }
                    }
                }

                LaunchedEffect(mainUiState.isTouchLocked) {
                    if (mainUiState.isTouchLocked) {
                        val params = window.attributes
                        params.screenBrightness = 1.0f
                        window.attributes = params
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        val params = window.attributes
                        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = params
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                // If the anchor is lost while confirmation is pending (e.g. project reload),
                // auto-clear so the hidden rail is never left with no escape path.
                LaunchedEffect(arUiState.isAnchorEstablished) {
                    if (!arUiState.isAnchorEstablished && mainViewModel.uiState.value.planeConfirmationPending) {
                        mainViewModel.confirmPlane()
                    }
                }

                // Task 4e: Mark tutorials complete on meaningful actions.
                LaunchedEffect(arUiState.isAnchorEstablished) {
                    if (arUiState.isAnchorEstablished) {
                        settingsViewModel.markTutorialComplete("tut_ar")
                    }
                }
                LaunchedEffect(editorUiState.layers.size) {
                    if (editorUiState.layers.isNotEmpty()) {
                        settingsViewModel.markTutorialComplete("tut_design")
                        settingsViewModel.markTutorialComplete("tut_${editorUiState.editorMode.name.lowercase()}")
                    }
                }

                // Back-press escape hatches — defined lowest-priority first (Compose uses LIFO).
                BackHandler(enabled = showLibrary) { showLibrary = false }
                BackHandler(enabled = showSettings) { showSettings = false }
                BackHandler(enabled = mainUiState.planeConfirmationPending && !mainUiState.isInPlaneRealignment) {
                    mainViewModel.confirmPlane()
                }
                BackHandler(enabled = mainUiState.isInPlaneRealignment) {
                    mainViewModel.endPlaneRealignment()
                }
                BackHandler(enabled = mainUiState.isCapturingTarget) {
                    mainViewModel.cancelTapMode()
                    arViewModel.clearTapHighlights()
                }
                BackHandler(enabled = mainUiState.isTouchLocked) {
                    mainViewModel.setTouchLocked(false)
                }

                val isRailVisible = !editorUiState.hideUiForCapture &&
                        !mainUiState.isTouchLocked &&
                        !mainUiState.isCapturingTarget &&
                        !mainUiState.planeConfirmationPending &&
                        !showLibrary &&
                        !showSettings

                var permissionRequestedAtLeastOnce by remember { mutableStateOf(hasCameraPermission) }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
                        )
                    }
                    permissionRequestedAtLeastOnce = true
                }

                // Keep ArUiState in sync with the runtime camera permission so AR
                // overlays can react to it without receiving the raw flag directly.
                LaunchedEffect(hasCameraPermission) {
                    arViewModel.setCameraPermission(hasCameraPermission)
                }

                LaunchedEffect(editorUiState.editorMode) {
                    if (editorUiState.editorMode == EditorMode.STENCIL) {
                        editorViewModel.setEditorMode(EditorMode.MOCKUP)
                    }
                }

                LaunchedEffect(Unit) {
                    arViewModel.unfreezeRequested.collect {
                        editorViewModel.toggleImageLock()
                    }
                }

                val overlayImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { editorViewModel.onAddLayer(it) }
                }
                val backgroundImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    uri?.let { editorViewModel.setBackgroundImage(it) }
                }

                // Task 6: Auto-open image picker once anchor is established and no layers exist yet.
                // Guard on !planeConfirmationPending so it fires after plane confirm, not during.
                LaunchedEffect(arUiState.isAnchorEstablished, mainUiState.planeConfirmationPending) {
                    if (arUiState.isAnchorEstablished
                        && !mainUiState.planeConfirmationPending
                        && editorUiState.layers.isEmpty()
                    ) {
                        overlayImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }

                val navStrings = remember { NavStrings() }
                var showFontPicker by remember { mutableStateOf(false) }
                var fontPickerLayerId by remember { mutableStateOf<String?>(null) }
                val layerMenusOpen = remember { mutableStateMapOf<String, Boolean>() }

                val context = LocalContext.current
                val canvasBg = editorUiState.canvasBackground
                val navItemColor = remember(canvasBg) {
                    Color(1f - canvasBg.red, 1f - canvasBg.green, 1f - canvasBg.blue, alpha = 1f)
                }

                val mainHelpItems = remember(editorUiState.layers) {
                    val base = mutableMapOf(
                        // ─── Mode Menu ──────────────────────────────────────────────────
                        "mode_host" to "Mode Switcher: Switch between app modes. Instructions: Tap to expand, then select AR, Overlay, Mockup, or Lightbox.",
                        "ar" to "AR Mode: Project design onto a wall using spatial mapping. Instructions: Scan environment until wall is detected, then tap to place the anchor.",
                        "overlay" to "Overlay Mode: Display design over live camera without spatial tracking. Instructions: Manually scale and move the design to fit your frame.",
                        "mockup" to "Mockup Mode: Preview design on a static photo. Instructions: Use 'Wall' to set a background image, then arrange layers on top.",
                        "trace" to "Lightbox Mode: High-brightness tracing surface. Instructions: Place paper over the screen and trace. Triple-tap anywhere to exit.",

                        // ─── Target Menu ────────────────────────────────────────────────
                        "target_host" to "Target Management: Tools for AR anchors. Instructions: Tap to access tools for creating or re-aligning the mural's position.",
                        "scan_mode_toggle" to "Scan Resolution: Switch between Mural (large scale) and Canvas (small scale). Mural uses Gaussian Splats for walls; Canvas uses Point Clouds for detailed desk-scale art.",
                        "create" to "Create Anchor: Photograph a mark on the wall to lock projection. Instructions: Point at a distinct texture and tap. The app will track this mark.",

                        // ─── Design Menu ────────────────────────────────────────────────
                        "design_host" to "Layer Management: Add images, text, or sketches. Instructions: Tap to expand. Long-press any layer button to open its secondary menu.",
                        "add_img" to "Import Image: Add a sketch or reference photo. Instructions: Select an image from your gallery. It will appear as a new design layer.",
                        "add_draw" to "New Sketch: Add a blank layer for freehand drawing. Instructions: Select the layer to reveal brush, eraser, and smudge tools.",
                        "add_text" to "Add Text: Create a typography layer. Instructions: Tap the layer's 'Edit' button to change content and formatting.",
                        "wall" to "Change Wall: Set background for Mockup mode. Instructions: Select a photo of the physical wall you plan to paint on.",

                        // ─── Project Menu ───────────────────────────────────────────────
                        "project_host" to "Project Management: Save, load, and export work. Instructions: Tap to access administrative tasks for your mural.",
                        "new" to "New Project: Start a fresh mural. Instructions: Tap to reset. Warning: This clears all current work. Save first!",
                        "save" to "Save Project: Store mural and AR map. Instructions: Enter a project name and tap Save to preserve your progress.",
                        "load" to "Project Library: Access saved murals. Instructions: Browse the library to resume work or import shared project files.",
                        "export" to "Export Image: Save high-res snapshot. Instructions: Renders all visible layers into a single PNG in your photo gallery.",
                        "settings" to "App Settings: Configure preferences. Instructions: Open to adjust handedness, units, and scanning parameters.",

                        // ─── Global Tools ───────────────────────────────────────────────
                        "light" to "Flashlight: Toggle device LED. Instructions: Use this to illuminate dark walls for better AR tracking.",
                        "lock_trace" to "Lock/Freeze: Prevent accidental changes. Instructions: In Trace mode, disables screen. In other modes, locks layer transforms.",
                        "help_main" to "Interactive Help: Explains button functions. Instructions: Tap to activate (Cyan). While active, tap any button to see its help text."
                    )

                    editorUiState.layers.forEach { layer ->
                        base["layer_${layer.id}"] = "Layer '${layer.name}': Active mural element. Instructions: Tap to select. Drag button up/down to reorder. Transform on screen with gestures."
                    }
                    base
                }

                val nestedHelpItems = remember(editorUiState.layers) {
                    val base = mutableMapOf<String, String>()
                    editorUiState.layers.forEach { layer ->
                        val id = layer.id
                        base["edit_text_$id"] = "Edit Text: Change words in typography layer. Instructions: Tap to open the keyboard and update the text content."
                        base["size_$id"] = "Brush/Text Size: Adjust scale and softness. Instructions: Drag up/down for size. Drag left/right for brush feathering."
                        base["font_$id"] = "Font Picker: Select typography style. Instructions: Tap to browse available fonts and apply them to the text layer."
                        base["color_$id"] = "Color Tool: Set active color. Instructions: Drag up/down for brightness, left/right for saturation. Tap for full color wheel."
                        base["kern_$id"] = "Text Kerning: Adjust letter spacing. Instructions: Drag left/right to tighten or loosen the text layout."
                        base["bold_$id"] = "Bold Toggle: Thick font weight. Instructions: Tap to toggle bold styling on the active text layer."
                        base["italic_$id"] = "Italic Toggle: Slanted styling. Instructions: Tap to toggle italic styling on the active text layer."
                        base["outline_$id"] = "Text Outline: Character stroke. Instructions: Tap to toggle a visible outline around the text characters."
                        base["shadow_$id"] = "Drop Shadow: Text depth. Instructions: Tap to toggle a soft shadow behind the typography for better legibility."
                        base["stencil_$id"] = "Generate Stencil: Create printable templates. Instructions: Tap to begin multi-layer stencil generation for this image."
                        base["blend_$id"] = "Blend Mode: Composite style. Instructions: Tap to cycle through modes like Multiply, Screen, and Overlay."
                        base["adj_$id"] = "Image Adjustments: Fine-tune appearance. Instructions: Tap to reveal sliders for Opacity, Brightness, Contrast, and Saturation."
                        base["invert_$id"] = "Invert Colors: High-contrast negative. Instructions: Tap to flip colors. Whites become black and vice-versa—useful for tracing."
                        base["balance_$id"] = "Color Balance: RGB channel tuning. Instructions: Tap to reveal sliders for precise Red, Green, and Blue adjustment."
                        base["eraser_$id"] = "Eraser Brush: Remove layer content. Instructions: Paint on screen to erase. Use 'Size' button to adjust diameter."
                        base["blur_$id"] = "Blur/Smudge Tool: Blend colors. Instructions: Paint over regions to soften edges or smudge details together."
                        base["liquify_$id"] = "Warp Tool: Reshape design. Instructions: Drag on screen to push and pull pixels—great for adjusting proportions."
                        base["dodge_$id"] = "Dodge Tool: Lighten areas. Instructions: Paint over parts of the layer to increase their luminance."
                        base["burn_$id"] = "Burn Tool: Darken areas. Instructions: Paint over parts of the layer to decrease their luminance."
                        base["iso_$id"] = "Isolate Subject: Auto-background removal. Instructions: Tap to extract the main subject from its background using AI."
                        base["line_$id"] = "Sketch Outline: Line art filter. Instructions: Tap to convert the photo into a black-and-white transparent outline."
                        base["help_layer_$id"] = "Layer Help: Toggle info for these specific layer tools. Instructions: Tap to activate. Then tap any tool icon to see what it does."
                    }
                    base
                }

                val helpViewModel: HelpViewModel = hiltViewModel()
                val activeHelpList by helpViewModel.activeHelpList.collectAsState()

                // Logic to switch active help list based on UI context
                LaunchedEffect(editorUiState.activeLayerId) {
                    Timber.tag("GraffitiXR_Help")
                        .d("Active layer ID changed to: ${editorUiState.activeLayerId}")
                    val helpList = if (editorUiState.activeLayerId != null) nestedHelpItems else mainHelpItems
                    helpViewModel.setActiveHelpList(helpList)
                }

                // Onboarding Manager
                val onboardingManager = remember(context) { OnboardingManager(context) }
                LaunchedEffect(Unit) {
                    if (onboardingManager.isFirstTime("main_screen")) {
                        onboardingManager.markAsSeen("main_screen")
                    }
                }

                val tutorials = getTutorials(editorUiState.layers)

                AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
                    azTheme(
                        activeColor = Cyan,
                        defaultShape = AzButtonShape.RECTANGLE,
                        headerIconShape = AzHeaderIconShape.ROUNDED,
                        translucentBackground = Color.Black.copy(alpha = 0.5f)
                    )
                    azConfig(
                        packButtons = true,
                        dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT
                    )
                    azAdvanced(
                        helpEnabled = true,
                        helpList = activeHelpList,
                        onDismissHelp = { },
                        tutorials = tutorials
                    )

                    if (isRailVisible) {
                        ConfigureRailItems(
                            mainViewModel, editorViewModel, arViewModel, dashboardViewModel, context,
                            overlayImagePicker, backgroundImagePicker, editorUiState, arUiState, navStrings,
                            navItemColor = navItemColor,
                            onShowFontPicker = { layerId -> fontPickerLayerId = layerId; showFontPicker = true },
                            layerMenusOpen = layerMenusOpen
                        )
                    }

                    background(weight = 0) {
                        MainScreen(
                            uiState = editorUiState,
                            arUiState = arUiState,
                            isTouchLocked = mainUiState.isTouchLocked,
                            isCameraActive = !showLibrary,
                            isWaitingForTap = mainUiState.isWaitingForTap,
                            mainUiState = mainUiState,
                            mainViewModel = mainViewModel,
                            editorViewModel = editorViewModel,
                            arViewModel = arViewModel,
                            slamManager = slamManager,
                            hasCameraPermission = hasCameraPermission,
                            cameraController = cameraController,
                            onRendererCreated = { _ -> }
                        )

                        if (mainUiState.isCapturingTarget) {
                            TargetCreationBackground(
                                uiState = arUiState,
                                captureStep = mainUiState.captureStep,
                                onInitUnwarpPoints = { arViewModel.setUnwarpPoints(it) }
                            )
                        }
                    }

                    onscreen {
                        // Auto-show the mode tutorial on first visit using the v8.0 controller API.
                        // DataStore (completedTutorials) persists "seen" state across app restarts.
                        val tutorialController = LocalAzTutorialController.current
                        LaunchedEffect(editorUiState.editorMode, completedTutorials) {
                            val tutorialId = when (editorUiState.editorMode) {
                                EditorMode.AR      -> "ar_mode"
                                EditorMode.OVERLAY -> "overlay_mode"
                                EditorMode.MOCKUP  -> "mockup_mode"
                                EditorMode.TRACE   -> "trace_mode"
                                else               -> null
                            }
                            val key = "tut_${editorUiState.editorMode.name.lowercase()}"
                            if (tutorialId != null && key !in completedTutorials) {
                                tutorialController.startTutorial(tutorialId)
                                settingsViewModel.markTutorialComplete(key)
                            }
                        }

                        if (editorUiState.stencilHintVisible || editorUiState.isStencilGenerating) {
                            val pos = editorUiState.stencilButtonPosition
                            val density = LocalDensity.current
                            val offset = with(density) { IntOffset(pos.x.toInt() + 100.dp.roundToPx(), pos.y.toInt()) }

                            Box(Modifier.offset { offset }) {
                                if (editorUiState.isStencilGenerating) {
                                    Text("GENERATING...", color = Color.Cyan, fontWeight = FontWeight.Bold)
                                } else if (editorUiState.stencilHintVisible) {
                                    Text(
                                        "Press again to add a layer.",
                                        color = Color.White,
                                        modifier = Modifier
                                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }

                        var fullSize by remember { mutableStateOf(IntSize.Zero) }

                        Box(Modifier.fillMaxSize().onSizeChanged { fullSize = it }) {
                            AzNavHost(startDestination = EditorMode.TRACE.name) {
                                composable(EditorMode.AR.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.OVERLAY.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.MOCKUP.name) { EditorOverlay(editorViewModel, mainUiState) }
                                composable(EditorMode.TRACE.name) { EditorOverlay(editorViewModel, mainUiState) }
                            }

                            if (mainUiState.isTouchLocked) {
                                var showUnlockInstructions by remember(mainUiState.isTouchLocked) { mutableStateOf(true) }
                                LaunchedEffect(Unit) {
                                    kotlinx.coroutines.delay(3000)
                                    showUnlockInstructions = false
                                }
                                TouchLockOverlay(
                                    isLocked = true,
                                    onUnlockRequested = { mainViewModel.setTouchLocked(false) }
                                )
                                UnlockInstructionsPopup(visible = showUnlockInstructions)
                            }

                            val isScanningPhase = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.arScanMode == ArScanMode.GAUSSIAN_SPLATS
                                    && arUiState.scanPhase != ScanPhase.COMPLETE
                            if (isScanningPhase && !mainUiState.isCapturingTarget && !showLibrary && !showSettings) {
                                ScanCoachingOverlay(
                                    splatCount = arUiState.splatCount,
                                    hint = arUiState.scanHint,
                                    scanPhase = arUiState.scanPhase,
                                    ambientSectorsCovered = arUiState.ambientSectorsCovered,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 96.dp)
                                )
                            }

                            val showDepthWarning = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.arScanMode == ArScanMode.GAUSSIAN_SPLATS
                                    && !arUiState.isDepthApiSupported
                                    && arUiState.splatCount == 0
                            if (showDepthWarning && !showLibrary && !showSettings) {
                                DepthApiUnsupportedBanner(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 16.dp)
                                )
                            }

                            // Error: ARCore not installed or device not supported
                            if (editorUiState.editorMode == EditorMode.AR
                                && !arUiState.isArCoreAvailable
                                && !showLibrary && !showSettings
                            ) {
                                ArCoreUnavailableOverlay(
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }

                            // Error: camera permission permanently denied
                            if (editorUiState.editorMode == EditorMode.AR
                                && permissionRequestedAtLeastOnce
                                && !arUiState.hasCameraPermission
                                && !showLibrary && !showSettings
                            ) {
                                CameraPermissionDeniedBanner(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 16.dp)
                                )
                            }

                            if (mainUiState.isWaitingForTap && !showLibrary && !showSettings) {
                                TapTargetOverlay(
                                    onCancel = {
                                        mainViewModel.cancelTapMode()
                                        arViewModel.clearTapHighlights()
                                    },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            LaunchedEffect(mainUiState.planeConfirmationPending) {
                                arViewModel.setPlaneConfirmationBorder(mainUiState.planeConfirmationPending)
                            }

                            LaunchedEffect(arUiState.targetPhysicalExtent) {
                                arUiState.targetPhysicalExtent?.let { (w, h) ->
                                    editorViewModel.setAnchorExtent(w, h)
                                }
                            }

                            val showPlaneConfirm = mainUiState.planeConfirmationPending
                                    && !mainUiState.isInPlaneRealignment
                                    && arUiState.isAnchorEstablished
                                    && editorUiState.editorMode == EditorMode.AR
                                    && !showLibrary && !showSettings
                            if (showPlaneConfirm) {
                                PlaneConfirmOverlay(
                                    onConfirm = { mainViewModel.confirmPlane() },
                                    onRedetect = { mainViewModel.beginPlaneRealignment() },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            val showRealignment = mainUiState.isInPlaneRealignment
                                    && editorUiState.editorMode == EditorMode.AR
                                    && !showLibrary && !showSettings
                            if (showRealignment) {
                                PlaneRealignmentOverlay(
                                    onTryThisPlane = {
                                        arViewModel.retriggerPlaneDetection()
                                        mainViewModel.endPlaneRealignment()
                                    },
                                    onCancel = { mainViewModel.endPlaneRealignment() },
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            val showProgress = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.isAnchorEstablished
                                    && arUiState.paintingProgress > 0.01f
                                    && !mainUiState.isCapturingTarget
                                    && !showLibrary && !showSettings
                            if (showProgress) {
                                PaintingProgressIndicator(
                                    progress = arUiState.paintingProgress,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 16.dp, end = 16.dp)
                                )
                            }

                            val distanceM = arUiState.distanceToAnchorMeters
                            if (editorUiState.editorMode == EditorMode.AR
                                && arUiState.isAnchorEstablished
                                && distanceM > 0f
                                && !showLibrary && !showSettings
                            ) {
                                DistanceBadge(
                                    distanceMeters = distanceM,
                                    imperial = arUiState.isImperialUnits,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(top = 16.dp, start = 16.dp)
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings) {
                                RelocStatusBadge(
                                    isAnchorEstablished = arUiState.isAnchorEstablished,
                                    paintingProgress = arUiState.paintingProgress,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 16.dp, end = 16.dp)
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && editorUiState.showDiagOverlay) {
                                DiagPopup(
                                    diagLog = arUiState.diagLog,
                                    modifier = Modifier.align(Alignment.TopStart)
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings) {
                                AnchorLockFlash(isAnchorEstablished = arUiState.isAnchorEstablished)
                            }

                            OffscreenIndicators(
                                uiState = editorUiState,
                                arUiState = arUiState,
                                screenSize = fullSize
                            )

                            if (mainUiState.isCapturingTarget) {
                                TargetCreationUi(
                                    uiState = arUiState,
                                    isRightHanded = editorUiState.isRightHanded,
                                    captureStep = mainUiState.captureStep,
                                    isLoading = isProcessing,
                                    onConfirm = { bitmap, mask, depth, dw, dh, ds, intr, view ->
                                        arViewModel.setInitialAnchorFromCapture()
                                        mainViewModel.onConfirmTargetCreation(bitmap, mask, depth, dw, dh, ds, intr, view)
                                    },
                                    onRetake = {
                                        mainViewModel.onRetakeCapture()
                                        if (mainUiState.captureOriginatedFromTap) {
                                            arViewModel.clearTapHighlights()
                                        } else {
                                            arViewModel.requestCapture()
                                        }
                                    },
                                    onCancel = {
                                        mainViewModel.onCancelCaptureClicked()
                                    },
                                    onUnwarpConfirm = { points ->
                                        val currentBitmap = arUiState.tempCaptureBitmap
                                        if (currentBitmap != null && points.size == 4) {
                                            isProcessing = true
                                            lifecycleScope.launch(Dispatchers.Default) {
                                                val unwarped = ImageProcessor.unwarpImage(currentBitmap, points)
                                                if (unwarped != null) {
                                                    arViewModel.setTempCapture(unwarped)
                                                }
                                                mainViewModel.setCaptureStep(CaptureStep.MASK)
                                                isProcessing = false
                                            }
                                        } else {
                                            mainViewModel.setCaptureStep(CaptureStep.MASK)
                                        }
                                    },
                                    onMaskConfirmed = { bitmap ->
                                        arViewModel.setTempCapture(bitmap)
                                        mainViewModel.setCaptureStep(CaptureStep.REVIEW)
                                    },
                                    onRequestCapture = { arViewModel.requestCapture() },
                                    onUpdateUnwarpPoints = { arViewModel.setUnwarpPoints(it) },
                                    onSetActiveUnwarpPoint = { arViewModel.setActiveUnwarpPoint(it) },
                                    onSetMagnifierPosition = { arViewModel.setMagnifierPosition(it) },
                                    onUpdateMaskPath = { path -> path?.let { arViewModel.updateMaskPath(it) } },
                                    onBeginErase = { arViewModel.beginErase() },
                                    onEraseAtPoint = { nx, ny -> arViewModel.eraseAtPoint(nx, ny) },
                                    onUndoErase = { arViewModel.undoErase() },
                                    onRedoErase = { arViewModel.redoErase() }
                                )
                            }

                            if (showSaveDialog) {
                                SaveProjectDialog(
                                    initialName = editorUiState.projectId ?: "New Project",
                                    onDismissRequest = { showSaveDialog = false },
                                    onSaveRequest = { name ->
                                        lifecycleScope.launch {
                                            arViewModel.saveMapBlocking()
                                            arViewModel.saveCloudPointsBlocking()
                                            editorViewModel.saveProject(name)
                                            showSaveDialog = false
                                        }
                                    }
                                )
                            }

                            if (showFontPicker) {
                                FontPickerDialog(
                                    onFontSelected = { fontName ->
                                        fontPickerLayerId?.let { editorViewModel.onTextFontChanged(it, fontName) }
                                        showFontPicker = false
                                    },
                                    onDismiss = { showFontPicker = false }
                                )
                            }


                            if (showPosterDialog && posterSourceLayerId != null) {
                                PosterOptionsDialog(
                                    sourceLayerId = posterSourceLayerId!!,
                                    layers = editorUiState.layers,
                                    onDismiss = { showPosterDialog = false },
                                    onGenerate = { size, selectedIds ->
                                        editorViewModel.generatePosterPdf(selectedIds, size)
                                        showPosterDialog = false
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
                                    },
                                    onImportProject = { uri ->
                                        dashboardViewModel.importProject(uri)
                                    },
                                    onClose = { showLibrary = false }
                                )
                            }

                            if (showSettings) {
                                val dashboardUiState by dashboardViewModel.uiState.collectAsState()
                                SettingsScreen(
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    updateStatus = dashboardUiState.updateStatusMessage,
                                    isCheckingForUpdate = dashboardUiState.isCheckingForUpdate,
                                    isRightHanded = editorUiState.isRightHanded,
                                    onHandednessChanged = { editorViewModel.toggleHandedness() },
                                    showDiagOverlay = editorUiState.showDiagOverlay,
                                    onDiagOverlayChanged = { editorViewModel.toggleDiagOverlay() },
                                    arScanMode = arUiState.arScanMode,
                                    onArScanModeChanged = { arViewModel.setArScanMode(it) },
                                    showAnchorBoundary = arUiState.showAnchorBoundary,
                                    onAnchorBoundaryChanged = { arViewModel.setShowAnchorBoundary(it) },
                                    isImperialUnits = arUiState.isImperialUnits,
                                    onImperialUnitsChanged = { arViewModel.setImperialUnits(it) },
                                    backgroundColor = editorUiState.canvasBackground.toArgb(),
                                    onBackgroundColorChanged = { argb -> settingsViewModel.setBackgroundColor(argb) },
                                    onCheckForUpdates = { dashboardViewModel.checkForUpdates(BuildConfig.VERSION_NAME) },
                                    onInstallUpdate = { dashboardViewModel.installUpdate(this@MainActivity) },
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

    override fun onResume() {
        super.onResume()
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arViewModel.destroyArSession()
        if (isFinishing) slamManager.destroy()
    }

    private fun AzNavHostScope.ConfigureRailItems(
        mainViewModel: MainViewModel,
        editorViewModel: EditorViewModel,
        arViewModel: ArViewModel,
        dashboardViewModel: DashboardViewModel,
        context: android.content.Context,
        overlayPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        backgroundPicker: androidx.activity.compose.ManagedActivityResultLauncher<PickVisualMediaRequest, android.net.Uri?>,
        editorUiState: EditorUiState,
        arUiState: ArUiState,
        navStrings: NavStrings,
        navItemColor: Color = Color.White,
        onShowFontPicker: (String) -> Unit = {},
        layerMenusOpen: MutableMap<String, Boolean>
    ) {
        val requestPermissions = {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        if (editorUiState.editorMode == EditorMode.STENCIL) return

        val isArMode = editorUiState.editorMode == EditorMode.AR

        azRailHostItem(id = "mode_host", text = navStrings.modes, color = navItemColor, info = navStrings.modesInfo)
        azRailSubItem(id = "ar", hostId = "mode_host", text = navStrings.arMode, route = EditorMode.AR.name, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.arModeInfo)

        if (isArMode) {
            azRailSubToggle(
                id = "scan_mode_toggle",
                hostId = "mode_host",
                isChecked = arUiState.arScanMode == ArScanMode.GAUSSIAN_SPLATS,
                toggleOnText = navStrings.mural,
                toggleOffText = navStrings.canvas,
                info = navStrings.scanModeToggleInfo,
                color = Cyan
            ) {
                arViewModel.setArScanMode(if (arUiState.arScanMode == ArScanMode.GAUSSIAN_SPLATS) ArScanMode.CLOUD_POINTS else ArScanMode.GAUSSIAN_SPLATS)
            }
        }

        azRailSubItem(id = "overlay", hostId = "mode_host", text = navStrings.overlay, route = EditorMode.OVERLAY.name, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.overlayInfo)
        azRailSubItem(id = "mockup", hostId = "mode_host", text = navStrings.mockup, route = EditorMode.MOCKUP.name, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.mockupInfo)
        azRailSubItem(id = "trace", hostId = "mode_host", text = navStrings.trace, route = EditorMode.TRACE.name, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.traceInfo)

        azDivider()

        if (isArMode) {
            azRailHostItem(id = "target_host", text = navStrings.grid, color = navItemColor, info = navStrings.gridInfo)

            azRailSubItem(id = "create", hostId = "target_host", text = navStrings.create, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.createInfo) {
                if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions()
            }

            azDivider()
        }

        val canEdit = if (isArMode)
            arUiState.scanPhase == ScanPhase.COMPLETE || arUiState.isAnchorEstablished
        else true

        if (canEdit) {
            azRailHostItem(id = "design_host", text = navStrings.design, color = navItemColor, info = navStrings.designInfo)
            azRailSubItem(id = "add_img", hostId = "design_host", text = "Image", color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.openInfo) {
                overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            azRailSubItem(id = "add_draw", hostId = "design_host", text = "Draw", color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.drawInfo) {
                editorViewModel.onAddBlankLayer()
            }
            azRailSubItem(id = "add_text", hostId = "design_host", text = "Text", color = navItemColor, shape = AzButtonShape.NONE) {
                editorViewModel.onAddTextLayer()
            }

            if (editorUiState.editorMode == EditorMode.MOCKUP) {
                azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.wallInfo) {
                    backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }

            azDivider()
        }

        azRailHostItem(id = "project_host", text = navStrings.project, color = navItemColor, info = navStrings.projectInfo)
        azRailSubItem(id = "new", hostId = "project_host", text = navStrings.new, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.newInfo) {
            dashboardViewModel.onNewProject(editorUiState.isRightHanded)
            showLibrary = false
        }
        azRailSubItem(id = "save", hostId = "project_host", text = navStrings.save, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.saveInfo) {
            showSaveDialog = true
        }
        azRailSubItem(id = "load", hostId = "project_host", text = navStrings.load, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.loadInfo) {
            showLibrary = true
        }
        azRailSubItem(id = "export", hostId = "project_host", text = navStrings.export, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.exportInfo) {
            if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
                arViewModel.requestExport { bgBitmap ->
                    editorViewModel.exportImage(bgBitmap)
                }
            } else {
                editorViewModel.exportImage(null)
            }
        }
        azRailSubItem(id = "settings", hostId = "project_host", text = navStrings.settings, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.settingsInfo) {
            showSettings = true
        }

        azDivider()

        if (canEdit) {
            editorUiState.layers.reversed().forEach { layer ->
                val activeTool = editorUiState.activeTool
                val forceOpenHiddenMenu = layerMenusOpen[layer.id] ?: false

                azRailRelocItem(
                    id = "layer_${layer.id}",
                    hostId = "design_host",
                    text = layer.name,
                    color = if (layer.isLinked) Cyan else navItemColor,
                    info = navStrings.layerInfo,
                    nestedRailAlignment = AzNestedRailAlignment.VERTICAL,
                    keepNestedRailOpen = true,
                    forceHiddenMenuOpen = forceOpenHiddenMenu,
                    onHiddenMenuDismiss = { layerMenusOpen[layer.id] = false },
                    onClick = {
                        editorViewModel.onLayerActivated(layer.id)
                        editorViewModel.setActiveTool(Tool.NONE)
                    },
                    onRelocate = { _, _, new -> editorViewModel.onLayerReordered(new.map { it.removePrefix("layer_") }.reversed()) },
                    nestedContent = {
                        val activate = { editorViewModel.onLayerActivated(layer.id) }

                        if (layer.textParams != null) {
                            azRailItem(id = "edit_text_${layer.id}", text = "Edit", color = navItemColor, shape = AzButtonShape.RECTANGLE) {
                                activate()
                                layerMenusOpen[layer.id] = true
                            }
                        }

                        val addSizeItem: () -> Unit = {
                            azRailItem(
                                id = "size_${layer.id}",
                                text = "Size",
                                color = navItemColor,
                                shape = AzButtonShape.RECTANGLE,
                                info = navStrings.sizeInfo,
                                content = AzComposableContent { isEnabled ->
                                    val liveState by editorViewModel.uiState.collectAsState()
                                    var itemRadiusPx by remember { mutableFloatStateOf(100f) }
                                    val density = LocalDensity.current
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .onSizeChanged { size -> itemRadiusPx = size.width / 2f }
                                            .pointerInput(isEnabled) {
                                                if (!isEnabled) return@pointerInput
                                                detectDragGestures { change, dragAmount ->
                                                    change.consume()
                                                    // Vertical drag → size, horizontal drag → feathering
                                                    if (kotlin.math.abs(dragAmount.y) >= kotlin.math.abs(dragAmount.x)) {
                                                        val currentSize = editorViewModel.uiState.value.brushSize
                                                        editorViewModel.setBrushSize(
                                                            (currentSize - dragAmount.y * 0.5f).coerceIn(1f, itemRadiusPx)
                                                        )
                                                    } else {
                                                        val currentFeather = editorViewModel.uiState.value.brushFeathering
                                                        editorViewModel.setBrushFeathering(
                                                            (currentFeather + dragAmount.x * 0.005f).coerceIn(0f, 1f)
                                                        )
                                                    }
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val sizeDp = with(density) {
                                            liveState.brushSize.coerceIn(1f, itemRadiusPx).toDp()
                                        }
                                        val checkerModifier = Modifier.drawBehind {
                                            val squareSize = 6.dp.toPx()
                                            val cols = (size.width / squareSize).toInt() + 1
                                            val rows = (size.height / squareSize).toInt() + 1
                                            for (row in 0 until rows) {
                                                for (col in 0 until cols) {
                                                    val isEven = (row + col) % 2 == 0
                                                    drawRect(
                                                        color = if (isEven) Color.LightGray else Color.Gray,
                                                        topLeft = Offset(col * squareSize, row * squareSize),
                                                        size = Size(squareSize, squareSize)
                                                    )
                                                }
                                            }
                                        }

                                        // Solid inner circle = hard core; outer ring (darker) = feathering amount
                                        Box(contentAlignment = Alignment.Center) {
                                            if (liveState.brushFeathering > 0.05f) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(sizeDp)
                                                        .clip(CircleShape)
                                                        .then(checkerModifier)
                                                        .background(Color.Black.copy(alpha = 0.5f))
                                                )
                                            }
                                            val hardCoreDp = with(density) {
                                                (liveState.brushSize * (1f - liveState.brushFeathering * 0.7f)).coerceIn(2f, itemRadiusPx).toDp()
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(hardCoreDp)
                                                    .clip(CircleShape)
                                                    .then(checkerModifier)
                                            )
                                        }
                                    }
                                }
                            )
                        }

                        when {
                            layer.textParams != null -> {
                                val tp = layer.textParams!!
                                azRailItem(id = "font_${layer.id}", text = "Font", color = navItemColor, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    onShowFontPicker(layer.id)
                                }
                                azRailItem(
                                    id = "size_${layer.id}",
                                    text = "Size",
                                    color = navItemColor,
                                    shape = AzButtonShape.RECTANGLE,
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val displaySize = liveState.layers.find { it.id == layer.id }?.textParams?.fontSizeDp ?: 150f
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val current = editorViewModel.uiState.value.layers
                                                            .find { it.id == layer.id }?.textParams?.fontSizeDp ?: 150f
                                                        editorViewModel.onTextSizeChanged(layer.id, (current - dragAmount.y * 0.5f).coerceIn(8f, 300f))
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${displaySize.toInt()}pt",
                                                color = navItemColor,
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                )
                                azRailItem(
                                    id = "color_${layer.id}",
                                    text = "Color",
                                    color = navItemColor,
                                    shape = AzButtonShape.RECTANGLE,
                                    onClick = { activate(); editorViewModel.onColorClicked() },
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val currentColor = liveState.layers.find { it.id == layer.id }?.textParams?.colorArgb
                                            ?: 0xFFFFFFFF.toInt()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val hsv = FloatArray(3)
                                                        android.graphics.Color.colorToHSV(currentColor, hsv)
                                                        hsv[2] = (hsv[2] - dragAmount.y * 0.002f).coerceIn(0f, 1f)
                                                        hsv[1] = (hsv[1] + dragAmount.x * 0.002f).coerceIn(0f, 1f)
                                                        editorViewModel.onTextColorChanged(layer.id, android.graphics.Color.HSVToColor(hsv))
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .background(Color(currentColor), CircleShape)
                                                    .border(1.dp, navItemColor.copy(alpha = 0.5f), CircleShape)
                                            )
                                        }
                                    }
                                )
                                azRailItem(
                                    id = "kern_${layer.id}",
                                    text = "Kern",
                                    color = navItemColor,
                                    shape = AzButtonShape.RECTANGLE,
                                    content = AzComposableContent { isEnabled ->
                                        val liveState by editorViewModel.uiState.collectAsState()
                                        val displayKern = liveState.layers.find { it.id == layer.id }?.textParams?.letterSpacingEm ?: 0f
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .pointerInput(isEnabled) {
                                                    if (!isEnabled) return@pointerInput
                                                    detectDragGestures { change, dragAmount ->
                                                        change.consume()
                                                        val current = editorViewModel.uiState.value.layers
                                                            .find { it.id == layer.id }?.textParams?.letterSpacingEm ?: 0f
                                                        editorViewModel.onTextKerningChanged(layer.id, (current + dragAmount.x * 0.003f).coerceIn(-0.2f, 1f))
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = String.format("%.2f", displayKern),
                                                color = navItemColor,
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                )
                                azRailItem(id = "bold_${layer.id}", text = "Bold", color = if (tp.isBold) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, !tp.isBold, tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                }
                                azRailItem(id = "italic_${layer.id}", text = "Italic", color = if (tp.isItalic) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, tp.isBold, !tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                }
                                azRailItem(id = "outline_${layer.id}", text = "Outline", color = if (tp.hasOutline) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, !tp.hasOutline, tp.hasDropShadow)
                                }
                                azRailItem(id = "shadow_${layer.id}", text = "Shadow", color = if (tp.hasDropShadow) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, tp.hasOutline, !tp.hasDropShadow)
                                }
                                if (layer.stencilType == null) {
                                    azRailItem(
                                        id = "stencil_${layer.id}",
                                        text = "Stencil",
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        content = AzComposableContent { _ ->
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .onGloballyPositioned { coords ->
                                                        if (coords.isAttached) {
                                                            editorViewModel.updateStencilButtonPosition(coords.positionInWindow())
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Stencil",
                                                    color = navItemColor,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    ) {
                                        activate()
                                        editorViewModel.onGenerateStencil(layer.id)
                                    }
                                }
                                azRailItem(id = "blend_${layer.id}", text = "Blend", color = navItemColor, shape = AzButtonShape.RECTANGLE, onClick = { activate(); editorViewModel.onCycleBlendMode() })
                                azRailItem(id = "adj_${layer.id}", text = "Adjust", color = navItemColor, shape = AzButtonShape.RECTANGLE, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                azRailItem(id = "invert_${layer.id}", text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.invertInfo, onClick = { activate(); editorViewModel.onToggleInvert() })
                            }
                            layer.isSketch -> {
                                azRailItem(id = "blend_${layer.id}", text = "Blend", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.blendingInfo, onClick = { activate(); editorViewModel.onCycleBlendMode() })
                                azRailItem(id = "adj_${layer.id}", text = "Adjust", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.adjustInfo, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                azRailItem(id = "invert_${layer.id}", text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.invertInfo, onClick = { activate(); editorViewModel.onToggleInvert() })
                                if (layer.stencilType == null) {
                                    azRailItem(
                                        id = "stencil_${layer.id}",
                                        text = "Stencil",
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        content = AzComposableContent { _ ->
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .onGloballyPositioned { coords ->
                                                        if (coords.isAttached) {
                                                            editorViewModel.updateStencilButtonPosition(coords.positionInWindow())
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Stencil",
                                                    color = navItemColor,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    ) {
                                        activate()
                                        editorViewModel.onGenerateStencil(layer.id)
                                    }
                                }
                                azRailItem(id = "balance_${layer.id}", text = "Balance", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.balanceInfo, onClick = { activate(); editorViewModel.onBalanceClicked() })
                                // --- Brush tools at bottom ---
                                azRailItem(id = "eraser_${layer.id}", text = "Eraser", color = if (activeTool == Tool.ERASER) Cyan else navItemColor, info = navStrings.eraserInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) })
                                azRailItem(id = "blur_${layer.id}", text = "Blur", color = if (activeTool == Tool.BLUR) Cyan else navItemColor, info = navStrings.blurInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) })
                                azRailItem(id = "liquify_${layer.id}", text = "Liquify", color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor, info = navStrings.liquifyInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) })
                                azRailItem(id = "dodge_${layer.id}", text = "Dodge", color = if (activeTool == Tool.DODGE) Cyan else navItemColor, info = navStrings.dodgeInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) })
                                azRailItem(id = "burn_${layer.id}", text = "Burn", color = if (activeTool == Tool.BURN) Cyan else navItemColor, info = navStrings.burnInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) })
                                addSizeItem()
                            }
                            else -> {
                                azRailItem(id = "iso_${layer.id}", text = "Isolate", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.isolateInfo, onClick = { activate(); editorViewModel.onRemoveBackgroundClicked() })
                                azRailItem(id = "line_${layer.id}", text = "Outline", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.outlineInfo, onClick = { activate(); editorViewModel.onSketchClicked() })
                                azRailItem(id = "invert_${layer.id}", text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.invertInfo, onClick = { activate(); editorViewModel.onToggleInvert() })
                                if (layer.stencilType == null) {
                                    azRailItem(
                                        id = "stencil_${layer.id}",
                                        text = "Stencil",
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        content = AzComposableContent { _ ->
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .onGloballyPositioned { coords ->
                                                        if (coords.isAttached) {
                                                            editorViewModel.updateStencilButtonPosition(coords.positionInWindow())
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "Stencil",
                                                    color = navItemColor,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    ) {
                                        activate()
                                        editorViewModel.onGenerateStencil(layer.id)
                                    }
                                }
                                azRailItem(id = "adj_${layer.id}", text = "Adjust", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.adjustInfo, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                azRailItem(id = "balance_${layer.id}", text = "Balance", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.balanceInfo, onClick = { activate(); editorViewModel.onBalanceClicked() })
                                azRailItem(id = "blend_${layer.id}", text = "Blend", color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.blendingInfo, onClick = { activate(); editorViewModel.onCycleBlendMode() })

                                // --- Brush tools at bottom ---
                                azRailItem(id = "eraser_${layer.id}", text = "Eraser", color = if (activeTool == Tool.ERASER) Cyan else navItemColor, info = navStrings.eraserInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) })
                                azRailItem(id = "blur_${layer.id}", text = "Blur", color = if (activeTool == Tool.BLUR) Cyan else navItemColor, info = navStrings.blurInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) })
                                azRailItem(id = "liquify_${layer.id}", text = "Liquify", color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor, info = navStrings.liquifyInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) })
                                azRailItem(id = "dodge_${layer.id}", text = "Dodge", color = if (activeTool == Tool.DODGE) Cyan else navItemColor, info = navStrings.dodgeInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) })
                                azRailItem(id = "burn_${layer.id}", text = "Burn", color = if (activeTool == Tool.BURN) Cyan else navItemColor, info = navStrings.burnInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) })
                                addSizeItem()
                            }
                        }

                        azHelpRailItem(id = "help_layer_${layer.id}", text = navStrings.help, color = navItemColor, shape = AzButtonShape.RECTANGLE)
                    }
                ) {
                    inputItem(hint = "Rename") { newName -> editorViewModel.onLayerRenamed(layer.id, newName) }
                    if (layer.textParams != null) {
                        inputItem(
                            hint = "Edit text",
                            initialValue = layer.textParams!!.text,
                            onValueChange = { text -> editorViewModel.onTextContentChanged(layer.id, text) }
                        )
                    }
                    listItem(text = "Copy Edits") { editorViewModel.copyLayerModifications(layer.id) }
                    listItem(text = "Paste Edits") { editorViewModel.pasteLayerModifications(layer.id) }
                    if (layer.stencilType != null) {
                        listItem(text = "Generate Poster") { 
                            posterSourceLayerId = layer.stencilSourceId ?: layer.id
                            showPosterDialog = true 
                        }
                    }
                    listItem(text = "Duplicate") { editorViewModel.onLayerDuplicated(layer.id) }
                    
                    // Check if part of a linked group (contiguous links)
                    val layers = editorUiState.layers
                    val idx = layers.indexOfFirst { it.id == layer.id }
                    val isPartToUnlink = if (idx >= 0) {
                        (idx > 0 && layers[idx].isLinked) || 
                        (idx + 1 < layers.size && layers[idx + 1].isLinked)
                    } else false

                    listItem(text = if (isPartToUnlink) "Unlink Layer" else "Link Layer") { editorViewModel.onToggleLinkLayer(layer.id) }
                    listItem(text = if (layer.isVisible) "Hide Layer" else "Show Layer") { editorViewModel.onToggleVisibility(layer.id) }
                    listItem(text = "Flatten All") { editorViewModel.onFlattenAllLayers() }
                    listItem(text = "Delete") { editorViewModel.onLayerRemoved(layer.id) }
                }
            }
        }

        azDivider()

        if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
            azRailItem(id = "light", text = navStrings.light, color = navItemColor, info = navStrings.lightInfo, onClick = { arViewModel.toggleFlashlight() })
        }

        val lockText = if (editorUiState.editorMode == EditorMode.TRACE) "Lock" else "Freeze"
        val lockAction: () -> Unit = if (editorUiState.editorMode == EditorMode.TRACE) {
            { mainViewModel.setTouchLocked(true) }
        } else {
            {
                editorViewModel.toggleImageLock()
                val visibleLayers = editorUiState.layers.filter { it.isVisible && it.bitmap != null }
                if (visibleLayers.isNotEmpty()) {
                    val composite = compositeLayersForAr(visibleLayers)
                    arViewModel.onFreezeRequested(composite)
                }
            }
        }
        azRailItem(id = "lock_trace", text = lockText, color = navItemColor, info = navStrings.lockInfo, onClick = lockAction)

        azHelpRailItem(id = "help_main", text = navStrings.help, color = navItemColor, shape = AzButtonShape.RECTANGLE)
    }
}

@Composable
private fun DepthApiUnsupportedBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xEECC4400), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = "This device doesn't support the Depth API.\nSwitch to Canvas mode in Settings.",
            color = HotPink,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ArCoreUnavailableOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE1A1A1A))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ARCore is required",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "This device does not have ARCore installed or is not supported. Install ARCore from the Play Store to use AR features.",
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                "market://details?id=com.google.ar.core".toUri())
                        )
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                "https://play.google.com/store/apps/details?id=com.google.ar.core".toUri())
                        )
                    }
                }
            ) {
                Text("Install ARCore")
            }
        }
    }
}

@Composable
private fun CameraPermissionDeniedBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .background(Color(0xEE550000), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Camera permission is required for AR mode.",
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC2200))
            ) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun TapTargetOverlay(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 96.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xEEFFFFFF), RoundedCornerShape(16.dp))
                .border(2.dp, Color.Cyan, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "TARGET CREATION",
                    color = Color(0xFF007788),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Tap directly on your painted reference marks on the screen. The app will immediately isolate them.",
                    color = Color(0xFF222222),
                    textAlign = TextAlign.Start
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onCancel,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
        ) {
            Text("Cancel")
        }
    }
}

@Composable
private fun DiagPopup(
    diagLog: String?,
    modifier: Modifier = Modifier
) {
    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(80f) }
    var visible by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    if (!visible) return

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .pointerInput(diagLog) {
                detectTapGestures {
                    val text = diagLog ?: return@detectTapGestures
                    val cm = context.getSystemService(AndroidClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("diag", text))
                    copied = true
                    scope.launch {
                        kotlinx.coroutines.delay(1500)
                        copied = false
                    }
                }
            }
            .background(
                if (copied) Color(0xDD004444) else Color(0xDD000000),
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (copied) Color.Green else Color.Cyan,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(max = 300.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (copied) "COPIED ✓" else "DEPTH DIAG  (tap to copy)",
                    color = if (copied) Color.Green else Color.Cyan,

                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "✕",
                    color = Color.Gray,
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { _ -> visible = false }
                        }
                )
            }
            Text(
                text = diagLog ?: "Waiting for first frame…",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun ScanCoachingOverlay(
    splatCount: Int,
    hint: String?,
    modifier: Modifier = Modifier,
    scanPhase: ScanPhase = ScanPhase.AMBIENT,
    ambientSectorsCovered: Int = 0,
) {
    val phaseLabel = when (scanPhase) {
        ScanPhase.AMBIENT -> "Step 1: Map your surroundings"
        ScanPhase.WALL -> "Step 2: Scan the target wall"
        ScanPhase.COMPLETE -> null
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit  = fadeOut() + slideOutVertically { it / 2 }
        ) {
            AnimatedContent(
                targetState = hint ?: "",
                transitionSpec = {
                    (fadeIn() + slideInVertically { -it / 3 })
                        .togetherWith(fadeOut() + slideOutVertically { it / 3 })
                },
                label = "scan_hint"
            ) { text ->
                Box(
                    modifier = Modifier
                        .background(
                            Color(0xCC000000),
                            RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = text,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (phaseLabel != null) {
                    Text(
                        text = phaseLabel,
                        color = Color.Cyan,

                        fontWeight = FontWeight.Bold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (scanPhase == ScanPhase.AMBIENT) {
                        LinearProgressIndicator(
                            progress = { (ambientSectorsCovered / 12f).coerceIn(0f, 1f) },
                            modifier = Modifier.width(100.dp),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                        Text(
                            text = "${ambientSectorsCovered * 30}° / 360°",
                            color = Color.LightGray,

                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { (splatCount / 50_000f).coerceIn(0f, 1f) },
                            modifier = Modifier.width(100.dp),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                        Text(
                            text = "${splatCount / 1000}k / 50k",
                            color = Color.LightGray,

                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaneConfirmOverlay(
    onConfirm: () -> Unit,
    onRedetect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 96.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xEE000000), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFFFF8C00), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Is the artwork on the correct wall?",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("Looks correct")
                    }
                    OutlinedButton(
                        onClick = onRedetect,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8C00))
                    ) {
                        Text("Re-detect")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaneRealignmentOverlay(
    onTryThisPlane: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(bottom = 96.dp).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xEE000000), RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFFFF8C00), RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Re-detect Wall Surface",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "The anchor is placed from where you stood when you captured. " +
                        "ARCore picks the tracked vertical surface most directly in front of " +
                        "that original position.\n\n" +
                        "1. Return to approximately where you stood during capture\n" +
                        "2. Slowly pan the camera across your mural wall so ARCore can " +
                        "register it as a flat surface\n" +
                        "3. Hold steady facing the artwork, then tap below\n\n" +
                        "The orange border will jump to the newly detected surface.",
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Start
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onTryThisPlane,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                    ) {
                        Text("Use This Wall")
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8C00))
                    ) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}

@Composable
private fun DistanceBadge(
    distanceMeters: Float,
    imperial: Boolean,
    modifier: Modifier = Modifier
) {
    val label = if (imperial) {
        val feet = distanceMeters * 3.28084f
        "%.1f ft".format(feet)
    } else {
        if (distanceMeters < 1f) "${(distanceMeters * 100).toInt()} cm"
        else "%.1f m".format(distanceMeters)
    }
    Box(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
        )
    }
}

@Composable
private fun RelocStatusBadge(
    isAnchorEstablished: Boolean,
    paintingProgress: Float,
    modifier: Modifier = Modifier
) {
    val relocState = when {
        !isAnchorEstablished -> RelocState.IDLE
        paintingProgress > 0f -> RelocState.TRACKING
        else -> RelocState.SEARCHING
    }
    if (relocState == RelocState.IDLE) return

    val infiniteTransition = rememberInfiniteTransition(label = "reloc_pulse")
    val pulseAlpha by if (relocState == RelocState.SEARCHING) {
        infiniteTransition.animateFloat(
            initialValue = 0.4f, targetValue = 1f, label = "pulse",
            animationSpec = infiniteRepeatable(
                animation = tween(700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val dotColor = if (relocState == RelocState.TRACKING) Color(0xFF66BB6A) else Color(0xFFFFCA28)
    val label = when (relocState) {
        RelocState.SEARCHING -> "Scanning\u2026"
        RelocState.TRACKING  -> "${(paintingProgress * 100).toInt()}% matched"
        else                 -> ""
    }

    Row(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .alpha(pulseAlpha)
                .background(dotColor, CircleShape)
        )
        Text(label, fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun AnchorLockFlash(isAnchorEstablished: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isAnchorEstablished) {
        if (isAnchorEstablished) {
            visible = true
            delay(2000L)
            visible = false
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit  = fadeOut(tween(500))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x2200CC44)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF66BB6A),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Anchor locked",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun PaintingProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val pct = (progress * 100f).toInt().coerceIn(0, 100)
    val barColor = when {
        pct >= 80 -> Color(0xFF66BB6A)
        pct >= 40 -> Color(0xFFFFCA28)
        else      -> Color(0xFFEF5350)
    }
    Box(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.width(90.dp),
                color = barColor,
                trackColor = Color.White.copy(alpha = 0.2f)
            )
            Text(
                text = "$pct%",
                color = Color.White,

            )
        }
    }
}

private val AVAILABLE_FONTS = listOf(
    "Roboto", "Oswald", "Bebas Neue", "Anton",
    "Playfair Display", "Pacifico", "Dancing Script",
    "Permanent Marker", "Rock Salt", "Bangers", "Righteous"
)

@Composable
private fun FontPickerDialog(
    onFontSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val googleFontsProvider = remember {
        androidx.compose.ui.text.googlefonts.GoogleFont.Provider(
            providerAuthority = "com.google.android.gms.fonts",
            providerPackage = "com.google.android.gms",
            certificates = com.hereliesaz.graffitixr.R.array.com_google_android_gms_fonts_certs
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Font") },
        text = {
            LazyColumn {
                items(AVAILABLE_FONTS) { fontName ->
                    val googleFont = androidx.compose.ui.text.googlefonts.GoogleFont(fontName)
                    val fontFamily = FontFamily(
                        androidx.compose.ui.text.googlefonts.Font(googleFont, googleFontsProvider)
                    )
                    Text(
                        text = "Aa  $fontName",
                        fontFamily = fontFamily,
                        fontSize = 20.sp,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFontSelected(fontName) }
                            .padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {}
    )
}

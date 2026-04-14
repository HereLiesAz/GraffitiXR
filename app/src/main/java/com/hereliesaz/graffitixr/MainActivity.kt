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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.core.content.FileProvider
import java.io.File
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
import com.hereliesaz.graffitixr.common.util.isolateMarkings
import com.hereliesaz.graffitixr.design.components.InfoDialog
import com.hereliesaz.graffitixr.design.components.PosterOptionsDialog
import com.hereliesaz.graffitixr.design.components.TouchLockOverlay
import com.hereliesaz.graffitixr.design.components.UnlockInstructionsPopup
import androidx.compose.ui.res.stringResource
import com.hereliesaz.graffitixr.design.R as DesignR
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.core.os.LocaleListCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.design.theme.rememberNavStrings
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
    var showWallSourceDialog by mutableStateOf(false)
    var isExporting by mutableStateOf(false)

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

                val mainViewModel: MainViewModel = hiltViewModel()
                val editorViewModel: EditorViewModel = hiltViewModel()
                val dashboardViewModel: DashboardViewModel = hiltViewModel()
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val cameraController = rememberCameraController()

                var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
                var showHelp by remember { mutableStateOf(false) }

                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val arUiState by arViewModel.uiState.collectAsState()
                val dashboardUiState by dashboardViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()
                val completedTutorials by settingsViewModel.completedTutorials.collectAsState()
                val language by settingsViewModel.language.collectAsState()

                LaunchedEffect(language) {
                    val appLocales = LocaleListCompat.forLanguageTags(language.code)
                    if (AppCompatDelegate.getApplicationLocales() != appLocales) {
                        AppCompatDelegate.setApplicationLocales(appLocales)
                    }
                }

                var isProcessing by remember { mutableStateOf(false) }

                val currentTempCapture = arUiState.tempCaptureBitmap
                val currentCaptureStep = mainUiState.captureStep
                val isWaitingForTap = mainUiState.isWaitingForTap

                LaunchedEffect(currentTempCapture, currentCaptureStep, isWaitingForTap) {
                    if (currentTempCapture != null) {
                        if (currentCaptureStep == CaptureStep.NONE && isWaitingForTap) {
                            // Phase 4: Captured frame from tap. Transition to RECTIFY.
                            mainViewModel.setCaptureStep(CaptureStep.RECTIFY)
                        } else if (currentCaptureStep == CaptureStep.CAPTURE) {
                            // Manual capture from rail. Transition to RECTIFY.
                            mainViewModel.setCaptureStep(CaptureStep.RECTIFY)
                        }
                    }
                }

                // Removed auto-confirm LaunchedEffect to allow target review

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
                        !showSettings &&
                        !isExporting

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
                val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                    if (success) {
                        cameraUri?.let { editorViewModel.setBackgroundImage(it.toUri()) }
                    }
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

                val strings = rememberAppStrings()
                val navStrings = strings.nav
                var showFontPicker by remember { mutableStateOf(false) }
                var fontPickerLayerId by remember { mutableStateOf<String?>(null) }
                val layerMenusOpen = remember { mutableStateMapOf<String, Boolean>() }

                val context = LocalContext.current
                val canvasBg = editorUiState.canvasBackground
                val navItemColor = remember(canvasBg) {
                    Color(1f - canvasBg.red, 1f - canvasBg.green, 1f - canvasBg.blue, alpha = 1f)
                }

                val mainHelpItems = remember(editorUiState.layers) {
                    val base = mutableMapOf<String, Any>(
                        // ─── Mode Menu ──────────────────────────────────────────────────
                        "mode_host" to strings.help.modeHost,
                        "ar" to strings.help.ar,
                        "overlay" to strings.help.overlay,
                        "mockup" to strings.help.mockup,
                        "trace" to strings.help.trace,

                        // ─── Target Menu ────────────────────────────────────────────────
                        "target_host" to strings.help.targetHost,
                        "scan_mode_toggle" to strings.help.scanModeToggle,
                        "create" to strings.help.create,

                        // ─── Design Menu ────────────────────────────────────────────────
                        "design_host" to strings.help.designHost,
                        "add_img" to strings.help.addImg,
                        "add_draw" to strings.help.addDraw,
                        "add_text" to strings.help.addText,
                        "wall" to strings.help.wall,

                        // ─── Project Menu ───────────────────────────────────────────────
                        "project_host" to strings.help.projectHost,
                        "new" to strings.help.newProject,
                        "save" to strings.help.saveProject,
                        "load" to strings.help.loadProject,
                        "export" to strings.help.exportImage,
                        "settings" to strings.help.appSettings,

                        // ─── Global Tools ───────────────────────────────────────────────
                        "light" to strings.help.flashlight,
                        "lock_trace" to strings.help.lockTrace,
                        "help_main" to strings.help.helpMain
                    )

                    editorUiState.layers.forEach { layer ->
                        base["layer_${layer.id}"] = strings.help.layer(layer.name)
                    }
                    base
                }

                val nestedHelpItems = remember(editorUiState.layers) {
                    val base = mutableMapOf<String, Any>()
                    editorUiState.layers.forEach { layer ->
                        val id = layer.id
                        base["edit_text_$id"] = strings.help.editText
                        base["size_$id"] = strings.help.size
                        base["font_$id"] = strings.help.font
                        base["color_$id"] = strings.help.color
                        base["kern_$id"] = strings.help.kern
                        base["bold_$id"] = strings.help.bold
                        base["italic_$id"] = strings.help.italic
                        base["outline_$id"] = strings.help.outline
                        base["shadow_$id"] = strings.help.shadow
                        base["stencil_$id"] = strings.help.stencilGen
                        base["blend_$id"] = strings.help.blend
                        base["adj_$id"] = strings.help.adj
                        base["invert_$id"] = strings.help.invert
                        base["balance_$id"] = strings.help.balance
                        base["eraser_$id"] = strings.help.eraser
                        base["blur_$id"] = strings.help.blur
                        base["liquify_$id"] = strings.help.liquify
                        base["dodge_$id"] = strings.help.dodge
                        base["burn_$id"] = strings.help.burn
                        base["iso_$id"] = strings.help.iso
                        base["line_$id"] = strings.help.line
                        base["help_layer_$id"] = strings.help.helpLayer
                    }
                    base
                }

                val helpViewModel: HelpViewModel =
                    hiltViewModel(checkNotNull<ViewModelStoreOwner>(LocalViewModelStoreOwner.current) {
                        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
                    }, null)
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

                val tutorials = getTutorials(editorUiState.layers, strings)

                AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
                    azTheme(
                        activeColor = Cyan,
                        defaultShape = AzButtonShape.RECTANGLE,
                        headerIconShape = AzHeaderIconShape.ROUNDED,
                        translucentBackground = Color.Black.copy(alpha = 0.5f)
                    )
                    azConfig(
                        packButtons = true,
                        dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT,
                        noMenu = !isRailVisible
                    )
                    azAdvanced(
                        helpEnabled = showHelp,
                        helpList = activeHelpList,
                        onDismissHelp = { showHelp = false },
                        tutorials = tutorials
                    )

                    if (isRailVisible) {
                        ConfigureRailItems(
                            mainViewModel, editorViewModel, arViewModel, dashboardViewModel, context,
                            overlayImagePicker, backgroundImagePicker, editorUiState, arUiState, strings,
                            navItemColor = navItemColor,
                            onShowFontPicker = { layerId -> fontPickerLayerId = layerId; showFontPicker = true },
                            layerMenusOpen = layerMenusOpen,
                            showLibrary = showLibrary,
                            showHelp = showHelp,
                            onHelpToggle = { showHelp = !showHelp }
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
                        if (isExporting) return@onscreen

                        // Auto-show the mode tutorial on first visit using the v8.0 controller API.
                        // DataStore (completedTutorials) persists "seen" state across app restarts.
                        // showLibrary is included as a key so the effect re-fires when the library
                        // closes — without this guard the tutorial fires while the library is still
                        // covering the editor, marking it "complete" before the user ever sees it.
                        val tutorialController = LocalAzTutorialController.current
                        LaunchedEffect(editorUiState.editorMode, showLibrary, completedTutorials) {
                            if (showLibrary) return@LaunchedEffect

                            val noLayers = editorUiState.layers.isEmpty()
                            val noAnchor = !arUiState.isAnchorEstablished

                            if (noLayers && (editorUiState.editorMode != EditorMode.AR || noAnchor)) {
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
                        }

                        if (editorUiState.stencilHintVisible || editorUiState.isStencilGenerating) {
                            val pos = editorUiState.stencilButtonPosition
                            val density = LocalDensity.current
                            val offset = with(density) { IntOffset(pos.x.toInt() + 100.dp.roundToPx(), pos.y.toInt()) }

                            Box(Modifier.offset { offset }) {
                                if (editorUiState.isStencilGenerating) {
                                    Text(stringResource(DesignR.string.generating), color = Color.Cyan, fontWeight = FontWeight.Bold)
                                } else {
                                    Text(
                                        stringResource(DesignR.string.stencil_hint),
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
                                composable(EditorMode.AR.name) { EditorOverlay(editorViewModel, mainUiState, strings) }
                                composable(EditorMode.OVERLAY.name) { EditorOverlay(editorViewModel, mainUiState, strings) }
                                composable(EditorMode.MOCKUP.name) { EditorOverlay(editorViewModel, mainUiState, strings) }
                                composable(EditorMode.TRACE.name) { EditorOverlay(editorViewModel, mainUiState, strings) }
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
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    strings = strings
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
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    strings = strings
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
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                    strings = strings
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
                                        .padding(top = 16.dp, end = 16.dp),
                                    strings = strings
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && editorUiState.showDiagOverlay) {
                                DiagPopup(
                                    diagLog = arUiState.diagLog,
                                    modifier = Modifier.align(Alignment.TopStart),
                                    strings = strings
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings) {
                                AnchorLockFlash(isAnchorEstablished = arUiState.isAnchorEstablished, strings = strings)
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
                                    strings = strings,
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
                                        val currentBitmap = arUiState.tempCaptureBitmap // Use upright display frame
                                        if (currentBitmap != null && points.size == 4) {
                                            isProcessing = true
                                            lifecycleScope.launch(Dispatchers.Default) {
                                                val unwarped = ImageProcessor.unwarpImage(currentBitmap, points)
                                                if (unwarped != null) {
                                                    // Phase 4: Isolate markings on the rectified surface for the mask step
                                                    val isolated = unwarped.isolateMarkings(null)
                                                    arViewModel.setTempCapture(isolated)
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
                                        arViewModel.generateAnnotationsForReview(bitmap)
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
                                    initialName = editorUiState.projectId ?: stringResource(DesignR.string.new_project_name),
                                    onDismissRequest = { showSaveDialog = false },
                                    onSaveRequest = { name ->
                                        lifecycleScope.launch {
                                            arViewModel.saveMapBlocking()
                                            arViewModel.saveCloudPointsBlocking()
                                            editorViewModel.saveProject(name)
                                            showSaveDialog = false
                                        }
                                    },
                                    strings = strings
                                )
                            }

                            if (dashboardUiState.showNewProjectDialog) {
                                SaveProjectDialog(
                                    initialName = stringResource(DesignR.string.new_project_name),
                                    onDismissRequest = { dashboardViewModel.dismissNewProjectDialog() },
                                    onSaveRequest = { name ->
                                        dashboardViewModel.onCreateProject(name, editorUiState.isRightHanded)
                                        showLibrary = false
                                    },
                                    strings = strings
                                )
                            }

                            if (showFontPicker) {
                                FontPickerDialog(
                                    onFontSelected = { fontName ->
                                        fontPickerLayerId?.let { editorViewModel.onTextFontChanged(it, fontName) }
                                        showFontPicker = false
                                    },
                                    onDismiss = { showFontPicker = false },
                                    strings = strings
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
                                    },
                                    strings = strings
                                )
                            }

                            if (showWallSourceDialog) {
                                WallSourceDialog(
                                    onDismiss = { showWallSourceDialog = false },
                                    onGallery = {
                                        showWallSourceDialog = false
                                        backgroundImagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                    onCamera = {
                                        showWallSourceDialog = false
                                        if (hasCameraPermission) {
                                            val tmpFile = File(context.cacheDir, "wall_camera_${System.currentTimeMillis()}.jpg")
                                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmpFile)
                                            cameraUri = uri.toString()
                                            takePictureLauncher.launch(uri)
                                        } else {
                                            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                                        }
                                    },
                                    strings = strings
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
                                        dashboardViewModel.onNewProjectTriggered()
                                    },
                                    onImportProject = { uri ->
                                        dashboardViewModel.importProject(uri)
                                    },
                                    onClose = { showLibrary = false },
                                    strings = strings
                                )
                            }

                            if (showSettings) {
                                val dashboardUiState by dashboardViewModel.uiState.collectAsState()
                                SettingsScreen(
                                    currentVersion = BuildConfig.VERSION_NAME,
                                    updateStatus = dashboardUiState.updateStatusMessage,
                                    isCheckingForUpdate = dashboardUiState.isCheckingForUpdate,
                                    currentLanguage = language,
                                    onLanguageChanged = { settingsViewModel.setLanguage(it) },
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
                                    onClose = { showSettings = false },
                                    strings = strings
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EditorOverlay(viewModel: EditorViewModel, mainUiState: MainUiState, strings: AppStrings) {
        val uiState by viewModel.uiState.collectAsState()
        EditorUi(
            actions = viewModel,
            uiState = uiState,
            isTouchLocked = mainUiState.isTouchLocked,
            showUnlockInstructions = mainUiState.showUnlockInstructions,
            strings = strings,
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
        strings: AppStrings,
        navItemColor: Color = Color.White,
        onShowFontPicker: (String) -> Unit = {},
        layerMenusOpen: MutableMap<String, Boolean>,
        showLibrary: Boolean,
        showHelp: Boolean,
        onHelpToggle: () -> Unit
    ) {
        val navStrings = strings.nav
        val requestPermissions = {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }

        if (editorUiState.editorMode == EditorMode.STENCIL) return

        if (!showLibrary) {
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
                azRailSubItem(id = "add_img", hostId = "design_host", text = navStrings.image, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.openInfo) {
                    overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                azRailSubItem(id = "add_draw", hostId = "design_host", text = navStrings.draw, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.drawInfo) {
                    editorViewModel.onAddBlankLayer()
                }
                azRailSubItem(id = "add_text", hostId = "design_host", text = navStrings.text, color = navItemColor, shape = AzButtonShape.NONE) {
                    editorViewModel.onAddTextLayer()
                }

                if (editorUiState.editorMode == EditorMode.MOCKUP) {
                    azRailSubItem(id = "wall", hostId = "design_host", text = navStrings.wall, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.wallInfo) {
                        showWallSourceDialog = true
                    }
                }

                azDivider()
            }
        }

        azRailHostItem(id = "project_host", text = navStrings.project, color = navItemColor, info = navStrings.projectInfo)
        azRailSubItem(id = "new", hostId = "project_host", text = navStrings.new, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.newInfo) {
            dashboardViewModel.onNewProjectTriggered()
        }
        azRailSubItem(id = "save", hostId = "project_host", text = navStrings.save, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.saveInfo) {
            showSaveDialog = true
        }
        azRailSubItem(id = "load", hostId = "project_host", text = navStrings.load, color = navItemColor, shape = AzButtonShape.NONE, info = navStrings.loadInfo) {
            this@MainActivity.showLibrary = true
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

        if (!showLibrary) {
            val isArMode = editorUiState.editorMode == EditorMode.AR
            val canEdit = if (isArMode)
                arUiState.scanPhase == ScanPhase.COMPLETE || arUiState.isAnchorEstablished
            else true

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
                                azRailItem(id = "edit_text_${layer.id}", text = navStrings.edit, color = navItemColor, shape = AzButtonShape.RECTANGLE) {
                                    activate()
                                    layerMenusOpen[layer.id] = true
                                }
                            }

                            val addSizeItem: () -> Unit = {
                                azRailItem(
                                    id = "size_${layer.id}",
                                    text = navStrings.size,
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
                                    azRailItem(id = "font_${layer.id}", text = navStrings.font, color = navItemColor, shape = AzButtonShape.RECTANGLE) {
                                        activate()
                                        onShowFontPicker(layer.id)
                                    }
                                    azRailItem(
                                        id = "size_${layer.id}",
                                        text = navStrings.size,
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
                                        text = navStrings.color,
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
                                        text = navStrings.kern,
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
                                    azRailItem(id = "bold_${layer.id}", text = navStrings.bold, color = if (tp.isBold) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, !tp.isBold, tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailItem(id = "italic_${layer.id}", text = navStrings.italic, color = if (tp.isItalic) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, !tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailItem(id = "outline_${layer.id}", text = navStrings.outline, color = if (tp.hasOutline) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, !tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailItem(id = "shadow_${layer.id}", text = navStrings.shadow, color = if (tp.hasDropShadow) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, tp.hasOutline, !tp.hasDropShadow)
                                    }
                                    if (layer.stencilType == null) {
                                        azRailItem(
                                            id = "stencil_${layer.id}",
                                            text = navStrings.stencil,
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
                                                        text = navStrings.stencil,
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
                                    azRailItem(id = "blend_${layer.id}", text = navStrings.build, color = navItemColor, shape = AzButtonShape.RECTANGLE, onClick = { activate(); editorViewModel.onCycleBlendMode() })
                                    azRailItem(id = "adj_${layer.id}", text = navStrings.adjust, color = navItemColor, shape = AzButtonShape.RECTANGLE, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                    azRailItem(id = "invert_${layer.id}", text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.invertInfo, onClick = { activate(); editorViewModel.onToggleInvert() })
                                }
                                layer.isSketch -> {
                                    azRailItem(id = "blend_${layer.id}", text = navStrings.build, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.blendingInfo, onClick = { activate(); editorViewModel.onCycleBlendMode() })
                                    azRailItem(id = "adj_${layer.id}", text = navStrings.adjust, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.adjustInfo, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                    azRailItem(id = "invert_${layer.id}", text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.invertInfo, onClick = { activate(); editorViewModel.onToggleInvert() })
                                    if (layer.stencilType == null) {
                                        azRailItem(
                                            id = "stencil_${layer.id}",
                                            text = navStrings.stencil,
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
                                                        text = navStrings.stencil,
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
                                    azRailItem(id = "balance_${layer.id}", text = navStrings.balance, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.balanceInfo, onClick = { activate(); editorViewModel.onBalanceClicked() })
                                    // --- Brush tools at bottom ---
                                    azRailItem(id = "eraser_${layer.id}", text = navStrings.eraser, color = if (activeTool == Tool.ERASER) Cyan else navItemColor, info = navStrings.eraserInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) })
                                    azRailItem(id = "blur_${layer.id}", text = navStrings.blur, color = if (activeTool == Tool.BLUR) Cyan else navItemColor, info = navStrings.blurInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) })
                                    azRailItem(id = "liquify_${layer.id}", text = navStrings.liquify, color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor, info = navStrings.liquifyInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) })
                                    azRailItem(id = "dodge_${layer.id}", text = navStrings.dodge, color = if (activeTool == Tool.DODGE) Cyan else navItemColor, info = navStrings.dodgeInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) })
                                    azRailItem(id = "burn_${layer.id}", text = navStrings.burn, color = if (activeTool == Tool.BURN) Cyan else navItemColor, info = navStrings.burnInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) })
                                    addSizeItem()
                                }
                                else -> {
                                    azRailItem(id = "iso_${layer.id}", text = navStrings.isolate, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.isolateInfo, onClick = { activate(); editorViewModel.onRemoveBackgroundClicked() })
                                    azRailItem(id = "line_${layer.id}", text = navStrings.outline, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.outlineInfo, onClick = { activate(); editorViewModel.onSketchClicked() })
                                    azRailItem(id = "invert_${layer.id}", text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.invertInfo, onClick = { activate(); editorViewModel.onToggleInvert() })
                                    if (layer.stencilType == null) {
                                        azRailItem(
                                            id = "stencil_${layer.id}",
                                            text = navStrings.stencil,
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
                                                        text = navStrings.stencil,
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
                                    azRailItem(id = "adj_${layer.id}", text = navStrings.adjust, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.adjustInfo, onClick = { activate(); editorViewModel.onAdjustClicked() })
                                    azRailItem(id = "balance_${layer.id}", text = navStrings.balance, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.balanceInfo, onClick = { activate(); editorViewModel.onBalanceClicked() })
                                    azRailItem(id = "blend_${layer.id}", text = navStrings.build, color = navItemColor, shape = AzButtonShape.RECTANGLE, info = navStrings.blendingInfo, onClick = { activate(); editorViewModel.onCycleBlendMode() })

                                    // --- Brush tools at bottom ---
                                    azRailItem(id = "eraser_${layer.id}", text = navStrings.eraser, color = if (activeTool == Tool.ERASER) Cyan else navItemColor, info = navStrings.eraserInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) })
                                    azRailItem(id = "blur_${layer.id}", text = navStrings.blur, color = if (activeTool == Tool.BLUR) Cyan else navItemColor, info = navStrings.blurInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) })
                                    azRailItem(id = "liquify_${layer.id}", text = navStrings.liquify, color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor, info = navStrings.liquifyInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) })
                                    azRailItem(id = "dodge_${layer.id}", text = navStrings.dodge, color = if (activeTool == Tool.DODGE) Cyan else navItemColor, info = navStrings.dodgeInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) })
                                    azRailItem(id = "burn_${layer.id}", text = navStrings.burn, color = if (activeTool == Tool.BURN) Cyan else navItemColor, info = navStrings.burnInfo, onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) })
                                    addSizeItem()
                                }
                            }

                            azHelpRailItem(id = "help_layer_${layer.id}", text = navStrings.help, color = navItemColor, shape = AzButtonShape.RECTANGLE)
                        }
                    ) {
                        inputItem(hint = strings.editor.renameHint) { newName -> editorViewModel.onLayerRenamed(layer.id, newName) }
                        if (layer.textParams != null) {
                            inputItem(
                                hint = strings.editor.editTextHint,
                                initialValue = layer.textParams!!.text,
                                onValueChange = { text -> editorViewModel.onTextContentChanged(layer.id, text) }
                            )
                        }
                        listItem(text = strings.editor.copyEdits) { editorViewModel.copyLayerModifications(layer.id) }
                        listItem(text = strings.editor.pasteEdits) { editorViewModel.pasteLayerModifications(layer.id) }
                        if (layer.stencilType != null) {
                            listItem(text = strings.editor.generatePoster) {
                                posterSourceLayerId = layer.stencilSourceId ?: layer.id
                                showPosterDialog = true
                            }
                        }
                        listItem(text = strings.editor.duplicate) { editorViewModel.onLayerDuplicated(layer.id) }

                        // Check if part of a linked group (contiguous links)
                        val layers = editorUiState.layers
                        val idx = layers.indexOfFirst { it.id == layer.id }
                        val isPartToUnlink = if (idx >= 0) {
                            (idx > 0 && layers[idx].isLinked) ||
                                    (idx + 1 < layers.size && layers[idx + 1].isLinked)
                        } else false

                        listItem(text = if (isPartToUnlink) strings.editor.unlinkLayer else strings.editor.linkLayer) { editorViewModel.onToggleLinkLayer(layer.id) }
                        listItem(text = if (layer.isVisible) strings.editor.hideLayer else strings.editor.showLayer) { editorViewModel.onToggleVisibility(layer.id) }
                        listItem(text = strings.editor.flattenAll) { editorViewModel.onFlattenAllLayers() }
                        listItem(text = strings.editor.delete) { editorViewModel.onLayerRemoved(layer.id) }
                    }
                }
            }

            azDivider()

            if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
                azRailItem(id = "light", text = navStrings.light, color = navItemColor, info = navStrings.lightInfo, onClick = { arViewModel.toggleFlashlight() })
            }

            val lockText = if (editorUiState.editorMode == EditorMode.TRACE) strings.editor.lock else strings.editor.freeze
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
        }

        azDivider()

        azRailItem(
            id = "help_main",
            text = navStrings.help,
            color = if (showHelp) Cyan else navItemColor,
            shape = AzButtonShape.RECTANGLE,
            info = navStrings.helpInfo
        ) {
            onHelpToggle()
        }
    }
}


@Composable
private fun WallSourceDialog(
    onDismiss: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    strings: AppStrings
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(DesignR.string.wall_source_title)) },
        text = { Text(stringResource(DesignR.string.wall_source_text)) },
        confirmButton = {
            AzButton(text = stringResource(DesignR.string.take_photo), onClick = onCamera, shape = AzButtonShape.RECTANGLE)
        },
        dismissButton = {
            AzButton(text = stringResource(DesignR.string.choose_from_gallery), onClick = onGallery, shape = AzButtonShape.RECTANGLE)
        }
    )
}

@Composable
private fun DepthApiUnsupportedBanner(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xEECC4400), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text = stringResource(DesignR.string.depth_unsupported),
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
                text = stringResource(DesignR.string.arcore_required_title),
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(DesignR.string.arcore_required_text),
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )
            AzButton(
                text = stringResource(DesignR.string.install_arcore),
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
                },
                shape = AzButtonShape.RECTANGLE
            )
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
                text = stringResource(DesignR.string.camera_permission_required),
                color = Color.White,
                textAlign = TextAlign.Center
            )
            AzButton(
                text = stringResource(DesignR.string.open_settings),
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                },
                color = Color(0xFFCC2200),
                shape = AzButtonShape.RECTANGLE
            )
        }
    }
}

@Composable
private fun TapTargetOverlay(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    strings: AppStrings
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
                    text = strings.ar.targetCreationTitle,
                    color = Color(0xFF007788),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.ar.targetCreationText,
                    color = Color(0xFF222222),
                    textAlign = TextAlign.Start
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AzButton(
            text = strings.common.cancel,
            onClick = onCancel,
            color = Color.Gray,
            shape = AzButtonShape.RECTANGLE
        )
    }
}

@Composable
private fun DiagPopup(
    diagLog: String?,
    modifier: Modifier = Modifier,
    strings: AppStrings
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
                    if (copied) strings.ar.diagCopied else strings.ar.diagTitle,
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
                text = diagLog ?: strings.ar.diagWaiting,
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
        ScanPhase.AMBIENT -> stringResource(DesignR.string.scan_step_1)
        ScanPhase.WALL -> stringResource(DesignR.string.scan_step_2)
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
    modifier: Modifier = Modifier,
    strings: AppStrings
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
                    text = strings.ar.planeConfirmQuestion,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AzButton(
                        text = strings.ar.looksCorrect,
                        onClick = onConfirm,
                        color = Color(0xFF2E7D32),
                        shape = AzButtonShape.RECTANGLE
                    )
                    AzButton(
                        text = strings.ar.redetect,
                        onClick = onRedetect,
                        color = Color(0xFFFF8C00),
                        shape = AzButtonShape.RECTANGLE
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaneRealignmentOverlay(
    onTryThisPlane: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    strings: AppStrings
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
                    text = strings.ar.planeRealignmentTitle,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.ar.planeRealignmentText,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Start
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AzButton(
                        text = strings.ar.useThisWall,
                        onClick = onTryThisPlane,
                        color = Color(0xFF2E7D32),
                        shape = AzButtonShape.RECTANGLE
                    )
                    AzButton(
                        text = strings.common.cancel,
                        onClick = onCancel,
                        color = Color(0xFFFF8C00),
                        shape = AzButtonShape.RECTANGLE
                    )
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
    val feetLabel = stringResource(DesignR.string.unit_feet)
    val cmLabel = stringResource(DesignR.string.unit_centimeters)
    val mLabel = stringResource(DesignR.string.unit_meters)

    val label = if (imperial) {
        val feet = distanceMeters * 3.28084f
        "%.1f %s".format(feet, feetLabel)
    } else {
        if (distanceMeters < 1f) "${(distanceMeters * 100).toInt()} %s".format(cmLabel)
        else "%.1f %s".format(distanceMeters, mLabel)
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
    modifier: Modifier = Modifier,
    strings: AppStrings
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
        RelocState.SEARCHING -> strings.ar.scanning
        RelocState.TRACKING  -> strings.ar.matchedPercent((paintingProgress * 100).toInt())
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
private fun AnchorLockFlash(isAnchorEstablished: Boolean, strings: AppStrings) {
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
                    strings.ar.anchorLocked,
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
    onDismiss: () -> Unit,
    strings: AppStrings
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
        title = { Text(strings.editor.chooseFont) },
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


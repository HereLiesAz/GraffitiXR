// FILE: app/src/main/java/com/hereliesaz/graffitixr/MainActivity.kt
package com.hereliesaz.graffitixr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.Canvas
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
import kotlinx.coroutines.withContext
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
import com.hereliesaz.graffitixr.common.model.MuralMethod
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
import com.hereliesaz.graffitixr.feature.editor.MockupScreen
import com.hereliesaz.graffitixr.feature.editor.OverlayScreen
import com.hereliesaz.graffitixr.feature.editor.TraceScreen
import com.hereliesaz.graffitixr.data.OnboardingManager
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
                            mainViewModel.setCaptureStep(CaptureStep.MASK)
                        } else if (currentCaptureStep == CaptureStep.CAPTURE) {
                            mainViewModel.setCaptureStep(CaptureStep.MASK)
                        }
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
                            runCatching { EditorMode.valueOf(route) }.getOrNull()?.let { mode ->
                                if (editorUiState.editorMode != mode) editorViewModel.setEditorMode(mode)
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

                LaunchedEffect(arUiState.isAnchorEstablished) {
                    if (!arUiState.isAnchorEstablished && mainViewModel.uiState.value.isInPlaneRealignment) {
                        mainViewModel.endPlaneRealignment()
                    }
                }

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

                BackHandler(enabled = showLibrary) { showLibrary = false }
                BackHandler(enabled = showSettings) { showSettings = false }
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
                        !showSettings &&
                        !isExporting

                var permissionRequestedAtLeastOnce by remember { mutableStateOf(hasCameraPermission) }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) {
                        val permissions = mutableListOf(Manifest.permission.CAMERA)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        } else {
                            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                    permissionRequestedAtLeastOnce = true
                }

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

                var showDesignInstructionsDialog by remember { mutableStateOf(false) }

                LaunchedEffect(arUiState.isAnchorEstablished) {
                    if (arUiState.isAnchorEstablished && editorUiState.layers.isEmpty()) {
                        showDesignInstructionsDialog = true
                    }
                }

                val strings = rememberAppStrings()
                val navStrings = strings.nav
                var showFontPicker by remember { mutableStateOf(false) }
                var fontPickerLayerId by remember { mutableStateOf<String?>(null) }
                var showHelp by remember { mutableStateOf(false) }
                val layerMenusOpen = remember { mutableStateMapOf<String, Boolean>() }

                val context = LocalContext.current
                val canvasBg = editorUiState.canvasBackground

                val navItemColor = remember(canvasBg) {
                    val luminance = 0.299f * canvasBg.red + 0.587f * canvasBg.green + 0.114f * canvasBg.blue
                    if (luminance > 0.5f) Color.Black else Color.White
                }

                val allHelpItems = remember(editorUiState.layers, strings, context) {
                    buildHelpItems(strings, editorUiState.layers) +
                            ("wearable.main" to context.getString(DesignR.string.nav_wearable_info))
                }

                val helpViewModel: HelpViewModel =
                    hiltViewModel(checkNotNull<ViewModelStoreOwner>(LocalViewModelStoreOwner.current) {
                        "No ViewModelStoreOwner was provided via LocalViewModelStoreOwner"
                    }, null)
                val activeHelpList by helpViewModel.activeHelpList.collectAsState()

                LaunchedEffect(allHelpItems) {
                    helpViewModel.setActiveHelpList(allHelpItems)
                }

                val modeTutorials = remember(context) { getGraffitiTutorials(context) }
                val tutorials = getTutorials(editorUiState.layers, strings) + modeTutorials

                if (BuildConfig.DEBUG) {
                    RailIntegrityCheck.verify(
                        layers = editorUiState.layers,
                        mode = editorUiState.editorMode,
                        helpList = allHelpItems,
                        tutorials = tutorials,
                    )
                }

                AzHostActivityLayout(navController = navController, initiallyExpanded = false) {
                    azTheme(
                        activeColor = Cyan,
                        defaultShape = AzButtonShape.RECTANGLE,
                        headerIconShape = AzHeaderIconShape.ROUNDED,
                        translucentBackground = Color.Transparent
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
                            showLibrary = showLibrary
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

                        val tutorialController = LocalAzTutorialController.current
                        LaunchedEffect(editorUiState.editorMode, showLibrary, completedTutorials) {
                            if (showLibrary) return@LaunchedEffect

                            val noLayers = editorUiState.layers.isEmpty()
                            val noAnchor = !arUiState.isAnchorEstablished

                            if (noLayers && (editorUiState.editorMode != EditorMode.AR || noAnchor)) {
                                val tutorialId = when (editorUiState.editorMode) {
                                    EditorMode.AR      -> "mode.ar.firstRun"
                                    EditorMode.OVERLAY -> "mode.overlay.firstRun"
                                    EditorMode.MOCKUP  -> "mode.mockup.firstRun"
                                    EditorMode.TRACE   -> "mode.trace.firstRun"
                                    else               -> null
                                }
                                val key = "tut_${editorUiState.editorMode.name.lowercase()}"
                                if (tutorialId != null) {
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
                                composable(EditorMode.AR.name) {
                                    ModeOnboarding(EditorMode.AR, showLibrary, editorViewModel, arUiState)
                                    EditorOverlay(editorViewModel, mainUiState, strings)
                                }
                                composable(EditorMode.OVERLAY.name) {
                                    OverlayScreen(editorViewModel, showLibrary, arUiState)
                                    EditorOverlay(editorViewModel, mainUiState, strings)
                                }
                                composable(EditorMode.MOCKUP.name) {
                                    MockupScreen(editorViewModel, showLibrary, arUiState)
                                    EditorOverlay(editorViewModel, mainUiState, strings)
                                }
                                composable(EditorMode.TRACE.name) {
                                    TraceScreen(editorViewModel, showLibrary, arUiState)
                                    EditorOverlay(editorViewModel, mainUiState, strings)
                                }
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
                                    && arUiState.arScanMode == ArScanMode.MURAL
                                    && arUiState.scanPhase != ScanPhase.COMPLETE
                            if (isScanningPhase && !mainUiState.isCapturingTarget && !showLibrary && !showSettings) {
                                ScanCoachingOverlay(
                                    splatCount = arUiState.splatCount,
                                    immutableCount = arUiState.immutableSplatCount,
                                    hint = arUiState.scanHint,
                                    scanPhase = arUiState.scanPhase,
                                    ambientSectorsCovered = arUiState.ambientSectorsCovered,
                                    worldMappingProgress = arUiState.worldMappingProgress,
                                    visitedSectorsMask = arUiState.visitedSectorsMask,
                                    muralMethod = arUiState.muralMethod,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 96.dp)
                                )
                            }

                            val showDepthWarning = editorUiState.editorMode == EditorMode.AR
                                    && arUiState.arScanMode == ArScanMode.MURAL
                                    && !arUiState.isDepthApiSupported
                                    && arUiState.splatCount == 0
                            if (showDepthWarning && !showLibrary && !showSettings) {
                                DepthApiUnsupportedBanner(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 16.dp)
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR
                                && !arUiState.isArCoreAvailable
                                && !showLibrary && !showSettings
                            ) {
                                ArCoreUnavailableOverlay(
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }

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

                            LaunchedEffect(arUiState.targetPhysicalExtent) {
                                arUiState.targetPhysicalExtent?.let { (w, h) ->
                                    editorViewModel.setAnchorExtent(w, h)
                                }
                            }

                            val showPostTargetHint = arUiState.isAnchorEstablished
                                    && editorUiState.layers.isEmpty()
                                    && !mainUiState.isCapturingTarget
                                    && editorUiState.editorMode == EditorMode.AR
                                    && !showLibrary && !showSettings
                            if (showPostTargetHint) {
                                PostTargetInstructionOverlay(
                                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
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
                                    && false
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
                                && false
                            ) {
                                DistanceBadge(
                                    distanceMeters = distanceM,
                                    imperial = arUiState.isImperialUnits,
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(top = 16.dp, start = 16.dp)
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings && !arUiState.isAnchorEstablished) {
                                RelocStatusBadge(
                                    isAnchorEstablished = arUiState.isAnchorEstablished,
                                    paintingProgress = arUiState.paintingProgress,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 16.dp, end = 16.dp),
                                    strings = strings
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && editorUiState.showDiagOverlay && !arUiState.isAnchorEstablished) {
                                DiagnosticOverlay(
                                    uiState = arUiState,
                                    modifier = Modifier.align(Alignment.TopStart).padding(top = 100.dp, start = 16.dp)
                                )
                                DiagPopup(
                                    diagLog = arUiState.diagLog,
                                    modifier = Modifier.align(Alignment.TopStart),
                                    strings = strings
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings) {
                                AnchorLockFlash(isAnchorEstablished = arUiState.isAnchorEstablished, strings = strings)
                            }

                            SyncingBadge(
                                isSyncing = arUiState.coopSessionState is com.hereliesaz.graffitixr.common.model.CoopSessionState.Connected
                                        || arUiState.coopSessionState is com.hereliesaz.graffitixr.common.model.CoopSessionState.Reconnecting,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(bottom = 120.dp),
                                strings = strings
                            )

                            OffscreenIndicators(
                                uiState = editorUiState,
                                arUiState = arUiState,
                                screenSize = fullSize
                            )

                            if (arUiState.showCoopNotFoundDialog) {
                                CoopNotFoundDialog(
                                    onDismiss = { arViewModel.dismissCoopNotFoundDialog() },
                                    onHost = { arViewModel.startHosting() },
                                    onSearch = { /* TODO Task 15: open QR scanner for joinFromQr */ arViewModel.dismissCoopNotFoundDialog() },
                                    canHost = arUiState.isAnchorEstablished && arUiState.splatCount > 0,
                                    strings = strings
                                )
                            }

                            if (mainUiState.isCapturingTarget) {
                                TargetCreationUi(
                                    uiState = arUiState,
                                    captureStep = mainUiState.captureStep,
                                    isWaitingForTap = mainUiState.isWaitingForTap,
                                    isLoading = isProcessing,
                                    strings = strings,
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
                                                val pixelPoints = points.map {
                                                    Offset(it.x * currentBitmap.width, it.y * currentBitmap.height)
                                                }
                                                val unwarped = ImageProcessor.unwarpImage(currentBitmap, pixelPoints)
                                                val mask = arUiState.annotatedCaptureBitmap
                                                val unwarpedMask = if (mask != null) ImageProcessor.unwarpImage(mask, pixelPoints) else null

                                                withContext(Dispatchers.Main) {
                                                    if (unwarped != null) {
                                                        arViewModel.setTempCapture(unwarped)
                                                        arViewModel.setAnnotatedCapture(unwarpedMask)
                                                        arViewModel.setInitialAnchorFromCapture()
                                                        mainViewModel.onConfirmTargetCreation(
                                                            unwarped,
                                                            unwarpedMask,
                                                            arUiState.targetDepthBuffer,
                                                            arUiState.targetDepthBufferWidth,
                                                            arUiState.targetDepthBufferHeight,
                                                            arUiState.targetDepthStride,
                                                            arUiState.targetIntrinsics,
                                                            arUiState.targetCaptureViewMatrix
                                                        )
                                                    } else {
                                                        mainViewModel.setCaptureStep(CaptureStep.NONE)
                                                    }
                                                    isProcessing = false
                                                }
                                            }
                                        }
                                    },
                                    onMaskConfirmed = { mask ->
                                        arViewModel.setAnnotatedCapture(mask)
                                        mainViewModel.setCaptureStep(CaptureStep.RECTIFY)
                                    },
                                    onUpdateUnwarpPoints = { arViewModel.setUnwarpPoints(it) },
                                    onEraseAtPoint = { nx, ny, r -> arViewModel.applyEraseToMask(nx, ny, r) }
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

                            if (showDesignInstructionsDialog) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showDesignInstructionsDialog = false },
                                    title = { Text("Design Your Mural", color = Color.White) },
                                    text = { Text("Tap 'Design' in the menu, then press 'Image' to import one, 'Sketch' to draw one, or 'Text' to write one.", color = Color.White) },
                                    containerColor = Color(0xEE1A1A1A),
                                    confirmButton = {
                                        AzButton(text = "Got it", onClick = { showDesignInstructionsDialog = false }, shape = AzButtonShape.RECTANGLE)
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
                                    muralMethod = arUiState.muralMethod,
                                    onMuralMethodChanged = { arViewModel.setMuralMethod(it) },
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

    @Composable
    private fun ModeOnboarding(mode: EditorMode, isLibraryVisible: Boolean, viewModel: EditorViewModel, arUiState: ArUiState) {
        val context = LocalContext.current
        val uiState by viewModel.uiState.collectAsState()
        val onboardingManager = remember(context) { OnboardingManager(context) }
        var showOnboarding by remember(mode) { mutableStateOf(false) }

        LaunchedEffect(isLibraryVisible, uiState.editorMode, mode, arUiState.isAnchorEstablished) {
            if (!isLibraryVisible && uiState.editorMode == mode) {
                val noLayers = uiState.layers.isEmpty()
                val noAnchor = !arUiState.isAnchorEstablished
                val isScreenEmpty = noLayers && (mode != EditorMode.AR || noAnchor)

                if (isScreenEmpty || onboardingManager.isFirstTime(mode.name)) {
                    showOnboarding = true
                    onboardingManager.markAsSeen(mode.name)
                }
            }
        }

        if (showOnboarding) {
            com.hereliesaz.graffitixr.design.components.OnboardingDialog(
                mode = mode,
                onDismiss = { showOnboarding = false }
            )
        }
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
        showLibrary: Boolean
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

            azRailHostItem(id = "mode.host", text = navStrings.modes, color = navItemColor)
            azRailSubItem(id = "mode.ar", hostId = "mode.host", text = navStrings.arMode, route = EditorMode.AR.name, color = navItemColor, shape = AzButtonShape.NONE)

            if (isArMode) {
                val scanModeText = when (arUiState.arScanMode) {
                    ArScanMode.CLOUD_POINTS -> navStrings.canvas
                    ArScanMode.MURAL -> navStrings.mural
                }
                azRailSubItem(
                    id = "target.scanModeCycle",
                    hostId = "mode.host",
                    text = scanModeText,
                    color = Cyan,
                    shape = AzButtonShape.RECTANGLE
                ) {
                    val modes = ArScanMode.entries
                    val next = modes[(arUiState.arScanMode.ordinal + 1) % modes.size]
                    arViewModel.setArScanMode(next)
                }

                azRailSubItem(
                    id = "coop.main",
                    hostId = "mode.host",
                    text = when (arUiState.coopSessionState) {
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.Idle -> "Co-op"
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.WaitingForGuest -> "Waiting…"
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.Connected -> "Connected"
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.Reconnecting -> "Reconnecting…"
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.Ended -> "Co-op"
                    },
                    color = when (arUiState.coopSessionState) {
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.WaitingForGuest,
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.Reconnecting,
                        is com.hereliesaz.graffitixr.common.model.CoopSessionState.Connected -> Cyan
                        else -> navItemColor
                    },
                    shape = AzButtonShape.RECTANGLE
                ) {
                    arViewModel.dismissCoopNotFoundDialog() // TODO Task 15: open QR entry UI
                }
            }

            azRailSubItem(id = "mode.overlay", hostId = "mode.host", text = navStrings.overlay, route = EditorMode.OVERLAY.name, color = navItemColor, shape = AzButtonShape.NONE)
            azRailSubItem(id = "mode.mockup", hostId = "mode.host", text = navStrings.mockup, route = EditorMode.MOCKUP.name, color = navItemColor, shape = AzButtonShape.NONE)
            azRailSubItem(id = "mode.trace", hostId = "mode.host", text = navStrings.trace, route = EditorMode.TRACE.name, color = navItemColor, shape = AzButtonShape.NONE)

            azRailSubItem(
                id = "wearable.main",
                hostId = "mode.host",
                text = navStrings.wearable,
                color = navItemColor,
                shape = AzButtonShape.RECTANGLE
            ) {
                // Future: launch wearable connection flow
            }

            azDivider()

            if (isArMode) {
                azRailHostItem(id = "target.host", text = navStrings.grid, color = navItemColor)

                azRailSubItem(id = "target.create", hostId = "target.host", text = navStrings.create, color = navItemColor, shape = AzButtonShape.NONE) {
                    if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions()
                }

                azDivider()
            }

            val isGuest = arUiState.coopRole == com.hereliesaz.graffitixr.common.model.CoopRole.GUEST
            val canEdit = if (isArMode)
                (arUiState.scanPhase == ScanPhase.COMPLETE || arUiState.isAnchorEstablished) && !isGuest
            else true

            if (canEdit) {
                azRailHostItem(id = "design.host", text = navStrings.design, color = navItemColor)
                azRailSubItem(id = "design.addImg", hostId = "design.host", text = navStrings.image, color = navItemColor, shape = AzButtonShape.NONE) {
                    overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                azRailSubItem(id = "design.addDraw", hostId = "design.host", text = navStrings.draw, color = navItemColor, shape = AzButtonShape.NONE) {
                    editorViewModel.onAddBlankLayer()
                }
                azRailSubItem(id = "design.addText", hostId = "design.host", text = navStrings.text, color = navItemColor, shape = AzButtonShape.NONE) {
                    editorViewModel.onAddTextLayer()
                }

                if (editorUiState.editorMode == EditorMode.MOCKUP) {
                    azRailSubItem(id = "design.wall", hostId = "design.host", text = navStrings.wall, color = navItemColor, shape = AzButtonShape.NONE) {
                        showWallSourceDialog = true
                    }
                }

                azDivider()
            }
        }

        azRailHostItem(id = "project.host", text = navStrings.project, color = navItemColor)
        azRailSubItem(id = "project.new", hostId = "project.host", text = navStrings.new, color = navItemColor, shape = AzButtonShape.NONE) {
            dashboardViewModel.onNewProjectTriggered()
        }
        azRailSubItem(id = "project.save", hostId = "project.host", text = navStrings.save, color = navItemColor, shape = AzButtonShape.NONE) {
            showSaveDialog = true
        }
        azRailSubItem(id = "project.load", hostId = "project.host", text = navStrings.load, color = navItemColor, shape = AzButtonShape.NONE) {
            this@MainActivity.showLibrary = true
        }
        azRailSubItem(id = "project.export", hostId = "project.host", text = navStrings.export, color = navItemColor, shape = AzButtonShape.NONE) {
            if (editorUiState.editorMode == EditorMode.AR || editorUiState.editorMode == EditorMode.OVERLAY) {
                arViewModel.requestExport { bgBitmap ->
                    editorViewModel.exportImage(bgBitmap)
                }
            } else {
                editorViewModel.exportImage(null)
            }
        }
        azRailSubItem(id = "project.settings", hostId = "project.host", text = navStrings.settings, color = navItemColor, shape = AzButtonShape.NONE) {
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
                        id = layerId(layer),
                        hostId = "design.host",
                        text = layer.name,
                        color = if (layer.isLinked) Cyan else navItemColor,
                        nestedRailAlignment = AzNestedRailAlignment.VERTICAL,
                        keepNestedRailOpen = true,
                        forceHiddenMenuOpen = forceOpenHiddenMenu,
                        onHiddenMenuDismiss = { layerMenusOpen[layer.id] = false },
                        onClick = {
                            editorViewModel.onLayerActivated(layer.id)
                            editorViewModel.setActiveTool(Tool.NONE)
                        },
                        onRelocate = { _, _, new -> editorViewModel.onLayerReordered(new.map { it.removePrefix("layer.") }.reversed()) },
                        nestedContent = {
                            val activate = { editorViewModel.onLayerActivated(layer.id) }

                            if (layer.textParams != null) {
                                azRailItem(
                                    id = layerId(layer, "editText"),
                                    text = navStrings.edit,
                                    color = navItemColor,
                                    shape = AzButtonShape.RECTANGLE
                                ) {
                                    activate()
                                    layerMenusOpen[layer.id] = true
                                }
                            }

                            val addSizeItem: () -> Unit = {
                                azRailItem(
                                    id = layerId(layer, "size"),
                                    text = navStrings.size,
                                    color = navItemColor,
                                    shape = AzButtonShape.RECTANGLE,
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
                                    azRailItem(
                                        id = layerId(layer, "font"),
                                        text = navStrings.font,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE
                                    ) {
                                        activate()
                                        onShowFontPicker(layer.id)
                                    }
                                    azRailItem(
                                        id = layerId(layer, "size"),
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
                                        id = layerId(layer, "color"),
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
                                        id = layerId(layer, "kern"),
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
                                    azRailItem(
                                        id = layerId(layer, "bold"),
                                        text = navStrings.bold,
                                        color = if (tp.isBold) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, !tp.isBold, tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailItem(
                                        id = layerId(layer, "italic"),
                                        text = navStrings.italic,
                                        color = if (tp.isItalic) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, !tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailItem(
                                        id = layerId(layer, "outline"),
                                        text = navStrings.outline,
                                        color = if (tp.hasOutline) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, !tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailItem(
                                        id = layerId(layer, "shadow"),
                                        text = navStrings.shadow,
                                        color = if (tp.hasDropShadow) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, tp.hasOutline, !tp.hasDropShadow)
                                    }
                                    if (layer.stencilType == null) {
                                        azRailItem(
                                            id = layerId(layer, "stencil"),
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
                                    azRailItem(
                                        id = layerId(layer, "blend"),
                                        text = navStrings.build,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onCycleBlendMode() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "adj"),
                                        text = navStrings.adjust,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onAdjustClicked() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "invert"),
                                        text = navStrings.invert,
                                        color = if (layer.isInverted) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onToggleInvert() }
                                    )
                                }
                                layer.isSketch -> {
                                    azRailItem(
                                        id = layerId(layer, "blend"),
                                        text = navStrings.build,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onCycleBlendMode() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "adj"),
                                        text = navStrings.adjust,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onAdjustClicked() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "invert"),
                                        text = navStrings.invert,
                                        color = if (layer.isInverted) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onToggleInvert() }
                                    )
                                    if (layer.stencilType == null) {
                                        azRailItem(
                                            id = layerId(layer, "stencil"),
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
                                    azRailItem(
                                        id = layerId(layer, "balance"),
                                        text = navStrings.balance,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onBalanceClicked() }
                                    )
                                    // --- Brush tools at bottom ---
                                    azRailItem(
                                        id = layerId(layer, "eraser"),
                                        text = navStrings.eraser,
                                        color = if (activeTool == Tool.ERASER) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "blur"),
                                        text = navStrings.blur,
                                        color = if (activeTool == Tool.BLUR) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "liquify"),
                                        text = navStrings.liquify,
                                        color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "dodge"),
                                        text = navStrings.dodge,
                                        color = if (activeTool == Tool.DODGE) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "burn"),
                                        text = navStrings.burn,
                                        color = if (activeTool == Tool.BURN) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                                    )
                                    addSizeItem()
                                }
                                else -> {
                                    azRailItem(
                                        id = layerId(layer, "iso"),
                                        text = navStrings.isolate,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onRemoveBackgroundClicked() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "line"),
                                        text = navStrings.outline,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onSketchClicked() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "invert"),
                                        text = navStrings.invert,
                                        color = if (layer.isInverted) Cyan else navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onToggleInvert() }
                                    )
                                    if (layer.stencilType == null) {
                                        azRailItem(
                                            id = layerId(layer, "stencil"),
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
                                    azRailItem(
                                        id = layerId(layer, "adj"),
                                        text = navStrings.adjust,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onAdjustClicked() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "balance"),
                                        text = navStrings.balance,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onBalanceClicked() }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "blend"),
                                        text = navStrings.build,
                                        color = navItemColor,
                                        shape = AzButtonShape.RECTANGLE,
                                        onClick = { activate(); editorViewModel.onCycleBlendMode() }
                                    )

                                    // --- Brush tools at bottom ---
                                    azRailItem(
                                        id = layerId(layer, "eraser"),
                                        text = navStrings.eraser,
                                        color = if (activeTool == Tool.ERASER) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.ERASER) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "blur"),
                                        text = navStrings.blur,
                                        color = if (activeTool == Tool.BLUR) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "liquify"),
                                        text = navStrings.liquify,
                                        color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "dodge"),
                                        text = navStrings.dodge,
                                        color = if (activeTool == Tool.DODGE) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                                    )
                                    azRailItem(
                                        id = layerId(layer, "burn"),
                                        text = navStrings.burn,
                                        color = if (activeTool == Tool.BURN) Cyan else navItemColor,
                                        onClick = { activate(); editorViewModel.setActiveTool(Tool.BURN) }
                                    )
                                    addSizeItem()
                                }
                            }

                            azHelpRailItem(
                                id = "${layerId(layer)}.help",
                                text = navStrings.help,
                                color = navItemColor,
                                shape = AzButtonShape.RECTANGLE
                            )
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
                azRailItem(id = "tool.light", text = navStrings.light, color = navItemColor, onClick = { arViewModel.toggleFlashlight() })
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
            azRailItem(id = "tool.lockTrace", text = lockText, color = navItemColor, onClick = lockAction)
        }

        azDivider()

        azHelpRailItem(
            id = "tool.helpMain",
            text = navStrings.help,
            color = navItemColor,
            shape = AzButtonShape.RECTANGLE
        )
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
    val context = LocalContext.current
    val displayMetrics = context.resources.displayMetrics
    val screenWidthPx = displayMetrics.widthPixels.toFloat()
    val screenHeightPx = displayMetrics.heightPixels.toFloat()

    var offsetX by remember { mutableFloatStateOf(16f) }
    var offsetY by remember { mutableFloatStateOf(80f) }
    var visible by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (!visible) return

    Box(
        modifier = modifier
            .offset { IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX = (offsetX + dragAmount.x).coerceIn(0f, screenWidthPx - 200f)
                    offsetY = (offsetY + dragAmount.y).coerceIn(0f, screenHeightPx - 200f)
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
    immutableCount: Int,
    hint: String?,
    modifier: Modifier = Modifier,
    scanPhase: ScanPhase = ScanPhase.AMBIENT,
    ambientSectorsCovered: Int = 0,
    worldMappingProgress: Float = 0f,
    visitedSectorsMask: Long = 0L,
    muralMethod: MuralMethod = MuralMethod.VOXEL_HASH
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
                        SectorRingIndicator(visitedSectorsMask = visitedSectorsMask)

                        Text(
                            text = "${(worldMappingProgress * 100).toInt()}%",
                            color = Color.Cyan,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "360° MAPPED",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { (splatCount / 50_000f).coerceIn(0f, 1f) },
                            modifier = Modifier.width(100.dp),
                            color = Color.Cyan,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                        val displayTotal = splatCount / 1000
                        val displayImmutable = immutableCount / 1000
                        Text(
                            text = "${displayTotal}k (${displayImmutable}k locked) / 50k",
                            color = Color.LightGray,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectorRingIndicator(visitedSectorsMask: Long, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(40.dp)) {
        val ringRadius = size.minDimension / 2f * 0.70f
        val strokeW    = size.minDimension / 2f * 0.45f
        val gapDeg     = 3f
        val sweepDeg   = 360f / 36f - gapDeg
        for (i in 0..35) {
            val startAngle = -90f + i * (360f / 36f) + gapDeg / 2f
            val isVisited  = (visitedSectorsMask ushr i) and 1L != 0L
            drawArc(
                color      = if (isVisited) Color.Cyan else Color.White.copy(alpha = 0.18f),
                startAngle = startAngle,
                sweepAngle = sweepDeg,
                useCenter  = false,
                topLeft    = androidx.compose.ui.geometry.Offset(center.x - ringRadius, center.y - ringRadius),
                size       = androidx.compose.ui.geometry.Size(ringRadius * 2, ringRadius * 2),
                style      = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = strokeW,
                    cap   = androidx.compose.ui.graphics.StrokeCap.Butt
                )
            )
        }
    }
}

@Composable
private fun CoopNotFoundDialog(
    onDismiss: () -> Unit,
    onHost: () -> Unit,
    onSearch: () -> Unit,
    canHost: Boolean,
    strings: AppStrings
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .background(Color(0xEE1A1A1A), RoundedCornerShape(16.dp))
                .border(2.dp, Color.Cyan, RoundedCornerShape(16.dp))
                .padding(24.dp)
                .clickable(enabled = false) { }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "NO SESSIONS FOUND",
                    color = Color.Cyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Nearby GraffitiXR sessions could not be located. You can try searching again or host your own session.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AzButton(
                        text = "HOST",
                        onClick = { if (canHost) onHost() },
                        color = if (canHost) HotPink else Color.Gray,
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.weight(1f)
                    )
                    AzButton(
                        text = "SEARCH",
                        onClick = onSearch,
                        color = HotPink,
                        shape = AzButtonShape.RECTANGLE,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (!canHost) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Establish a target first to host.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticOverlay(
    uiState: ArUiState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .border(1.dp, Cyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(12.dp)
            .width(220.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "AR DIAGNOSTICS",
                color = Cyan,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )

            val lensMode = when {
                uiState.isHardwareStereoActive -> "MANDATORY HW"
                uiState.isDualLensActive -> "SW STEREO"
                else -> "SINGLE (HW NOT FOUND)"
            }
            DiagnosticRow("Lens Mode", lensMode, if (uiState.isDualLensActive) Cyan else Color.Gray)
            DiagnosticRow("Depth (Ctr)", if (uiState.currentCenterDepth > 0) "%.2fm".format(uiState.currentCenterDepth) else "---", Color.White)

            Spacer(Modifier.height(4.dp))

            Text(text = "CONFIDENCE", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            ConfidenceProgressBar("Visible", uiState.visibleSplatConfidenceAvg)
            ConfidenceProgressBar("Global", uiState.globalSplatConfidenceAvg)

            Spacer(Modifier.height(4.dp))

            DiagnosticRow("Splats", "${uiState.splatCount}", Color.White)
            DiagnosticRow("Immutable", "${uiState.immutableSplatCount}", if (uiState.immutableSplatCount > 0) HotPink else Color.White)

            val sensors = uiState.sensorData
            if (sensors != null) {
                Spacer(Modifier.height(4.dp))
                Text(text = "SENSOR DATA", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "A: %.1f° P: %.1f° R: %.1f°".format(sensors.azimuth, sensors.pitch, sensors.roll),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.Gray, fontSize = 11.sp)
        Text(text = value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConfidenceProgressBar(label: String, progress: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = label, color = Color.White, fontSize = 10.sp)
            Text(text = "${(progress * 100).toInt()}%", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = Cyan,
            trackColor = Color.White.copy(alpha = 0.1f)
        )
    }
}

@Composable
private fun PostTargetInstructionOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xEE1A1A1A), RoundedCornerShape(20.dp))
            .border(2.dp, Color.Cyan, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .widthIn(max = 340.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "TARGET ESTABLISHED",
                color = Color.Cyan,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Now, open 'Design' in the sidebar and choose Image, Sketch, or Text to create your artwork layer.",
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
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
private fun SyncingBadge(
    isSyncing: Boolean,
    modifier: Modifier = Modifier,
    strings: AppStrings
) {
    if (!isSyncing) return

    val infiniteTransition = rememberInfiniteTransition(label = "sync_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Row(
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(pulseAlpha)
                .background(Color.Cyan, CircleShape)
        )
        Text(
            text = strings.ar.syncing,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
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
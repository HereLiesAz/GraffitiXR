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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.content.FileProvider
import java.io.File
import com.meta.wearable.dat.core.Wearables
import com.google.android.gms.common.GoogleApiAvailability
import com.hereliesaz.aznavrail.*
import com.hereliesaz.aznavrail.model.*
import com.hereliesaz.aznavrail.HiddenMenuScope
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.CaptureStep
import com.hereliesaz.graffitixr.common.model.ScanPhase
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.EditorUiState
import com.hereliesaz.graffitixr.onboarding.ArUnavailableOverlay
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
import com.hereliesaz.graffitixr.design.theme.NeonGreen
import com.hereliesaz.graffitixr.design.theme.NavStrings
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
import com.hereliesaz.graffitixr.common.model.CoopSessionState
import com.hereliesaz.graffitixr.common.model.CoopRole
import com.hereliesaz.graffitixr.ui.coop.CoopHostQrOverlay
import com.hereliesaz.graffitixr.ui.coop.CoopJoinQrScannerOverlay
import com.hereliesaz.graffitixr.ui.coop.CoopSpectatorBanner
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
import androidx.compose.material.icons.filled.*
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
import com.hereliesaz.graffitixr.design.theme.AppStrings
import com.hereliesaz.graffitixr.design.theme.rememberAppStrings
import com.hereliesaz.graffitixr.design.theme.rememberNavStrings
import timber.log.Timber
import kotlin.math.abs

private const val LIBRARY_ROUTE = "library"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository
    @Inject lateinit var securityProviderManager: SecurityProviderManager

    private val qrScannerLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            arViewModel.joinFromQr(result.contents)
        }
    }

    private val arViewModel: ArViewModel by viewModels()

    var showSaveDialog by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showPosterDialog by mutableStateOf(false)
    var posterSourceLayerId by mutableStateOf<String?>(null)
    var hasCameraPermission by mutableStateOf(false)
    var hasBluetoothPermission by mutableStateOf(false)
    var showWallSourceDialog by mutableStateOf(false)
    var isExporting by mutableStateOf(false)
    // Crash report captured on the previous run (native SIGSEGV and/or JVM), shown on launch.
    var pendingCrashReport by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { p ->
        hasCameraPermission = p[Manifest.permission.CAMERA] ?: false
        hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p[Manifest.permission.BLUETOOTH_CONNECT] ?: false
        } else {
            true
        }
        if (hasBluetoothPermission) {
            checkAndInitializeWearables()
        }
    }

    private fun checkAndInitializeWearables() {
        val bluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (bluetoothPermission) {
            try {
                Wearables.initialize(this)
            } catch (e: Exception) {
                // Ignore initialization errors if already initialized or permissions still missing
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        hasBluetoothPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // Surface any crash captured on the previous run: native backtrace (signal handler) and/or
        // the JVM CrashReporter dump. Read + delete so it shows exactly once.
        run {
            val parts = mutableListOf<String>()
            listOf(
                "last_native_crash.txt" to "native crash",
                // The hardware-stereo/depth probe runs in the isolated ":probe" process; a native crash
                // there is expected on devices with a broken depth graph and is benign (the probe times
                // out and AR falls back to mono). Surface it for debugging, but framed so it is not
                // mistaken for an app crash.
                "last_native_crash_probe.txt" to "probe-process native crash — ISOLATED, not an app crash (AR fell back to mono)",
                "last_crash.txt" to "JVM crash"
            ).forEach { (name, label) ->
                val f = java.io.File(cacheDir, name)
                if (f.exists()) {
                    runCatching { parts.add("=== $label ($name) ===\n" + f.readText()) }
                    runCatching { f.delete() }
                }
            }
            if (parts.isNotEmpty()) pendingCrashReport = parts.joinToString("\n\n")
        }

        securityProviderManager.installAsync(this)
        slamManager.ensureInitialized()
        checkAndInitializeWearables()

        lifecycleScope.launch {
            securityProviderManager.securityProviderState.collect { state ->
                if (state is SecurityProviderState.RecoverableError) {
                    GoogleApiAvailability.getInstance().getErrorDialog(this@MainActivity, state.errorCode, 9000)?.show()
                }
            }
        }

        setContent {
            GraffitiXRTheme {
                pendingCrashReport?.let { report ->
                    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { pendingCrashReport = null },
                        title = { androidx.compose.material3.Text("Previous crash captured") },
                        text = {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                androidx.compose.material3.Text(
                                    report,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    modifier = Modifier.verticalScroll(rememberScrollState())
                                )
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                            }) { androidx.compose.material3.Text("Copy") }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { pendingCrashReport = null }) {
                                androidx.compose.material3.Text("Dismiss")
                            }
                        }
                    )
                }
                val navController = rememberNavController()
                val currentBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = currentBackStackEntry?.destination?.route
                // Treat the brief pre-first-composition window (currentRoute == null) as
                // "library visible" so the editor doesn't flash before AzNavHost mounts.
                val showLibrary = currentRoute == null || currentRoute == LIBRARY_ROUTE

                val mainViewModel: MainViewModel = hiltViewModel()
                val editorViewModel: EditorViewModel = hiltViewModel()
                val dashboardViewModel: DashboardViewModel = hiltViewModel()
                val settingsViewModel: SettingsViewModel = hiltViewModel()
                val cameraController = rememberCameraController()

                var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }


                val editorUiState by editorViewModel.uiState.collectAsState()
                val mainUiState by mainViewModel.uiState.collectAsState()
                val arUiState by arViewModel.uiState.collectAsState()
                val coopState = arUiState.coopSessionState
                var showJoinScanner by remember { mutableStateOf(false) }
                // Which mode's whole-design adjustment panel is open (null = closed). Driven by the
                // mode Layer "Adjust" rail item.
                var modeAdjustTarget by remember { mutableStateOf<EditorMode?>(null) }
                val hostQr by arViewModel.hostQrPayload.collectAsState()
                val dashboardUiState by dashboardViewModel.uiState.collectAsState()
                val dashboardNavigation by dashboardViewModel.navigationTrigger.collectAsState()
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
                            "project_library" -> navController.navigate(LIBRARY_ROUTE) {
                                launchSingleTop = true
                            }
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

                LaunchedEffect(mainUiState.isTouchLocked, editorUiState.editorMode, arUiState.batteryTier) {
                    val params = window.attributes
                    if (mainUiState.isTouchLocked) {
                        // Keep the screen on in every mode while locked, but force MAX brightness only
                        // where it's functionally needed — the TRACE lightbox. Other modes keep system
                        // brightness (AR touch-lock at max brightness was pure waste). Even in TRACE,
                        // cap brightness once battery is low.
                        params.screenBrightness = when {
                            editorUiState.editorMode != EditorMode.TRACE ->
                                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                            arUiState.batteryTier >= 2 -> 0.85f
                            else -> 1.0f
                        }
                        window.attributes = params
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
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

                // AzNavRail caches `isExpanded` in rememberSaveable; forcing
                // noMenu on the library screen re-initialises it to false so
                // its outer fillMaxSize Box never attaches tapOutsideToCollapse.
                val railMenuDisabled = !isRailVisible || showLibrary

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

                // If a project (or restored state) puts the user in AR mode on a
                // device where ARCore is unsupported, route them to OVERLAY —
                // the closest non-AR experience, since both render artwork on
                // top of the live camera feed.
                LaunchedEffect(arUiState.isArCoreAvailabilityResolved, arUiState.isArCoreAvailable, currentRoute) {
                    if (arUiState.isArCoreAvailabilityResolved &&
                        !arUiState.isArCoreAvailable &&
                        currentRoute == EditorMode.AR.name
                    ) {
                        navController.navigate(EditorMode.OVERLAY.name) {
                            popUpTo(EditorMode.AR.name) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    arViewModel.unfreezeRequested.collect {
                        editorViewModel.toggleImageLock()
                    }
                }

                LaunchedEffect(arViewModel, editorViewModel) {
                    arViewModel.spectatorOpHandler = { op -> editorViewModel.applySpectatorOp(op) }
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
                val layerMenusOpen = remember { mutableStateMapOf<String, Boolean>() }

                val context = LocalContext.current
                val canvasBg = editorUiState.canvasBackground

                val navItemColor = remember(canvasBg) {
                    val luminance = 0.299f * canvasBg.red + 0.587f * canvasBg.green + 0.114f * canvasBg.blue
                    if (luminance > 0.5f) Color.Black else Color.White
                }

                val allHelpItems = remember(editorUiState.layers, strings) {
                    buildHelpItems(strings, editorUiState.layers)
                }

                // Scope tutorials to the same keys present in `allHelpItems` so the
                // HelpOverlay's filter (info OR helpList OR tutorial) doesn't
                // surface cards for items the user isn't looking at — e.g.
                // host sub-items that are inline expansions, not "rail" items.
                val rawTutorials = getTutorials(editorUiState.layers, strings)
                val tutorials = remember(rawTutorials, allHelpItems) {
                    rawTutorials.filterKeys { it in allHelpItems }
                }
                if (BuildConfig.DEBUG) {
                    RailIntegrityCheck.verify(
                        layers = editorUiState.layers,
                        mode = editorUiState.editorMode,
                        helpList = allHelpItems,
                        tutorials = tutorials,
                    )
                }

                // Window-space bounds of each rail item, reported by AzNavRail's
                // onItemGloballyPositioned. Drives the tutorial pointer that aims at the item the
                // current walkthrough step is asking the user to interact with.
                val railItemBounds = remember { androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }

                AzHostActivityLayout(navController = navController, currentDestination = currentRoute, initiallyExpanded = false) {
                    azTheme(
                        activeColor = Cyan,
                        defaultShape = AzButtonShape.RECTANGLE,
                        headerIconShape = AzHeaderIconShape.ROUNDED,
                        translucentBackground = Color.Transparent
                    )
                    azConfig(
                        packButtons = true,
                        dockingSide = if (editorUiState.isRightHanded) AzDockingSide.LEFT else AzDockingSide.RIGHT,
                        noMenu = railMenuDisabled
                    )
                    azAdvanced(
                        helpEnabled = true,
                        helpList = allHelpItems,
                        tutorials = tutorials,
                        // Single, reliable advancement signal: AzNavRail reports every rail
                        // interaction (tap, toggle, cycler, nested-rail open, reloc drag) here, so
                        // the do-it-to-advance walkthrough advances when the user performs the
                        // targeted action. Replaces the scattered per-item onRailTap calls.
                        onInteraction = { id, _ -> mainViewModel.onRailInteraction(id) },
                        // Window-space bounds per item, so the walkthrough can point at its target.
                        onItemGloballyPositioned = { id, rect -> railItemBounds[id] = rect }
                    )

                    if (isRailVisible) {
                        ConfigureRailItems(
                            mainViewModel, editorViewModel, arViewModel, dashboardViewModel, context,
                            overlayImagePicker, backgroundImagePicker, editorUiState, arUiState, strings,
                            navItemColor = navItemColor,
                            onShowFontPicker = { layerId -> fontPickerLayerId = layerId; showFontPicker = true },
                            layerMenusOpen = layerMenusOpen,
                            showLibrary = showLibrary,
                            tutorialModeActive = mainUiState.tutorialModeActive,
                            coopState = coopState,
                            isTouchLocked = mainUiState.isTouchLocked,
                            onShowJoinScanner = { showJoinScanner = true },
                            onWallPhoto = {
                                if (hasCameraPermission) {
                                    val tmpFile = File(context.cacheDir, "wall_camera_${System.currentTimeMillis()}.jpg")
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmpFile)
                                    cameraUri = uri.toString()
                                    takePictureLauncher.launch(uri)
                                } else {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION))
                                }
                            },
                            onOpenModeAdjust = { modeAdjustTarget = it }
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

                        // Single source of truth for the auto-fired-overlay modal gate. The rule
                        // is that auto overlays (onboarding, AR-unavailable explainer) must
                        // early-return on EVERY modal, not just one — collapsing the repeated
                        // boolean chains here prevents a future overlay from forgetting one.
                        val anyModalActive = showLibrary || showSettings || isExporting ||
                            mainUiState.isCapturingTarget || showSaveDialog ||
                            dashboardUiState.showNewProjectDialog

                        // Auto-open the edit-text box the instant a new text layer is created.
                        // The new layer's rail item must compose first (with its hidden menu
                        // closed) so flipping layerMenusOpen to true is a clean closed->open edge
                        // the rail picks up; hence the brief delay.
                        LaunchedEffect(editorUiState.autoEditTextLayerId) {
                            val id = editorUiState.autoEditTextLayerId ?: return@LaunchedEffect
                            editorViewModel.onLayerActivated(id)
                            kotlinx.coroutines.delay(250)
                            layerMenusOpen[id] = true
                            editorViewModel.consumeAutoEditTextLayer()
                        }

                        val completedTutorials by mainViewModel.completedTutorials.collectAsState()

                        // Adaptive onboarding coach. A single step is derived from the *current* app
                        // state (mode, layers, active layer, wall photo, AR target) — the coach adapts
                        // to the user instead of forcing a fixed path: performing the action changes
                        // the state, which moves the coach to the next step on its own. It shows only
                        // when the user is idle (never over a modal, never mid-gesture), reveals a
                        // step's lines one at a time (screen tap or idle timer), and remembers each
                        // step as shown so it never nags. Tapping Help (tutorialModeActive) replays
                        // coaching for the session, ignoring the persisted "already seen" set.
                        val coachStep = rememberCoachStep(editorUiState, arUiState)
                        val replayCoaching = mainUiState.tutorialModeActive
                        val coachSeenThisSession =
                            remember(replayCoaching) { androidx.compose.runtime.mutableStateListOf<String>() }
                        if (!anyModalActive && coachStep != null) {
                            val key = coachStep.key
                            val suppressed = key in coachSeenThisSession ||
                                (!replayCoaching && key in completedTutorials)
                            if (!suppressed) {
                                OnboardingCoachOverlay(
                                    step = coachStep,
                                    gestureInProgress = editorUiState.gestureInProgress,
                                    targetBounds = coachStep.targetId?.let { railItemBounds[it] },
                                    onSeen = {
                                        coachSeenThisSession.add(key)
                                        if (!replayCoaching) mainViewModel.markTutorialCompletePersistent(key)
                                    },
                                )
                            }
                        }

                        // First-launch explainer for devices where ARCore is
                        // unavailable. Modal-gated identically to the per-mode
                        // onboarding above, and dismissed-once via the same
                        // completedTutorials DataStore set (collected above).
                        val arExplainerKey = "ar_unavailable_explainer"
                        var arExplainerDismissedThisSession by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                        val arUnavailableLines = remember {
                            context.resources.getStringArray(DesignR.array.onboarding_ar_unavailable).toList()
                        }
                        if (!anyModalActive &&
                            arUiState.isArCoreAvailabilityResolved &&
                            !arUiState.isArCoreAvailable &&
                            arExplainerKey !in completedTutorials &&
                            !arExplainerDismissedThisSession
                        ) {
                            ArUnavailableOverlay(
                                lines = arUnavailableLines,
                                onDismiss = {
                                    arExplainerDismissedThisSession = true
                                    mainViewModel.markTutorialCompletePersistent(arExplainerKey)
                                }
                            )
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
                        var lockTaps by remember { mutableIntStateOf(0) }
                        
                        LaunchedEffect(mainUiState.isTouchLocked) {
                            if (mainUiState.isTouchLocked) lockTaps = 0
                        }

                        Box(Modifier
                            .fillMaxSize()
                            .onSizeChanged { fullSize = it }
                            .then(
                                if (mainUiState.isTouchLocked) {
                                    Modifier.pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                                val isDown = event.changes.any { it.pressed && !it.previousPressed }
                                                event.changes.forEach { it.consume() }
                                                if (isDown) {
                                                    lockTaps++
                                                    if (lockTaps >= 4) {
                                                        mainViewModel.setTouchLocked(false)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else Modifier
                            )
                        ) {
                            AzNavHost(startDestination = LIBRARY_ROUTE) {
                                composable(LIBRARY_ROUTE) {
                                    val dashboardState by dashboardViewModel.uiState.collectAsState()
                                    LaunchedEffect(Unit) { dashboardViewModel.loadAvailableProjects() }
                                    ProjectLibraryScreen(
                                        projects = dashboardState.availableProjects,
                                        onLoadProject = { project ->
                                            // Switching projects ends any active guest co-op session
                                            // so host ops can't keep mutating the newly-opened project.
                                            if (arUiState.coopRole == CoopRole.GUEST) arViewModel.leaveSession()
                                            dashboardViewModel.openProject(project)
                                            navController.navigate(EditorMode.DESIGN.name) {
                                                popUpTo(LIBRARY_ROUTE) { inclusive = true }
                                                launchSingleTop = true
                                            }
                                        },
                                        onDeleteProject = { dashboardViewModel.deleteProject(it) },
                                        onNewProject = {
                                            if (arUiState.coopRole == CoopRole.GUEST) arViewModel.leaveSession()
                                            dashboardViewModel.onNewProjectTriggered()
                                        },
                                        onImportProject = { uri -> dashboardViewModel.importProject(uri) },
                                        onClose = { /* no-op: ProjectLibraryScreen no longer exposes a close affordance */ },
                                        strings = strings
                                    )
                                }
                                composable(EditorMode.AR.name) {
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
                                composable(EditorMode.DESIGN.name) {
                                    // The design canvas is a background component (AzNavRail
                                    // `background` layer, rendered full-screen behind the rail by
                                    // MainScreen). Onscreen content here is only the editing overlay.
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

                            // Depth-unsupported devices auto-fall-back to Canvas (handled in
                            // ArViewModel), so the old "switch to Canvas in Settings" banner is gone.

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
                                // Keep the diag-log readout clear of the stats panel above (both were
                                // pinned TopStart and overlapped into an unreadable mess). Sit it in the
                                // empty band below the panel and above the adjustment knobs.
                                DiagPopup(
                                    diagLog = arUiState.diagLog,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 16.dp, end = 16.dp, bottom = 220.dp),
                                    strings = strings
                                )
                            }

                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings) {
                                AnchorLockFlash(isAnchorEstablished = arUiState.isAnchorEstablished, strings = strings)
                            }

                            // Dev/eval overlay: debug builds only, and off unless the user opts in
                            // via the Diagnostic Overlay setting (hidden by default).
                            if (EVAL_OVERLAY_ENABLED && editorUiState.showDiagOverlay && editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings && !mainUiState.isCapturingTarget) {
                                EvalOverlay(
                                    metrics = arUiState.evalLiveMetrics,
                                    onStartRecord = { arViewModel.evalStartRecording() },
                                    onStopRecord = { arViewModel.evalStopRecording() },
                                    onStartLog = { arViewModel.evalStartLog() },
                                    onStopLog = { arViewModel.evalStopLog() },
                                    onInduceLoss = { arViewModel.evalInduceLoss() },
                                    onToggleFusion = { arViewModel.evalSetFusionEnabled(it) },
                                    onToggleSelfGrow = { arViewModel.evalSetSelfGrowEnabled(it) },
                                )
                            }

                            SyncingBadge(
                                isSyncing = coopState is CoopSessionState.Connected
                                        || coopState is CoopSessionState.Reconnecting,
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

                            // Tap-to-distance (Sub-project C): live center reticle + a distance chip
                            // pinned at each tapped wall mark. Only when depth is available in AR mode.
                            if (editorUiState.editorMode == EditorMode.AR && !showLibrary && !showSettings && arUiState.isDepthApiSupported) {
                                androidx.compose.material3.Text(
                                    text = com.hereliesaz.graffitixr.feature.ar.eval.DistanceFormat.format(
                                        arUiState.currentCenterDepth, arUiState.isImperialUnits
                                    ),
                                    color = androidx.compose.ui.graphics.Color.Cyan,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                                arUiState.tapMarks.forEach { mark ->
                                    androidx.compose.material3.Text(
                                        text = com.hereliesaz.graffitixr.feature.ar.eval.DistanceFormat.format(
                                            mark.distanceMeters, arUiState.isImperialUnits
                                        ),
                                        color = androidx.compose.ui.graphics.Color.Yellow,
                                        modifier = Modifier.align(
                                            androidx.compose.ui.BiasAlignment(mark.nx * 2f - 1f, mark.ny * 2f - 1f)
                                        )
                                    )
                                }
                            }

                            // Auto-fired by AR state, so it must yield to any user-driven modal
                            // rather than stack on top of it. Stays true in state and re-renders
                            // once the higher-priority modal dismisses.
                            if (arUiState.showCoopNotFoundDialog && !anyModalActive) {
                                CoopNotFoundDialog(
                                    onDismiss = { arViewModel.dismissCoopNotFoundDialog() },
                                    onHost = { arViewModel.startHosting() },
                                    onSearch = {
                                        arViewModel.dismissCoopNotFoundDialog()
                                        qrScannerLauncher.launch(ScanOptions().apply {
                                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                            setPrompt("Scan host QR code")
                                            setBeepEnabled(false)
                                            setOrientationLocked(false)
                                        })
                                    },
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
                                        navController.navigate(EditorMode.DESIGN.name) {
                                            popUpTo(LIBRARY_ROUTE) { inclusive = true }
                                            launchSingleTop = true
                                        }
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
                                    showFeaturePoints = editorUiState.showFeaturePoints,
                                    onFeaturePointsChanged = { editorViewModel.toggleFeaturePoints() },
                                    showPlaneGrids = editorUiState.showPlaneGrids,
                                    onPlaneGridsChanged = { editorViewModel.togglePlaneGrids() },
                                    showVoxels = editorUiState.showVoxels,
                                    onVoxelsChanged = { editorViewModel.toggleVoxels() },
                                    showPoints = editorUiState.showPoints,
                                    onPointsChanged = { editorViewModel.togglePoints() },
                                    showMesh = editorUiState.showMesh,
                                    onMeshChanged = { editorViewModel.toggleMesh() },
                                    parallaxMinDegrees = arUiState.parallaxMinDegrees,
                                    onParallaxMinDegreesChanged = { arViewModel.setParallaxMinDegrees(it) },
                                    cameraTargetFps = arUiState.cameraTargetFps,
                                    onCameraTargetFpsChanged = { arViewModel.setCameraTargetFps(it) },
                                    throttleOnThermal = arUiState.throttleOnThermal,
                                    onThrottleOnThermalChanged = { arViewModel.setThrottleOnThermal(it) },
                                    throttleOnPowerSave = arUiState.throttleOnPowerSave,
                                    onThrottleOnPowerSaveChanged = { arViewModel.setThrottleOnPowerSave(it) },
                                    throttleOnLowBattery = arUiState.throttleOnLowBattery,
                                    onThrottleOnLowBatteryChanged = { arViewModel.setThrottleOnLowBattery(it) },
                                    throttleOnLag = arUiState.throttleOnLag,
                                    onThrottleOnLagChanged = { arViewModel.setThrottleOnLag(it) },
                                    adaptiveRateEnabled = arUiState.adaptiveRateEnabled,
                                    onAdaptiveRateEnabledChanged = { arViewModel.setAdaptiveRateEnabled(it) },
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
                                    onOpenUpdatePage = { dashboardViewModel.openUpdatePage(this@MainActivity) },
                                    onResetTutorials = { settingsViewModel.resetCompletedTutorials() },
                                    onClose = { showSettings = false },
                                    strings = strings
                                )
                            }

                            if (hostQr != null && coopState is CoopSessionState.WaitingForGuest) {
                                CoopHostQrOverlay(
                                    qrPayload = hostQr!!,
                                    onStopSharing = { arViewModel.leaveSession() },
                                )
                            }
                            if (showJoinScanner) {
                                CoopJoinQrScannerOverlay(
                                    onScanned = { qr ->
                                        showJoinScanner = false
                                        arViewModel.joinFromQr(qr)
                                    },
                                    onCancelled = { showJoinScanner = false },
                                )
                            }
                            if (arUiState.coopRole == CoopRole.GUEST &&
                                (coopState is CoopSessionState.Connected || coopState is CoopSessionState.Reconnecting)) {
                                CoopSpectatorBanner(
                                    peerName = (coopState as? CoopSessionState.Connected)?.peerName ?: "host",
                                    isReconnecting = coopState is CoopSessionState.Reconnecting,
                                    onLeave = { arViewModel.leaveSession() },
                                    modifier = Modifier.align(Alignment.TopCenter),
                                )
                            }

                            modeAdjustTarget?.let { mode ->
                                ModeAdjustPanel(
                                    mode = mode,
                                    adjustment = editorUiState.modeAdjustments[mode]
                                        ?: com.hereliesaz.graffitixr.common.model.ModeAdjustment(),
                                    onChange = { editorViewModel.onModeAdjustmentChanged(mode, it) },
                                    onReset = { editorViewModel.onModeLayerReset(mode) },
                                    onDismiss = { modeAdjustTarget = null },
                                    modifier = Modifier.align(Alignment.CenterEnd),
                                )
                            }

                            val glassesState by arViewModel.glassesSessionState.collectAsState()
                            when (val s = glassesState) {
                                is com.hereliesaz.graffitixr.feature.ar.GlassesSessionState.PairingPrompt -> {
                                    com.hereliesaz.graffitixr.ui.glasses.GlassesPairingOverlay(
                                        onCancel = { arViewModel.endGlassesSession() },
                                    )
                                }
                                is com.hereliesaz.graffitixr.feature.ar.GlassesSessionState.CalibrationPrompt -> {
                                    com.hereliesaz.graffitixr.ui.glasses.CalibrationOverlay(
                                        progress = s.progress,
                                        onTap = { point -> arViewModel.submitCalibrationTap(point) },
                                    )
                                }
                                is com.hereliesaz.graffitixr.feature.ar.GlassesSessionState.Active -> {
                                    com.hereliesaz.graffitixr.ui.glasses.GlassesStatusBanner(
                                        isFallback = false,
                                        fallbackReason = null,
                                        onReconnect = {},
                                        onLeave = { arViewModel.endGlassesSession() },
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                }
                                is com.hereliesaz.graffitixr.feature.ar.GlassesSessionState.Fallback -> {
                                    com.hereliesaz.graffitixr.ui.glasses.GlassesStatusBanner(
                                        isFallback = true,
                                        fallbackReason = s.reason,
                                        onReconnect = { arViewModel.startGlassesSession() },
                                        onLeave = { arViewModel.endGlassesSession() },
                                        modifier = Modifier.align(Alignment.TopCenter),
                                    )
                                }
                                com.hereliesaz.graffitixr.feature.ar.GlassesSessionState.Idle -> Unit
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

    /**
     * Adds a "Layer" sub-host under a mode that lets the user edit the whole design as a unit for
     * that mode: tapping it selects whole-design editing (transform gestures move/scale/rotate the
     * mural), "Adjust" opens the tone panel, and "Reset" clears the mode's adjustment. These edits
     * persist per mode; Design-mode layer edits stay global.
     */
    private fun AzNavHostScope.modeLayerSubHost(
        modeId: String,
        mode: EditorMode,
        editorUiState: EditorUiState,
        editorViewModel: EditorViewModel,
        navStrings: NavStrings,
        navItemColor: Color,
        onOpenModeAdjust: (EditorMode) -> Unit
    ) {
        val active = editorUiState.editingModeLayer && editorUiState.editorMode == mode
        azRailSubHostItem(
            id = "$modeId.layer",
            hostId = modeId,
            text = "Layer",
            color = if (active) Cyan else navItemColor,
            shape = AzButtonShape.RECTANGLE,
            onClick = { editorViewModel.onModeLayerSelected(mode) }
        )
        azRailSubItem(id = "$modeId.layer.adjust", hostId = "$modeId.layer", text = navStrings.adjust, color = navItemColor, shape = AzButtonShape.NONE) {
            editorViewModel.onModeLayerSelected(mode)
            onOpenModeAdjust(mode)
        }
        azRailSubItem(id = "$modeId.layer.reset", hostId = "$modeId.layer", text = "Reset", color = navItemColor, shape = AzButtonShape.NONE) {
            editorViewModel.onModeLayerReset(mode)
        }
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
        tutorialModeActive: Boolean = false,
        coopState: CoopSessionState = CoopSessionState.Idle,
        isTouchLocked: Boolean,
        onShowJoinScanner: () -> Unit = {},
        onWallPhoto: () -> Unit = {},
        onOpenModeAdjust: (EditorMode) -> Unit = {}
    ) {
        val navStrings = strings.nav
        val requestPermissions = {
            val perms = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }

        if (editorUiState.editorMode == EditorMode.STENCIL) return

        if (!showLibrary) {
            val isDesignMode = editorUiState.editorMode == EditorMode.DESIGN

            // 1. DESIGN FOLDER (TOP)
            azRailHostItem(
                id = "host.design",
                text = navStrings.design,
                color = if (isDesignMode) Cyan else navItemColor,
                initiallyExpanded = isDesignMode,
                onClick = {
                    // From a Mode, tapping Design navigates to the dedicated Design screen. In Design it
                    // just expands the design tools below (this onClick is a no-op there).
                    if (!isDesignMode) {
                        // Design needs an active project or every Add silently no-ops — create+open one
                        // if the user jumped straight into Design without loading a project.
                        if (editorUiState.projectId == null) dashboardViewModel.createAndOpenProject()
                        navController.navigate(EditorMode.DESIGN.name) { launchSingleTop = true }
                    }
                }
            )

            if (isDesignMode) {
                azRailSubItem(id = "design.addImg", hostId = "host.design", text = "Open", color = navItemColor, shape = AzButtonShape.NONE) {
                    overlayPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                azRailSubItem(id = "design.addDraw", hostId = "host.design", text = "Sketch", color = navItemColor, shape = AzButtonShape.NONE) {
                    editorViewModel.onAddBlankLayer()
                }
                azRailSubItem(id = "design.addText", hostId = "host.design", text = "Text", color = navItemColor, shape = AzButtonShape.NONE) {
                    editorViewModel.onAddTextLayer()
                }

                // LAYERS — contained in a "Layers" sub-host under Design (9.6 nested hosts).
                // Each layer is a relocItem (drag to reorder); tapping it opens its nested rail of
                // editing tools (edit/size/font/color/blend/invert/paint/retouch/etc.). The hidden
                // menu carries link/duplicate/copy/flatten/delete.
                if (editorUiState.layers.isNotEmpty()) {
                    azRailSubHostItem(id = "design.layers", hostId = "host.design", text = "Layers", color = navItemColor, shape = AzButtonShape.RECTANGLE, initiallyExpanded = true)
                }
                editorUiState.layers.reversed().forEach { layer ->
                    val activeTool = editorUiState.activeTool
                    val forceOpenHiddenMenu = layerMenusOpen[layer.id] ?: false

                    azRailRelocItem(
                        id = layerId(layer),
                        hostId = "design.layers",
                        text = layer.name,
                        color = when {
                            editorUiState.activeLayerId == layer.id -> Cyan   // selected
                            layer.isLinked -> NeonGreen                       // linked (its own color)
                            else -> HotPink                                   // any other layer
                        },
                        shape = AzButtonShape.NONE,
                        nestedRailAlignment = AzNestedRailAlignment.VERTICAL,
                        keepNestedRailOpen = true,
                        forceHiddenMenuOpen = forceOpenHiddenMenu,
                        onHiddenMenuDismiss = { layerMenusOpen[layer.id] = false },
                        onClick = {
                            editorViewModel.onLayerActivated(layer.id)
                            editorViewModel.setActiveTool(Tool.NONE)
                        },
                        onRelocate = { _: Int, _: Int, new: List<String> -> editorViewModel.onLayerReordered(new.map { it.removePrefix("layer.") }.reversed()) },
                        nestedContent = {
                            val activate = { editorViewModel.onLayerActivated(layer.id) }

                            if (layer.textParams != null) {
                                azRailItem(
                                    id = layerId(layer, "editText"),
                                    text = navStrings.edit,
                                    color = navItemColor,
                                    shape = AzButtonShape.CIRCLE
                                ) {
                                    activate()
                                    layerMenusOpen[layer.id] = true
                                }
                            }

                            val addSizeItem: () -> Unit = {
                                azRailItem(
                                    id = layerId(layer, "size.brush"),
                                    text = navStrings.size,
                                    color = navItemColor,
                                    shape = AzButtonShape.CIRCLE,
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
                                                        if (abs(dragAmount.y) >= abs(dragAmount.x)) {
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
                                    azRailHostItem(id = layerId(layer, "grp.text"), text = "Text", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(
                                        id = layerId(layer, "font"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.font,
                                        color = navItemColor,
                                        shape = AzButtonShape.CIRCLE
                                    ) {
                                        activate()
                                        onShowFontPicker(layer.id)
                                    }
                                    azRailSubItem(
                                        id = layerId(layer, "size.text"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.size,
                                        color = navItemColor,
                                        shape = AzButtonShape.CIRCLE,
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
                                    azRailSubItem(
                                        id = layerId(layer, "color"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.color,
                                        color = navItemColor,
                                        shape = AzButtonShape.CIRCLE,
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
                                    azRailSubItem(
                                        id = layerId(layer, "kern"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.kern,
                                        color = navItemColor,
                                        shape = AzButtonShape.CIRCLE,
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
                                    azRailSubItem(
                                        id = layerId(layer, "bold"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.bold,
                                        color = if (tp.isBold) Cyan else navItemColor,
                                        shape = AzButtonShape.CIRCLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, !tp.isBold, tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailSubItem(
                                        id = layerId(layer, "italic"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.italic,
                                        color = if (tp.isItalic) Cyan else navItemColor,
                                        shape = AzButtonShape.CIRCLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, !tp.isItalic, tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailSubItem(
                                        id = layerId(layer, "outline"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.outline,
                                        color = if (tp.hasOutline) Cyan else navItemColor,
                                        shape = AzButtonShape.CIRCLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, !tp.hasOutline, tp.hasDropShadow)
                                    }
                                    azRailSubItem(
                                        id = layerId(layer, "shadow"),
                                        hostId = layerId(layer, "grp.text"),
                                        text = navStrings.shadow,
                                        color = if (tp.hasDropShadow) Cyan else navItemColor,
                                        shape = AzButtonShape.CIRCLE
                                    ) {
                                        activate()
                                        editorViewModel.onTextStyleChanged(layer.id, tp.isBold, tp.isItalic, tp.hasOutline, !tp.hasDropShadow)
                                    }
                                    if (layer.stencilType == null) {
                                        azRailHostItem(id = layerId(layer, "grp.effects"), text = "Effects", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                        azRailSubItem(
                                            id = layerId(layer, "stencil"),
                                            hostId = layerId(layer, "grp.effects"),
                                            text = navStrings.stencil,
                                            color = navItemColor,
                                            shape = AzButtonShape.CIRCLE,
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
                                    azRailHostItem(id = layerId(layer, "grp.adjust"), text = "Adjust", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(
                                        id = layerId(layer, "blend"),
                                        hostId = layerId(layer, "grp.adjust"),
                                        text = navStrings.build,
                                        color = navItemColor,
                                        shape = AzButtonShape.CIRCLE,
                                        onClick = { activate(); editorViewModel.onCycleBlendMode() }
                                    )
                                    azRailSubItem(
                                        id = layerId(layer, "adj"),
                                        hostId = layerId(layer, "grp.adjust"),
                                        text = navStrings.adjust,
                                        color = navItemColor,
                                        shape = AzButtonShape.CIRCLE,
                                        onClick = { activate(); editorViewModel.onAdjustClicked() }
                                    )
                                    azRailSubItem(
                                        id = layerId(layer, "invert"),
                                        hostId = layerId(layer, "grp.adjust"),
                                        text = navStrings.invert,
                                        color = if (layer.isInverted) Cyan else navItemColor,
                                        shape = AzButtonShape.CIRCLE,
                                        onClick = { activate(); editorViewModel.onToggleInvert() }
                                    )
                                }
                                layer.isSketch -> {
                                    azRailHostItem(id = layerId(layer, "grp.paint"), text = "Paint", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "eraser"), hostId = layerId(layer, "grp.paint"), text = navStrings.eraser, color = if (activeTool == Tool.ERASER) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.ERASER) }

                                    azRailHostItem(id = layerId(layer, "grp.retouch"), text = "Retouch", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "blur"), hostId = layerId(layer, "grp.retouch"), text = navStrings.blur, color = if (activeTool == Tool.BLUR) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                                    azRailSubItem(id = layerId(layer, "liquify"), hostId = layerId(layer, "grp.retouch"), text = navStrings.liquify, color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                                    azRailSubItem(id = layerId(layer, "dodge"), hostId = layerId(layer, "grp.retouch"), text = navStrings.dodge, color = if (activeTool == Tool.DODGE) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                                    azRailSubItem(id = layerId(layer, "burn"), hostId = layerId(layer, "grp.retouch"), text = navStrings.burn, color = if (activeTool == Tool.BURN) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.BURN) }

                                    azRailHostItem(id = layerId(layer, "grp.adjust"), text = "Adjust", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "adj"), hostId = layerId(layer, "grp.adjust"), text = navStrings.adjust, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onAdjustClicked() }
                                    azRailSubItem(id = layerId(layer, "balance"), hostId = layerId(layer, "grp.adjust"), text = navStrings.balance, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onBalanceClicked() }
                                    azRailSubItem(id = layerId(layer, "blend"), hostId = layerId(layer, "grp.adjust"), text = navStrings.build, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onCycleBlendMode() }
                                    azRailSubItem(id = layerId(layer, "invert"), hostId = layerId(layer, "grp.adjust"), text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onToggleInvert() }

                                    if (layer.stencilType == null) {
                                        azRailHostItem(id = layerId(layer, "grp.effects"), text = "Effects", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                        azRailSubItem(
                                            id = layerId(layer, "stencil"),
                                            hostId = layerId(layer, "grp.effects"),
                                            text = navStrings.stencil,
                                            color = navItemColor,
                                            shape = AzButtonShape.CIRCLE,
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

                                    addSizeItem()
                                }
                                else -> {
                                    // Image-layer tools grouped into folders (host/sub) so the nested
                                    // rail isn't an overwhelming flat list. Size stays a standalone
                                    // widget (most-used control); all other tools live under a folder.
                                    azRailHostItem(id = layerId(layer, "grp.paint"), text = "Paint", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "eraser"), hostId = layerId(layer, "grp.paint"), text = navStrings.eraser, color = if (activeTool == Tool.ERASER) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.ERASER) }

                                    azRailHostItem(id = layerId(layer, "grp.retouch"), text = "Retouch", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "blur"), hostId = layerId(layer, "grp.retouch"), text = navStrings.blur, color = if (activeTool == Tool.BLUR) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.BLUR) }
                                    azRailSubItem(id = layerId(layer, "liquify"), hostId = layerId(layer, "grp.retouch"), text = navStrings.liquify, color = if (activeTool == Tool.LIQUIFY) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.LIQUIFY) }
                                    azRailSubItem(id = layerId(layer, "dodge"), hostId = layerId(layer, "grp.retouch"), text = navStrings.dodge, color = if (activeTool == Tool.DODGE) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.DODGE) }
                                    azRailSubItem(id = layerId(layer, "burn"), hostId = layerId(layer, "grp.retouch"), text = navStrings.burn, color = if (activeTool == Tool.BURN) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.setActiveTool(Tool.BURN) }

                                    azRailHostItem(id = layerId(layer, "grp.adjust"), text = "Adjust", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "adj"), hostId = layerId(layer, "grp.adjust"), text = navStrings.adjust, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onAdjustClicked() }
                                    azRailSubItem(id = layerId(layer, "balance"), hostId = layerId(layer, "grp.adjust"), text = navStrings.balance, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onBalanceClicked() }
                                    azRailSubItem(id = layerId(layer, "blend"), hostId = layerId(layer, "grp.adjust"), text = navStrings.build, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onCycleBlendMode() }
                                    azRailSubItem(id = layerId(layer, "invert"), hostId = layerId(layer, "grp.adjust"), text = navStrings.invert, color = if (layer.isInverted) Cyan else navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onToggleInvert() }

                                    azRailHostItem(id = layerId(layer, "grp.effects"), text = "Effects", color = navItemColor, shape = AzButtonShape.RECTANGLE)
                                    azRailSubItem(id = layerId(layer, "iso"), hostId = layerId(layer, "grp.effects"), text = navStrings.isolate, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onRemoveBackgroundClicked() }
                                    azRailSubItem(id = layerId(layer, "line"), hostId = layerId(layer, "grp.effects"), text = navStrings.outline, color = navItemColor, shape = AzButtonShape.CIRCLE) { activate(); editorViewModel.onSketchClicked() }
                                    if (layer.stencilType == null) {
                                        azRailSubItem(
                                            id = layerId(layer, "stencil"),
                                            hostId = layerId(layer, "grp.effects"),
                                            text = navStrings.stencil,
                                            color = navItemColor,
                                            shape = AzButtonShape.CIRCLE,
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

            // 3. MODES FOLDER
            azRailHostItem(
                id = "host.modes",
                text = navStrings.modes,
                color = navItemColor
            )

            val showArModeEntry = !arUiState.isArCoreAvailabilityResolved || arUiState.isArCoreAvailable
            if (showArModeEntry) {
                // AR is a sub-host: it navigates to AR mode and contains its tools.
                azRailSubHostItem(id = "mode.ar", hostId = "host.modes", text = navStrings.arMode, route = EditorMode.AR.name, color = navItemColor, shape = AzButtonShape.NONE)
                // Target capture — only meaningful while in AR mode.
                if (editorUiState.editorMode == EditorMode.AR) {
                    azRailSubItem(id = "target.create", hostId = "mode.ar", text = navStrings.grid, color = navItemColor, shape = AzButtonShape.RECTANGLE) {
                        if (hasCameraPermission) mainViewModel.startTargetCapture() else requestPermissions()
                    }
                }
                modeLayerSubHost("mode.ar", EditorMode.AR, editorUiState, editorViewModel, navStrings, navItemColor, onOpenModeAdjust)
            }

            azRailSubHostItem(id = "mode.overlay", hostId = "host.modes", text = navStrings.overlay, route = EditorMode.OVERLAY.name, color = navItemColor, shape = AzButtonShape.NONE)
            modeLayerSubHost("mode.overlay", EditorMode.OVERLAY, editorUiState, editorViewModel, navStrings, navItemColor, onOpenModeAdjust)

            // Mockup ▸ Wall ▸ { Photo (take a photo), File (pick an image) }
            azRailSubHostItem(id = "mode.mockup", hostId = "host.modes", text = navStrings.mockup, route = EditorMode.MOCKUP.name, color = navItemColor, shape = AzButtonShape.NONE)
            azRailSubHostItem(id = "mockup.wall", hostId = "mode.mockup", text = navStrings.wall, color = navItemColor, shape = AzButtonShape.NONE)
            azRailSubItem(id = "wall.photo", hostId = "mockup.wall", text = navStrings.photo, color = navItemColor, shape = AzButtonShape.NONE) {
                onWallPhoto()
            }
            azRailSubItem(id = "wall.file", hostId = "mockup.wall", text = navStrings.file, color = navItemColor, shape = AzButtonShape.NONE) {
                backgroundPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            modeLayerSubHost("mode.mockup", EditorMode.MOCKUP, editorUiState, editorViewModel, navStrings, navItemColor, onOpenModeAdjust)

            // Trace ▸ Freeze (+ Layer)
            azRailSubHostItem(id = "mode.trace", hostId = "host.modes", text = navStrings.trace, route = EditorMode.TRACE.name, color = navItemColor, shape = AzButtonShape.NONE)
            azRailSubItem(id = "mode.trace.freeze", hostId = "mode.trace", text = "Freeze", color = navItemColor, shape = AzButtonShape.NONE) {
                mainViewModel.setTouchLocked(!isTouchLocked)
            }
            modeLayerSubHost("mode.trace", EditorMode.TRACE, editorUiState, editorViewModel, navStrings, navItemColor, onOpenModeAdjust)

            // 4. PROJECT FOLDER
            azRailHostItem(
                id = "host.project",
                text = navStrings.project,
                color = navItemColor
            )
            azRailSubItem(id = "proj.new", hostId = "host.project", text = navStrings.new, color = navItemColor, shape = AzButtonShape.NONE) {                dashboardViewModel.onNewProjectTriggered()
            }
            azRailSubItem(id = "proj.save", hostId = "host.project", text = navStrings.save, color = navItemColor, shape = AzButtonShape.NONE) {                showSaveDialog = true
            }
            azRailSubItem(id = "proj.load", hostId = "host.project", text = navStrings.load, color = navItemColor, shape = AzButtonShape.NONE) {                navController.navigate(LIBRARY_ROUTE) { launchSingleTop = true }
            }
            azRailSubItem(id = "proj.settings", hostId = "host.project", text = navStrings.settings, color = navItemColor, shape = AzButtonShape.NONE) {                showSettings = true
            }

            azDivider()

            azRailItem(
                id = "item.help",
                text = navStrings.help,
                color = if (tutorialModeActive) Cyan else navItemColor,
                onClick = { mainViewModel.toggleTutorialMode() }
            )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Explicit COPY button in its own gesture region. Tapping the panel body competes
                    // with the drag handler and often loses, so this guarantees a reliable copy target.
                    Text(
                        "COPY",
                        color = if (copied) Color.Green else Color.Cyan,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.pointerInput(diagLog) {
                            detectTapGestures {
                                val text = diagLog ?: return@detectTapGestures
                                val cm = context.getSystemService(AndroidClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("diag", text))
                                copied = true
                                scope.launch {
                                    kotlinx.coroutines.delay(1500)
                                    copied = false
                                }
                            }
                        }
                    )
                    Text(
                        "✕",
                        color = Color.Gray,
                        modifier = Modifier
                            .padding(start = 16.dp)
                            .pointerInput(Unit) {
                                detectTapGestures { _ -> visible = false }
                            }
                    )
                }
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

// Eval overlay only renders in debug builds; production users never see it.
private val EVAL_OVERLAY_ENABLED = com.hereliesaz.graffitixr.BuildConfig.DEBUG

@Composable
private fun EvalOverlay(
    metrics: com.hereliesaz.graffitixr.common.model.EvalLiveMetrics,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onStartLog: () -> Unit,
    onStopLog: () -> Unit,
    onInduceLoss: () -> Unit,
    onToggleFusion: (Boolean) -> Unit,
    onToggleSelfGrow: (Boolean) -> Unit,
) {
    // Local UI state for the A/B switch; defaults to true to match ArRenderer.fusionEnabled.
    val fusionOn = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    // Teleological self-grow defaults OFF to match the native default (mutates the reloc fingerprint).
    val selfGrowOn = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(
        androidx.compose.ui.Modifier
            .background(androidx.compose.ui.graphics.Color(0xAA000000))
            .padding(8.dp)
    ) {
        DiagnosticRow("Err", if (metrics.errMm >= 0) "%.0fmm / %.1f°".format(metrics.errMm, metrics.errDeg) else "no marks", androidx.compose.ui.graphics.Color.Cyan)
        DiagnosticRow("Jitter", "%.1fmm".format(metrics.jitterMm), androidx.compose.ui.graphics.Color.White)
        DiagnosticRow("Avail", "%.0f%%".format(metrics.availability * 100), androidx.compose.ui.graphics.Color.White)
        DiagnosticRow("Recovery", metrics.recoveryMs?.let { "${it}ms" } ?: "—", androidx.compose.ui.graphics.Color.White)
        DiagnosticRow("Stage ms", metrics.stageMs.joinToString(" ") { "%.1f".format(it) }, androidx.compose.ui.graphics.Color.Yellow)
        DiagnosticRow("FP pts", metrics.wallCount.toString(), androidx.compose.ui.graphics.Color.Green)
        DiagnosticRow("Batt", "%.0fmA".format(metrics.batteryMa), androidx.compose.ui.graphics.Color.White)
        androidx.compose.foundation.layout.Row {
            androidx.compose.material3.TextButton(onClick = onStartLog) { androidx.compose.material3.Text("Log▶") }
            androidx.compose.material3.TextButton(onClick = onStopLog) { androidx.compose.material3.Text("Log■") }
            androidx.compose.material3.TextButton(onClick = onInduceLoss) { androidx.compose.material3.Text("Loss") }
            androidx.compose.material3.TextButton(onClick = onStartRecord) { androidx.compose.material3.Text("Rec▶") }
            androidx.compose.material3.TextButton(onClick = onStopRecord) { androidx.compose.material3.Text("Rec■") }
            androidx.compose.material3.TextButton(onClick = {
                fusionOn.value = !fusionOn.value
                onToggleFusion(fusionOn.value)
            }) {
                androidx.compose.material3.Text(if (fusionOn.value) "Fusion ON" else "Fusion OFF")
            }
            androidx.compose.material3.TextButton(onClick = {
                selfGrowOn.value = !selfGrowOn.value
                onToggleSelfGrow(selfGrowOn.value)
            }) {
                androidx.compose.material3.Text(if (selfGrowOn.value) "Grow ON" else "Grow OFF")
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
    // Always call animateFloat (never inside an if/else) and select the value, so the set of
    // composable/remember slots stays stable when relocState flips SEARCHING↔TRACKING — the
    // conditional hook call could otherwise corrupt the slot table.
    val animatedAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )
    val pulseAlpha = if (relocState == RelocState.SEARCHING) animatedAlpha else 1f

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
package com.hereliesaz.graffitixr.feature.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.common.model.CoverageGlowAppearance
import com.hereliesaz.graffitixr.common.model.CoverageGlowColor
import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import com.hereliesaz.graffitixr.common.model.CoverageGlowIntensity
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import androidx.compose.material3.Slider
import kotlin.math.roundToInt
import com.hereliesaz.graffitixr.design.theme.AppStrings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun SettingsScreen(
    currentVersion: String,
    releasesPageError: String?,
    currentLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    isRightHanded: Boolean,
    onHandednessChanged: (Boolean) -> Unit,
    crashReportingConsent: Boolean,
    onCrashReportingConsentChanged: (Boolean) -> Unit,
    crashReportToken: String,
    onCrashReportTokenChanged: (String) -> Unit,
    // Debug builds only: the developer's crash upload (public issue tracker, developer token).
    developerCrashUpload: Boolean = false,
    showDiagOverlay: Boolean,
    onDiagOverlayChanged: () -> Unit,
    showFeaturePoints: Boolean,
    onFeaturePointsChanged: () -> Unit,
    showPlaneGrids: Boolean,
    onPlaneGridsChanged: () -> Unit,
    showPoints: Boolean,
    onPointsChanged: () -> Unit,
    cameraTargetFps: Int,
    onCameraTargetFpsChanged: (Int) -> Unit,
    arCoreEnabled: Boolean,
    onArCoreEnabledChanged: (Boolean) -> Unit,
    arCoreDepthEnabled: Boolean,
    onArCoreDepthEnabledChanged: (Boolean) -> Unit,
    monocularDepthEnabled: Boolean,
    onMonocularDepthEnabledChanged: (Boolean) -> Unit,
    powerSaving: Boolean,
    onPowerSavingChanged: (Boolean) -> Unit,
    adaptiveRateEnabled: Boolean,
    onAdaptiveRateEnabledChanged: (Boolean) -> Unit,
    ambientScanEnabled: Boolean,
    onAmbientScanEnabledChanged: (Boolean) -> Unit,
    changePassEnabled: Boolean,
    onChangePassEnabledChanged: (Boolean) -> Unit,
    cvPreviewOn: Boolean,
    onCvPreviewChanged: (Boolean) -> Unit,
    isImperialUnits: Boolean,
    onImperialUnitsChanged: (Boolean) -> Unit,
    backgroundColor: Int,
    onBackgroundColorChanged: (Int) -> Unit,
    coverageGlow: CoverageGlowAppearance,
    onCoverageGlowColorChanged: (Int) -> Unit,
    onCoverageGlowIntensityChanged: (Float) -> Unit,
    onCoverageGlowStyleChanged: (CoverageGlowStyle) -> Unit,
    anticipation: AnticipationSettings = AnticipationSettings.DEFAULT,
    onAnticipationChanged: (AnticipationSettings) -> Unit = {},
    doodleFeatures: com.hereliesaz.graffitixr.common.model.DoodleFeatures =
        com.hereliesaz.graffitixr.common.model.DoodleFeatures.DEFAULT,
    onDoodleFeaturesChanged: (com.hereliesaz.graffitixr.common.model.DoodleFeatures) -> Unit = {},
    onOpenReleasesPage: () -> Unit,
    onResetTutorials: () -> Unit,
    onClose: () -> Unit,
    strings: AppStrings
) {
    val context = LocalContext.current

    // Permissions State — recomputed on ON_RESUME so returning from App Settings (where the user may
    // have just granted a permission via [openAppSettings]) reflects the new grant, instead of the
    // stale value a keyless remember{} would freeze at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openAppSettings: () -> Unit = {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", context.packageName, null)
            intent.data = uri
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("SettingsScreen", "Failed to open app settings", e)
        }
    }

    var showLanguageDialog by remember { mutableStateOf(false) }
    // Developer overlays are for diagnosing tracking, not for painting: closed until asked for.
    var developerExpanded by rememberSaveable { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }

    if (showTokenDialog) {
        // Draft edited locally; only committed on Save so a half-typed token never partially
        // overwrites the stored one. Prefilled with the current value so editing (not just replacing)
        // works. The field masks the token like a password — it grants issue-write to the repo.
        var draft by remember { mutableStateOf(crashReportToken) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text(strings.settings.crashTokenLabel) },
            text = {
                Column {
                    Text(
                        text = strings.settings.crashTokenDesc,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Password
                        ),
                        label = { Text(strings.settings.crashTokenField) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                AzButton(text = strings.common.done, onClick = {
                    onCrashReportTokenChanged(draft)
                    showTokenDialog = false
                })
            },
            dismissButton = {
                AzButton(text = strings.common.close, onClick = { showTokenDialog = false })
            }
        )
    }

    if (showLanguageDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(strings.settings.languageLabel) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    LazyColumn {
                        items(AppLanguage.entries.size) { index ->
                            val lang = AppLanguage.entries[index]
                            val isSelected = lang == currentLanguage
                            // A radio group: the selection is a radio button (and RadioButton
                            // semantics via selectable), not only a colour change, so it reads on
                            // every theme and is announced by TalkBack.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = isSelected,
                                        role = Role.RadioButton,
                                        onClick = {
                                            onLanguageChanged(lang)
                                            showLanguageDialog = false
                                        },
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = isSelected, onClick = null)
                                Text(
                                    text = lang.displayName,
                                    fontSize = 18.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                AzButton(text = strings.common.done, onClick = { showLanguageDialog = false })
            }
        )
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = true) {}, // Block clicks
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.8f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.settings.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.common.close)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            SettingsSectionTitle(strings.settings.general)
                            SettingsItem(
                                label = strings.settings.languageLabel,
                                value = currentLanguage.displayName,
                                modifier = Modifier.clickable { showLanguageDialog = true }
                            )
                            SettingsItem(
                                label = strings.settings.dominantHand,
                                value = if (isRightHanded) strings.settings.handRight else strings.settings.handLeft,
                                modifier = Modifier.clickable { onHandednessChanged(!isRightHanded) }
                            )
                            SettingsItem(
                                label = strings.settings.units,
                                value = if (isImperialUnits) strings.settings.imperial else strings.settings.metric,
                                modifier = Modifier.clickable { onImperialUnitsChanged(!isImperialUnits) }
                            )
                            CanvasBackgroundSwatches(backgroundColor, onBackgroundColorChanged, strings)
                            SettingsItem(
                                label = strings.settings.resetTutorials,
                                value = strings.settings.resetTutorialsValue,
                                modifier = Modifier.clickable { onResetTutorials() }
                            )
                        }

                        item {
                            SettingsSectionTitle(strings.settings.ar)
                            // Use ARCore when the device supports it (default on). Off forces the
                            // standalone SphereSLAM/KPM path even on an ARCore-capable device. ARCore
                            // depth is AUTOMATIC (monocular/ML) depth, which feeds plane finding and
                            // stabilizes the feature cloud; off is the escape hatch for hardware whose
                            // ML depth graph destabilizes VIO. Both apply immediately, mid-AR included.
                            SettingsSwitch(strings.settings.useArCore, arCoreEnabled, onArCoreEnabledChanged)
                            SettingsSwitch(strings.settings.arCoreDepth, arCoreDepthEnabled, onArCoreDepthEnabledChanged)
                            // Monocular depth (MiDaS Small) — standalone (non-ARCore) backend only:
                            // calibrates the sphere map's point radii while the feature map builds.
                            // Default on; never loaded on the ARCore backend (MiDaS depth is relative,
                            // so it can't stand in for ARCore depth). A self-test runs when it loads;
                            // its result shows in the diag overlay.
                            SettingsSwitch(strings.settings.monocularDepth, monocularDepthEnabled, onMonocularDepthEnabledChanged)
                            SettingsSwitch(strings.settings.ambientScan, ambientScanEnabled, onAmbientScanEnabledChanged)
                            // Pass B: also judge progress by what has changed since the clean-wall photo. Off
                            // by default, never switched on for the user; it needs that photo to do anything.
                            SettingsSwitch(strings.settings.changePass, changePassEnabled, onChangePassEnabledChanged)
                            // CV view: everything the computer vision is looking for, drawn over the design
                            // in AR (moved here from the rail).
                            SettingsSwitch(strings.nav.cvView, cvPreviewOn, onCvPreviewChanged)
                            SettingsItem(
                                label = strings.settings.cameraFps,
                                value = cameraTargetFpsLabel(cameraTargetFps, strings),
                                modifier = Modifier.clickable {
                                    onCameraTargetFpsChanged(
                                        com.hereliesaz.graffitixr.common.model.CameraTargetFps
                                            .next(cameraTargetFps),
                                    )
                                }
                            )
                        }

                        item {
                            SettingsSectionTitle(strings.settings.glowSection)
                            CoverageGlowSettings(
                                appearance = coverageGlow,
                                onColorChanged = onCoverageGlowColorChanged,
                                onIntensityChanged = onCoverageGlowIntensityChanged,
                                onStyleChanged = onCoverageGlowStyleChanged,
                                strings = strings,
                            )
                        }

                        item {
                            SettingsSectionTitle(strings.settings.power)
                            // One switch for all four perception throttle triggers (heat, power
                            // saver, low battery, frame lag): each drops perception 60→30 fps and
                            // backs relocalization off while active. Default on.
                            SettingsSwitch(
                                label = strings.settings.powerSaving,
                                checked = powerSaving,
                                onCheckedChange = onPowerSavingChanged,
                                description = strings.settings.powerSavingDesc,
                            )
                            // Adaptive rate: gates heavy SLAM work while the projection is locked and
                            // the phone is still (snaps back instantly on motion), plus battery-tier
                            // degradation. Standalone: once tracking work exceeds a camera frame, the
                            // analyzer skips frames to stay under half duty (AnalysisFrameGate).
                            // Default on. Off = always full rate.
                            SettingsSwitch(strings.settings.adaptiveRate, adaptiveRateEnabled, onAdaptiveRateEnabledChanged)
                        }

                        item {
                            SettingsSectionTitle(strings.settings.anticipationSection)
                            AnticipationSettingsControls(anticipation, onAnticipationChanged, strings)
                            // What the next target's doodle is made of: tap to cycle.
                            SettingsItem(
                                label = strings.settings.doodleFeatures,
                                value = doodleFeaturesLabel(doodleFeatures, strings),
                                modifier = Modifier.clickable {
                                    onDoodleFeaturesChanged(
                                        com.hereliesaz.graffitixr.common.model.DoodleFeatures.next(doodleFeatures),
                                    )
                                },
                            )
                            Text(
                                text = doodleFeaturesDescription(doodleFeatures, strings),
                                color = Color.Gray,
                                fontSize = 12.sp,
                            )
                        }

                        if (developerCrashUpload) item {
                            SettingsSectionTitle(strings.settings.privacy)
                            // Debug builds only. Off by default and must ask, not assume. On a crash,
                            // the report (device model + recent logcat) goes to this project's PUBLIC
                            // GitHub issue tracker with the developer's token. Release builds never
                            // upload: the crash dialog offers the user a report to share themselves.
                            // Other network calls: co-op sessions the artist starts, and the launch
                            // update check (UpdateChecker: Play's update API or GitHub releases).
                            SettingsSwitch(
                                label = strings.settings.crashReports,
                                checked = crashReportingConsent,
                                onCheckedChange = onCrashReportingConsentChanged,
                                description = strings.settings.crashReportsDesc,
                            )
                            // The credential for the upload above. Entered here, held only on-device,
                            // never compiled into the APK. Shown as Set/Not set — never the value.
                            SettingsItem(
                                label = strings.settings.crashTokenLabel,
                                value = if (crashReportToken.isNotBlank()) strings.settings.crashTokenSet else strings.settings.crashTokenNotSet,
                                modifier = Modifier.clickable { showTokenDialog = true }
                            )
                        }

                        item {
                            SettingsSectionTitle(strings.settings.appInfo)
                            SettingsItem(
                                label = strings.settings.version,
                                value = currentVersion
                            )
                            // The app never contacts a server to look for updates
                            // (docs/en/PRIVACY_POLICY.md); this only opens the public releases page
                            // in the browser, on tap.
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(
                                        onClick = onOpenReleasesPage,
                                        role = Role.Button,
                                        onClickLabel = strings.settings.openReleases,
                                    )
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(text = strings.settings.openReleases, fontWeight = FontWeight.Medium)
                                Text(
                                    text = releasesPageError ?: RELEASES_PAGE_DISPLAY,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray,
                                )
                            }
                            // Only permissions the app actually declares: there is no notification
                            // row because the merged manifest has no POST_NOTIFICATIONS — the app
                            // posts no notifications, so the row could only ever read "not granted".
                            PermissionItem(name = strings.settings.cameraAccess, isGranted = cameraPermission, onClick = openAppSettings, strings = strings)
                        }

                        item {
                            DeveloperSectionHeader(
                                title = strings.settings.developer,
                                expanded = developerExpanded,
                                expandedLabel = strings.settings.stateExpanded,
                                collapsedLabel = strings.settings.stateCollapsed,
                                onToggle = { developerExpanded = !developerExpanded },
                            )
                            if (developerExpanded) {
                                SettingsSwitch(strings.settings.diagOverlay, showDiagOverlay, { onDiagOverlayChanged() })
                                SettingsSwitch(strings.settings.layerFeaturePoints, showFeaturePoints, { onFeaturePointsChanged() })
                                SettingsSwitch(strings.settings.layerPlaneGrids, showPlaneGrids, { onPlaneGridsChanged() })
                                SettingsSwitch(strings.settings.layerPoints, showPoints, { onPointsChanged() })
                            }
                        }
                    }

                    Text(
                        text = strings.settings.copyright,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Colour, intensity and style of the standalone SphereSLAM photosphere coverage glow. Every control
 * applies immediately: the live glow restyles on its next frame, no AR restart.
 */
@Composable
private fun CoverageGlowSettings(
    appearance: CoverageGlowAppearance,
    onColorChanged: (Int) -> Unit,
    onIntensityChanged: (Float) -> Unit,
    onStyleChanged: (CoverageGlowStyle) -> Unit,
    strings: AppStrings,
) {
    val selectedColor = CoverageGlowColor.fromArgb(appearance.colorArgb)
    // Same layout as the canvas swatches above: label on its own line, scrollable 48dp targets.
    Text(
        strings.settings.glowColor,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CoverageGlowColor.entries.forEach { swatch ->
            GlowColorSwatch(
                label = glowColorLabel(swatch, strings),
                argb = swatch.argb,
                isSelected = swatch == selectedColor,
                selectedLabel = strings.settings.stateSelected,
                onClick = { onColorChanged(swatch.argb) },
            )
        }
    }

    val percent = (appearance.safeIntensity * 100f).roundToInt()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = strings.settings.glowIntensity, fontWeight = FontWeight.Medium)
        Text(text = "$percent%", color = Color.Gray)
    }
    Slider(
        value = appearance.safeIntensity,
        onValueChange = onIntensityChanged,
        valueRange = CoverageGlowIntensity.MIN..CoverageGlowIntensity.MAX,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = "$percent%" },
    )

    SettingsItem(
        label = strings.settings.glowStyle,
        value = glowStyleLabel(appearance.style, strings),
        modifier = Modifier.clickable { onStyleChanged(CoverageGlowStyle.next(appearance.style)) }
    )
    Text(
        text = glowStyleDescription(appearance.style, strings),
        color = Color.Gray,
        fontSize = 12.sp,
    )
}

/**
 * Settings → Anticipation: the half-sphere of views (distance, meridians, circles, roll; whole
 * steps) and how far battery, heat and load may cut it (percent). Every move applies at once.
 */
@Composable
private fun AnticipationSettingsControls(
    settings: AnticipationSettings,
    onChanged: (AnticipationSettings) -> Unit,
    strings: AppStrings,
) {
    val s = settings.sanitized()
    Text(text = strings.settings.anticipationDesc, color = Color.Gray, fontSize = 12.sp)
    StepSlider(strings.settings.anticipationDistance, s.distance, 0, AnticipationSettings.MAX_DISTANCE) { onChanged(s.copy(distance = it)) }
    // The stored field names are historical and swapped relative to geography: `latitude` counts the
    // MERIDIANS (lines of longitude) and `longitude` the circles of latitude (see HalfSphere.plans).
    // The labels follow the geography; the stored fields keep their meaning.
    StepSlider(strings.settings.anticipationLongitude, s.latitude, 0, AnticipationSettings.MAX_LATITUDE) { onChanged(s.copy(latitude = it)) }
    StepSlider(strings.settings.anticipationLatitude, s.longitude, 0, AnticipationSettings.MAX_LONGITUDE) { onChanged(s.copy(longitude = it)) }
    StepSlider(strings.settings.anticipationRoll, s.roll, 1, AnticipationSettings.MAX_ROLL) { onChanged(s.copy(roll = it)) }
    Text(text = strings.settings.anticipationViews(s.maxViews), color = Color.Gray, fontSize = 12.sp)
    WeightSlider(strings.settings.anticipationBattery, s.batteryWeight) { onChanged(s.copy(batteryWeight = it)) }
    WeightSlider(strings.settings.anticipationHeat, s.heatWeight) { onChanged(s.copy(heatWeight = it)) }
    WeightSlider(strings.settings.anticipationCompute, s.computeWeight) { onChanged(s.copy(computeWeight = it)) }
}

@Composable
private fun StepSlider(label: String, value: Int, min: Int, max: Int, onChanged: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = "$value", color = Color.Gray)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { v -> val n = v.roundToInt(); if (n != value) onChanged(n) },
        valueRange = min.toFloat()..max.toFloat(),
        steps = (max - min - 1).coerceAtLeast(0),
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$value" },
    )
}

@Composable
private fun WeightSlider(label: String, value: Float, onChanged: (Float) -> Unit) {
    val percent = (value * 100f).roundToInt()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = "$percent%", color = Color.Gray)
    }
    Slider(
        value = value,
        onValueChange = onChanged,
        valueRange = 0f..1f,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$percent%" },
    )
}

/**
 * A 48dp-target colour swatch whose selection ring sits in the gap AROUND the colour, so it reads
 * on every swatch colour (a coloured border vanishes on a swatch of the same colour).
 */
@Composable
private fun GlowColorSwatch(
    label: String,
    argb: Int,
    isSelected: Boolean,
    selectedLabel: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = if (isSelected) selectedLabel else ""
            }
            .padding(2.dp)
            .border(
                width = 2.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape
            )
            .padding(6.dp)
            .background(Color(argb.toLong() and 0xFFFFFFFFL), CircleShape)
            .border(width = 0.5.dp, color = Color.Gray, shape = CircleShape)
    )
}

/** The camera-FPS row's value: the two device sentinels are named (localized), real rates show the number. */
private fun cameraTargetFpsLabel(value: Int, strings: AppStrings): String =
    when (com.hereliesaz.graffitixr.common.model.CameraTargetFps.display(value)) {
        com.hereliesaz.graffitixr.common.model.CameraTargetFps.Display.DEVICE_DEFAULT -> strings.settings.fpsDeviceDefault
        com.hereliesaz.graffitixr.common.model.CameraTargetFps.Display.DEVICE_MAX -> strings.settings.fpsDeviceMax
        com.hereliesaz.graffitixr.common.model.CameraTargetFps.Display.RATE -> value.toString()
    }

private fun glowColorLabel(color: CoverageGlowColor, strings: AppStrings): String = when (color) {
    CoverageGlowColor.WHITE -> strings.settings.glowColorWhite
    CoverageGlowColor.LIGHT_GREY -> strings.settings.glowColorLightGrey
    CoverageGlowColor.MID_GREY -> strings.settings.glowColorMidGrey
    CoverageGlowColor.BLACK -> strings.settings.glowColorBlack
    CoverageGlowColor.HOT_PINK -> strings.settings.glowColorPink
    CoverageGlowColor.CYAN -> strings.settings.glowColorCyan
}

// Exhaustive on purpose: a new DoodleFeatures entry won't compile until it has a label here.
private fun doodleFeaturesLabel(
    features: com.hereliesaz.graffitixr.common.model.DoodleFeatures,
    strings: AppStrings,
): String = when (features) {
    com.hereliesaz.graffitixr.common.model.DoodleFeatures.SUPERPOINT -> strings.settings.doodleSuperPoint
    com.hereliesaz.graffitixr.common.model.DoodleFeatures.HALF_SPHERE -> strings.settings.doodleHalfSphere
    com.hereliesaz.graffitixr.common.model.DoodleFeatures.BOTH -> strings.settings.doodleBoth
}

private fun doodleFeaturesDescription(
    features: com.hereliesaz.graffitixr.common.model.DoodleFeatures,
    strings: AppStrings,
): String = when (features) {
    com.hereliesaz.graffitixr.common.model.DoodleFeatures.SUPERPOINT -> strings.settings.doodleSuperPointDesc
    com.hereliesaz.graffitixr.common.model.DoodleFeatures.HALF_SPHERE -> strings.settings.doodleHalfSphereDesc
    com.hereliesaz.graffitixr.common.model.DoodleFeatures.BOTH -> strings.settings.doodleBothDesc
}

// Exhaustive on purpose: a new CoverageGlowStyle entry won't compile until it has a label here.
private fun glowStyleLabel(style: CoverageGlowStyle, strings: AppStrings): String = when (style) {
    CoverageGlowStyle.WASH -> strings.settings.glowStyleWash
    CoverageGlowStyle.MISSING_TILES -> strings.settings.glowStyleMissing
    CoverageGlowStyle.OUTLINE -> strings.settings.glowStyleOutline
    CoverageGlowStyle.WASH_OUTLINED -> strings.settings.glowStyleWashOutlined
}

private fun glowStyleDescription(style: CoverageGlowStyle, strings: AppStrings): String = when (style) {
    CoverageGlowStyle.WASH -> strings.settings.glowStyleWashDesc
    CoverageGlowStyle.MISSING_TILES -> strings.settings.glowStyleMissingDesc
    CoverageGlowStyle.OUTLINE -> strings.settings.glowStyleOutlineDesc
    CoverageGlowStyle.WASH_OUTLINED -> strings.settings.glowStyleWashOutlinedDesc
}

@Composable
fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun SettingsItem(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        // Each row's clickable modifier is passed in by the caller and sized only to its Text
        // children (~24dp), well under Android's 48dp minimum touch target — every toggle in this
        // list was harder to hit than it needed to be. heightIn enforces the minimum without
        // affecting rows that pass a non-clickable modifier (the read-only Version row).
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = value, color = Color.Gray)
    }
}

/**
 * An on/off setting: the whole row toggles (48dp minimum, Switch role), the Switch just shows the
 * state. [description], when given, sits under the label.
 */
@Composable
private fun SettingsSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = label, fontWeight = FontWeight.Medium)
            if (description != null) {
                Text(text = description, color = Color.Gray, fontSize = 12.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The Developer section's title, doubling as its expand/collapse control. */
@Composable
private fun DeveloperSectionHeader(
    title: String,
    expanded: Boolean,
    expandedLabel: String,
    collapsedLabel: String,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null, // Handled by stateDescription
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Canvas background swatches. A label-left/swatches-right Row (SettingsItem's shape) can't fit five
 * 48dp touch targets + 4×8dp gaps (272dp) plus any label width on a compact/split-screen card —
 * Compose doesn't wrap a plain Row, so it clipped or shrank targets below the accessibility minimum.
 * Label on its own line, swatches in a horizontally scrollable row below: every swatch stays
 * reachable (via scroll) at any width, and none of the 48dp targets are ever compressed.
 */
@Composable
private fun CanvasBackgroundSwatches(backgroundColor: Int, onChanged: (Int) -> Unit, strings: AppStrings) {
    Text(
        strings.settings.canvasBg,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 8.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(
            strings.settings.canvasBlack to 0xFF000000.toInt(),
            strings.settings.canvasDark to 0xFF1A1A2E.toInt(),
            strings.settings.canvasGrey to 0xFF2C2C2C.toInt(),
            strings.settings.canvasWhite to 0xFFFFFFFF.toInt(),
            strings.settings.canvasNavy to 0xFF0D1B2A.toInt(),
        ).forEach { (label, argb) ->
            val isSelected = backgroundColor == argb
            // The visible color circle stays 32dp (8dp padding inside a 48dp box), matching
            // Android's 48dp minimum touch target without the swatch itself looking oversized.
            // The color-only Box also gets a contentDescription + Button role via clickable's
            // onClickLabel/role so a screen reader hears "Black, selected" instead of nothing.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = label,
                        onClick = { onChanged(argb) }
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = label
                        stateDescription = if (isSelected) strings.settings.stateSelected else ""
                    }
                    .padding(8.dp)
                    .background(Color(argb.toLong() and 0xFFFFFFFFL), CircleShape)
                    .border(
                        width = if (isSelected) 2.dp else 0.5.dp,
                        color = if (isSelected) Color.Cyan else Color.Gray,
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
fun PermissionItem(name: String, isGranted: Boolean, onClick: () -> Unit, strings: AppStrings) {
    val statusText = if (isGranted) strings.settings.granted else strings.settings.denied
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = strings.settings.openAppSettings
            )
            .padding(vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = statusText
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, fontWeight = FontWeight.Medium)
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null, // Handled by stateDescription
            tint = if (isGranted) Color.Green else Color.Red
        )
    }
}

/** Where Updates ▸ releases page goes, shown under the row (not translated: it is a URL). */
private const val RELEASES_PAGE_DISPLAY = "github.com/HereLiesAz/GraffitiXR/releases"

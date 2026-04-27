package com.hereliesaz.graffitixr.feature.dashboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.aznavrail.AzLoad
import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.design.theme.AppStrings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Composable
fun SettingsScreen(
    currentVersion: String,
    updateStatus: String?,
    isCheckingForUpdate: Boolean,
    currentLanguage: AppLanguage,
    onLanguageChanged: (AppLanguage) -> Unit,
    isRightHanded: Boolean,
    onHandednessChanged: (Boolean) -> Unit,
    showDiagOverlay: Boolean,
    onDiagOverlayChanged: () -> Unit,
    arScanMode: ArScanMode,
    onArScanModeChanged: (ArScanMode) -> Unit,
    showAnchorBoundary: Boolean,
    onAnchorBoundaryChanged: (Boolean) -> Unit,
    isImperialUnits: Boolean,
    onImperialUnitsChanged: (Boolean) -> Unit,
    backgroundColor: Int,
    onBackgroundColorChanged: (Int) -> Unit,
    muralMethod: MuralMethod,
    onMuralMethodChanged: (MuralMethod) -> Unit,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onClose: () -> Unit,
    strings: AppStrings
) {
    val context = LocalContext.current

    // Auto-check for updates on load
    LaunchedEffect(Unit) {
        onCheckForUpdates()
    }

    // Permissions State
    val cameraPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    val storagePermissionName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val storagePermission = remember {
        ContextCompat.checkSelfPermission(context, storagePermissionName) == PackageManager.PERMISSION_GRANTED
    }

    val openAppSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        val uri = Uri.fromParts("package", context.packageName, null)
        intent.data = uri
        context.startActivity(intent)
    }

    var showLanguageDialog by remember { mutableStateOf(false) }

    if (showLanguageDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(strings.settings.languageLabel) },
            text = {
                Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    LazyColumn {
                        items(AppLanguage.entries.size) { index ->
                            val lang = AppLanguage.entries[index]
                            Text(
                                text = lang.displayName,
                                fontSize = 18.sp,
                                color = if (lang == currentLanguage) MaterialTheme.colorScheme.primary else Color.Unspecified,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLanguageChanged(lang)
                                        showLanguageDialog = false
                                    }
                                    .padding(12.dp)
                            )
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
                        // Preferences Section
                        item {
                            SettingsSectionTitle(strings.settings.preferences)
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
                                label = strings.settings.diagOverlay,
                                value = if (showDiagOverlay) strings.settings.on else strings.settings.off,
                                modifier = Modifier.clickable { onDiagOverlayChanged() }
                            )
                            val modes = ArScanMode.entries
                            val nextMode = modes[(arScanMode.ordinal + 1) % modes.size]
                            val scanModeValue = when (arScanMode) {
                                ArScanMode.CLOUD_POINTS -> strings.settings.pointCloud
                                ArScanMode.MURAL -> strings.nav.mural
                            }
                            SettingsItem(
                                label = strings.settings.scanMode,
                                value = scanModeValue,
                                modifier = Modifier.clickable { onArScanModeChanged(nextMode) }
                            )

                            if (arScanMode == ArScanMode.MURAL) {
                                val methods = MuralMethod.entries
                                val nextMethod = methods[(muralMethod.ordinal + 1) % methods.size]
                                val muralMethodValue = when (muralMethod) {
                                    MuralMethod.VOXEL_HASH -> strings.settings.voxelHash
                                    MuralMethod.SURFACE_MESH -> strings.settings.surfaceMesh
                                    MuralMethod.CLOUD_OFFSET -> strings.settings.cloudOffset
                                }
                                SettingsItem(
                                    label = strings.settings.muralMethod,
                                    value = muralMethodValue,
                                    modifier = Modifier.clickable { onMuralMethodChanged(nextMethod) }
                                )
                            }
                            SettingsItem(
                                label = strings.settings.anchorBoundary,
                                value = if (showAnchorBoundary) strings.settings.on else strings.settings.off,
                                modifier = Modifier.clickable { onAnchorBoundaryChanged(!showAnchorBoundary) }
                            )
                            SettingsItem(
                                label = strings.settings.units,
                                value = if (isImperialUnits) strings.settings.imperial else strings.settings.metric,
                                modifier = Modifier.clickable { onImperialUnitsChanged(!isImperialUnits) }
                            )
                            SettingRow(label = strings.settings.canvasBg) {
                                listOf(
                                    "Black" to 0xFF000000.toInt(),
                                    "Dark"  to 0xFF1A1A2E.toInt(),
                                    "Grey"  to 0xFF2C2C2C.toInt(),
                                    "White" to 0xFFFFFFFF.toInt(),
                                    "Navy"  to 0xFF0D1B2A.toInt(),
                                ).forEach { (label, argb) ->
                                    val isSelected = backgroundColor == argb
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(Color(argb.toLong() and 0xFFFFFFFFL), CircleShape)
                                            .border(
                                                width = if (isSelected) 2.dp else 0.5.dp,
                                                color = if (isSelected) Color.Cyan else Color.Gray,
                                                shape = CircleShape
                                            )
                                            .clickable { onBackgroundColorChanged(argb) }
                                    )
                                }
                            }
                        }

                        // Version Section
                        item {
                            SettingsSectionTitle(strings.settings.appInfo)
                            SettingsItem(
                                label = strings.settings.version,
                                value = currentVersion
                            )
                        }

                        // Updates Section
                        item {
                            SettingsSectionTitle(strings.settings.updates)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val isUpdateAvailable = updateStatus?.startsWith("New version") == true
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(
                                            enabled = isUpdateAvailable,
                                            onClick = onInstallUpdate,
                                            role = Role.Button,
                                            onClickLabel = strings.settings.installUpdate
                                        )
                                ) {
                                    Text(text = strings.settings.checkUpdates, fontWeight = FontWeight.Medium)
                                    if (updateStatus != null) {
                                        Text(
                                            text = updateStatus,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if(isUpdateAvailable) MaterialTheme.colorScheme.primary else Color.Gray
                                        )
                                    }
                                }
                                IconButton(onClick = onCheckForUpdates, enabled = !isCheckingForUpdate) {
                                    if (isCheckingForUpdate) {
                                        AzLoad()
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = strings.settings.checkUpdates)
                                    }
                                }
                            }
                        }

                        // Permissions Section (User can accept/revoke via App Settings)
                        item {
                            SettingsSectionTitle(strings.settings.permissions)
                            PermissionItem(name = strings.settings.cameraAccess, isGranted = cameraPermission, onClick = openAppSettings, strings = strings)
                            PermissionItem(name = strings.settings.photoAccess, isGranted = storagePermission, onClick = openAppSettings, strings = strings)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val notificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                PermissionItem(name = strings.settings.notifications, isGranted = notificationPermission, onClick = openAppSettings, strings = strings)
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
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = value, color = Color.Gray)
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
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

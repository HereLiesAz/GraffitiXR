package com.hereliesaz.graffitixr.composables

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hereliesaz.aznavrail.AzLoad

@Composable
fun SettingsScreen(
    currentVersion: String,
    updateStatus: String?,
    isCheckingForUpdate: Boolean,
    onCheckForUpdates: () -> Unit,
    onInstallUpdate: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

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


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .windowInsetsPadding(WindowInsets.systemBars)
            .clickable(enabled = true) {}, // Block clicks
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 88.dp), // Clear the navigation rail
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
                            text = "Settings",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Version Section
                        item {
                            SettingsSectionTitle("App Information")
                            SettingsItem(
                                label = "Version",
                                value = currentVersion
                            )
                        }

                        // Updates Section
                        item {
                            SettingsSectionTitle("Updates")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val isUpdateAvailable = updateStatus?.startsWith("New version") == true
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = isUpdateAvailable, onClick = onInstallUpdate)
                                ) {
                                    Text(text = "Check for experimental updates", fontWeight = FontWeight.Medium)
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
                                        Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                                    }
                                }
                            }
                        }

                        // Permissions Section (User can accept/revoke via App Settings)
                        item {
                            SettingsSectionTitle("Permissions")
                            PermissionItem(name = "Camera Access", isGranted = cameraPermission, onClick = openAppSettings)
                            PermissionItem(name = "Photo Library Access", isGranted = storagePermission, onClick = openAppSettings)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val notificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                PermissionItem(name = "Notifications", isGranted = notificationPermission, onClick = openAppSettings)
                            }
                        }
                    }

                    Text(
                        text = "GraffitiXR © 2024",
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
fun SettingsItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontWeight = FontWeight.Medium)
        Text(text = value, color = Color.Gray)
    }
}

@Composable
fun PermissionItem(name: String, isGranted: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = name, fontWeight = FontWeight.Medium)
        Icon(
            imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = if (isGranted) "Granted" else "Denied",
            tint = if (isGranted) Color.Green else Color.Red
        )
    }
}
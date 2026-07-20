package com.hereliesaz.graffitixr.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.hereliesaz.aznavrail.AzButton
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.data.azphalt.MarketplaceEntry

/**
 * The azphalt marketplace, host side. Users browse the catalog, install `.azp` extensions, and apply
 * an installed LUT to the active editor layer. This is the "make use of its plugins and extensions"
 * payoff: an installed grade actually transforms the artwork via the editor.
 *
 * [onApplyLut] hands an installed extension id to the editor (EditorViewModel.applyInstalledLut).
 */
@Composable
fun MarketplaceScreen(
    viewModel: MarketplaceViewModel,
    onApplyLut: (String) -> Unit,
    onClose: () -> Unit,
) {
    val installed by viewModel.installed.collectAsState()
    val busyId by viewModel.busyId.collectAsState()
    val status by viewModel.status.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val offline by viewModel.offline.collectAsState()
    val loading by viewModel.loading.collectAsState()

    val installedLuts = remember(installed) {
        installed.filter { ext ->
            ext.manifest.assets.any { it.type == AssetType.LUT }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f))
            .clickable(enabled = true) {}, // Block clicks reaching the canvas behind.
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight(0.85f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Extensions",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                        }
                    }

                    status?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (installedLuts.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Installed — apply to active layer",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            items(installedLuts, key = { "applied-${it.id}" }) { ext ->
                                InstalledLutRow(
                                    ext = ext,
                                    onApply = { onApplyLut(ext.id) },
                                )
                            }
                            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Browse",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val note = when {
                                    loading -> "Loading…"
                                    offline -> "Offline · bundled extensions"
                                    else -> null
                                }
                                note?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                        items(catalog, key = { it.id }) { entry ->
                            CatalogRow(
                                entry = entry,
                                isInstalled = installed.any { it.id == entry.id },
                                isBusy = busyId == entry.id,
                                onInstall = { viewModel.install(entry) },
                                onUninstall = { viewModel.uninstall(entry.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledLutRow(ext: InstalledExtension, onApply: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = ext.manifest.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        AzButton(text = "Apply", onClick = onApply)
    }
}

@Composable
private fun CatalogRow(
    entry: MarketplaceEntry,
    isInstalled: Boolean,
    isBusy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    text = "${entry.author} · ${entry.priceLabel}" +
                        (entry.rating?.let { " · ★ $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Box(modifier = Modifier.padding(start = 8.dp)) {
                when {
                    isBusy -> Text(
                        text = "…",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    !entry.installable -> Text(
                        text = "Soon",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    isInstalled -> AzButton(text = "Remove", onClick = onUninstall)
                    else -> AzButton(text = "Install", onClick = onInstall)
                }
            }
        }
    }
}

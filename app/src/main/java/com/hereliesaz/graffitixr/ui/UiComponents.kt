package com.hereliesaz.graffitixr.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

// Local definition of AzNavRail to support custom features like 'Isolate' and Relocation
// This mirrors the library but allows for app-specific customizations if needed,
// or acts as a bridge if the library version is incompatible.

@Composable
fun AzNavRail(
    navController: NavController?,
    currentDestination: String?,
    isLandscape: Boolean,
    content: AzNavRailScope.() -> Unit
) {
    val scope = AzNavRailScopeImpl()
    scope.content()

    val railItems = scope.items
    val settings = scope.settings

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .width(80.dp)
                .background(Color.Black)
                .padding(vertical = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(railItems) { item ->
                    RailItemView(item, currentDestination, settings.activeColor)
                }
            }
        }
    } else {
        // Portrait - Bottom Bar style or similar
        // For this app, we stick to a side rail even in portrait or use a bottom bar.
        // Assuming Bottom Rail for Portrait based on typical usage.
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color.Black)
                    .align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                railItems.filter { it.hostId == null }.forEach { item ->
                    RailItemView(item, currentDestination, settings.activeColor)
                }
            }
        }
    }
}

// ... Scope and Item classes ...
interface AzNavRailScope {
    fun azSettings(
        isLoading: Boolean = false,
        packRailButtons: Boolean = true,
        defaultShape: Any? = null,
        headerIconShape: Any? = null,
        infoScreen: Boolean = false,
        activeColor: Color = Color.Cyan,
        onDismissInfoScreen: () -> Unit = {}
    )
    fun azRailItem(id: String, text: String, info: String? = null, onClick: () -> Unit)
    fun azRailHostItem(id: String, text: String, onClick: () -> Unit)
    fun azRailSubItem(id: String, hostId: String, text: String, info: String? = null, onClick: () -> Unit)
    fun azRailSubToggle(id: String, hostId: String, isChecked: Boolean, toggleOnText: String, toggleOffText: String, info: String? = null, onClick: () -> Unit)
    fun azDivider()

    // The critical one for layers
    fun azRailRelocItem(
        id: String,
        hostId: String,
        text: String,
        onClick: () -> Unit,
        onRelocate: (Int, Int, List<String>) -> Unit,
        content: AzRelocScope.() -> Unit
    )
}

interface AzRelocScope {
    fun inputItem(hint: String, onConfirm: (String) -> Unit)
    fun listItem(text: String, onClick: () -> Unit)
}

private class AzNavRailScopeImpl : AzNavRailScope {
    val items = mutableListOf<RailItem>()
    var settings = AzSettings()

    override fun azSettings(
        isLoading: Boolean,
        packRailButtons: Boolean,
        defaultShape: Any?,
        headerIconShape: Any?,
        infoScreen: Boolean,
        activeColor: Color,
        onDismissInfoScreen: () -> Unit
    ) {
        settings = AzSettings(isLoading, packRailButtons, activeColor)
    }

    override fun azRailItem(id: String, text: String, info: String?, onClick: () -> Unit) {
        items.add(RailItem(id, null, text, info, onClick))
    }

    override fun azRailHostItem(id: String, text: String, onClick: () -> Unit) {
        items.add(RailItem(id, null, text, null, onClick, isHost = true))
    }

    override fun azRailSubItem(id: String, hostId: String, text: String, info: String?, onClick: () -> Unit) {
        items.add(RailItem(id, hostId, text, info, onClick))
    }

    override fun azRailSubToggle(
        id: String,
        hostId: String,
        isChecked: Boolean,
        toggleOnText: String,
        toggleOffText: String,
        info: String?,
        onClick: () -> Unit
    ) {
        items.add(RailItem(id, hostId, if(isChecked) toggleOnText else toggleOffText, info, onClick))
    }

    override fun azDivider() {
        // No-op for simple mock
    }

    override fun azRailRelocItem(
        id: String,
        hostId: String,
        text: String,
        onClick: () -> Unit,
        onRelocate: (Int, Int, List<String>) -> Unit,
        content: AzRelocScope.() -> Unit
    ) {
        items.add(RailItem(id, hostId, text, null, onClick))
    }
}

data class AzSettings(
    val isLoading: Boolean = false,
    val packRailButtons: Boolean = true,
    val activeColor: Color = Color.Cyan
)

data class RailItem(
    val id: String,
    val hostId: String?,
    val text: String,
    val info: String?,
    val onClick: () -> Unit,
    val isHost: Boolean = false
)

@Composable
fun RailItemView(item: RailItem, currentDestination: String?, activeColor: Color) {
    val isActive = currentDestination == item.id
    val bgColor = if (isActive) activeColor.copy(alpha = 0.3f) else Color.Transparent

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { item.onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon placeholder
        Icon(Icons.Default.Menu, contentDescription = null, tint = Color.White)
        Text(item.text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

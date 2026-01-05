package com.hereliesaz.graffitixr.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Data classes to represent the navigation structure
data class NavItem(
    val id: String,
    val text: String,
    val onClick: () -> Unit,
    val isSelected: Boolean = false
)

data class NavToggleItem(
    val id: String,
    val isChecked: Boolean,
    val toggleOnText: String,
    val toggleOffText: String,
    val onClick: () -> Unit,
    val isSelected: Boolean = false
)

data class NavHostItem(
    val id: String,
    val text: String,
    val children: List<Any>
)

private object NavDivider

@Composable
fun GraffitiNavRail(
    currentDestination: String?,
    isLandscape: Boolean,
    content: @Composable GraffitiNavRailScope.() -> Unit
) {
    val scope = remember { GraffitiNavRailScope() }
    scope.content()

    var activeHost by remember { mutableStateOf<NavHostItem?>(null) }

    Row(modifier = Modifier.fillMaxHeight()) {
        // Main Rail
        LazyColumn(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                .fillMaxHeight()
                .padding(4.dp)
                .width(80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(scope.getItems()) { item ->
                when (item) {
                    is NavHostItem -> {
                        val isSelected = item.children.any {
                            when (it) {
                                is NavItem -> it.id == currentDestination
                                is NavToggleItem -> it.id == currentDestination
                                else -> false
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { activeHost = item }
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                                .fillMaxWidth()
                        ) {
                            Text(text = item.text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    is NavItem -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (item.id == currentDestination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable(onClick = item.onClick)
                                .padding(vertical = 12.dp, horizontal = 4.dp)
                                .fillMaxWidth()
                        ) {
                            Text(text = item.text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = if (item.id == currentDestination) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    is NavDivider -> {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        // Sub-menu (fly-out)
        AnimatedVisibility(
            visible = activeHost != null,
            enter = expandHorizontally(),
            exit = shrinkHorizontally() + fadeOut()
        ) {
            activeHost?.let { host ->
                LazyColumn(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
                        .fillMaxHeight()
                        .padding(4.dp)
                        .width(120.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { activeHost = null }
                                .padding(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            Spacer(Modifier.width(8.dp))
                            Text(host.text, fontWeight = FontWeight.Bold)
                        }
                        Divider()
                    }
                    items(host.children) { subItem ->
                        when (subItem) {
                            is NavItem -> {
                                Text(
                                    text = subItem.text,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (subItem.id == currentDestination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable(onClick = subItem.onClick)
                                        .padding(12.dp),
                                    color = if (subItem.id == currentDestination) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            is NavToggleItem -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (subItem.id == currentDestination) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                        .clickable(onClick = subItem.onClick)
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (subItem.isChecked) subItem.toggleOnText else subItem.toggleOffText,
                                        color = if (subItem.id == currentDestination) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Switch(
                                        checked = subItem.isChecked,
                                        onCheckedChange = { subItem.onClick() },
                                        thumbContent = {
                                            if (subItem.isChecked) {
                                                Box(modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                            } else {
                                                Box(modifier = Modifier.size(16.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Stable
class GraffitiNavRailScope {
    private val items = mutableListOf<Any>()

    @Composable
    fun railHostItem(id: String, text: String, content: @Composable GraffitiNavRailScope.() -> Unit) {
        val scope = remember { GraffitiNavRailScope() }
        scope.content()
        items.add(NavHostItem(id, text, scope.getItems()))
    }

    @Composable
    fun railSubItem(id: String, text: String, onClick: () -> Unit, isSelected: Boolean = false) {
        items.add(NavItem(id, text, onClick, isSelected))
    }

    @Composable
    fun railItem(id: String, text: String, onClick: () -> Unit, isSelected: Boolean = false) {
        items.add(NavItem(id, text, onClick, isSelected))
    }

    @Composable
    fun railSubToggle(id: String, isChecked: Boolean, toggleOnText: String, toggleOffText: String, onClick: () -> Unit, isSelected: Boolean = false) {
        items.add(NavToggleItem(id, isChecked, toggleOnText, toggleOffText, onClick, isSelected))
    }

    @Composable
    fun divider() {
        items.add(NavDivider)
    }

    fun getItems(): List<Any> = items
}

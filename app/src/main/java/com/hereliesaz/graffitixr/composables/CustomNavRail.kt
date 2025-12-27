package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.font.FontWeight

// Data models for the rail
sealed class RailItem {
    abstract val id: String
    abstract val label: String

    data class Host(override val id: String, override val label: String, val subItems: List<Sub>) : RailItem()
    data class Sub(override val id: String, override val label: String, val onClick: () -> Unit) : RailItem()
    data class Leaf(override val id: String, override val label: String, val onClick: () -> Unit) : RailItem()
}

@Composable
fun CustomNavRail(
    items: List<RailItem>,
    expandedHostId: String?,
    onHostClick: (String) -> Unit,
    onItemPositioned: (String, Rect) -> Unit
) {
    NavigationRail(
        containerColor = Color.Black,
        contentColor = Color.White,
        modifier = Modifier.width(80.dp) // Approximate standard width
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { item ->
                when (item) {
                    is RailItem.Host -> {
                        RailHostButton(
                            label = item.label,
                            isSelected = item.id == expandedHostId,
                            onClick = { onHostClick(item.id) },
                            modifier = Modifier.onGloballyPositioned {
                                onItemPositioned(item.id, it.boundsInRoot())
                            }
                        )
                        if (item.id == expandedHostId) {
                            item.subItems.forEach { sub ->
                                RailSubButton(
                                    label = sub.label,
                                    onClick = sub.onClick,
                                    modifier = Modifier.onGloballyPositioned {
                                        onItemPositioned(sub.id, it.boundsInRoot())
                                    }
                                )
                            }
                            HorizontalDivider(
                                color = Color.DarkGray,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    is RailItem.Leaf -> {
                        RailHostButton( // Using Host style for top-level leaves
                            label = item.label,
                            isSelected = false,
                            onClick = item.onClick,
                            modifier = Modifier.onGloballyPositioned {
                                onItemPositioned(item.id, it.boundsInRoot())
                            }
                        )
                    }
                    is RailItem.Sub -> {} // Handled inside Host
                }
            }
        }
    }
}

@Composable
fun RailHostButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color.DarkGray else Color.Transparent,
            contentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun RailSubButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Color.LightGray),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 0.dp),
        modifier = modifier.fillMaxWidth().height(40.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
    }
}

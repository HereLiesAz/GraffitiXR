package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A row of indicators (dots) to show the current page in a pager or wizard.
 *
 * @param pageCount Total number of pages.
 * @param currentPage The index of the currently selected page (0-based).
 * @param onPageSelected Callback when an indicator is clicked.
 * @param modifier Modifier for the layout.
 * @param activeColor Color of the active indicator.
 * @param inactiveColor Color of the inactive indicators.
 */
@Composable
fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp), // Reduce spacing as touch targets are large
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { page ->
            val isSelected = page == currentPage
            Box(
                modifier = Modifier
                    .size(48.dp) // Accessibility touch target size
                    .clickable { onPageSelected(page) }
                    .semantics {
                        role = Role.Button
                        contentDescription = "Go to step ${page + 1}"
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) activeColor else inactiveColor)
                )
            }
        }
    }
}

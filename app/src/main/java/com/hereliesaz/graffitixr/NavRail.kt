package com.hereliesaz.graffitixr

import androidx.compose.runtime.Composable
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.NavItem
import com.hereliesaz.aznavrail.NavItemData
import com.hereliesaz.aznavrail.NavRailMenuSection

enum class SliderType {
    Opacity,
    Contrast,
    Saturation,
    Brightness
}

@Composable
fun AppNavRail(
    onSelectImage: () -> Unit,
    onClearMarkers: () -> Unit,
    onLockMural: () -> Unit,
    onResetMural: () -> Unit,
    onSliderSelected: (SliderType) -> Unit
) {
    AzNavRail(
        menuSections = listOf(
            NavRailMenuSection(
                title = "Image",
                items = listOf(
                    NavItem(
                        text = "Select Image",
                        data = NavItemData.Action(onClick = onSelectImage)
                    ),
                    NavItem(
                        text = "Remove BG",
                        data = NavItemData.Action(onClick = { /* TODO */ })
                    )
                )
            ),
            NavRailMenuSection(
                title = "Adjustments",
                items = listOf(
                    NavItem(
                        text = "Opacity",
                        data = NavItemData.Action(onClick = { onSliderSelected(SliderType.Opacity) })
                    ),
                    NavItem(
                        text = "Saturation",
                        data = NavItemData.Action(onClick = { onSliderSelected(SliderType.Saturation) })
                    ),
                    NavItem(
                        text = "Brightness",
                        data = NavItemData.Action(onClick = { onSliderSelected(SliderType.Brightness) })
                    ),
                    NavItem(
                        text = "Contrast",
                        data = NavItemData.Action(onClick = { onSliderSelected(SliderType.Contrast) })
                    )
                )
            ),
            NavRailMenuSection(
                title = "XR",
                items = listOf(
                    NavItem(
                        text = "Lock",
                        data = NavItemData.Action(onClick = onLockMural)
                    ),
                    NavItem(
                        text = "Reset",
                        data = NavItemData.Action(onClick = onResetMural)
                    ),
                    NavItem(
                        text = "Clear Markers",
                        data = NavItemData.Action(onClick = onClearMarkers)
                    )
                )
            )
        )
    )
}

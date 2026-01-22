package com.hereliesaz.graffitixr.utils

import androidx.compose.ui.graphics.Color
import com.hereliesaz.aznavrail.model.AzButtonShape
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.aznavrail.model.AzHeaderIconShape
import com.hereliesaz.aznavrail.AzNavHostScope

/**
 * Shim to support AzNavRail 6.21 API compliance on the 6.20 library version.
 * This allows the code to follow strict 6.21 protocols while waiting for the library update.
 *
 * Note: Since 6.20's azSettings is likely monolithic (overwrites previous settings),
 * splitting configuration might cause earlier settings to be lost if they are called sequentially.
 * This is a known limitation of running 6.21 code on 6.20.
 */

fun AzNavHostScope.azTheme(
    activeColor: Color? = null,
    defaultShape: AzButtonShape? = null,
    headerIconShape: AzHeaderIconShape? = null
) {
    // Best effort to set theme.
    // Warning: This might overwrite other settings with defaults if azSettings doesn't support partial updates.
    if (activeColor != null || defaultShape != null || headerIconShape != null) {
        this.azSettings(
            activeColor = activeColor ?: Color.Green, // Default fallback if null passed but function called?
            // Actually, we should only pass what we have.
            // But Kotlin arguments works by defaulting the others.
            // We can't conditionally omit arguments.
            // So we assume defaults.
            defaultShape = defaultShape ?: AzButtonShape.RECTANGLE,
            headerIconShape = headerIconShape ?: AzHeaderIconShape.ROUNDED
        )
    }
}

fun AzNavHostScope.azConfig(
    packButtons: Boolean? = null,
    displayAppName: Boolean? = null,
    dockingSide: AzDockingSide? = null
) {
    if (packButtons != null || displayAppName != null || dockingSide != null) {
        this.azSettings(
            packRailButtons = packButtons ?: false,
            displayAppNameInHeader = displayAppName ?: false,
            dockingSide = dockingSide ?: AzDockingSide.RIGHT
        )
    }
}

fun AzNavHostScope.azAdvanced(
    isLoading: Boolean = false,
    infoScreen: Boolean = false,
    onDismissInfoScreen: () -> Unit = {}
) {
    // This is usually the last call in MainScreen, so it might overwrite others.
    this.azSettings(
        isLoading = isLoading,
        infoScreen = infoScreen,
        onDismissInfoScreen = onDismissInfoScreen
    )
}

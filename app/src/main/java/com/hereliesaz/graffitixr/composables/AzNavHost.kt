package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.AzNavRailScope

/**
 * A container that combines navigation and the application's signature AzNavRail.
 * It enforces the UI safety zones and overlay structure.
 */
@Composable
fun AzNavHost(
    navController: NavHostController,
    startDestination: String,
    currentDestination: String?,
    isLandscape: Boolean,
    isRailVisible: Boolean = true,
    rail: @Composable AzNavRailScope.() -> Unit,
    builder: NavGraphBuilder.() -> Unit
) {
    val configuration = LocalConfiguration.current

    // Safe Zone Calculations (Top/Bottom 10%)
    val safeInsets = WindowInsets.safeDrawing.asPaddingValues()
    val screenHeight = configuration.screenHeightDp.dp
    // 10% safety margin plus system bars
    val topSafePadding = (screenHeight * 0.1f).coerceAtLeast(safeInsets.calculateTopPadding() + 16.dp)
    val bottomSafePadding = (screenHeight * 0.1f).coerceAtLeast(safeInsets.calculateBottomPadding() + 16.dp)

    Box(modifier = Modifier.fillMaxSize()) {
        // Content Layer (Z-Index 1)
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
            builder = builder
        )

        // Rail Layer (Z-Index 6)
        if (isRailVisible) {
            Box(
                modifier = Modifier
                    .zIndex(6f)
                    .fillMaxHeight()
                    .padding(top = topSafePadding, bottom = bottomSafePadding)
            ) {
                AzNavRail(
                    navController = null, // Navigation handled manually in rail block
                    currentDestination = currentDestination,
                    isLandscape = isLandscape,
                    content = rail
                )
            }
        }
    }
}

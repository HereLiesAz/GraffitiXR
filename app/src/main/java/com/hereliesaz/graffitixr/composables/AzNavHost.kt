package com.hereliesaz.graffitixr.composables

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.hereliesaz.aznavrail.AzNavHostScope
import com.hereliesaz.aznavrail.AzNavHostScopeImpl
import com.hereliesaz.aznavrail.AzNavRail
import com.hereliesaz.aznavrail.LocalAzNavHostPresent
import com.hereliesaz.aznavrail.LocalAzSafeZones
import com.hereliesaz.aznavrail.internal.AzLayoutConfig
import com.hereliesaz.aznavrail.internal.AzSafeZones
import com.hereliesaz.aznavrail.model.AzDockingSide

@Composable
fun AzNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController,
    startDestination: String,
    currentDestination: String? = null,
    isLandscape: Boolean? = null,
    isRailVisible: Boolean = true,
    backgroundContent: @Composable () -> Unit = {},
    rail: AzNavHostScope.() -> Unit,
    content: NavGraphBuilder.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val effectiveIsLandscape = isLandscape ?: (configuration.screenWidthDp > configuration.screenHeightDp)

    val effectiveCurrentDestination = if (currentDestination != null) {
        currentDestination
    } else {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        navBackStackEntry?.destination?.route
    }

    val scope = remember { AzNavHostScopeImpl() }
    scope.resetHost()
    scope.apply(rail)

    // Determine rail settings
    val railScope = scope.getRailScopeImpl()
    val dockingSide = railScope.dockingSide
    val railWidth = railScope.collapsedRailWidth

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxHeight = maxHeight
        val safeTop = maxHeight * AzLayoutConfig.SafeTopPercent
        val safeBottom = maxHeight * AzLayoutConfig.SafeBottomPercent

        // Layer 1: Backgrounds
        // Note: scope.backgrounds are defined in the rail block, but NavHost content usually covers everything.
        // If we want to support backgrounds behind NavHost, we render them here.
        Box(modifier = Modifier.fillMaxSize()) {
            backgroundContent()
        }

        // Layer 2: Content (NavHost)
        val startPadding = if (isRailVisible && dockingSide == AzDockingSide.LEFT) railWidth else 0.dp
        val endPadding = if (isRailVisible && dockingSide == AzDockingSide.RIGHT) railWidth else 0.dp
        val topPadding = if (isRailVisible) safeTop else 0.dp
        val bottomPadding = if (isRailVisible) safeBottom else 0.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = topPadding, bottom = bottomPadding, start = startPadding, end = endPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = startDestination,
                builder = content
            )
        }

        // Layer 3: AzNavRail
        if (isRailVisible) {
            CompositionLocalProvider(
                LocalAzNavHostPresent provides true,
                LocalAzSafeZones provides AzSafeZones(safeTop, safeBottom)
            ) {
                AzNavRail(
                    modifier = Modifier.fillMaxSize(),
                    navController = navController,
                    currentDestination = effectiveCurrentDestination,
                    isLandscape = effectiveIsLandscape,
                    providedScope = railScope
                ) {}
            }
        }
    }
}

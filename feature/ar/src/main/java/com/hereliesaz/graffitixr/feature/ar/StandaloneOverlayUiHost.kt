package com.hereliesaz.graffitixr.feature.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * The standalone overlay's camera surface is AzHostActivityLayout *background* content: full-screen,
 * behind the rail and outside its safe zones. Anything the artist must read or tap — the tracking
 * status, Copy Diagnostics, Recapture / New Target, the sweep hint, the fatal dialog — belongs in the
 * host's *onscreen* layer, which keeps it out of the top 20 % / bottom 10 % and clear of the rail
 * (a button drawn in the background's top strip sat under the status bar and never got the tap).
 *
 * [SphereSlamStandaloneOverlay] publishes that UI here; the host renders it in `onscreen`. Without a
 * host the overlay draws it inline, as before.
 */

/** Hand-off between the standalone overlay (background) and the host's onscreen layer. */
class StandaloneOverlayUiHost {
    /** The overlay's interactive UI, or null when it has none (e.g. not in standalone AR). */
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        internal set

    /** Bumped by [requestNewTarget]; the overlay drops its target and opens capture on each change. */
    var newTargetRequests: Int by mutableIntStateOf(0)
        private set

    /** Start a new wall target (the rail's Target action in standalone). */
    fun requestNewTarget() {
        newTargetRequests++
    }
}

/** Render whatever the overlay published (call from the host's onscreen layer). */
@Composable
fun StandaloneOverlayUi(host: StandaloneOverlayUiHost) {
    host.content?.invoke()
}

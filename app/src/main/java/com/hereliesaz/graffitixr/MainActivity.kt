package com.hereliesaz.graffitixr

import android.os.Bundle
import com.hereliesaz.aznavrail.AzActivity
import com.hereliesaz.aznavrail.annotation.App
import com.hereliesaz.aznavrail.annotation.Az
import com.hereliesaz.aznavrail.model.AzDockingSide
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The Single Activity for the application.
 * Sets up the Compose content, Hilt injection, and the top-level Navigation Graph.
 */
@Az(app = App(
    dock = AzDockingSide.LEFT,
))
@AndroidEntryPoint
class MainActivity : AzActivity() {

    // Link the Generated Graph
    override val graph = AzGraph

    @Inject lateinit var slamManager: SlamManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // RESURRECTION: Ensure native engine is alive even if the Process survived
        // but the Activity was previously destroyed.
        slamManager.ensureInitialized()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only release native resources if the Activity is actually finishing (not rotating)
        // This prevents the Native Engine from being killed and recreated on configuration changes.
        if (isFinishing) {
            slamManager.destroy()
        }
    }
}

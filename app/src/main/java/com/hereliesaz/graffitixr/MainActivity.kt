package com.hereliesaz.graffitixr

import android.os.Bundle
import com.hereliesaz.aznavrail.AzActivity
import com.hereliesaz.aznavrail.annotation.App
import com.hereliesaz.aznavrail.annotation.Az
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The Single Activity for the application.
 * Extends AzActivity to use the generated AzNavRail graph.
 */
@AndroidEntryPoint
@Az(app = App())
class MainActivity : AzActivity() {

    @Inject lateinit var slamManager: SlamManager
    @Inject lateinit var projectRepository: com.hereliesaz.graffitixr.domain.repository.ProjectRepository

    override val graph = AzGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // RESURRECTION: Ensure native engine is alive
        slamManager.ensureInitialized()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            slamManager.destroy()
        }
    }
}

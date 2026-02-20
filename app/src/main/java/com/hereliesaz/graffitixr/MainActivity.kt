package com.hereliesaz.graffitixr

import android.os.Bundle
import com.hereliesaz.aznavrail.AzActivity
import com.hereliesaz.aznavrail.annotation.App
import androidx.activity.viewModels
import com.hereliesaz.aznavrail.annotation.Az
import com.hereliesaz.graffitixr.feature.ar.ArViewModel
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

    private val mainViewModel: MainViewModel by viewModels()
    private val arViewModel: ArViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // RESURRECTION: Ensure native engine is alive
        slamManager.ensureInitialized()

        // Initialize Action Dispatcher for pure-function rail items
        ActionDispatcher.setViewModels(arViewModel, mainViewModel)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            slamManager.destroy()
        }
    }
}

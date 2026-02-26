package com.hereliesaz.graffitixr.feature.ar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Entry point for the AR Mapping experience.
 * Handles permissions and dependency injection for the AR session.
 */
@AndroidEntryPoint
class MappingActivity : ComponentActivity() {

    @Inject
    lateinit var slamManager: SlamManager

    @Inject
    lateinit var projectRepository: ProjectRepository

    private val viewModel: ArViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            MappingScreen(
                viewModel = viewModel,
                slamManager = slamManager,
                projectRepository = projectRepository,
                hasCameraPermission = hasCameraPermission,
                onBackClick = { finish() },
                onScanComplete = { /* Navigation logic for project save */ }
            )
        }
    }
}
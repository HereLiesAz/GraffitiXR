package com.hereliesaz.graffitixr.feature.ar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint

/**
 * Entry point for the AR mapping process.
 */
@AndroidEntryPoint
class MappingActivity : ComponentActivity() {

    private val viewModel: ArViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            // Corrected: MappingScreen now only takes the ViewModel and Modifier
            MappingScreen(
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
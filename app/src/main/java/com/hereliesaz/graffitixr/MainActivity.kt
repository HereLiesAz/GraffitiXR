package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure shared OpenCV loader is triggered
        ensureOpenCVLoaded()

        setContent {
            GraffitiXRTheme {
                MainScreen(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.arRenderer?.onResume(this)
    }

    override fun onPause() {
        super.onPause()
        viewModel.arRenderer?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.arRenderer?.cleanup()
    }
}
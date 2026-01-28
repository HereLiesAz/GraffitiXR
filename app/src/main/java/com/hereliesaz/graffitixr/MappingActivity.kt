package com.hereliesaz.graffitixr

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded

class MappingActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModelFactory(application) }
    private var arRenderer: ArRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureOpenCVLoaded()

        setContent {
            GraffitiXRTheme {
                MappingScreen(
                    onBackClicked = { finish() },
                    onRendererCreated = { renderer ->
                        arRenderer = renderer
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        arRenderer?.onResume(this)
    }

    override fun onPause() {
        super.onPause()
        arRenderer?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arRenderer?.cleanup()
        arRenderer = null
    }
}

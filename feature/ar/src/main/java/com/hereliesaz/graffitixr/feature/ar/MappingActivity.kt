package com.hereliesaz.graffitixr.feature.ar

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.nativebridge.ensureOpenCVLoaded
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MappingActivity : ComponentActivity() {

    // INJECT THE SINGLETON ENGINE
    @Inject lateinit var slamManager: SlamManager

    private var arRenderer: ArRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureOpenCVLoaded()

        setContent {
            GraffitiXRTheme {
                MappingScreen(
                    slamManager = slamManager, // Pass down to screen
                    onMapSaved = { mapId ->
                        runOnUiThread {
                            Toast.makeText(this, "Map Saved: $mapId", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    },
                    onExit = { finish() },
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
        arRenderer?.onPause(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        arRenderer?.cleanup()
        arRenderer = null
    }
}
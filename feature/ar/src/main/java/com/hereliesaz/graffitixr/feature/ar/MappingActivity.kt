package com.hereliesaz.graffitixr.feature.ar

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hereliesaz.graffitixr.design.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.natives.ensureOpenCVLoaded

class MappingActivity : ComponentActivity() {

    private var arRenderer: ArRenderer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureOpenCVLoaded()

        setContent {
            GraffitiXRTheme {
                MappingScreen(
                    // FIX: Provide missing callbacks
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
        arRenderer?.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arRenderer?.cleanup()
        arRenderer = null
    }
}

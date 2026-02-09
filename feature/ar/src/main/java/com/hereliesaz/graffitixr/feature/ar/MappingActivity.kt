package com.hereliesaz.graffitixr.feature.ar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hereliesaz.graffitixr.feature.ar.GraffitiArView
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer

class MappingActivity : ComponentActivity() {

    private lateinit var arView: GraffitiArView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This activity seems to be a wrapper for the mapping screen
        // Initialize View directly or via Compose
        arView = GraffitiArView(this)
        setContentView(arView)
    }

    override fun onResume() {
        super.onResume()
        if (::arView.isInitialized) {
            arView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::arView.isInitialized) {
            arView.onPause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::arView.isInitialized) {
            arView.cleanup()
        }
    }
}
package com.hereliesaz.graffitixr

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.xr.compose.SceneView
import androidx.xr.compose.rememberScene
import androidx.xr.compose.ARScene
import androidx.xr.compose.rememberARCameraNode
import com.hereliesaz.graffitixr.ui.theme.MuralOverlayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuralOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val camera = rememberARCameraNode()
                    ARScene(
                        modifier = Modifier.fillMaxSize(),
                        camera = camera,
                    )
                }
            }
        }
    }
}

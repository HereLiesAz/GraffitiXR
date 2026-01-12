package com.hereliesaz.graffitixr

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded

class MappingActivity : ComponentActivity() {
    private val TAG = "MappingActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.v(TAG, "onCreate: called")

        // Use robust initialization from utils to ensure native libraries are ready
        if (!ensureOpenCVLoaded()) {
            Log.e(TAG, "OpenCV initialization failed in MappingActivity")
        } else {
            Log.v(TAG, "OpenCV initialized successfully in MappingActivity")
        }

        setContent {
            GraffitiXRTheme {
                MappingScreen(onExit = {
                    Log.v(TAG, "onExit: called from MappingScreen")
                    finish()
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.v(TAG, "onStart: called")
    }

    override fun onResume() {
        super.onResume()
        Log.v(TAG, "onResume: called")
    }

    override fun onPause() {
        super.onPause()
        Log.v(TAG, "onPause: called")
    }

    override fun onStop() {
        super.onStop()
        Log.v(TAG, "onStop: called")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.v(TAG, "onDestroy: called")
    }

    override fun onRestart() {
        super.onRestart()
        Log.v(TAG, "onRestart: called")
    }
}

package com.hereliesaz.graffitixr

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.hereliesaz.graffitixr.ui.theme.GraffitiXRTheme
import com.hereliesaz.graffitixr.utils.ensureOpenCVLoaded
import org.opencv.android.OpenCVLoader

class MappingActivity : ComponentActivity() {
    private val TAG = "MappingActivity"

    companion object {
        init {
            // Static load to ensure library is available for finalizers
            try {
                if (!OpenCVLoader.initLocal()) {
                    System.loadLibrary("opencv_java4")
                }
            } catch (e: Throwable) {
                Log.e("MappingActivity", "Static OpenCV load failed", e)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.v(TAG, "onCreate: called")

        // Redundant check for safety
        ensureOpenCVLoaded()

        setContent {
            GraffitiXRTheme {
                MappingScreen(
                    onMapSaved = { id ->
                        Log.v(TAG, "Map saved with ID: $id")
                        // In a real scenario, you might return this result to the calling Activity
                        // For now, we just toast and finish
                        runOnUiThread {
                            Toast.makeText(this, "Map Saved: $id", Toast.LENGTH_LONG).show()
                        }
                        finish()
                    },
                    onExit = {
                        Log.v(TAG, "onExit: called from MappingScreen")
                        finish()
                    }
                )
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
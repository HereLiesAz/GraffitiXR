package com.hereliesaz.graffitixr

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eqgis.eqr.layout.SceneLayout
import com.hereliesaz.graffitixr.slam.SlamManager

class MappingActivity : AppCompatActivity() {

    private lateinit var sceneLayout: SceneLayout
    private lateinit var statusText: TextView
    private var slamManager: SlamManager? = null
    private var isMapping = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mapping)

        sceneLayout = findViewById(R.id.ar_scene_layout)
        statusText = findViewById(R.id.tv_status)

        val backButton = findViewById<ImageButton>(R.id.btn_back)
        backButton.setOnClickListener { finish() }

        val startButton = findViewById<Button>(R.id.btn_start_mapping)
        startButton.setOnClickListener { startMapping() }

        val stopButton = findViewById<Button>(R.id.btn_stop_mapping)
        stopButton.setOnClickListener { stopMapping() }

        val saveButton = findViewById<Button>(R.id.btn_save_map)
        saveButton.setOnClickListener { saveMap() }

        try {
            sceneLayout.init(this)
            statusText.text = "Surveyor Mode: Initialized"
        } catch (e: Exception) {
            statusText.text = "Error: ${e.message}"
            android.util.Log.e("MappingActivity", "Error initializing Sceneform-EQR", e)
        }
    }

    private fun startMapping() {
        if (isMapping) return
        try {
            if (slamManager == null) {
                slamManager = SlamManager(this)
                slamManager?.init()
                // TODO: Connect camera stream from SceneLayout to slamManager.processFrame(...)
                // Currently, SceneLayout (from eq-renderer) does not expose a public frame callback
                // compatible with this custom SLAM implementation. To fully enable SLAM,
                // you must either:
                // 1. Modify SceneLayout (if source available) to emit frames.
                // 2. Use a custom AR renderer that provides frame access (like ArRenderer).
            }
            sceneLayout.resume()
            statusText.text = "Mapping Started"
            isMapping = true
            Toast.makeText(this, "Mapping Started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            android.util.Log.e("MappingActivity", "Error starting mapping", e)
            statusText.text = "Error starting mapping"
        }
    }

    private fun stopMapping() {
        if (!isMapping) return
        sceneLayout.pause()
        statusText.text = "Mapping Stopped"
        isMapping = false
        Toast.makeText(this, "Mapping Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun saveMap() {
        slamManager?.saveMap()
        Toast.makeText(this, "Map Save Requested (Check Logs)", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        if (isMapping) {
            sceneLayout.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        sceneLayout.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        sceneLayout.destroy()
        slamManager?.dispose()
    }
}

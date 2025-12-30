package com.hereliesaz.graffitixr

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.eqgis.eqr.layout.SceneLayout
import com.eqgis.slam.core.SlamCore

class MappingActivity : AppCompatActivity() {

    private lateinit var sceneLayout: SceneLayout
    private lateinit var statusText: TextView
    private var slamCore: SlamCore? = null
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
            if (slamCore == null) {
                slamCore = SlamCore(this)
                slamCore?.init()
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
        // Map saving functionality is currently unavailable in the integrated version of eq-slam.
        // Future updates may expose the required API for ORB-SLAM3 map persistence.
        android.util.Log.w("MappingActivity", "Save Map requested but API not found.")
        Toast.makeText(this, "Map Saving Not Supported in this version", Toast.LENGTH_LONG).show()
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
        slamCore?.dispose()
    }
}

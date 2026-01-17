package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.hereliesaz.graffitixr.data.Fingerprint
import com.hereliesaz.graffitixr.data.FingerprintSerializer
import com.hereliesaz.graffitixr.data.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class ProjectManager {

    suspend fun saveProject(context: Context, state: UiState, projectId: String = "current_project") = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()

        // 1. Save Fingerprint
        state.fingerprintJson?.let { json ->
            File(root, "fingerprint.json").writeText(json)
        }

        // 2. Save Targets
        state.capturedTargetImages.forEach { (id, bitmap) ->
            val file = File(root, "target_$id.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        // 3. Save Layer Metadata (simplified)
        // We really should serialize the whole List<OverlayLayer>
        // But for now, we just save a manifest.
        val manifest = Json.encodeToString(state.layers.map { it.id }) // Placeholder
        File(root, "layers_manifest.json").writeText(manifest)

        Log.d("ProjectManager", "Project saved to ${root.absolutePath}")
    }

    suspend fun loadFingerprint(context: Context, projectId: String = "current_project"): Fingerprint? = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "projects/$projectId/fingerprint.json")
        if (!file.exists()) return@withContext null
        try {
            val json = file.readText()
            return@withContext Json.decodeFromString(FingerprintSerializer, json)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load fingerprint", e)
            return@withContext null
        }
    }

    suspend fun loadTargetImages(context: Context, projectId: String = "current_project"): Map<String, Bitmap> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) return@withContext emptyMap()

        val map = mutableMapOf<String, Bitmap>()
        root.listFiles()?.forEach { file ->
            if (file.name.startsWith("target_") && file.name.endsWith(".png")) {
                val id = file.name.removePrefix("target_").removeSuffix(".png")
                try {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    map[id] = bmp
                } catch (e: Exception) {
                    Log.e("ProjectManager", "Bad target image: ${file.name}")
                }
            }
        }
        return@withContext map
    }
}
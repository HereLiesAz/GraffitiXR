package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.UiState
import com.hereliesaz.graffitixr.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

interface UriProvider {
    fun getUriForFile(file: File): Uri
}

class DefaultUriProvider : UriProvider {
    override fun getUriForFile(file: File): Uri {
        return Uri.fromFile(file)
    }
}

class ProjectManager(private val uriProvider: UriProvider = DefaultUriProvider()) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun getProjectList(context: Context): List<String> {
        val projectsDir = File(context.filesDir, "projects")
        if (!projectsDir.exists()) return emptyList()
        return projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    fun deleteProject(context: Context, projectName: String) {
        val projectDir = File(context.filesDir, "projects/$projectName")
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    suspend fun saveProject(context: Context, state: UiState, projectId: String, thumbnail: Bitmap? = null) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()

        // 1. Save Thumbnail (if provided)
        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            FileOutputStream(file).use { out ->
                // Resize for efficiency? Usually caller provides what they want
                thumbnail.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            uriProvider.getUriForFile(file)
        } else {
            // Check if existing thumbnail exists to preserve it
            val file = File(root, "thumbnail.png")
            if (file.exists()) uriProvider.getUriForFile(file) else null
        }

        // 2. Save Target Images (Bitmaps) -> URIs
        val savedTargetUris = state.capturedTargetImages.mapIndexed { index, bitmap ->
            val file = File(root, "target_$index.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            uriProvider.getUriForFile(file)
        }

        // 3. Deserialize Fingerprint JSON string to Object (if exists)
        val fingerprintObj: Fingerprint? = state.fingerprintJson?.let {
            try {
                json.decodeFromString(FingerprintSerializer, it)
            } catch (e: Exception) {
                Log.e("ProjectManager", "Failed to decode fingerprintJson", e)
                null
            }
        }

        val activeLayer = state.layers.find { it.id == state.activeLayerId } ?: state.layers.firstOrNull()

        // 3. Create ProjectData
        // Map List<List<Offset>> to List<List<Pair<Float, Float>>>
        val serializableDrawingPaths = state.drawingPaths.map { path ->
            path.map { offset -> Pair(offset.x, offset.y) }
        }

        val projectData = ProjectData(
            id = projectId,
            name = projectId,
            lastModified = System.currentTimeMillis(),
            backgroundImageUri = state.backgroundImageUri,
            overlayImageUri = state.overlayImageUri,
            thumbnailUri = thumbnailUri,
            targetImageUris = savedTargetUris,
            refinementPaths = state.refinementPaths,
            gpsData = state.gpsData,
            opacity = activeLayer?.opacity ?: 1f,
            brightness = activeLayer?.brightness ?: 0f,
            contrast = activeLayer?.contrast ?: 1f,
            saturation = activeLayer?.saturation ?: 1f,
            colorBalanceR = activeLayer?.colorBalanceR ?: 1f,
            colorBalanceG = activeLayer?.colorBalanceG ?: 1f,
            colorBalanceB = activeLayer?.colorBalanceB ?: 1f,
            scale = activeLayer?.scale ?: 1f,
            rotationX = activeLayer?.rotationX ?: 0f,
            rotationY = activeLayer?.rotationY ?: 0f,
            rotationZ = activeLayer?.rotationZ ?: 0f,
            offset = activeLayer?.offset ?: Offset.Zero,
            blendMode = activeLayer?.blendMode ?: androidx.compose.ui.graphics.BlendMode.SrcOver,
            fingerprint = fingerprintObj,
            drawingPaths = serializableDrawingPaths,
            progressPercentage = state.progressPercentage,
            layers = state.layers,
            calibrationSnapshots = state.calibrationSnapshots
        )

        // 4. Save ProjectData to JSON
        val jsonString = json.encodeToString(projectData)
        File(root, "project.json").writeText(jsonString)
    }

    suspend fun loadProject(context: Context, projectId: String): UiState? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val projectData = json.decodeFromString<ProjectData>(jsonString)

            // Load Target Bitmaps
            val targetBitmaps = projectData.targetImageUris.mapNotNull { uri ->
                ImageUtils.loadBitmapFromUri(context, uri)
            }

            // Fingerprint Object -> JSON String
            val fingerprintJson = projectData.fingerprint?.let {
                json.encodeToString(FingerprintSerializer, it)
            }

            // Map drawing paths back to Offset
            val drawingPaths = projectData.drawingPaths.map { path ->
                path.map { pair -> Offset(pair.first, pair.second) }
            }

            // Map back to UiState
            UiState(
                backgroundImageUri = projectData.backgroundImageUri,
                overlayImageUri = projectData.overlayImageUri,
                capturedTargetImages = targetBitmaps,
                capturedTargetUris = projectData.targetImageUris,
                refinementPaths = projectData.refinementPaths,
                drawingPaths = drawingPaths,
                progressPercentage = projectData.progressPercentage,
                fingerprintJson = fingerprintJson,
                layers = projectData.layers,
                calibrationSnapshots = projectData.calibrationSnapshots,

                // Active layer logic
                activeLayerId = if (projectData.layers.isNotEmpty()) projectData.layers.first().id else null
            ).let { state ->
                if (state.layers.isEmpty() && projectData.overlayImageUri != null) {
                    // Create migration layer from legacy fields
                    val legacyLayer = OverlayLayer(
                        uri = projectData.overlayImageUri,
                        name = "Base Layer",
                        opacity = projectData.opacity,
                        brightness = projectData.brightness,
                        contrast = projectData.contrast,
                        saturation = projectData.saturation,
                        colorBalanceR = projectData.colorBalanceR,
                        colorBalanceG = projectData.colorBalanceG,
                        colorBalanceB = projectData.colorBalanceB,
                        scale = projectData.scale,
                        rotationX = projectData.rotationX,
                        rotationY = projectData.rotationY,
                        rotationZ = projectData.rotationZ,
                        offset = projectData.offset,
                        blendMode = projectData.blendMode
                    )
                    state.copy(layers = listOf(legacyLayer), activeLayerId = legacyLayer.id)
                } else {
                    state
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project", e)
            null
        }
    }

    suspend fun loadProjectMetadata(context: Context, projectId: String): ProjectData? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            json.decodeFromString<ProjectData>(jsonString)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            null
        }
    }

    fun exportProjectToUri(context: Context, projectId: String, uri: Uri) {
        val sourceFolder = File(context.filesDir, "projects/$projectId")
        if (!sourceFolder.exists()) return

        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    zipFolder(sourceFolder, sourceFolder.name, zos)
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectManager", "Export failed", e)
        }
    }

    private fun zipFolder(folder: File, parentFolder: String, zos: ZipOutputStream) {
        for (file in folder.listFiles() ?: emptyArray()) {
            if (file.isDirectory) {
                zipFolder(file, "$parentFolder/${file.name}", zos)
            } else {
                val entry = ZipEntry("$parentFolder/${file.name}")
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}

package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import com.hereliesaz.graffitixr.common.util.ImageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

interface UriProvider {
    fun getUriForFile(file: File): Uri
}

class DefaultUriProvider @Inject constructor() : UriProvider {
    override fun getUriForFile(file: File): Uri {
        return Uri.fromFile(file)
    }
}

@Singleton
class ProjectManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val uriProvider: UriProvider
) {

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

    fun getMapPath(context: Context, projectId: String): String {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return File(root, "map.bin").absolutePath
    }

    fun getCloudPointsPath(context: Context, projectId: String): String {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return File(root, "cloud_points.bin").absolutePath
    }

    suspend fun saveProject(context: Context, projectData: GraffitiProject, targetImages: List<Bitmap>? = null, thumbnail: Bitmap? = null) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/${projectData.id}")
        if (!root.exists()) root.mkdirs()

        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            FileOutputStream(file).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            uriProvider.getUriForFile(file)
        } else {
            projectData.thumbnailUri ?: run {
                val file = File(root, "thumbnail.png")
                if (file.exists()) uriProvider.getUriForFile(file) else null
            }
        }

        // Properly append new targets to the existing list
        val savedTargetUris = if (targetImages != null) {
            val existingCount = projectData.targetImageUris.size
            val newUris = targetImages.mapIndexed { index, bitmap ->
                val file = File(root, "target_${existingCount + index}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                uriProvider.getUriForFile(file)
            }
            projectData.targetImageUris + newUris
        } else {
            projectData.targetImageUris
        }

        val updatedGraffitiProject = projectData.copy(
            thumbnailUri = thumbnailUri,
            targetImageUris = savedTargetUris,
            lastModified = System.currentTimeMillis()
        )

        val jsonString = json.encodeToString(updatedGraffitiProject)
        File(root, "project.json").writeText(jsonString)
    }

    suspend fun loadProject(context: Context, projectId: String): LoadedProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val projectData = migrateIfNeeded(context, json.decodeFromString<GraffitiProject>(jsonString))

            val targetBitmaps = projectData.targetImageUris.mapNotNull { uri ->
                ImageUtils.loadBitmapSync(context, uri)
            }

            LoadedProject(projectData, targetBitmaps)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project", e)
            null
        }
    }

    suspend fun loadProjectMetadata(context: Context, projectId: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val project = json.decodeFromString<GraffitiProject>(jsonString)
            migrateIfNeeded(context, project)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            null
        }
    }

    private suspend fun migrateIfNeeded(context: Context, project: GraffitiProject): GraffitiProject {
        val lv = project.legacyVisuals
        val defaults = LegacyVisuals()
        if (lv == defaults) return project

        val migratedLayers: List<OverlayLayer> = when {
            project.layers.isEmpty() && project.overlayImageUri != null -> {
                val uri = project.overlayImageUri!!
                listOf(
                    OverlayLayer(
                        uri = uri,
                        name = "Overlay",
                        scale = lv.scale,
                        offset = lv.offset,
                        rotationX = lv.rotationX,
                        rotationY = lv.rotationY,
                        rotationZ = lv.rotationZ,
                        opacity = lv.opacity,
                        blendMode = lv.blendMode.toModelBlendMode(),
                        brightness = lv.brightness,
                        contrast = lv.contrast,
                        saturation = lv.saturation,
                        colorBalanceR = lv.colorBalanceR,
                        colorBalanceG = lv.colorBalanceG,
                        colorBalanceB = lv.colorBalanceB
                    )
                )
            }
            project.layers.isNotEmpty() && project.layers.first().hasDefaultVisuals() -> {
                val first = project.layers.first().copy(
                    scale = lv.scale,
                    offset = lv.offset,
                    rotationX = lv.rotationX,
                    rotationY = lv.rotationY,
                    rotationZ = lv.rotationZ,
                    opacity = lv.opacity,
                    blendMode = lv.blendMode.toModelBlendMode(),
                    brightness = lv.brightness,
                    contrast = lv.contrast,
                    saturation = lv.saturation,
                    colorBalanceR = lv.colorBalanceR,
                    colorBalanceG = lv.colorBalanceG,
                    colorBalanceB = lv.colorBalanceB
                )
                listOf(first) + project.layers.drop(1)
            }
            else -> project.layers
        }

        val migrated = project.copy(layers = migratedLayers, legacyVisuals = defaults)
        saveProject(context, migrated)
        Log.i("ProjectManager", "Migrated legacyVisuals for project ${project.id}")
        return migrated
    }

    private fun OverlayLayer.hasDefaultVisuals(): Boolean {
        return scale == 1f && offset == Offset.Zero && rotationX == 0f && rotationY == 0f && rotationZ == 0f &&
                opacity == 1f && blendMode == ModelBlendMode.SrcOver && brightness == 0f && contrast == 1f &&
                saturation == 1f && colorBalanceR == 1f && colorBalanceG == 1f && colorBalanceB == 1f
    }

    fun exportProjectToUri(context: Context, projectId: String, uri: Uri) {
        val sourceFolder = File(context.filesDir, "projects/$projectId")
        if (!sourceFolder.exists()) return

        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    // Use empty string for parent to zip contents directly into the root.
                    zipFolder(sourceFolder, "", zos)
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectManager", "Export failed", e)
        }
    }

    suspend fun importProjectFromUri(context: Context, uri: Uri): GraffitiProject? = withContext(Dispatchers.IO) {
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var projectData: GraffitiProject? = null
                    val extractedFiles = mutableMapOf<String, File>()

                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val relativeName = if (name.contains('/')) name.substringAfter('/') else name

                        if (!entry.isDirectory && relativeName.isNotEmpty()) {
                            val bytes = zis.readBytes()
                            if (relativeName == "project.json") {
                                try {
                                    projectData = json.decodeFromString<GraffitiProject>(bytes.decodeToString())
                                } catch (e: Exception) {
                                    Log.e("ProjectManager", "Failed to parse project.json", e)
                                }
                            }
                            extractedFiles[relativeName] = File.createTempFile("gxr_", null, context.cacheDir).also {
                                it.writeBytes(bytes)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    val project = projectData ?: return@use null
                    val destDir = File(context.filesDir, "projects/${project.id}").also { it.mkdirs() }

                    for ((name, tmpFile) in extractedFiles) {
                        val dest = File(destDir, name)
                        dest.parentFile?.mkdirs()
                        tmpFile.renameTo(dest)
                    }

                    project
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectManager", "Import failed", e)
            null
        }
    }

    private fun ComposeBlendMode.toModelBlendMode(): ModelBlendMode = when (this) {
        ComposeBlendMode.Multiply   -> ModelBlendMode.Multiply
        ComposeBlendMode.Screen     -> ModelBlendMode.Screen
        ComposeBlendMode.Overlay    -> ModelBlendMode.Overlay
        ComposeBlendMode.Darken     -> ModelBlendMode.Darken
        ComposeBlendMode.Lighten    -> ModelBlendMode.Lighten
        ComposeBlendMode.ColorDodge -> ModelBlendMode.ColorDodge
        ComposeBlendMode.ColorBurn  -> ModelBlendMode.ColorBurn
        ComposeBlendMode.Hardlight  -> ModelBlendMode.HardLight
        ComposeBlendMode.Softlight  -> ModelBlendMode.SoftLight
        ComposeBlendMode.Difference -> ModelBlendMode.Difference
        ComposeBlendMode.Exclusion  -> ModelBlendMode.Exclusion
        ComposeBlendMode.Hue        -> ModelBlendMode.Hue
        ComposeBlendMode.Saturation -> ModelBlendMode.Saturation
        ComposeBlendMode.Color      -> ModelBlendMode.Color
        ComposeBlendMode.Luminosity -> ModelBlendMode.Luminosity
        ComposeBlendMode.Clear      -> ModelBlendMode.Clear
        ComposeBlendMode.Src        -> ModelBlendMode.Src
        ComposeBlendMode.Dst        -> ModelBlendMode.Dst
        ComposeBlendMode.DstOver    -> ModelBlendMode.DstOver
        ComposeBlendMode.SrcIn      -> ModelBlendMode.SrcIn
        ComposeBlendMode.DstIn      -> ModelBlendMode.DstIn
        ComposeBlendMode.SrcOut     -> ModelBlendMode.SrcOut
        ComposeBlendMode.DstOut     -> ModelBlendMode.DstOut
        ComposeBlendMode.SrcAtop    -> ModelBlendMode.SrcAtop
        ComposeBlendMode.DstAtop    -> ModelBlendMode.DstAtop
        ComposeBlendMode.Xor        -> ModelBlendMode.Xor
        ComposeBlendMode.Plus       -> ModelBlendMode.Plus
        ComposeBlendMode.Modulate   -> ModelBlendMode.Modulate
        else                        -> ModelBlendMode.SrcOver
    }

    private fun zipFolder(folder: File, parentFolder: String, zos: ZipOutputStream) {
        for (file in folder.listFiles() ?: emptyArray()) {
            // Use relative path from the source folder to avoid nested parent directories in the ZIP.
            val zipPath = if (parentFolder.isEmpty()) file.name else "$parentFolder/${file.name}"
            if (file.isDirectory) {
                zipFolder(file, zipPath, zos)
            } else {
                val entry = ZipEntry(zipPath)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}
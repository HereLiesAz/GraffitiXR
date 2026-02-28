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

/**
 * Interface for providing URIs for files. Abstraction allows for easier testing.
 */
interface UriProvider {
    fun getUriForFile(file: File): Uri
}

/**
 * Default implementation using [Uri.fromFile].
 */
class DefaultUriProvider @Inject constructor() : UriProvider {
    override fun getUriForFile(file: File): Uri {
        return Uri.fromFile(file)
    }
}

/**
 * Manages the low-level file system operations for GraffitiXR projects.
 * Handles saving/loading JSON metadata and image assets (thumbnails, targets).
 *
 * Project Structure:
 * - /files/projects/{projectId}/
 *   - project.json (Metadata)
 *   - thumbnail.png (Preview)
 *   - target_0.png (Target images)
 *   - map.bin (SLAM map)
 */
@Singleton
class ProjectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uriProvider: UriProvider
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Returns a list of project IDs (directory names) found in storage.
     */
    fun getProjectList(context: Context): List<String> {
        val projectsDir = File(context.filesDir, "projects")
        if (!projectsDir.exists()) return emptyList()
        return projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    /**
     * Deletes a project directory and all its contents.
     */
    fun deleteProject(context: Context, projectName: String) {
        val projectDir = File(context.filesDir, "projects/$projectName")
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    /**
     * Returns the absolute path to the map.bin file for a given project.
     * Ensures the project directory exists.
     */
    fun getMapPath(context: Context, projectId: String): String {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return File(root, "map.bin").absolutePath
    }

    /**
     * Saves project metadata and optional assets to disk.
     *
     * @param context Application context.
     * @param projectData The project metadata to serialize.
     * @param targetImages Optional list of bitmaps to save as target images.
     * @param thumbnail Optional bitmap to save as the project thumbnail.
     */
    suspend fun saveProject(context: Context, projectData: GraffitiProject, targetImages: List<Bitmap>? = null, thumbnail: Bitmap? = null) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/${projectData.id}")
        if (!root.exists()) root.mkdirs()

        // 1. Save Thumbnail (if provided)
        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            FileOutputStream(file).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            uriProvider.getUriForFile(file)
        } else {
            // Keep existing or check file
             projectData.thumbnailUri ?: run {
                val file = File(root, "thumbnail.png")
                if (file.exists()) uriProvider.getUriForFile(file) else null
            }
        }

        // 2. Save Target Images (Bitmaps) -> URIs
        val savedTargetUris = if (targetImages != null) {
            targetImages.mapIndexed { index, bitmap ->
                val file = File(root, "target_$index.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                uriProvider.getUriForFile(file)
            }
        } else {
            projectData.targetImageUris
        }

        val updatedGraffitiProject = projectData.copy(
            thumbnailUri = thumbnailUri,
            targetImageUris = savedTargetUris,
            lastModified = System.currentTimeMillis()
        )

        // 3. Save GraffitiProject to JSON
        val jsonString = json.encodeToString(updatedGraffitiProject)
        File(root, "project.json").writeText(jsonString)
    }

    /**
     * Loads a full project including bitmaps into memory.
     */
    suspend fun loadProject(context: Context, projectId: String): LoadedProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val projectData = migrateIfNeeded(context, json.decodeFromString<GraffitiProject>(jsonString))

            // Load Target Bitmaps
            val targetBitmaps = projectData.targetImageUris.mapNotNull { uri ->
                ImageUtils.getBitmapFromUri(context, uri)
            }

            LoadedProject(projectData, targetBitmaps)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project", e)
            null
        }
    }

    /**
     * Loads only the metadata for a project (faster than full load).
     */
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

    /**
     * Migrates [GraffitiProject.legacyVisuals] into the first [OverlayLayer] (or creates one from
     * [GraffitiProject.overlayImageUri]) when the legacy field carries non-default values.
     * Saves the updated project back to disk so migration only runs once.
     */
    private suspend fun migrateIfNeeded(context: Context, project: GraffitiProject): GraffitiProject {
        val lv = project.legacyVisuals
        val defaults = LegacyVisuals()
        if (lv == defaults) return project // Nothing to migrate

        val migratedLayers: List<OverlayLayer> = when {
            // Case 1: no layers yet — promote overlayImageUri + legacyVisuals into a new layer
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
            // Case 2: layers exist but first one still has all-default visual values — apply legacyVisuals to it
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
        return scale == 1f &&
            offset == Offset.Zero &&
            rotationX == 0f &&
            rotationY == 0f &&
            rotationZ == 0f &&
            opacity == 1f &&
            blendMode == ModelBlendMode.SrcOver &&
            brightness == 0f &&
            contrast == 1f &&
            saturation == 1f &&
            colorBalanceR == 1f &&
            colorBalanceG == 1f &&
            colorBalanceB == 1f
    }

    /**
     * Zips the project folder into a single file at the specified URI.
     */
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

    /**
     * Imports a project from a .gxr zip file at the given URI.
     * Extracts into /files/projects/{projectId}/ and returns the loaded metadata.
     * Returns null if the zip is invalid or missing project.json.
     */
    suspend fun importProjectFromUri(context: Context, uri: Uri): GraffitiProject? = withContext(Dispatchers.IO) {
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    // First pass: find project.json to get the project ID
                    var projectData: GraffitiProject? = null
                    val extractedFiles = mutableMapOf<String, File>()

                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        // Strip top-level folder prefix if present (e.g. "projectId/file" -> "file")
                        val relativeName = if (name.contains('/')) name.substringAfter('/') else name

                        if (!entry.isDirectory && relativeName.isNotEmpty()) {
                            // Buffer entry data (can't seek in ZipInputStream)
                            val bytes = zis.readBytes()
                            if (relativeName == "project.json") {
                                try {
                                    projectData = json.decodeFromString<GraffitiProject>(bytes.decodeToString())
                                } catch (e: Exception) {
                                    Log.e("ProjectManager", "Failed to parse project.json", e)
                                }
                            }
                            // Store all files for extraction after we know the project ID
                            extractedFiles[relativeName] = File.createTempFile("gxr_", null, context.cacheDir).also {
                                it.writeBytes(bytes)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    val project = projectData ?: return@use null
                    val destDir = File(context.filesDir, "projects/${project.id}").also { it.mkdirs() }

                    // Move extracted files into the project directory
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

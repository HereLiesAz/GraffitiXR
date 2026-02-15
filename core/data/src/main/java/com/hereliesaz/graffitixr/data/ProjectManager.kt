package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.*
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
            val projectData = json.decodeFromString<GraffitiProject>(jsonString)

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
            json.decodeFromString<GraffitiProject>(jsonString)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            null
        }
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

package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
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

class ProjectManager(
    private val context: Context,
    private val repository: ProjectRepository,
    private val uriProvider: UriProvider = DefaultUriProvider()
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

    // FIX: Added missing method required by MainViewModel.finalizeMap()
    fun getMapPath(context: Context, projectId: String): String {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return File(root, "map.bin").absolutePath
    }

    suspend fun saveProject(context: Context, projectData: ProjectData, targetImages: List<Bitmap>, thumbnail: Bitmap? = null) = withContext(Dispatchers.IO) {
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
            val file = File(root, "thumbnail.png")
            if (file.exists()) uriProvider.getUriForFile(file) else null
        }

        // 2. Save Target Images (Bitmaps) -> URIs
        val savedTargetUris = targetImages.mapIndexed { index, bitmap ->
            val file = File(root, "target_$index.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            uriProvider.getUriForFile(file)
        }

        val updatedProjectData = projectData.copy(
            thumbnailUri = thumbnailUri,
            targetImageUris = savedTargetUris,
            lastModified = System.currentTimeMillis()
        )

        // 3. Save ProjectData to JSON
        val jsonString = json.encodeToString(updatedProjectData)
        File(root, "project.json").writeText(jsonString)
    }

    suspend fun loadProject(context: Context, projectId: String): LoadedProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val projectData = json.decodeFromString<ProjectData>(jsonString)

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

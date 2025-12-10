package com.hereliesaz.graffitixr.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.hereliesaz.graffitixr.data.ProjectData
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

class ProjectManager(private val context: Context) {
    private val projectsDir = File(context.filesDir, "projects")
    private val targetsDir = File(context.filesDir, "targets")

    init {
        if (!projectsDir.exists()) projectsDir.mkdirs()
        if (!targetsDir.exists()) targetsDir.mkdirs()
    }

    fun saveProject(projectName: String, projectData: ProjectData) {
        val file = File(projectsDir, "$projectName.json")
        val jsonString = Json.encodeToString(ProjectData.serializer(), projectData)
        file.writeText(jsonString)
    }

    fun loadProject(projectName: String): ProjectData? {
        val file = File(projectsDir, "$projectName.json")
        return if (file.exists()) {
            val jsonString = file.readText()
            Json.decodeFromString(ProjectData.serializer(), jsonString)
        } else {
            null
        }
    }

    /**
     * Saves a list of bitmaps to internal storage and returns their URIs.
     * This ensures the robust target data persists across app restarts.
     */
    fun saveTargetImages(projectName: String, bitmaps: List<Bitmap>): List<Uri> {
        val projectTargetDir = File(targetsDir, projectName)
        if (!projectTargetDir.exists()) projectTargetDir.mkdirs()

        // Clean old targets for this project
        projectTargetDir.listFiles()?.forEach { it.delete() }

        return bitmaps.mapIndexed { index, bitmap ->
            val file = File(projectTargetDir, "target_$index.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // Return internal file URI
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
    }

    /**
     * Loads bitmaps from a list of URIs.
     */
    fun loadTargetBitmaps(uris: List<Uri>): List<Bitmap> {
        return uris.mapNotNull { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    fun getProjectList(): List<String> {
        return projectsDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    fun deleteProject(projectName: String) {
        val file = File(projectsDir, "$projectName.json")
        if (file.exists()) {
            file.delete()
        }
        val projectTargetDir = File(targetsDir, projectName)
        if (projectTargetDir.exists()) {
            projectTargetDir.deleteRecursively()
        }
    }
}
package com.hereliesaz.graffitixr.utils

import android.content.Context
import com.hereliesaz.graffitixr.data.ProjectData
import kotlinx.serialization.json.Json
import java.io.File

class ProjectManager(private val context: Context) {
    private val projectsDir = File(context.filesDir, "projects")

    init {
        if (!projectsDir.exists()) {
            projectsDir.mkdirs()
        }
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

    fun getProjectList(): List<String> {
        return projectsDir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    fun deleteProject(projectName: String) {
        val file = File(projectsDir, "$projectName.json")
        if (file.exists()) {
            file.delete()
        }
    }
}

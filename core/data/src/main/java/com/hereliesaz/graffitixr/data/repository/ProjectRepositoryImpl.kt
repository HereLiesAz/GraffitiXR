package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.Project
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject

class ProjectRepositoryImpl @Inject constructor(
    private val context: Context,
    // Using IO dispatcher for file operations
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<Project?>(null)
    override val currentProject: StateFlow<Project?> = _currentProject.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    override suspend fun loadProject(projectId: String): Result<Project> = withContext(ioDispatcher) {
        try {
            val file = File(getProjectDir(), "$projectId.json")
            if (!file.exists()) {
                return@withContext Result.failure(Exception("Project not found"))
            }
            val content = file.readText()
            val project = json.decodeFromString<Project>(content)
            _currentProject.value = project
            Result.success(project)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createProject(name: String): Project = withContext(ioDispatcher) {
        val newProject = Project(
            id = UUID.randomUUID().toString(),
            name = name
        )
        _currentProject.value = newProject
        saveProjectInternal(newProject)
        newProject
    }

    override suspend fun updateProject(transform: (Project) -> Project) {
        _currentProject.update { current ->
            if (current == null) return@update null
            val updated = transform(current).copy(lastModified = System.currentTimeMillis())
            // Fire and forget save to avoid blocking UI
            // In a real prod app, use a queue or worker
            CoroutineScope(ioDispatcher).launch {
                saveProjectInternal(updated)
            }
            updated
        }
    }

    override suspend fun saveProject(): Result<Unit> = withContext(ioDispatcher) {
        val current = _currentProject.value ?: return@withContext Result.failure(Exception("No active project"))
        saveProjectInternal(current)
    }

    override suspend fun closeProject() {
        _currentProject.value = null
    }

    private fun saveProjectInternal(project: Project): Result<Unit> {
        return try {
            val file = File(getProjectDir(), "${project.id}.json")
            val content = json.encodeToString(Project.serializer(), project)
            file.writeText(content)
            Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun getProjectDir(): File {
        val dir = File(context.filesDir, "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
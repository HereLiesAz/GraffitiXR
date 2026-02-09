package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.ProjectData
import com.hereliesaz.graffitixr.data.ProjectManager
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
import javax.inject.Inject

class ProjectRepositoryImpl @Inject constructor(
    private val context: Context,
    private val projectManager: ProjectManager,
    // Using IO dispatcher for file operations
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<ProjectData?>(null)
    override val currentProject: StateFlow<ProjectData?> = _currentProject.asStateFlow()

    override suspend fun loadProject(projectId: String): Result<ProjectData> = withContext(ioDispatcher) {
        try {
            val loadedProject = projectManager.loadProject(context, projectId)
            if (loadedProject != null) {
                _currentProject.value = loadedProject.projectData
                Result.success(loadedProject.projectData)
            } else {
                Result.failure(Exception("Project not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createProject(name: String): ProjectData = withContext(ioDispatcher) {
        // Force Recompile
        val newProject = ProjectData(name = name)
        _currentProject.value = newProject
        projectManager.saveProject(context, newProject, emptyList())
        newProject
    }

    override suspend fun updateProject(transform: (ProjectData) -> ProjectData) {
        _currentProject.update { current ->
            if (current == null) return@update null
            val updated = transform(current).copy(lastModified = System.currentTimeMillis())
            // Fire and forget save to avoid blocking UI
            // In a real prod app, use a queue or worker
            CoroutineScope(ioDispatcher).launch {
                projectManager.saveProject(context, updated, null)
            }
            updated
        }
    }

    override suspend fun getProjects(): List<ProjectData> = withContext(ioDispatcher) {
        projectManager.getProjectList(context).mapNotNull { id ->
            projectManager.loadProjectMetadata(context, id)
        }
    }

    override suspend fun saveProject(): Result<Unit> = withContext(ioDispatcher) {
        val current = _currentProject.value ?: return@withContext Result.failure(Exception("No active project"))
        try {
            projectManager.saveProject(context, current, null)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun closeProject() {
        _currentProject.value = null
    }
}
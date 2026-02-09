package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.dispatcher.IoDispatcher
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.Project
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class ProjectRepositoryImpl @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val projectManager: ProjectManager,
    @IoDispatcher private val io: CoroutineDispatcher
) : ProjectRepository {

    private val projects = mutableListOf<Project>()
    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    override suspend fun loadProject(projectId: String): Result<GraffitiProject> {
        // Implementation placeholder
        val project = GraffitiProject(id = projectId)
        _currentProject.value = project
        return Result.success(project)
    }

    override suspend fun getProjects(): Flow<List<Project>> = flowOf(projects)

    override suspend fun saveProject(): Result<Unit> {
        // Implementation placeholder
        return Result.success(Unit)
    }

    override suspend fun closeProject() {
        _currentProject.value = null
    }

    suspend fun getProjectById(id: String): Project? {
        return projects.find { it.id == id }
    }

    override suspend fun createProject(name: String): GraffitiProject {
        val project = GraffitiProject(name = name)
        _currentProject.value = project
        return project
    }

    suspend fun deleteProject(id: String) {
        projects.removeAll { it.id == id }
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        _currentProject.value?.let {
            _currentProject.value = transform(it)
        }
    }
}

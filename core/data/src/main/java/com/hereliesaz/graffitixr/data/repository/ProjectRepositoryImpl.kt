package com.hereliesaz.graffitixr.core.data.repository

import com.hereliesaz.graffitixr.core.model.Project
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

// Placeholder implementation to get you compiling.
// Replace with Room/DataStore logic later.
class ProjectRepositoryImpl @Inject constructor() : ProjectRepository {

    private val projects = mutableListOf<Project>()

    override fun getProjects(): Flow<List<Project>> = flowOf(projects)

    override suspend fun getProjectById(id: String): Project? {
        return projects.find { it.id == id }
    }

    override suspend fun createProject(project: Project) {
        projects.add(project)
    }

    override suspend fun deleteProject(id: String) {
        projects.removeAll { it.id == id }
    }

    override suspend fun updateProject(project: Project) {
        val index = projects.indexOfFirst { it.id == project.id }
        if (index != -1) {
            projects[index] = project
        }
    }
}
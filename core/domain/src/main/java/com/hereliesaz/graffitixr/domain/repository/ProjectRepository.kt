package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.ProjectData
import kotlinx.coroutines.flow.StateFlow

interface ProjectRepository {
    val currentProject: StateFlow<ProjectData?>

    suspend fun loadProject(projectId: String): Boolean
    suspend fun saveProject()
    suspend fun createNewProject(): String
    
    // Mutation methods
    suspend fun updateProject(transform: (ProjectData) -> ProjectData)
    
    // Accessors
    suspend fun getProjectList(): List<String>
    fun getMapPath(projectId: String): String
}

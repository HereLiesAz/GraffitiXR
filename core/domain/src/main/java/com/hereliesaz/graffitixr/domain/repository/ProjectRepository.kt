package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ProjectRepository {
    val currentProject: StateFlow<GraffitiProject?>
    val projects: Flow<List<GraffitiProject>>

    suspend fun createProject(name: String): GraffitiProject
    suspend fun createProject(project: GraffitiProject)
    suspend fun getProject(id: String): GraffitiProject?
    suspend fun getProjects(): List<GraffitiProject>
    suspend fun loadProject(id: String): Result<Unit>
    suspend fun updateProject(project: GraffitiProject)
    suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject)
    suspend fun deleteProject(id: String)
    suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String
    suspend fun updateTargetFingerprint(projectId: String, path: String)
    suspend fun updateMapPath(projectId: String, path: String)
}
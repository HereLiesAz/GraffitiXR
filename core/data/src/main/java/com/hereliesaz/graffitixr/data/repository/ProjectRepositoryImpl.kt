package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectManager: ProjectManager
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    override val projects: Flow<List<GraffitiProject>> = flow {
        emit(getProjects())
    }

    override suspend fun createProject(name: String): GraffitiProject {
        val newProject = GraffitiProject(name = name)
        projectManager.saveProject(context, newProject)
        _currentProject.value = newProject
        return newProject
    }

    override suspend fun createProject(project: GraffitiProject) {
        projectManager.saveProject(context, project)
        _currentProject.value = project
    }

    override suspend fun getProject(id: String): GraffitiProject? {
        return projectManager.loadProjectMetadata(context, id)
    }

    override suspend fun getProjects(): List<GraffitiProject> {
        val projectIds = projectManager.getProjectList(context)
        return projectIds.mapNotNull { id ->
            projectManager.loadProjectMetadata(context, id)
        }
    }

    override suspend fun loadProject(id: String): Result<Unit> {
        val project = getProject(id)
        return if (project != null) {
            _currentProject.value = project
            Result.success(Unit)
        } else {
            Result.failure(Exception("Project not found"))
        }
    }

    override suspend fun updateProject(project: GraffitiProject) {
        projectManager.saveProject(context, project)
        if (_currentProject.value?.id == project.id) {
            _currentProject.value = project
        }
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        val current = _currentProject.value ?: return
        val updated = transform(current)
        updateProject(updated)
    }

    override suspend fun deleteProject(id: String) {
        projectManager.deleteProject(context, id)
        if (_currentProject.value?.id == id) {
            _currentProject.value = null
        }
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        val file = File(root, filename)
        file.writeBytes(data)
        file.absolutePath
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        val project = getProject(projectId) ?: return
        updateProject(project.copy(targetFingerprintPath = path))
    }

    override suspend fun updateMapPath(projectId: String, path: String) {
        val project = getProject(projectId) ?: return
        updateProject(project.copy(mapPath = path))
    }

    override suspend fun importProject(uri: android.net.Uri): Result<GraffitiProject> {
        val project = projectManager.importProjectFromUri(context, uri)
            ?: return Result.failure(Exception("Failed to import project from $uri"))
        _currentProject.value = project
        return Result.success(project)
    }
}

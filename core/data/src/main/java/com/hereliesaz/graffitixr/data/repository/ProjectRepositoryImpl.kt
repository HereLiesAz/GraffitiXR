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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projectManager: ProjectManager
) : ProjectRepository {

    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    // Backing state for the project list so observers see creates/deletes/imports,
    // as the ProjectRepository contract promises. A plain cold flow emitted once.
    private val _projects = MutableStateFlow<List<GraffitiProject>>(emptyList())
    override val projects: Flow<List<GraffitiProject>> = _projects.onStart { refreshProjects() }

    private suspend fun refreshProjects() {
        _projects.value = getProjects()
    }

    override suspend fun createProject(name: String): GraffitiProject {
        val newProject = GraffitiProject(name = name)
        projectManager.saveProject(context, newProject)
        _currentProject.value = newProject
        refreshProjects()
        return newProject
    }

    override suspend fun createProject(project: GraffitiProject) {
        projectManager.saveProject(context, project)
        _currentProject.value = project
        refreshProjects()
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
        refreshProjects()
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        // Apply the transform atomically against the live state so two concurrent
        // callers can't both read the same base and clobber each other's mutation.
        val updated = _currentProject.updateAndGet { current -> current?.let(transform) } ?: return
        projectManager.saveProject(context, updated)
        refreshProjects()
    }

    override suspend fun deleteProject(id: String) {
        projectManager.deleteProject(context, id)
        if (_currentProject.value?.id == id) {
            _currentProject.value = null
        }
        refreshProjects()
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        val file = File(root, filename)
        // Atomic write: a half-written map.bin / fingerprint can crash native loaders.
        val tmp = File(root, "$filename.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) {
                file.writeBytes(data)
                tmp.delete()
            }
        }
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
        refreshProjects()
        return Result.success(project)
    }
}

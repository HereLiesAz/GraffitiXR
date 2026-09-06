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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    // Serializes project writes, switches and deletion; state is published after persistence.
    private val saveMutex = Mutex()

    // Backing state for the project list so observers see creates/deletes/imports,
    // as the ProjectRepository contract promises. A plain cold flow emitted once.
    private val _projects = MutableStateFlow<List<GraffitiProject>>(emptyList())
    override val projects: Flow<List<GraffitiProject>> = _projects.onStart { refreshProjects() }

    private suspend fun refreshProjects() {
        _projects.value = getProjects()
    }

    override suspend fun createProject(name: String): GraffitiProject {
        val project = GraffitiProject(name = name)
        createProject(project)
        return project
    }

    override suspend fun createProject(project: GraffitiProject) = saveMutex.withLock {
        projectManager.saveProject(context, project)
        _currentProject.value = project
        refreshProjects()
    }

    override suspend fun getProject(id: String): GraffitiProject? = withContext(Dispatchers.IO) {
        projectManager.loadProjectMetadata(context, id)
    }

    override suspend fun getProjects(): List<GraffitiProject> = withContext(Dispatchers.IO) {
        projectManager.getProjectList(context).mapNotNull { id ->
            projectManager.loadProjectMetadata(context, id)
        }
    }

    override suspend fun loadProject(id: String): Result<Unit> = saveMutex.withLock {
        val project = getProject(id)
        if (project != null) {
            _currentProject.value = project
            Result.success(Unit)
        } else {
            Result.failure(Exception("Project not found"))
        }
    }

    override suspend fun updateProject(project: GraffitiProject) = saveMutex.withLock {
        projectManager.saveProject(context, project)
        if (_currentProject.value?.id == project.id) _currentProject.value = project
        refreshProjects()
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) = saveMutex.withLock {
        val current = _currentProject.value ?: return@withLock
        val updated = transform(current)
        // Publish only after the write succeeds. Switching/deleting uses the same lock, so a
        // queued write cannot save a different project or resurrect a deleted one.
        projectManager.saveProject(context, updated)
        _currentProject.value = updated
        refreshProjects()
    }

    override suspend fun deleteProject(id: String) = saveMutex.withLock {
        withContext(Dispatchers.IO) { projectManager.deleteProject(context, id) }
        if (_currentProject.value?.id == id) _currentProject.value = null
        refreshProjects()
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        val file = File(root, filename)
        // Atomic write: a half-written map.bin / fingerprint can crash native loaders.
        val tmp = File.createTempFile("${file.name}.", ".tmp", root)
        try {
            tmp.writeBytes(data)
            check(tmp.renameTo(file)) { "Could not replace $filename" }
        } finally {
            tmp.delete()
        }
        file.absolutePath
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        val project = getProject(projectId) ?: return
        updateProject(project.copy(targetFingerprintPath = path))
    }

    override suspend fun importProject(uri: android.net.Uri): Result<GraffitiProject> = saveMutex.withLock {
        val project = projectManager.importProjectFromUri(context, uri)
            ?: return@withLock Result.failure(Exception("Failed to import project from $uri"))
        _currentProject.value = project
        refreshProjects()
        Result.success(project)
    }
}

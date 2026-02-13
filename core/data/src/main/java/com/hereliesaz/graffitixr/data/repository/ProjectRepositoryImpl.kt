package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [ProjectRepository] using local file storage.
 * Projects are stored as JSON files in the app's internal storage directory.
 *
 * @param context The application context, used to access the file system.
 */
@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ProjectRepository {

    // JSON configuration for serialization
    private val serializer = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // Directory where project JSON files are stored
    private val projectsDir: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    /**
     * Polling flow that emits the list of projects every 2 seconds.
     * TODO: Replace with FileObserver or reactive database (Room) for better performance.
     */
    override val projects: Flow<List<GraffitiProject>> = flow {
        while(true) {
            val list = internalGetProjectList()
            emit(list)
            kotlinx.coroutines.delay(2000)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Helper to read and parse all project files from the directory.
     */
    private suspend fun internalGetProjectList(): List<GraffitiProject> = withContext(Dispatchers.IO) {
        projectsDir.listFiles { _, name -> name.endsWith(".json") }
            ?.mapNotNull { file ->
                try {
                    val json = file.readText()
                    serializer.decodeFromString<GraffitiProject>(json)
                } catch(e: Exception) {
                    e.printStackTrace()
                    null
                }
            } ?: emptyList()
    }

    override suspend fun getProjects(): List<GraffitiProject> = internalGetProjectList()

    override suspend fun createProject(name: String): GraffitiProject = withContext(Dispatchers.IO) {
        val newProject = GraffitiProject(name = name)
        saveProjectToDisk(newProject)
        _currentProject.value = newProject
        newProject
    }

    override suspend fun createProject(project: GraffitiProject) = withContext(Dispatchers.IO) {
        saveProjectToDisk(project)
        _currentProject.value = project
    }

    override suspend fun getProject(id: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "$id.json")
        if (file.exists()) {
            try {
                serializer.decodeFromString<GraffitiProject>(file.readText())
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } else {
            null
        }
    }

    override suspend fun loadProject(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val p = getProject(id)
        if (p != null) {
            _currentProject.value = p
            Result.success(Unit)
        } else {
            Result.failure(Exception("Project with ID $id not found"))
        }
    }

    override suspend fun updateProject(project: GraffitiProject) = withContext(Dispatchers.IO) {
        saveProjectToDisk(project)
        // Only update currentProject flow if it matches the ID being updated
        if (_currentProject.value?.id == project.id) {
            _currentProject.value = project
        }
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        _currentProject.value?.let { current ->
            val updated = transform(current)
            updateProject(updated)
        }
    }

    override suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "$id.json")
        if (file.exists()) {
            if (!file.delete()) {
                throw java.io.IOException("Failed to delete project file: ${file.absolutePath}")
            }
        }

        // If we deleted the current project, clear the selection
        if (_currentProject.value?.id == id) {
            _currentProject.value = null
        }
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        // Create a subdirectory for the project's artifacts
        val projectArtifactsDir = File(projectsDir, projectId).apply { mkdirs() }
        val file = File(projectArtifactsDir, filename)
        file.writeBytes(data)
        file.absolutePath
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        getProject(projectId)?.let { project ->
            val updated = project.copy(targetFingerprintPath = path)
            updateProject(updated)
        }
    }

    override suspend fun updateMapPath(projectId: String, path: String) {
        getProject(projectId)?.let { project ->
            val updated = project.copy(mapPath = path)
            updateProject(updated)
        }
    }

    private fun saveProjectToDisk(p: GraffitiProject) {
        try {
            val json = serializer.encodeToString(p)
            File(projectsDir, "${p.id}.json").writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

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

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : ProjectRepository {

    private val serializer = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val projectsDir: File by lazy {
        File(context.filesDir, "projects").apply { mkdirs() }
    }

    private val _currentProject = MutableStateFlow<GraffitiProject?>(null)
    override val currentProject: StateFlow<GraffitiProject?> = _currentProject.asStateFlow()

    override val projects: Flow<List<GraffitiProject>> = flow {
        while(true) {
            emit(internalGetProjectList())
            kotlinx.coroutines.delay(2000)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getProjects(): List<GraffitiProject> = withContext(Dispatchers.IO) {
        internalGetProjectList()
    }

    private fun internalGetProjectList(): List<GraffitiProject> {
        return projectsDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull {
                try { serializer.decodeFromString<GraffitiProject>(it.readText()) }
                catch(e: Exception) { null }
            } ?: emptyList()
    }

    override suspend fun createProject(name: String): GraffitiProject = withContext(Dispatchers.IO) {
        val p = GraffitiProject(name = name)
        saveProject(p)
        _currentProject.value = p
        p
    }

    override suspend fun createProject(project: GraffitiProject) {
        saveProject(project)
        _currentProject.value = project
    }

    override suspend fun getProject(id: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val file = File(projectsDir, "$id.json")
        if (file.exists()) serializer.decodeFromString<GraffitiProject>(file.readText()) else null
    }

    override suspend fun loadProject(id: String): Result<Unit> {
        val p = getProject(id)
        return if (p != null) {
            _currentProject.value = p
            Result.success(Unit)
        } else Result.failure(Exception("Not found"))
    }

    override suspend fun updateProject(project: GraffitiProject) = withContext(Dispatchers.IO) {
        saveProject(project)
        if (_currentProject.value?.id == project.id) _currentProject.value = project
    }

    override suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject) {
        _currentProject.value?.let { updateProject(transform(it)) }
    }

    override suspend fun deleteProject(id: String) = withContext(Dispatchers.IO) {
        File(projectsDir, "$id.json").delete()
        if (_currentProject.value?.id == id) _currentProject.value = null
    }

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String = withContext(Dispatchers.IO) {
        val file = File(File(projectsDir, projectId).apply { mkdirs() }, filename)
        file.writeBytes(data)
        file.absolutePath
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        getProject(projectId)?.let { updateProject(it.copy(targetFingerprint = path)) }
    }

    override suspend fun updateMapPath(projectId: String, path: String) {
        getProject(projectId)?.let { updateProject(it.copy(mapPath = path)) }
    }

    private fun saveProject(p: GraffitiProject) {
        File(projectsDir, "${p.id}.json").writeText(serializer.encodeToString(p))
    }
}
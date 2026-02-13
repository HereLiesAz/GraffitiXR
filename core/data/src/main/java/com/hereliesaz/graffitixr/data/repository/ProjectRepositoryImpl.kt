package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.core.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.data.local.ProjectSerializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serializer: ProjectSerializer
) : ProjectRepository {

    private val projectsDir = File(context.filesDir, "projects")
    private val _projects = MutableStateFlow<Map<String, GraffitiProject>>(emptyMap())

    // Expose flow from domain interface
    override val projects: Flow<List<GraffitiProject>> = _projects.map {
        it.values.toList().sortedByDescending { p -> p.lastModified }
    }

    init {
        if (!projectsDir.exists()) projectsDir.mkdirs()
        refresh()
    }

    private fun refresh() {
        val loaded = projectsDir.listFiles()?.mapNotNull { dir ->
            val metaFile = File(dir, "project.json")
            if (metaFile.exists()) {
                try {
                    serializer.decode(metaFile.readText())
                } catch (e: Exception) {
                    null
                }
            } else null
        }?.associateBy { it.id } ?: emptyMap()

        _projects.value = loaded
    }

    override suspend fun getProject(id: String): GraffitiProject? {
        return _projects.value[id]
    }


    override suspend fun createProject(project: GraffitiProject) {
        saveToDisk(project)
        _projects.value = _projects.value + (project.id to project)
    }

    override suspend fun updateProject(project: GraffitiProject) {
        val updated = project.copy(lastModified = System.currentTimeMillis())
        saveToDisk(updated)
        _projects.value += (updated.id to updated)
    }

    override suspend fun deleteProject(id: String) {
        withContext(Dispatchers.IO) {
            val dir = File(projectsDir, id)
            if (dir.exists()) dir.deleteRecursively()
        }
        _projects.value -= id
    }

    // --- Teleological Implementation ---

    override suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val dir = File(projectsDir, projectId)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, filename)
            FileOutputStream(file).use { it.write(data) }

            file.absolutePath
        }
    }

    override suspend fun updateTargetFingerprint(projectId: String, path: String) {
        val current = _projects.value[projectId] ?: return
        updateProject(current.copy(targetFingerprintPath = path))
    }

    override suspend fun updateMapPath(projectId: String, path: String) {
        val current = _projects.value[projectId] ?: return
        updateProject(current.copy(mapPath = path))
    }

    private suspend fun saveToDisk(project: GraffitiProject) {
        withContext(Dispatchers.IO) {
            val dir = File(projectsDir, project.id)
            if (!dir.exists()) dir.mkdirs()

            val metaFile = File(dir, "project.json")
            metaFile.writeText(serializer.encode(project))
        }
    }
}
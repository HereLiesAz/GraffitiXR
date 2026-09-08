// FILE: feature/dashboard/src/main/java/com/hereliesaz/graffitixr/feature/dashboard/DashboardViewModel.kt
package com.hereliesaz.graffitixr.feature.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var openProjectJob: Job? = null

    init {
        viewModelScope.launch {
            repository.currentProject.collect { project ->
                _uiState.update { it.copy(currentProjectId = project?.id, currentProjectName = project?.name) }
            }
        }
    }

    fun dismissProjectError() {
        _uiState.update { it.copy(projectErrorMessage = null) }
    }

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val list = repository.getProjects()
                _uiState.update { it.copy(availableProjects = list) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(projectErrorMessage = "Couldn't load the project library.") }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun openProject(project: GraffitiProject, onOpened: () -> Unit = {}) {
        openProjectJob?.cancel()
        openProjectJob = viewModelScope.launch {
            val result = try {
                repository.loadProject(project.id)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
            result.onSuccess {
                _uiState.update { it.copy(currentProjectId = project.id, currentProjectName = project.name) }
                onOpened()
            }.onFailure { e ->
                android.util.Log.e("DashboardViewModel", "Failed to open project ${project.id}", e)
                _uiState.update { it.copy(projectErrorMessage = "Couldn't open this project.") }
                loadAvailableProjects()
            }
        }
    }

    fun onProjectRenamed(name: String) {
        _uiState.update { it.copy(currentProjectName = name) }
    }

    fun onNewProjectTriggered() {
        _uiState.update { it.copy(showNewProjectDialog = true) }
    }

    fun createAndOpenProject(name: String = "Untitled") {
        viewModelScope.launch {
            try {
                val p = repository.createProject(name)
                _uiState.update { it.copy(currentProjectId = p.id, currentProjectName = p.name) }
                loadAvailableProjects()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(projectErrorMessage = "Couldn't create the project.") }
            }
        }
    }

    fun onCreateProject(name: String, onCreated: () -> Unit = {}) {
        if (_uiState.value.isCreatingProject) return
        _uiState.update { it.copy(isCreatingProject = true) }
        viewModelScope.launch {
            try {
                val p = repository.createProject(name)
                _uiState.update { it.copy(currentProjectId = p.id, currentProjectName = p.name, showNewProjectDialog = false) }
                onCreated()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Failed to create project", e)
                _uiState.update { it.copy(projectErrorMessage = "Couldn't create the project. Check available storage and try again.") }
            } finally {
                _uiState.update { it.copy(isCreatingProject = false) }
            }
            loadAvailableProjects()
        }
    }

    fun dismissNewProjectDialog() {
        if (_uiState.value.isCreatingProject) return
        _uiState.update { it.copy(showNewProjectDialog = false) }
    }

    fun importProject(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importErrorMessage = null) }
            try {
                val result = repository.importProject(uri)
                if (result.isSuccess) {
                    loadAvailableProjects()
                } else {
                    val e = result.exceptionOrNull()
                    android.util.Log.e("DashboardViewModel", "Import failed for $uri", e)
                    _uiState.update { it.copy(importErrorMessage = IMPORT_FAILURE_MESSAGE) }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Error importing project", e)
                _uiState.update { it.copy(importErrorMessage = IMPORT_FAILURE_MESSAGE) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun dismissImportError() {
        _uiState.update { it.copy(importErrorMessage = null) }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                repository.deleteProject(projectId)
                loadAvailableProjects()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Error deleting project: $projectId", e)
                _uiState.update { it.copy(projectErrorMessage = "Couldn't delete this project.") }
            }
        }
    }

    fun checkForUpdates(currentVersion: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingForUpdate = true, updateStatusMessage = "Checking for updates...") }
            try {
                val latestRelease = fetchLatestRelease()
                if (latestRelease == null) {
                    _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Could not connect to update server.") }
                    return@launch
                }
                val latestTag = latestRelease.tagName.removePrefix("v")
                if (isNewerVersion(latestTag, currentVersion)) {
                    _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "New version $latestTag available", updateUrl = latestRelease.htmlUrl) }
                } else {
                    _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "You are on the latest experimental build.") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Update check failed.") }
            }
        }
    }

    fun openUpdatePage(context: Context) {
        val url = _uiState.value.updateUrl ?: "https://github.com/hereliesaz/GraffitiXR/releases"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            _uiState.update { it.copy(updateStatusMessage = "Opening browser...") }
        } catch (e: Exception) {
            android.util.Log.e("DashboardViewModel", "Failed to open update URL", e)
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://api.github.com/repos/hereliesaz/GraffitiXR/releases/latest")
            connection = url.openConnection() as HttpURLConnection
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != 200) return@withContext null
            parseRelease(connection.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    internal fun parseRelease(json: String): GitHubRelease? = try {
        val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
        val htmlUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]*/releases/[^\"]*)\"").find(json)?.groupValues?.get(1)
            ?: "https://github.com/hereliesaz/GraffitiXR/releases"
        GitHubRelease(tagName, htmlUrl)
    } catch (_: Exception) { null }

    internal fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String) = v.trim().removePrefix("v").split(".").map { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    internal data class GitHubRelease(val tagName: String, val htmlUrl: String)

    companion object {
        internal const val IMPORT_FAILURE_MESSAGE =
            "Couldn't import project — the file may be corrupt or in an unsupported format."
    }
}

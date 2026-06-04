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
import kotlinx.coroutines.delay
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

    private val _navigationTrigger = MutableStateFlow<String?>(null)
    val navigationTrigger: StateFlow<String?> = _navigationTrigger.asStateFlow()

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.getProjects()
            _uiState.update { it.copy(availableProjects = list, isLoading = false) }
        }
    }

    fun openProject(project: GraffitiProject) {
        viewModelScope.launch {
            repository.loadProject(project.id).onFailure { e ->
                // Previously the Result was discarded, so a missing/corrupt project failed
                // silently. Log it and refresh the list so a deleted project stops lingering.
                android.util.Log.e("DashboardViewModel", "Failed to open project ${project.id}", e)
                loadAvailableProjects()
            }
        }
    }

    fun onNewProjectTriggered() {
        _uiState.update { it.copy(showNewProjectDialog = true) }
    }

    /**
     * Create a project AND load it, so the editor gets a non-null projectId immediately. Used when the
     * user jumps straight into Design with no active project — otherwise every Add silently no-ops
     * because the add handlers require a projectId.
     */
    fun createAndOpenProject(name: String = "Untitled", isRightHanded: Boolean = true) {
        viewModelScope.launch {
            val p = repository.createProject(name)
            repository.updateProject(p.copy(isRightHanded = isRightHanded))
            repository.loadProject(p.id)
            loadAvailableProjects()
        }
    }

    fun onCreateProject(name: String, isRightHanded: Boolean) {
        viewModelScope.launch {
            val p = repository.createProject(name)
            repository.updateProject(p.copy(isRightHanded = isRightHanded))
            _uiState.update { it.copy(showNewProjectDialog = false) }
            loadAvailableProjects()
        }
    }

    fun dismissNewProjectDialog() {
        _uiState.update { it.copy(showNewProjectDialog = false) }
    }

    fun importProject(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val result = repository.importProject(uri)
                if (result.isSuccess) {
                    loadAvailableProjects()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Error importing project", e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                repository.deleteProject(projectId)
                loadAvailableProjects()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Error deleting project: $projectId", e)
            }
        }
    }

    fun navigateToSurveyor() { _navigationTrigger.value = "surveyor" }
    fun navigateToLibrary() { _navigationTrigger.value = "project_library" }
    fun navigateToSettings() { _navigationTrigger.value = "settings" }
    fun onNavigationConsumed() { _navigationTrigger.value = null }

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
                    _uiState.update {
                        it.copy(
                            isCheckingForUpdate = false,
                            updateStatusMessage = "New version $latestTag available",
                            updateUrl = latestRelease.htmlUrl
                        )
                    }
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
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            _uiState.update { it.copy(updateStatusMessage = "Opening browser...") }
        } catch (e: Exception) {
            android.util.Log.e("DashboardViewModel", "Failed to open update URL", e)
        }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/hereliesaz/GraffitiXR/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode != 200) return@withContext null

                val json = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                parseRelease(json)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseRelease(json: String): GitHubRelease? {
        return try {
            val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            val htmlUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: "https://github.com/hereliesaz/GraffitiXR/releases"
            GitHubRelease(tagName, htmlUrl)
        } catch (e: Exception) { null }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private data class GitHubRelease(val tagName: String, val htmlUrl: String)
}
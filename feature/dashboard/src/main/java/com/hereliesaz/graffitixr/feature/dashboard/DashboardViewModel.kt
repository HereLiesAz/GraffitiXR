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

    // Tracks the in-flight openProject load so a second tap (on the same or a different card)
    // supersedes rather than races the first: cancelling here means only the most recently tapped
    // project's load can ever win and fire navigation, regardless of which finishes disk I/O first.
    private var openProjectJob: Job? = null

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.getProjects()
            _uiState.update { it.copy(availableProjects = list, isLoading = false) }
        }
    }

    fun openProject(project: GraffitiProject) {
        openProjectJob?.cancel()
        openProjectJob = viewModelScope.launch {
            repository.loadProject(project.id)
                .onSuccess {
                    // Navigation is gated on this trigger (consumed once by the observer) rather than
                    // fired unconditionally by the caller, so a failed load can never carry the UI into
                    // the editor. Name is threaded through here (not read back off the id) so anything
                    // that needs to display it — e.g. the rename dialog — has the real name, not a UUID.
                    _uiState.update { it.copy(currentProjectId = project.id, currentProjectName = project.name) }
                    _navigationTrigger.value = DESTINATION_EDITOR
                }
                .onFailure { e ->
                    // Previously the Result was discarded, so a missing/corrupt project failed
                    // silently. Log it and refresh the list so a deleted project stops lingering.
                    android.util.Log.e("DashboardViewModel", "Failed to open project ${project.id}", e)
                    loadAvailableProjects()
                }
        }
    }

    /**
     * Keeps the dashboard's tracked project name in sync after an explicit rename/save from the
     * editor (see MainActivity's save dialog), so the rename dialog pre-fills the current name — not
     * a stale one — the next time it's opened without the user first returning to the library.
     */
    fun onProjectRenamed(name: String) {
        _uiState.update { it.copy(currentProjectName = name) }
    }

    fun onNewProjectTriggered() {
        _uiState.update { it.copy(showNewProjectDialog = true) }
    }

    /**
     * Create a project AND load it, so the editor gets a non-null projectId immediately. Used when the
     * user jumps straight into Design with no active project — otherwise every Add silently no-ops
     * because the add handlers require a projectId.
     */
    fun createAndOpenProject(name: String = "Untitled") {
        viewModelScope.launch {
            val p = repository.createProject(name)
            repository.loadProject(p.id)
            _uiState.update { it.copy(currentProjectId = p.id, currentProjectName = p.name) }
            loadAvailableProjects()
        }
    }

    /**
     * Guards against the new-project dialog staying visible across the async create: a second tap
     * while [DashboardUiState.isCreatingProject] is already true is a no-op, so a duplicate project
     * can't be spawned by tapping Save twice during the window before the dialog dismisses. The flag
     * flips synchronously (before [viewModelScope.launch] hands off control) so two calls issued back
     * to back in the same frame can't both pass the guard.
     */
    fun onCreateProject(name: String) {
        if (_uiState.value.isCreatingProject) return
        _uiState.update { it.copy(isCreatingProject = true) }
        viewModelScope.launch {
            try {
                val p = repository.createProject(name)
                _uiState.update { it.copy(currentProjectId = p.id, currentProjectName = p.name) }
            } finally {
                _uiState.update { it.copy(showNewProjectDialog = false, isCreatingProject = false) }
            }
            loadAvailableProjects()
        }
    }

    fun dismissNewProjectDialog() {
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
            }
        }
    }

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
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.github.com/repos/hereliesaz/GraffitiXR/releases/latest")
                connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode != 200) return@withContext null

                val json = connection.inputStream.bufferedReader().readText()
                parseRelease(json)
            } catch (_: Exception) {
                null
            } finally {
                // disconnect() was previously only reached on the 200 path — the early return and any
                // exception leaked the connection. finally releases it on every path.
                connection?.disconnect()
            }
        }
    }

    internal fun parseRelease(json: String): GitHubRelease? {
        return try {
            val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            // Match the RELEASE's html_url specifically (it contains /releases/), not merely the first
            // one in the document. The release payload also carries the author object's html_url
            // (https://github.com/<user>), so a positional match only worked because GitHub happens to
            // order the release's own field first — reorder the response and this would have started
            // opening the author's profile page instead of the release.
            val htmlUrl = Regex("\"html_url\"\\s*:\\s*\"([^\"]*/releases/[^\"]*)\"").find(json)?.groupValues?.get(1)
                ?: "https://github.com/hereliesaz/GraffitiXR/releases"
            GitHubRelease(tagName, htmlUrl)
        } catch (_: Exception) { null }
    }

    /**
     * Compares dotted version strings numerically, segment by segment.
     *
     * Segments are read up to the first non-digit, so a qualifier travels with its segment
     * ("1.2.3-beta" -> [1, 2, 3]) instead of collapsing it to zero. `toIntOrNull()` on the whole
     * segment returned null for "3-beta" and fell back to 0, making 1.2.3-beta compare as 1.2.0 —
     * which reported a *newer* release as older and silently suppressed the update prompt.
     */
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
        /** [navigationTrigger] value that means "a project just finished loading, enter the editor". */
        const val DESTINATION_EDITOR = "editor"
        internal const val IMPORT_FAILURE_MESSAGE =
            "Couldn't import project — the file may be corrupt or in an unsupported format."
    }
}

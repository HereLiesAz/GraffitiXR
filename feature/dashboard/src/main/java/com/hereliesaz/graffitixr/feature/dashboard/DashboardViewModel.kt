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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _navigationEvents = Channel<String>(Channel.BUFFERED)
    val navigationEvents: Flow<String> = _navigationEvents.receiveAsFlow()

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

    fun openProject(project: GraffitiProject) {
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
                _navigationEvents.trySend(DESTINATION_EDITOR)
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
        if (_uiState.value.isCreatingProject) return
        _uiState.update { it.copy(isCreatingProject = true) }
        viewModelScope.launch {
            try {
                val p = repository.createProject(name)
                _uiState.update { it.copy(currentProjectId = p.id, currentProjectName = p.name) }
                _navigationEvents.trySend(DESTINATION_EDITOR)
                loadAvailableProjects()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(projectErrorMessage = "Couldn't create the project.") }
            } finally {
                _uiState.update { it.copy(isCreatingProject = false) }
            }
        }
    }

    fun onCreateProject(name: String) {
        if (_uiState.value.isCreatingProject) return
        // Dismiss dialog immediately so the user sees progress, not a frozen dialog.
        // On failure it stays closed; the error is shown and NEW allows an explicit retry.
        _uiState.update { it.copy(isCreatingProject = true, showNewProjectDialog = false) }
        viewModelScope.launch {
            try {
                val p = repository.createProject(name)
                _uiState.update { it.copy(currentProjectId = p.id, currentProjectName = p.name) }
                _navigationEvents.trySend(DESTINATION_EDITOR)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Failed to create project", e)
                // Do NOT re-show the dialog on failure: if creation keeps failing (IO error,
                // serialization issue) showing it again creates an infinite retry loop. The
                // error message below is surfaced by the UI; the user can tap NEW to try again.
                _uiState.update {
                    it.copy(
                        projectErrorMessage = "Couldn't create \"$name\" — try again."
                    )
                }
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
                val result = repository.importProject(uri.toString())
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

    /**
     * Opens the public releases page in the browser. That is the whole update story: the app never
     * contacts a server to check for updates (docs/en/PRIVACY_POLICY.md), so there is no "latest
     * version" to compare against and nothing to poll. Only a failure to open the browser is
     * reported back, as [DashboardUiState.releasesPageError].
     */
    fun openReleasesPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            _uiState.update { it.copy(releasesPageError = null) }
        } catch (e: Exception) {
            android.util.Log.e("DashboardViewModel", "Failed to open the releases page", e)
            _uiState.update { it.copy(releasesPageError = "Couldn't open the browser.") }
        }
    }

    companion object {
        internal const val RELEASES_URL = "https://github.com/HereLiesAz/GraffitiXR/releases"
        const val DESTINATION_EDITOR = "editor"
        internal const val IMPORT_FAILURE_MESSAGE =
            "Couldn't import project — the file may be corrupt or in an unsupported format."
    }
}

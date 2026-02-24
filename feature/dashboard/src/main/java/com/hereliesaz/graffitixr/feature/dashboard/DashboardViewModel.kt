package com.hereliesaz.graffitixr.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Dashboard feature, primarily managing the Project Library.
 * Handles loading, creating, deleting, and opening projects.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {

    // Internal mutable state
    private val _uiState = MutableStateFlow(DashboardUiState())

    /**
     * Public immutable state flow observed by the UI.
     */
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _navigationTrigger = MutableStateFlow<String?>(null)
    val navigationTrigger: StateFlow<String?> = _navigationTrigger.asStateFlow()

    /**
     * Fetches the list of all available projects from the repository.
     * Updates [uiState] with the result.
     */
    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.getProjects()
            _uiState.update { it.copy(availableProjects = list, isLoading = false) }
        }
    }

    /**
     * Sets the specified project as the active project in the repository.
     * @param project The project to open.
     */
    fun openProject(project: GraffitiProject) {
        viewModelScope.launch { repository.loadProject(project.id) }
    }

    /**
     * Creates a new empty project and sets it as active.
     * @param isRightHanded The user's handedness preference to initialize the project with.
     */
    fun onNewProject(isRightHanded: Boolean) {
        viewModelScope.launch {
            val p = repository.createProject("New Project")
            repository.updateProject(p.copy(isRightHanded = isRightHanded))
        }
    }

    /**
     * Deletes a project by its ID and refreshes the list.
     * @param projectId The ID of the project to delete.
     */
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
}

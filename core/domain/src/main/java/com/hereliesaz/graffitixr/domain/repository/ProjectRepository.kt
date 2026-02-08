package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.ProjectData
import kotlinx.coroutines.flow.StateFlow

interface ProjectRepository {

    /**
     * The currently active project being edited.
     */
    val currentProject: StateFlow<ProjectData?>

    /**
     * Loads a project from disk by ID.
     */
    suspend fun loadProject(projectId: String): Result<ProjectData>

    /**
     * Creates a new project and sets it as active.
     */
    suspend fun createProject(name: String): ProjectData

    /**
     * Updates the current project state (autosave).
     * @param transform A function that takes the current project and returns the modified one.
     */
    suspend fun updateProject(transform: (ProjectData) -> ProjectData)

    /**
     * Returns a list of all available projects.
     */
    suspend fun getProjects(): List<ProjectData>

    /**
     * Saves the current state to disk immediately.
     */
    suspend fun saveProject(): Result<Unit>

    /**
     * Closes the current project and clears memory.
     */
    suspend fun closeProject()
}
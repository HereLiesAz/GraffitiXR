package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.Project
import kotlinx.coroutines.flow.StateFlow

interface ProjectRepository {

    /**
     * The currently active project being edited.
     */
    val currentProject: StateFlow<Project?>

    /**
     * Loads a project from disk by ID.
     */
    suspend fun loadProject(projectId: String): Result<Project>

    /**
     * Creates a new project and sets it as active.
     */
    suspend fun createProject(name: String): Project

    /**
     * Updates the current project state (autosave).
     * @param transform A function that takes the current project and returns the modified one.
     */
    suspend fun updateProject(transform: (Project) -> Project)

    /**
     * Saves the current state to disk immediately.
     */
    suspend fun saveProject(): Result<Unit>

    /**
     * Closes the current project and clears memory.
     */
    suspend fun closeProject()
}
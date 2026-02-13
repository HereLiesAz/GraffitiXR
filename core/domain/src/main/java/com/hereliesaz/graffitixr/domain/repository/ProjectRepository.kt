package com.hereliesaz.graffitixr.core.domain.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.core.common.model.GraffitiProject
import kotlinx.coroutines.flow.Flow

/**
 * Interface for managing the lifecycle and persistence of Graffiti Projects.
 * This is the primary entry point for features to interact with project data.
 */
interface ProjectRepository {

    /**
     * A [StateFlow] emitting the currently active project being edited, or null if none is open.
     */
    val currentProject: StateFlow<GraffitiProject?>

    /**
     * Loads a project from disk by its unique ID.
     *
     * @param projectId The unique identifier of the project to load.
     * @return A [Result] containing the [GraffitiProject] if successful, or an exception.
     */
    val projects: Flow<List<GraffitiProject>>

    /**
     * Creates a new project with the given name and sets it as the active project.
     *
     * @param name The display name for the new project.
     * @return The newly created [GraffitiProject].
     */
    suspend fun createProject(name: String): GraffitiProject
    suspend fun getProject(id: String): GraffitiProject?
    suspend fun createProject(project: GraffitiProject)
    suspend fun updateProject(project: GraffitiProject)
    suspend fun deleteProject(id: String)

    // --- Teleological Additions ---
    /**
     * Updates the current project state using a transformation function.
     * Triggers an asynchronous autosave operation.
     *
     * @param transform A lambda that takes the current [GraffitiProject] and returns a modified version.
     */
    suspend fun updateProject(transform: (GraffitiProject) -> GraffitiProject)

    /**
     * Saves a binary blob (ORB descriptors, SLAM map) to the project folder.
     * Returns the absolute path of the saved file.
     * Retrieves a list of all available projects stored on the device.
     *
     * @return A list of [GraffitiProject] metadata objects.
     */
    suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String

    /**
     * Forces an immediate save of the current project state to disk.
     *
     * @return A [Result] indicating success or failure.
     * Updates the pointer to the "Future Wall" fingerprint (Target).
     */
    suspend fun updateTargetFingerprint(projectId: String, path: String)

    /**
     * Closes the currently active project and clears it from memory.
     * Updates the pointer to the SLAM voxel map.
     */
    suspend fun updateMapPath(projectId: String, path: String)
}

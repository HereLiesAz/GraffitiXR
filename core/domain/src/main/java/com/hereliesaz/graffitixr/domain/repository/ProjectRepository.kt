package com.hereliesaz.graffitixr.core.domain.repository

import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.core.common.model.GraffitiProject
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    /**
     * Observes the list of all projects, sorted by last modified.
     */
    val projects: Flow<List<GraffitiProject>>

    suspend fun getProject(id: String): GraffitiProject?
    suspend fun createProject(project: GraffitiProject)
    suspend fun updateProject(project: GraffitiProject)
    suspend fun deleteProject(id: String)

    // --- Teleological Additions ---

    /**
     * Saves a binary blob (ORB descriptors, SLAM map) to the project folder.
     * Returns the absolute path of the saved file.
     */
    suspend fun saveArtifact(projectId: String, filename: String, data: ByteArray): String

    /**
     * Updates the pointer to the "Future Wall" fingerprint (Target).
     */
    suspend fun updateTargetFingerprint(projectId: String, path: String)

    /**
     * Updates the pointer to the SLAM voxel map.
     */
    suspend fun updateMapPath(projectId: String, path: String)
}
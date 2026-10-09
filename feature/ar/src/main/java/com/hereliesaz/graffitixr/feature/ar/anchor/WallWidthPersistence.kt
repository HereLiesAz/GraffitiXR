package com.hereliesaz.graffitixr.feature.ar.anchor

import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import timber.log.Timber

/** Writes a Measure result (BACKLOG Phase 6 step 1) to the project it was taken in. */
object WallWidthPersistence {
    /**
     * Set [widthMeters] as project [projectId]'s wall width. True only when that project was the
     * one written; false when the write threw or a different project was current by the time the
     * transform ran (the caller keeps the reading and tells the artist).
     */
    suspend fun save(repository: ProjectRepository, projectId: String, widthMeters: Float): Boolean {
        var applied = false
        return try {
            repository.updateProject {
                if (it.id == projectId) {
                    applied = true
                    it.copy(wallWidthMeters = widthMeters)
                } else {
                    applied = false
                    it
                }
            }
            applied
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.e(e, "Wall width save failed")
            false
        }
    }
}

package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.ArScanMode
import kotlinx.coroutines.flow.Flow

/**
 * Interface for managing application-wide settings.
 */
interface SettingsRepository {
    /**
     * A flow emitting the current user preference for handedness.
     * True for right-handed (default), False for left-handed.
     */
    val isRightHanded: Flow<Boolean>

    /**
     * Updates the user's handedness preference.
     *
     * @param isRight True for right-handed, False for left-handed.
     */
    suspend fun setRightHanded(isRight: Boolean)

    /** Which AR depth/mapping mode the user has selected. Defaults to [ArScanMode.CLOUD_POINTS]. */
    val arScanMode: Flow<ArScanMode>

    suspend fun setArScanMode(mode: ArScanMode)
}

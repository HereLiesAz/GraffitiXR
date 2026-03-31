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

    /** Which AR depth/mapping mode the user has selected. Defaults to [ArScanMode.GAUSSIAN_SPLATS]. */
    val arScanMode: Flow<ArScanMode>

    suspend fun setArScanMode(mode: ArScanMode)

    /** Whether to draw an orange boundary rectangle around the AR overlay quad when anchor is active. */
    val showAnchorBoundary: Flow<Boolean>

    suspend fun setShowAnchorBoundary(show: Boolean)

    /** Whether distances are displayed in imperial (ft) rather than metric (m/cm). */
    val isImperialUnits: Flow<Boolean>

    suspend fun setImperialUnits(imperial: Boolean)

    /** Canvas background color as ARGB Int. Default is opaque black (0xFF000000). */
    val backgroundColor: Flow<Int>

    suspend fun setBackgroundColor(argb: Int)
}

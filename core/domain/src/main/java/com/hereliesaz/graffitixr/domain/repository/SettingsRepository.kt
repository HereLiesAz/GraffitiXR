package com.hereliesaz.graffitixr.domain.repository

import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
import com.hereliesaz.graffitixr.common.model.AppLanguage
import kotlinx.coroutines.flow.Flow

/**
 * Interface for managing application-wide settings.
 */
interface SettingsRepository {
    /**
     * A flow emitting the current user preference for app language.
     */
    val language: Flow<AppLanguage>

    /**
     * Updates the user's language preference.
     */
    suspend fun setLanguage(language: AppLanguage)

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

    /** Which AR depth/mapping mode the user has selected. Defaults to [ArScanMode.MURAL]. */
    val arScanMode: Flow<ArScanMode>

    suspend fun setArScanMode(mode: ArScanMode)
    
    /** The specific engine used when [ArScanMode.MURAL] is active. */
    val muralMethod: Flow<MuralMethod>
    
    suspend fun setMuralMethod(method: MuralMethod)

    /** Whether to draw an orange boundary rectangle around the AR overlay quad when anchor is active. */
    val showAnchorBoundary: Flow<Boolean>

    suspend fun setShowAnchorBoundary(show: Boolean)

    /**
     * Set once a device proves it can't run forced hardware-stereo (ARCore motion-stereo disparity
     * fails / VIO never tracks): future sessions skip the stereo config and stay on Canvas, so the
     * broken path can't thrash the device. Cleared when the user explicitly re-selects Mural.
     */
    val forcedStereoUnstable: Flow<Boolean>

    suspend fun setForcedStereoUnstable(unstable: Boolean)

    /**
     * Cached result of the one-time hardware-stereo capability probe:
     * -1 = not yet probed, 0 = device can't run forced stereo (use mono), 1 = stereo tracks (use it).
     * Probing runs a short throwaway stereo session on a worker thread the first time AR is entered,
     * so we only adopt the dual-lens path on a device whose motion-stereo actually tracks.
     */
    val stereoCapability: Flow<Int>

    suspend fun setStereoCapability(value: Int)

    /** Whether distances are displayed in imperial (ft) rather than metric (m/cm). */
    val isImperialUnits: Flow<Boolean>

    suspend fun setImperialUnits(imperial: Boolean)

    /** Canvas background color as ARGB Int. Default is opaque black (0xFF000000). */
    val backgroundColor: Flow<Int>
    suspend fun setBackgroundColor(argb: Int)

    /** Minimum viewpoint shift (degrees) before a re-observation parallax-verifies a voxel. Default 4. */
    val parallaxMinDegrees: Flow<Float>
    suspend fun setParallaxMinDegrees(deg: Float)

    /** Set of tutorial keys the user has completed. Keys: "tut_ar", "tut_overlay", "tut_mockup", "tut_trace", "tut_design", "tut_project". */
    val completedTutorials: Flow<Set<String>>

    suspend fun markTutorialComplete(key: String)

    /** Clears every completed-tutorial key, allowing first-run flows to fire again. */
    suspend fun clearCompletedTutorials()
}

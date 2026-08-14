package com.hereliesaz.graffitixr.domain.repository

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

    /**
     * Whether the 360-degree ambient sweep is required before the wall scan begins. Default true.
     *
     * Replaces the old Canvas/Mural `arScanMode`: the two modes named two mapping engines (splatting
     * and surface mesh) that no longer exist, and the only behaviour that still differed between them
     * was this sweep.
     */
    val ambientScanEnabled: Flow<Boolean>

    suspend fun setAmbientScanEnabled(enabled: Boolean)
    
    

    /** Whether to draw an orange boundary rectangle around the AR overlay quad when anchor is active. */
    val showAnchorBoundary: Flow<Boolean>

    suspend fun setShowAnchorBoundary(show: Boolean)

    /**
     * Whether relocalization corrections are fused onto the overlay ("drift correction").
     *
     * Persisted, and that is the whole point. It was session-scoped, which cost a real field run:
     * the artist enabled it, the session ended, and the next run silently reverted to off. The
     * relocalizer locked on 31% of ticks at a 94% inlier ratio and every one of those corrections
     * was computed and discarded, so the overlay drifted exactly as if nothing worked — and the
     * report's own `Fusion: DISABLED ×777 (100%)` was the only place that said why.
     *
     * Off by default still: it is unvalidated on a device. But a switch the artist has to remember
     * to find and re-flip on every launch is not a switch they have.
     */
    val driftCorrectionEnabled: Flow<Boolean>

    suspend fun setDriftCorrectionEnabled(on: Boolean)

    /**
     * Whether self-grow may promote validated new marks into the reloc fingerprint.
     *
     * Persisted for the same reason, and still default-off for a stronger one: it is the only
     * mechanism that permanently rewrites the map. Persistence remembers an explicit choice; it does
     * not make one.
     */
    val selfGrowEnabled: Flow<Boolean>

    suspend fun setSelfGrowEnabled(on: Boolean)

    /**
     * The persistent wall FEATURE MAP (`IMPLEMENTATION.md` phases 2b/3): grow it from
     * relocalization-locked frames, and match against it as a second relocalization source.
     *
     * Off by default, like the other two experiment switches, and persisted for the same reason.
     * Until this existed, `SlamManager.setMapRelocEnabled`/`setMapBuildEnabled` had no caller at
     * all: the native flags stayed at their `false` defaults for the process lifetime, so the map
     * was never built, never matched, and the `WallFeatureMap` the project record persists for it
     * was always empty — a whole persistence path carrying nothing.
     */
    val featureMapEnabled: Flow<Boolean>

    suspend fun setFeatureMapEnabled(on: Boolean)

    /**
     * ARCore camera autofocus. Default ON, which is the shipped behaviour.
     *
     * A real trade-off rather than a setting with a right answer. FIXED parks the lens at infinity
     * and keeps the optics constant, which is what ARCore's triangulation wants; at arm's length in
     * low light it also delivers a blurred frame with no usable texture, so no features and no
     * planes. AUTO fixes the blur and in exchange sweeps the effective focal length mid-stream,
     * which on devices with sloppy OEM intrinsics destabilises tracking. Persisted so an artist who
     * has found which one their device and their working distance prefer keeps it.
     */
    val autoFocusEnabled: Flow<Boolean>

    suspend fun setAutoFocusEnabled(on: Boolean)

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

    /** ARCore camera target frame rate: 60 (default) or 30. Lower = less power/heat. */
    val cameraTargetFps: Flow<Int>
    suspend fun setCameraTargetFps(fps: Int)

    /** Perception-throttle triggers: each, when on, drops perception to 30fps while active. Default on. */
    val throttleOnThermal: Flow<Boolean>
    suspend fun setThrottleOnThermal(on: Boolean)
    val throttleOnPowerSave: Flow<Boolean>
    suspend fun setThrottleOnPowerSave(on: Boolean)
    val throttleOnLowBattery: Flow<Boolean>
    suspend fun setThrottleOnLowBattery(on: Boolean)
    val throttleOnLag: Flow<Boolean>
    suspend fun setThrottleOnLag(on: Boolean)

    /**
     * Master toggle for the adaptive AR frame-rate coach (gates heavy SLAM work while idle, plus the
     * battery-tier degradation). Default on. Off = always full rate (more drain, no behaviour change).
     */
    val adaptiveRateEnabled: Flow<Boolean>
    suspend fun setAdaptiveRateEnabled(on: Boolean)

    /** Set of tutorial keys the user has completed. Keys: "tut_ar", "tut_overlay", "tut_mockup", "tut_trace", "tut_design", "tut_project". */
    val completedTutorials: Flow<Set<String>>

    suspend fun markTutorialComplete(key: String)

    /** Clears every completed-tutorial key, allowing first-run flows to fire again. */
    suspend fun clearCompletedTutorials()

    /**
     * The four AR perception-debug overlays (diagnostic HUD, feature points, plane grids, point
     * cloud). Previously these only ever dispatched an in-memory reducer intent — indistinguishable
     * in Settings from the handedness toggle two rows above, which genuinely persists, until the
     * process was killed and every one of them silently reverted to its hardcoded default.
     */
    val showDiagOverlay: Flow<Boolean>
    suspend fun setShowDiagOverlay(on: Boolean)

    val showFeaturePoints: Flow<Boolean>
    suspend fun setShowFeaturePoints(on: Boolean)

    val showPlaneGrids: Flow<Boolean>
    suspend fun setShowPlaneGrids(on: Boolean)

    val showPoints: Flow<Boolean>
    suspend fun setShowPoints(on: Boolean)
}

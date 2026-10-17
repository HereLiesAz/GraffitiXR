package com.hereliesaz.graffitixr.data.repository

import kotlinx.coroutines.flow.distinctUntilChanged
import com.hereliesaz.graffitixr.common.model.AnticipationSettings
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.common.model.CameraTargetFps
import com.hereliesaz.graffitixr.common.model.CoverageGlowColor
import com.hereliesaz.graffitixr.common.model.CoverageGlowIntensity
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    private val LANGUAGE = stringPreferencesKey("language")
    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")
    // Legacy: the Canvas/Mural scan mode, read only to migrate an existing preference (below).
    private val AR_SCAN_MODE = stringPreferencesKey("ar_scan_mode")
    private val AMBIENT_SCAN_ENABLED = booleanPreferencesKey("ambient_scan_enabled")

    /**
     * The legacy Canvas mode's persisted name, as a literal.
     *
     * Deliberately not a reference to the enum: the enum is deleted, and a migration that depends on
     * live code is a migration that breaks the next time that code is refactored. What is on disk is
     * a string, so the check is against a string.
     */
    private val LEGACY_CANVAS_MODE = "CLOUD_POINTS"
    private val SHOW_ANCHOR_BOUNDARY = booleanPreferencesKey("show_anchor_boundary")
    private val DRIFT_CORRECTION_ENABLED = booleanPreferencesKey("drift_correction_enabled")
    private val SELF_GROW_ENABLED = booleanPreferencesKey("self_grow_enabled")
    private val CRASH_REPORTING_CONSENT = booleanPreferencesKey("crash_reporting_consent")
    private val CRASH_REPORT_TOKEN = stringPreferencesKey("crash_report_token")
    private val LAST_OPEN_PROJECT_ID = stringPreferencesKey("last_open_project_id")
    private val FEATURE_MAP_ENABLED = booleanPreferencesKey("feature_map_enabled")
    private val AUTO_FOCUS_ENABLED = booleanPreferencesKey("auto_focus_enabled")
    private val FORCED_STEREO_UNSTABLE = booleanPreferencesKey("forced_stereo_unstable")
    // Key intentionally renamed from "stereo_capability": the probe's meaning changed from "stereo
    // tracks" to "dual lenses actually triangulate depth", so pre-existing verdicts must be discarded
    // and re-probed under the stricter test. Reading the new key returns -1 (unprobed) on old installs.
    private val STEREO_CAPABILITY = intPreferencesKey("depth_triangulation_capability")
    private val IS_IMPERIAL_UNITS = booleanPreferencesKey("is_imperial_units")
    private val BACKGROUND_COLOR = intPreferencesKey("background_color")
    private val CAMERA_TARGET_FPS = intPreferencesKey("camera_target_fps")
    private val AR_CORE_ENABLED = booleanPreferencesKey("ar_core_enabled")
    private val AR_CORE_DEPTH_ENABLED = booleanPreferencesKey("ar_core_depth_enabled")
    private val MONOCULAR_DEPTH_ENABLED = booleanPreferencesKey("monocular_depth_enabled")
    private val THROTTLE_ON_THERMAL = booleanPreferencesKey("throttle_on_thermal")
    private val THROTTLE_ON_POWER_SAVE = booleanPreferencesKey("throttle_on_power_save")
    private val THROTTLE_ON_LOW_BATTERY = booleanPreferencesKey("throttle_on_low_battery")
    private val THROTTLE_ON_LAG = booleanPreferencesKey("throttle_on_lag")
    private val ADAPTIVE_RATE_ENABLED = booleanPreferencesKey("adaptive_rate_enabled")
    private val COMPLETED_TUTORIALS = stringSetPreferencesKey("completed_tutorials")
    private val SHOW_DIAG_OVERLAY = booleanPreferencesKey("show_diag_overlay")
    private val SHOW_FEATURE_POINTS = booleanPreferencesKey("show_feature_points")
    private val SHOW_PLANE_GRIDS = booleanPreferencesKey("show_plane_grids")
    private val SHOW_POINTS = booleanPreferencesKey("show_points")
    private val COVERAGE_GLOW_COLOR = intPreferencesKey("coverage_glow_color")
    private val COVERAGE_GLOW_INTENSITY = floatPreferencesKey("coverage_glow_intensity")
    private val COVERAGE_GLOW_STYLE = stringPreferencesKey("coverage_glow_style")
    private val FINGERPRINT_FEATURES = stringPreferencesKey("fingerprint_features")
    private val ANTICIPATION_DISTANCE = intPreferencesKey("anticipation_distance")
    private val ANTICIPATION_LATITUDE = intPreferencesKey("anticipation_latitude")
    private val ANTICIPATION_LONGITUDE = intPreferencesKey("anticipation_longitude")
    private val ANTICIPATION_ROLL = intPreferencesKey("anticipation_roll")
    private val ANTICIPATION_BATTERY = floatPreferencesKey("anticipation_battery_weight")
    private val ANTICIPATION_HEAT = floatPreferencesKey("anticipation_heat_weight")
    private val ANTICIPATION_COMPUTE = floatPreferencesKey("anticipation_compute_weight")

    override val language: Flow<AppLanguage> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val code = preferences[LANGUAGE] ?: ""
            AppLanguage.entries.find { it.code == code } ?: AppLanguage.SYSTEM
        }

    override suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { preferences ->
            preferences[LANGUAGE] = language.code
        }
    }

    override val isRightHanded: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[IS_RIGHT_HANDED] ?: true
        }

    override suspend fun setRightHanded(isRight: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_RIGHT_HANDED] = isRight
        }
    }

    /**
     * Migrates rather than resets. Canvas (`CLOUD_POINTS`) was precisely the mode that skipped the
     * ambient sweep, so a user who picked it keeps skipping it; everyone else — including the
     * untouched default, Mural — keeps requiring it. Reading the legacy key only when the new one is
     * absent means the migration happens once and a later explicit choice always wins.
     */
    override val ambientScanEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            preferences[AMBIENT_SCAN_ENABLED]
                ?: (preferences[AR_SCAN_MODE] != LEGACY_CANVAS_MODE)
        }

    override suspend fun setAmbientScanEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AMBIENT_SCAN_ENABLED] = enabled
        }
    }

    override val showAnchorBoundary: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SHOW_ANCHOR_BOUNDARY] ?: false }

    override val forcedStereoUnstable: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[FORCED_STEREO_UNSTABLE] ?: false }

    override suspend fun setForcedStereoUnstable(unstable: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FORCED_STEREO_UNSTABLE] = unstable
        }
    }

    override val stereoCapability: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[STEREO_CAPABILITY] ?: -1 }

    override suspend fun setStereoCapability(value: Int) {
        context.dataStore.edit { preferences ->
            preferences[STEREO_CAPABILITY] = value
        }
    }

    override suspend fun setShowAnchorBoundary(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_ANCHOR_BOUNDARY] = show
        }
    }

    // Drift correction defaults ON; an explicit choice (either way) is remembered.
    override val driftCorrectionEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[DRIFT_CORRECTION_ENABLED] ?: true }

    override suspend fun setDriftCorrectionEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[DRIFT_CORRECTION_ENABLED] = on
        }
    }

    override val selfGrowEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SELF_GROW_ENABLED] ?: true }

    override suspend fun setSelfGrowEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SELF_GROW_ENABLED] = on
        }
    }

    override val crashReportingConsent: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[CRASH_REPORTING_CONSENT] ?: false }

    override suspend fun setCrashReportingConsent(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[CRASH_REPORTING_CONSENT] = on
        }
    }

    // Blank by default: no token ever ships in the binary, so an install that never enters one has
    // nothing to leak and uploads nothing. Stored verbatim; trimmed on write so a pasted token with
    // stray whitespace still authenticates.
    override val crashReportToken: Flow<String> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[CRASH_REPORT_TOKEN] ?: "" }

    override suspend fun setCrashReportToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[CRASH_REPORT_TOKEN] = token.trim()
        }
    }

    override val featureMapEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[FEATURE_MAP_ENABLED] ?: true }

    override suspend fun setFeatureMapEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[FEATURE_MAP_ENABLED] = on
        }
    }

    // Defaults TRUE: AUTO is the shipped behaviour, so an install that has never touched the
    // switch keeps exactly the focus mode it had.
    override val autoFocusEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[AUTO_FOCUS_ENABLED] ?: true }

    override suspend fun setAutoFocusEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AUTO_FOCUS_ENABLED] = on
        }
    }

    override val isImperialUnits: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[IS_IMPERIAL_UNITS] ?: false }

    override suspend fun setImperialUnits(imperial: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_IMPERIAL_UNITS] = imperial
        }
    }

    override val backgroundColor: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[BACKGROUND_COLOR] ?: 0xFF000000.toInt() }

    override suspend fun setBackgroundColor(argb: Int) {
        context.dataStore.edit { preferences ->
            preferences[BACKGROUND_COLOR] = argb
        }
    }

    override val cameraTargetFps: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        // DEVICE_DEFAULT: let ARCore select its own camera config instead of the app pinning a rate.
        // 30/60 are opt-in power-saving caps in Settings, not the default.
        .map { preferences -> preferences[CAMERA_TARGET_FPS] ?: CameraTargetFps.DEVICE_DEFAULT }

    override suspend fun setCameraTargetFps(fps: Int) {
        context.dataStore.edit { preferences ->
            preferences[CAMERA_TARGET_FPS] = fps
        }
    }

    // Defaults TRUE: use ARCore when the device supports it. Turning it off forces the standalone
    // (SphereSLAM/KPM) path even on an ARCore-capable device — the same path ARCore-less phones take.
    override val arCoreEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[AR_CORE_ENABLED] ?: true }

    override suspend fun setArCoreEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AR_CORE_ENABLED] = on
        }
    }

    // Defaults TRUE: enable ARCore's Depth API (DepthMode.AUTOMATIC) where the device supports it.
    // AUTOMATIC is ARCore's monocular/ML depth, built for single-camera phones with no depth sensor,
    // and it is what feeds plane finding and stabilizes the feature cloud. The toggle exists because
    // on some hardware the ML depth graph can destabilize VIO; disabling it there is the escape hatch.
    override val arCoreDepthEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[AR_CORE_DEPTH_ENABLED] ?: true }

    override suspend fun setArCoreDepthEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AR_CORE_DEPTH_ENABLED] = on
        }
    }

    // Defaults TRUE: monocular depth (MiDaS Small; LiteRT GPU/CPU since SphereSLAM 0.24.3) feeds the
    // standalone (non-ARCore) sphere map's radial placement. It loads/runs only on the standalone
    // backend, so an ARCore device pays nothing. (Was briefly opt-in after a
    // startup crash: the old Depth Anything model used an 8-bit MatMulNBits op ORT rejects, and R8 had
    // stripped ai.onnxruntime.OrtException so the failure aborted instead of being caught. MiDaS Small
    // avoids that op and now runs on LiteRT, not ORT; the SphereSLAM :models AAR ships the consumer R8
    // keep rules for both runtimes (app/proguard-rules.pro no longer carries its own ORT rule), so a
    // load error fails soft and it is safe on again.)
    override val monocularDepthEnabled: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[MONOCULAR_DEPTH_ENABLED] ?: true }

    override suspend fun setMonocularDepthEnabled(on: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[MONOCULAR_DEPTH_ENABLED] = on
        }
    }

    private fun throttleFlow(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>): Flow<Boolean> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { preferences -> preferences[key] ?: true }

    override val throttleOnThermal: Flow<Boolean> = throttleFlow(THROTTLE_ON_THERMAL)
    override suspend fun setThrottleOnThermal(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_THERMAL] = on }
    }

    override val throttleOnPowerSave: Flow<Boolean> = throttleFlow(THROTTLE_ON_POWER_SAVE)
    override suspend fun setThrottleOnPowerSave(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_POWER_SAVE] = on }
    }

    override val throttleOnLowBattery: Flow<Boolean> = throttleFlow(THROTTLE_ON_LOW_BATTERY)
    override suspend fun setThrottleOnLowBattery(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_LOW_BATTERY] = on }
    }

    override val throttleOnLag: Flow<Boolean> = throttleFlow(THROTTLE_ON_LAG)
    override suspend fun setThrottleOnLag(on: Boolean) {
        context.dataStore.edit { it[THROTTLE_ON_LAG] = on }
    }

    override val adaptiveRateEnabled: Flow<Boolean> = throttleFlow(ADAPTIVE_RATE_ENABLED)
    override suspend fun setAdaptiveRateEnabled(on: Boolean) {
        context.dataStore.edit { it[ADAPTIVE_RATE_ENABLED] = on }
    }

    override val anticipationSettings: Flow<AnticipationSettings> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { p ->
            val d = AnticipationSettings.DEFAULT
            AnticipationSettings(
                distance = p[ANTICIPATION_DISTANCE] ?: d.distance,
                latitude = p[ANTICIPATION_LATITUDE] ?: d.latitude,
                longitude = p[ANTICIPATION_LONGITUDE] ?: d.longitude,
                roll = p[ANTICIPATION_ROLL] ?: d.roll,
                batteryWeight = p[ANTICIPATION_BATTERY] ?: d.batteryWeight,
                heatWeight = p[ANTICIPATION_HEAT] ?: d.heatWeight,
                computeWeight = p[ANTICIPATION_COMPUTE] ?: d.computeWeight,
            ).sanitized()
        }
        .distinctUntilChanged()

    override suspend fun setAnticipationSettings(settings: AnticipationSettings) {
        val s = settings.sanitized()
        context.dataStore.edit {
            it[ANTICIPATION_DISTANCE] = s.distance
            it[ANTICIPATION_LATITUDE] = s.latitude
            it[ANTICIPATION_LONGITUDE] = s.longitude
            it[ANTICIPATION_ROLL] = s.roll
            it[ANTICIPATION_BATTERY] = s.batteryWeight
            it[ANTICIPATION_HEAT] = s.heatWeight
            it[ANTICIPATION_COMPUTE] = s.computeWeight
        }
    }

    override val completedTutorials: Flow<Set<String>> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[COMPLETED_TUTORIALS] ?: emptySet() }

    override suspend fun markTutorialComplete(key: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[COMPLETED_TUTORIALS] ?: emptySet()
            preferences[COMPLETED_TUTORIALS] = current + key
        }
    }

    override suspend fun clearCompletedTutorials() {
        context.dataStore.edit { preferences ->
            preferences[COMPLETED_TUTORIALS] = emptySet()
        }
    }

    // Defaults mirror EditorUiState's hardcoded fallbacks, so an install that has never touched
    // these rows keeps exactly the visualization it shipped with.
    override val showDiagOverlay: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SHOW_DIAG_OVERLAY] ?: false }

    override suspend fun setShowDiagOverlay(on: Boolean) {
        context.dataStore.edit { it[SHOW_DIAG_OVERLAY] = on }
    }

    override val showFeaturePoints: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SHOW_FEATURE_POINTS] ?: false }

    override suspend fun setShowFeaturePoints(on: Boolean) {
        context.dataStore.edit { it[SHOW_FEATURE_POINTS] = on }
    }

    override val showPlaneGrids: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SHOW_PLANE_GRIDS] ?: true }

    override suspend fun setShowPlaneGrids(on: Boolean) {
        context.dataStore.edit { it[SHOW_PLANE_GRIDS] = on }
    }

    override val showPoints: Flow<Boolean> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[SHOW_POINTS] ?: true }

    override suspend fun setShowPoints(on: Boolean) {
        context.dataStore.edit { it[SHOW_POINTS] = on }
    }

    // Default: a hot pink 14% wash. A colour the user picked is stored and kept.
    override val coverageGlowColor: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[COVERAGE_GLOW_COLOR] ?: CoverageGlowColor.DEFAULT.argb }

    override suspend fun setCoverageGlowColor(argb: Int) {
        // Opacity is the intensity setting; store the colour opaque so equality checks are stable.
        context.dataStore.edit { it[COVERAGE_GLOW_COLOR] = argb or OPAQUE_ALPHA }
    }

    override val coverageGlowIntensity: Flow<Float> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            CoverageGlowIntensity.sanitize(
                preferences[COVERAGE_GLOW_INTENSITY] ?: CoverageGlowIntensity.DEFAULT
            )
        }

    override suspend fun setCoverageGlowIntensity(intensity: Float) {
        context.dataStore.edit { it[COVERAGE_GLOW_INTENSITY] = CoverageGlowIntensity.sanitize(intensity) }
    }

    override val coverageGlowStyle: Flow<CoverageGlowStyle> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> CoverageGlowStyle.fromStorageKey(preferences[COVERAGE_GLOW_STYLE]) }

    override suspend fun setCoverageGlowStyle(style: CoverageGlowStyle) {
        context.dataStore.edit { it[COVERAGE_GLOW_STYLE] = style.storageKey }
    }

    override val fingerprintFeatures: Flow<com.hereliesaz.graffitixr.common.model.FingerprintFeatures> =
        context.dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map { com.hereliesaz.graffitixr.common.model.FingerprintFeatures.fromStorageKey(it[FINGERPRINT_FEATURES]) }
            .distinctUntilChanged()

    override suspend fun setFingerprintFeatures(features: com.hereliesaz.graffitixr.common.model.FingerprintFeatures) {
        context.dataStore.edit { it[FINGERPRINT_FEATURES] = features.storageKey }
    }

    override val lastOpenProjectId: Flow<String?> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[LAST_OPEN_PROJECT_ID]?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()

    override suspend fun setLastOpenProjectId(id: String?) {
        context.dataStore.edit { preferences ->
            if (id.isNullOrBlank()) preferences.remove(LAST_OPEN_PROJECT_ID)
            else preferences[LAST_OPEN_PROJECT_ID] = id
        }
    }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF000000.toInt()
    }
}

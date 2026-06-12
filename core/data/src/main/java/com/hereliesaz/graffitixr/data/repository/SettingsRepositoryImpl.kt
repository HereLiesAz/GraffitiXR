package com.hereliesaz.graffitixr.data.repository

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
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.common.model.MuralMethod
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
    private val AR_SCAN_MODE = stringPreferencesKey("ar_scan_mode")
    private val MURAL_METHOD = stringPreferencesKey("mural_method")
    private val SHOW_ANCHOR_BOUNDARY = booleanPreferencesKey("show_anchor_boundary")
    private val FORCED_STEREO_UNSTABLE = booleanPreferencesKey("forced_stereo_unstable")
    // Key intentionally renamed from "stereo_capability": the probe's meaning changed from "stereo
    // tracks" to "dual lenses actually triangulate depth", so pre-existing verdicts must be discarded
    // and re-probed under the stricter test. Reading the new key returns -1 (unprobed) on old installs.
    private val STEREO_CAPABILITY = intPreferencesKey("depth_triangulation_capability")
    private val IS_IMPERIAL_UNITS = booleanPreferencesKey("is_imperial_units")
    private val BACKGROUND_COLOR = intPreferencesKey("background_color")
    private val PARALLAX_MIN_DEG = floatPreferencesKey("parallax_min_degrees")
    private val CAMERA_TARGET_FPS = intPreferencesKey("camera_target_fps")
    private val THROTTLE_ON_THERMAL = booleanPreferencesKey("throttle_on_thermal")
    private val THROTTLE_ON_POWER_SAVE = booleanPreferencesKey("throttle_on_power_save")
    private val THROTTLE_ON_LOW_BATTERY = booleanPreferencesKey("throttle_on_low_battery")
    private val THROTTLE_ON_LAG = booleanPreferencesKey("throttle_on_lag")
    private val ADAPTIVE_RATE_ENABLED = booleanPreferencesKey("adaptive_rate_enabled")
    private val COMPLETED_TUTORIALS = stringSetPreferencesKey("completed_tutorials")

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

    override val arScanMode: Flow<ArScanMode> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            when (preferences[AR_SCAN_MODE]) {
                ArScanMode.CLOUD_POINTS.name -> ArScanMode.CLOUD_POINTS
                else -> ArScanMode.MURAL  // default
            }
        }

    override val muralMethod: Flow<MuralMethod> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            when (preferences[MURAL_METHOD]) {
                MuralMethod.SURFACE_MESH.name -> MuralMethod.SURFACE_MESH
                MuralMethod.CLOUD_OFFSET.name -> MuralMethod.CLOUD_OFFSET
                else -> MuralMethod.VOXEL_HASH // default
            }
        }

    override suspend fun setMuralMethod(method: MuralMethod) {
        context.dataStore.edit { preferences ->
            preferences[MURAL_METHOD] = method.name
        }
    }

    override suspend fun setArScanMode(mode: ArScanMode) {
        context.dataStore.edit { preferences ->
            preferences[AR_SCAN_MODE] = mode.name
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

    override val parallaxMinDegrees: Flow<Float> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PARALLAX_MIN_DEG] ?: 4.0f }

    override suspend fun setParallaxMinDegrees(deg: Float) {
        context.dataStore.edit { preferences ->
            preferences[PARALLAX_MIN_DEG] = deg
        }
    }

    override val cameraTargetFps: Flow<Int> = context.dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[CAMERA_TARGET_FPS] ?: 60 }

    override suspend fun setCameraTargetFps(fps: Int) {
        context.dataStore.edit { preferences ->
            preferences[CAMERA_TARGET_FPS] = fps
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
}

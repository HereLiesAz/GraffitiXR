package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : SettingsRepository {

    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")
    private val AR_SCAN_MODE = stringPreferencesKey("ar_scan_mode")
    private val SHOW_ANCHOR_BOUNDARY = booleanPreferencesKey("show_anchor_boundary")
    private val IS_IMPERIAL_UNITS = booleanPreferencesKey("is_imperial_units")

    override val isRightHanded: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_RIGHT_HANDED] ?: true
        }

    override suspend fun setRightHanded(isRight: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_RIGHT_HANDED] = isRight
        }
    }

    override val arScanMode: Flow<ArScanMode> = context.dataStore.data
        .map { preferences ->
            when (preferences[AR_SCAN_MODE]) {
                ArScanMode.CLOUD_POINTS.name -> ArScanMode.CLOUD_POINTS
                else -> ArScanMode.GAUSSIAN_SPLATS  // default
            }
        }

    override suspend fun setArScanMode(mode: ArScanMode) {
        context.dataStore.edit { preferences ->
            preferences[AR_SCAN_MODE] = mode.name
        }
    }

    override val showAnchorBoundary: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[SHOW_ANCHOR_BOUNDARY] ?: false }

    override suspend fun setShowAnchorBoundary(show: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SHOW_ANCHOR_BOUNDARY] = show
        }
    }

    override val isImperialUnits: Flow<Boolean> = context.dataStore.data
        .map { preferences -> preferences[IS_IMPERIAL_UNITS] ?: false }

    override suspend fun setImperialUnits(imperial: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_IMPERIAL_UNITS] = imperial
        }
    }
}

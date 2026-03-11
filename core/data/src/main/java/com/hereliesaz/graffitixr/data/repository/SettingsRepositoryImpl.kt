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
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")
    private val AR_SCAN_MODE = stringPreferencesKey("ar_scan_mode")

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
                ArScanMode.GAUSSIAN_SPLATS.name -> ArScanMode.GAUSSIAN_SPLATS
                else -> ArScanMode.CLOUD_POINTS  // default
            }
        }

    override suspend fun setArScanMode(mode: ArScanMode) {
        context.dataStore.edit { preferences ->
            preferences[AR_SCAN_MODE] = mode.name
        }
    }
}

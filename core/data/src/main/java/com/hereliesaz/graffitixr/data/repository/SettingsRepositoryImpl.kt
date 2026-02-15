package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
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

    override val isRightHanded: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[IS_RIGHT_HANDED] ?: true
        }

    override suspend fun setRightHanded(isRight: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[IS_RIGHT_HANDED] = isRight
        }
    }
}

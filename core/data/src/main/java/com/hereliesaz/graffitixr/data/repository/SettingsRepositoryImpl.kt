package com.hereliesaz.graffitixr.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepositoryImpl @Inject constructor(private val context: Context) : SettingsRepository {

    private val IS_RIGHT_HANDED = booleanPreferencesKey("is_right_handed")

    override val isRightHanded: Flow<Boolean> =
        context.dataStore.data.map { preferences ->
            preferences[IS_RIGHT_HANDED] ?: true // Default to right-handed
        }

    override suspend fun setRightHanded(isRightHanded: Boolean) {
        context.dataStore.edit {
            it[IS_RIGHT_HANDED] = isRightHanded
        }
    }
}

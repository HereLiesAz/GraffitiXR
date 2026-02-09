package com.hereliesaz.graffitixr.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

class SettingsRepositoryImpl @Inject constructor(override val isRightHanded: Flow<Boolean>) : SettingsRepository {

    private val _themeMode = MutableStateFlow(0) // 0 = System, 1 = Light, 2 = Dark

    val themeMode: Flow<Int> = _themeMode

    suspend fun setThemeMode(mode: Int) {
        _themeMode.value = mode
    }

    override suspend fun setRightHanded(isRight: Boolean) {
        TODO("Not yet implemented")
    }
}
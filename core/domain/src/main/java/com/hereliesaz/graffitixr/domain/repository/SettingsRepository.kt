package com.hereliesaz.graffitixr.domain.repository

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val isRightHanded: Flow<Boolean>
    suspend fun setRightHanded(isRight: Boolean)
}

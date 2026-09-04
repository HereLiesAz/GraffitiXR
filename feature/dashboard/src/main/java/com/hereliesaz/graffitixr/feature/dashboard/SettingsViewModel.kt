package com.hereliesaz.graffitixr.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.AppLanguage
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val language: StateFlow<AppLanguage> = settingsRepository.language
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppLanguage.SYSTEM)

    val crashReportingConsent: StateFlow<Boolean> = settingsRepository.crashReportingConsent
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    fun setCrashReportingConsent(on: Boolean) {
        viewModelScope.launch { settingsRepository.setCrashReportingConsent(on) }
    }

    fun setBackgroundColor(argb: Int) {
        viewModelScope.launch { settingsRepository.setBackgroundColor(argb) }
    }

    fun resetCompletedTutorials() {
        viewModelScope.launch { settingsRepository.clearCompletedTutorials() }
    }
}

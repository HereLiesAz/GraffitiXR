package com.hereliesaz.graffitixr

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class HelpViewModel : ViewModel() {
    private val _activeHelpList = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeHelpList: StateFlow<Map<String, String>> = _activeHelpList

    fun setActiveHelpList(helpList: Map<String, String>) {
        _activeHelpList.value = helpList
    }
}

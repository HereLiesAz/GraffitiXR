package com.hereliesaz.graffitixr

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class HelpViewModel : ViewModel() {
    private val _activeHelpList = MutableStateFlow<Map<String, Any>>(emptyMap())
    val activeHelpList: StateFlow<Map<String, Any>> = _activeHelpList

    fun setActiveHelpList(helpList: Map<String, Any>) {
        _activeHelpList.value = helpList
    }
}

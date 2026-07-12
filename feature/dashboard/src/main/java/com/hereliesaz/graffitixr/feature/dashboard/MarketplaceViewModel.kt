package com.hereliesaz.graffitixr.feature.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.data.azphalt.ExtensionRepository
import com.hereliesaz.graffitixr.data.azphalt.InstalledExtension
import com.hereliesaz.graffitixr.data.azphalt.MarketplaceEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the azphalt marketplace UI: the browse catalog, live installed-set, and install/uninstall.
 * The repository's filesystem is the source of truth for "installed"; this just surfaces it to Compose
 * and runs the blocking install IO off the main thread. Errors land in [status] as a one-shot message.
 */
@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val extensions: ExtensionRepository,
) : ViewModel() {

    val catalog: List<MarketplaceEntry> = extensions.catalog()

    val installed: StateFlow<List<InstalledExtension>> = extensions.installed

    private val _busyId = MutableStateFlow<String?>(null)
    /** Id of the entry currently installing/uninstalling, so the row can show progress and disable. */
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun isInstalled(id: String): Boolean = installed.value.any { it.id == id }

    fun install(entry: MarketplaceEntry) {
        if (_busyId.value != null) return
        _busyId.value = entry.id
        _status.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { extensions.install(entry, System.currentTimeMillis()) }
            }
            result.onSuccess { _status.value = "Installed ${entry.name}" }
                .onFailure { _status.value = "Install failed: ${it.message}" }
            _busyId.value = null
        }
    }

    fun uninstall(id: String) {
        if (_busyId.value != null) return
        _busyId.value = id
        viewModelScope.launch {
            withContext(Dispatchers.IO) { extensions.uninstall(id) }
            _busyId.value = null
        }
    }

    fun clearStatus() { _status.value = null }
}

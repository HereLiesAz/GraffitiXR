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

    private val _catalog = MutableStateFlow<List<MarketplaceEntry>>(emptyList())
    /** The store catalog: live registry results, or the bundled seed when [offline]. */
    val catalog: StateFlow<List<MarketplaceEntry>> = _catalog.asStateFlow()

    private val _offline = MutableStateFlow(false)
    /** True when [catalog] is the bundled seed because the registry was unreachable. */
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    val installed: StateFlow<List<InstalledExtension>> = extensions.installed

    init { refresh() }

    /** Fetch the catalog from the live registry (falling back to the seed). Safe to call repeatedly. */
    fun refresh(query: String? = null) {
        viewModelScope.launch {
            _loading.value = true
            val result = withContext(Dispatchers.IO) { extensions.browseCatalog(query) }
            _catalog.value = result.entries
            _offline.value = !result.isLive
            _loading.value = false
        }
    }

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
        _status.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { extensions.uninstall(id) }
            }
            result.onFailure { _status.value = "Uninstall failed: ${it.message}" }
            _busyId.value = null
        }
    }

    fun clearStatus() { _status.value = null }
}

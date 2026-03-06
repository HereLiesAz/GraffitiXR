package com.hereliesaz.graffitixr.feature.dashboard

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * ViewModel for the Dashboard feature, primarily managing the Project Library.
 * Handles loading, creating, deleting, and opening projects, as well as OTA updates.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _navigationTrigger = MutableStateFlow<String?>(null)
    val navigationTrigger: StateFlow<String?> = _navigationTrigger.asStateFlow()

    fun loadAvailableProjects() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val list = repository.getProjects()
            _uiState.update { it.copy(availableProjects = list, isLoading = false) }
        }
    }

    fun openProject(project: GraffitiProject) {
        viewModelScope.launch { repository.loadProject(project.id) }
    }

    fun onNewProject(isRightHanded: Boolean) {
        viewModelScope.launch {
            val p = repository.createProject("New Project")
            repository.updateProject(p.copy(isRightHanded = isRightHanded))
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                repository.deleteProject(projectId)
                loadAvailableProjects()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                android.util.Log.e("DashboardViewModel", "Error deleting project: $projectId", e)
            }
        }
    }

    fun navigateToSurveyor() { _navigationTrigger.value = "surveyor" }
    fun navigateToLibrary() { _navigationTrigger.value = "project_library" }
    fun navigateToSettings() { _navigationTrigger.value = "settings" }
    fun onNavigationConsumed() { _navigationTrigger.value = null }

    /**
     * Checks GitHub for the latest release and compares it to the installed version.
     */
    fun checkForUpdates(currentVersion: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingForUpdate = true, updateStatusMessage = "Checking for updates...") }
            try {
                val latestRelease = fetchLatestRelease()
                if (latestRelease == null) {
                    _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Could not connect to update server.") }
                    return@launch
                }

                val latestTag = latestRelease.tagName.removePrefix("v")
                if (isNewerVersion(latestTag, currentVersion)) {
                    val apkAsset = latestRelease.assets.firstOrNull { it.name.endsWith(".apk") }
                    _uiState.update {
                        it.copy(
                            isCheckingForUpdate = false,
                            updateStatusMessage = "New version $latestTag available",
                            pendingUpdateApkUrl = apkAsset?.browserDownloadUrl
                        )
                    }
                } else {
                    _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "You are on the latest experimental build.") }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.update { it.copy(isCheckingForUpdate = false, updateStatusMessage = "Update check failed.") }
            }
        }
    }

    /**
     * Initiates the APK download using Android's native DownloadManager.
     */
    fun installUpdate(context: Context) {
        val apkUrl = _uiState.value.pendingUpdateApkUrl ?: return
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("GraffitiXR Update")
            setDescription("Downloading latest experimental build...")
            setDestinationInExternalFilesDir(context, null, "update.apk")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        }
        val downloadId = downloadManager.enqueue(request)

        // Save ID so ApkInstallReceiver knows when our specific download completes
        context.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            .edit().putLong("update_download_id", downloadId).apply()

        _uiState.update { it.copy(updateStatusMessage = "Downloading update... Check notifications.") }
    }

    private suspend fun fetchLatestRelease(): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/hereliesaz/GraffitiXR/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000

                if (connection.responseCode != 200) return@withContext null

                val json = connection.inputStream.bufferedReader().readText()
                connection.disconnect()
                parseRelease(json)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseRelease(json: String): GitHubRelease? {
        return try {
            val tagName = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            val assetUrls = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(json)
                .map { it.groupValues[1] }
                .toList()
            val assetNames = Regex("\"name\"\\s*:\\s*\"([^\"]+\\.apk)\"")
                .findAll(json)
                .map { it.groupValues[1] }
                .toList()

            val assets = assetNames.zip(assetUrls).map { (name, url) -> GitHubAsset(name, url) }
            GitHubRelease(tagName, assets)
        } catch (e: Exception) { null }
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val l = parse(latest)
        val c = parse(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private data class GitHubRelease(val tagName: String, val assets: List<GitHubAsset>)
    private data class GitHubAsset(val name: String, val browserDownloadUrl: String)
}
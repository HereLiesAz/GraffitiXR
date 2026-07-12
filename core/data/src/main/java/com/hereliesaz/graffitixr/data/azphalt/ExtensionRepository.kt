package com.hereliesaz.graffitixr.data.azphalt

import android.content.Context
import com.hereliesaz.graffitixr.common.azphalt.AssetType
import com.hereliesaz.graffitixr.common.azphalt.CubeLut
import com.hereliesaz.graffitixr.common.azphalt.parseCubeLut
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The GraffitiXR side of the azphalt marketplace: browse the catalog, install `.azp` packages, track
 * what's installed, and hand installed asset extensions (LUTs) to the editor. The filesystem under
 * `filesDir/extensions/<id>/` IS the installed-state — [installed] is rebuilt by scanning it, so an
 * install/uninstall survives process death with no separate index.
 */
@Singleton
class ExtensionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val extensionsRoot = File(context.filesDir, "extensions")
    private val installer = AzpInstaller(extensionsRoot)

    /** Serializes filesystem-mutating operations so concurrent install/uninstall can't interleave. */
    private val lock = Any()

    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<List<InstalledExtension>> = _installed.asStateFlow()

    /** The catalog to browse. Bundled seed today; swap for the registry HTTP API when deployed. */
    fun catalog(): List<MarketplaceEntry> = SEED_MARKETPLACE

    fun isInstalled(id: String): Boolean = _installed.value.any { it.id == id }

    /**
     * Fetch, verify, and unpack the entry's `.azp`. Throws on any fetch/integrity/safety failure.
     * Runs blocking IO — call from a background dispatcher.
     */
    fun install(entry: MarketplaceEntry, nowMs: Long): InstalledExtension = synchronized(lock) {
        val installed = openSource(entry.source).use { input ->
            installer.install(input, nowMs)
        }
        _installed.value = scanInstalled()
        installed
    }

    fun uninstall(id: String) = synchronized(lock) {
        val ext = _installed.value.find { it.id == id } ?: return
        File(ext.dir).deleteRecursively()
        _installed.value = scanInstalled()
    }

    /** Installed LUT asset extensions, paired with their loadable [CubeLut] (parsed lazily on use). */
    fun installedLuts(): List<InstalledExtension> =
        _installed.value.filter { ext -> ext.manifest.assets.any { it.type == AssetType.LUT } }

    /** Load the first LUT of an installed extension, or null if it has none / fails to parse. */
    fun loadLut(id: String): CubeLut? {
        val ext = _installed.value.find { it.id == id } ?: return null
        val lutAsset = ext.manifest.assets.firstOrNull { it.type == AssetType.LUT } ?: return null
        val file = File(ext.filePath(lutAsset.path))
        if (!file.exists()) return null
        return runCatching { parseCubeLut(file.readText()) }.getOrNull()
    }

    private fun openSource(source: String) = when {
        source.startsWith("asset:") -> context.assets.open(source.removePrefix("asset:"))
        source.startsWith("http://") || source.startsWith("https://") ->
            URL(source).openStream()
        else -> throw AzpInstaller.InstallException("Unsupported source: $source")
    }

    private fun scanInstalled(): List<InstalledExtension> {
        val root = extensionsRoot
        if (!root.isDirectory) return emptyList()
        return root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val manifestFile = File(dir, "manifest.json")
            if (!manifestFile.exists()) return@mapNotNull null
            runCatching {
                InstalledExtension(
                    manifest = parseManifest(manifestFile.readText()),
                    dir = dir.absolutePath,
                    installedAt = manifestFile.lastModified(),
                )
            }.getOrNull()
        }.sortedBy { it.manifest.name }
    }
}

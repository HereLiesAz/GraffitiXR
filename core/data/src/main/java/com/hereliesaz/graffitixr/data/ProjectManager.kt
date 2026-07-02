package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
import com.hereliesaz.graffitixr.common.util.ImageUtils
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

interface UriProvider {
    fun getUriForFile(file: File): Uri
}

class DefaultUriProvider @Inject constructor() : UriProvider {
    override fun getUriForFile(file: File): Uri {
        return Uri.fromFile(file)
    }
}

@Singleton
class ProjectManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val uriProvider: UriProvider,
    private val projectRepositoryProvider: Provider<ProjectRepository>
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        /**
         * Cap on total decompressed bytes accepted from an imported/peer-received `.gxr` archive.
         * Both sources are untrusted (a shared file, or the co-op wire), so a zip bomb must not
         * be able to fill the app sandbox. Generous vs. real projects (multi-layer PNGs).
         */
        private const val MAX_IMPORT_BYTES = 512L * 1024 * 1024
    }

    fun getProjectList(context: Context): List<String> {
        val projectsDir = File(context.filesDir, "projects")
        if (!projectsDir.exists()) return emptyList()
        return projectsDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
    }

    fun deleteProject(context: Context, projectName: String) {
        val projectDir = File(context.filesDir, "projects/$projectName")
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    fun getMapPath(context: Context, projectId: String): String {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return File(root, "map.bin").absolutePath
    }

    /**
     * Returns the path to the auxiliary mesh persistence file used by SurfaceMesh.
     */
    fun getMeshPath(context: Context, projectId: String): String {
        return getMapPath(context, projectId) + ".mesh"
    }

    fun getCloudPointsPath(context: Context, projectId: String): String {
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.exists()) root.mkdirs()
        return File(root, "cloud_points.bin").absolutePath
    }

    suspend fun saveProject(context: Context, projectData: GraffitiProject, targetImages: List<Bitmap>? = null, thumbnail: Bitmap? = null) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/${projectData.id}")
        if (!root.exists()) root.mkdirs()

        // The target fingerprint must survive EVERY routine save (layer edits, autosave, re-entering
        // AR, etc.) and change only when the user creates a NEW target — which is the one path that
        // passes a non-null fingerprint. So when the incoming project carries no fingerprint, carry
        // over whatever is already persisted instead of nulling it. This makes it impossible for any
        // other writer (or a stale-snapshot race) to wipe the saved target.
        val incoming = if (projectData.fingerprint == null) {
            val existing = try {
                val f = File(root, "project.json")
                if (f.exists()) json.decodeFromString<GraffitiProject>(f.readText()) else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow cancellation
            } catch (e: Exception) {
                null
            }
            if (existing != null) {
                projectData.copy(
                    fingerprint = existing.fingerprint,
                    fingerprintIntrinsics = existing.fingerprintIntrinsics,
                    fingerprintAnchor = existing.fingerprintAnchor,
                    // Preserve the legacy target-fingerprint references too, so no save without them
                    // can wipe an existing target.
                    targetFingerprint = projectData.targetFingerprint ?: existing.targetFingerprint,
                    targetFingerprintPath = projectData.targetFingerprintPath ?: existing.targetFingerprintPath,
                )
            } else projectData
        } else projectData

        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            FileOutputStream(file).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 80, out)
            }
            uriProvider.getUriForFile(file)
        } else {
            incoming.thumbnailUri ?: run {
                val file = File(root, "thumbnail.png")
                if (file.exists()) uriProvider.getUriForFile(file) else null
            }
        }

        // Properly append new targets to the existing list
        val savedTargetUris = if (targetImages != null) {
            val existingCount = incoming.targetImageUris.size
            val newUris = targetImages.mapIndexed { index, bitmap ->
                val file = File(root, "target_${existingCount + index}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                uriProvider.getUriForFile(file)
            }
            incoming.targetImageUris + newUris
        } else {
            incoming.targetImageUris
        }

        val updatedGraffitiProject = incoming.copy(
            thumbnailUri = thumbnailUri,
            targetImageUris = savedTargetUris,
            lastModified = System.currentTimeMillis()
        )

        val jsonString = json.encodeToString(updatedGraffitiProject)
        atomicWriteText(File(root, "project.json"), jsonString)
    }

    /**
     * Writes [text] to [target] atomically: stream into a sibling temp file then rename
     * over the target, so a crash/kill/IO-error mid-write can never leave a truncated
     * project.json that would fail to parse and silently drop the project on next load.
     */
    private fun atomicWriteText(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // Some filesystems won't rename onto an existing file; replace explicitly.
            target.delete()
            if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
        }
    }

    suspend fun loadProject(context: Context, projectId: String): LoadedProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val decoded = json.decodeFromString<GraffitiProject>(jsonString)
            val projectData = migrateInMemory(decoded)
            if (projectData !== decoded) {
                // Persist the migration only on a full load — loadProjectMetadata stays read-only.
                saveProject(context, projectData)
                Log.i("ProjectManager", "Migrated legacyVisuals for project ${projectData.id}")
            }

            val targetBitmaps = projectData.targetImageUris.mapNotNull { uri ->
                ImageUtils.loadBitmapSync(context, uri)
            }

            LoadedProject(projectData, targetBitmaps)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project", e)
            null
        }
    }

    suspend fun loadProjectMetadata(context: Context, projectId: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val project = json.decodeFromString<GraffitiProject>(jsonString)
            // In-memory migration only: this is a read path (dashboard listing) and must not
            // write to disk — the persisted migration happens in loadProject.
            migrateInMemory(project)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            null
        }
    }

    /**
     * Applies the legacyVisuals→layers migration purely in memory. Returns [project] itself
     * (same reference) when no migration is needed, so callers can detect change by identity
     * and decide whether to persist.
     */
    private fun migrateInMemory(project: GraffitiProject): GraffitiProject {
        val lv = project.legacyVisuals
        val defaults = LegacyVisuals()
        if (lv == defaults) return project

        val migratedLayers: List<OverlayLayer> = when {
            project.layers.isEmpty() && project.overlayImageUri != null -> {
                val uri = project.overlayImageUri!!
                listOf(
                    OverlayLayer(
                        uri = uri,
                        name = "Overlay",
                        scale = lv.scale,
                        offset = lv.offset,
                        rotationX = lv.rotationX,
                        rotationY = lv.rotationY,
                        rotationZ = lv.rotationZ,
                        opacity = lv.opacity,
                        blendMode = lv.blendMode.toModelBlendMode(),
                        brightness = lv.brightness,
                        contrast = lv.contrast,
                        saturation = lv.saturation,
                        colorBalanceR = lv.colorBalanceR,
                        colorBalanceG = lv.colorBalanceG,
                        colorBalanceB = lv.colorBalanceB
                    )
                )
            }
            project.layers.isNotEmpty() && project.layers.first().hasDefaultVisuals() -> {
                val first = project.layers.first().copy(
                    scale = lv.scale,
                    offset = lv.offset,
                    rotationX = lv.rotationX,
                    rotationY = lv.rotationY,
                    rotationZ = lv.rotationZ,
                    opacity = lv.opacity,
                    blendMode = lv.blendMode.toModelBlendMode(),
                    brightness = lv.brightness,
                    contrast = lv.contrast,
                    saturation = lv.saturation,
                    colorBalanceR = lv.colorBalanceR,
                    colorBalanceG = lv.colorBalanceG,
                    colorBalanceB = lv.colorBalanceB
                )
                listOf(first) + project.layers.drop(1)
            }
            else -> project.layers
        }

        return project.copy(layers = migratedLayers, legacyVisuals = defaults)
    }

    private fun OverlayLayer.hasDefaultVisuals(): Boolean {
        return scale == 1f && offset == Offset.Zero && rotationX == 0f && rotationY == 0f && rotationZ == 0f &&
                opacity == 1f && blendMode == ModelBlendMode.SrcOver && brightness == 0f && contrast == 1f &&
                saturation == 1f && colorBalanceR == 1f && colorBalanceG == 1f && colorBalanceB == 1f
    }

    fun exportProjectToUri(context: Context, projectId: String, uri: Uri) {
        val sourceFolder = File(context.filesDir, "projects/$projectId")
        if (!sourceFolder.exists()) return

        try {
            context.contentResolver.openOutputStream(uri)?.use { os ->
                ZipOutputStream(os).use { zos ->
                    // Use empty string for parent to zip contents directly into the root.
                    zipFolder(sourceFolder, "", zos)
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectManager", "Export failed", e)
        }
    }

    suspend fun importProjectFromUri(context: Context, uri: Uri): GraffitiProject? = withContext(Dispatchers.IO) {
        var extractedFiles: Map<String, File> = emptyMap()
        return@withContext try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var projectData: GraffitiProject? = null
                    val extracted = mutableMapOf<String, File>()
                    extractedFiles = extracted
                    var totalBytes = 0L

                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val relativeName = if (name.contains('/')) name.substringAfter('/') else name

                        if (!entry.isDirectory && relativeName.isNotEmpty()) {
                            val bytes = zis.readBytes()
                            totalBytes += bytes.size
                            if (totalBytes > MAX_IMPORT_BYTES) {
                                Log.e("ProjectManager", "Import aborted: archive exceeds $MAX_IMPORT_BYTES bytes")
                                return@use null
                            }
                            if (relativeName == "project.json") {
                                try {
                                    projectData = json.decodeFromString<GraffitiProject>(bytes.decodeToString())
                                } catch (e: Exception) {
                                    Log.e("ProjectManager", "Failed to parse project.json", e)
                                }
                            }
                            extracted[relativeName] = File.createTempFile("gxr_", null, context.cacheDir).also {
                                it.writeBytes(bytes)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    val project = projectData ?: return@use null
                    // project.id comes from the (untrusted) archive and becomes a path segment.
                    if (!isSafeProjectId(project.id)) {
                        Log.e("ProjectManager", "Import rejected: unsafe project id")
                        return@use null
                    }
                    val destDir = File(context.filesDir, "projects/${project.id}").also { it.mkdirs() }

                    for ((name, tmpFile) in extracted) {
                        // Zip-Slip guard: entry names are attacker-controlled and may contain
                        // ".." components that escape destDir.
                        val dest = resolveInside(destDir, name)
                        if (dest == null) {
                            Log.w("ProjectManager", "Skipping zip entry escaping project dir: $name")
                            tmpFile.delete()
                            continue
                        }
                        dest.parentFile?.mkdirs()
                        // renameTo can silently fail across filesystems (cache vs files dir) or when
                        // the destination exists — fall back to an explicit copy so an imported
                        // project is never left with missing files.
                        if (dest.exists()) dest.delete()
                        if (!tmpFile.renameTo(dest)) {
                            tmpFile.copyTo(dest, overwrite = true)
                            tmpFile.delete()
                        }
                    }

                    project
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectManager", "Import failed", e)
            null
        } finally {
            // Temp files are only renamed away on the success path; clear any stragglers.
            extractedFiles.values.forEach { if (it.exists()) it.delete() }
        }
    }

    /**
     * Resolves [entryName] to a file under [destDir], or null if the name (via ".." components,
     * absolute paths, etc.) would escape it — the Zip-Slip attack. Canonical-path prefix check.
     */
    private fun resolveInside(destDir: File, entryName: String): File? {
        val candidate = File(destDir, entryName)
        val canonical = candidate.canonicalPath
        return if (canonical.startsWith(destDir.canonicalPath + File.separator)) candidate else null
    }

    /**
     * Project ids become a path segment under filesDir/projects; ids parsed out of imported or
     * peer-received archives must not be able to traverse out of it.
     */
    private fun isSafeProjectId(id: String): Boolean =
        id.isNotEmpty() && id.length <= 128 && id.all { it.isLetterOrDigit() || it == '_' || it == '-' }

    private fun ComposeBlendMode.toModelBlendMode(): ModelBlendMode = when (this) {
        ComposeBlendMode.Multiply   -> ModelBlendMode.Multiply
        ComposeBlendMode.Screen     -> ModelBlendMode.Screen
        ComposeBlendMode.Overlay    -> ModelBlendMode.Overlay
        ComposeBlendMode.Darken     -> ModelBlendMode.Darken
        ComposeBlendMode.Lighten    -> ModelBlendMode.Lighten
        ComposeBlendMode.ColorDodge -> ModelBlendMode.ColorDodge
        ComposeBlendMode.ColorBurn  -> ModelBlendMode.ColorBurn
        ComposeBlendMode.Hardlight  -> ModelBlendMode.HardLight
        ComposeBlendMode.Softlight  -> ModelBlendMode.SoftLight
        ComposeBlendMode.Difference -> ModelBlendMode.Difference
        ComposeBlendMode.Exclusion  -> ModelBlendMode.Exclusion
        ComposeBlendMode.Hue        -> ModelBlendMode.Hue
        ComposeBlendMode.Saturation -> ModelBlendMode.Saturation
        ComposeBlendMode.Color      -> ModelBlendMode.Color
        ComposeBlendMode.Luminosity -> ModelBlendMode.Luminosity
        ComposeBlendMode.Clear      -> ModelBlendMode.Clear
        ComposeBlendMode.Src        -> ModelBlendMode.Src
        ComposeBlendMode.Dst        -> ModelBlendMode.Dst
        ComposeBlendMode.DstOver    -> ModelBlendMode.DstOver
        ComposeBlendMode.SrcIn      -> ModelBlendMode.SrcIn
        ComposeBlendMode.DstIn      -> ModelBlendMode.DstIn
        ComposeBlendMode.SrcOut     -> ModelBlendMode.SrcOut
        ComposeBlendMode.DstOut     -> ModelBlendMode.DstOut
        ComposeBlendMode.SrcAtop    -> ModelBlendMode.SrcAtop
        ComposeBlendMode.DstAtop    -> ModelBlendMode.DstAtop
        ComposeBlendMode.Xor        -> ModelBlendMode.Xor
        ComposeBlendMode.Plus       -> ModelBlendMode.Plus
        ComposeBlendMode.Modulate   -> ModelBlendMode.Modulate
        else                        -> ModelBlendMode.SrcOver
    }

    // --- Co-op implementation (Task 17) ---

    /**
     * Returns the ID of the currently open project, or "unknown" if none is loaded.
     */
    fun currentProjectId(): String = projectRepositoryProvider.get().currentProject.value?.id ?: "unknown"

    /**
     * Serialises the current project to bytes for bulk-transfer to a guest device.
     */
    fun serializeCurrentProject(): ByteArray {
        val project = projectRepositoryProvider.get().currentProject.value ?: return ByteArray(0)
        val sourceFolder = File(context.filesDir, "projects/${project.id}")
        if (!sourceFolder.exists()) return ByteArray(0)

        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zipFolder(sourceFolder, "", zos)
            }
            baos.toByteArray()
        }
    }

    /**
     * Loads a project received as raw bytes from a host device (spectator/guest path).
     */
    suspend fun loadAsSpectator(bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext

        run {
            try {
                ZipInputStream(bytes.inputStream()).use { zis ->
                    var projectData: GraffitiProject? = null
                    val extractedFiles = mutableMapOf<String, ByteArray>()
                    var totalBytes = 0L

                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name.isNotEmpty()) {
                            val fileBytes = zis.readBytes()
                            totalBytes += fileBytes.size
                            if (totalBytes > MAX_IMPORT_BYTES) {
                                Log.e("ProjectManager", "Spectator load aborted: archive exceeds $MAX_IMPORT_BYTES bytes")
                                return@use
                            }
                            if (name == "project.json") {
                                projectData = json.decodeFromString<GraffitiProject>(fileBytes.decodeToString())
                            }
                            extractedFiles[name] = fileBytes
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }

                    val project = projectData ?: return@use
                    // The archive arrived over the co-op wire — id and entry names are untrusted.
                    if (!isSafeProjectId(project.id)) {
                        Log.e("ProjectManager", "Spectator load rejected: unsafe project id")
                        return@use
                    }
                    val destDir = File(context.filesDir, "projects/${project.id}").also { it.mkdirs() }

                    for ((name, fileBytes) in extractedFiles) {
                        val dest = resolveInside(destDir, name)
                        if (dest == null) {
                            Log.w("ProjectManager", "Skipping zip entry escaping project dir: $name")
                            continue
                        }
                        dest.parentFile?.mkdirs()
                        dest.writeBytes(fileBytes)
                    }

                    withContext(Dispatchers.Main) {
                        projectRepositoryProvider.get().createProject(project)
                    }
                }
            } catch (e: Exception) {
                Log.e("ProjectManager", "loadAsSpectator failed", e)
            }
        }
    }

    // --- End co-op implementation ---

    private fun zipFolder(folder: File, parentFolder: String, zos: ZipOutputStream) {
        for (file in folder.listFiles() ?: emptyArray()) {
            // Use relative path from the source folder to avoid nested parent directories in the ZIP.
            val zipPath = if (parentFolder.isEmpty()) file.name else "$parentFolder/${file.name}"
            if (file.isDirectory) {
                zipFolder(file, zipPath, zos)
            } else {
                val entry = ZipEntry(zipPath)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}
package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import com.hereliesaz.graffitixr.common.model.*
import com.hereliesaz.graffitixr.common.model.BlendMode as ModelBlendMode
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
import java.util.UUID
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
    @param:ApplicationContext private val appContext: Context,
    private val uriProvider: UriProvider,
    private val projectRepositoryProvider: Provider<ProjectRepository>
) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        // CaptureEnvironment's attitude/location groups use Float.NaN/Double.NaN as the documented
        // "not measured" sentinel (azimuthDeg, bearingDeg, speedMps, altitudeM, ...) and those fields
        // are genuinely NaN at runtime whenever a capture has, say, a GPS fix but no bearing (a user
        // standing still). kotlinx.serialization throws by default on encoding a non-finite float —
        // without this flag, saving that ordinary project crashes instead of persisting.
        allowSpecialFloatingPointValues = true
    }

    companion object {
        private const val SPECTATOR_PREFIX = "coop_"
        /**
         * Cap on total decompressed bytes accepted from an imported/peer-received `.gxr` archive.
         * Both sources are untrusted (a shared file, or the co-op wire), so a zip bomb must not
         * be able to fill the app sandbox. Generous vs. real projects (multi-layer PNGs).
         */
        private const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

        /**
         * Cap on how many captured target images a single project keeps, enforced by
         * [pruneTargetImages]. Nothing previously pruned this list, so repeated re-captures could grow
         * it, and its on-disk PNGs, without bound short of deleting the whole project. No existing
         * precedent in this codebase for the right number, so 30 is chosen as generous headroom for a
         * real capture session while still bounding worst-case storage to a few dozen PNGs.
         *
         * Note: the live capture flow appends via [appendTargetImage], which does its own pruning and
         * never routes through [saveProject]'s `targetImages` parameter — this cap/path is exercised by
         * tests and direct API use, not production captures.
         */
        private const val MAX_TARGET_IMAGES = 30
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

    suspend fun saveProject(
        context: Context,
        projectData: GraffitiProject,
        targetImages: List<Bitmap>? = null,
        thumbnail: Bitmap? = null,
    ) = saveProjectInternal(
        context = context,
        projectData = projectData,
        targetImages = targetImages,
        thumbnail = thumbnail,
        preserveExistingCaptureState = true,
    )

    /**
     * Persist an authoritative read-modify-write snapshot exactly as supplied.
     *
     * [saveProject] intentionally preserves capture/target fields when an older whole-object writer
     * omits them. That legacy safety net must NOT run for ProjectRepository's mutex-serialized
     * transform updates: null can be an explicit state transition (for example, clearing a missing
     * SphereSLAM reference), and resurrecting the old on-disk value makes memory and project.json
     * disagree. The repository uses this exact path only after transforming its current state under
     * the save mutex.
     */
    suspend fun saveProjectExact(
        context: Context,
        projectData: GraffitiProject,
    ) = saveProjectInternal(
        context = context,
        projectData = projectData,
        targetImages = null,
        thumbnail = null,
        preserveExistingCaptureState = false,
    )

    private suspend fun saveProjectInternal(
        context: Context,
        projectData: GraffitiProject,
        targetImages: List<Bitmap>?,
        thumbnail: Bitmap?,
        preserveExistingCaptureState: Boolean,
    ) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/${projectData.id}")
        if (!root.exists()) root.mkdirs()

        // The target fingerprint must survive EVERY routine save (layer edits, autosave, re-entering
        // AR, etc.) and change only when the user creates a NEW target — which is the one path that
        // passes a non-null fingerprint. So when the incoming project carries no fingerprint, carry
        // over whatever is already persisted instead of nulling it. This makes it impossible for any
        // other writer (or a stale-snapshot race) to wipe the saved target — the view matrix and
        // capture rotation are captured alongside the fingerprint itself, so they follow the same
        // unconditional preserve-from-existing as the fingerprint/intrinsics/anchor; the wall feature
        // map and cloud anchor id can each legitimately be set on a routine (non-fingerprint) save
        // (e.g. the passive wall-map save), so those instead only fall back to the on-disk value when
        // the incoming project doesn't set one, same as the legacy target-fingerprint references.
        val incoming = if (preserveExistingCaptureState && projectData.fingerprint == null) {
            val existing = try {
                val f = File(root, "project.json")
                if (f.exists()) json.decodeFromString<GraffitiProject>(f.readText()) else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow cancellation
            } catch (_: Exception) {
                null
            }
            if (existing != null) {
                projectData.copy(
                    fingerprint = existing.fingerprint,
                    fingerprintIntrinsics = existing.fingerprintIntrinsics,
                    fingerprintAnchor = existing.fingerprintAnchor,
                    fingerprintViewMatrix = existing.fingerprintViewMatrix,
                    fingerprintCaptureRotationDeg = existing.fingerprintCaptureRotationDeg,
                    // Preserve the legacy target-fingerprint references too, so no save without them
                    // can wipe an existing target.
                    targetFingerprint = projectData.targetFingerprint ?: existing.targetFingerprint,
                    targetFingerprintPath = projectData.targetFingerprintPath ?: existing.targetFingerprintPath,
                    // Standalone SphereSLAM's canonical wall page is just as persistent as the
                    // fingerprint: routine/stale saves must not silently erase it.
                    sphereSlamReferenceUri =
                        projectData.sphereSlamReferenceUri ?: existing.sphereSlamReferenceUri,
                    sphereSlamReferenceWidthMeters =
                        if (projectData.sphereSlamReferenceUri != null)
                            projectData.sphereSlamReferenceWidthMeters
                        else existing.sphereSlamReferenceWidthMeters,
                    sphereSlamReferencePhysicallyMetric =
                        if (projectData.sphereSlamReferenceUri != null)
                            projectData.sphereSlamReferencePhysicallyMetric
                        else existing.sphereSlamReferencePhysicallyMetric,
                    sphereSlamAnchorGeneration =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamAnchorGeneration
                        } else {
                            existing.sphereSlamAnchorGeneration
                        },
                    sphereSlamAnchorFrameVersion =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamAnchorFrameVersion
                        } else {
                            existing.sphereSlamAnchorFrameVersion
                        },
                    sphereSlamPlacementAnchorGeneration =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamPlacementAnchorGeneration
                        } else {
                            existing.sphereSlamPlacementAnchorGeneration
                        },
                    sphereSlamModeAdjustment =
                        projectData.sphereSlamModeAdjustment ?: existing.sphereSlamModeAdjustment,
                    // A new canonical page defines a new standalone object frame. Preserve an old
                    // seed only while the canonical reference URI itself is unchanged.
                    sphereSlamFingerprint =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamFingerprint
                        } else {
                            projectData.sphereSlamFingerprint ?: existing.sphereSlamFingerprint
                        },
                    sphereSlamFingerprintFrameVersion =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamFingerprintFrameVersion
                        } else {
                            existing.sphereSlamFingerprintFrameVersion
                        },
                    sphereSlamMapFromFingerprint =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamMapFromFingerprint
                        } else if (projectData.sphereSlamMapFromFingerprint.size == 16) {
                            projectData.sphereSlamMapFromFingerprint
                        } else {
                            existing.sphereSlamMapFromFingerprint
                        },
                    // The standalone wide-wall map is expressed in the SAME centred page frame as
                    // sphereSlamFingerprint. A new canonical page must never inherit the old map.
                    sphereSlamWallFeatureMap =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamWallFeatureMap
                        } else {
                            projectData.sphereSlamWallFeatureMap ?: existing.sphereSlamWallFeatureMap
                        },
                    sphereSlamWallFeatureMapFrameVersion =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamWallFeatureMapFrameVersion
                        } else {
                            existing.sphereSlamWallFeatureMapFrameVersion
                        },
                    sphereSlamAtlasPages =
                        if (
                            projectData.sphereSlamReferenceUri != null &&
                            projectData.sphereSlamReferenceUri != existing.sphereSlamReferenceUri
                        ) {
                            projectData.sphereSlamAtlasPages
                        } else if (projectData.sphereSlamAtlasPages.isNotEmpty()) {
                            projectData.sphereSlamAtlasPages
                        } else {
                            existing.sphereSlamAtlasPages
                        },
                    wallFeatureMap = projectData.wallFeatureMap ?: existing.wallFeatureMap,
                    // Artist-measured; a stale whole-object writer must not erase it. A deliberate
                    // clear goes through updateProject (saveProjectExact), which bypasses this merge.
                    wallWidthMeters = projectData.wallWidthMeters ?: existing.wallWidthMeters,
                    paintMarks = projectData.paintMarks ?: existing.paintMarks,
                    paintGrid = projectData.paintGrid ?: existing.paintGrid,
                    cloudAnchorId = projectData.cloudAnchorId ?: existing.cloudAnchorId,
                    // Written once by the AR placement; a stale snapshot must not reset it.
                    arDesignHalfWidthM = if (projectData.arDesignHalfWidthM > 0f) projectData.arDesignHalfWidthM
                        else existing.arDesignHalfWidthM,
                )
            } else projectData
        } else projectData

        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            FileOutputStream(file).use { out ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            uriProvider.getUriForFile(file)
        } else {
            incoming.thumbnailUri ?: run {
                val file = File(root, "thumbnail.png")
                if (file.exists()) uriProvider.getUriForFile(file) else null
            }
        }

        // Properly append new targets to the existing list, then prune to MAX_TARGET_IMAGES so
        // repeated captures can't grow this list (and its on-disk PNGs) without bound. Filenames are
        // unique (not size-indexed): once the list is pruned to the cap its size stops growing, so an
        // index derived from it would collide with — and silently overwrite — a still-kept file.
        val savedTargetUris = if (targetImages != null) {
            val newUris = targetImages.map { bitmap ->
                val file = File.createTempFile("target_", ".png", root)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                uriProvider.getUriForFile(file)
            }
            pruneTargetImages(incoming.targetImageUris + newUris)
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
     * Throws rather than silently falling back to a non-atomic direct write if the rename
     * can't be made to succeed.
     */
    private fun atomicWriteText(target: File, text: String) {
        val tmp = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            tmp.writeText(text)
            // Some filesystems won't rename onto an existing file on the first try; retry a
            // couple times before giving up, rather than falling back to a direct write onto
            // target, which a crash mid-write could leave truncated.
            var renamed = tmp.renameTo(target)
            var attempt = 0
            while (!renamed && attempt < 2) {
                attempt++
                renamed = tmp.renameTo(target)
            }
            check(renamed) { "Could not atomically replace ${target.name}" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Drops the oldest entries once [uris] exceeds [MAX_TARGET_IMAGES], deleting their backing files
     * so a pruned entry doesn't leak a PNG that nothing references any more.
     */
    private fun pruneTargetImages(uris: List<Uri>): List<Uri> {
        if (uris.size <= MAX_TARGET_IMAGES) return uris
        val overflow = uris.size - MAX_TARGET_IMAGES
        uris.take(overflow).forEach { uri ->
            try {
                uri.path?.let { File(it).delete() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: a failed delete just leaves an orphaned file, not a correctness issue.
            }
        }
        return uris.drop(overflow)
    }

    /**
     * Writes a new rectified wall page for standalone SphereSLAM and returns its URI.
     *
     * Each successful write gets a unique final filename. The caller commits that URI together with
     * its scale metadata through ProjectRepository.updateProject(), then deletes the previously
     * referenced file. Versioning is intentional: overwriting a single canonical PNG before
     * project.json commits can leave new pixels paired with old scale metadata after process death.
     */
    suspend fun saveSphereSlamReference(
        context: Context,
        projectId: String,
        bitmap: Bitmap,
    ): Uri = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId").also { if (!it.exists()) it.mkdirs() }
        val target = File(root, "sphereslam_reference_${UUID.randomUUID()}.png")
        val tmp = File.createTempFile("sphereslam_reference_", ".tmp", root)
        try {
            FileOutputStream(tmp).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not encode SphereSLAM reference image"
                }
                out.flush()
                out.fd.sync()
            }
            check(tmp.renameTo(target)) { "Could not install SphereSLAM reference image" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        uriProvider.getUriForFile(target)
    }

    /**
     * Best-effort deletion for a standalone SphereSLAM reference owned by [projectId].
     *
     * Accept the old fixed canonical filename for cleanup/migration as well as the current
     * versioned names, but never delete a path outside this project directory.
     */
    suspend fun deleteSphereSlamReference(
        context: Context,
        projectId: String,
        uri: Uri?,
    ) = withContext(Dispatchers.IO) {
        val path = uri?.path ?: return@withContext
        val root = File(context.filesDir, "projects/$projectId").canonicalFile
        val file = File(path).canonicalFile
        val validName =
            file.name == "sphereslam_reference.png" ||
                (file.name.startsWith("sphereslam_reference_") && file.extension == "png")
        if (file.parentFile == root && validName) {
            runCatching { file.delete() }
        }
    }

    /** Write one additional rectified KPM page without changing canonical page 0. */
    suspend fun saveSphereSlamAtlasPage(
        context: Context,
        projectId: String,
        pageNo: Int,
        bitmap: Bitmap,
    ): Uri = withContext(Dispatchers.IO) {
        require(pageNo > 0)
        val root = File(context.filesDir, "projects/$projectId").also { if (!it.exists()) it.mkdirs() }
        val target = File(root, "sphereslam_page_${pageNo}_${UUID.randomUUID()}.png")
        val tmp = File.createTempFile("sphereslam_page_${pageNo}_", ".tmp", root)
        try {
            FileOutputStream(tmp).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Could not encode SphereSLAM atlas page"
                }
                out.flush()
                out.fd.sync()
            }
            check(tmp.renameTo(target)) { "Could not install SphereSLAM atlas page" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        uriProvider.getUriForFile(target)
    }

    suspend fun deleteSphereSlamAtlasPage(
        context: Context,
        projectId: String,
        uri: Uri?,
    ) = withContext(Dispatchers.IO) {
        val path = uri?.path ?: return@withContext
        val root = File(context.filesDir, "projects/$projectId").canonicalFile
        val file = File(path).canonicalFile
        val validName = file.name.startsWith("sphereslam_page_") && file.extension == "png"
        if (file.parentFile == root && validName) runCatching { file.delete() }
    }

    /**
     * Writes [bitmap] to disk as the next target image for [projectId] and returns the resulting
     * (pruned) URI list — pure file IO, does NOT touch project.json. Callers own folding the result
     * into the project (e.g. via [ProjectRepository.updateProject]'s atomic transform) so this can be
     * used by writers that must not perform a whole-object [saveProject] (see MainViewModel's target
     * capture paths, which used to call [saveProject] directly and could silently clobber a concurrent
     * AR wall-map or editor design save).
     */
    suspend fun appendTargetImage(
        context: Context,
        projectId: String,
        existingUris: List<Uri>,
        bitmap: Bitmap,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId").also { if (!it.exists()) it.mkdirs() }
        // Unique filename, not size-indexed — see the comment in saveProject's target-image block for
        // why an index derived from (possibly already-pruned) list size would collide.
        val file = File.createTempFile("target_", ".png", root)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        pruneTargetImages(existingUris + uriProvider.getUriForFile(file))
    }

    /**
     * Loads a project's metadata, applying [migrateInMemory] purely in memory.
     *
     * This used to be two functions: this read-only one (used everywhere — dashboard listing, repo
     * `getProject`/`loadProject`, `updateTargetFingerprint`) plus a `loadProject(): LoadedProject?`
     * that additionally loaded every target-image bitmap and — its one distinguishing behavior —
     * persisted the migration back to project.json. That second function had no caller anywhere in
     * the codebase (nothing ever consumed a `LoadedProject`'s bitmaps either), so the "persist on
     * full load" behavior a couple of old comments described never actually happened: every real load
     * went through this function, which was always read-only. Migrations stay in-memory-only and are
     * re-applied (cheaply — a couple of reference/equality checks) on every load; the dead function
     * and its now-inaccurate comments have been removed rather than wired up, since nothing in the
     * app needs pre-loaded target-image bitmaps and re-migrating on every read is not a real cost.
     */
    suspend fun loadProjectMetadata(context: Context, projectId: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val project = json.decodeFromString<GraffitiProject>(jsonString)
            migrateInMemory(project)
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            null
        }
    }

    /**
     * Brings an on-disk project up to the current shape, purely in memory. Returns [project] itself
     * (same reference) when nothing needed migrating, so callers can detect change by identity and
     * decide whether to persist.
     *
     * Two generations of migration now. The older one folds `legacyVisuals` (a single image's
     * transform and tone, from before layers existed) onto the image itself. The newer one collapses
     * the layer LIST back to one `design`, because multilayer editing moved to the companion design
     * app — the first layer wins, which is the one the artist was placing.
     */
    private fun migrateInMemory(project: GraffitiProject): GraffitiProject {
        val collapsed = when {
            project.design != null -> project
            project.layers.isNotEmpty() -> project.copy(design = project.layers.first(), layers = emptyList())
            else -> project
        }
        val migrated = migrateLegacyVisuals(collapsed)
        return if (migrated === collapsed && collapsed === project) project else migrated
    }

    private fun migrateLegacyVisuals(project: GraffitiProject): GraffitiProject {
        val lv = project.legacyVisuals
        val defaults = LegacyVisuals()
        if (lv == defaults) return project

        val migratedDesign: OverlayLayer? = when {
            project.design == null && project.overlayImageUri != null -> {
                val uri = project.overlayImageUri!!
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
            }
            project.design?.hasDefaultVisuals() == true -> {
                project.design!!.copy(
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
            }
            else -> project.design
        }

        return project.copy(design = migratedDesign, legacyVisuals = defaults)
    }

    private fun OverlayLayer.hasDefaultVisuals(): Boolean {
        return scale == 1f && offset == Offset.Zero && rotationX == 0f && rotationY == 0f && rotationZ == 0f &&
                opacity == 1f && blendMode == ModelBlendMode.SrcOver && brightness == 0f && contrast == 1f &&
                saturation == 1f && colorBalanceR == 1f && colorBalanceG == 1f && colorBalanceB == 1f
    }

    /**
     * Zips the project folder into [uri]. Returns true only when the whole archive was written: a
     * failure mid-write still closes the zip (leaving a readable but incomplete archive), so callers
     * must not share [uri]'s contents unless this returns true.
     */
    fun exportProjectToUri(context: Context, projectId: String, uri: Uri): Boolean {
        val sourceFolder = File(context.filesDir, "projects/$projectId")
        if (!sourceFolder.exists()) return false

        return try {
            val os = context.contentResolver.openOutputStream(uri) ?: return false
            os.use {
                ZipOutputStream(it).use { zos ->
                    // Use empty string for parent to zip contents directly into the root.
                    zipFolder(sourceFolder, "", zos)
                }
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ProjectManager", "Export failed", e)
            false
        }
    }

    /**
     * Streams the current ZIP entry straight to a fresh temp file under [cacheDir], bounding the
     * CUMULATIVE decompressed size: aborts (deletes the temp file, returns null) the moment
     * [runningTotal] + this entry would exceed [MAX_IMPORT_BYTES].
     *
     * Writing to disk as we go — rather than buffering the whole entry in memory first and checking
     * the cap afterwards — is what makes the cap actually protective: a heap `ByteArrayOutputStream`
     * doubles its backing array as it grows, so a single entry a few hundred MB under the cap could
     * OOM a typical Android heap long before this function's own size check ever ran. The chunk size
     * (64 KiB) bounds how much of any one entry is ever in memory at once, regardless of the entry's
     * total (possibly cap-sized) length.
     *
     * Returns (tempFile, newRunningTotal), or null once the cap is exceeded.
     */
    private fun streamEntryBounded(zis: ZipInputStream, runningTotal: Long, cacheDir: File): Pair<File, Long>? {
        val tmp = File.createTempFile("gxr_", null, cacheDir)
        val chunk = ByteArray(64 * 1024)
        var total = runningTotal
        var exceeded = false
        FileOutputStream(tmp).use { out ->
            while (true) {
                val n = zis.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_IMPORT_BYTES) {
                    exceeded = true
                    break
                }
                out.write(chunk, 0, n)
            }
        }
        if (exceeded) {
            tmp.delete()
            return null
        }
        return tmp to total
    }

    suspend fun importProjectFromUri(context: Context, uri: Uri): GraffitiProject? = withContext(Dispatchers.IO) {
        // All callers pass the application context here (same object as the injected [appContext]),
        // so the body uses [appContext] throughout rather than mixing it with this parameter.
        val extractedFiles = mutableMapOf<String, File>()
        return@withContext try {
            appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var projectData: GraffitiProject? = null
                    var totalBytes = 0L

                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val relativeName = if (name.contains('/')) name.substringAfter('/') else name

                        if (!entry.isDirectory && relativeName.isNotEmpty()) {
                            val streamed = streamEntryBounded(zis, totalBytes, appContext.cacheDir)
                            if (streamed == null) {
                                Log.e("ProjectManager", "Import aborted: archive exceeds $MAX_IMPORT_BYTES bytes")
                                return@use null
                            }
                            val (tmpFile, newTotal) = streamed
                            totalBytes = newTotal
                            if (relativeName == "project.json") {
                                try {
                                    projectData = json.decodeFromString<GraffitiProject>(tmpFile.readText())
                                } catch (e: Exception) {
                                    Log.e("ProjectManager", "Failed to parse project.json", e)
                                }
                            }
                            // Duplicate entry names (e.g. two entries whose first path segment strips
                            // to the same relative name) must not leak the SUPERSEDED temp file — the
                            // map assignment below would otherwise drop the only reference to it.
                            extractedFiles.remove(relativeName)?.delete()
                            extractedFiles[relativeName] = tmpFile
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
                    // An import is a new library entry when this id already exists. Never
                    // overwrite a working mural with an older shared/archive copy.
                    val importedId = if (File(appContext.filesDir, "projects/${project.id}").exists())
                        java.util.UUID.randomUUID().toString() else project.id
                    val destDir = File(appContext.filesDir, "projects/$importedId")
                    destDir.mkdirs()
                    check(destDir.isDirectory) { "Could not create import directory" }
                    try {
                        for ((name, tmpFile) in extractedFiles) {
                            val dest = resolveInside(destDir, name) ?: continue
                            dest.parentFile?.mkdirs()
                            tmpFile.copyTo(dest, overwrite = true)
                        }
                        val imported = relocateProjectFiles(project, destDir).copy(id = importedId)
                        atomicWriteText(File(destDir, "project.json"), json.encodeToString(imported))
                        imported
                    } catch (e: Exception) {
                        destDir.deleteRecursively()
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ProjectManager", "Import failed", e)
            null
        } finally {
            // Extracted temp files are copied (not renamed) into the destination directory on the
            // success path and left in place; clear them here on every path (success and failure).
            extractedFiles.values.forEach { if (it.exists()) it.delete() }
        }
    }

    private fun relocateProjectFiles(project: GraffitiProject, root: File): GraffitiProject {
        fun localPath(path: String): File {
            val marker = "/projects/${project.id}/"
            val relative = if (marker in path) path.substringAfter(marker) else path.substringAfterLast('/')
            return resolveInside(root, relative)?.takeIf { it.isFile }
                ?: error("Archive is missing project asset: $relative")
        }
        fun localUri(uri: Uri?): Uri? = uri?.let {
            uriProvider.getUriForFile(localPath(it.path ?: it.toString()))
        }
        return migrateInMemory(project).let { migrated ->
            migrated.copy(
                design = migrated.design?.let { it.copy(uri = localUri(it.uri)!!) },
                backgroundImageUri = localUri(migrated.backgroundImageUri),
                overlayImageUri = localUri(migrated.overlayImageUri),
                originalOverlayImageUri = localUri(migrated.originalOverlayImageUri),
                thumbnailUri = localUri(migrated.thumbnailUri),
                targetImageUris = migrated.targetImageUris.map { localUri(it)!! },
                sphereSlamReferenceUri = localUri(migrated.sphereSlamReferenceUri),
                sphereSlamAtlasPages = migrated.sphereSlamAtlasPages.map { page ->
                    page.copy(referenceUri = localUri(page.referenceUri)!!)
                },
                evolutionImageUris = migrated.evolutionImageUris.map { localUri(it)!! },
                targetFingerprintPath = migrated.targetFingerprintPath?.let { localPath(it).absolutePath },
            )
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
     *
     * Zips the whole project directory into a heap `ByteArray` — potentially several MB for a
     * project with many target images — so this always runs off the caller's thread, matching
     * every other IO function in this class.
     */
    suspend fun serializeCurrentProject(): ByteArray = withContext(Dispatchers.IO) {
        val project = projectRepositoryProvider.get().currentProject.value ?: return@withContext ByteArray(0)
        val sourceFolder = File(appContext.filesDir, "projects/${project.id}")
        if (!sourceFolder.exists()) return@withContext ByteArray(0)

        ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zipFolder(sourceFolder, "", zos)
            }
            baos.toByteArray()
        }
    }

    /**
     * Loads a project received as raw bytes from a host device (spectator/guest path).
     *
     * Mirrors [importProjectFromUri]'s hardening: entries stream straight to temp files (never held
     * fully in memory — a co-op bulk snapshot is exactly as untrusted as an imported .gxr), the
     * cumulative decompressed size is capped at [MAX_IMPORT_BYTES], and temp files are always cleaned
     * up (duplicate-collision path and the exception path both included).
     */
    /**
     * Installs the host's project archive as this guest's current project. Returns true only when
     * the project was created; false (never a throw) on any rejection or failure, so the caller can
     * tell the guest instead of showing a connected session with nothing loaded.
     *
     * The copy lives under [spectatorId], never the host's id: a guest who imported the host's .gxr
     * and edited it locally holds a project with that same id, and joining the session must not
     * overwrite their work. Repeated bulks (reconnects) replace the spectator copy, as they should.
     */
    suspend fun loadAsSpectator(
        bytes: ByteArray,
        /**
         * Session-only normalization applied after archive URIs are relocated but BEFORE the
         * spectator project is published. This is intentionally scoped to the spectator copy: it
         * lets co-op map backend-specific placement fields without ever mutating the user's local
         * project or the host archive on disk.
         */
        transform: (GraffitiProject) -> GraffitiProject = { it },
    ): Boolean = withContext(Dispatchers.IO) {
        if (bytes.isEmpty()) return@withContext false
        var loaded = false

        val extractedFiles = mutableMapOf<String, File>()
        try {
            ZipInputStream(bytes.inputStream()).use { zis ->
                var projectData: GraffitiProject? = null
                var totalBytes = 0L

                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        val streamed = streamEntryBounded(zis, totalBytes, appContext.cacheDir)
                        if (streamed == null) {
                            Log.e("ProjectManager", "Spectator load aborted: archive exceeds $MAX_IMPORT_BYTES bytes")
                            return@use
                        }
                        val (tmpFile, newTotal) = streamed
                        totalBytes = newTotal
                        if (name == "project.json") {
                            projectData = json.decodeFromString<GraffitiProject>(tmpFile.readText())
                        }
                        // Duplicate entry names must not leak the superseded temp file.
                        extractedFiles.remove(name)?.delete()
                        extractedFiles[name] = tmpFile
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
                val localId = spectatorId(project.id)
                val destDir = File(appContext.filesDir, "projects/$localId").also { it.mkdirs() }

                for ((name, tmpFile) in extractedFiles) {
                    val dest = resolveInside(destDir, name)
                    if (dest == null) {
                        Log.w("ProjectManager", "Skipping zip entry escaping project dir: $name")
                        tmpFile.delete()
                        continue
                    }
                    dest.parentFile?.mkdirs()
                    if (dest.exists()) dest.delete()
                    if (!tmpFile.renameTo(dest)) {
                        tmpFile.copyTo(dest, overwrite = true)
                        tmpFile.delete()
                    }
                }

                // Relocate against the HOST's id (the archive's paths carry it), then re-key.
                val relocated = relocateProjectFiles(project, destDir).copy(id = localId)
                val normalized = transform(relocated)
                require(normalized.id == localId) {
                    "spectator transform must preserve isolated project id"
                }
                withContext(Dispatchers.Main) {
                    projectRepositoryProvider.get().createProject(normalized)
                }
                loaded = true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ProjectManager", "loadAsSpectator failed", e)
        } finally {
            // Temp files are only renamed away on the success path; clear any stragglers.
            extractedFiles.values.forEach { if (it.exists()) it.delete() }
        }
        loaded
    }

    /** Local id for a spectated host project; distinct from the host id so it never collides. */
    internal fun spectatorId(hostId: String): String =
        if (hostId.startsWith(SPECTATOR_PREFIX)) hostId else SPECTATOR_PREFIX + hostId

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

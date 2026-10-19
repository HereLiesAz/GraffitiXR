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

        /** Prefix of the design images the editor writes (import and co-op pixel replacement). */
        private const val DESIGN_FILE_PREFIX = "design_"

        /** Under filesDir, outside projects/: where a spectator archive is built before swap-in. */
        private const val SPECTATOR_STAGING_DIR = "coop_staging"
        /** Filename shape of [saveHybridKpmPage] output; [deleteHybridKpmPage] refuses anything else. */
        private const val HYBRID_KPM_PAGE_PREFIX = "hybrid_kpm_page_"
        private const val HYBRID_KPM_PAGE_SUFFIX = ".y8.gz"
        /** Generous ceiling (4096²) over the 1024-px capture cap, so a bad manifest cannot OOM. */
        private const val MAX_HYBRID_KPM_PAGE_BYTES = 4096 * 4096
        /**
         * Cap on total decompressed bytes accepted from an imported/peer-received `.gxr` archive.
         * Both sources are untrusted (a shared file, or the co-op wire), so a zip bomb must not
         * be able to fill the app sandbox.
         *
         * Why 512 MiB: a project is a design PNG (or a few superseded ones), at most
         * [MAX_TARGET_IMAGES] captured target PNGs, evolution frames, the SphereSLAM reference/atlas
         * pages and the hybrid KPM page. Camera-resolution PNGs are ~5-15 MB each, so even a heavily
         * used project stays well under ~200 MiB; 512 MiB leaves ~2.5x headroom over that. It is
         * also what a co-op host can plausibly hold as one in-memory zip ([serializeCurrentProject])
         * and what a mid-range phone can be expected to have free for the extraction temp files plus
         * the staged copy (see [hasRoomToImport], which checks the free space up front).
         */
        private const val MAX_IMPORT_BYTES = 512L * 1024 * 1024

        /**
         * Free space always required before an import starts extracting, on top of a multiple of the
         * archive's declared size: the manifest rewrite, directory entries and filesystem slack.
         */
        private const val IMPORT_MIN_FREE_BYTES = 64L * 1024 * 1024

        /** Under filesDir, outside projects/: where an import is built before it becomes a project. */
        private const val IMPORT_STAGING_DIR = "import_staging"

        /**
         * Cap on how many captured target images a single project keeps, enforced by
         * [pruneDoodleImages]. Nothing previously pruned this list, so repeated re-captures could grow
         * it, and its on-disk PNGs, without bound short of deleting the whole project. No existing
         * precedent in this codebase for the right number, so 30 is chosen as generous headroom for a
         * real capture session while still bounding worst-case storage to a few dozen PNGs.
         *
         * Note: the live capture flow appends via [appendDoodleImage], which does its own pruning and
         * never routes through [saveProject]'s `doodleImages` parameter — this cap/path is exercised by
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
        doodleImages: List<Bitmap>? = null,
        thumbnail: Bitmap? = null,
    ): GraffitiProject = saveProjectInternal(
        context = context,
        projectData = projectData,
        doodleImages = doodleImages,
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
    ): GraffitiProject = saveProjectInternal(
        context = context,
        projectData = projectData,
        doodleImages = null,
        thumbnail = null,
        preserveExistingCaptureState = false,
    )

    private suspend fun saveProjectInternal(
        context: Context,
        projectData: GraffitiProject,
        doodleImages: List<Bitmap>?,
        thumbnail: Bitmap?,
        preserveExistingCaptureState: Boolean,
    ): GraffitiProject = withContext(Dispatchers.IO) {
        // A damaged placeholder carries none of the project: writing it would replace what is left.
        if (projectData.isDamaged) throw java.io.IOException("Refusing to save damaged project ${projectData.id}")
        val root = File(context.filesDir, "projects/${projectData.id}")
        if (!root.exists()) root.mkdirs()

        // The target doodle must survive EVERY routine save (layer edits, autosave, re-entering
        // AR, etc.) and change only when the user creates a NEW target — which is the one path that
        // passes a non-null doodle. So when the incoming project carries no doodle, carry
        // over whatever is already persisted instead of nulling it. This makes it impossible for any
        // other writer (or a stale-snapshot race) to wipe the saved target — the view matrix and
        // capture rotation are captured alongside the doodle itself, so they follow the same
        // unconditional preserve-from-existing as the doodle/intrinsics/anchor; the wall feature
        // map and cloud anchor id can each legitimately be set on a routine (non-doodle) save
        // (e.g. the passive wall-map save), so those instead only fall back to the on-disk value when
        // the incoming project doesn't set one, same as the legacy target-doodle references (the
        // learned wall normal and the capture environment follow the same fallback).
        //
        // An incoming doodle does not switch the merge off wholesale. Only a doodle that
        // DIFFERS from the persisted one is a new target, and only the state expressed in the old
        // target's frame (the capture group, legacy refs, wall map, paint marks/grid, cloud anchor,
        // AR placement width) is taken verbatim from it. A stale writer that merely carries the
        // already-saved doodle gets the full merge, and the frame-independent state below
        // (SphereSLAM's own page group, the measured wall width) is preserved either way.
        val mergedCapture = if (preserveExistingCaptureState) {
            val existing = try {
                val f = File(root, "project.json")
                if (f.exists()) json.decodeFromString<GraffitiProject>(f.readText()) else null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow cancellation
            } catch (_: Exception) {
                null
            }
            if (existing != null) {
                val replacesDoodle =
                    projectData.doodle != null && projectData.doodle != existing.doodle
                val withTarget = if (replacesDoodle) projectData else projectData.copy(
                    doodle = existing.doodle,
                    doodleIntrinsics = existing.doodleIntrinsics,
                    doodleAnchor = existing.doodleAnchor,
                    doodleViewMatrix = existing.doodleViewMatrix,
                    doodleCaptureRotationDeg = existing.doodleCaptureRotationDeg,
                    arCoreCaptureImageUri = existing.arCoreCaptureImageUri,
                    // Learned by MobileGS at trusted locks (so a routine save may carry a newer one);
                    // an older whole-object writer that never saw it must not clear it.
                    doodleWallNormalEnu =
                        projectData.doodleWallNormalEnu.ifEmpty { existing.doodleWallNormalEnu },
                    // Captured with the doodle, in the same frame as the view matrix above.
                    captureEnvironment = projectData.captureEnvironment ?: existing.captureEnvironment,
                    // Preserve the legacy target-doodle references too, so no save without them
                    // can wipe an existing target.
                    doodleData = projectData.doodleData ?: existing.doodleData,
                    doodleDataPath = projectData.doodleDataPath ?: existing.doodleDataPath,
                    wallFeatureMap = projectData.wallFeatureMap ?: existing.wallFeatureMap,
                    paintMarks = projectData.paintMarks ?: existing.paintMarks,
                    paintGrid = projectData.paintGrid ?: existing.paintGrid,
                    grownDoodle = projectData.grownDoodle ?: existing.grownDoodle,
                    sphereSlamPaintMarks = projectData.sphereSlamPaintMarks ?: existing.sphereSlamPaintMarks,
                    sphereSlamPaintGrid = projectData.sphereSlamPaintGrid ?: existing.sphereSlamPaintGrid,
                    sphereSlamGrownDoodle = projectData.sphereSlamGrownDoodle ?: existing.sphereSlamGrownDoodle,
                    cloudAnchorId = projectData.cloudAnchorId ?: existing.cloudAnchorId,
                    // Written once by the AR placement; a stale snapshot must not reset it.
                    arDesignHalfWidthM = if (projectData.arDesignHalfWidthM > 0f) projectData.arDesignHalfWidthM
                        else existing.arDesignHalfWidthM,
                )
                withTarget.copy(
                    // Standalone SphereSLAM's canonical wall page is just as persistent as the
                    // doodle: routine/stale saves must not silently erase it.
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
                    // Artist-measured; a stale whole-object writer must not erase it. A deliberate
                    // clear goes through updateProject (saveProjectExact), which bypasses this merge.
                    wallWidthMeters = projectData.wallWidthMeters ?: existing.wallWidthMeters,
                )
            } else projectData
        } else projectData
        val incoming = preserveHybridKpmPage(root, mergedCapture, preserveExistingCaptureState)

        val thumbnailUri = if (thumbnail != null) {
            val file = File(root, "thumbnail.png")
            writePngAtomically(thumbnail, file, "project thumbnail")
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
        val savedDoodleUris = if (doodleImages != null) {
            val newUris = doodleImages.map { bitmap ->
                val file = File(root, "target_${UUID.randomUUID()}.png")
                writePngAtomically(bitmap, file, "target image")
                uriProvider.getUriForFile(file)
            }
            pruneDoodleImages(incoming.doodleImageUris + newUris, keep = incoming.arCoreCaptureImageUri)
        } else {
            incoming.doodleImageUris
        }

        val updatedGraffitiProject = incoming.copy(
            thumbnailUri = thumbnailUri,
            doodleImageUris = savedDoodleUris,
            lastModified = System.currentTimeMillis()
        )

        val jsonString = json.encodeToString(updatedGraffitiProject)
        atomicWriteText(File(root, "project.json"), jsonString)
        // Return what was actually persisted (after preserve-merges) so callers publish that.
        updatedGraffitiProject
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
            // Flushed to the device before the rename, like the other writers here: without the sync a
            // power loss after the rename can still leave an empty or partial project.json.
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
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
     * Encodes [bitmap] as PNG into a sibling temp file, fsyncs it, then renames it over [target], the
     * same commit discipline as [saveSphereSlamReference]. A failed encode (compress returns false),
     * a full disk or a kill mid-write therefore never leaves a truncated PNG at [target]; it throws
     * instead, so no caller records a URI for an image that was not fully written.
     */
    private fun writePngAtomically(bitmap: Bitmap, target: File, label: String) {
        val tmp = File.createTempFile("${target.nameWithoutExtension}_", ".tmp", target.parentFile)
        try {
            FileOutputStream(tmp).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Could not encode $label" }
                out.flush()
                out.fd.sync()
            }
            check(tmp.renameTo(target)) { "Could not install $label" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Drops the oldest entries once [uris] exceeds [MAX_TARGET_IMAGES], deleting their backing files
     * so a pruned entry doesn't leak a PNG that nothing references any more.
     */
    private fun pruneDoodleImages(uris: List<Uri>, keep: Uri? = null): List<Uri> {
        val kept = keepNewest(uris, keep)
        if (kept.size == uris.size) return uris
        (uris - kept.toSet()).forEach { uri ->
            try {
                uri.path?.let { File(it).delete() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: a failed delete just leaves an orphaned file, not a correctness issue.
            }
        }
        return kept
    }

    /** The newest [MAX_TARGET_IMAGES] of [uris], oldest first, never dropping [keep]. */
    private fun keepNewest(uris: List<Uri>, keep: Uri?): List<Uri> {
        if (uris.size <= MAX_TARGET_IMAGES) return uris
        val held = keep?.takeIf { it in uris }
        val rest = uris.filter { it != held }.takeLast(if (held != null) MAX_TARGET_IMAGES - 1 else MAX_TARGET_IMAGES)
        return if (held == null) rest else uris.filter { it == held || it in rest }
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

    /**
     * The hybrid KPM page's six fields are one unit keyed on its URI, written only by the hybrid
     * persist transform (saveProjectExact, which bypasses this). A stale whole-object writer that
     * carries no page must not erase it — with OR without a doodle in hand: a snapshot taken
     * after a target's doodle saved but before its page committed carries the doodle and
     * would skip the doodle-null merge above. Keeping an older page is safe: it restores only
     * while the project's doodle matches its key.
     */
    private fun preserveHybridKpmPage(
        root: File,
        project: GraffitiProject,
        preserve: Boolean,
    ): GraffitiProject {
        if (!preserve || project.hybridKpmPageUri != null) return project
        val existing = try {
            val f = File(root, "project.json")
            if (f.exists()) json.decodeFromString<GraffitiProject>(f.readText()) else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return project
        if (existing.hybridKpmPageUri == null) return project
        return project.copy(
            hybridKpmPageUri = existing.hybridKpmPageUri,
            hybridKpmPageWidthPx = existing.hybridKpmPageWidthPx,
            hybridKpmPageHeightPx = existing.hybridKpmPageHeightPx,
            hybridKpmPageWidthMeters = existing.hybridKpmPageWidthMeters,
            hybridKpmPageFromArtwork = existing.hybridKpmPageFromArtwork,
            hybridKpmDoodleKey = existing.hybridKpmDoodleKey,
        )
    }

    /**
     * Writes the hybrid (ARCore-sidecar) KPM page as gzip'd raw 8-bit luma and returns its URI.
     *
     * Raw luma rather than PNG: the matcher consumes luma, and a grey ARGB bitmap round trip through
     * PNG and back through a luma formula is not guaranteed bit-exact. Versioned filename, temp file,
     * fsync, rename — the same commit discipline as [saveSphereSlamReference].
     */
    suspend fun saveHybridKpmPage(
        context: Context,
        projectId: String,
        luma: ByteArray,
    ): Uri = withContext(Dispatchers.IO) {
        require(luma.isNotEmpty())
        val root = File(context.filesDir, "projects/$projectId").also { if (!it.exists()) it.mkdirs() }
        val target = File(root, "${HYBRID_KPM_PAGE_PREFIX}${UUID.randomUUID()}$HYBRID_KPM_PAGE_SUFFIX")
        val tmp = File.createTempFile(HYBRID_KPM_PAGE_PREFIX, ".tmp", root)
        try {
            FileOutputStream(tmp).use { out ->
                java.util.zip.GZIPOutputStream(out).let { gz ->
                    gz.write(luma)
                    gz.finish()
                }
                out.flush()
                out.fd.sync()
            }
            check(tmp.renameTo(target)) { "Could not install hybrid KPM page" }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        uriProvider.getUriForFile(target)
    }

    /**
     * Reads a page written by [saveHybridKpmPage]. Returns null — never a partial or padded buffer —
     * when the file is missing, unreadable, or does not hold exactly [expectedBytes] bytes.
     */
    suspend fun readHybridKpmPage(uri: Uri, expectedBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        // The size comes from project.json, which may be an imported or peer-sent manifest; refuse
        // anything a real page (≤ 1024 px per side at capture) could not be, before allocating.
        if (expectedBytes <= 0 || expectedBytes > MAX_HYBRID_KPM_PAGE_BYTES) return@withContext null
        val path = uri.path ?: return@withContext null
        runCatching {
            java.util.zip.GZIPInputStream(File(path).inputStream().buffered()).use { gz ->
                val out = ByteArray(expectedBytes)
                var read = 0
                while (read < expectedBytes) {
                    val n = gz.read(out, read, expectedBytes - read)
                    if (n < 0) return@use null
                    read += n
                }
                if (gz.read() != -1) null else out
            }
        }.getOrNull()
    }

    /** Best-effort deletion of a hybrid KPM page owned by [projectId]; never outside its directory. */
    suspend fun deleteHybridKpmPage(
        context: Context,
        projectId: String,
        uri: Uri?,
    ) = withContext(Dispatchers.IO) {
        val path = uri?.path ?: return@withContext
        val root = File(context.filesDir, "projects/$projectId").canonicalFile
        val file = File(path).canonicalFile
        val validName = file.name.startsWith(HYBRID_KPM_PAGE_PREFIX) && file.name.endsWith(HYBRID_KPM_PAGE_SUFFIX)
        if (file.parentFile == root && validName) runCatching { file.delete() }
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
    suspend fun appendDoodleImage(
        context: Context,
        projectId: String,
        existingUris: List<Uri>,
        bitmap: Bitmap,
        keep: Uri? = null,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId").also { if (!it.exists()) it.mkdirs() }
        // Unique filename, not size-indexed — see the comment in saveProject's target-image block for
        // why an index derived from (possibly already-pruned) list size would collide.
        val file = File(root, "target_${UUID.randomUUID()}.png")
        writePngAtomically(bitmap, file, "target image")
        // Only the LIST is pruned here: the dropped files are still referenced by project.json until the
        // caller commits this list, so the caller deletes them afterwards ([deleteDoodleImages]).
        // [keep] (the ARCore capture photo) survives the pruning.
        keepNewest(existingUris + uriProvider.getUriForFile(file), keep)
    }

    /**
     * Delete target-image files that the committed project no longer references (the entries
     * [appendDoodleImage] pruned), or a new one whose commit lost the race. Best-effort.
     */
    suspend fun deleteDoodleImages(uris: Collection<Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { uri ->
            try {
                uri.path?.let { File(it).delete() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // A failed delete leaves an orphaned file, not a dangling reference.
            }
        }
    }

    /**
     * Loads a project's metadata, applying [migrateInMemory] purely in memory.
     *
     * This used to be two functions: this read-only one (used everywhere — dashboard listing, repo
     * `getProject`/`loadProject`, `updateDoodleData`) plus a `loadProject(): LoadedProject?`
     * that additionally loaded every target-image bitmap and — its one distinguishing behavior —
     * persisted the migration back to project.json. That second function had no caller anywhere in
     * the codebase (nothing ever consumed a `LoadedProject`'s bitmaps either), so the "persist on
     * full load" behavior a couple of old comments described never actually happened: every real load
     * went through this function, which was always read-only. Migrations stay in-memory-only and are
     * re-applied (cheaply — a couple of reference/equality checks) on every load; the dead function
     * and its now-inaccurate comments have been removed rather than wired up, since nothing in the
     * app needs pre-loaded target-image bitmaps and re-migrating on every read is not a real cost.
     *
     * When the manifest's id does not match [projectId], the folder name, the manifest is repaired
     * (re-keyed to the folder name and rewritten atomically, logged) if the folder name is a valid
     * project id; otherwise this returns null and logs.
     */
    suspend fun loadProjectMetadata(context: Context, projectId: String): GraffitiProject? = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects/$projectId")
        val projectFile = File(root, "project.json")
        if (!projectFile.exists()) return@withContext null

        return@withContext try {
            val jsonString = projectFile.readText()
            val project = json.decodeFromString<GraffitiProject>(jsonString)
            // The folder name is the project's identity everywhere else (saves, deletes, the file
            // URIs under it). A manifest whose id differs (a legacy half-finished import that kept
            // the archive's id, a hand-copied folder) would list under one id and then save, delete
            // and open under another. Refusing it would orphan the folder - listed nowhere it can be
            // opened, undeletable from the app, its files leaking - so when the folder name is itself
            // a valid project id the manifest is REPAIRED: re-keyed to the folder and written back
            // atomically. Only a folder whose name cannot be a project id is refused.
            if (project.id != projectId) {
                if (!isSafeProjectId(projectId)) {
                    Log.e(
                        "ProjectManager",
                        "Refusing project folder '$projectId': its manifest id is '${project.id}' " +
                            "and the folder name is not a valid project id",
                    )
                    return@withContext null
                }
                val repaired = rekeyProject(project, projectId)
                Log.w(
                    "ProjectManager",
                    "Repairing project folder '$projectId': manifest id '${project.id}' re-keyed to the folder name",
                )
                try {
                    atomicWriteText(projectFile, json.encodeToString(repaired))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The in-memory repair is still correct, and the next successful save writes it.
                    Log.e("ProjectManager", "Could not write back the repaired manifest of '$projectId'", e)
                }
                return@withContext migrateInMemory(repaired)
            }
            migrateInMemory(project)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // never swallow cancellation
        } catch (e: Exception) {
            Log.e("ProjectManager", "Failed to load project metadata", e)
            damagedPlaceholder(projectId, projectFile)
        }
    }

    /**
     * An unreadable project.json (a decode or validation failure) keeps the project in the library as
     * damaged instead of dropping it: its files stay intact, it can be deleted, and it is never opened
     * or written. The name is salvaged from the raw JSON when it can be.
     */
    private fun damagedPlaceholder(projectId: String, projectFile: File): GraffitiProject? {
        if (!isSafeProjectId(projectId)) return null
        val name = runCatching {
            (Json.parseToJsonElement(projectFile.readText()) as? kotlinx.serialization.json.JsonObject)
                ?.get("name")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: projectId
        return GraffitiProject(
            id = projectId,
            name = name,
            lastModified = projectFile.lastModified(),
            isDamaged = true,
        )
    }

    /**
     * [project] under a new id: the id itself, and every file reference that names the old id's
     * folder (`/projects/<old>/`) re-pointed at the new one's. References that do not mention the old
     * folder are left alone.
     */
    private fun rekeyProject(project: GraffitiProject, newId: String): GraffitiProject {
        val oldMarker = "/projects/${project.id}/"
        val newMarker = "/projects/$newId/"
        fun path(p: String): String = p.replace(oldMarker, newMarker)
        fun uri(u: Uri): Uri = u.toString().let { if (oldMarker in it) Uri.parse(path(it)) else u }
        fun uri(u: Uri?): Uri? = u?.let { uri(it) }
        return project.copy(
            id = newId,
            design = project.design?.let { it.copy(uri = uri(it.uri)) },
            layers = project.layers.map { it.copy(uri = uri(it.uri)) },
            backgroundImageUri = uri(project.backgroundImageUri),
            overlayImageUri = uri(project.overlayImageUri),
            originalOverlayImageUri = uri(project.originalOverlayImageUri),
            thumbnailUri = uri(project.thumbnailUri),
            doodleImageUris = project.doodleImageUris.map { uri(it) },
            cleanWallUri = uri(project.cleanWallUri),
            arCoreCaptureImageUri = uri(project.arCoreCaptureImageUri),
            sphereSlamReferenceUri = uri(project.sphereSlamReferenceUri),
            sphereSlamAtlasPages = project.sphereSlamAtlasPages.map { it.copy(referenceUri = uri(it.referenceUri)) },
            evolutionImageUris = project.evolutionImageUris.map { uri(it) },
            hybridKpmPageUri = uri(project.hybridKpmPageUri),
            doodleDataPath = project.doodleDataPath?.let { path(it) },
        )
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
                    zipFolder(sourceFolder, "", zos, unreferencedDesignFiles(sourceFolder))
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

    /**
     * Whether the device has the free space to extract an archive that EXPANDS to [expandedBytes]
     * (see [expandedArchiveSize]; negative when it could not be determined).
     *
     * Sized from the uncompressed bytes, not the archive's length: a few KB of highly compressible
     * data inflates to hundreds of MB, and a check against the compressed length used to pass it.
     *
     * Extraction first streams every entry to a temp file under the cache dir (at most
     * [MAX_IMPORT_BYTES] in total) and then moves or, across filesystems, copies it into staging, so
     * the worst case holds the data twice. The requirement is [IMPORT_MIN_FREE_BYTES] plus twice the
     * expanded size, never more than twice [MAX_IMPORT_BYTES]. An archive that expands past
     * [MAX_IMPORT_BYTES] is refused outright (the cumulative cap would abort it part-way anyway).
     * With an unknown size only the floor applies; the cap in [streamEntryBounded] still bounds the
     * rest, and a disk that fills mid-way fails the import cleanly.
     *
     * [freeBytes] is the smaller of the cache and files dirs' usable space unless a test supplies one.
     */
    internal fun hasRoomToImport(
        expandedBytes: Long,
        freeBytes: Long = minOf(appContext.cacheDir.usableSpace, appContext.filesDir.usableSpace),
    ): Boolean {
        if (expandedBytes > MAX_IMPORT_BYTES) return false
        val needed = if (expandedBytes > 0) {
            minOf(IMPORT_MIN_FREE_BYTES + 2 * expandedBytes, 2 * MAX_IMPORT_BYTES)
        } else {
            IMPORT_MIN_FREE_BYTES
        }
        return freeBytes >= needed
    }

    /**
     * Total uncompressed size of the archive behind [uri], or -1 when it cannot be read.
     *
     * Counts the bytes each entry actually inflates to rather than trusting the entries' declared
     * sizes: those are attacker-controlled, and absent (-1) for archives written as a stream. Reading
     * stops once the total passes [MAX_IMPORT_BYTES], so a decompression bomb costs a bounded amount
     * of work here and the returned value (> the cap) is enough for [hasRoomToImport] to refuse it.
     */
    private fun expandedArchiveSize(uri: Uri): Long = try {
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { zis ->
                val sink = ByteArray(64 * 1024)
                var total = 0L
                var entry = zis.nextEntry
                while (entry != null && total <= MAX_IMPORT_BYTES) {
                    if (!entry.isDirectory) {
                        while (total <= MAX_IMPORT_BYTES) {
                            val n = zis.read(sink)
                            if (n < 0) break
                            total += n
                        }
                    }
                    entry = if (total > MAX_IMPORT_BYTES) null else zis.nextEntry
                }
                total
            }
        } ?: -1L
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        -1L
    }

    suspend fun importProjectFromUri(context: Context, uri: Uri): GraffitiProject? = withContext(Dispatchers.IO) {
        // All callers pass the application context here (same object as the injected [appContext]),
        // so the body uses [appContext] throughout rather than mixing it with this parameter.
        val extractedFiles = mutableMapOf<String, File>()
        var stagingDir: File? = null
        return@withContext try {
            if (!hasRoomToImport(expandedArchiveSize(uri))) {
                Log.e("ProjectManager", "Import refused: not enough free storage to extract the archive")
                return@withContext null
            }
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
                    // Built outside projects/ (like loadAsSpectator), so the archive's project.json,
                    // which still carries the sender's id, is never visible as a library entry, and
                    // a failure or kill part-way leaves nothing listed. The folder only appears under
                    // its final name, whole, once the corrected manifest has been written.
                    val staging = File(appContext.filesDir, "$IMPORT_STAGING_DIR/${UUID.randomUUID()}")
                        .also { it.mkdirs() }
                    stagingDir = staging
                    check(staging.isDirectory) { "Could not create import staging directory" }

                    for ((name, tmpFile) in extractedFiles) {
                        val dest = resolveInside(staging, name) ?: continue
                        dest.parentFile?.mkdirs()
                        if (dest.exists()) dest.delete()
                        if (!tmpFile.renameTo(dest)) {
                            tmpFile.copyTo(dest, overwrite = true)
                            tmpFile.delete()
                        }
                    }
                    // Validate and re-key against the staged files, but point every URI at the final
                    // folder the staging directory is about to become.
                    val imported = relocateProjectFiles(project, staging, finalRoot = destDir)
                        .copy(id = importedId)
                    atomicWriteText(File(staging, "project.json"), json.encodeToString(imported))
                    destDir.parentFile?.mkdirs()
                    check(!destDir.exists()) { "Import destination already exists" }
                    check(staging.renameTo(destDir)) { "Could not install imported project" }
                    imported
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ProjectManager", "Import failed", e)
            null
        } finally {
            // Extracted temp files are moved (or copied) into staging on the success path; clear any
            // stragglers here on every path (success and failure).
            extractedFiles.values.forEach { if (it.exists()) it.delete() }
            // Renamed into projects/ on success; anything still here is a failed import's leftovers.
            stagingDir?.let { if (it.exists()) it.deleteRecursively() }
        }
    }

    /**
     * Re-points every file reference in [project] at the matching file under [root], failing if the
     * archive lacks one. With [finalRoot] the files are checked under [root] (a staging directory)
     * but the returned URIs and paths name the same relative file under [finalRoot], the folder
     * [root] is about to be renamed to.
     */
    private fun relocateProjectFiles(
        project: GraffitiProject,
        root: File,
        finalRoot: File = root,
    ): GraffitiProject {
        fun localPath(path: String): File {
            val marker = "/projects/${project.id}/"
            val relative = if (marker in path) path.substringAfter(marker) else path.substringAfterLast('/')
            val staged = resolveInside(root, relative)?.takeIf { it.isFile }
                ?: error("Archive is missing project asset: $relative")
            return if (finalRoot == root) staged
            else File(finalRoot, staged.canonicalFile.relativeTo(root.canonicalFile).path)
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
                doodleImageUris = migrated.doodleImageUris.map { localUri(it)!! },
                cleanWallUri = localUri(migrated.cleanWallUri),
                arCoreCaptureImageUri = localUri(migrated.arCoreCaptureImageUri),
                sphereSlamReferenceUri = localUri(migrated.sphereSlamReferenceUri),
                sphereSlamAtlasPages = migrated.sphereSlamAtlasPages.map { page ->
                    page.copy(referenceUri = localUri(page.referenceUri)!!)
                },
                evolutionImageUris = migrated.evolutionImageUris.map { localUri(it)!! },
                doodleDataPath = migrated.doodleDataPath?.let { localPath(it).absolutePath },
            ).let { relocated ->
                // The hybrid page is an optional accelerator, not project content: a sender's
                // archive that lacks the file drops the page rather than failing the whole import.
                val hybridUri = migrated.hybridKpmPageUri?.let { uri ->
                    runCatching { localUri(uri) }.getOrNull()
                }
                if (hybridUri != null) {
                    relocated.copy(hybridKpmPageUri = hybridUri)
                } else {
                    relocated.copy(
                        hybridKpmPageUri = null,
                        hybridKpmPageWidthPx = 0,
                        hybridKpmPageHeightPx = 0,
                        hybridKpmPageWidthMeters = 0f,
                        hybridKpmPageFromArtwork = emptyList(),
                        hybridKpmDoodleKey = emptyList(),
                    )
                }
            }
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
                zipFolder(sourceFolder, "", zos, unreferencedDesignFiles(sourceFolder))
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
     * overwrite their work. Repeated bulks (reconnects) replace the spectator copy, as they should:
     * the archive is extracted into a fresh staging directory that is swapped in for the old copy
     * whole, and the project is saved exactly, so nothing from a previous join (files, or fields the
     * host has since cleared) survives into this one.
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
        var stagingDir: File? = null
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
                val destDir = File(appContext.filesDir, "projects/$localId")
                // Staged outside projects/ so a half-built copy is never listed as a project.
                val staging = File(appContext.filesDir, "$SPECTATOR_STAGING_DIR/${UUID.randomUUID()}")
                    .also { it.mkdirs() }
                stagingDir = staging

                for ((name, tmpFile) in extractedFiles) {
                    val dest = resolveInside(staging, name)
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

                // Validate against the staged copy first: every referenced asset must be in THIS
                // archive (so the previous join's leftover files can never satisfy a reference the new
                // archive no longer carries), pointing every URI at the folder staging becomes.
                // Relocated against the HOST's id (the archive's paths carry it), then re-keyed.
                val relocated = relocateProjectFiles(project, staging, finalRoot = destDir).copy(id = localId)
                val normalized = transform(relocated)
                require(normalized.id == localId) {
                    "spectator transform must preserve isolated project id"
                }
                // The re-keyed manifest goes into staging BEFORE the folder becomes visible: swapping
                // the archive's own project.json in first listed a folder whose manifest carried the
                // host's id until the repository save below landed (and for ever, if it never did).
                atomicWriteText(File(staging, "project.json"), json.encodeToString(normalized))
                replaceDirectory(staging, destDir)
                // Exact save: createProject's stale-writer merge would read the previous join's
                // project.json and resurrect fields this snapshot deliberately lacks.
                withContext(Dispatchers.Main) {
                    projectRepositoryProvider.get().replaceProject(normalized)
                }
                loaded = true
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("ProjectManager", "loadAsSpectator failed", e)
        } finally {
            // Temp files are only renamed away on the success path; clear any stragglers.
            extractedFiles.values.forEach { if (it.exists()) it.delete() }
            // Renamed into place on success; anything still here is a failed load's leftovers.
            stagingDir?.let { if (it.exists()) it.deleteRecursively() }
        }
        loaded
    }

    /**
     * Swaps [staging] in for [dest] as a whole. The old copy is renamed aside first and restored if
     * the swap fails, so [dest] is either entirely the old copy or entirely the new one.
     */
    private fun replaceDirectory(staging: File, dest: File) {
        dest.parentFile?.mkdirs()
        val retired = File(staging.parentFile, "${staging.name}.old")
        if (dest.exists() && !dest.renameTo(retired)) {
            // Could not move it aside (unusual on one filesystem): fall back to deleting in place.
            check(dest.deleteRecursively()) { "Could not clear previous spectator copy" }
        }
        if (!staging.renameTo(dest)) {
            if (retired.exists()) retired.renameTo(dest)
            error("Could not install spectator copy")
        }
        retired.deleteRecursively()
    }

    /** Whether [projectId] names a co-op spectator copy (see [spectatorId]) rather than a user's project. */
    fun isSpectatorProject(projectId: String): Boolean = projectId.startsWith(SPECTATOR_PREFIX)

    /** Local id for a spectated host project; distinct from the host id so it never collides. */
    internal fun spectatorId(hostId: String): String =
        if (hostId.startsWith(SPECTATOR_PREFIX)) hostId else SPECTATOR_PREFIX + hostId

    // --- End co-op implementation ---

    /**
     * Names of superseded design images in [folder]: root-level `design_*` files that its
     * project.json no longer mentions. Every import and every co-op pixel replacement writes a new
     * one, so without this each bulk transfer and export would ship every design the project ever
     * had. They are only left out of archives here, never deleted: an in-memory undo entry may still
     * point at one, and undo reloads a changed image from its file. The device-side cleanup is
     * [deleteUnreferencedDesignFiles], which the editor runs when it loads a project (its undo
     * history is empty then). No project.json, no exclusions.
     */
    private fun unreferencedDesignFiles(folder: File): Set<String> {
        val manifest = File(folder, "project.json").takeIf { it.isFile }?.readText() ?: return emptySet()
        return folder.listFiles()
            ?.filter { it.isFile && it.name.startsWith(DESIGN_FILE_PREFIX) && it.name !in manifest }
            ?.mapTo(mutableSetOf()) { it.name }
            ?: emptySet()
    }

    /**
     * Deletes the superseded design images of [projectId] that its project.json no longer mentions
     * and that were last modified before [modifiedBeforeMs]. Returns how many were deleted.
     *
     * Exports already leave such files out of archives; this reclaims them on the device. The age
     * bound is what keeps it safe against an import in flight: the editor writes a new design image
     * before project.json points at it, so a caller passes the time its own session started, and a
     * file written since then is never touched. Nothing is deleted when the project has no manifest.
     */
    suspend fun deleteUnreferencedDesignFiles(
        context: Context,
        projectId: String,
        modifiedBeforeMs: Long,
    ): Int = withContext(Dispatchers.IO) {
        if (!isSafeProjectId(projectId)) return@withContext 0
        val root = File(context.filesDir, "projects/$projectId")
        if (!root.isDirectory) return@withContext 0
        var deleted = 0
        for (name in unreferencedDesignFiles(root)) {
            val file = File(root, name)
            if (file.lastModified() < modifiedBeforeMs && file.delete()) deleted++
        }
        deleted
    }

    private fun zipFolder(
        folder: File,
        parentFolder: String,
        zos: ZipOutputStream,
        // Root-level file names to leave out (see [unreferencedDesignFiles]).
        excludeAtRoot: Set<String> = emptySet(),
    ) {
        for (file in folder.listFiles() ?: emptyArray()) {
            if (parentFolder.isEmpty() && file.name in excludeAtRoot) continue
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

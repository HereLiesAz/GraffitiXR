package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.CaptureEnvironment
import com.hereliesaz.graffitixr.common.model.DeviceAttitude
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.LocationFix
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Exercises project files with real Android URI parsing, including serialized URI round trips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectManagerTest {

    private lateinit var mockContext: Context
    private lateinit var tempFilesDir: File
    private lateinit var uriProvider: UriProvider
    private lateinit var manager: ProjectManager

    @Before
    fun setup() {
        tempFilesDir = File(System.getProperty("java.io.tmpdir"), "graffitixr_test_files")
        tempFilesDir.mkdirs()

        mockContext = mockk()
        every { mockContext.filesDir } returns tempFilesDir
        every { mockContext.cacheDir } returns File(tempFilesDir, "cache").also { it.mkdirs() }

        uriProvider = DefaultUriProvider()
        val projectRepositoryProvider = mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>(relaxed = true)
        // A relaxed Provider<T> returns an erased Object from get(), which fails the cast at the call
        // site; stub it so paths that reach the repository (spectator load) actually complete.
        every { projectRepositoryProvider.get() } returns
            mockk<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>(relaxed = true)
        manager = ProjectManager(mockContext, uriProvider, projectRepositoryProvider)
    }

    @After
    fun teardown() {
        tempFilesDir.deleteRecursively()
    }

    @Test
    fun `importing the same id preserves the existing project`() = runTest {
        manager.saveProject(mockContext, GraffitiProject(id = "same", name = "Current work"))
        val imported = importZip(zipOf("project.json" to projectJson("same")))
        assertTrue(imported != null && imported.id != "same")
        assertEquals("Current work", manager.loadProjectMetadata(mockContext, "same")?.name)
        assertTrue(File(tempFilesDir, "projects/${imported!!.id}/project.json").exists())
    }

    @Test
    fun `import rewrites sender image paths to local project assets`() = runTest {
        val manifest = """{"id":"portable","name":"Wall","design":{"uri":"file:///sender/files/projects/portable/design.png"}}""".toByteArray()
        val imported = importZip(zipOf("project.json" to manifest, "design.png" to byteArrayOf(1, 2)))
        assertEquals(
            File(tempFilesDir, "projects/portable/design.png").canonicalFile,
            imported?.design?.uri?.path?.let { File(it).canonicalFile },
        )
        assertEquals(imported?.design?.uri.toString(), manager.loadProjectMetadata(mockContext, "portable")?.design?.uri.toString())
    }

    @Test
    fun `getProjectList returns empty list when directory is missing or empty`() = runTest {
        val list = manager.getProjectList(mockContext)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `saveProject and loadProjectMetadata works correctly`() = runTest {
        val project = GraffitiProject(id = "test_project", name = "My Test Art")
        manager.saveProject(mockContext, project)

        val list = manager.getProjectList(mockContext)
        assertEquals(1, list.size)
        assertEquals("test_project", list[0])

        val loaded = manager.loadProjectMetadata(mockContext, "test_project")
        assertEquals("My Test Art", loaded?.name)
    }

    @Test
    fun `deleteProject removes directory`() = runTest {
        val project = GraffitiProject(id = "del_project", name = "To Be Deleted")
        manager.saveProject(mockContext, project)
        assertTrue(File(tempFilesDir, "projects/del_project").exists())

        manager.deleteProject(mockContext, "del_project")
        assertFalse(File(tempFilesDir, "projects/del_project").exists())
    }

    @Test
    fun `deleteProject removes versioned SphereSLAM reference with project directory`() = runTest {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        manager.saveProject(mockContext, GraffitiProject(id = "delete_slam", name = "Wall"))
        val uri = manager.saveSphereSlamReference(mockContext, "delete_slam", bitmap)
        val file = File(requireNotNull(uri.path))
        assertTrue(file.exists())

        manager.deleteProject(mockContext, "delete_slam")

        assertFalse(file.exists())
        assertFalse(File(tempFilesDir, "projects/delete_slam").exists())
    }

    @Test
    fun `importProjectFromUri fails gracefully on bad URI`() = runTest {
        val mockUri = Uri.parse("content://test/project.gxr")
        val mockResolver = mockk<android.content.ContentResolver>()

        every { mockContext.contentResolver } returns mockResolver
        every { mockResolver.openInputStream(any()) } returns null

        val result = manager.importProjectFromUri(mockContext, mockUri)
        assertNull(result)
    }

    // --- Zip-Slip / hostile-archive hardening ---

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun projectJson(id: String) = """{"id":"$id","name":"evil"}""".toByteArray()

    private suspend fun importZip(zipBytes: ByteArray): GraffitiProject? {
        val mockUri = Uri.parse("content://test/project.gxr")
        val mockResolver = mockk<android.content.ContentResolver>()
        every { mockContext.contentResolver } returns mockResolver
        every { mockResolver.openInputStream(any()) } returns zipBytes.inputStream()
        return manager.importProjectFromUri(mockContext, mockUri)
    }

    @Test
    fun `import skips zip entries that escape the project directory`() = runTest {
        val evilTarget = File(tempFilesDir.parentFile, "gxr_zip_slip_escape.txt")
        evilTarget.delete()
        // Entry names get their first path segment stripped on import, so both hostile shapes
        // must be caught: a "../" chain surviving the strip, nested under a decoy segment.
        val depth = "../".repeat(6)
        val zip = zipOf(
            "project.json" to projectJson("safe_project"),
            "x/$depth${evilTarget.name}" to "pwned".toByteArray(),
            "innocent.png" to byteArrayOf(1, 2, 3),
        )

        val result = importZip(zip)

        assertFalse("hostile entry must not be written outside filesDir", evilTarget.exists())
        // The import itself succeeds minus the hostile entry.
        assertEquals("safe_project", result?.id)
        assertTrue(File(tempFilesDir, "projects/safe_project/innocent.png").exists())
        assertFalse(
            File(tempFilesDir, "projects/safe_project").walkTopDown().any { it.name == evilTarget.name },
        )
    }

    @Test
    fun `import rejects archives with a path-traversal project id`() = runTest {
        val result = importZip(zipOf("project.json" to projectJson("../escape")))
        assertNull("hostile project.id must reject the whole import", result)
        assertFalse(File(tempFilesDir, "escape").exists())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadAsSpectator skips escaping entries and rejects hostile ids`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val evilTarget = File(tempFilesDir.parentFile, "gxr_spectator_escape.txt")
            evilTarget.delete()

            // Hostile id: nothing may be created for it.
            manager.loadAsSpectator(zipOf("project.json" to projectJson("../spec_escape")))
            assertFalse(File(tempFilesDir, "spec_escape").exists())

            // Escaping entry: skipped, rest of the project loads. Spectator zips keep raw entry
            // names (no first-segment strip), so a bare "../" chain is the attack shape here.
            val depth = "../".repeat(6)
            manager.loadAsSpectator(
                zipOf(
                    "project.json" to projectJson("spec_safe"),
                    "$depth${evilTarget.name}" to "pwned".toByteArray(),
                    "layer.png" to byteArrayOf(7),
                ),
            )
            assertFalse(evilTarget.exists())
            assertTrue(File(tempFilesDir, "projects/coop_spec_safe/layer.png").exists())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `loadAsSpectator never overwrites a local project with the host's id`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val local = File(tempFilesDir, "projects/shared_wall").also { it.mkdirs() }
            File(local, "layer.png").writeBytes(byteArrayOf(1))
            val ok = manager.loadAsSpectator(
                zipOf("project.json" to projectJson("shared_wall"), "layer.png" to byteArrayOf(9)),
            )
            assertTrue("spectator load should succeed", ok)
            assertTrue(
                "local project was overwritten",
                File(local, "layer.png").readBytes().contentEquals(byteArrayOf(1)),
            )
            assertTrue(
                "spectator copy missing",
                File(tempFilesDir, "projects/coop_shared_wall/layer.png").exists(),
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `loadProjectMetadata does not write the migrated project back to disk`() = runTest {
        // A "legacy" project: non-default legacyVisuals triggers the in-memory migration.
        val projectDir = File(tempFilesDir, "projects/legacy_project").also { it.mkdirs() }
        val legacyJson = """{"id":"legacy_project","name":"Old","legacyVisuals":{"scale":2.0}}"""
        val projectFile = File(projectDir, "project.json")
        projectFile.writeText(legacyJson)

        val metadata = manager.loadProjectMetadata(mockContext, "legacy_project")

        assertEquals("legacy_project", metadata?.id)
        // Read path must be side-effect free: bytes on disk untouched.
        assertEquals(legacyJson, projectFile.readText())
    }

    // --- NaN sentinel serialization (CaptureEnvironment) ---

    @Test
    fun `saveProject does not throw when captureEnvironment has NaN sentinel fields`() = runTest {
        // GPS very often has a fix but no bearing/speed (a user standing still) — those default to
        // Float.NaN (see LocationFix), and DeviceAttitude's azimuth/pitch/roll do the same when no
        // absolute heading is available. kotlinx.serialization throws on a non-finite float unless
        // the Json is configured for it — this must not crash an entirely ordinary capture.
        val env = CaptureEnvironment(
            location = LocationFix(latitude = 40.0, longitude = -74.0), // bearingDeg/speedMps -> NaN
            attitude = DeviceAttitude(), // azimuthDeg/pitchDeg/rollDeg -> NaN
        )
        val project = GraffitiProject(id = "nan_project", name = "NaN test", captureEnvironment = env)

        // Must not throw.
        manager.saveProject(mockContext, project)

        val loaded = manager.loadProjectMetadata(mockContext, "nan_project")
        assertEquals(40.0, loaded?.captureEnvironment?.location?.latitude ?: 0.0, 0.0)
        assertTrue(loaded?.captureEnvironment?.location?.bearingDeg?.isNaN() == true)
        assertTrue(loaded?.captureEnvironment?.location?.speedMps?.isNaN() == true)
        assertTrue(loaded?.captureEnvironment?.attitude?.azimuthDeg?.isNaN() == true)
    }

    // --- Target image pruning (unbounded growth) ---

    @Test
    fun `saveProject prunes target images beyond the cap and deletes their files`() = runTest {
        val projectRepositoryProvider = mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>(relaxed = true)
        val pruningManager = ProjectManager(mockContext, uriProvider, projectRepositoryProvider)
        val bitmap = mockk<Bitmap>(relaxed = true)

        var project = GraffitiProject(id = "many_targets", name = "Many targets")
        // One capture at a time, as the real capture flow does — 35 captures against a cap of 30.
        repeat(35) {
            pruningManager.saveProject(mockContext, project, targetImages = listOf(bitmap))
            project = pruningManager.loadProjectMetadata(mockContext, "many_targets")!!
        }

        assertEquals(30, project.targetImageUris.size)
        // Nothing beyond the cap remains on disk — pruned entries are deleted, not merely dropped
        // from the list.
        val targetFiles = File(tempFilesDir, "projects/many_targets").listFiles { f -> f.name.startsWith("target_") }
        assertEquals(30, targetFiles?.size ?: -1)
    }

    @Test
    fun `appendTargetImage prunes beyond the cap without touching project json`() = runTest {
        val projectRepositoryProvider = mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>(relaxed = true)
        val pruningManager = ProjectManager(mockContext, uriProvider, projectRepositoryProvider)
        val bitmap = mockk<Bitmap>(relaxed = true)

        var uris = emptyList<Uri>()
        repeat(35) {
            uris = pruningManager.appendTargetImage(mockContext, "capture_only", uris, bitmap)
        }

        assertEquals(30, uris.size)
        // Pure file IO: appendTargetImage must never create project.json.
        assertFalse(File(tempFilesDir, "projects/capture_only/project.json").exists())
        val targetFiles = File(tempFilesDir, "projects/capture_only").listFiles { f -> f.name.startsWith("target_") }
        assertEquals(30, targetFiles?.size ?: -1)
    }


    // --- Standalone SphereSLAM reference persistence ---

    @Test
    fun `SphereSLAM reference writes are versioned and cleanup is project scoped`() = runTest {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        val first = manager.saveSphereSlamReference(mockContext, "slam_project", bitmap)
        val second = manager.saveSphereSlamReference(mockContext, "slam_project", bitmap)

        val firstFile = File(requireNotNull(first.path))
        val secondFile = File(requireNotNull(second.path))
        assertTrue(firstFile.name.startsWith("sphereslam_reference_"))
        assertTrue(firstFile.name.endsWith(".png"))
        assertTrue(secondFile.name.startsWith("sphereslam_reference_"))
        assertTrue(firstFile.name != secondFile.name)
        assertTrue(firstFile.exists())
        assertTrue(secondFile.exists())

        manager.deleteSphereSlamReference(mockContext, "slam_project", first)
        assertFalse(firstFile.exists())
        assertTrue(secondFile.exists())

        val outside = File(tempFilesDir, "sphereslam_reference_outside.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        manager.deleteSphereSlamReference(mockContext, "slam_project", Uri.fromFile(outside))
        assertTrue("cleanup must not escape the project directory", outside.exists())
    }

    @Test
    fun `SphereSLAM cleanup accepts the legacy fixed reference filename`() = runTest {
        val root = File(tempFilesDir, "projects/legacy_slam").also { it.mkdirs() }
        val legacy = File(root, "sphereslam_reference.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        manager.deleteSphereSlamReference(mockContext, "legacy_slam", Uri.fromFile(legacy))

        assertFalse(legacy.exists())
    }

    @Test
    fun `hybrid KPM page round-trips bit-exact`() = runTest {
        val luma = ByteArray(6 * 4) { (it * 37).toByte() }
        val uri = manager.saveHybridKpmPage(mockContext, "hyb_rt", luma)
        assertTrue(File(uri.path!!).name.startsWith("hybrid_kpm_page_"))
        assertArrayEquals(luma, manager.readHybridKpmPage(uri, 24))
    }

    @Test
    fun `hybrid KPM page of the wrong size reads as null, never padded or truncated`() = runTest {
        val uri = manager.saveHybridKpmPage(mockContext, "hyb_size", ByteArray(24) { 7 })
        assertNull(manager.readHybridKpmPage(uri, 25))
        assertNull(manager.readHybridKpmPage(uri, 23))
        assertNull(manager.readHybridKpmPage(uri, 0))
    }

    @Test
    fun `hybrid KPM delete refuses files it did not write`() = runTest {
        val root = File(tempFilesDir, "projects/hyb_del").also { it.mkdirs() }
        val design = File(root, "design.png").apply { writeBytes(byteArrayOf(1)) }
        manager.deleteHybridKpmPage(mockContext, "hyb_del", Uri.fromFile(design))
        assertTrue(design.exists())
        val page = manager.saveHybridKpmPage(mockContext, "hyb_del", byteArrayOf(1, 2))
        manager.deleteHybridKpmPage(mockContext, "hyb_del", page)
        assertFalse(File(page.path!!).exists())
    }

    @Test
    fun `stale whole-object save keeps the hybrid KPM page`() = runTest {
        val uri = manager.saveHybridKpmPage(mockContext, "hyb_stale", ByteArray(4))
        val relation = List(16) { if (it % 5 == 0) 1f else 0f }
        manager.saveProject(
            mockContext,
            GraffitiProject(
                id = "hyb_stale", name = "Wall",
                hybridKpmPageUri = uri, hybridKpmPageWidthPx = 2, hybridKpmPageHeightPx = 2,
                hybridKpmPageWidthMeters = 1.5f, hybridKpmPageFromArtwork = relation,
            ),
        )
        // A writer holding an old snapshot (no hybrid fields) saves over it.
        manager.saveProject(mockContext, GraffitiProject(id = "hyb_stale", name = "Renamed"))
        val loaded = manager.loadProjectMetadata(mockContext, "hyb_stale")
        assertEquals(uri, loaded?.hybridKpmPageUri)
        assertEquals(2, loaded?.hybridKpmPageWidthPx)
        assertEquals(1.5f, loaded?.hybridKpmPageWidthMeters ?: 0f, 0f)
        assertEquals(relation, loaded?.hybridKpmPageFromArtwork)
    }

    @Test
    fun `import rebases the hybrid KPM page URI`() = runTest {
        val manifest =
            """{"id":"hyb_import","name":"Wall","hybridKpmPageUri":"file:///sender/files/projects/hyb_import/hybrid_kpm_page_x.y8.gz","hybridKpmPageWidthPx":2,"hybridKpmPageHeightPx":2,"hybridKpmPageWidthMeters":1.0}"""
                .toByteArray()
        val imported = importZip(zipOf("project.json" to manifest, "hybrid_kpm_page_x.y8.gz" to byteArrayOf(9)))
        assertEquals(
            File(tempFilesDir, "projects/hyb_import/hybrid_kpm_page_x.y8.gz").canonicalFile,
            imported?.hybridKpmPageUri?.path?.let(::File)?.canonicalFile,
        )
    }

    @Test
    fun `legacy project has no hybrid KPM page`() = runTest {
        val projectDir = File(tempFilesDir, "projects/pre_hyb").also { it.mkdirs() }
        File(projectDir, "project.json").writeText("""{"id":"pre_hyb","name":"Old"}""")
        val loaded = manager.loadProjectMetadata(mockContext, "pre_hyb")
        assertNull(loaded?.hybridKpmPageUri)
        assertEquals(0, loaded?.hybridKpmPageWidthPx)
        assertTrue(loaded?.hybridKpmPageFromArtwork?.isEmpty() == true)
    }

    @Test
    fun `legacy project without SphereSLAM fields gets safe standalone defaults`() = runTest {
        val projectDir = File(tempFilesDir, "projects/pre_slam").also { it.mkdirs() }
        File(projectDir, "project.json").writeText(
            """{"id":"pre_slam","name":"Old project"}""",
        )

        val loaded = manager.loadProjectMetadata(mockContext, "pre_slam")

        assertNull(loaded?.sphereSlamReferenceUri)
        assertEquals(1f, loaded?.sphereSlamReferenceWidthMeters ?: 0f, 0f)
        assertFalse(loaded?.sphereSlamReferencePhysicallyMetric ?: true)
    }

    @Test
    fun `import rebases versioned SphereSLAM reference URI`() = runTest {
        val manifest =
            """{"id":"slam_import","name":"Wall","sphereSlamReferenceUri":"file:///sender/files/projects/slam_import/sphereslam_reference_abc.png","sphereSlamReferenceWidthMeters":2.5,"sphereSlamReferencePhysicallyMetric":true}"""
                .toByteArray()
        val imported = importZip(
            zipOf(
                "project.json" to manifest,
                "sphereslam_reference_abc.png" to byteArrayOf(1, 2, 3, 4),
            ),
        )

        val reference = imported?.sphereSlamReferenceUri?.path?.let(::File)
        assertEquals(
            File(tempFilesDir, "projects/slam_import/sphereslam_reference_abc.png").canonicalFile,
            reference?.canonicalFile,
        )
        assertEquals(2.5f, imported?.sphereSlamReferenceWidthMeters ?: 0f, 0f)
        assertTrue(imported?.sphereSlamReferencePhysicallyMetric == true)
    }

    @Test
    fun `process death before SphereSLAM metadata commit restores previous reference`() = runTest {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val oldUri = manager.saveSphereSlamReference(mockContext, "slam_crash_before", bitmap)
        manager.saveProject(
            mockContext,
            GraffitiProject(
                id = "slam_crash_before",
                name = "Wall",
                sphereSlamReferenceUri = oldUri,
                sphereSlamReferenceWidthMeters = 1.25f,
                sphereSlamReferencePhysicallyMetric = true,
            ),
        )

        // This write represents the candidate file existing when the process dies before the
        // repository can commit its URI + scale metadata.
        val orphanCandidate =
            manager.saveSphereSlamReference(mockContext, "slam_crash_before", bitmap)

        val restored = manager.loadProjectMetadata(mockContext, "slam_crash_before")
        assertEquals(oldUri, restored?.sphereSlamReferenceUri)
        assertEquals(1.25f, restored?.sphereSlamReferenceWidthMeters ?: 0f, 0f)
        assertTrue(File(requireNotNull(oldUri.path)).exists())
        assertTrue(File(requireNotNull(orphanCandidate.path)).exists())
    }

    @Test
    fun `process death after SphereSLAM metadata commit restores new reference even if old file remains`() = runTest {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val oldUri = manager.saveSphereSlamReference(mockContext, "slam_crash_after", bitmap)
        val newUri = manager.saveSphereSlamReference(mockContext, "slam_crash_after", bitmap)
        manager.saveProject(
            mockContext,
            GraffitiProject(
                id = "slam_crash_after",
                name = "Wall",
                sphereSlamReferenceUri = newUri,
                sphereSlamReferenceWidthMeters = 2.75f,
                sphereSlamReferencePhysicallyMetric = true,
            ),
        )

        // Simulate death before best-effort cleanup of oldUri.
        val restored = manager.loadProjectMetadata(mockContext, "slam_crash_after")
        assertEquals(newUri, restored?.sphereSlamReferenceUri)
        assertEquals(2.75f, restored?.sphereSlamReferenceWidthMeters ?: 0f, 0f)
        assertTrue(File(requireNotNull(oldUri.path)).exists())
        assertTrue(File(requireNotNull(newUri.path)).exists())
    }

    @Test
    fun `duplicate-id import rebases SphereSLAM reference into newly assigned project id`() = runTest {
        manager.saveProject(
            mockContext,
            GraffitiProject(id = "same_slam", name = "Existing"),
        )
        val manifest =
            """{"id":"same_slam","name":"Imported","sphereSlamReferenceUri":"file:///sender/files/projects/same_slam/sphereslam_reference_abc.png","sphereSlamReferenceWidthMeters":2.0,"sphereSlamReferencePhysicallyMetric":true}"""
                .toByteArray()

        val imported = importZip(
            zipOf(
                "project.json" to manifest,
                "sphereslam_reference_abc.png" to byteArrayOf(9, 8, 7),
            ),
        )

        assertNotNull(imported)
        assertTrue(imported!!.id != "same_slam")
        assertEquals(
            File(
                tempFilesDir,
                "projects/${imported.id}/sphereslam_reference_abc.png",
            ).canonicalFile,
            File(requireNotNull(imported.sphereSlamReferenceUri?.path)).canonicalFile,
        )
        assertNull(
            manager.loadProjectMetadata(mockContext, "same_slam")?.sphereSlamReferenceUri,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `spectator transfer rebases SphereSLAM reference into coop project`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val captured = mutableListOf<GraffitiProject>()
            val repo =
                mockk<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>(relaxed = true)
            coEvery { repo.createProject(any<GraffitiProject>()) } coAnswers {
                captured += firstArg<GraffitiProject>()
            }
            val provider =
                mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>()
            every { provider.get() } returns repo
            val coopManager = ProjectManager(mockContext, uriProvider, provider)

            val manifest =
                """{"id":"host_slam","name":"Host","sphereSlamReferenceUri":"file:///host/files/projects/host_slam/sphereslam_reference_abc.png","sphereSlamReferenceWidthMeters":1.75,"sphereSlamReferencePhysicallyMetric":true}"""
                    .toByteArray()

            val loaded = coopManager.loadAsSpectator(
                zipOf(
                    "project.json" to manifest,
                    "sphereslam_reference_abc.png" to byteArrayOf(1, 3, 5, 7),
                ),
            )

            assertTrue(loaded)
            assertEquals(1, captured.size)
            val spectator = captured.single()
            assertEquals("coop_host_slam", spectator.id)
            assertEquals(
                File(
                    tempFilesDir,
                    "projects/coop_host_slam/sphereslam_reference_abc.png",
                ).canonicalFile,
                File(requireNotNull(spectator.sphereSlamReferenceUri?.path)).canonicalFile,
            )
            assertEquals(1.75f, spectator.sphereSlamReferenceWidthMeters, 0f)
            assertTrue(spectator.sphereSlamReferencePhysicallyMetric)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // --- Zip extraction temp-file cleanup (duplicate entry names) ---

    @Test
    fun `import deletes the superseded temp file on duplicate entry names`() = runTest {
        // Both entries strip (first path segment removed) to the same relative name "dup.png" — the
        // LATER entry must win, and the EARLIER entry's temp file must not leak into the cache dir.
        val zip = zipOf(
            "project.json" to projectJson("dup_project"),
            "a/dup.png" to byteArrayOf(1, 1, 1),
            "b/dup.png" to byteArrayOf(2, 2, 2, 2),
        )

        val result = importZip(zip)

        assertEquals("dup_project", result?.id)
        val destFile = File(tempFilesDir, "projects/dup_project/dup.png")
        assertTrue(destFile.exists())
        assertArrayEquals(byteArrayOf(2, 2, 2, 2), destFile.readBytes())

        val cacheDir = File(tempFilesDir, "cache")
        val leftoverTempFiles = cacheDir.listFiles { f -> f.name.startsWith("gxr_") } ?: emptyArray()
        assertTrue(
            "cache dir must not accumulate a leaked temp file from the superseded duplicate: " +
                leftoverTempFiles.joinToString { it.name },
            leftoverTempFiles.isEmpty(),
        )
    }

}

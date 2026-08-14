package com.hereliesaz.graffitixr.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.CaptureEnvironment
import com.hereliesaz.graffitixr.common.model.DeviceAttitude
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.LocationFix
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class ProjectManagerTest {

    private lateinit var mockContext: Context
    private lateinit var tempFilesDir: File
    private lateinit var uriProvider: UriProvider
    private lateinit var manager: ProjectManager

    @Before
    fun setup() {
        tempFilesDir = File(System.getProperty("java.io.tmpdir"), "graffitixr_test_files")
        tempFilesDir.mkdirs()

        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempFilesDir
        every { mockContext.cacheDir } returns File(tempFilesDir, "cache").also { it.mkdirs() }

        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        every { Uri.fromFile(any()) } returns mockk(relaxed = true)

        // The hardened import/spectator paths log skipped hostile entries; android.util.Log is
        // not available in plain JVM tests.
        mockkStatic(android.util.Log::class)
        every { android.util.Log.e(any(), any()) } returns 0
        every { android.util.Log.e(any(), any(), any()) } returns 0
        every { android.util.Log.w(any(), any<String>()) } returns 0
        every { android.util.Log.i(any(), any()) } returns 0

        uriProvider = mockk(relaxed = true)
        val projectRepositoryProvider = mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>(relaxed = true)
        manager = ProjectManager(mockContext, uriProvider, projectRepositoryProvider)
    }

    @After
    fun teardown() {
        tempFilesDir.deleteRecursively()
        unmockkStatic(Uri::class)
        unmockkStatic(android.util.Log::class)
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
    fun `importProjectFromUri fails gracefully on bad URI`() = runTest {
        val mockUri = mockk<Uri>(relaxed = true)
        val mockResolver = mockk<android.content.ContentResolver>(relaxed = true)

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
        val mockUri = mockk<Uri>(relaxed = true)
        val mockResolver = mockk<android.content.ContentResolver>(relaxed = true)
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
            assertTrue(File(tempFilesDir, "projects/spec_safe/layer.png").exists())
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

    /** A [UriProvider] whose returned [Uri] mocks expose a real, deletable file path. */
    private fun realFileUriProvider(): UriProvider = object : UriProvider {
        override fun getUriForFile(file: File): Uri {
            val u = mockk<Uri>(relaxed = true)
            every { u.path } returns file.absolutePath
            every { u.toString() } returns "file://${file.absolutePath}"
            return u
        }
    }

    @Test
    fun `saveProject prunes target images beyond the cap and deletes their files`() = runTest {
        // The default `Uri.parse` stub from setup() returns the SAME opaque mock for every string,
        // which would make a round-tripped URI's `.path` meaningless. Override it here so decoding
        // the "file://..." strings this test's UriProvider writes reconstructs a working `.path`,
        // mirroring what real android.net.Uri.parse/.fromFile actually do.
        every { Uri.parse(any()) } answers {
            val s = firstArg<String>()
            mockk<Uri>(relaxed = true).also { m ->
                every { m.path } returns s.removePrefix("file://")
                every { m.toString() } returns s
            }
        }

        val projectRepositoryProvider = mockk<javax.inject.Provider<com.hereliesaz.graffitixr.domain.repository.ProjectRepository>>(relaxed = true)
        val pruningManager = ProjectManager(mockContext, realFileUriProvider(), projectRepositoryProvider)
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
        val pruningManager = ProjectManager(mockContext, realFileUriProvider(), projectRepositoryProvider)
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

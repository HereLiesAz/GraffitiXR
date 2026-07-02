package com.hereliesaz.graffitixr.data

import android.content.Context
import android.net.Uri
import com.hereliesaz.graffitixr.common.model.GraffitiProject
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
    fun `getMapPath returns correct path and creates directory`() = runTest {
        val path = manager.getMapPath(mockContext, "map_project")
        val expectedFile = File(tempFilesDir, "projects/map_project/map.bin")
        assertEquals(expectedFile.absolutePath, path)
        assertTrue(expectedFile.parentFile.exists())
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
}
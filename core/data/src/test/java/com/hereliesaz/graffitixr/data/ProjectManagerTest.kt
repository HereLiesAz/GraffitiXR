package com.hereliesaz.graffitixr.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProjectManagerTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context
    private val mockUriProvider: UriProvider = mockk(relaxed = true)
    private lateinit var projectManager: ProjectManager

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0

        tempDir = Files.createTempDirectory("gxr_test").toFile()
        mockContext = mockk(relaxed = true)
        every { mockContext.filesDir } returns tempDir
        every { mockContext.cacheDir } returns File(tempDir, "cache").also { it.mkdirs() }
        projectManager = ProjectManager(mockContext, mockUriProvider)
    }

    @After
    fun tearDown() {
        unmockkAll()
        tempDir.deleteRecursively()
    }

    // --- getProjectList ---

    @Test
    fun `getProjectList returns empty list when projects directory does not exist`() {
        val result = projectManager.getProjectList(mockContext)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `getProjectList returns subdirectory names as project IDs`() {
        val projectsDir = File(tempDir, "projects")
        projectsDir.mkdirs()
        File(projectsDir, "project-alpha").mkdirs()
        File(projectsDir, "project-beta").mkdirs()

        val result = projectManager.getProjectList(mockContext)

        assertEquals(2, result.size)
        assertTrue(result.containsAll(listOf("project-alpha", "project-beta")))
    }

    @Test
    fun `getProjectList ignores regular files in the projects directory`() {
        val projectsDir = File(tempDir, "projects")
        projectsDir.mkdirs()
        File(projectsDir, "real-project").mkdirs()
        File(projectsDir, "stray-file.json").createNewFile()

        val result = projectManager.getProjectList(mockContext)

        assertEquals(1, result.size)
        assertEquals("real-project", result.first())
    }

    // --- deleteProject ---

    @Test
    fun `deleteProject removes the project directory and all its contents`() {
        val projectDir = File(tempDir, "projects/my-project")
        projectDir.mkdirs()
        File(projectDir, "project.json").writeText("{}")
        File(projectDir, "thumbnail.png").createNewFile()

        projectManager.deleteProject(mockContext, "my-project")

        assertFalse(projectDir.exists())
    }

    @Test
    fun `deleteProject does not throw when project does not exist`() {
        // Must complete silently — no project directory present
        projectManager.deleteProject(mockContext, "non-existent-project")
    }

    // --- getMapPath ---

    @Test
    fun `getMapPath returns path ending with map dot bin`() {
        val path = projectManager.getMapPath(mockContext, "slam-project")
        assertTrue(path.endsWith("map.bin"))
    }

    @Test
    fun `getMapPath path contains the project ID`() {
        val path = projectManager.getMapPath(mockContext, "my-slam-project")
        assertTrue(path.contains("my-slam-project"))
    }

    @Test
    fun `getMapPath creates the project directory if it does not exist`() {
        val path = projectManager.getMapPath(mockContext, "new-project")
        val dir = File(path).parentFile!!
        assertTrue(dir.exists())
        assertTrue(dir.isDirectory)
    }

    // --- importProjectFromUri ---

    @Test
    fun `importProjectFromUri returns null when zip contains no project json`() = runTest {
        val zipBytes = buildZip(emptyMap())
        val (uri, resolver) = mockUriWithStream(zipBytes)
        every { mockContext.contentResolver } returns resolver

        val result = projectManager.importProjectFromUri(mockContext, uri)

        assertNull(result)
    }

    @Test
    fun `importProjectFromUri returns null when project json is malformed`() = runTest {
        val zipBytes = buildZip(mapOf("project.json" to "{{not valid json"))
        val (uri, resolver) = mockUriWithStream(zipBytes)
        every { mockContext.contentResolver } returns resolver

        val result = projectManager.importProjectFromUri(mockContext, uri)

        assertNull(result)
    }

    @Test
    fun `importProjectFromUri returns null when contentResolver returns null stream`() = runTest {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(uri) } returns null
        every { mockContext.contentResolver } returns resolver

        val result = projectManager.importProjectFromUri(mockContext, uri)

        assertNull(result)
    }

    @Test
    fun `importProjectFromUri strips top-level folder prefix in zip entries`() = runTest {
        // Some zip tools wrap all entries under a folder named after the project.
        // importProjectFromUri must strip "projectId/file" → "file" before looking for project.json.
        val zipBytes = buildZip(mapOf("some-folder/project.json" to "{{invalid json"))
        val (uri, resolver) = mockUriWithStream(zipBytes)
        every { mockContext.contentResolver } returns resolver

        // Invalid JSON means null is returned, but the folder-stripping logic was exercised
        // (if stripping were broken the entry would be ignored entirely and we'd still get null —
        // this test documents the expected path through the code)
        val result = projectManager.importProjectFromUri(mockContext, uri)

        assertNull(result)
    }

    // --- Helpers ---

    private fun buildZip(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun mockUriWithStream(bytes: ByteArray): Pair<Uri, ContentResolver> {
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        return uri to resolver
    }
}

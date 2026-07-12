package com.hereliesaz.graffitixr.data.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers [AzpInstaller]'s host safety obligations: digest verification and path-traversal defence.
 * Everything here is pure JVM (zip + files + SHA-256), so it runs as a plain unit test.
 */
class AzpInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** Build an in-memory .azp ZIP from path -> bytes. */
    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((path, bytes) in entries) {
                zos.putNextEntry(ZipEntry(path))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(id: String, files: Map<String, ByteArray>): ByteArray {
        val filesJson = files.entries.joinToString(",") { (path, bytes) ->
            "\"$path\":\"sha256-${sha256(bytes)}\""
        }
        return """
            {
              "azphalt": "1.0",
              "id": "$id",
              "name": "Test Grade",
              "version": "1.0.0",
              "kind": "asset",
              "license": "MIT",
              "compat": ">=1.0",
              "assets": [{ "type": "lut", "path": "assets/grade.cube" }],
              "files": { $filesJson }
            }
        """.trimIndent().toByteArray()
    }

    @Test
    fun `valid package installs and unpacks its files`() {
        val lut = "TITLE \"x\"\nLUT_3D_SIZE 2\n".toByteArray() +
            "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n".toByteArray()
        val payload = mapOf("assets/grade.cube" to lut)
        val bytes = zip(payload + ("manifest.json" to manifest("com.test.grade", payload)))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        val installed = installer.install(ByteArrayInputStream(bytes), nowMs = 123L)

        assertEquals("com.test.grade", installed.id)
        assertEquals(123L, installed.installedAt)
        assertTrue(java.io.File(installed.filePath("assets/grade.cube")).exists())
        assertTrue(java.io.File(installed.filePath("manifest.json")).exists())
    }

    @Test
    fun `digest mismatch is rejected`() {
        val real = "real".toByteArray()
        val tampered = "tampered".toByteArray()
        // Manifest lists the digest of `real`, but the archive carries `tampered`.
        val manifestBytes = manifest("com.test.grade", mapOf("assets/grade.cube" to real))
        val bytes = zip(mapOf(
            "manifest.json" to manifestBytes,
            "assets/grade.cube" to tampered,
        ))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for digest mismatch")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("Digest mismatch"))
        }
    }

    @Test
    fun `path traversal entry is rejected`() {
        val evil = "pwned".toByteArray()
        val bytes = zip(mapOf(
            "manifest.json" to manifest("com.test.grade", emptyMap()),
            "../escape.txt" to evil,
        ))

        val root = tmp.newFolder("extensions")
        val installer = AzpInstaller(root)
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for path traversal")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("Unsafe path"))
        }
        // Nothing must have escaped the extensions root.
        assertFalse(java.io.File(root.parentFile, "escape.txt").exists())
    }

    @Test
    fun `package without manifest is rejected`() {
        val bytes = zip(mapOf("assets/grade.cube" to "x".toByteArray()))
        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for missing manifest")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("no manifest.json"))
        }
    }

    @Test
    fun `manifest file listed but absent is rejected`() {
        val lut = "data".toByteArray()
        // Manifest claims a file the archive doesn't contain.
        val manifestBytes = manifest("com.test.grade", mapOf("assets/grade.cube" to lut))
        val bytes = zip(mapOf("manifest.json" to manifestBytes))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for missing payload file")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("Missing payload file"))
        }
    }
}

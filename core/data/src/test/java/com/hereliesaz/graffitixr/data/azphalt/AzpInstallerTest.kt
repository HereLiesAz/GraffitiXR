package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.TrustedKey
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
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
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers [AzpInstaller]'s host safety and asset-host-conformance obligations: digest verification,
 * path-traversal defence, the required LICENSE, rejecting code packages, and `compat` validation.
 * Everything here is pure JVM (zip + files + SHA-256), so it runs as a plain unit test.
 */
class AzpInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val license = "MIT\n".toByteArray()

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

    /** A manifest.json whose `files` map is exactly [files], with the given kind/compat. */
    private fun manifest(
        id: String,
        files: Map<String, ByteArray>,
        kind: String = "asset",
        compat: String = ">=0.1",
    ): ByteArray {
        val filesJson = files.entries.joinToString(",") { (path, bytes) ->
            "\"$path\":\"sha256-${sha256(bytes)}\""
        }
        return """
            {
              "azphalt": "0.1",
              "id": "$id",
              "name": "Test Grade",
              "version": "1.0.0",
              "kind": "$kind",
              "license": "MIT",
              "compat": "$compat",
              "assets": [{ "type": "lut", "path": "assets/grade.cube" }],
              "files": { $filesJson }
            }
        """.trimIndent().toByteArray()
    }

    @Test
    fun `valid package installs and unpacks its files`() {
        val lut = "TITLE \"x\"\nLUT_3D_SIZE 2\n".toByteArray() +
            "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n".toByteArray()
        val payload = mapOf("assets/grade.cube" to lut, "LICENSE" to license)
        val bytes = zip(payload + ("manifest.json" to manifest("com.test.grade", payload)))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        val installed = installer.install(ByteArrayInputStream(bytes), nowMs = 123L)

        assertEquals("com.test.grade", installed.id)
        assertEquals(123L, installed.installedAt)
        assertTrue(java.io.File(installed.filePath("assets/grade.cube")).exists())
        assertTrue(java.io.File(installed.filePath("manifest.json")).exists())
        assertTrue(java.io.File(installed.filePath("LICENSE")).exists())
    }

    @Test
    fun `digest mismatch is rejected`() {
        val real = "real".toByteArray()
        val tampered = "tampered".toByteArray()
        // Manifest lists the digest of `real`, but the archive carries `tampered`.
        val manifestBytes = manifest(
            "com.test.grade",
            mapOf("assets/grade.cube" to real, "LICENSE" to license),
        )
        val bytes = zip(mapOf(
            "manifest.json" to manifestBytes,
            "assets/grade.cube" to tampered,
            "LICENSE" to license,
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
    fun `unlisted file is not unpacked`() {
        val lut = "grade".toByteArray()
        val payload = mapOf("assets/grade.cube" to lut, "LICENSE" to license)
        // A stowaway file the manifest does not declare — it passed no digest check, so it must
        // never land on disk.
        val bytes = zip(payload + mapOf(
            "manifest.json" to manifest("com.test.grade", payload),
            "assets/stowaway.js" to "alert(1)".toByteArray(),
        ))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        val installed = installer.install(ByteArrayInputStream(bytes), nowMs = 0L)

        assertTrue(java.io.File(installed.filePath("assets/grade.cube")).exists())
        assertFalse(java.io.File(installed.filePath("assets/stowaway.js")).exists())
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
        // Manifest claims a file the archive doesn't contain (but LICENSE is present).
        val manifestBytes = manifest(
            "com.test.grade",
            mapOf("assets/grade.cube" to lut, "LICENSE" to license),
        )
        val bytes = zip(mapOf("manifest.json" to manifestBytes, "LICENSE" to license))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for missing payload file")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("Missing payload file"))
        }
    }

    @Test
    fun `code package is rejected by the asset-only host`() {
        val payload = mapOf("LICENSE" to license)
        val bytes = zip(payload + ("manifest.json" to manifest("com.test.code", payload, kind = "code")))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for kind=code")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("kind=code"))
        }
    }

    @Test
    fun `incompatible compat is rejected`() {
        val payload = mapOf("LICENSE" to license)
        // Needs a newer spec than this host implements (0.1).
        val bytes = zip(payload + ("manifest.json" to manifest("com.test.future", payload, compat = ">=0.9")))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for incompatible compat")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("needs azphalt"))
        }
    }

    @Test
    fun `package without LICENSE is rejected`() {
        val lut = "grade".toByteArray()
        val payload = mapOf("assets/grade.cube" to lut)
        val bytes = zip(payload + ("manifest.json" to manifest("com.test.grade", payload)))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for missing LICENSE")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("LICENSE"))
        }
    }

    // ── signature / provenance ───────────────────────────────────────────────────────────────────

    private val lutBytes = "TITLE \"x\"\nLUT_3D_SIZE 2\n".toByteArray() +
        "0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n".toByteArray()

    /** Build a `.azp` whose `signature.json` signs [manifestBytes] with [signedBytes] (default: the
     *  manifest itself → a valid signature; pass different bytes to forge an invalid one). */
    private fun signedPackage(
        pub: Ed25519PublicKeyParameters,
        priv: Ed25519PrivateKeyParameters,
        id: String = "com.test.signed",
        signedBytes: ByteArray? = null,
    ): ByteArray {
        val payload = mapOf("assets/grade.cube" to lutBytes, "LICENSE" to license)
        val manifestBytes = manifest(id, payload)
        val sig = Base64.getEncoder().encodeToString(sign(priv, signedBytes ?: manifestBytes))
        val sigJson = """{"alg":"ed25519","publicKey":"${spkiB64(pub)}","signature":"$sig"}""".toByteArray()
        return zip(payload + mapOf("manifest.json" to manifestBytes, "signature.json" to sigJson))
    }

    @Test
    fun `valid signature installs as SIGNED_UNTRUSTED with an empty trust store`() {
        val (priv, pub) = keyPair()
        val bytes = signedPackage(pub, priv)

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        val installed = installer.install(ByteArrayInputStream(bytes), nowMs = 0L)

        assertEquals(SignatureStatus.SIGNED_UNTRUSTED, installed.signature)
        // signature.json is preserved so provenance survives a rescan.
        assertTrue(java.io.File(installed.filePath("signature.json")).exists())
    }

    @Test
    fun `valid signature from a trusted key installs as SIGNED_TRUSTED`() {
        val (priv, pub) = keyPair()
        val bytes = signedPackage(pub, priv)

        val store = TrustStore(listOf(TrustedKey(spkiB64(pub))))
        val installer = AzpInstaller(tmp.newFolder("extensions"), store)
        val installed = installer.install(ByteArrayInputStream(bytes), nowMs = 0L)

        assertEquals(SignatureStatus.SIGNED_TRUSTED, installed.signature)
    }

    @Test
    fun `invalid signature is rejected`() {
        val (priv, pub) = keyPair()
        // Sign bytes that are not the manifest → the signature won't verify over manifest.json.
        val bytes = signedPackage(pub, priv, signedBytes = "not the manifest".toByteArray())

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        try {
            installer.install(ByteArrayInputStream(bytes), nowMs = 0L)
            fail("Expected InstallException for an invalid signature")
        } catch (e: AzpInstaller.InstallException) {
            assertTrue(e.message!!.contains("invalid signature"))
        }
    }

    @Test
    fun `unsigned package installs as UNSIGNED`() {
        val payload = mapOf("assets/grade.cube" to lutBytes, "LICENSE" to license)
        val bytes = zip(payload + ("manifest.json" to manifest("com.test.unsigned", payload)))

        val installer = AzpInstaller(tmp.newFolder("extensions"))
        val installed = installer.install(ByteArrayInputStream(bytes), nowMs = 0L)

        assertEquals(SignatureStatus.UNSIGNED, installed.signature)
    }

    // ── Ed25519 signing helpers (BouncyCastle) ───────────────────────────────────────────────────

    /** Fixed 12-byte Ed25519 SPKI DER header; the raw 32-byte key follows (RFC 8410). */
    private val ed25519SpkiPrefix =
        intArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
            .map { it.toByte() }.toByteArray()

    private fun spkiB64(pub: Ed25519PublicKeyParameters): String =
        Base64.getEncoder().encodeToString(ed25519SpkiPrefix + pub.encoded)

    private fun keyPair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = gen.generateKeyPair()
        return (kp.private as Ed25519PrivateKeyParameters) to (kp.public as Ed25519PublicKeyParameters)
    }

    private fun sign(priv: Ed25519PrivateKeyParameters, msg: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(msg, 0, msg.size)
        return signer.generateSignature()
    }
}

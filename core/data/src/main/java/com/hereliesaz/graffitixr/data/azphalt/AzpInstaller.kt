package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AZPHALT_SPEC_VERSION
import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.AzpSignatures
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.SignatureStatus
import com.hereliesaz.graffitixr.common.azphalt.TrustStore
import com.hereliesaz.graffitixr.common.azphalt.isCompatibleSpec
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Reads, verifies, and unpacks `.azp` packages (the ZIP container from azphalt spec/package-format.md)
 * into `<extensionsRoot>/<id>/`. Enforces the host's safety obligations:
 *  - reject unsafe entry paths (absolute, `..` traversal),
 *  - verify every payload file listed in the manifest's `files` map against its SHA-256 digest,
 *  - require a manifest.json.
 *
 * Signature (Ed25519 over the verbatim `manifest.json`) is verified when present: a package carrying
 * an invalid signature is refused (tamper-evidence), while an unsigned or signed-but-untrusted package
 * installs with its provenance recorded on [InstalledExtension.signature]. Trust (identity) is decided
 * against [trustStore] — directly, or via a registry counter-signature (spec/package-format.md § Signing).
 */
class AzpInstaller(
    private val extensionsRoot: File,
    private val trustStore: TrustStore = TrustStore.EMPTY,
) {

    class InstallException(message: String) : Exception(message)

    companion object {
        /** Cumulative decompressed-size ceiling for a `.azp` — a zip-bomb guard on untrusted input.
         *  Asset packages are small (bundled multi-GB models use `remoteUrl`, not the archive). */
        const val MAX_PACKAGE_BYTES: Long = 64L * 1024 * 1024
    }

    /**
     * Verify and unpack a `.azp` from [input] (a ZIP stream). Returns the [InstalledExtension].
     * Overwrites any prior install of the same id. Throws [InstallException] on any safety/integrity
     * failure, leaving no partial install for that id.
     */
    fun install(input: InputStream, nowMs: Long): InstalledExtension {
        // Read the whole archive into memory (we must parse the manifest to know the digests before we
        // trust any file). The source can be an attacker-controlled URL, so bound the CUMULATIVE
        // decompressed size while streaming and abort a zip bomb before it can OOM the app — never
        // `readBytes()` an entry unbounded.
        val entries = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        val chunk = ByteArray(64 * 1024)
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (isUnsafePath(name)) throw InstallException("Unsafe path in package: $name")
                    val out = ByteArrayOutputStream()
                    while (true) {
                        val n = zip.read(chunk)
                        if (n < 0) break
                        totalBytes += n
                        if (totalBytes > MAX_PACKAGE_BYTES) {
                            throw InstallException("Package exceeds the ${MAX_PACKAGE_BYTES / (1024 * 1024)} MB limit")
                        }
                        out.write(chunk, 0, n)
                    }
                    entries[name] = out.toByteArray()
                }
                zip.closeEntry()
                e = zip.nextEntry
            }
        }

        val manifestBytes = entries["manifest.json"]
            ?: throw InstallException("Package has no manifest.json")
        val manifest: AzphaltManifest = try {
            parseManifest(manifestBytes.decodeToString())
        } catch (t: Throwable) {
            throw InstallException("Invalid manifest.json: ${t.message}")
        }

        // Asset-host policy (spec/ADOPTION_ASSET_HOST.md). GraffitiXR runs no extension code, so:
        //  - reject `kind: "code"` outright;
        //  - a `mixed` package installs, but only its assets are ever used (its entry/runtime are
        //    ignored downstream — the repository only reads `manifest.assets`).
        if (manifest.kind == ExtensionKind.CODE) {
            throw InstallException("This host installs asset extensions only; '${manifest.id}' is kind=code")
        }

        // Conformance: validate the declared spec compatibility against what this host implements.
        if (!isCompatibleSpec(manifest.compat)) {
            throw InstallException(
                "Package '${manifest.id}' needs azphalt ${manifest.compat}; host implements $AZPHALT_SPEC_VERSION"
            )
        }

        // The package format requires a LICENSE file; refuse a package that omits it.
        if (!entries.containsKey("LICENSE")) {
            throw InstallException("Package '${manifest.id}' is missing the required LICENSE file")
        }

        // Integrity: every file the manifest lists must be present and match its digest.
        for ((path, digest) in manifest.files) {
            val bytes = entries[path] ?: throw InstallException("Missing payload file: $path")
            val actual = "sha256-" + sha256Hex(bytes)
            if (!actual.equals(normalizeDigest(digest), ignoreCase = true)) {
                throw InstallException("Digest mismatch for $path")
            }
        }

        // Provenance: verify the detached Ed25519 signature (if any) over the *verbatim* manifest.json
        // bytes. A present-but-invalid signature is tamper-evidence — refuse it. An unsigned or
        // signed-but-untrusted package installs, with its status recorded for the UI to warn on.
        val signatureJson = entries["signature.json"]?.decodeToString()
        val signatureStatus = AzpSignatures.evaluate(manifestBytes, signatureJson, trustStore)
        if (signatureStatus == SignatureStatus.INVALID) {
            throw InstallException("Package '${manifest.id}' has an invalid signature (tampered or corrupt)")
        }

        // Unpack into a dot-prefixed staging dir first, then atomically swap it into place — so an
        // IOException mid-unpack (or a path-escape) can never leave a partial <id>/ install, honouring
        // the "no partial install" contract. The staging name starts with '.' so a concurrent rescan
        // skips it. (The rescan filter in ExtensionRepository ignores dot-prefixed dirs.)
        val dir = File(extensionsRoot, safeId(manifest.id))
        val staging = File(extensionsRoot, ".staging-${safeId(manifest.id)}-$nowMs")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        try {
            for ((path, bytes) in entries) {
                // Only unpack manifest.json, the detached signature.json (exempt from the files map, so
                // provenance can be re-derived on rescan), and files the manifest declares (which passed
                // the digest check above). An unlisted entry is an unverified payload — never write it.
                if (path != "manifest.json" && path != "signature.json" && !manifest.files.containsKey(path)) continue
                val target = File(staging, path)
                // Second-line defence: the resolved target must stay inside the staging dir.
                if (!target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    throw InstallException("Path escapes extension dir: $path")
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            // Swap in: drop any prior install, then move staging into place.
            if (dir.exists()) dir.deleteRecursively()
            if (!staging.renameTo(dir)) {
                throw InstallException("Failed to finalize install for '${manifest.id}'")
            }
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }

        return InstalledExtension(
            manifest = manifest,
            dir = dir.absolutePath,
            installedAt = nowMs,
            signature = signatureStatus,
        )
    }

    private fun isUnsafePath(name: String): Boolean {
        if (name.startsWith("/") || name.startsWith("\\") || name.contains(":")) return true
        return name.split('/', '\\').any { it == ".." }
    }

    // Reverse-DNS ids are filesystem-safe, but defend anyway: keep only [A-Za-z0-9._-].
    private fun safeId(id: String): String = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun normalizeDigest(d: String): String = if (d.startsWith("sha256-")) d else "sha256-$d"

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

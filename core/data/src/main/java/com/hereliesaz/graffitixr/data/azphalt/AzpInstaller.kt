package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AZPHALT_SPEC_VERSION
import com.hereliesaz.graffitixr.common.azphalt.AzphaltManifest
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import com.hereliesaz.graffitixr.common.azphalt.isCompatibleSpec
import com.hereliesaz.graffitixr.common.azphalt.parseManifest
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
 * Signature (Ed25519) is treated as tamper-evidence, not identity — the trust model is explicitly
 * open in the spec — so the integrity digests are the enforced gate here.
 */
class AzpInstaller(private val extensionsRoot: File) {

    class InstallException(message: String) : Exception(message)

    /**
     * Verify and unpack a `.azp` from [input] (a ZIP stream). Returns the [InstalledExtension].
     * Overwrites any prior install of the same id. Throws [InstallException] on any safety/integrity
     * failure, leaving no partial install for that id.
     */
    fun install(input: InputStream, nowMs: Long): InstalledExtension {
        // Read the whole archive into memory first: we must parse the manifest to know the digests
        // before we trust any file, and a .azp is small. Map of entry path → bytes.
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) {
                    val name = e.name
                    if (isUnsafePath(name)) throw InstallException("Unsafe path in package: $name")
                    entries[name] = zip.readBytes()
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

        // Unpack into <root>/<safe id>/, replacing any prior install.
        val dir = File(extensionsRoot, safeId(manifest.id))
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        for ((path, bytes) in entries) {
            // Only unpack manifest.json and files the manifest declares (and which therefore passed
            // the digest check above). An unlisted entry is an unverified payload — never write it.
            if (path != "manifest.json" && !manifest.files.containsKey(path)) continue
            val target = File(dir, path)
            // Second-line defence: the resolved target must stay inside dir.
            if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                dir.deleteRecursively()
                throw InstallException("Path escapes extension dir: $path")
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }

        return InstalledExtension(manifest = manifest, dir = dir.absolutePath, installedAt = nowMs)
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

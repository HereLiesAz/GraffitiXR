package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.util.PublicKeyFactory
import java.util.Base64

/**
 * The detached `signature.json` carried by a signed `.azp` (azphalt spec/package-format.md § Signing).
 *
 * A signature is an **Ed25519** signature over the **exact `manifest.json` byte sequence stored in the
 * archive** — verbatim, with **no re-canonicalization** (no JCS/RFC 8785, no whitespace normalization,
 * no key reordering). `publicKey` is base64 **SPKI** (DER `SubjectPublicKeyInfo`). Signing the manifest
 * transitively signs the payload through the `files` digests. This mirrors `@azphalt/azp`'s
 * `AzpSignature`, so a package signed by the reference `signAzp` verifies here byte-for-byte — proven
 * against the published test vector (`packages/azp/test/vectors/signature-vector.json`) in the tests.
 */
@Serializable
data class AzpSignature(
    val alg: String,
    /** Base64 SPKI public key the signature verifies against. */
    val publicKey: String,
    /** Base64 Ed25519 signature over the `manifest.json` bytes. */
    val signature: String,
    /** Optional signer-chosen key identifier (informational; trust matches on [publicKey]). */
    val keyId: String? = null,
    /** Optional registry counter-signature vouching for [publicKey] — see [AzpCountersignature]. */
    val countersignature: AzpCountersignature? = null,
)

/**
 * A counter-signature: an Ed25519 signature over the **SPKI bytes of the key below it** in the chain
 * (the author at the base, or the previous counter-signer). Counter-signatures nest, forming a
 * web-of-trust chain a host walks in [evaluateTrust].
 */
@Serializable
data class AzpCountersignature(
    /** Base64 SPKI public key of the registry/authority making this vouch. */
    val publicKey: String,
    /** Base64 Ed25519 signature over the vouched-for key's SPKI DER bytes. */
    val signature: String,
    val keyId: String? = null,
    /** A further counter-signature vouching for [publicKey] — the next hop up the chain. */
    val countersignature: AzpCountersignature? = null,
)

/**
 * A package's provenance, mirroring the spec's integrity-vs-identity split: a valid signature is
 * tamper-evidence; *trust* is a separate decision against a [TrustStore].
 */
enum class SignatureStatus {
    /** No `signature.json` — integrity only, no established provenance. */
    UNSIGNED,

    /** Valid Ed25519 signature, but the signer is not in the trust store. */
    SIGNED_UNTRUSTED,

    /** Valid signature AND the signer is trusted (directly, or via a registry counter-signature). */
    SIGNED_TRUSTED,

    /** A `signature.json` is present but does not verify — tampered/corrupt; a host MUST refuse it. */
    INVALID,
}

/**
 * Ed25519 signature + trust checks for `.azp` packages. Pure/JVM — BouncyCastle (Android's built-in
 * Ed25519 is API 33+, but this app's minSdk is 26) and `java.util.Base64` (API 26+) — so parse and
 * verify are unit-testable off-device, like [CubeLut].
 */
object AzpSignatures {

    /** Parse a `signature.json` body, or `null` if it's absent/blank/malformed. */
    fun parse(signatureJson: String?): AzpSignature? {
        if (signatureJson.isNullOrBlank()) return null
        // Explicit serializer (not the reified extension) so parsing needs no extra import.
        return runCatching { AzphaltJson.decodeFromString(AzpSignature.serializer(), signatureJson) }.getOrNull()
    }

    /**
     * Verify an Ed25519 [signatureB64] over [message] against a base64 SPKI [publicKeyB64]. Returns
     * `false` on any malformation (bad base64, non-Ed25519 key, wrong signature length) rather than
     * throwing — mirroring the reference's "return false, don't crash" posture.
     */
    fun ed25519Verify(publicKeyB64: String, message: ByteArray, signatureB64: String): Boolean =
        runCatching {
            val spki = Base64.getDecoder().decode(publicKeyB64)
            val sig = Base64.getDecoder().decode(signatureB64)
            val key = PublicKeyFactory.createKey(spki)
            if (key !is Ed25519PublicKeyParameters) return@runCatching false
            val verifier = Ed25519Signer()
            verifier.init(false, key)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(sig)
        }.getOrDefault(false)

    /**
     * Is [sig] a valid Ed25519 signature over the exact [manifestBytes]? Tamper-evidence only — the
     * identity question ("is the signer trusted?") is [evaluateTrust].
     */
    fun isManifestSignatureValid(manifestBytes: ByteArray, sig: AzpSignature): Boolean {
        if (!sig.alg.equals("ed25519", ignoreCase = true)) return false
        return ed25519Verify(sig.publicKey, manifestBytes, sig.signature)
    }

    /**
     * Evaluate a package's provenance from its verbatim `manifest.json` [manifestBytes] and optional
     * `signature.json` body against a [store]. A host MUST refuse a package whose result is
     * [SignatureStatus.INVALID]; the others are installable but carry differing provenance.
     */
    fun evaluate(manifestBytes: ByteArray, signatureJson: String?, store: TrustStore): SignatureStatus {
        val sig = parse(signatureJson)
        if (sig == null) {
            // Distinguish "no signature" from "a signature.json is present but won't parse". The
            // latter is tamper-evidence (truncated/corrupt), which a host MUST refuse — returning
            // UNSIGNED here would let a mangled signature install as if it were merely unsigned.
            return if (signatureJson.isNullOrBlank()) SignatureStatus.UNSIGNED else SignatureStatus.INVALID
        }
        if (!isManifestSignatureValid(manifestBytes, sig)) return SignatureStatus.INVALID
        return if (evaluateTrust(sig, store).trusted) SignatureStatus.SIGNED_TRUSTED
        else SignatureStatus.SIGNED_UNTRUSTED
    }
}

package com.hereliesaz.graffitixr.common.azphalt

import java.util.Base64

/**
 * The azphalt trust model (spec/package-format.md § Signing). A valid signature is *tamper-evidence*;
 * this is the *identity* decision. A host holds a [TrustStore] of trusted Ed25519 public keys; a
 * package is **trusted** when either its signer key is directly in the store, or its signer key was
 * counter-signed by a **registry** key in the store — transitive trust, so a host can trust one
 * registry instead of every author. Key distribution is out-of-band by design; this only enforces the
 * cryptography. Mirrors `@azphalt/azp`'s `verifyTrust`.
 */

/** A public key a host trusts (base64 SPKI), as a direct author key or a registry key. */
data class TrustedKey(val publicKey: String, val keyId: String? = null, val label: String? = null)

/** The set of Ed25519 public keys a host trusts. [EMPTY] establishes no provenance (signed-untrusted). */
data class TrustStore(val keys: List<TrustedKey> = emptyList()) {
    companion object {
        val EMPTY = TrustStore(emptyList())
    }
}

/** The outcome of a trust evaluation. */
data class TrustResult(
    val trusted: Boolean,
    val reason: String,
    val signerPublicKey: String? = null,
    /** When trust came transitively, the registry key that vouched for the signer. */
    val viaRegistryPublicKey: String? = null,
)

/** Ceiling on counter-signature chain length — a DoS guard against attacker-crafted deep chains. */
private const val MAX_CHAIN_DEPTH = 10

/**
 * Decide whether the signer of [sig] is trusted per [store]: directly (signer key in the store), or
 * transitively by walking the counter-signature chain — each hop's key vouches (signs) for the key
 * below it (the author at the base, then each previous counter-signer). Trusted as soon as a hop's key
 * is in the store, provided every hop's signature down to it verifies. Assumes [sig] has already been
 * confirmed internally valid (see [AzpSignatures.isManifestSignatureValid]).
 */
fun evaluateTrust(sig: AzpSignature, store: TrustStore): TrustResult {
    val trusted = store.keys.map { it.publicKey }.toHashSet()
    val signer = sig.publicKey

    // (a) Direct trust.
    if (trusted.contains(signer)) {
        return TrustResult(true, "signer key is directly trusted", signerPublicKey = signer)
    }

    // (b) Transitive trust: walk the counter-signature chain from the author up. Each link signs the
    // SPKI bytes of the key below it (`vouchedKey`), so the message to verify is that key's DER bytes.
    var vouchedKey = signer
    var cs = sig.countersignature
    var hop = 0
    while (cs != null) {
        hop++
        if (hop > MAX_CHAIN_DEPTH) {
            return TrustResult(false, "counter-signature chain exceeds the $MAX_CHAIN_DEPTH-hop limit", signerPublicKey = signer)
        }
        val message = runCatching { Base64.getDecoder().decode(vouchedKey) }.getOrNull()
            ?: return TrustResult(false, "counter-signature is malformed at hop $hop", signerPublicKey = signer)
        if (!AzpSignatures.ed25519Verify(cs.publicKey, message, cs.signature)) {
            return TrustResult(false, "counter-signature invalid at hop $hop", signerPublicKey = signer)
        }
        if (trusted.contains(cs.publicKey)) {
            return TrustResult(
                trusted = true,
                reason = if (hop == 1) "signer counter-signed by a trusted registry"
                else "signer trusted via a $hop-hop counter-signature chain",
                signerPublicKey = signer,
                viaRegistryPublicKey = cs.publicKey,
            )
        }
        vouchedKey = cs.publicKey
        cs = cs.countersignature
    }

    return TrustResult(
        trusted = false,
        reason = if (sig.countersignature != null) "counter-signature chain reaches no trusted key"
        else "signer key is not in the trust store",
        signerPublicKey = signer,
    )
}

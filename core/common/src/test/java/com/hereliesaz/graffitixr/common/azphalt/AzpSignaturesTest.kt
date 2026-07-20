package com.hereliesaz.graffitixr.common.azphalt

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Ed25519 signature + trust verification, checked against azphalt's **published cross-implementation
 * test vector** (`packages/azp/test/vectors/signature-vector.json`) so this host verifies a package
 * signed by the reference `@azphalt/azp` byte-for-byte, plus sign/verify round-trips and a
 * counter-signature (registry) trust chain built in-test.
 */
class AzpSignaturesTest {

    // ── azphalt signature-vector.json (verbatim) ───────────────────────────────────────────────
    private val vectorManifest: ByteArray = (
        "{\n" +
            "  \"azphalt\": \"0.1\",\n" +
            "  \"id\": \"com.azphalt.example.vector\",\n" +
            "  \"name\": \"Signature Vector\",\n" +
            "  \"version\": \"1.0.0\",\n" +
            "  \"kind\": \"asset\",\n" +
            "  \"license\": \"MIT\",\n" +
            "  \"compat\": \">=0.1\",\n" +
            "  \"files\": {\n" +
            "    \"assets/x.txt\": \"sha256-2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae\"\n" +
            "  }\n" +
            "}\n"
        ).toByteArray(Charsets.UTF_8)
    private val vectorPublicKey = "MCowBQYDK2VwAyEAWlUm8cHGqCDVs3Uzn9x7UeErLOyDdCTr0n+HvKjDZ4M="
    private val vectorSignature =
        "VzdxDliOyWWAJA8KZjoFHfdizyb36IdZnRVhtJbFS0ZLccQIJOLAHfTNODbh1CNKTDerZZhCCZrPh+BsZ95WAQ=="

    @Test
    fun `test-vector manifest bytes reproduce the published SHA-256`() {
        val hex = MessageDigest.getInstance("SHA-256").digest(vectorManifest)
            .joinToString("") { "%02x".format(it) }
        assertEquals("699b003ee9f44e8e6ed41ffc8fcb941010cc788d30df7f5d8cb7c36731e3d037", hex)
    }

    @Test
    fun `verifies the published azphalt signature vector`() {
        assertTrue(AzpSignatures.ed25519Verify(vectorPublicKey, vectorManifest, vectorSignature))
    }

    @Test
    fun `rejects the vector signature over tampered manifest bytes`() {
        val tampered = vectorManifest.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertFalse(AzpSignatures.ed25519Verify(vectorPublicKey, tampered, vectorSignature))
    }

    @Test
    fun `malformed inputs verify false instead of throwing`() {
        assertFalse(AzpSignatures.ed25519Verify("not-base64!!", vectorManifest, vectorSignature))
        assertFalse(AzpSignatures.ed25519Verify(vectorPublicKey, vectorManifest, "AAAA"))
        assertNull(AzpSignatures.parse(null))
        assertNull(AzpSignatures.parse("   "))
        assertNull(AzpSignatures.parse("{not json"))
    }

    @Test
    fun `evaluate maps the vector to a valid, untrusted-then-trusted signature`() {
        val sigJson = sigJson(vectorPublicKey, vectorSignature)
        assertEquals(SignatureStatus.UNSIGNED, AzpSignatures.evaluate(vectorManifest, null, TrustStore.EMPTY))
        assertEquals(
            SignatureStatus.SIGNED_UNTRUSTED,
            AzpSignatures.evaluate(vectorManifest, sigJson, TrustStore.EMPTY),
        )
        assertEquals(
            SignatureStatus.SIGNED_TRUSTED,
            AzpSignatures.evaluate(vectorManifest, sigJson, TrustStore(listOf(TrustedKey(vectorPublicKey)))),
        )
    }

    @Test
    fun `evaluate flags a present-but-invalid signature as INVALID`() {
        val tampered = vectorManifest.copyOf().also { it[3] = (it[3] + 1).toByte() }
        assertEquals(
            SignatureStatus.INVALID,
            AzpSignatures.evaluate(tampered, sigJson(vectorPublicKey, vectorSignature), TrustStore.EMPTY),
        )
    }

    @Test
    fun `evaluate flags a present-but-unparseable signature as INVALID, not UNSIGNED`() {
        // A signature.json that is present but corrupt/truncated is tamper-evidence — the host MUST
        // refuse it. Returning UNSIGNED here would let a mangled signature install unnoticed.
        assertEquals(
            SignatureStatus.INVALID,
            AzpSignatures.evaluate(vectorManifest, "{not valid json", TrustStore.EMPTY),
        )
        // A truly absent signature is still UNSIGNED (blank counts as absent).
        assertEquals(
            SignatureStatus.UNSIGNED,
            AzpSignatures.evaluate(vectorManifest, "   ", TrustStore.EMPTY),
        )
    }

    @Test
    fun `sign-then-verify round-trips with a freshly generated key`() {
        val (priv, pub) = genKeyPair()
        val msg = "manifest-bytes".toByteArray()
        val pubB64 = spkiB64(pub)
        val sigB64 = Base64.getEncoder().encodeToString(sign(priv, msg))

        assertTrue(AzpSignatures.ed25519Verify(pubB64, msg, sigB64))
        assertEquals(
            SignatureStatus.SIGNED_UNTRUSTED,
            AzpSignatures.evaluate(msg, sigJson(pubB64, sigB64), TrustStore.EMPTY),
        )
        assertEquals(
            SignatureStatus.SIGNED_TRUSTED,
            AzpSignatures.evaluate(msg, sigJson(pubB64, sigB64), TrustStore(listOf(TrustedKey(pubB64)))),
        )
    }

    @Test
    fun `trust is established via a one-hop registry counter-signature`() {
        val (authorPriv, authorPub) = genKeyPair()
        val (registryPriv, registryPub) = genKeyPair()
        val msg = "the-manifest".toByteArray()

        val authorPubB64 = spkiB64(authorPub)
        val registryPubB64 = spkiB64(registryPub)
        val authorSig = Base64.getEncoder().encodeToString(sign(authorPriv, msg))
        // The registry vouches for the author by signing the author's SPKI DER bytes.
        val authorSpkiDer = spkiDer(authorPub)
        val counterSig = Base64.getEncoder().encodeToString(sign(registryPriv, authorSpkiDer))

        val sigJson = sigJson(
            authorPubB64,
            authorSig,
            countersig = """{"publicKey":"$registryPubB64","signature":"$counterSig"}""",
        )

        // Registry trusted → transitive trust for the author.
        val viaRegistry = AzpSignatures.evaluate(msg, sigJson, TrustStore(listOf(TrustedKey(registryPubB64))))
        assertEquals(SignatureStatus.SIGNED_TRUSTED, viaRegistry)

        val sig = AzpSignatures.parse(sigJson)!!
        val result = evaluateTrust(sig, TrustStore(listOf(TrustedKey(registryPubB64))))
        assertTrue(result.trusted)
        assertEquals(registryPubB64, result.viaRegistryPublicKey)

        // Neither the author nor the registry trusted → untrusted, but still a valid signature.
        assertEquals(SignatureStatus.SIGNED_UNTRUSTED, AzpSignatures.evaluate(msg, sigJson, TrustStore.EMPTY))
        assertFalse(evaluateTrust(sig, TrustStore.EMPTY).trusted)
    }

    @Test
    fun `a broken counter-signature severs the chain`() {
        val (authorPriv, authorPub) = genKeyPair()
        val (_, registryPub) = genKeyPair()
        val msg = "m".toByteArray()

        val authorSig = Base64.getEncoder().encodeToString(sign(authorPriv, msg))
        // A counter-signature whose bytes are not a valid signature over the author's key.
        val bogus = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })
        val sigJson = sigJson(
            spkiB64(authorPub),
            authorSig,
            countersig = """{"publicKey":"${spkiB64(registryPub)}","signature":"$bogus"}""",
        )

        val result = evaluateTrust(AzpSignatures.parse(sigJson)!!, TrustStore(listOf(TrustedKey(spkiB64(registryPub)))))
        assertFalse(result.trusted)
        assertTrue(result.reason.contains("invalid at hop 1"))
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────

    /** Fixed 12-byte Ed25519 SPKI DER header; the raw 32-byte key follows (RFC 8410). */
    private val ed25519SpkiPrefix =
        intArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
            .map { it.toByte() }.toByteArray()

    private fun spkiDer(pub: Ed25519PublicKeyParameters): ByteArray = ed25519SpkiPrefix + pub.encoded
    private fun spkiB64(pub: Ed25519PublicKeyParameters): String =
        Base64.getEncoder().encodeToString(spkiDer(pub))

    private fun genKeyPair(): Pair<Ed25519PrivateKeyParameters, Ed25519PublicKeyParameters> {
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

    private fun sigJson(publicKey: String, signature: String, countersig: String? = null): String {
        val cs = countersig?.let { ""","countersignature":$it""" } ?: ""
        return """{"alg":"ed25519","publicKey":"$publicKey","signature":"$signature"$cs}"""
    }
}

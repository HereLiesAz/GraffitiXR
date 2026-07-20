package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the azphalt registry client (spec/repository-api.md): request URLs, response parsing, and the
 * mapping to catalog cards. Transport is faked, so this is a pure unit test.
 */
class RepositoryApiTest {

    /** Records the URLs requested and returns canned bodies keyed by a path fragment. */
    private class FakeHttp(private val bodies: Map<String, String>) {
        val requested = mutableListOf<String>()
        val posted = mutableListOf<Pair<String, String>>()
        fun get(url: String, @Suppress("UNUSED_PARAMETER") headers: Map<String, String>): String {
            requested += url
            return bodies.entries.firstOrNull { url.contains(it.key) }?.value
                ?: error("no fake body for $url")
        }
        fun post(
            url: String,
            @Suppress("UNUSED_PARAMETER") headers: Map<String, String>,
            body: String,
        ): String {
            posted += url to body
            return bodies.entries.firstOrNull { url.contains(it.key) }?.value
                ?: error("no fake body for $url")
        }
    }

    @Test
    fun `search parses the paginated response and hits the right URL`() {
        val fake = FakeHttp(mapOf(
            "/packages" to """
                {
                  "packages": [
                    { "id": "com.a.lut", "name": "A LUT", "author": "A", "version": "1.2.0",
                      "types": ["lut"], "tags": ["warm"], "priceStatus": "free" },
                    { "id": "com.b.filter", "name": "B Filter", "author": "B", "version": "2.0.0",
                      "types": ["code"], "priceStatus": "paid" }
                  ],
                  "total": 2, "page": 1, "pages": 1
                }
            """.trimIndent(),
        ))
        val client = RepositoryClient("https://reg.example/", fake::get)

        val resp = client.search(q = "grade", types = listOf("lut"), page = 1)

        assertEquals(2, resp.total)
        assertEquals(1, resp.pages)
        assertEquals("com.a.lut", resp.packages[0].id)
        assertTrue(resp.packages[1].isPaid)
        assertFalse(resp.packages[0].isPaid)
        // URL is built off the trimmed base with the query params.
        val url = fake.requested.single()
        assertTrue(url.startsWith("https://reg.example/packages?"))
        assertTrue(url.contains("q=grade"))
        assertTrue(url.contains("types=lut"))
        assertTrue(url.contains("page=1"))
    }

    @Test
    fun `search sends the new scoping and sort params`() {
        val fake = FakeHttp(mapOf(
            "/packages" to """{ "packages": [], "total": 0, "page": 1, "pages": 1 }""",
        ))
        val client = RepositoryClient("https://reg.example", fake::get)

        client.search(
            app = "com.hereliesaz.graffitixr",
            capabilities = listOf("bitmap", "layers"),
            mediaDomains = listOf("image"),
            sort = RepositorySort.RECENT,
        )

        val url = fake.requested.single()
        assertTrue(url.contains("app=com.hereliesaz.graffitixr"))
        assertTrue(url.contains("capabilities=bitmap%2Clayers"))
        assertTrue(url.contains("mediaDomains=image"))
        assertTrue(url.contains("sort=recent"))
    }

    @Test
    fun `discover parses auth, supported types, and signing keys`() {
        val fake = FakeHttp(mapOf(
            "azphalt-repository.json" to """
                {
                  "name": "Official SFX Library", "version": "0.1",
                  "description": "SFX for editors.",
                  "auth": { "type": "oauth2", "url": "https://sfx.example/oauth/authorize" },
                  "supportedTypes": ["audio", "lut", "transition"],
                  "profiles": ["video-audio"],
                  "signingKeys": [
                    { "publicKey": "MCowBQYDK2Vw", "keyId": "reg-2026", "label": "Official SFX Library" }
                  ]
                }
            """.trimIndent(),
        ))
        val client = RepositoryClient("https://reg.example", fake::get)

        val info = client.discover()

        assertEquals("oauth2", info.auth?.type)
        assertEquals(listOf("audio", "lut", "transition"), info.supportedTypes)
        assertEquals("reg-2026", info.signingKeys.single().keyId)
        assertTrue(fake.requested.single().endsWith("/.well-known/azphalt-repository.json"))
    }

    @Test
    fun `revocations parses the feed and flags an installed version`() {
        val fake = FakeHttp(mapOf(
            "/revocations" to """
                {
                  "revocations": [
                    { "id": "com.a.lut", "version": "1.2.0", "reason": "malicious", "revokedAt": "2026-07-18T00:00:00Z" }
                  ]
                }
            """.trimIndent(),
        ))
        val client = RepositoryClient("https://reg.example", fake::get)

        val feed = client.revocations(since = "2026-07-01T00:00:00Z")

        assertTrue(feed.isRevoked("com.a.lut", "1.2.0"))
        assertFalse(feed.isRevoked("com.a.lut", "1.3.0"))
        assertTrue(fake.requested.single().contains("since=2026-07-01"))
    }

    @Test
    fun `updates posts the installed set and parses available updates`() {
        val fake = FakeHttp(mapOf(
            "/updates" to """{ "updates": [ { "id": "com.a.lut", "latest": "1.3.0" } ] }""",
        ))
        val client = RepositoryClient("https://reg.example", fake::get, fake::post)

        val resp = client.updates(listOf(InstalledRef("com.a.lut", "1.2.0")))

        assertEquals("1.3.0", resp.updates.single().latest)
        val (url, body) = fake.posted.single()
        assertEquals("https://reg.example/updates", url)
        assertTrue(body.contains("com.a.lut"))
        assertTrue(body.contains("1.2.0"))
    }

    @Test
    fun `updates without a post transport fails clearly`() {
        val client = RepositoryClient("https://reg.example", { _, _ -> "" })
        val e = runCatching { client.updates(listOf(InstalledRef("x", "1.0.0"))) }.exceptionOrNull()
        assertTrue(e is UnsupportedOperationException)
    }

    @Test
    fun `resolveCatalog uses live results but falls back to the seed on error or empty`() {
        val live = listOf(
            RepositoryPackage("com.x.lut", "X", version = "1.0.0", types = listOf("lut"))
                .toMarketplaceEntry("https://reg.example/x"),
        )
        // Live, non-empty → shown as-is and flagged live.
        val ok = resolveCatalog(Result.success(live))
        assertTrue(ok.isLive)
        assertEquals(live, ok.entries)
        // Registry error → bundled seed, offline.
        val errored = resolveCatalog(Result.failure(RuntimeException("registry down")))
        assertFalse(errored.isLive)
        assertEquals(SEED_MARKETPLACE, errored.entries)
        // Empty page → also treated as offline fallback so browsing never dead-ends.
        val empty = resolveCatalog(Result.success(emptyList()))
        assertFalse(empty.isLive)
        assertEquals(SEED_MARKETPLACE, empty.entries)
    }

    @Test
    fun `latest version prefers the declared latest over history head`() {
        val withLatest = PackageDetail("id", "N", versions = listOf("1.1.0", "1.0.0"), latest = "1.2.0")
        val withoutLatest = PackageDetail("id", "N", versions = listOf("1.1.0", "1.0.0"))
        assertEquals("1.2.0", withLatest.latestVersion)
        assertEquals("1.1.0", withoutLatest.latestVersion)
    }

    @Test
    fun `download url is well-formed and encodes segments`() {
        val client = RepositoryClient("https://reg.example", { _, _ -> "" })
        assertEquals(
            "https://reg.example/packages/com.a.lut/versions/1.2.0/download",
            client.downloadUrl("com.a.lut", "1.2.0"),
        )
    }

    @Test
    fun `package maps to a catalog card with kind inferred from types`() {
        val asset = RepositoryPackage("com.a.lut", "A", version = "1.0.0", types = listOf("lut"))
        val code = RepositoryPackage("com.b.f", "B", version = "1.0.0", types = listOf("code"))
        val mixed = RepositoryPackage("com.c.m", "C", version = "1.0.0", types = listOf("code", "lut"))

        assertEquals(ExtensionKind.ASSET, asset.toMarketplaceEntry("u").kind)
        assertEquals(ExtensionKind.CODE, code.toMarketplaceEntry("u").kind)
        assertEquals(ExtensionKind.MIXED, mixed.toMarketplaceEntry("u").kind)

        val paidCard = RepositoryPackage("p", "P", version = "1.0.0", types = listOf("lut"), priceStatus = "paid")
            .toMarketplaceEntry("https://reg.example/packages/p/versions/1.0.0/download")
        assertEquals("Paid", paidCard.priceLabel)
        assertEquals("https://reg.example/packages/p/versions/1.0.0/download", paidCard.source)
        // Asset card is installable by this host; a pure-code one is not.
        assertTrue(asset.toMarketplaceEntry("u").installable)
        assertFalse(code.toMarketplaceEntry("u").installable)
    }
}
